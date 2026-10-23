package com.rain.sdk.wallet

import com.rain.sdk.error.RainError
import com.rain.sdk.turnkey.rainApiBaseUrlHost

/**
 * Rain's wallet backend identity per [RainWalletEnvironment]: the Turnkey organization the wallets
 * live under and the Rain API host the authentication runs against. Public identifiers, not
 * secrets: possession grants nothing, because every call to Rain's endpoints needs a wallet auth
 * token the host's backend mints with its Api-Key and abuse is bounded by Rain's rate limits.
 * Embedded so a host configures nothing but the environment and the token supplier.
 */
internal object RainWalletBackend {
    /**
     * Rain's sandbox parent organization, used for [RainWalletEnvironment.Sandbox] and
     * [RainWalletEnvironment.Custom]. Shared with Rain's other SDKs as a cross-platform contract.
     */
    const val ORGANIZATION_ID: String = "63495e45-8e64-42b5-b602-c68f019ca806"
    const val SANDBOX_API_BASE_URL: String = "https://api-dev.rain.xyz"

    /** Production's host; [rowFor] refuses it. */
    const val PRODUCTION_API_BASE_URL: String = "https://api.rain.xyz"

    /** The refusal of [RainWalletEnvironment.Production], and of a [RainWalletEnvironment.Custom] URL on its host. */
    const val PRODUCTION_NOT_AVAILABLE_MESSAGE: String =
        "RainWalletEnvironment.Production is not available yet: Rain's production API has no wallet backend behind it, " +
            "so this release refuses it, and a Custom URL on its host the same way; pass Sandbox until the release " +
            "that turns production on"

    /** One environment's pair. */
    class Row(val organizationId: String, val apiBaseUrl: String)

    /**
     * The pair for [environment]. [RainWalletEnvironment.Production], and a [RainWalletEnvironment.Custom]
     * URL on production's host, throw [RainError.InvalidConfig]: production has no wallet backend, and
     * the passkey leg, which never reaches Rain, would otherwise sign a production app into the sandbox
     * organization. The host is read through the parser the managed configuration validates the URL with.
     */
    fun rowFor(environment: RainWalletEnvironment): Row = when (environment) {
        RainWalletEnvironment.Sandbox -> Row(ORGANIZATION_ID, SANDBOX_API_BASE_URL)
        RainWalletEnvironment.Production -> throw RainError.InvalidConfig(PRODUCTION_NOT_AVAILABLE_MESSAGE)
        is RainWalletEnvironment.Custom -> {
            if (rainApiBaseUrlHost(environment.apiBaseUrl) == PRODUCTION_HOST) throw RainError.InvalidConfig(PRODUCTION_NOT_AVAILABLE_MESSAGE)
            Row(ORGANIZATION_ID, environment.apiBaseUrl)
        }
    }

    private val PRODUCTION_HOST = PRODUCTION_API_BASE_URL.removePrefix("https://")
}
