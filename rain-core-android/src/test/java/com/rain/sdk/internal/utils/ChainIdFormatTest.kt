package com.rain.sdk.internal.utils

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pins CAIP-2 formatting, parsing, and namespace dispatch for EVM and Solana chain ids. */
class ChainIdFormatTest {

    private val devnetCaip2 = "solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1"

    @Test
    fun `EIP155 formats an integer chain id`() {
        assertThat(ChainIdFormat.EIP155.format(1)).isEqualTo("eip155:1")
        assertThat(ChainIdFormat.EIP155.format(43114)).isEqualTo("eip155:43114")
    }

    @Test
    fun `SOLANA formats a sentinel id as its genesis-hash caip2`() {
        assertThat(ChainIdFormat.SOLANA.format(RainChain.SOLANA_MAINNET))
            .isEqualTo("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp")
        assertThat(ChainIdFormat.SOLANA.format(RainChain.SOLANA_DEVNET)).isEqualTo(devnetCaip2)
    }

    @Test
    fun `SOLANA format rejects a non-solana chain id`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChainIdFormat.SOLANA.format(1)
        }
    }

    @Test
    fun `namespaceFor dispatches solana sentinels to SOLANA and the rest to EIP155`() {
        assertThat(ChainIdFormat.namespaceFor(RainChain.SOLANA_MAINNET))
            .isEqualTo(ChainIdFormat.SOLANA)
        assertThat(ChainIdFormat.namespaceFor(RainChain.SOLANA_DEVNET))
            .isEqualTo(ChainIdFormat.SOLANA)
        assertThat(ChainIdFormat.namespaceFor(RainChain.SOLANA_TESTNET))
            .isEqualTo(ChainIdFormat.SOLANA)
        assertThat(ChainIdFormat.namespaceFor(1)).isEqualTo(ChainIdFormat.EIP155)
        assertThat(ChainIdFormat.namespaceFor(43114)).isEqualTo(ChainIdFormat.EIP155)
    }

    @Test
    fun `EIP155 parses its own format and rejects others`() {
        assertThat(ChainIdFormat.EIP155.parse("eip155:137")).isEqualTo(137)
        assertThat(ChainIdFormat.EIP155.parse(devnetCaip2)).isNull()
        assertThat(ChainIdFormat.EIP155.parse("eip155:abc")).isNull()
        assertThat(ChainIdFormat.EIP155.parse("eip155")).isNull()
    }

    @Test
    fun `SOLANA parses a known caip2 back to its sentinel id`() {
        assertThat(ChainIdFormat.SOLANA.parse(devnetCaip2)).isEqualTo(RainChain.SOLANA_DEVNET)
        assertThat(ChainIdFormat.SOLANA.parse("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp"))
            .isEqualTo(RainChain.SOLANA_MAINNET)
        assertThat(ChainIdFormat.SOLANA.parse("solana:unknowngenesis")).isNull()
        assertThat(ChainIdFormat.SOLANA.parse("eip155:1")).isNull()
    }

    @Test
    fun `format and parse round-trip for every solana sentinel`() {
        listOf(RainChain.SOLANA_MAINNET, RainChain.SOLANA_TESTNET, RainChain.SOLANA_DEVNET)
            .forEach { chainId ->
                val formatted = ChainIdFormat.SOLANA.format(chainId)
                assertThat(ChainIdFormat.SOLANA.parse(formatted)).isEqualTo(chainId)
            }
    }
}
