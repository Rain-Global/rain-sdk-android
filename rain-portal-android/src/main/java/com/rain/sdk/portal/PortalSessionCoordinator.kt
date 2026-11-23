package com.rain.sdk.portal

import com.rain.sdk.error.RainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guards every Portal call: auth classification, refresh-on-auth-failure (host re-mint + client
 * rebuild, single-flighted), transient backoff on reads, and the expiry hook (once per dead token).
 */
internal class PortalSessionCoordinator(
    private val policy: PortalSessionPolicy = PortalSessionPolicy(),
    private val onSessionTokenNeeded: (suspend () -> String?)? = null,
    private val onSessionExpired: (() -> Unit)? = null,
    private val installToken: suspend (String) -> Unit = {},
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val refreshLock = Mutex()
    private val expiryNotified = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    /** Bumped per successful install / failed refresh so in-flight callers see what they missed. */
    private val tokenEpoch = AtomicInteger(0)
    private val failedRefreshes = AtomicInteger(0)

    private val _state = MutableStateFlow<PortalSessionState>(PortalSessionState.Unknown)

    private val deathCallbacks = CopyOnWriteArrayList<() -> Unit>()

    val sessionStates: StateFlow<PortalSessionState> = _state.asStateFlow()

    fun currentState(): PortalSessionState = _state.value

    /** Runs once per session death, before the host hook. */
    fun onSessionDeath(callback: () -> Unit) {
        deathCallbacks += callback
    }

    /** Permanently silences the hook. */
    fun stop() {
        stopped.set(true)
    }

    /** Idempotent call: transient failures are retried. */
    suspend fun <T> executeRead(block: suspend () -> T): T = execute(idempotent = true, block)

    /** Non-idempotent call: retried only once, after a token refresh. */
    suspend fun <T> executeWrite(block: suspend () -> T): T = execute(idempotent = false, block)

    /** Re-mints and rebuilds now; throws [RainError.TokenExpired] (after the hook) on failure. */
    suspend fun refreshNow() {
        val outcome = refreshLock.withLock { refreshOutcome() }
        if (outcome is RefreshOutcome.Dead) expireAndThrow(outcome.cause)
    }

    /** Installs a host-minted token, rebuilding the client around it. */
    suspend fun installNow(token: String) {
        require(token.isNotBlank()) { "Portal session token must not be empty" }
        val outcome = refreshLock.withLock {
            installOutcome(token).also { if (it is RefreshOutcome.Dead) failedRefreshes.incrementAndGet() }
        }
        if (outcome is RefreshOutcome.Dead) expireAndThrow(outcome.cause)
    }

    private suspend fun <T> execute(idempotent: Boolean, block: suspend () -> T): T {
        var refreshedAfterAuthFailure = false
        var transientRetries = 0
        var backoffMs = policy.initialRetryDelayMs
        while (true) {
            // Pre-call guard: a known-dead token is re-minted first or fails fast.
            if (_state.value is PortalSessionState.Expired) {
                if (refreshedAfterAuthFailure || !canRefresh()) expireAndThrow()
                refreshedAfterAuthFailure = true
                refreshOrExpire(
                    epochSeen = tokenEpoch.get(),
                    failuresSeen = failedRefreshes.get(),
                    cause = null
                )
            }
            val epoch = tokenEpoch.get()
            val failures = failedRefreshes.get()
            try {
                val result = block()
                markActive()
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when {
                    // Portal rejects a bad token before executing, so one retry is safe for sends too.
                    isAuthFailure(e) -> {
                        if (refreshedAfterAuthFailure) expireAndThrow(e)
                        refreshedAfterAuthFailure = true
                        // Swapped while in flight: the failure belongs to the old token.
                        if (tokenEpoch.get() != epoch) continue
                        if (!canRefresh()) expireAndThrow(e)
                        refreshOrExpire(epochSeen = epoch, failuresSeen = failures, cause = e)
                    }
                    idempotent && transientRetries < policy.maxTransientRetries && isTransient(e) -> {
                        transientRetries++
                        Timber.w(
                            e,
                            "Rain SDK: transient Portal failure, retry %d/%d",
                            transientRetries,
                            policy.maxTransientRetries
                        )
                        retryDelay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(policy.maxRetryDelayMs)
                    }
                    // Neither a token problem nor retryable. It leaves as a RainError, never as a
                    // vendor type: core passes a RainError through with its code and would otherwise see a
                    // Portal exception it cannot classify.
                    else -> {
                        logUnmappedFailure(e)
                        throw PortalErrorMapping.map(e)
                    }
                }
            }
        }
    }

    private fun canRefresh(): Boolean = policy.autoRefresh && onSessionTokenNeeded != null

    private sealed interface RefreshOutcome {
        data object Fresh : RefreshOutcome
        data class Dead(val cause: Throwable?) : RefreshOutcome
    }

    private suspend fun refreshOrExpire(epochSeen: Int, failuresSeen: Int, cause: Throwable?) {
        val outcome = refreshLock.withLock {
            when {
                // Another caller already swapped or already failed on this token: share it.
                tokenEpoch.get() != epochSeen -> RefreshOutcome.Fresh
                failedRefreshes.get() != failuresSeen -> RefreshOutcome.Dead(null)
                else -> refreshOutcome()
            }
        }
        if (outcome is RefreshOutcome.Dead) expireAndThrow(outcome.cause ?: cause)
    }

    /** Reports instead of throwing so [refreshLock] is released before the hook fires. */
    private suspend fun refreshOutcome(): RefreshOutcome = attempt().also { outcome ->
        if (outcome is RefreshOutcome.Dead) failedRefreshes.incrementAndGet()
    }

    private suspend fun attempt(): RefreshOutcome {
        val mint = onSessionTokenNeeded ?: return RefreshOutcome.Dead(null)
        val before = _state.value
        _state.value = PortalSessionState.Refreshing
        val token = try {
            mint()
        } catch (e: CancellationException) {
            _state.compareAndSet(PortalSessionState.Refreshing, before)
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Rain SDK: Portal session token re-mint failed")
            return RefreshOutcome.Dead(e)
        }
        if (token.isNullOrBlank()) {
            Timber.w("Rain SDK: host declined to re-mint the Portal session token")
            return RefreshOutcome.Dead(null)
        }
        return installOutcome(token)
    }

    private suspend fun installOutcome(token: String): RefreshOutcome {
        val before = _state.value
        _state.value = PortalSessionState.Refreshing
        try {
            installToken(token)
        } catch (e: CancellationException) {
            _state.compareAndSet(PortalSessionState.Refreshing, before)
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Rain SDK: Portal client rebuild with the new session token failed")
            return RefreshOutcome.Dead(e)
        }
        tokenEpoch.incrementAndGet()
        expiryNotified.set(false)
        markActive()
        return RefreshOutcome.Fresh
    }

    private fun markActive() {
        if (!stopped.get()) _state.value = PortalSessionState.Active
    }

    private fun expireAndThrow(cause: Throwable? = null): Nothing {
        if (cause != null) Timber.w(cause, "Rain SDK: Portal session token is no longer usable")
        notifyDeath()
        throw RainError.TokenExpired()
    }

    private fun notifyDeath() {
        _state.value = PortalSessionState.Expired
        if (stopped.get()) return
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

    /**
     * A warning, not an error: a read on a fallback path lands here in normal operation, and the
     * host acts on the RainError itself. Our own RainError raised inside the block is a verdict,
     * not a vendor failure, and goes unlogged. Portal logs the throwable as it is; Turnkey's twin
     * sanitizes vendor data first.
     */
    private fun logUnmappedFailure(e: Exception) {
        if (e !is RainError) Timber.w(e, "Rain SDK: Portal call failed")
    }

    private fun isAuthFailure(e: Throwable): Boolean = anyInChain(e) {
        it is RainError.TokenExpired || PortalErrorMapping.mapAuthOrNull(it) is RainError.TokenExpired
    }

    private fun isTransient(e: Throwable): Boolean = anyInChain(e) {
        PortalErrorMapping.isTransient(it)
    }

    private inline fun anyInChain(e: Throwable, predicate: (Throwable) -> Boolean): Boolean {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (predicate(current)) return true
            current = current.cause.takeIf { it !== current }
            depth++
        }
        return false
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 8
    }
}
