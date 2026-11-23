package com.rain.sdk.portal

/**
 * Canonical addresses for Portal adapter tests. Mirrors the core test module's `TestFixtures`;
 * duplicated here because Kotlin `internal` test helpers don't cross the module boundary.
 */
internal object TestFixtures {
    const val WALLET_ADDRESS = "0x1234567890123456789012345678901234567890"
    const val CONTRACT_ADDRESS = "0x1234567890123456789012345678901234567890"
    const val USDC_ADDRESS = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
}
