package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.network.chainreader.JsonRpcClient
import com.rain.sdk.internal.solana.SolanaRpcClient
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.internal.utils.validateAndChecksumAddress
import com.rain.sdk.models.Token
import com.turnkey.types.TListEthTransactionHistoryResponse
import com.turnkey.types.V1AssetBalance
import com.turnkey.types.V1EthTransactionHistoryItem
import com.turnkey.types.V1TransactionHistoryBlock
import com.turnkey.types.V1TransactionHistoryFee
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.lang.ref.WeakReference
import java.math.BigInteger

/**
 * Pins what [TurnkeyManager] owns, through a real [TurnkeySessionCoordinator] over the Turnkey mock,
 * the way `PrivyManagerTest` drives a real `PrivyManager`. Routing and fallback decisions are the
 * provider's and stay in its suites.
 */
class TurnkeyManagerTest {

    private companion object {
        const val GC_ATTEMPTS = 20
    }

    private lateinit var rpc: MockRpcServer
    private val devnet = RainChain.SOLANA_DEVNET
    private val devnetCaip2 = SolanaChains.caip2(devnet)
    private val from = MockTurnkey.DEFAULT_WALLET_ADDRESS
    private val to = TurnkeyTestFixtures.RECIPIENT_ADDRESS

    /** Unknown to the registry, so enrichment comes from the reader behind the store. */
    private val tokenStore = TokenMetadataStore(MockChainReader(decimals = 6, symbol = "MOCK", name = "Mock Token"))

    @Before
    fun setUp() {
        assumeJdk24()
        rpc = MockRpcServer().also { it.start() }
    }

    @After
    fun tearDown() {
        if (::rpc.isInitialized) rpc.shutdown()
    }

    private fun manager(
        turnkey: MockTurnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana())),
        sponsorGas: Boolean = false,
    ): TurnkeyManager {
        val httpClient = OkHttpClient()
        val jsonRpcClient = JsonRpcClient(httpClient)
        return TurnkeyManager(
            turnkey = turnkey,
            rpcEndpoints = mapOf(1 to rpc.urlFor(1), devnet to rpc.urlFor(devnet)),
            solanaRpcClient = SolanaRpcClient(jsonRpcClient),
            sponsorGas = sponsorGas,
            httpClient = httpClient,
            pollingIntervalMs = 0L,
            jsonRpcClient = jsonRpcClient,
            sessionCoordinator = TurnkeySessionCoordinator(turnkey = turnkey, retryDelay = { }),
        )
    }

    private fun clientOf(turnkey: MockTurnkey) = turnkey.turnkeyClient as MockTurnkeyClient

    private fun asset(caip19: String, balance: String, decimals: Long?, symbol: String?, name: String?) =
        V1AssetBalance(
            balance = balance,
            caip19 = caip19,
            decimals = decimals,
            display = null,
            name = name,
            symbol = symbol
        )

    // ---------- sends ----------

    @Test
    fun `sendEvmTransaction with sponsorGas sends a sponsored body carrying the gas-station nonce`() = runBlocking {
        val turnkey = MockTurnkey()
        val client = clientOf(turnkey)
        client.mockGasStationNonce = "7"

        val hash = manager(turnkey, sponsorGas = true).sendEvmTransaction(1, from, to, "0x", "0x0")

        val body = client.ethSendTransactionCalls.single()
        assertThat(body.sponsor).isTrue()
        assertThat(body.gasStationNonce).isEqualTo("7")
        assertThat(body.nonce).isNull()
        assertThat(body.gasLimit).isNull()
        assertThat(body.maxFeePerGas).isNull()
        assertThat(client.getNoncesCalls.single().gasStationNonce).isTrue()
        assertThat(hash).isEqualTo(client.mockTransactionHash)
    }

    @Test
    fun `sendEvmTransaction self-paid quotes nonce and gas from the chain and buffers the gas limit by a fifth`() =
        runBlocking {
            rpc.stub(method = "eth_getTransactionCount", result = "0x5")
            rpc.stub(method = "eth_estimateGas", result = "0x5208") // 21000
            rpc.stub(method = "eth_gasPrice", result = "0x3b9aca00") // 1 gwei
            val turnkey = MockTurnkey()
            val client = clientOf(turnkey)

            manager(turnkey, sponsorGas = false).sendEvmTransaction(1, from, to, "0x", "0x0")

            val body = client.ethSendTransactionCalls.single()
            assertThat(body.sponsor).isFalse()
            assertThat(body.nonce).isEqualTo("5")
            assertThat(body.gasLimit).isEqualTo("25200")
            assertThat(body.maxFeePerGas).isEqualTo("1000000000")
            assertThat(body.maxPriorityFeePerGas).isEqualTo("1000000000")
            assertThat(client.getNoncesCalls).isEmpty()
        }

    @Test
    fun `sendEvmTransaction polls the status until the hash appears`() = runBlocking {
        val turnkey = MockTurnkey()
        val client = clientOf(turnkey)
        val expected = "0x" + "9".repeat(64)
        client.sendTransactionStatusQueue = mutableListOf(
            MockTurnkeyClient.StatusFixture.pending(),
            MockTurnkeyClient.StatusFixture.pending(),
            MockTurnkeyClient.StatusFixture.broadcasted(expected)
        )

        val hash = manager(turnkey, sponsorGas = true).sendEvmTransaction(1, from, to, "0x", "0x0")

        assertThat(hash).isEqualTo(expected)
        assertThat(client.sendTransactionStatusCalls).hasSize(3)
    }

    @Test
    fun `sendEvmTransaction lets a cancellation during the status poll leave as itself, not as TransactionPending`() {
        val turnkey = MockTurnkey()
        val client = clientOf(turnkey)
        val cancelled = CancellationException("caller left")
        client.sendTransactionStatusError = cancelled

        val error = assertThrows(CancellationException::class.java) {
            runBlocking { manager(turnkey, sponsorGas = true).sendEvmTransaction(1, from, to, "0x", "0x0") }
        }

        // Turnkey accepted the send, but a cancelled caller is not a pending send: the coordinator and
        // the poll both rethrow cancellation ahead of the TransactionPending fallback.
        assertThat(error).isSameInstanceAs(cancelled)
        assertThat(client.ethSendTransactionCalls).hasSize(1)
        assertThat(client.sendTransactionStatusCalls).hasSize(1)
    }

    // ---------- EVM balances ----------

    @Test
    fun `evmNativeBalance reads Turnkey's native row and takes decimals from the token store when the row has none`() =
        runBlocking {
            val turnkey = MockTurnkey()
            turnkey.turnkeyClient = MockTurnkeyClient(
                mockBalances = listOf(
                    asset("eip155:1/slip44:60", "1500000000000000000", decimals = null, symbol = null, name = null)
                )
            )

            val balance = manager(turnkey).evmNativeBalance(1, from, tokenStore)

            assertThat(balance.token).isEqualTo(Token.Native)
            assertThat(balance.rawAmount).isEqualTo(BigInteger("1500000000000000000"))
            assertThat(balance.decimals).isEqualTo(18)
            assertThat(balance.symbol).isEqualTo("ETH")
        }

    @Test
    fun `evmBalances keeps native, drops zero rows and enriches sparse ones from the token store`() = runBlocking {
        val zeroToken = "0x1111111111111111111111111111111111111111"
        val sparse = TurnkeyTestFixtures.TOKEN_ADDRESS
        val turnkey = MockTurnkey()
        turnkey.turnkeyClient = MockTurnkeyClient(
            mockBalances = listOf(
                asset("eip155:1/slip44:60", "1000000000000000000", decimals = 18L, symbol = "ETH", name = "Ethereum"),
                asset("eip155:1/erc20:$zeroToken", "0", decimals = 18L, symbol = "ZERO", name = null),
                asset("eip155:1/erc20:$sparse", "2500000", decimals = null, symbol = null, name = null)
            )
        )

        val balances = manager(turnkey).evmBalances(1, from, tokenStore)

        val tokens = balances.map { it.token }
        assertThat(tokens).containsExactly(Token.Native, Token.Contract(sparse)).inOrder()
        val token = balances.last()
        assertThat(token.rawAmount).isEqualTo(BigInteger("2500000"))
        assertThat(token.decimals).isEqualTo(6)
        assertThat(token.symbol).isEqualTo("MOCK")
        assertThat(token.name).isEqualTo("Mock Token")
    }

    // ---------- Solana balances ----------

    @Test
    fun `solanaBalanceOrNull answers native SOL from an empty list and null for a mint Turnkey does not list`() =
        runBlocking {
            val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
            turnkey.turnkeyClient = MockTurnkeyClient(mockBalances = emptyList())
            val manager = manager(turnkey)
            val owner = MockTurnkey.DEFAULT_SOLANA_ADDRESS

            val native = requireNotNull(manager.solanaBalanceOrNull(devnet, owner, Token.Native, tokenStore))
            assertThat(native.rawAmount).isEqualTo(BigInteger.ZERO)
            assertThat(native.decimals).isEqualTo(9)

            val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
            assertThat(manager.solanaBalanceOrNull(devnet, owner, Token.Contract(mint), tokenStore)).isNull()
        }

    @Test
    fun `solanaBalancesOrNull is null without an SPL row and the shaped list with one`() = runBlocking {
        val owner = MockTurnkey.DEFAULT_SOLANA_ADDRESS
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val native = asset("$devnetCaip2/slip44:501", "2500000000", decimals = 9L, symbol = "SOL", name = "Solana")
        val nativeOnly = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        nativeOnly.turnkeyClient = MockTurnkeyClient(mockBalances = listOf(native))

        assertThat(manager(nativeOnly).solanaBalancesOrNull(devnet, owner, tokenStore)).isNull()

        val withSpl = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        withSpl.turnkeyClient = MockTurnkeyClient(
            mockBalances = listOf(
                native,
                asset("$devnetCaip2/token:$mint", "20000000", decimals = 6L, symbol = "USDC", name = "USD Coin")
            )
        )

        val balances = requireNotNull(manager(withSpl).solanaBalancesOrNull(devnet, owner, tokenStore))

        assertThat(balances.map { it.token }).containsExactly(Token.Native, Token.Contract(mint)).inOrder()
        assertThat(balances.first().rawAmount).isEqualTo(BigInteger("2500000000"))
        assertThat(balances.last().decimals).isEqualTo(6)
        assertThat(balances.last().symbol).isEqualTo("USDC")
    }

    @Test
    fun `solanaBalancesOrNull is null when the Turnkey read fails and rethrows a dead session`() {
        val owner = MockTurnkey.DEFAULT_SOLANA_ADDRESS
        val failing = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        clientOf(failing).walletAddressBalancesError = RuntimeException("balances unavailable")

        val onFailure = runBlocking { manager(failing).solanaBalancesOrNull(devnet, owner, tokenStore) }
        assertThat(onFailure).isNull()

        val dead =
            MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = MockTurnkey.expiredSession())
        dead.refreshSessionError = RuntimeException("refresh rejected")

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { manager(dead).solanaBalancesOrNull(devnet, owner, tokenStore) }
        }
    }

    // ---------- history ----------

    private fun ethRow(hash: String, timestamp: String) = V1EthTransactionHistoryItem(
        block = V1TransactionHistoryBlock(hash = "0xblock", number = "1", timestamp = timestamp),
        fee = V1TransactionHistoryFee(amount = "21000000000000", caip19 = "eip155:1/slip44:60"),
        from = from,
        origin = "TRANSACTION_ORIGIN_EXTERNAL",
        status = "CONFIRMED",
        to = to,
        transactionHash = hash,
        transfers = emptyList()
    )

    @Test
    fun `indexedEvmTransactions maps history rows for the wallet's address and returns them newest first`(): Unit = runBlocking {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        val client = clientOf(turnkey)
        client.mockEthHistory = TListEthTransactionHistoryResponse(
            transactions = listOf(ethRow("0xold", "2026-08-12T10:00:00Z"), ethRow("0xnew", "2026-08-13T10:00:00Z"))
        )

        val transactions = manager(turnkey).indexedEvmTransactions(1, limit = 10, offset = 0, order = null)

        assertThat(transactions.map { it.hash }).containsExactly("0xnew", "0xold").inOrder()
        assertThat(transactions.first().from).isEqualTo(from)
        assertThat(transactions.first().to).isEqualTo(validateAndChecksumAddress(to, "to"))
        val request = client.listEthHistoryCalls.single()
        assertThat(request.organizationId).isEqualTo(MockTurnkey.DEFAULT_ORG_ID)
        assertThat(request.address).isEqualTo(from)
        assertThat(request.caip2).isEqualTo("eip155:1")
        assertThat(request.paginationOptions?.limit).isEqualTo("10")
    }

    @Test
    fun `getEvmTransactionsFromActivities keeps the chain's rows and resolves each hash from its status id`() = runBlocking {
        val turnkey = MockTurnkey()
        val client = clientOf(turnkey)
        client.mockActivities = listOf(
            MockTurnkey.makeActivity(
                id = "a1",
                from = from,
                to = to,
                caip2 = "eip155:1",
                value = null,
                data = "0x",
                sendTransactionStatusId = "status-1"
            ),
            MockTurnkey.makeActivity(
                id = "a2",
                from = from,
                to = to,
                caip2 = "eip155:137",
                value = null,
                data = "0x",
                sendTransactionStatusId = "status-2"
            )
        )

        val transactions = manager(turnkey).getEvmTransactionsFromActivities(1, limit = 10, offset = 0, order = null)

        assertThat(transactions.map { it.uniqueId }).containsExactly("a1")
        assertThat(transactions.single().hash).isEqualTo(client.mockTransactionHash)
        assertThat(client.sendTransactionStatusCalls.single().sendTransactionStatusId).isEqualTo("status-1")
    }

    // ---------- lifecycle ----------

    @Test
    @Suppress("ExplicitGarbageCollectionCall") // the assertion is collectability itself
    fun `a discarded manager leaves nothing registered on the coordinator it shared`() {
        val turnkey = MockTurnkey()
        val coordinator = TurnkeySessionCoordinator(turnkey = turnkey, retryDelay = { })
        val discarded = resolveAndDrop(turnkey, coordinator)

        // Only the coordinator outlives the manager here. The callback list the manager used to
        // register on kept every discarded manager reachable for the coordinator's lifetime; the
        // death count leaves nothing behind, so the reference clears.
        var attempts = 0
        while (discarded.get() != null && attempts < GC_ATTEMPTS) {
            System.gc()
            attempts++
        }
        assertThat(discarded.get()).isNull()
        assertThat(coordinator.deathEpoch).isEqualTo(0)
    }

    /** Builds a manager over [coordinator], resolves through it and hands back only a weak handle. */
    private fun resolveAndDrop(
        turnkey: MockTurnkey,
        coordinator: TurnkeySessionCoordinator
    ): WeakReference<TurnkeyManager> {
        val manager = TurnkeyManager(
            turnkey = turnkey,
            rpcEndpoints = emptyMap(),
            solanaRpcClient = SolanaRpcClient(JsonRpcClient()),
            sponsorGas = false,
            sessionCoordinator = coordinator,
        )
        runBlocking { manager.getAddress() }
        return WeakReference(manager)
    }

    // ---------- error boundary ----------

    @Test
    fun `a raw vendor failure leaves a manager read as ProviderError carrying the cause`() {
        val turnkey = MockTurnkey()
        clientOf(turnkey).walletAddressBalancesError = RuntimeException("upstream unavailable")

        val error = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { manager(turnkey).evmNativeBalance(1, from, tokenStore) }
        }

        assertThat(error).hasCauseThat().hasMessageThat().isEqualTo("upstream unavailable")
    }
}
