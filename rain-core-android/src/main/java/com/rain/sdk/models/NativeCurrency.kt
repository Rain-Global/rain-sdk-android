package com.rain.sdk.models

/**
 * Metadata for a chain's native currency (e.g. ETH on Ethereum, AVAX on Avalanche).
 *
 * Static per-chain reference data, looked up by chain ID in the token registry.
 */
data class NativeCurrency(
    /** Currency symbol (e.g. "ETH", "AVAX", "POL"). */
    val symbol: String,

    /** Human-readable name (e.g. "Ether", "Avalanche"). */
    val name: String,

    /** Number of decimal places. Effectively always 18 for EVM native currencies. */
    val decimals: Int = 18
)
