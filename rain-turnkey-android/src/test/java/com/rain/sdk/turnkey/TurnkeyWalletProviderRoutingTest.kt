package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.turnkey.types.V1AssetBalance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigInteger

/**
 * Verifies the chain-routing decision in [TurnkeyWalletProvider]:
 *  - Chains in [TurnkeyBroadcastChains.BALANCE_API_CHAIN_IDS] go
 *    through Turnkey's `get_wallet_address_balances`.
 *  - Anything else falls through to the injected [com.rain.sdk.internal.network.chainreader.ChainReader].
 *  - `getERC20Balance` is delegated to the ChainReader unconditionally — same RPC call
 *    everywhere, no reason to maintain two implementations.
 *  - A backend that refuses its balance service falls back to the ChainReader: on the feature gate
 *    (HTTP 403) for both reads, on any other refused status for the native read only; every other
 *    failure surfaces.
 */
class TurnkeyWalletProviderRoutingTest {

    @Before
    fun requireJdk24() = assumeJdk24()

    private fun makeProvider(
        chainReader: MockChainReader,
        chainId: Int = 1
    ): TurnkeyWalletProvider {
        val turnkey = MockTurnkey()
        // The MockTurnkeyClient on `turnkey` returns no balances by default — fine for chain
        // ID 1 since we override behavior via the chainReader only when off-allowlist.
        return turnkeyWalletProvider(
            turnkey = turnkey,
            rpcEndpoints = mapOf(chainId to "https://eth.example/rpc"),
            walletAddressOverride = MockTurnkey.DEFAULT_WALLET_ADDRESS,
            httpClient = OkHttpClient(),
            chainReader = chainReader
        )
    }

    // ---------- supported-chain path ----------

    @Test
    fun `getBalance native on Ethereum 1 routes through Turnkey balances, not ChainReader`() = runBlocking {
        val chainReader = MockChainReader()
        val turnkey = MockTurnkey()
        turnkey.turnkeyClient = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "500000000000000000", // 0.5 ETH
                    caip19 = "eip155:1/slip44:60",
                    decimals = 18L,
                    display = null,
                    name = "Ethereum",
                    symbol = "ETH"
                )
            )
        )
        val provider = turnkeyWalletProvider(
            turnkey = turnkey,
            rpcEndpoints = mapOf(1 to "https://eth.example/rpc"),
            walletAddressOverride = MockTurnkey.DEFAULT_WALLET_ADDRESS,
            httpClient = OkHttpClient(),
            chainReader = chainReader
        )

        val balance = provider.getBalance(chainId = 1, token = Token.Native)

        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-9).of(0.5)
        // Turnkey-supported chain shouldn't have touched the ChainReader at all.
        assertThat(chainReader.balanceCalls).isEmpty()
    }

    // ---------- unsupported-chain fallback ----------

    @Test
    fun `getBalance native on Avalanche Fuji 43113 routes through ChainReader`() = runBlocking {
        val chainReader = MockChainReader(
            balance = Balance(Token.Native, 43113, BigInteger("2500000000000000000"), 18, "AVAX", "Avalanche")
        )
        val provider = makeProvider(chainReader, chainId = 43113)

        val balance = provider.getBalance(chainId = 43113, token = Token.Native)

        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-9).of(2.5)
        assertThat(chainReader.balanceCalls).hasSize(1)
        val call = chainReader.balanceCalls.single()
        assertThat(call.chainId).isEqualTo(43113)
        assertThat(call.token).isEqualTo(Token.Native)
    }

    @Test
    fun `getBalances on unsupported chain delegates to ChainReader and filters zero balances`() = runBlocking {
        val usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        val zero = "0x6b175474e89094c44da98b954eedeac495271d0f"
        val chainReader = MockChainReader(
            balances = listOf(
                Balance(Token.Native, 43113, BigInteger("1000000000000000000"), 18, "AVAX", "Avalanche"),
                Balance(Token.Contract(usdc), 43113, BigInteger("100000000"), 6, "USDC", "USDC"),
                Balance(Token.Contract(zero), 43113, BigInteger.ZERO, 18, "ZERO", "Zero") // filtered out
            )
        )
        val provider = makeProvider(chainReader, chainId = 43113)

        val balances = provider.getBalances(chainId = 43113)

        // Native always kept; zero-balance contract dropped.
        assertThat(balances.map { it.token })
            .containsExactly(Token.Native, Token.Contract(usdc))
        assertThat(chainReader.balancesCalls).hasSize(1)
    }

    // ---------- unconditional delegation ----------

    @Test
    fun `getBalance contract always delegates to ChainReader, even on Turnkey-supported chains`() = runBlocking {
        val usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        val chainReader = MockChainReader(
            balance = Balance(Token.Contract(usdc), 1, BigInteger("42000000"), 6, "USDC", "USDC")
        )
        val provider = makeProvider(chainReader, chainId = 1)

        val balance = provider.getBalance(chainId = 1, token = Token.Contract(usdc))

        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-9).of(42.0)
        assertThat(chainReader.balanceCalls).hasSize(1)
        val call = chainReader.balanceCalls.single()
        assertThat(call.chainId).isEqualTo(1)
        assertThat(call.token).isEqualTo(Token.Contract(usdc))
    }

    // ---------- address cache ----------

    @Test
    fun `getAddress is cached after first resolve and skips wallet refresh on subsequent calls`() = runBlocking {
        val turnkey = MockTurnkey()
        var refreshCount = 0
        val cached = object : TurnkeyContextProtocol by turnkey {
            override suspend fun refreshWallets() {
                refreshCount++
                turnkey.wallets = listOf(MockTurnkey.defaultWallet())
            }
        }
        val provider = turnkeyWalletProvider(
            turnkey = cached,
            rpcEndpoints = mapOf(1 to "https://eth.example/rpc"),
            httpClient = OkHttpClient(),
            chainReader = MockChainReader()
        )

        val first = provider.getWalletAddress()
        val second = provider.getWalletAddress()
        val third = provider.getWalletAddress()

        assertThat(first).isEqualTo(second)
        assertThat(second).isEqualTo(third)
        // wallets already contained a usable address, so refresh shouldn't have run at all.
        assertThat(refreshCount).isEqualTo(0)
    }

    @Test
    fun `a Turnkey balances failure that is not a refusal surfaces as ProviderError`() {
        val chainReader = MockChainReader()
        val turnkey = MockTurnkey()
        (turnkey.turnkeyClient as MockTurnkeyClient).walletAddressBalancesError =
            RuntimeException("balances unavailable")
        val provider = turnkeyWalletProvider(
            turnkey = turnkey,
            rpcEndpoints = mapOf(1 to "https://eth.example/rpc"),
            walletAddressOverride = MockTurnkey.DEFAULT_WALLET_ADDRESS,
            httpClient = OkHttpClient(),
            chainReader = chainReader
        )

        val single = assertThrows(RainError::class.java) {
            runBlocking { provider.getBalance(chainId = 1, token = Token.Native) }
        }
        val all = assertThrows(RainError::class.java) {
            runBlocking { provider.getBalances(chainId = 1) }
        }

        // A failure that is not a refusal by the backend (no HTTP status in it) is reported, not
        // papered over with a node read; the refusal cases follow below. The native read has no chain
        // leg; the list's registry read runs alongside the backend call and is cancelled when that
        // fails, so whether it reached the reader is scheduling, not contract, and not asserted.
        assertThat(single).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(all).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(chainReader.balanceCalls).isEmpty()
    }

    // ---------- a backend that refuses its balance service ----------

    private fun refusingProvider(turnkey: MockTurnkey, chainReader: MockChainReader) = turnkeyWalletProvider(
        turnkey = turnkey,
        rpcEndpoints = mapOf(1 to "https://eth.example/rpc"),
        walletAddressOverride = MockTurnkey.DEFAULT_WALLET_ADDRESS,
        httpClient = OkHttpClient(),
        chainReader = chainReader,
        // The coordinator's retry delays are real under runBlocking; the retry count is what matters here.
        sessionCoordinator = TurnkeySessionCoordinator(turnkey = turnkey, retryDelay = { })
    )

    @Test
    fun `a 403 from the balance service falls back to the chain, logs the gate once and stops asking the backend`() {
        val native = Balance(Token.Native, 1, BigInteger("5"), 18, "ETH", "Ether")
        val chainReader = MockChainReader(
            balance = native,
            balances = listOf(
                native,
                Balance(Token.Contract(TurnkeyTestFixtures.USDC_ADDRESS), 1, BigInteger("1000000"), 6, "USDC", "USDC"),
                Balance(Token.Contract(TurnkeyTestFixtures.TOKEN_ADDRESS), 1, BigInteger.ZERO, 6, "MOCK", "Mock Token")
            )
        )
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.walletAddressBalancesError = MockTurnkey.httpError(BALANCES_PATH, 403)
        val provider = refusingProvider(turnkey, chainReader)

        lateinit var single: Balance
        lateinit var all: List<Balance>
        val entries = capturingLogs {
            runBlocking {
                single = provider.getBalance(chainId = 1, token = Token.Native)
                all = provider.getBalances(chainId = 1)
            }
        }

        assertThat(single.rawAmount).isEqualTo(BigInteger("5"))
        assertThat(chainReader.balanceCalls.single().token).isEqualTo(Token.Native)
        // The chain read carries the registry tokens and drops the zero row, as on an unsupported chain.
        assertThat(all.map { it.token })
            .containsExactly(Token.Native, Token.Contract(TurnkeyTestFixtures.USDC_ADDRESS))
            .inOrder()
        assertThat(chainReader.balancesCalls.single().tokens.map { it.address.lowercase() })
            .contains(TurnkeyTestFixtures.USDC_ADDRESS)
        // The gate is an organization setting: asked once, refused once, never asked again by this provider.
        assertThat(client.walletAddressBalanceCalls).hasSize(1)
        val gate = entries.filter { it.second.contains("balance service is not enabled") }
        assertThat(gate).hasSize(1)
        assertThat(gate.single().first).isEqualTo(android.util.Log.INFO)
        assertThat(entries.none { it.second.contains("reading the chain instead") }).isTrue()
    }

    @Test
    fun `on the feature gate a registry batch that gave up is read again strictly, and that failure surfaces`() {
        val chainReader = MockChainReader(balancesError = RainError.NetworkError("rpc down"))
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.walletAddressBalancesError = MockTurnkey.httpError(BALANCES_PATH, 403)
        val provider = refusingProvider(turnkey, chainReader)

        val entries = capturingLogs {
            // The batch alongside the backend call gives up; the gate is found; the chain is the only
            // source, so it is read again the strict way and its failure is the answer, not an empty list.
            assertThrows(RainError.NetworkError::class.java) { runBlocking { provider.getBalances(chainId = 1) } }
            // With the gate known, no batch is started: one strict read, same failure.
            assertThrows(RainError.NetworkError::class.java) { runBlocking { provider.getBalances(chainId = 1) } }
        }

        assertThat(chainReader.balancesCalls).hasSize(3)
        assertThat(client.walletAddressBalanceCalls).hasSize(1)
        assertThat(entries.filter { it.second.contains("not read from the chain for chainId=1 (read failed)") }).hasSize(1)
        assertThat(entries.filter { it.second.contains("balance service is not enabled") }).hasSize(1)
    }

    @Test
    fun `a 5xx from the balance service is retried, then the chain answers the native read and the list surfaces`() {
        val chainReader = MockChainReader()
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.walletAddressBalancesError = MockTurnkey.httpError(BALANCES_PATH, 503)
        val provider = refusingProvider(turnkey, chainReader)

        lateinit var listFailure: RainError
        val entries = capturingLogs {
            runBlocking {
                provider.getBalance(chainId = 1, token = Token.Native)
                listFailure = expectThrows<RainError.ProviderError> { provider.getBalances(chainId = 1) }
            }
        }

        // The default policy retries a transient status twice before the coordinator gives up, per read;
        // a 5xx is not the feature gate, so the second read asks the backend again.
        assertThat(client.walletAddressBalanceCalls).hasSize(6)
        // The native balance is the same number from either source, so the chain answers it, with a warning.
        assertThat(chainReader.balanceCalls).hasSize(1)
        assertThat(entries.count { it.first == android.util.Log.WARN && it.second.contains("reading the chain instead") }).isEqualTo(1)
        // The token list is not, so the refusal surfaces for the host to retry, status attached, and the chain is not read.
        assertThat(listFailure.cause?.let(TurnkeyErrorMapping::turnkeyHttpStatus)).isEqualTo(503)
        assertThat(chainReader.balancesCalls).isEmpty()
        assertThat(entries.none { it.second.contains("balance service is not enabled") }).isTrue()
    }

    @Test
    fun `a 400 from the balance service is a refusal for the native read only, and the list surfaces it`() {
        val chainReader = MockChainReader()
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.walletAddressBalancesError = MockTurnkey.httpError(BALANCES_PATH, 400)
        val provider = refusingProvider(turnkey, chainReader)

        val entries = capturingLogs {
            runBlocking {
                provider.getBalance(chainId = 1, token = Token.Native)
                expectThrows<RainError.ProviderError> { provider.getBalances(chainId = 1) }
            }
        }

        // Not transient, not the gate: one call per read, no retry, and the next read asks again.
        assertThat(client.walletAddressBalanceCalls).hasSize(2)
        assertThat(chainReader.balanceCalls).hasSize(1)
        assertThat(chainReader.balancesCalls).isEmpty()
        assertThat(entries.count { it.first == android.util.Log.WARN && it.second.contains("reading the chain instead") }).isEqualTo(1)
    }

    @Test
    fun `without an RPC endpoint a refused native read surfaces the refusal, except on the feature gate`() {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.walletAddressBalancesError = MockTurnkey.httpError(BALANCES_PATH, 503)
        // No reader double: the real EVM reader with no endpoints, so the node read fails as RAIN_102.
        val provider = turnkeyWalletProvider(
            turnkey = turnkey,
            rpcEndpoints = emptyMap(),
            walletAddressOverride = MockTurnkey.DEFAULT_WALLET_ADDRESS,
            httpClient = OkHttpClient(),
            sessionCoordinator = TurnkeySessionCoordinator(turnkey = turnkey, retryDelay = { })
        )

        // A backend outage is not a setup error: the refusal surfaces, status attached.
        val outage = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { provider.getBalance(chainId = 1, token = Token.Native) }
        }
        assertThat(outage.cause?.let(TurnkeyErrorMapping::turnkeyHttpStatus)).isEqualTo(503)

        // The gate is a setup matter: the missing endpoint is the error to fix.
        client.walletAddressBalancesError = MockTurnkey.httpError(BALANCES_PATH, 403)
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { provider.getBalance(chainId = 1, token = Token.Native) }
        }
    }

    @Test
    fun `a transport failure on the balance service surfaces without reading the chain`() {
        val chainReader = MockChainReader()
        val turnkey = MockTurnkey()
        (turnkey.turnkeyClient as MockTurnkeyClient).walletAddressBalancesError = IOException("connection reset")
        val provider = refusingProvider(turnkey, chainReader)

        val single = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { provider.getBalance(chainId = 1, token = Token.Native) }
        }
        val all = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { provider.getBalances(chainId = 1) }
        }

        assertThat(single.cause).isInstanceOf(IOException::class.java)
        assertThat(all.cause).isInstanceOf(IOException::class.java)
        assertThat(chainReader.balanceCalls).isEmpty()
        assertThat(chainReader.balancesCalls).isEmpty()
    }

    @Test
    fun `a missing session throws TokenExpired without reading the chain`() {
        val chainReader = MockChainReader()
        val provider = refusingProvider(MockTurnkey(session = null), chainReader)

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { provider.getBalance(chainId = 1, token = Token.Native) }
        }
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { provider.getBalances(chainId = 1) }
        }
        assertThat(chainReader.balanceCalls).isEmpty()
        assertThat(chainReader.balancesCalls).isEmpty()
    }

    @Test
    fun `a caller cancelled during the backend read is cancelled, and the chain is not consulted`() = runBlocking {
        val chainReader = MockChainReader()
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.walletAddressBalancesGate = CompletableDeferred()
        val provider = refusingProvider(turnkey, chainReader)

        val read = async { provider.getBalances(chainId = 1) }
        while (client.walletAddressBalanceCalls.isEmpty()) yield()
        read.cancelAndJoin()

        // isCancelled is also true for a job that failed; awaiting it tells cancellation from failure.
        assertThat(read.isCancelled).isTrue()
        expectThrows<CancellationException> { read.await() }
        assertThat(chainReader.balancesCalls).isEmpty()
    }

    private companion object {
        const val BALANCES_PATH = "/public/v1/query/get_wallet_address_balances"
    }
}
