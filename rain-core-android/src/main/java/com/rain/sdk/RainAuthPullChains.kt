package com.rain.sdk

/**
 * The chains Rain's Auth Pull runs on, grouped by environment.
 *
 * Rain's USDC contract differs between sandbox and production, and the
 * two sets of chains do not overlap. Approving on a chain from the wrong environment therefore
 * produces a perfectly valid allowance that no authorization will ever draw on, and on a mainnet
 * chain it spends real gas to grant that allowance.
 * The approval path rejects that pairing up front (see `RainSdkManager`).
 *
 * These sets answer for an *environment*. What a built SDK will actually accept is narrower: the
 * host's [RainAuthPullConfig] intersected with the chains that have an RPC endpoint, exposed as
 * `RainSdk.authPullChainIds` / `RainClient.authPullChainIds`. Gate UI on those; reach for
 * [SANDBOX] / [PRODUCTION] only before an SDK exists, and never keep a third copy of the list,
 * which is how these drift.
 *
 * Maintenance: in-tree like `TokenRegistry`, so the SDK owns updates. Rain is actively adding
 * chains to the beta; edit this file and ship a release. The matching USDC entries live in
 * `TokenRegistry`, and `RainAuthPullChainsTest` enforces that every chain here has one.
 */
object RainAuthPullChains {

    /** Sandbox Auth Pull chains. */
    val SANDBOX: Set<Int> = setOf(RainChain.BASE_SEPOLIA, RainChain.ARBITRUM_SEPOLIA)

    /** Production Auth Pull chains. */
    val PRODUCTION: Set<Int> = setOf(RainChain.BASE_MAINNET, RainChain.ARBITRUM_MAINNET)

    /**
     * The chains a [RainAuthPullConfig] of [kind] may name. The canonical sandbox and production
     * configurations map to their own set. A custom configuration describes a non-standard Rain
     * deployment whose environment cannot be inferred, so it may draw on either set but on nothing
     * outside them.
     */
    internal fun supported(kind: RainAuthPullConfig.Kind): Set<Int> = when (kind) {
        RainAuthPullConfig.Kind.SANDBOX -> SANDBOX
        RainAuthPullConfig.Kind.PRODUCTION -> PRODUCTION
        RainAuthPullConfig.Kind.CUSTOM -> SANDBOX + PRODUCTION
    }
}
