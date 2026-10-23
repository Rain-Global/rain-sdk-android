package com.rain.sdk.wallet

import com.rain.sdk.error.RainError
import com.rain.sdk.turnkey.rainApiBaseUrlHost

/**
 * Rain's wallet backend identity per [RainWalletEnvironment]: the Turnkey organization the wallets
 * live under, its auth-proxy configuration, which the contact attach runs through, and the Rain
 * API host the authentication runs against. Public identifiers, not secrets: possession grants
 * nothing, because every call to Rain's endpoints needs a wallet auth token the host's backend
 * mints with its Api-Key, the proxy's one-time-code flow still gates the attach, and abuse is
 * bounded by the backends' rate limits. Embedded so a host configures nothing but the environment
 * and the token supplier.
 */
internal object RainWalletBackend {
    /**
     * The one organization every environment uses today, Rain's sandbox parent organization: the
     * release that turns [RainWalletEnvironment.Production] on brings production's organization with
     * it, and a [RainWalletEnvironment.Custom] deployment is read as a sandbox one. Shared with
     * Rain's other SDKs as a cross-platform contract.
     */
    const val ORGANIZATION_ID: String = "63495e45-8e64-42b5-b602-c68f019ca806"

    /** The auth-proxy configuration of that organization; one for every environment, as the organization is. */
    const val AUTH_CONFIG_ID: String = "1d8aac5e-f236-4800-bab7-98a9e27b4b2a"
    const val SANDBOX_API_BASE_URL: String = "https://api-dev.rain.xyz"

    /** The host production answers from once Rain turns it on; until then [rowFor] refuses [RainWalletEnvironment.Production]. */
    const val PRODUCTION_API_BASE_URL: String = "https://api.rain.xyz"

    /** The refusal of [RainWalletEnvironment.Production] until the release that turns it on. */
    const val PRODUCTION_NOT_AVAILABLE_MESSAGE: String =
        "RainWalletEnvironment.Production is not available yet: Rain's production API has no wallet backend behind it, " +
            "so this release refuses it, and a Custom URL on its host the same way; pass Sandbox until the release " +
            "that turns production on"

    /** One environment's pair. */
    class Row(val organizationId: String, val apiBaseUrl: String)

    /**
     * The pair for [environment]. [RainWalletEnvironment.Production] is refused with
     * [RainError.InvalidConfig]: production has no wallet backend configured yet, and the one passkey
     * call that never reaches Rain would otherwise sign a production app into the sandbox
     * organization. The row arrives with the release that turns production on, since it carries
     * production's own organization. A [RainWalletEnvironment.Custom] URL on production's host is
     * refused for the same reason, since it would rebuild that row; the host is read through the
     * one parser the managed configuration validates the URL with, so no spelling of it slips past.
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
