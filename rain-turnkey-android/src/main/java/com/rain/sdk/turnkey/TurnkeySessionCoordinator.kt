package com.rain.sdk.turnkey

import com.rain.sdk.error.RainError
import com.turnkey.core.models.AuthState
import com.turnkey.core.models.Session
import com.turnkey.core.models.errors.TurnkeyKotlinError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Guards every Turnkey call behind session-expiry checks, proactive refresh, refresh-on-401
 * retry, and transient-failure backoff, per [TurnkeySessionPolicy], for Turnkey's externally-owned
 * session.
 *
 * Terminal auth failures always surface as [RainError.TokenExpired], advance [deathEpoch] and
 * fire the host's `onSessionExpired` hook once per session death; the hook re-arms when a live
 * session is seen again, the count only grows. A session Turnkey refuses to refresh is cleared
 * with the failure (see [clearRevokedSession]), so [currentState] and [sessionStates] read it as
 * dead from then on instead of trusting its stored expiry.
 */
internal class TurnkeySessionCoordinator(
    private val turnkey: TurnkeyContextProtocol,
    private val policy: TurnkeySessionPolicy = TurnkeySessionPolicy(),
    private val onSessionExpired: (() -> Unit)? = null,
    private val nowEpochSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val retryDelay: suspend (Long) -> Unit = { delay(it.milliseconds) },
) {
    private val refreshLock = Mutex()
    private val expiryNotified = AtomicBoolean(false)
    private val monitoringStarted = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    /** Whether a session was ever observed — only an existing session can "die". */
    private val sawSession = AtomicBoolean(false)

    /**
     * Set by a deliberate logout so the Active→dead transition it causes does not fire the host's
     * re-auth hook (whose contract forbids re-entering the SDK). The death still advances
     * [deathEpoch], so cached accounts go stale on logout too. Sticky until the watcher next sees an
     * Active session, because the watcher observes the transition on its own coroutine after the
     * clear call has already returned.
     */
    private val hostHookSuppressed = AtomicBoolean(false)

    /**
     * Counts session deaths and replacements. Cached account state carries the value it was
     * resolved under and is stale once this has moved on: the previous user's addresses must never
     * serve a later login. A counter rather than a callback list, so a discarded manager leaves
     * nothing registered here. Advanced before the host hook runs, so anything the hook re-reads
     * is already stale. Read-only outside this class: only the coordinator advances it.
     */
    val deathEpoch: Int get() = deaths.get()
    private val deaths = AtomicInteger(0)

    /** Marks the next session death as intentional: the host hook stays silent for it. */
    fun suppressNextHostHook() {
        hostHookSuppressed.set(true)
    }

    /** Undoes [suppressNextHostHook] when the intentional death did not happen after all. */
    fun releaseHostHookSuppression() {
        hostHookSuppressed.set(false)
    }

    /**
     * A login replaced the live session with another one (Active→Active, which the watcher does
     * not treat as a death): advances [deathEpoch] — cached accounts belong to the previous user —
     * without the host hook, and without arming the death latch.
     */
    fun notifySessionReplaced() {
        if (stopped.get()) return
        deaths.incrementAndGet()
    }

    /** Snapshot of the session state as seen right now. */
    fun currentState(): TurnkeySessionState =
        deriveState(turnkey.authState.value, turnkey.session)

    /**
     * The session state over time. Re-derives on every Turnkey auth/session emission, and —
     * because a session passing its expiry emits nothing by itself — re-checks at the active
     * session's expiry instant, looping in case the timer wakes a hair early (rounding, or
     * monotonic-vs-wall-clock drift).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionStates: Flow<TurnkeySessionState> =
        combine(turnkey.authState, turnkey.sessionFlow) { auth, session -> auth to session }
            .transformLatest { (auth, session) ->
                var state = deriveState(auth, session)
                emit(state)
                while (state is TurnkeySessionState.Active && session != null) {
                    val waitMs = ((session.expiry - nowEpochSeconds()) * 1000).toLong() +
                        EXPIRY_RECHECK_SLACK_MS
                    delay(waitMs.coerceAtLeast(EXPIRY_RECHECK_SLACK_MS).milliseconds)
                    val next = deriveState(auth, session)
                    if (next != state) emit(next)
                    state = next
                }
            }
            .distinctUntilChanged()

    /** Idempotent call (balances, history, status reads): transient failures are retried. */
    suspend fun <T> executeRead(block: suspend (Session, TurnkeyClientProtocol) -> T): T =
        execute(idempotent = true, block)

    /** Non-idempotent call (sends, signing): retried only after a session refresh on 401. */
    suspend fun <T> executeWrite(block: suspend (Session, TurnkeyClientProtocol) -> T): T =
        execute(idempotent = false, block)

    /**
     * Forces a session refresh through Turnkey regardless of remaining lifetime. Throws
     * [RainError.TokenExpired] (after firing the expiry hook) when the refresh fails.
     */
    suspend fun refreshNow() {
        if (turnkey.session != null) sawSession.set(true)
        val outcome = refreshLock.withLock {
            // A caller that cleared the session while this one waited on the lock left nothing to
            // refresh, and the vendor would fall back to its default session key.
            if (turnkey.session == null) RefreshOutcome.Dead(null) else refreshOutcome()
        }
        if (outcome is RefreshOutcome.Dead) expireAndThrow(outcome.cause)
        expiryNotified.set(false)
    }

    /** Permanently silences this coordinator: no watcher, and the hook can never fire again. */
    fun stop() {
        stopped.set(true)
    }

    /**
     * Starts the passive watcher that fires `onSessionExpired` when an active session dies
     * without any Rain call in flight (Turnkey's expiry timer clearing it, a logout, or the
     * JWT lapsing while the app is backgrounded). Safe to call more than once.
     *
     * Only Active→dead transitions notify: a session that is already dead when monitoring
     * starts (cold launch before login) is not a "death" — it surfaces through
     * [currentState] or the first wallet call instead.
     */
    fun startMonitoring(scope: CoroutineScope) {
        if (stopped.get()) {
            Timber.w("Rain SDK: wallet session watcher not started — this provider was closed; build a new one")
            return
        }
        if (!monitoringStarted.compareAndSet(false, true)) return
        scope.launch {
            var previous: TurnkeySessionState? = null
            sessionStates.collect { state ->
                when (state) {
                    is TurnkeySessionState.Active -> {
                        sawSession.set(true)
                        expiryNotified.set(false)
                        hostHookSuppressed.set(false)
                    }
                    is TurnkeySessionState.Expired,
                    is TurnkeySessionState.Unauthenticated ->
                        if (previous is TurnkeySessionState.Active) notifyExpired()
                    // Transitional — keep `previous` so Active → Loading → Unauthenticated
                    // still reads as a death of the active session.
                    is TurnkeySessionState.Loading -> return@collect
                }
                previous = state
            }
        }
    }

    private suspend fun <T> execute(
        idempotent: Boolean,
        block: suspend (Session, TurnkeyClientProtocol) -> T
    ): T {
        var refreshedAfterAuthFailure = false
        var transientRetries = 0
        var backoffMs = policy.initialRetryDelayMs
        while (true) {
            val session = ensureValidSession()
            val client = turnkey.turnkeyClient ?: expireAndThrow()
            try {
                return block(session, client)
            } catch (e: Exception) {
                // A cancellation leaves as itself, bare or wrapped in the vendor's failure type (its
                // own calls catch Throwable): the caller going away is not a failure to classify.
                e.cancellationInChain()?.let { throw it }
                when {
                    isAuthFailure(e) -> {
                        // A 401 on the request itself means Turnkey rejected it before executing
                        // anything, so one refresh-and-retry is safe even for a send. A block that
                        // polls after acceptance must not let a poll-phase 401 out as such: both
                        // sends sort those inside the write (TurnkeyManager.sendOutcome), so one
                        // never reaches this branch.
                        if (refreshedAfterAuthFailure || !policy.autoRefresh) expireAndThrow(e)
                        refreshedAfterAuthFailure = true
                        val outcome = refreshLock.withLock {
                            // A concurrent caller may have already rotated the rejected
                            // session; refreshing again would waste a key rotation and could
                            // report a false session death. One that found it revoked has
                            // cleared it, and there is nothing left to refresh.
                            val current = turnkey.session ?: return@withLock RefreshOutcome.Dead(null)
                            if (current.publicKey != session.publicKey) {
                                RefreshOutcome.Fresh(current)
                            } else {
                                refreshOutcome()
                            }
                        }
                        if (outcome is RefreshOutcome.Dead) expireAndThrow(outcome.cause)
                    }
                    idempotent && transientRetries < policy.maxTransientRetries && isTransient(e) -> {
                        transientRetries++
                        Timber.w(
                            e,
                            "Rain SDK: transient wallet backend failure, retry %d/%d",
                            transientRetries,
                            policy.maxTransientRetries
                        )
                        retryDelay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(policy.maxRetryDelayMs)
                    }
                    // Neither a session problem nor retryable. It leaves as a RainError, never as
                    // a vendor type: core passes a RainError through with its code and would otherwise see
                    // a Turnkey exception it cannot classify.
                    else -> {
                        logUnmappedFailure(e)
                        throw TurnkeyErrorMapping.map(e)
                    }
                }
            }
        }
    }

    /**
     * The current session, refreshed first when it is expired or within the refresh buffer.
     * Throws [RainError.TokenExpired] when no usable session can be produced.
     */
    private suspend fun ensureValidSession(): Session {
        // Turnkey restores persisted sessions asynchronously after launch; a call racing that
        // restore must wait it out rather than misreport a valid session as expired.
        if (turnkey.session == null && turnkey.authState.value == AuthState.loading) {
            withTimeoutOrNull(AUTH_RESTORE_TIMEOUT_MS.milliseconds) {
                turnkey.authState.first { it != AuthState.loading }
            }
        }
        val session = turnkey.session ?: expireAndThrow()
        sawSession.set(true)
        val now = nowEpochSeconds()
        if (session.expiry - now > policy.refreshBufferSeconds) {
            expiryNotified.set(false)
            return session
        }
        if (!policy.autoRefresh) {
            if (session.expiry <= now) expireAndThrow()
            // Inside the buffer but not yet expired: without autoRefresh the call proceeds.
            return session
        }
        val outcome = refreshLock.withLock {
            // A concurrent caller may have refreshed while this one waited on the lock.
            val current = turnkey.session ?: return@withLock RefreshOutcome.Dead(null)
            if (current.expiry - nowEpochSeconds() > policy.refreshBufferSeconds) {
                return@withLock RefreshOutcome.Fresh(current)
            }
            refreshOutcome()
        }
        return when (outcome) {
            is RefreshOutcome.Fresh -> outcome.session
            is RefreshOutcome.Dead -> expireAndThrow(outcome.cause)
        }
    }

    /** Outcome of a locked refresh attempt. Only ever built while holding [refreshLock]. */
    private sealed interface RefreshOutcome {
        data class Fresh(val session: Session) : RefreshOutcome
        data class Dead(val cause: Exception?) : RefreshOutcome
    }

    /**
     * Refreshes through Turnkey and reports the outcome instead of throwing, so the caller fires
     * the expiry hook only after it has released [refreshLock]: a host reacting to the hook by
     * calling back into the coordinator must never find the lock still held. The watcher may fire
     * the hook earlier, while the lock is still held, when [clearRevokedSession] flips the vendor's
     * state; that path never waits on the lock. A refresh Turnkey refused clears the stored session
     * first. One that failed for any other reason (offline, a 5xx) leaves it stored. A refresh
     * whose session a login replaced while it was on the network reports the new session as fresh
     * whatever the failure, because the failure was the old session's and the new one is live.
     */
    private suspend fun refreshOutcome(): RefreshOutcome {
        val refreshed = turnkey.session
        val refreshedKey = turnkey.selectedSessionKey
        return try {
            turnkey.refreshSession(policy.refreshExpirationSeconds?.toString())
            turnkey.session?.let { RefreshOutcome.Fresh(it) } ?: RefreshOutcome.Dead(null)
        } catch (e: Exception) {
            // A cancellation leaves as itself, bare or wrapped: FailedToRefreshSession wraps whatever
            // ended the refresh, and a screen closed mid-refresh is not a session death.
            e.cancellationInChain()?.let { throw it }
            // The rule the 401 retry applies before it refreshes: a login that replaced the session
            // meanwhile holds the live one, and a death reported over it would sign the user out of
            // the session they just created.
            val current = turnkey.session
            if (current != null && refreshed != null && current.publicKey != refreshed.publicKey) {
                Timber.w(e, "Rain SDK: wallet session refresh failed, but a login replaced the session meanwhile")
                return RefreshOutcome.Fresh(current)
            }
            Timber.w(e, "Rain SDK: wallet session refresh failed")
            if (isAuthFailure(e)) clearRevokedSession(refreshedKey)
            RefreshOutcome.Dead(e)
        }
    }

    /**
     * Clears the stored session after Turnkey refused to refresh it with HTTP 401 on the stamp-login
     * request (sdk-kotlin 2.1.0 `TurnkeyContext.refreshSession` wraps it in `FailedToRefreshSession`).
     * The session was revoked out from under this device, which is what a login on another device
     * does (`invalidateExisting`), and the stored copy still carries its local expiry. Without the
     * clear, [currentState], [sessionStates] and every read derived from them would keep saying
     * Active until the JWT lapsed, up to the session's whole lifetime, while every call failed. The
     * clear flips them with the failure. The watcher then sees Active → Unauthenticated, and
     * [expiryNotified] keeps the host hook at one firing for the one death. The clear names the key
     * the refresh was for and is skipped when another key is selected by now: the vendor selects a
     * session in two steps, key first and then session, so a login halfway through still shows the
     * old session under its new key, and that login clears the superseded session itself. Runs
     * under [refreshLock], so a caller waiting on the lock finds no session instead of refreshing a
     * cleared one. A clear that fails is logged, and the [RainError.TokenExpired] the caller throws
     * is what surfaces. The vendor's `InvalidSession` is an auth failure too, but it is thrown
     * before any request, when there is no session to refresh, so it leaves nothing to clear.
     */
    @Suppress("TooGenericExceptionCaught") // best-effort cleanup: the TokenExpired is what surfaces
    private suspend fun clearRevokedSession(refreshedKey: String?) {
        val key = refreshedKey ?: return
        if (turnkey.selectedSessionKey != key) return
        try {
            turnkey.clearSession(key)
        } catch (e: Exception) {
            e.cancellationInChain()?.let { throw it }
            Timber.w(e, "Rain SDK: could not clear the revoked wallet session")
        }
    }

    private fun expireAndThrow(cause: Throwable? = null): Nothing {
        if (cause != null) Timber.w(cause, "Rain SDK: wallet session is no longer usable")
        notifyExpired()
        throw RainError.TokenExpired()
    }

    private fun notifyExpired() {
        // A closed coordinator belongs to a discarded provider — it must never notify.
        if (stopped.get()) return
        // Never logged in is not a session death: only notify once a session has been seen.
        if (!sawSession.get()) return
        if (!expiryNotified.compareAndSet(false, true)) return
        // Stale the caches first: whatever the host hook re-reads must already be invalid.
        deaths.incrementAndGet()
        // A deliberate logout is not a death the host has to recover from.
        if (hostHookSuppressed.getAndSet(false)) return
        onSessionExpired?.let { hook ->
            runCatching { hook() }
                .onFailure { Timber.w(it, "Rain SDK: onSessionExpired callback threw") }
        }
    }

    private fun deriveState(auth: AuthState, session: Session?): TurnkeySessionState = when {
        auth == AuthState.loading -> TurnkeySessionState.Loading
        session == null || auth == AuthState.unauthenticated -> TurnkeySessionState.Unauthenticated
        session.expiry <= nowEpochSeconds() -> TurnkeySessionState.Expired
        else -> TurnkeySessionState.Active(session.expiry)
    }

    /**
     * Warning, not error: reads on fallback paths land here in normal operation, and the RainError
     * itself is what the host acts on. A RainError raised inside the block is our own verdict and
     * needs no vendor log. A vendor HTTP failure carries the response body in its message and the
     * typed client's `ActivityNotCompleted` prints the whole activity from `toString()`, so neither
     * reaches the log as a throwable: the line carries the wrapper's class and the sanitized text,
     * the same text the mapper's exit hands the host (`withoutResponseBody`).
     */
    private fun logUnmappedFailure(e: Exception) {
        if (e is RainError) return
        val vendor = e.causeChain().firstOrNull { it.carriesVendorData() }
        if (vendor == null) {
            Timber.w(e, "Rain SDK: wallet backend call failed")
        } else {
            Timber.w("Rain SDK: wallet backend call failed: %s: %s", e.javaClass.simpleName, vendor.sanitizedForHost().message)
        }
    }

    private fun isAuthFailure(e: Throwable): Boolean = e.causeChain().any { t ->
        t is RainError.TokenExpired ||
            t is TurnkeyKotlinError.InvalidSession ||
            TurnkeyErrorMapping.turnkeyHttpStatus(t) == 401
    }

    private fun isTransient(e: Throwable): Boolean = e.causeChain().any { t ->
        t is IOException ||
            TurnkeyErrorMapping.turnkeyHttpStatus(t)?.let { isTransientStatus(it) } == true
    }

    private fun isTransientStatus(status: Int): Boolean =
        status == 408 || status == 429 || status in 500..599

    internal companion object {
        /** Extra wait past the expiry instant so an early timer wake cannot re-derive Active. */
        private const val EXPIRY_RECHECK_SLACK_MS = 50L

        /**
         * Longest a call waits for Turnkey's async session restore before judging the session. The
         * managed auth's logout waits on a restore in flight for the same span, so it reads this.
         */
        const val AUTH_RESTORE_TIMEOUT_MS = 10_000L
    }
}
