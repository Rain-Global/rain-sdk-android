package com.rain.sdk

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.constants.TokenRegistry
import org.junit.Test

/**
 * Pins the chain IDs hosts and the Rain API both switch on, and the Auth Pull token registry
 * entries an approval depends on.
 */
class RainChainTest {

    @Test
    fun `chain IDs are pinned`() {
        assertThat(RainChain.AVALANCHE_MAINNET).isEqualTo(43114)
        assertThat(RainChain.AVALANCHE_TESTNET).isEqualTo(43113)
        assertThat(RainChain.SOLANA_MAINNET).isEqualTo(900)
        assertThat(RainChain.SOLANA_TESTNET).isEqualTo(902)
        assertThat(RainChain.SOLANA_DEVNET).isEqualTo(901)
    }

    @Test
    fun `Auth Pull chain IDs are pinned`() {
        assertThat(RainChain.BASE_MAINNET).isEqualTo(8453)
        assertThat(RainChain.BASE_SEPOLIA).isEqualTo(84532)
        assertThat(RainChain.ARBITRUM_MAINNET).isEqualTo(42161)
        assertThat(RainChain.ARBITRUM_SEPOLIA).isEqualTo(421614)
    }

    @Test
    fun `USDC is registered on every Auth Pull chain, at 6 decimals`() {
        val expected = mapOf(
            RainChain.BASE_MAINNET to "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
            RainChain.BASE_SEPOLIA to "0x036CbD53842c5426634e7929541eC2318f3dCF7e",
            RainChain.ARBITRUM_MAINNET to "0xaf88d065e77c8cC2239327C5EDb3A432268e5831",
            RainChain.ARBITRUM_SEPOLIA to "0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d",
        )

        expected.forEach { (chainId, address) ->
            val usdc = TokenRegistry.tokensFor(chainId).firstOrNull { it.symbol == "USDC" }
            assertThat(usdc).isNotNull()
            assertThat(usdc!!.address).isEqualTo(address)
            assertThat(usdc.decimals).isEqualTo(6)
        }
    }

    @Test
    fun `the Auth Pull testnets report ETH as their gas token`() {
        assertThat(TokenRegistry.nativeCurrency(RainChain.BASE_SEPOLIA).symbol).isEqualTo("ETH")
        assertThat(TokenRegistry.nativeCurrency(RainChain.ARBITRUM_SEPOLIA).symbol).isEqualTo("ETH")
    }

    @Test
    fun `Solana routing recognizes the public sentinel IDs`() {
        assertThat(RainChain.isSolana(RainChain.SOLANA_MAINNET)).isTrue()
        assertThat(RainChain.isSolana(RainChain.SOLANA_DEVNET)).isTrue()
        assertThat(RainChain.isSolana(RainChain.BASE_SEPOLIA)).isFalse()
    }
}
