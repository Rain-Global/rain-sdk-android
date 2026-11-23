package com.rain.sdk.internal.constants

import com.rain.sdk.RainChain
import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.solana.SolanaConverter
import com.rain.sdk.models.NativeCurrency

/**
 * Per-cluster reference data for Solana, the analogue of [TokenRegistry]/`RainConstants` for
 * EVM. Solana has no EIP-155 integer chain ID, so the SDK keys clusters by the [RainChain]
 * sentinel IDs and maps them to their CAIP-2 (genesis-hash) identifiers, which is the form
 * vendor Solana APIs (send, balances) expect.
 */
@RainAdapterApi
object SolanaChains {
    // CAIP-2 references: base58 of each cluster's genesis hash, truncated to 32 chars per the spec.
    private val CAIP2_BY_CHAIN_ID: Map<Int, String> = mapOf(
        RainChain.SOLANA_MAINNET to "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp",
        RainChain.SOLANA_TESTNET to "solana:4uhcVJyU9pJkvQyS88uRDiswHXSCkY3z",
        RainChain.SOLANA_DEVNET to "solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1"
    )

    /** SOL native currency — 9 decimals (vs 18 for EVM), identical across clusters. */
    val NATIVE_CURRENCY = NativeCurrency(
        symbol = "SOL",
        name = "Solana",
        decimals = SolanaConverter.SOL_DECIMALS
    )

    fun isSolanaChain(chainId: Int): Boolean = CAIP2_BY_CHAIN_ID.containsKey(chainId)

    /** CAIP-2 identifier for [chainId], e.g. `solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1`. */
    fun caip2(chainId: Int): String =
        CAIP2_BY_CHAIN_ID[chainId]
            ?: throw IllegalArgumentException("Not a known Solana chainId: $chainId")

    /** Sentinel chain id for a Solana CAIP-2 identifier, or null when unknown. */
    fun chainIdForCaip2(caip2: String): Int? =
        CAIP2_BY_CHAIN_ID.entries.firstOrNull { it.value == caip2 }?.key
}
