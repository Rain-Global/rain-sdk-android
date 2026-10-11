package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import com.turnkey.types.V1AssetBalance
import com.turnkey.types.V1HashFunction
import com.turnkey.types.V1PayloadEncoding
import com.turnkey.types.V1SignRawPayloadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger

class TurnkeyWalletProviderTest {

    /**
     * The Turnkey Kotlin SDK is published with class-file major version 68 (Java 24).
     * Skip Turnkey-dependent tests on JVMs older than 24 to avoid spurious
     * UnsupportedClassVersionError failures. Android production builds are unaffected
     * (R8/D8 dexes Turnkey's bytecode regardless of host JVM version).
     */
    @Before
    fun requireJdk24() = assumeJdk24()

    private fun makeProvider(
        turnkey: MockTurnkey = MockTurnkey(),
        walletAddressOverride: String? = null,
        rpcEndpoints: Map<Int, String> = mapOf(1 to "https://eth.example/rpc")
    ): TurnkeyWalletProvider = turnkeyWalletProvider(
        turnkey = turnkey,
        rpcEndpoints = rpcEndpoints,
        walletAddressOverride = walletAddressOverride,
        httpClient = OkHttpClient(),
        // Inject a mock reader so unknown-token enrichment never hits the network.
        chainReader = MockChainReader()
    )

    @Test
    fun `getAddress returns override when provided`() = runBlocking {
        val provider = makeProvider(walletAddressOverride = "0xOVERRIDE")
        assertThat(provider.getWalletAddress()).isEqualTo("0xOVERRIDE")
    }

    @Test
    fun `getAddress returns first ethereum account from wallets`() = runBlocking {
        val provider = makeProvider()
        assertThat(provider.getWalletAddress()).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
    }

    @Test
    fun `getAddress refreshes wallets when initial list has no ethereum account`() = runBlocking {
        val turnkey = MockTurnkey(wallets = emptyList())
        var refreshTriggered = false
        val withRefresh = object : TurnkeyContextProtocol by turnkey {
            override suspend fun refreshWallets() {
                refreshTriggered = true
                turnkey.wallets = listOf(MockTurnkey.defaultWallet())
            }
        }
        val provider = turnkeyWalletProvider(
            turnkey = withRefresh,
            rpcEndpoints = mapOf(1 to "https://eth.example/rpc"),
            walletAddressOverride = null,
            httpClient = OkHttpClient()
        )
        val addr = provider.getWalletAddress()
        assertThat(refreshTriggered).isTrue()
        assertThat(addr).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
    }

    @Test
    fun `getAddress throws WalletUnavailable when no ethereum wallet exists`() {
        val turnkey = MockTurnkey(wallets = emptyList())
        val provider = makeProvider(turnkey = turnkey)
        assertThrows(RainError.WalletUnavailable::class.java) {
            runBlocking { provider.getWalletAddress() }
        }
    }

    @Test
    fun `signTypedData passes EIP712 encoding and NO_OP hash through to turnkey`() = runBlocking {
        val turnkey = MockTurnkey()
        val provider = makeProvider(turnkey = turnkey)

        val typed = """{"types":{}}"""
        provider.signTypedData(chainId = 1, walletAddress = "0xabc", typedDataJson = typed)

        assertThat(turnkey.signRawPayloadCalls).hasSize(1)
        val call = turnkey.signRawPayloadCalls.single()
        assertThat(call.signWith).isEqualTo("0xabc")
        assertThat(call.payload).isEqualTo(typed)
        assertThat(call.encoding).isEqualTo(V1PayloadEncoding.PAYLOAD_ENCODING_EIP712)
        assertThat(call.hashFunction).isEqualTo(V1HashFunction.HASH_FUNCTION_NO_OP)
    }

    @Test
    fun `signTypedData signs on a chain Turnkey cannot broadcast on and sends nothing`() = runBlocking {
        // prepareWithdrawal on Avalanche is the host's own-RPC path: the signature comes back and no
        // send starts, however the adapter gates its sends.
        val turnkey = MockTurnkey()
        val provider = makeProvider(turnkey = turnkey)

        val signature = provider.signTypedData(chainId = 43114, walletAddress = "0xabc", typedDataJson = """{"types":{}}""")

        assertThat(signature).startsWith("0x")
        assertThat(turnkey.signRawPayloadCalls).hasSize(1)
        assertThat((turnkey.turnkeyClient as MockTurnkeyClient).ethSendTransactionCalls).isEmpty()
    }

    @Test
    fun `signTypedData formats signature as 0x-prefixed 65 bytes`() = runBlocking {
        val turnkey = MockTurnkey(
            mockSignature = V1SignRawPayloadResult(
                r = "1".repeat(64),
                s = "2".repeat(64),
                v = "1c" // 28 in hex
            )
        )
        val provider = makeProvider(turnkey = turnkey)

        val signature = provider.signTypedData(1, "0xabc", "{}")

        assertThat(signature).startsWith("0x")
        // 2 (prefix) + 64 (r) + 64 (s) + 2 (v) = 132
        assertThat(signature).hasLength(132)
        assertThat(signature.takeLast(2)).isEqualTo("1c")
    }

    @Test
    fun `signTypedData normalizes recovery id below 27 by adding 27`() = runBlocking {
        val turnkey = MockTurnkey(
            mockSignature = V1SignRawPayloadResult(
                r = "1".repeat(64),
                s = "2".repeat(64),
                v = "00" // raw 0 should become 27 (0x1b)
            )
        )
        val provider = makeProvider(turnkey = turnkey)

        val signature = provider.signTypedData(1, "0xabc", "{}")
        assertThat(signature.takeLast(2)).isEqualTo("1b")
    }

    @Test
    fun `getBalance native parses slip44 native asset from balances`() = runBlocking {
        val turnkey = MockTurnkey()
        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "1500000000000000000", // 1.5 ETH in wei
                    caip19 = "eip155:1/slip44:60",
                    decimals = 18L,
                    display = null,
                    name = "Ethereum",
                    symbol = "ETH"
                ),
                V1AssetBalance(
                    balance = "100000000",
                    caip19 = "eip155:1/erc20:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
                    decimals = 6L,
                    display = null,
                    name = "USDC",
                    symbol = "USDC"
                )
            )
        )
        turnkey.turnkeyClient = client
        val provider = makeProvider(turnkey = turnkey)

        val balance = provider.getBalance(chainId = 1, token = Token.Native)
        assertThat(balance.token).isEqualTo(Token.Native)
        assertThat(balance.rawAmount).isEqualTo(java.math.BigInteger("1500000000000000000"))
        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-9).of(1.5)
    }

    @Test
    fun `getBalances maps token addresses to balances and includes native`() = runBlocking {
        val turnkey = MockTurnkey()
        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "1500000000000000000",
                    caip19 = "eip155:1/slip44:60",
                    decimals = 18L,
                    display = null,
                    name = null,
                    symbol = null
                ),
                V1AssetBalance(
                    balance = "100500000",
                    caip19 = "eip155:1/erc20:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
                    decimals = 6L,
                    display = null,
                    name = null,
                    symbol = null
                ),
                V1AssetBalance(
                    balance = "2000000000000000000",
                    caip19 = "eip155:1/erc20:0x6b175474e89094c44da98b954eedeac495271d0f",
                    decimals = 18L,
                    display = null,
                    name = null,
                    symbol = null
                )
            )
        )
        turnkey.turnkeyClient = client
        val provider = makeProvider(turnkey = turnkey)

        val balances = provider.getBalances(chainId = 1)
        // Native + 2 ERC-20s.
        assertThat(balances).hasSize(3)
        assertThat(balances.any { it.token is Token.Native }).isTrue()

        val usdc = balances.single { it.token == Token.Contract("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48") }
        assertThat(usdc.decimalAmount.toDouble()).isWithin(1e-6).of(100.5)

        val dai = balances.single { it.token == Token.Contract("0x6b175474e89094c44da98b954eedeac495271d0f") }
        assertThat(dai.decimalAmount.toDouble()).isWithin(1e-9).of(2.0)
    }

    @Test
    fun `getTransactions filters by chainId, sorts DESC and applies limit_offset`() = runBlocking {
        val turnkey = MockTurnkey()
        val orgId = MockTurnkey.DEFAULT_ORG_ID
        val activities = (1..5).map { i ->
            MockTurnkey.makeActivity(
                id = "act-$i",
                from = "0xfrom",
                to = "0xto-$i",
                caip2 = "eip155:1",
                value = "0",
                data = "0x",
                sendTransactionStatusId = "sid-$i"
            ).copy(
                createdAt = com.turnkey.types.Externaldatav1Timestamp(
                    nanos = "0",
                    seconds = (1_700_000_000 + i).toString()
                )
            )
        } + MockTurnkey.makeActivity(
            id = "other-chain",
            from = "0xfrom",
            to = "0xto",
            caip2 = "eip155:43114",
            value = "0",
            data = "0x",
            sendTransactionStatusId = "ignored"
        )

        val client = MockTurnkeyClient(mockActivities = activities)
        turnkey.turnkeyClient = client
        val provider = makeProvider(turnkey = turnkey)

        val result = provider.getTransactions(
            chainId = 1,
            limit = 2,
            offset = 1,
            order = RainTransactionOrder.DESC
        )

        assertThat(result).hasSize(2)
        // DESC sort: newest first; act-5 has the largest seconds. With offset=1 we drop the newest,
        // so the visible window is act-4, act-3.
        assertThat(result[0].chainId).isEqualTo(1)
        assertThat(client.getActivitiesCalls).hasSize(1)
        assertThat(client.getActivitiesCalls.single().organizationId).isEqualTo(orgId)
    }

    @Test
    fun `getTransactions renders a large wei value exactly, not through Double`() = runBlocking {
        val turnkey = MockTurnkey()
        // 12345678901234567890 wei — above 2^53, so a Double round-trip would corrupt it.
        val activities = listOf(
            MockTurnkey.makeActivity(
                id = "act-1",
                from = "0xfrom",
                to = "0xto",
                caip2 = "eip155:1",
                value = "12345678901234567890",
                data = "0x",
                sendTransactionStatusId = "sid-1"
            )
        )
        turnkey.turnkeyClient = MockTurnkeyClient(mockActivities = activities)
        val provider = makeProvider(turnkey = turnkey)

        val result = provider.getTransactions(chainId = 1, limit = null, offset = null, order = null)

        assertThat(result).hasSize(1)
        assertThat(result[0].value!!.compareTo(BigDecimal("12.34567890123456789"))).isEqualTo(0)
    }

    @Test
    fun `sendTransaction throws TokenExpired when session missing`() {
        val turnkey = MockTurnkey(session = null)
        val provider = makeProvider(turnkey = turnkey)
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                provider.sendTransaction(
                    chainId = 1,
                    from = "0xabc",
                    to = "0xdef",
                    data = "0x",
                    value = "0x0"
                )
            }
        }
    }

    @Test
    fun `getAddress override applies to the ethereum address only`() = runBlocking {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        val provider = makeProvider(turnkey = turnkey, walletAddressOverride = "0xOVERRIDE")

        assertThat(provider.getWalletAddress()).isEqualTo("0xOVERRIDE")
        assertThat(provider.getWalletAddress(RainChain.SOLANA_DEVNET))
            .isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
    }

    @Test
    fun `getBalances fills decimals symbol and name from the token store when Turnkey omits them`() =
        runBlocking {
            val turnkey = MockTurnkey()
            turnkey.turnkeyClient = MockTurnkeyClient(
                mockBalances = listOf(
                    V1AssetBalance(
                        balance = "1000000",
                        caip19 = "eip155:1/erc20:${TurnkeyTestFixtures.TOKEN_ADDRESS}",
                        decimals = null,
                        display = null,
                        name = null,
                        symbol = null
                    )
                )
            )
            val reader = MockChainReader(decimals = 6, symbol = "MOCK", name = "Mock Token")
            val provider = turnkeyWalletProvider(
                turnkey = turnkey,
                rpcEndpoints = mapOf(1 to "https://eth.example/rpc"),
                httpClient = OkHttpClient(),
                chainReader = reader
            )

            val token = provider.getBalances(chainId = 1)
                .single { it.token == Token.Contract(TurnkeyTestFixtures.TOKEN_ADDRESS) }

            assertThat(token.decimals).isEqualTo(6)
            assertThat(token.symbol).isEqualTo("MOCK")
            assertThat(token.name).isEqualTo("Mock Token")
        }

    // ---------- registry and registered tokens the backend leaves out ----------

    @Test
    fun `getBalances on a backend chain adds the registry and registered tokens the backend left out, read from the chain alongside it`() =
        runBlocking {
            val registered = TokenInfo(1, TurnkeyTestFixtures.TOKEN_ADDRESS, "MOCK", 6, "Mock Token")
            val pyusd = "0x6c3ea9036406852006290770BEdFcAbA0e23A0e8" // in the built-in registry for chain 1
            val turnkey = MockTurnkey()
            turnkey.turnkeyClient = MockTurnkeyClient(
                mockBalances = listOf(
                    asset("eip155:1/slip44:60", "1000000000000000000", decimals = 18L),
                    // The backend lists USDC in lowercase; the registry spells it checksummed.
                    asset("eip155:1/erc20:${TurnkeyTestFixtures.USDC_ADDRESS}", "100500000", decimals = 6L)
                )
            )
            val reader = MockChainReader(
                balances = listOf(
                    Balance(Token.Native, 1, BigInteger("7"), 18, "ETH", "Ether"),
                    // The chain answers for USDC too, in the registry's checksummed spelling; the backend's lowercase row wins.
                    Balance(Token.Contract(USDC_CHECKSUMMED), 1, BigInteger.ONE, 6, "USDC", "USDC"),
                    Balance(Token.Contract(TurnkeyTestFixtures.TOKEN_ADDRESS), 1, BigInteger("2500000"), 6, "MOCK", "Mock Token"),
                    Balance(Token.Contract(pyusd), 1, BigInteger.ZERO, 6, "PYUSD", "PayPal USD")
                )
            )
            val provider = turnkeyWalletProvider(
                turnkey = turnkey,
                rpcEndpoints = mapOf(1 to "https://eth.example/rpc"),
                httpClient = OkHttpClient(),
                chainReader = reader,
                tokenStore = TokenMetadataStore(reader, seedTokens = listOf(registered))
            )

            val balances = provider.getBalances(chainId = 1)

            // The backend's rows, then the registered token only the chain answered for; the chain's
            // USDC and native rows lose to the backend's, and the zero PYUSD row is dropped.
            assertThat(balances.map { it.token }).containsExactly(
                Token.Native,
                Token.Contract(TurnkeyTestFixtures.USDC_ADDRESS),
                Token.Contract(TurnkeyTestFixtures.TOKEN_ADDRESS)
            ).inOrder()
            assertThat(balances.single { it.token is Token.Native }.rawAmount).isEqualTo(BigInteger("1000000000000000000"))
            assertThat(balances[1].rawAmount).isEqualTo(BigInteger("100500000"))
            assertThat(balances.last().decimalAmount).isEqualToIgnoringScale(BigDecimal("2.5"))

            // One batch, alongside the backend call, carrying every registry and registered token.
            val batch = reader.balancesCalls.single()
            assertThat(batch.walletAddress).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
            assertThat(batch.tokens.map { it.address.lowercase() }).contains(TurnkeyTestFixtures.USDC_ADDRESS)
            assertThat(batch.tokens.map { it.address }).contains(TurnkeyTestFixtures.TOKEN_ADDRESS)
            assertThat(batch.tokens.map { it.address }).contains(pyusd)
        }

    @Test
    fun `getBalances does not read the chain when nothing is registered for the chain`() =
        runBlocking {
            // Polygon Amoy is a backend chain with no built-in registry entry and no host registration.
            val amoy = 80002
            val turnkey = MockTurnkey()
            turnkey.turnkeyClient = MockTurnkeyClient(
                mockBalances = listOf(
                    asset("eip155:$amoy/slip44:60", "5", decimals = 18L),
                    asset("eip155:$amoy/erc20:${TurnkeyTestFixtures.TOKEN_ADDRESS}", "9", decimals = 6L, symbol = "MOCK")
                )
            )
            val reader = MockChainReader()
            val provider = turnkeyWalletProvider(
                turnkey = turnkey,
                rpcEndpoints = mapOf(amoy to "https://amoy.example/rpc"),
                httpClient = OkHttpClient(),
                chainReader = reader
            )

            val balances = provider.getBalances(chainId = amoy)

            assertThat(balances.map { it.token }).containsExactly(
                Token.Native,
                Token.Contract(TurnkeyTestFixtures.TOKEN_ADDRESS)
            ).inOrder()
            assertThat(reader.balancesCalls).isEmpty()
        }

    private fun backendWithUsdc() = MockTurnkeyClient(
        mockBalances = listOf(
            asset("eip155:1/slip44:60", "1000000000000000000", decimals = 18L),
            asset("eip155:1/erc20:${TurnkeyTestFixtures.USDC_ADDRESS}", "100500000", decimals = 6L)
        )
    )

    private fun registryProvider(
        turnkey: MockTurnkey,
        reader: MockChainReader,
        rpcEndpoints: Map<Int, String> = mapOf(1 to "https://eth.example/rpc"),
        registryReadTimeoutMs: Long = TurnkeyWalletProvider.REGISTRY_READ_TIMEOUT_MS
    ) = turnkeyWalletProvider(
        turnkey = turnkey,
        rpcEndpoints = rpcEndpoints,
        httpClient = OkHttpClient(),
        chainReader = reader,
        registryReadTimeoutMs = registryReadTimeoutMs
    )

    @Test
    fun `a failed chain read of the registry tokens keeps the backend's rows and warns once per chain`() {
        val turnkey = MockTurnkey()
        turnkey.turnkeyClient = backendWithUsdc()
        val reader = MockChainReader(balancesError = RainError.NetworkError("rate limited"))
        val provider = registryProvider(turnkey, reader)

        lateinit var first: List<Balance>
        val warnings = capturedLogs(android.util.Log.WARN) {
            runBlocking {
                first = provider.getBalances(chainId = 1)
                provider.getBalances(chainId = 1)
            }
        }

        // The backend's rows survive the failed extra read; the host hears about it once, not per poll.
        assertThat(first.map { it.token }).containsExactly(Token.Native, Token.Contract(TurnkeyTestFixtures.USDC_ADDRESS)).inOrder()
        assertThat(reader.balancesCalls).hasSize(2)
        assertThat(warnings.single()).contains("not read from the chain for chainId=1 (read failed)")
    }

    @Test
    fun `a chain read that fails with something other than a RainError is best effort too`() = runBlocking {
        val turnkey = MockTurnkey()
        turnkey.turnkeyClient = backendWithUsdc()
        val reader = MockChainReader(balancesError = IllegalStateException("not JSON-RPC"))
        val provider = registryProvider(turnkey, reader)

        val balances = provider.getBalances(chainId = 1)

        assertThat(balances.map { it.token }).containsExactly(Token.Native, Token.Contract(TurnkeyTestFixtures.USDC_ADDRESS)).inOrder()
    }

    @Test
    fun `a chain read that outlasts the registry timeout keeps the backend's rows and warns`() {
        val turnkey = MockTurnkey()
        turnkey.turnkeyClient = backendWithUsdc()
        // The gate is never completed: an RPC that never answers.
        val reader = MockChainReader(balancesGate = CompletableDeferred())
        val provider = registryProvider(turnkey, reader, registryReadTimeoutMs = 50)

        lateinit var balances: List<Balance>
        val warnings = capturedLogs(android.util.Log.WARN) {
            runBlocking { balances = provider.getBalances(chainId = 1) }
        }

        assertThat(balances.map { it.token }).containsExactly(Token.Native, Token.Contract(TurnkeyTestFixtures.USDC_ADDRESS)).inOrder()
        assertThat(warnings.single()).contains("no answer within 50 ms")
    }

    @Test
    fun `a chain without an RPC endpoint is not read, and the host is told once per chain`() {
        val turnkey = MockTurnkey()
        turnkey.turnkeyClient = backendWithUsdc()
        val reader = MockChainReader()
        // Chain 1 has registry tokens but no endpoint in this build; the backend still answers for it.
        val provider = registryProvider(turnkey, reader, rpcEndpoints = mapOf(80002 to "https://amoy.example/rpc"))

        lateinit var balances: List<Balance>
        val entries = capturedLogs(android.util.Log.INFO) {
            runBlocking {
                balances = provider.getBalances(chainId = 1)
                provider.getBalances(chainId = 1)
            }
        }

        assertThat(balances.map { it.token }).containsExactly(Token.Native, Token.Contract(TurnkeyTestFixtures.USDC_ADDRESS)).inOrder()
        assertThat(reader.balancesCalls).isEmpty()
        assertThat(entries.filter { it.contains("no RPC endpoint for chainId=1") }).hasSize(1)
    }

    @Test
    fun `a backend failure cancels the chain read in flight instead of waiting for it`() = runBlocking {
        val turnkey = MockTurnkey()
        val client = MockTurnkeyClient()
        client.walletAddressBalancesGate = CompletableDeferred()
        client.walletAddressBalancesError = RuntimeException("backend down")
        turnkey.turnkeyClient = client
        // The chain read never answers on its own; only cancellation can end it.
        val reader = MockChainReader(balancesGate = CompletableDeferred())
        val provider = registryProvider(turnkey, reader)

        // supervisorScope: the failing child must reach await() instead of failing the test's own scope.
        supervisorScope {
            val read = async { provider.getBalances(chainId = 1) }
            while (reader.balancesCalls.isEmpty() || client.walletAddressBalanceCalls.isEmpty()) yield()
            // Both reads are in flight. Let the backend fail.
            client.walletAddressBalancesGate?.complete(Unit)

            expectThrows<RainError.ProviderError> { read.await() }
        }
        // The chain read never answered on its own: the backend failure cancelled it.
        assertThat(reader.balancesGate?.isActive).isTrue()
    }

    @Test
    fun `a caller cancelled with the chain read in flight is cancelled, and the backend's rows are not returned`() = runBlocking<Unit> {
        val turnkey = MockTurnkey()
        turnkey.turnkeyClient = backendWithUsdc()
        val reader = MockChainReader(balancesGate = CompletableDeferred())
        val provider = registryProvider(turnkey, reader)

        val read = async { provider.getBalances(chainId = 1) }
        while (reader.balancesCalls.isEmpty()) yield()
        read.cancelAndJoin()

        assertThat(read.isCancelled).isTrue()
        expectThrows<CancellationException> { read.await() }
    }

    private fun capturedLogs(minPriority: Int, block: () -> Unit): List<String> {
        val messages = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority >= minPriority) messages += message
            }
        }
        Timber.plant(tree)
        try {
            block()
        } finally {
            Timber.uproot(tree)
        }
        return messages
    }

    private companion object {
        /** Chain-1 USDC as the built-in registry spells it; the fixture address is the same token in lowercase. */
        const val USDC_CHECKSUMMED = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
    }

    private fun asset(
        caip19: String,
        balance: String,
        decimals: Long?,
        symbol: String? = null,
        name: String? = null
    ) = V1AssetBalance(balance = balance, caip19 = caip19, decimals = decimals, display = null, name = name, symbol = symbol)
}
