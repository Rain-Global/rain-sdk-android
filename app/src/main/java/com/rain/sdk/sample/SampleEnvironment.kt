package com.rain.sdk.sample

import com.rain.sdk.RainAuthPullChains
import com.rain.sdk.RainAuthPullConfig
import com.rain.sdk.wallet.RainWalletEnvironment

/**
 * Which Rain environment this build of the sample talks to.
 *
 * One constant drives four things that have to agree: the host the demo's own [RainApiClient]
 * calls, the environment the Rain wallet is built with, the chains the picker offers, and the
 * operator address the Auth Pull screen prefills. The SDK learns the environment through
 * `RainWalletConfig.environment` and checks that an Auth Pull config's chains belong to the
 * environment its kind names.
 *
 * Left on [SampleRainEnvironment.SANDBOX] deliberately. Production means mainnet: real USDC, real
 * gas, and an allowance a real card authorization can draw on; and the Rain wallet refuses
 * [RainWalletEnvironment.Production] at construction until Rain turns it on.
 */
object SampleEnvironment {

    val rainApi: SampleRainEnvironment = SampleRainEnvironment.SANDBOX

    val isProduction: Boolean
        get() = rainApi == SampleRainEnvironment.PRODUCTION

    /** The Rain wallet's environment for [rainApi]: the SDK refuses the production one until Rain turns it on. */
    val rainWallet: RainWalletEnvironment
        get() = if (isProduction) RainWalletEnvironment.Production else RainWalletEnvironment.Sandbox

    /**
     * Rain's Auth Pull operator for [rainApi]: the spender an approval names, one address per
     * environment and the same on every chain within it. Published in Rain's Auth Pull docs:
     * https://docs.rain.xyz/docs/authorization-pull-from-user-wallet
     *
     * Prefilled here so the screen is usable immediately, and editable in the UI. A host app should
     * read this from Rain rather than shipping it as a constant, keyed off the environment its own
     * Rain API integration targets.
     */
    val authPullOperator: String
        get() = if (isProduction) "0xA3750f692BB9Fc5e62834f9291E3D508d7Ba4F74" else "0x5a6E6b0d5Ea051CfFF9b3dcC2Aa8Dac226458f29"

    val authPullConfig: RainAuthPullConfig
        get() = if (isProduction) {
            RainAuthPullConfig.production(authPullOperator)
        } else {
            RainAuthPullConfig.sandbox(authPullOperator)
        }

    /** The Auth Pull chains for [rainApi]: the picker's answer before any SDK exists. */
    val authPullChains: Set<Int>
        get() = if (isProduction) RainAuthPullChains.PRODUCTION else RainAuthPullChains.SANDBOX

    /** A human label for the mode banner, so it is obvious which environment a build points at. */
    val displayName: String
        get() = if (isProduction) "Production" else "Sandbox"
}
