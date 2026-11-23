package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.models.Token
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Session-hardening behavior through the wallet provider: expiry checks, refresh-on-401,
 * and typed session death on every Turnkey-backed path.
 */
class TurnkeyWalletProviderSessionTest {

    @Before
    fun requireJdk24() = assumeJdk24()

    private fun makeProvider(
        turnkey: MockTurnkey,
        policy: TurnkeySessionPolicy = TurnkeySessionPolicy(),
        onSessionExpired: (() -> Unit)? = null,
        solanaChainReader: MockChainReader? = null,
    ): TurnkeyWalletProvider = turnkeyWalletProvider(
        turnkey = turnkey,
        rpcEndpoints = mapOf(1 to "https://eth.example/rpc"),
        httpClient = OkHttpClient(),
        chainReader = MockChainReader(),
        solanaChainReader = solanaChainReader,
        sessionCoordinator = TurnkeySessionCoordinator(
            turnkey = turnkey,
            policy = policy,
            onSessionExpired = onSessionExpired,
            retryDelay = { /* no real sleeping in tests */ },
        )
    )

    /** The wallet provider under test plus the coordinator driving its session, for eviction tests. */
    private fun providerWithCoordinator(
        turnkey: MockTurnkey,
    ): Pair<TurnkeyWalletProvider, TurnkeySessionCoordinator> {
        val coordinator = TurnkeySessionCoordinator(turnkey = turnkey, retryDelay = { })
        return turnkeyWalletProvider(
            turnkey = turnkey,
            rpcEndpoints = mapOf(1 to "https://eth.example/rpc"),
            httpClient = OkHttpClient(),
            chainReader = MockChainReader(),
            sessionCoordinator = coordinator,
        ) to coordinator
    }

    @Test
    fun `session death evicts the cached address so a re-login cannot reuse it`() = runBlocking {
        val turnkey = MockTurnkey(wallets = emptyList())
        turnkey.onRefreshWallets = { turnkey.wallets = listOf(MockTurnkey.defaultWallet()) }
        val (provider, coordinator) = providerWithCoordinator(turnkey)

        assertThat(provider.getWalletAddress()).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)

        // The session dies for good.
        turnkey.session = null
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.refreshNow() }
        }

        // A different user logs in against the same Turnkey singleton.
        val other = "0x9999999999999999999999999999999999999999"
        turnkey.wallets = listOf(MockTurnkey.walletWithEthereumAddress(other))
        turnkey.session = MockTurnkey.defaultSession()

        assertThat(provider.getWalletAddress()).isEqualTo(other)
    }

    @Test
    fun `an address resolved across a session death is not written back into the cache`() = runBlocking {
        val turnkey = MockTurnkey(wallets = emptyList())
        val (provider, coordinator) = providerWithCoordinator(turnkey)

        val stale = "0x1111111111111111111111111111111111111111"
        turnkey.onRefreshWallets = {
            turnkey.wallets = listOf(MockTurnkey.walletWithEthereumAddress(stale))
            // The session dies after the wallets are fetched but before the address is cached.
            turnkey.session = null
            runCatching { coordinator.refreshNow() }
        }

        assertThat(provider.getWalletAddress()).isEqualTo(stale)

        // Not cached: the next call re-resolves and sees the new user's address.
        val fresh = "0x2222222222222222222222222222222222222222"
        turnkey.wallets = listOf(MockTurnkey.walletWithEthereumAddress(fresh))
        assertThat(provider.getWalletAddress()).isEqualTo(fresh)
    }

    @Test
    fun `a solana address resolved across a session death is not written back into the cache`() = runBlocking {
        val turnkey = MockTurnkey(wallets = emptyList())
        val (provider, coordinator) = providerWithCoordinator(turnkey)

        val stale = MockTurnkey.DEFAULT_SOLANA_ADDRESS
        turnkey.onRefreshWallets = {
            turnkey.wallets = listOf(MockTurnkey.walletWithEthAndSolana(solanaAddress = stale))
            // The session dies after the wallets are fetched but before the address is cached.
            turnkey.session = null
            runCatching { coordinator.refreshNow() }
        }

        assertThat(provider.getWalletAddress(RainChain.SOLANA_DEVNET)).isEqualTo(stale)

        // Not cached: the next call re-resolves and sees the new user's Solana account.
        val fresh = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        turnkey.wallets = listOf(MockTurnkey.walletWithEthAndSolana(solanaAddress = fresh))
        assertThat(provider.getWalletAddress(RainChain.SOLANA_DEVNET)).isEqualTo(fresh)
    }

    @Test
    fun `sendTransaction with an expired session and failing refresh throws TokenExpired`() {
        val turnkey = MockTurnkey(session = MockTurnkey.expiredSession())
        turnkey.refreshSessionError = RuntimeException("refresh rejected")
        var hookCalls = 0
        val provider = makeProvider(turnkey, onSessionExpired = { hookCalls++ })

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                provider.sendTransaction(1, "0xfrom", "0xto", "0x", "0x0")
            }
        }
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        assertThat(client.ethSendTransactionCalls).isEmpty()
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `getBalance refreshes on 401 and retries the balances read`() = runBlocking {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.walletAddressBalancesError = RuntimeException("HTTP error from /balances: 401")
        turnkey.onRefreshSession = {
            turnkey.session = MockTurnkey.defaultSession()
            client.walletAddressBalancesError = null
        }
        val provider = makeProvider(turnkey)

        val balance = provider.getBalance(chainId = 1, token = Token.Native)

        assertThat(balance.chainId).isEqualTo(1)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
        assertThat(client.walletAddressBalanceCalls).hasSize(2)
    }

    @Test
    fun `getTransactions surfaces TokenExpired from indexed history without the activity fallback`() {
        val turnkey = MockTurnkey()
        turnkey.refreshSessionError = RuntimeException("refresh rejected")
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.listEthHistoryError = MockTurnkey.httpError(MockTurnkey.ETH_HISTORY_PATH, 401)
        val provider = makeProvider(turnkey)

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { provider.getTransactions(chainId = 1, limit = 10, offset = 0, order = null) }
        }
        assertThat(client.getActivitiesCalls).isEmpty()
    }

    @Test
    fun `getTransactions falls back to activities when indexed history is feature-gated`() =
        runBlocking {
            // The mock client's default history answer is the vendor's HTTP 403 for a feature-gated org.
            val turnkey = MockTurnkey()
            val provider = makeProvider(turnkey)

            val transactions =
                provider.getTransactions(chainId = 1, limit = 10, offset = 0, order = null)

            assertThat(transactions).isEmpty()
            val client = turnkey.turnkeyClient as MockTurnkeyClient
            assertThat(client.getActivitiesCalls).hasSize(1)
        }

    @Test
    fun `getWalletAddress with expired session and no cached wallets throws TokenExpired`() {
        val turnkey = MockTurnkey(wallets = emptyList(), session = MockTurnkey.expiredSession())
        turnkey.refreshSessionError = RuntimeException("refresh rejected")
        val provider = makeProvider(turnkey)

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { provider.getWalletAddress() }
        }
        assertThat(turnkey.refreshWalletsCallCount).isEqualTo(0)
    }

    @Test
    fun `expired session on sends is refreshed transparently when refresh succeeds`() = runBlocking {
        val turnkey = MockTurnkey(session = MockTurnkey.expiredSession())
        turnkey.onRefreshSession = { turnkey.session = MockTurnkey.defaultSession() }
        val provider = makeProvider(turnkey)

        // signTypedData exercises the write path without needing RPC stubs.
        val signature = provider.signTypedData(1, "0xabc", "{}")

        assertThat(signature).startsWith("0x")
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
        assertThat(turnkey.signRawPayloadCalls).hasSize(1)
    }

    @Test
    fun `session death evicts the cached solana address so a re-login cannot reuse it`() = runBlocking {
        // Empty at first so the resolution runs through the coordinator, as the EVM case above does.
        val turnkey = MockTurnkey(wallets = emptyList())
        turnkey.onRefreshWallets = { turnkey.wallets = listOf(MockTurnkey.walletWithEthAndSolana()) }
        val (provider, coordinator) = providerWithCoordinator(turnkey)

        assertThat(provider.getWalletAddress(RainChain.SOLANA_DEVNET))
            .isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)

        // The session dies for good.
        turnkey.session = null
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.refreshNow() }
        }

        // A different user logs in against the same Turnkey singleton.
        val other = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        turnkey.wallets = listOf(MockTurnkey.walletWithEthAndSolana(solanaAddress = other))
        turnkey.session = MockTurnkey.defaultSession()

        assertThat(provider.getWalletAddress(RainChain.SOLANA_DEVNET)).isEqualTo(other)
    }

    @Test
    fun `getBalance and getBalances on solana surface TokenExpired without the node fallback`() {
        val turnkey = MockTurnkey(
            wallets = listOf(MockTurnkey.walletWithEthAndSolana()),
            session = MockTurnkey.expiredSession()
        )
        turnkey.refreshSessionError = RuntimeException("refresh rejected")
        val solanaReader = MockChainReader()
        val provider = makeProvider(turnkey, solanaChainReader = solanaReader)

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { provider.getBalance(RainChain.SOLANA_DEVNET, Token.Native) }
        }
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { provider.getBalances(RainChain.SOLANA_DEVNET) }
        }
        // A dead session surfaces as such; the node never stands in for it.
        assertThat(solanaReader.balanceCalls).isEmpty()
        assertThat(solanaReader.balancesCalls).isEmpty()
    }

    @Test
    fun `two providers over one coordinator both drop their cached addresses when the session dies`() = runBlocking {
        val turnkey = MockTurnkey(wallets = emptyList())
        turnkey.onRefreshWallets = { turnkey.wallets = listOf(MockTurnkey.defaultWallet()) }
        val coordinator = TurnkeySessionCoordinator(turnkey = turnkey, retryDelay = { })
        val providers = List(2) {
            turnkeyWalletProvider(
                turnkey = turnkey,
                rpcEndpoints = mapOf(1 to "https://eth.example/rpc"),
                httpClient = OkHttpClient(),
                chainReader = MockChainReader(),
                sessionCoordinator = coordinator,
            )
        }
        providers.forEach { assertThat(it.getWalletAddress()).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS) }

        turnkey.session = null
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.refreshNow() }
        }
        val other = "0x9999999999999999999999999999999999999999"
        turnkey.wallets = listOf(MockTurnkey.walletWithEthereumAddress(other))
        turnkey.session = MockTurnkey.defaultSession()

        // Nothing registers with the coordinator, so no provider depends on another's lifetime.
        providers.forEach { assertThat(it.getWalletAddress()).isEqualTo(other) }
    }
}
