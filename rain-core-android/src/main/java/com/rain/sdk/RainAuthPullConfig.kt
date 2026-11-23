package com.rain.sdk

/**
 * Trusted Auth Pull targets: per chain, the token contract an approval may name.
 *
 * Auth Pull approvals are intentionally disabled until a host supplies this configuration. The
 * SDK then requires every approval, allowance read, and fee estimate to use the configured token
 * for the selected chain. The spender is not configured: it is the user's collateral contract,
 * passed on each call.
 */
class RainAuthPullConfig private constructor(
    tokenAddresses: Map<Int, String>,
    internal val kind: Kind
) {
    val tokenAddresses: Map<Int, String> = tokenAddresses.toMap()

    companion object {
        /** Canonical sandbox USDC contracts. */
        fun sandbox(): RainAuthPullConfig = RainAuthPullConfig(
            tokenAddresses = mapOf(
                RainChain.BASE_SEPOLIA to "0x036CbD53842c5426634e7929541eC2318f3dCF7e",
                RainChain.ARBITRUM_SEPOLIA to "0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d"
            ),
            kind = Kind.SANDBOX
        )

        /** Canonical production USDC contracts. */
        fun production(): RainAuthPullConfig = RainAuthPullConfig(
            tokenAddresses = mapOf(
                RainChain.BASE_MAINNET to "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
                RainChain.ARBITRUM_MAINNET to "0xaf88d065e77c8cC2239327C5EDb3A432268e5831"
            ),
            kind = Kind.PRODUCTION
        )

        /**
         * Explicit targets for a non-standard Rain deployment (staging, a self-hosted gateway). Its
         * chains may come from either environment's Auth Pull set; the token map must be exact.
         */
        fun custom(tokenAddresses: Map<Int, String>): RainAuthPullConfig =
            RainAuthPullConfig(tokenAddresses, Kind.CUSTOM)
    }

    internal enum class Kind { SANDBOX, PRODUCTION, CUSTOM }
}
