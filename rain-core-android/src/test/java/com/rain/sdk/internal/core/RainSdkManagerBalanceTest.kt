package com.rain.sdk.internal.core

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.helpers.MockChainReader
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.internal.solana.Base58
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger

/**
 * Manager-contract tests for the rich balance API — mode guards and routing through the
 * active [com.rain.sdk.provider.WalletProvider]. Provider-specific success paths
 * live in `PortalWalletProviderTest` / `TurnkeyWalletProviderTest`.
 *
 * Note: an un-initialized manager surfaces every balance call as
 * [RainError.SdkNotInitialized] (via the `walletProvider ?: throw SdkNotInitialized()`
 * guard), not as `WalletUnavailable`.
 */
class RainSdkManagerBalanceTest {

    private val usdcBalance = Balance(
        token = Token.Contract(TestFixtures.USDC_ADDRESS),
        chainId = 1,
        rawAmount = BigInteger("100000000"),
        decimals = 6,
        symbol = "USDC",
        name = "USDC"
    )

    private val ethBalance = Balance(
        token = Token.Native,
        chainId = 1,
        rawAmount = BigInteger("1500000000000000000"),
        decimals = 18,
        symbol = "ETH",
        name = "Ether"
    )

    // ---- happy paths via the stub provider ----------------------------------------

    @Test
    fun `getBalance forwards chainId and token and returns provider result`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        stub.balanceToReturn = ethBalance

        val balance = manager.getBalance(chainId = 1, token = Token.Native)

        assertThat(balance).isEqualTo(ethBalance)
        assertThat(stub.getBalanceCalls).hasSize(1)
        val call = stub.getBalanceCalls.single()
        assertThat(call.chainId).isEqualTo(1)
        assertThat(call.token).isEqualTo(Token.Native)
    }

    @Test
    fun `getBalance forwards a contract token`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        stub.balanceToReturn = usdcBalance

        val token = Token.Contract(TestFixtures.USDC_ADDRESS)
        val balance = manager.getBalance(chainId = 1, token = token)

        assertThat(balance).isEqualTo(usdcBalance)
        assertThat(stub.getBalanceCalls.single().token).isEqualTo(token)
    }

    @Test
    fun `getBalances returns whatever the provider returned`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        stub.balancesToReturn = listOf(ethBalance, usdcBalance)

        val balances = manager.getTokenBalances(chainId = 1)

        assertThat(balances).containsExactly(ethBalance, usdcBalance).inOrder()
        assertThat(stub.getBalancesCalls).containsExactly(1)
    }

    // ---- error handling -----------------------------------------------------------

    @Test
    fun `getBalances wraps unexpected provider failures via ErrorMapper`() {
        val failing = object : StubWalletProvider() {
            override suspend fun getBalances(chainId: Int): List<Balance> {
                throw RuntimeException("indexer 503")
            }
        }
        val (manager, _) = TestManagers.stubProviderManager(failing)

        val ex = runCatching { runBlocking { manager.getTokenBalances(chainId = 1) } }.exceptionOrNull()
        // Generic RuntimeException → ProviderError per ErrorMapper.mapTransactionError.
        assertThat(ex).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `getBalance rethrows RainError WalletUnavailable without re-wrapping`() {
        val failing = object : StubWalletProvider() {
            override suspend fun getBalance(chainId: Int, token: Token): Balance {
                throw RainError.WalletUnavailable("no wallet")
            }
        }
        val (manager, _) = TestManagers.stubProviderManager(failing)
        assertThrows(RainError.WalletUnavailable::class.java) {
            runBlocking { manager.getBalance(chainId = 1, token = Token.Native) }
        }
    }

    // ---- registerTokens validation --------------------------------------------------

    @Test
    fun `registerTokens rejects a malformed EVM token address`() {
        // A malformed entry in the store rides into every balance batch on its chain, so it
        // must be refused at registration.
        val (manager, _) = TestManagers.stubProviderManager()

        assertThrows(RainError.InvalidConfig::class.java) {
            manager.registerTokensBlocking(
                listOf(TokenInfo(chainId = 1, address = "0x7f5c764c", symbol = "BAD", decimals = 6))
            )
        }
    }

    @Test
    fun `registerTokens rejects decimals outside the supported range`() {
        // No money path can scale by such a value, so the entry is refused at registration.
        val (manager, _) = TestManagers.stubProviderManager()

        for (decimals in listOf(78, -1)) {
            assertThrows("decimals=$decimals", RainError.InvalidConfig::class.java) {
                manager.registerTokensBlocking(
                    listOf(TokenInfo(chainId = 1, address = TestFixtures.TOKEN_ADDRESS, symbol = "BAD", decimals = decimals))
                )
            }
        }
    }

    @Test
    fun `registerTokens rejects a Solana mint that is not 32 bytes`() {
        val (manager, _) = TestManagers.stubProviderManager()

        assertThrows(RainError.InvalidConfig::class.java) {
            manager.registerTokensBlocking(
                listOf(
                    TokenInfo(
                        chainId = com.rain.sdk.RainChain.SOLANA_DEVNET,
                        address = Base58.encode(ByteArray(33)),
                        symbol = "BAD",
                        decimals = 6
                    )
                )
            )
        }
    }

    @Test
    fun `registerTokens accepts decimals 0 and 77 and stores them before it returns`() {
        val reader = MockChainReader(decimals = 99)
        val store = TokenMetadataStore(reader)
        val (manager, _) = TestManagers.stubProviderManager(tokenStore = store)
        val max = "0x00000000000000000000000000000000000000BB"

        manager.registerTokensBlocking(
            listOf(
                TokenInfo(chainId = 1, address = TestFixtures.TOKEN_ADDRESS, symbol = "ZERO", decimals = 0),
                TokenInfo(chainId = 1, address = max, symbol = "MAX", decimals = 77)
            )
        )

        // In place, not in the background: the store answers at once and never reads the chain for them.
        assertThat(runBlocking { store.decimalsOrNull(1, TestFixtures.TOKEN_ADDRESS) }).isEqualTo(0)
        assertThat(runBlocking { store.decimalsOrNull(1, max) }).isEqualTo(77)
        assertThat(reader.decimalsCalls).isEmpty()
    }

    @Test
    fun `registerTokens accepts valid EVM and base58 solana addresses`() {
        val (manager, _) = TestManagers.stubProviderManager()

        manager.registerTokensBlocking(
            listOf(
                TokenInfo(chainId = 1, address = TestFixtures.USDC_ADDRESS, symbol = "USDC", decimals = 6),
                TokenInfo(
                    chainId = com.rain.sdk.RainChain.SOLANA_DEVNET,
                    address = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU",
                    symbol = "USDC",
                    decimals = 6
                )
            )
        )
    }
}

/** `registerTokens` suspends; these tests check only its validation, so they call it blocking. */
private fun RainSdkManager.registerTokensBlocking(tokens: List<TokenInfo>) = runBlocking { registerTokens(tokens) }
