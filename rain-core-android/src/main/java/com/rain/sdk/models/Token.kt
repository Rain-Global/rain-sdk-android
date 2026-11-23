package com.rain.sdk.models

/**
 * Identifies a token to read a balance for, independent of any specific chain family.
 *
 * [Native] is the chain's own currency (e.g. ETH, AVAX, POL); [Contract] is a token
 * identified by its on-chain contract address. Contract-address equality is
 * case-insensitive, so callers may pass checksummed or lowercased addresses
 * interchangeably.
 */
sealed class Token {
    /** The chain's native gas currency (e.g. ETH, AVAX, POL). */
    object Native : Token()

    /** An ERC-20 token identified by its on-chain contract [address]. */
    class Contract(val address: String) : Token() {
        // Case-insensitive equality / hashing so checksummed and lowercased addresses
        // compare equal.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other is Contract && address.lowercase() == other.address.lowercase()
        }

        override fun hashCode(): Int = address.lowercase().hashCode()

        override fun toString(): String = "Token.Contract(address=$address)"
    }

    /** The contract address lowercased for stable comparison / lookup, or `null` for [Native]. */
    val normalizedAddress: String?
        get() = when (this) {
            is Native -> null
            is Contract -> address.lowercase()
        }

    companion object {
        /** Convenience factory for [Contract], for callers who prefer a function over the class. */
        fun contract(address: String): Token = Contract(address)
    }
}
