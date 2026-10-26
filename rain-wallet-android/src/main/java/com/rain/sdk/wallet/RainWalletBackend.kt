package com.rain.sdk.wallet

/**
 * Rain's wallet backend identity per [RainWalletEnvironment]: the Turnkey organization the wallets
 * live under and the Rain API host the authentication runs against. Public identifiers, not
 * secrets: possession grants nothing, because every call to Rain's endpoints needs a wallet auth
 * token the host's backend mints with its Api-Key and abuse is bounded by Rain's rate limits.
 * Embedded so a host configures nothing but the environment and the token supplier. The pairs are
 * a cross-platform contract shared with Rain's other SDKs.
 */
internal object RainWalletBackend {
    const val SANDBOX_ORGANIZATION_ID: String = "63495e45-8e64-42b5-b602-c68f019ca806"
    const val SANDBOX_API_BASE_URL: String = "https://api-dev.rain.xyz"

    const val PRODUCTION_ORGANIZATION_ID: String = "306c2e82-5ae2-4be6-8b41-1ab0cc6b0ab5"
    const val PRODUCTION_API_BASE_URL: String = "https://api.rain.xyz"

    /** One environment's pair. */
    class Row(val organizationId: String, val apiBaseUrl: String)

    /**
     * The pair for [environment]. The organization and the host always move together: the passkey
     * leg never reaches Rain, so a host paired with the other environment's organization would sign
     * users into the wrong one.
     */
    fun rowFor(environment: RainWalletEnvironment): Row = when (environment) {
        RainWalletEnvironment.Sandbox -> Row(SANDBOX_ORGANIZATION_ID, SANDBOX_API_BASE_URL)
        RainWalletEnvironment.Production -> Row(PRODUCTION_ORGANIZATION_ID, PRODUCTION_API_BASE_URL)
    }
}
