package com.rain.sdk.portal

/**
 * Session-hardening policy for the Portal provider. Portal exposes no auth state, expiry or
 * token swap, so refresh = host re-mint (`onSessionTokenNeeded`) + vendor client rebuild.
 *
 * @param autoRefresh Re-mint and retry once on a rejected token (Portal rejects a bad token
 *                    before executing, so the retry is safe for sends too).
 * @param maxTransientRetries Retries for HTTP 5xx/429/408 and network I/O on idempotent reads.
 * @param initialRetryDelayMs First backoff delay; doubles per retry up to [maxRetryDelayMs].
 * @param maxRetryDelayMs Backoff ceiling.
 */
data class PortalSessionPolicy(
    val autoRefresh: Boolean = true,
    val maxTransientRetries: Int = DEFAULT_MAX_TRANSIENT_RETRIES,
    val initialRetryDelayMs: Long = DEFAULT_INITIAL_RETRY_DELAY_MS,
    val maxRetryDelayMs: Long = DEFAULT_MAX_RETRY_DELAY_MS,
) {
    init {
        require(maxTransientRetries >= 0) { "maxTransientRetries must be >= 0" }
        require(initialRetryDelayMs >= 0) { "initialRetryDelayMs must be >= 0" }
        require(maxRetryDelayMs >= initialRetryDelayMs) {
            "maxRetryDelayMs must be >= initialRetryDelayMs"
        }
    }

    companion object {
        const val DEFAULT_MAX_TRANSIENT_RETRIES = 2
        const val DEFAULT_INITIAL_RETRY_DELAY_MS = 500L
        const val DEFAULT_MAX_RETRY_DELAY_MS = 4_000L
    }
}

/**
 * The Portal session as seen at the Rain SDK boundary (via [PortalProvider.sessionState]).
 * Driven by call outcomes — Portal has nothing to watch passively — so it lags until a call runs.
 */
sealed class PortalSessionState {
    /** No Portal call has completed yet for this token. */
    data object Unknown : PortalSessionState()

    /** The last Portal call or token refresh succeeded. */
    data object Active : PortalSessionState()

    /** A re-minted token is being installed. */
    data object Refreshing : PortalSessionState()

    /** Portal rejected the token and no fresh one could be installed; re-auth is required. */
    data object Expired : PortalSessionState()
}
