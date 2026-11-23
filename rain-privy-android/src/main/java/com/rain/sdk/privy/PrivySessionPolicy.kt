package com.rain.sdk.privy

/**
 * Session-hardening policy for the Privy provider.
 *
 * Privy differs from Turnkey: the Privy SDK refreshes its own session internally before every
 * wallet and indexer call, and exposes no JWT expiry. So there is no proactive-refresh window
 * to configure: hardening here is auth-state guarding, a re-auth hook, and transient-failure
 * backoff. An auth failure that survives Privy's own internal refresh means the session is
 * truly dead and surfaces as `RainError.TokenExpired` (never retried by Rain).
 *
 * @param maxTransientRetries Retries (beyond the first attempt) on idempotent reads for a
 *                            request that got no answer or an HTTP 408 or 429 answer. A 5xx
 *                            is not retried; *Session expiry and retry* in
 *                            rain-privy-android/README.md has the rule. Writes (sends,
 *                            signing) are never retried.
 * @param initialRetryDelayMs First backoff delay; doubles per retry up to [maxRetryDelayMs].
 * @param maxRetryDelayMs Backoff ceiling.
 */
data class PrivySessionPolicy(
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
 * The Privy session at the Rain SDK boundary, observable via [PrivyProvider.sessionState].
 * Privy exposes no JWT expiry, so there is no `Expired` state: a dead session surfaces as
 * [Unauthenticated] once Privy clears it.
 */
sealed class PrivySessionState {
    /** Privy is still restoring persisted credentials (app launch). */
    data object Loading : PrivySessionState()

    /** An authenticated session exists. */
    data object Active : PrivySessionState()

    /**
     * A prior session was restored but could not be verified (typically offline). Recoverable:
     * Privy re-verifies via `onNetworkRestored()` when connectivity returns.
     */
    data object Unverified : PrivySessionState()

    /** No session (never logged in, logged out, or the session died and Privy cleared it). */
    data object Unauthenticated : PrivySessionState()
}
