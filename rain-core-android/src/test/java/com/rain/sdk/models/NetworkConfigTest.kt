package com.rain.sdk.models

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pins `NetworkConfig`'s CAIP-2 formatting and parsing. */
class NetworkConfigTest {

    @Test
    fun `formats an EIP-155 chain id`() {
        assertThat(NetworkConfig(137, "https://rpc.test").eip155ChainId).isEqualTo("eip155:137")
    }

    @Test
    fun `formats a Solana sentinel id as its genesis-hash caip2`() {
        assertThat(NetworkConfig(RainChain.SOLANA_DEVNET, "https://rpc.test").eip155ChainId)
            .isEqualTo("solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1")
        assertThat(NetworkConfig(RainChain.SOLANA_MAINNET, "https://rpc.test").eip155ChainId)
            .isEqualTo("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp")
    }

    @Test
    fun `parses an EIP-155 chain id`() {
        val config = NetworkConfig.fromEip155("eip155:42161", "https://rpc.test", "Arbitrum")

        assertThat(config.chainId).isEqualTo(42161)
        assertThat(config.rpcUrl).isEqualTo("https://rpc.test")
        assertThat(config.networkName).isEqualTo("Arbitrum")
    }

    @Test
    fun `rejects a malformed EIP-155 chain id`() {
        listOf("137", "eip155:", "eip155:abc", "solana:5eykt4", "").forEach { malformed ->
            assertThrows(RainError.InvalidConfig::class.java) {
                NetworkConfig.fromEip155(malformed, "https://rpc.test")
            }
        }
    }
}
