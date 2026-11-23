package com.rain.sdk.sample

import com.rain.sdk.RainAuthPullChains
import com.rain.sdk.RainAuthPullConfig
import com.rain.sdk.wallet.RainWalletEnvironment

/**
 * Which Rain environment this build of the sample talks to.
 *
 * One constant drives four things that have to agree: the host the demo's own [RainApiClient]
 * calls, the environment the Rain wallet is built with, the chains the picker offers, and the
 * Auth Pull configuration the SDK is built with. The SDK checks that an Auth Pull config's
 * chains belong to the environment its kind names.
 *
 * Left on [SampleRainEnvironment.SANDBOX] deliberately. Production means mainnet: real USDC, real
 * gas, and an allowance a real card authorization can draw on.
 */
object SampleEnvironment {

    val rainApi: SampleRainEnvironment = SampleRainEnvironment.SANDBOX

    val isProduction: Boolean
        get() = rainApi == SampleRainEnvironment.PRODUCTION

    /** The Rain wallet's environment for [rainApi]. */
    val rainWallet: RainWalletEnvironment
        get() = if (isProduction) RainWalletEnvironment.Production else RainWalletEnvironment.Sandbox

    val authPullConfig: RainAuthPullConfig
        get() = if (isProduction) RainAuthPullConfig.production() else RainAuthPullConfig.sandbox()

    /** The Auth Pull chains for [rainApi]: the picker's answer before any SDK exists. */
    val authPullChains: Set<Int>
        get() = if (isProduction) RainAuthPullChains.PRODUCTION else RainAuthPullChains.SANDBOX

    /** A human label for the mode banner, so it is obvious which environment a build points at. */
    val displayName: String
        get() = if (isProduction) "Production" else "Sandbox"
}
