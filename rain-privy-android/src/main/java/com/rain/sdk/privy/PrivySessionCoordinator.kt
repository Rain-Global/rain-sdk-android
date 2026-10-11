package com.rain.sdk.privy

import com.rain.sdk.error.RainError
import io.privy.auth.AuthState
import io.privy.auth.PrivyUser
import io.privy.network.PrivyApiException
import io.privy.sdk.Privy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guards every Privy call behind auth-state checks and transient-failure backoff, and turns a
 * dying session into a host-visible signal (the `onSessionExpired` hook plus [sessionStates]).
 *
 * Unlike the Turnkey coordinator there is no refresh machinery: the Privy SDK single-flights
 * its own session refresh internally before every wallet/indexer call, so an auth failure that
 * reaches Rain means Privy already tried and the session is truly dead. Terminal auth failures
 * surface as [RainError.TokenExpired] and fire the hook once per session death; the hook
 * re-arms when a live session is seen again. Only a session Privy refuses or has dropped is a
 * death: a call or a [refreshNow] that got no answer from the network leaves as
 * [RainError.NetworkError], with the session and the hook untouched.
 */
internal class PrivySessionCoordinator(
    private val privy: Privy,
    private val policy: PrivySessionPolicy = PrivySessionPolicy(),
    private val onSessionExpired: (() -> Unit)? = null,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val expiryNotified = AtomicBoolean(false)
    private val monitoringStarted = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    /** Whether an authenticated session was ever observed — only an existing session can "die". */
    private val sawSession = AtomicBoolean(false)

    /** Runs when an active session dies (before the host hook) — e.g. cached-account eviction. */
    private val deathCallbacks = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Snapshot of the session state as seen right now. Reads Privy's `authState.value`, which
     * can briefly lag the source of truth; the guarded calls re-check via the vendor anyway.
     */
    fun currentState(): PrivySessionState = deriveState(privy.authState.value)

    /** The session state over time, derived from Privy's own auth-state flow. */
    val sessionStates: Flow<PrivySessionState> =
        privy.authState.map { deriveState(it) }.distinctUntilChanged()

    /** Registers a callback invoked once per session death, before the host hook. */
    fun onSessionDeath(callback: () -> Unit) {
        deathCallbacks += callback
    }

    /** Permanently silences this coordinator: no watcher, and the hook can never fire again. */
    fun stop() {
        stopped.set(true)
    }

    /**
     * Starts the passive watcher that fires `onSessionExpired` (and the death callbacks) when
     * an active session dies without any Rain call in flight. Safe to call more than once.
     *
     * Only Active→Unauthenticated transitions notify: a session that is already dead when
     * monitoring starts surfaces through [currentState] or the first wallet call instead.
     */
    fun startMonitoring(scope: CoroutineScope) {
        if (stopped.get()) {
            Timber.w("Rain SDK: Privy session watcher not started — this provider was closed; build a new one")
            return
        }
        if (!monitoringStarted.compareAndSet(false, true)) return
        scope.launch {
            var previous: PrivySessionState? = null
            sessionStates.collect { state ->
                when (state) {
                    is PrivySessionState.Active -> {
                        sawSession.set(true)
                        expiryNotified.set(false)
                    }
                    is PrivySessionState.Unauthenticated ->
                        if (previous is PrivySessionState.Active) notifyDeath()
                    // Transitional (startup restore / offline-unverified): keep `previous` so
                    // Active → Loading/Unverified → Unauthenticated still reads as a death.
                    is PrivySessionState.Loading,
                    is PrivySessionState.Unverified -> return@collect
                }
                previous = state
            }
        }
    }

    /** Idempotent call (address reads, history): transient failures are retried with backoff. */
    suspend fun <T> executeRead(block: suspend () -> T): T = execute(idempotent = true, block)

    /** Non-idempotent call (sends, signing): never retried. */
    suspend fun <T> executeWrite(block: suspend () -> T): T = execute(idempotent = false, block)

    /**
     * Forces a session refresh through Privy (`PrivyUser.refresh`). Throws
     * [RainError.TokenExpired], after firing the expiry hook, when Privy has no session, refuses
     * the refresh on auth grounds, or dropped the session while answering (privy-core 0.15.0 logs
     * the user out on any 4xx to its token refresh, `RealInternalAuthManager.handleAuthResponse`,
     * which `PrivyUser.refresh` runs when the JWT has expired). A refresh that failed with the
     * session still in place says nothing about it, so it leaves as that failure with the session
     * and the hook untouched: [RainError.NetworkError] when the request got no answer (the device
     * offline, a transport failure), [RainError.ProviderError] otherwise. A session the vendor
     * restored without a network answer is verified with it first, see [verifiedAuthState], and
     * throws [RainError.NetworkError] only if that still fails. The next refresh, by the host or by
     * Privy before a wallet call, decides.
     */
    suspend fun refreshNow() {
        val state = verifiedAuthState()
        val user = (state as? AuthState.Authenticated)?.user ?: run {
            val error = noUserError(state)
            if (error is RainError.TokenExpired) expireAndThrow() else throw error
        }
        sawSession.set(true)
        user.refresh().exceptionOrNull()?.let { e -> throw refreshFailure(e) }
        expiryNotified.set(false)
    }

    /**
     * What a refresh that failed with [e] leaves as: the caller's cancellation as itself, bare or
     * wrapped by the vendor; a death when Privy refused the refresh or, by its own state, dropped
     * the session while answering; otherwise the mapped failure, with the session untouched.
     */
    private suspend fun refreshFailure(e: Throwable): Throwable {
        val cancellation = if (e is CancellationException) e else e.cancellationInChain()
        return when {
            cancellation != null -> cancellation
            isAuthFailure(e) || sessionDropped() -> expireAndThrow(e)
            else -> {
                Timber.w(e, "Rain SDK: Privy session refresh failed; the session is unchanged")
                PrivyErrorMapping.map(e, idempotent = true)
            }
        }
    }

    /**
     * The Privy user for a call, or the failure for a call that has none, after one attempt to
     * settle an unverified session through [verifiedAuthState]. A session the vendor still cannot
     * verify is a [RainError.NetworkError], not a death, and a guarded call does not retry it: the
     * attempt is a vendor request that can wait out the vendor's HTTP timeout (60 s in privy-core
     * 0.15.0), so a read, and the `create()` probe that is one, asks once and leaves, and the
     * host's next call asks again. Any other state without a user is [RainError.TokenExpired],
     * which the guarded call turns into a death.
     */
    suspend fun requireUser(): PrivyUser {
        val state = verifiedAuthState()
        return (state as? AuthState.Authenticated)?.user ?: throw noUserError(state)
    }

    /**
     * The vendor's auth state, read once from its source of truth (`Privy.getAuthState`; the
     * public `authState` flow is a copy that can lag it), after asking the vendor to verify a
     * session it restored without a network answer. `AuthenticatedUnverified` is what a cold start
     * offline leaves behind, and also a restore that failed with a 5xx or a timeout while online;
     * the vendor re-verifies on its own only after the offline start (privy-core 0.15.0
     * `handleNetworkOfflineAtInit`, a one-shot listener), so every other case waits for
     * `Privy.onNetworkRestored()`, which the vendor documents for this and which is a no-op once
     * the state is settled.
     */
    private suspend fun verifiedAuthState(): AuthState {
        val state = privy.getAuthState()
        if (state !is AuthState.AuthenticatedUnverified) return state
        privy.onNetworkRestored()
        return privy.getAuthState()
    }

    /** Whether the vendor holds no session any more: its own answer to "did the session survive". */
    private suspend fun sessionDropped(): Boolean = privy.getAuthState() is AuthState.Unauthenticated

    private fun noUserError(state: AuthState): RainError =
        if (state is AuthState.AuthenticatedUnverified) {
            RainError.NetworkError("Session not verified yet; retry later")
        } else {
            RainError.TokenExpired()
        }

    private suspend fun <T> execute(idempotent: Boolean, block: suspend () -> T): T {
        // Privy restores persisted credentials asynchronously after launch; a call racing that
        // restore must wait it out rather than misreport a valid session as dead.
        awaitAuthReady()
        when (privy.authState.value) {
            // Note: this does not re-arm the hook — Privy's local state can keep reading
            // Authenticated after a server-side death, and each failing call must not re-fire.
            // Re-arming happens on the watcher's transition back to Active (a real re-login).
            is AuthState.Authenticated -> sawSession.set(true)
            is AuthState.Unauthenticated -> expireAndThrow()
            // Unverified proceeds: requireUser asks the vendor to verify it, once per call, and decides.
            else -> Unit
        }
        var transientRetries = 0
        var backoffMs = policy.initialRetryDelayMs
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                // The caller's cancellation leaves as itself, bare or wrapped by the vendor.
                val cancellation = if (e is CancellationException) e else e.cancellationInChain()
                when {
                    cancellation != null -> throw cancellation
                    // Privy already retried its own internal refresh before this surfaced, so
                    // an auth failure here is terminal — never retried by Rain.
                    isAuthFailure(e) -> expireAndThrow(e)
                    idempotent && transientRetries < policy.maxTransientRetries && isTransient(e) -> {
                        transientRetries++
                        Timber.w(
                            e,
                            "Rain SDK: transient Privy failure, retry %d/%d",
                            transientRetries,
                            policy.maxTransientRetries
                        )
                        retryDelay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(policy.maxRetryDelayMs)
                    }
                    // The vendor drops the session on a 4xx to the token refresh it runs inside a
                    // wallet call, which arrives as neither an auth failure nor a transient one.
                    sessionDropped() -> expireAndThrow(e)
                    // Neither an auth problem nor retryable. It leaves as a RainError, never as a
                    // vendor type: core passes a RainError through with its code and would otherwise see a
                    // Privy exception it cannot classify.
                    else -> {
                        logUnmappedFailure(e)
                        throw PrivyErrorMapping.map(e, idempotent)
                    }
                }
            }
        }
    }

    private suspend fun awaitAuthReady() {
        if (privy.authState.value !is AuthState.NotReady) return
        withTimeoutOrNull(AUTH_RESTORE_TIMEOUT_MS) {
            privy.authState.first { it !is AuthState.NotReady }
        }
    }

    private fun expireAndThrow(cause: Throwable? = null): Nothing {
        if (cause != null) Timber.w(cause, "Rain SDK: Privy session is no longer usable")
        notifyDeath()
        throw RainError.TokenExpired()
    }

    private fun notifyDeath() {
        // A closed coordinator belongs to a discarded provider — it must never notify.
        if (stopped.get()) return
        // Never logged in is not a session death: only notify once a session has been seen.
        if (!sawSession.get()) return
        if (!expiryNotified.compareAndSet(false, true)) return
        deathCallbacks.forEach { callback ->
            runCatching { callback() }
                .onFailure { Timber.w(it, "Rain SDK: session-death callback threw") }
        }
        onSessionExpired?.let { hook ->
            runCatching { hook() }
                .onFailure { Timber.w(it, "Rain SDK: onSessionExpired callback threw") }
        }
    }

    private fun deriveState(auth: AuthState): PrivySessionState = when (auth) {
        is AuthState.NotReady -> PrivySessionState.Loading
        is AuthState.Authenticated -> PrivySessionState.Active
        is AuthState.AuthenticatedUnverified -> PrivySessionState.Unverified
        is AuthState.Unauthenticated -> PrivySessionState.Unauthenticated
    }

    /**
     * Warning, not error: reads on fallback paths land here in normal operation, and the RainError
     * itself is what the host acts on. A RainError raised inside the block is our own verdict and
     * needs no vendor log.
     */
    private fun logUnmappedFailure(e: Exception) {
        if (e !is RainError) Timber.w(e, "Rain SDK: Privy call failed")
    }

    private fun isAuthFailure(e: Throwable): Boolean = e.causeChain().any {
        it is RainError.TokenExpired || PrivyErrorMapping.mapOrNull(it) is RainError.TokenExpired
    }

    /**
     * A request that got no answer (the network), or an HTTP answer that asks for a retry. Of the
     * statuses below only 408 and 429 ever arrive with their status: privy-core 0.15.0 reports a
     * 5xx status-less (see [PrivyErrorMapping.isNetworkFailure]), so a 5xx is not retried today. A
     * [RainError] raised inside the block is the coordinator's own verdict and never transient; the
     * one such error, [requireUser]'s on a session the vendor cannot verify, stands for a vendor
     * request already made, which is not repeated within the call.
     */
    private fun isTransient(e: Throwable): Boolean =
        PrivyErrorMapping.isNetworkFailure(e) ||
            e.causeChain().any { it is PrivyApiException && it.statusCode?.let(::isTransientStatus) == true }

    private fun isTransientStatus(status: Int): Boolean =
        status == 408 || status == 429 || status in 500..599

    private companion object {
        /** Longest a call waits for Privy's async credential restore before judging the session. */
        const val AUTH_RESTORE_TIMEOUT_MS = 10_000L
    }
}
