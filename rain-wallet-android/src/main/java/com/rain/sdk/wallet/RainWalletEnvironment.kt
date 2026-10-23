package com.rain.sdk.wallet

/**
 * Which Rain deployment the wallet's authentication runs against: the host your backend mints
 * wallet auth tokens with, and the one the SDK sends them to. More environments may be added;
 * include an `else` branch when you switch over it. [Custom] compares by URL.
 */
sealed interface RainWalletEnvironment {
    /**
     * Rain's sandbox, `https://api-dev.rain.xyz`, the one environment with a wallet backend behind
     * it. Not the default: [Production] is, and it is refused, so pass this value.
     */
    data object Sandbox : RainWalletEnvironment

    /**
     * Rain production, `https://api.rain.xyz`, the default, so a build that passes nothing never
     * runs against the sandbox by accident. Refused: production has no wallet backend behind it, so
     * the [RainProvider] constructor throws `RainError.InvalidConfig` (`RAIN_102`) and a build passes
     * [Sandbox] or a [Custom]; a build that passes nothing fails at construction rather than at the
     * first login.
     */
    data object Production : RainWalletEnvironment

    /**
     * A Rain deployment you name: any https host that forwards to Rain's sandbox, such as your own
     * proxy. An absolute https URL naming a host and nothing more; a path, a query, a fragment,
     * userinfo, plain http, or production's host throws `RainError.InvalidConfig` (`RAIN_102`) from
     * the [RainProvider] constructor. Two are equal when their URLs are, as given.
     */
    class Custom(val apiBaseUrl: String) : RainWalletEnvironment {
        override fun equals(other: Any?): Boolean = other is Custom && other.apiBaseUrl == apiBaseUrl

        override fun hashCode(): Int = apiBaseUrl.hashCode()

        override fun toString(): String = "Custom(apiBaseUrl=$apiBaseUrl)"
    }
}
