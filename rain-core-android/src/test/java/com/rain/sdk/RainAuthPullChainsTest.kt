package com.rain.sdk

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.constants.TokenRegistry
import com.rain.sdk.internal.network.chainreader.multicall3Address
import org.junit.Test

/**
 * Keeps the Auth Pull chain sets honest against Rain's published support matrix and against the
 * other in-tree lists an approval depends on.
 */
class RainAuthPullChainsTest {

    @Test
    fun `sandbox is Base Sepolia and Arbitrum Sepolia`() {
        assertThat(RainAuthPullChains.SANDBOX).containsExactly(84532, 421614)
    }

    @Test
    fun `production is Base and Arbitrum mainnet`() {
        assertThat(RainAuthPullChains.PRODUCTION).containsExactly(8453, 42161)
    }

    @Test
    fun `the two environments share no chain`() {
        assertThat(RainAuthPullChains.SANDBOX.intersect(RainAuthPullChains.PRODUCTION)).isEmpty()
    }

    @Test
    fun `each configuration kind maps to its Auth Pull set`() {
        assertThat(RainAuthPullChains.supported(RainAuthPullConfig.Kind.SANDBOX))
            .isEqualTo(RainAuthPullChains.SANDBOX)
        assertThat(RainAuthPullChains.supported(RainAuthPullConfig.Kind.PRODUCTION))
            .isEqualTo(RainAuthPullChains.PRODUCTION)
        // A custom deployment cannot be placed in one environment, so it may draw on either set.
        assertThat(RainAuthPullChains.supported(RainAuthPullConfig.Kind.CUSTOM))
            .isEqualTo(RainAuthPullChains.SANDBOX + RainAuthPullChains.PRODUCTION)
    }

    /**
     * The approval path resolves USDC's decimals from [TokenRegistry], and refuses to guess. An
     * Auth Pull chain with no registry entry would force every host to pass `decimals` by hand.
     */
    @Test
    fun `every Auth Pull chain ships USDC in the token registry`() {
        for (chainId in RainAuthPullChains.SANDBOX + RainAuthPullChains.PRODUCTION) {
            val usdc = TokenRegistry.tokensFor(chainId).filter { it.symbol == "USDC" }
            assertThat(usdc).hasSize(1)
            assertThat(usdc.single().decimals).isEqualTo(6)
        }
    }

    /** Allowance reads batch through Multicall3 where it is deployed; all four qualify. */
    @Test
    fun `every Auth Pull chain has a Multicall3 deployment`() {
        for (chainId in RainAuthPullChains.SANDBOX + RainAuthPullChains.PRODUCTION) {
            assertThat(multicall3Address(chainId)).isNotNull()
        }
    }

    /** ERC-20 approvals are EVM-only, so no Solana sentinel may leak into either set. */
    @Test
    fun `no Auth Pull chain is a Solana sentinel`() {
        for (chainId in RainAuthPullChains.SANDBOX + RainAuthPullChains.PRODUCTION) {
            assertThat(RainChain.isSolana(chainId)).isFalse()
        }
    }
}
