package com.rain.sdk

/**
 * Marks Rain SDK API that may change in any release.
 *
 * Through [SubclassOptInRequired] on an interface, the marker guards implementing it: a host that
 * writes its own [com.rain.sdk.provider.WalletProvider], [com.rain.sdk.provider.ProviderDescriptor]
 * or [com.rain.sdk.interfaces.RainClient] adds `@OptIn(ExperimentalRainApi::class)` to that
 * class and accepts that Rain may add members to the interface in any release, with a default body
 * wherever one makes sense. Calling the SDK through these interfaces needs no opt-in.
 *
 * On any other declaration, the marker means that declaration may change or be removed in any
 * release. The CHANGELOG records each such change.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This Rain SDK API may change in any release. Opt in with @OptIn(ExperimentalRainApi::class).",
)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
annotation class ExperimentalRainApi
