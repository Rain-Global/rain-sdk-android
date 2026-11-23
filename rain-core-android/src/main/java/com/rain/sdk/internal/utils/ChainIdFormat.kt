package com.rain.sdk.internal.utils

import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.constants.SolanaChains

/** CAIP-2 chain-ID namespaces: EIP-155 (`eip155:1`) and Solana (`solana:<genesis>`). */
@RainAdapterApi
enum class ChainIdFormat(val prefix: String) {
    EIP155("eip155"),
    SOLANA("solana");

    /** Format a chain ID as a CAIP-2 string in this namespace. */
    fun format(chainId: Int): String = when (this) {
        EIP155 -> "$prefix:$chainId"
        SOLANA -> SolanaChains.caip2(chainId)
    }

    /** Parse a formatted string into its chain ID, or `null` if the format doesn't match. */
    fun parse(value: String): Int? {
        val parts = value.split(":")
        if (parts.size != 2 || parts[0] != prefix) return null
        return when (this) {
            EIP155 -> parts[1].toIntOrNull()
            SOLANA -> SolanaChains.chainIdForCaip2(value)
        }
    }

    companion object {
        /** Namespace governing [chainId]: Solana sentinel ids map to [SOLANA], all others to [EIP155]. */
        fun namespaceFor(chainId: Int): ChainIdFormat =
            if (SolanaChains.isSolanaChain(chainId)) SOLANA else EIP155
    }
}
