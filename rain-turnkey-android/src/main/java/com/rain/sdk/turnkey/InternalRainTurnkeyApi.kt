package com.rain.sdk.turnkey

/**
 * Marks the managed-authentication surface of the Turnkey adapter: public in the binary because a
 * sibling Rain module (the Rain wallet provider, `RainProvider` in `rain-wallet-android`) has to call it, but not part of the SDK's contract
 * with host apps. The guard works as core's `RainAdapterApi` does, with
 * `-opt-in=com.rain.sdk.turnkey.InternalRainTurnkeyApi` for the Rain modules allowed through and
 * the sample app. No compatibility guarantees: it changes whenever the Rain wallet provider needs
 * it to.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal Rain SDK API reserved for the Rain wallet provider (RainProvider in rain-wallet-android). Host apps use the Rain wallet " +
        "provider or bring-your-own TurnkeyConfig(turnkey). No compatibility guarantees.",
)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
annotation class InternalRainTurnkeyApi
