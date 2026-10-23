package com.rain.sdk.wallet

/**
 * Which Rain deployment the wallet's authentication runs against: the host your backend mints
 * wallet auth tokens with, the one the SDK sends them to, and the wallet backend organization the
 * accounts live under. Each environment fixes all three, so a sandbox account never signs into
 * production. More environments may be added; include an `else` branch when you switch over it.
 */
sealed interface RainWalletEnvironment {
    /** Rain's sandbox, `https://api-dev.rain.xyz`: test users and testnets. */
    data object Sandbox : RainWalletEnvironment

    /**
     * Rain production, `https://api.rain.xyz`, the default, so a build that passes nothing never
     * runs against the sandbox by accident.
     */
    data object Production : RainWalletEnvironment
}
