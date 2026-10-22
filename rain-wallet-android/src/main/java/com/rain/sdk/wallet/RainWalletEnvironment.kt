package com.rain.sdk.wallet

/**
 * Which Rain deployment the wallet's authentication runs against: the host your backend mints
 * wallet auth tokens with, and the one the SDK sends them to. A `when` over it needs an `else`
 * branch, which is how a deployment can be added without a breaking release. [Custom] compares by
 * URL; a field can be added to it later through a new constructor overload.
 */
sealed interface RainWalletEnvironment {
    /**
     * Rain's sandbox, `https://api-dev.rain.xyz`, the one environment with a wallet backend behind
     * it today. Not the default: [Production] is, and this release refuses it, so pass this value
     * until production is turned on.
     */
    data object Sandbox : RainWalletEnvironment

    /**
     * Rain production, `https://api.rain.xyz`, the default, so a build that passes nothing never
     * runs against the sandbox by accident. Not available yet: production has no wallet backend
     * behind it, so the [RainProvider] constructor refuses this value with `RainError.InvalidConfig`
     * (`RAIN_102`) until the SDK release that turns production on, and every build passes [Sandbox]
     * until then; a release build that passes nothing fails at construction rather than at the
     * first login.
     */
    data object Production : RainWalletEnvironment

    /**
     * A Rain deployment you name: any https host that forwards to Rain's sandbox, such as your own
     * proxy. An absolute https URL naming a host and nothing more; a path, a query, a fragment,
     * userinfo, plain http, or production's host throws `RainError.InvalidConfig` (`RAIN_102`) from
     * the [RainProvider] constructor. The wallet backend is the sandbox organization, as for
     * [Sandbox]. Two are equal when their URLs are, as given.
     */
    class Custom(val apiBaseUrl: String) : RainWalletEnvironment {
        override fun equals(other: Any?): Boolean = other is Custom && other.apiBaseUrl == apiBaseUrl

        override fun hashCode(): Int = apiBaseUrl.hashCode()

        override fun toString(): String = "Custom(apiBaseUrl=$apiBaseUrl)"
    }
}

/**
 * Not a deployment, and never a value a host sees. It exists so that a `when` over
 * [RainWalletEnvironment] needs an `else` branch, which is what lets a deployment be added without a
 * breaking release. File-level because an interface member cannot be `internal`.
 */
internal data object Reserved : RainWalletEnvironment
