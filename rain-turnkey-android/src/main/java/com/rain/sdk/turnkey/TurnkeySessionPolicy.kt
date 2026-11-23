package com.rain.sdk.turnkey

/**
 * Session-hardening policy for the Turnkey provider: expiry, refresh and retry behaviour for the
 * session guarding every wallet call. *Session expiry, refresh, and retry* in
 * `docs/TURNKEY_SUPPORT.md` has what every wallet call does with it.
 *
 * Out-of-range values are a programming error and throw [IllegalArgumentException] at construction.
 *
 * @param refreshBufferSeconds Refresh the session when it is within this window of expiring.
 * @param autoRefresh When true the SDK calls Turnkey's `refreshSession` itself; when false an
 *                    expired session surfaces as `RainError.TokenExpired` and re-auth is the
 *                    host's job.
 * @param refreshExpirationSeconds Lifetime, in seconds, requested for refreshed sessions; null
 *                                 uses Turnkey's default (900 seconds). Must be positive.
 * @param maxTransientRetries Retries (beyond the first attempt) for transient failures on
 *                            idempotent reads.
 * @param initialRetryDelayMs First backoff delay; doubles per retry up to [maxRetryDelayMs].
 * @param maxRetryDelayMs Backoff ceiling.
 */
data class TurnkeySessionPolicy(
    val refreshBufferSeconds: Long = DEFAULT_REFRESH_BUFFER_SECONDS,
    val autoRefresh: Boolean = true,
    val refreshExpirationSeconds: Long? = null,
    val maxTransientRetries: Int = DEFAULT_MAX_TRANSIENT_RETRIES,
    val initialRetryDelayMs: Long = DEFAULT_INITIAL_RETRY_DELAY_MS,
    val maxRetryDelayMs: Long = DEFAULT_MAX_RETRY_DELAY_MS,
) {
    init {
        require(refreshBufferSeconds >= 0) { "refreshBufferSeconds must be >= 0" }
        require(refreshExpirationSeconds == null || refreshExpirationSeconds > 0) {
            "refreshExpirationSeconds must be > 0, was $refreshExpirationSeconds"
        }
        require(maxTransientRetries >= 0) { "maxTransientRetries must be >= 0" }
        require(initialRetryDelayMs >= 0) { "initialRetryDelayMs must be >= 0" }
        require(maxRetryDelayMs >= initialRetryDelayMs) {
            "maxRetryDelayMs must be >= initialRetryDelayMs"
        }
    }

    companion object {
        const val DEFAULT_REFRESH_BUFFER_SECONDS = 60L
        const val DEFAULT_MAX_TRANSIENT_RETRIES = 2
        const val DEFAULT_INITIAL_RETRY_DELAY_MS = 500L
        const val DEFAULT_MAX_RETRY_DELAY_MS = 4_000L
    }
}

/**
 * The Turnkey session as seen at the Rain SDK boundary, observable via
 * [TurnkeyProvider.sessionState]; *Observing session state* in `docs/TURNKEY_SUPPORT.md` has the
 * states and the hook.
 */
sealed class TurnkeySessionState {
    /** Turnkey is still restoring persisted sessions (app launch). */
    data object Loading : TurnkeySessionState()

    /** A session exists and its JWT has not expired. */
    data class Active(val expiresAtEpochSeconds: Double) : TurnkeySessionState()

    /** A session object is still present but its JWT expiry has passed. Re-authenticate. */
    data object Expired : TurnkeySessionState()

    /** No session (never logged in, logged out, or cleared by Turnkey's expiry timer). */
    data object Unauthenticated : TurnkeySessionState()
}

/**
 * Where managed Turnkey authentication stands, at the Rain boundary: a view over
 * [TurnkeySessionState] with [TurnkeySessionState.Expired] read as [Unauthenticated]. Observable
 * via [TurnkeyProvider.authState]; snapshot via [TurnkeyProvider.currentAuthState]. Internal API
 * ([InternalRainTurnkeyApi]). Hosts use `RainWalletAuthState` in `rain-wallet-android`, whose
 * KDoc has the rules.
 */
@InternalRainTurnkeyApi
sealed class TurnkeyAuthState {
    /** Turnkey is not configured yet, or a previous session is still being restored. */
    data object Loading : TurnkeyAuthState()

    /** A session is live. */
    data object Authenticated : TurnkeyAuthState()

    /** No usable session: a code login or a passkey login is needed. */
    data object Unauthenticated : TurnkeyAuthState()
}

internal fun TurnkeySessionState.toAuthState(): TurnkeyAuthState = when (this) {
    is TurnkeySessionState.Loading -> TurnkeyAuthState.Loading
    is TurnkeySessionState.Active -> TurnkeyAuthState.Authenticated
    is TurnkeySessionState.Expired,
    is TurnkeySessionState.Unauthenticated -> TurnkeyAuthState.Unauthenticated
}
