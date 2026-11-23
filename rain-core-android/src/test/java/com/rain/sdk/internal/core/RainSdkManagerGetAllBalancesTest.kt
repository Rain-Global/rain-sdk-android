package com.rain.sdk.internal.core

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger

/**
 * Tests for `RainSdkManager.getAllBalances` — fan-out across configured chains flattened
 * into one list, per-chain failure isolation, and the `reset()` lifecycle hook.
 */
class RainSdkManagerGetAllBalancesTest {

    private fun nativeBalance(chainId: Int) = Balance(
        token = Token.Native,
        chainId = chainId,
        rawAmount = BigInteger("1500000000000000000"),
        decimals = 18,
        symbol = "ETH",
        name = "Ether"
    )

    private fun usdcBalance(chainId: Int) = Balance(
        token = Token.Contract(TestFixtures.USDC_ADDRESS),
        chainId = chainId,
        rawAmount = BigInteger("100000000"),
        decimals = 6,
        symbol = "USDC",
        name = "USDC"
    )

    @Test
    fun `getAllBalances returns empty list when no chains were configured`(): Unit = runBlocking {
        // No rpcEndpoints → configuredChainIds is empty, so getAllBalances short-circuits.
        val (manager, _) = TestManagers.stubProviderManager(rpcEndpoints = emptyMap())
        assertThat(manager.getAllBalances()).isEmpty()
    }

    @Test
    fun `getAllBalances tolerates per-chain failures and flattens healthy chains`(): Unit = runBlocking {
        val failingChain = 137
        val workingChain = 43114
        val stub = object : StubWalletProvider() {
            override suspend fun getBalances(chainId: Int): List<Balance> = when (chainId) {
                workingChain -> listOf(nativeBalance(workingChain), usdcBalance(workingChain))
                else -> throw RuntimeException("indexer down for $chainId")
            }
        }
        // Configure the chain IDs via rpcEndpoints (their keys become configuredChainIds).
        val (manager, _) = TestManagers.stubProviderManager(
            stub,
            rpcEndpoints = mapOf(
                workingChain to "https://rpc.test",
                failingChain to "https://rpc.test"
            )
        )

        val balances = manager.getAllBalances()

        // The failing chain contributes nothing; only the working chain's balances survive.
        assertThat(balances).containsExactly(nativeBalance(workingChain), usdcBalance(workingChain))
        assertThat(balances.map { it.chainId }.toSet()).containsExactly(workingChain)
    }

    @Test
    fun `getAllBalances surfaces a dead session instead of returning an empty list`(): Unit =
        runBlocking {
            val stub = object : StubWalletProvider() {
                override suspend fun getBalances(chainId: Int): List<Balance> =
                    throw RainError.TokenExpired()
            }
            val (manager, _) = TestManagers.stubProviderManager(
                stub,
                rpcEndpoints = mapOf(1 to "https://rpc.test", 137 to "https://rpc.test")
            )

            // A dead wallet session affects every chain identically; an empty list here would
            // read as zero balances rather than as "re-authenticate".
            assertThrows(RainError.TokenExpired::class.java) {
                runBlocking { manager.getAllBalances() }
            }
        }

    @Test
    fun `reset leaves the client usable and the chain configuration intact`(): Unit = runBlocking {
        val stub = object : StubWalletProvider() {
            override suspend fun getBalances(chainId: Int): List<Balance> =
                listOf(nativeBalance(chainId))
        }
        val (manager, _) = TestManagers.stubProviderManager(
            stub,
            rpcEndpoints = mapOf(1 to "https://rpc.test", 137 to "https://rpc.test")
        )

        manager.reset()

        // The chain configuration is owned by the RainSdk and shared with every other resolved
        // client, so one client resetting must not deconfigure it.
        assertThat(manager.isInitialized).isTrue()
        assertThat(manager.getWalletAddress()).isEqualTo(stub.addressToReturn)
        assertThat(manager.getAllBalances().map { it.chainId }.toSet()).containsExactly(1, 137)
    }
}
