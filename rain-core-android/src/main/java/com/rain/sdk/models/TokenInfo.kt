package com.rain.sdk.models

/**
 * A token the SDK reads balances for and scales amounts by: an ERC-20 contract on an EVM chain, or
 * an SPL mint on a Solana chain.
 *
 * Seeded from the built-in registry, extendable at runtime by host apps via `registerTokens(...)`,
 * and returned by `RainSdk.tokenMetadata`.
 */
data class TokenInfo(
    /** Numeric chain ID: EIP-155 for EVM chains, 900 to 902 for the Solana clusters. */
    val chainId: Int,

    /** Token contract address, or the SPL mint on Solana. */
    val address: String,

    /** Token symbol (e.g. "USDC", "DAI"). `null` when an enriched token's `symbol()` read failed. */
    val symbol: String?,

    /** Number of decimal places (e.g. 6 for USDC, 18 for DAI). */
    val decimals: Int,

    /** Optional human-readable token name. */
    val name: String? = null
)
