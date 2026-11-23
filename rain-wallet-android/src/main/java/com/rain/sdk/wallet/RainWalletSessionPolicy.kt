package com.rain.sdk.wallet

import com.rain.sdk.turnkey.TurnkeyAuthState
import com.rain.sdk.turnkey.TurnkeySessionPolicy
import com.rain.sdk.turnkey.TurnkeySessionState

/**
 * Session-hardening policy for the Rain wallet provider: how the SDK guards wallet calls against
 * session expiry and transient failures. *Session expiry and retry* in rain-wallet-android/README.md
 * has the parameters and defaults; *Session expiry, refresh, and retry* in `docs/TURNKEY_SUPPORT.md`
 * has what every wallet call does with it.
 *
 * Out-of-range values are a programming error and throw [IllegalArgumentException] at construction.
 *
 * @param refreshBufferSeconds Refresh the session when it is within this window of expiring.
 * @param autoRefresh When true the SDK refreshes the session itself; when false an expired session
 *                    surfaces as `RainError.TokenExpired` (`RAIN_201`) and re-auth is the host's job.
 * @param refreshExpirationSeconds Lifetime, in seconds, requested for refreshed sessions; null uses
 *                                 the wallet backend's default of 900 seconds.
 * @param maxTransientRetries Retries (beyond the first attempt) for transient failures on
 *                            idempotent reads.
 * @param initialRetryDelayMs First backoff delay; doubles per retry up to [maxRetryDelayMs].
 * @param maxRetryDelayMs Backoff ceiling.
 */
class RainWalletSessionPolicy(
    val refreshBufferSeconds: Long = DEFAULT_REFRESH_BUFFER_SECONDS,
    val autoRefresh: Boolean = true,
    val refreshExpirationSeconds: Long? = null,
    val maxTransientRetries: Int = DEFAULT_MAX_TRANSIENT_RETRIES,
    val initialRetryDelayMs: Long = DEFAULT_INITIAL_RETRY_DELAY_MS,
    val maxRetryDelayMs: Long = DEFAULT_MAX_RETRY_DELAY_MS,
) {
    init {
        require(refreshBufferSeconds >= 0) { "refreshBufferSeconds must be >= 0, was $refreshBufferSeconds" }
        require(refreshExpirationSeconds == null || refreshExpirationSeconds > 0) {
            "refreshExpirationSeconds must be > 0, was $refreshExpirationSeconds"
        }
        require(maxTransientRetries >= 0) { "maxTransientRetries must be >= 0, was $maxTransientRetries" }
        require(initialRetryDelayMs >= 0) { "initialRetryDelayMs must be >= 0, was $initialRetryDelayMs" }
        require(maxRetryDelayMs >= initialRetryDelayMs) {
            "maxRetryDelayMs must be >= initialRetryDelayMs, was $maxRetryDelayMs vs $initialRetryDelayMs"
        }
    }

    internal fun toBacking(): TurnkeySessionPolicy = TurnkeySessionPolicy(
        refreshBufferSeconds = refreshBufferSeconds,
        autoRefresh = autoRefresh,
        refreshExpirationSeconds = refreshExpirationSeconds,
        maxTransientRetries = maxTransientRetries,
        initialRetryDelayMs = initialRetryDelayMs,
        maxRetryDelayMs = maxRetryDelayMs,
    )

    /** Every field, for logs and crash reports. */
    override fun toString(): String =
        "RainWalletSessionPolicy(refreshBufferSeconds=$refreshBufferSeconds, autoRefresh=$autoRefresh, " +
            "refreshExpirationSeconds=$refreshExpirationSeconds, maxTransientRetries=$maxTransientRetries, " +
            "initialRetryDelayMs=$initialRetryDelayMs, maxRetryDelayMs=$maxRetryDelayMs)"

    /**
     * The defaults, as constants. A `const val` is inlined into the caller at compile time, so a
     * host that names one keeps that value until it recompiles against a newer SDK.
     */
    companion object {
        /** Default [refreshBufferSeconds]: 60 seconds. */
        const val DEFAULT_REFRESH_BUFFER_SECONDS: Long = 60L

        /** Default [maxTransientRetries]: 2 retries after the first attempt. */
        const val DEFAULT_MAX_TRANSIENT_RETRIES: Int = 2

        /** Default [initialRetryDelayMs]: 500 milliseconds. */
        const val DEFAULT_INITIAL_RETRY_DELAY_MS: Long = 500L

        /** Default [maxRetryDelayMs]: 4 seconds. */
        const val DEFAULT_MAX_RETRY_DELAY_MS: Long = 4_000L
    }
}

/**
 * The wallet session as seen at the SDK boundary. Observable via [RainProvider.sessionState] so a
 * host can react to a session dying without waiting for a wallet call to fail. Exhaustive in a
 * `when`; a new state is a major-version change.
 */
sealed class RainWalletSessionState {
    /** The SDK is still restoring persisted sessions (app launch). */
    data object Loading : RainWalletSessionState()

    /** A session exists and has not expired; [expiresAtEpochSeconds] is its expiry in unix seconds. */
    class Active(val expiresAtEpochSeconds: Double) : RainWalletSessionState() {
        override fun equals(other: Any?): Boolean =
            other is Active && other.expiresAtEpochSeconds.compareTo(expiresAtEpochSeconds) == 0

        override fun hashCode(): Int = expiresAtEpochSeconds.hashCode()

        override fun toString(): String = "Active(expiresAtEpochSeconds=$expiresAtEpochSeconds)"
    }

    /** The session has expired. Re-authenticate. */
    data object Expired : RainWalletSessionState()

    /** No session (never logged in, logged out, or expired out). */
    data object Unauthenticated : RainWalletSessionState()
}

@JvmSynthetic
internal fun TurnkeySessionState.toRainWallet(): RainWalletSessionState = when (this) {
    is TurnkeySessionState.Loading -> RainWalletSessionState.Loading
    is TurnkeySessionState.Active -> RainWalletSessionState.Active(expiresAtEpochSeconds)
    is TurnkeySessionState.Expired -> RainWalletSessionState.Expired
    is TurnkeySessionState.Unauthenticated -> RainWalletSessionState.Unauthenticated
}

/**
 * Where Rain wallet authentication stands. A view over [RainWalletSessionState]:
 * [RainWalletSessionState.Expired] and [RainWalletSessionState.Unauthenticated] both read as
 * [Unauthenticated] here, because a login screen only needs to know whether a one-time code is
 * required. Observable via [RainProvider.authState]; snapshot via [RainProvider.currentAuthState].
 * Exhaustive in a `when`, as [RainWalletSessionState] is.
 */
sealed class RainWalletAuthState {
    /**
     * The wallet backend is not configured yet (no auth call has run), or the SDK is still
     * restoring a possible previous session from secure storage.
     */
    data object Loading : RainWalletAuthState()

    /** A session is live; the provider can be resolved and wallet calls will succeed. */
    data object Authenticated : RainWalletAuthState()

    /** No usable session: run [RainProvider.sendLoginCode] and [RainProvider.confirmLoginCode]. */
    data object Unauthenticated : RainWalletAuthState()
}

@JvmSynthetic
internal fun TurnkeyAuthState.toRainWallet(): RainWalletAuthState = when (this) {
    is TurnkeyAuthState.Loading -> RainWalletAuthState.Loading
    is TurnkeyAuthState.Authenticated -> RainWalletAuthState.Authenticated
    is TurnkeyAuthState.Unauthenticated -> RainWalletAuthState.Unauthenticated
}
