package com.rain.sdk.portal

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import io.portalhq.android.exceptions.PortalException
import io.portalhq.android.utils.errors.PortalError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class PortalSessionCoordinatorTest {

    private class RecordingDelay {
        val delays = mutableListOf<Long>()
        val fn: suspend (Long) -> Unit = { delays += it }
    }

    private class InstallRecorder {
        val tokens = mutableListOf<String>()
        var failWith: Throwable? = null
        val fn: suspend (String) -> Unit = { token ->
            failWith?.let { throw it }
            tokens += token
        }
    }

    private fun coordinator(
        policy: PortalSessionPolicy = PortalSessionPolicy(),
        onSessionTokenNeeded: (suspend () -> String?)? = null,
        onSessionExpired: (() -> Unit)? = null,
        installer: InstallRecorder = InstallRecorder(),
        delayRecorder: RecordingDelay = RecordingDelay(),
    ) = PortalSessionCoordinator(
        policy = policy,
        onSessionTokenNeeded = onSessionTokenNeeded,
        onSessionExpired = onSessionExpired,
        installToken = installer.fn,
        retryDelay = delayRecorder.fn,
    )

    private fun unauthorized() = PortalException.Api.HttpUnauthorized("401 - Unauthorized")

    private fun invalidApiKey() = PortalException.Mpc.MpcResultError(
        PortalError(rawCode = 202, rawMessage = "invalid api key", id = "")
    )

    private fun httpFailed(status: Int) = PortalException.Api.HttpRequestFailed("$status - upstream")

    // ---------- state ----------

    @Test
    fun `starts Unknown and becomes Active after a successful call`() = runBlocking {
        val coordinator = coordinator()
        assertThat(coordinator.currentState()).isEqualTo(PortalSessionState.Unknown)

        coordinator.executeRead { "ok" }

        assertThat(coordinator.currentState()).isEqualTo(PortalSessionState.Active)
    }

    // ---------- auth failure without refresh ----------

    @Test
    fun `auth failure without a refresh hook surfaces TokenExpired and fires the hook once`() {
        var hookCalls = 0
        val coordinator = coordinator(onSessionExpired = { hookCalls++ })

        repeat(2) {
            assertThrows(RainError.TokenExpired::class.java) {
                runBlocking { coordinator.executeRead { throw unauthorized() } }
            }
        }

        assertThat(hookCalls).isEqualTo(1)
        assertThat(coordinator.currentState()).isEqualTo(PortalSessionState.Expired)
    }

    @Test
    fun `MPC invalid-api-key is an auth failure too`() {
        var hookCalls = 0
        val coordinator = coordinator(onSessionExpired = { hookCalls++ })

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeWrite { throw invalidApiKey() } }
        }
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `an already-mapped TokenExpired wrapped in ProviderError is an auth failure`() {
        var hookCalls = 0
        val coordinator = coordinator(onSessionExpired = { hookCalls++ })

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                coordinator.executeRead { throw RainError.ProviderError(RainError.TokenExpired()) }
            }
        }
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `once Expired, calls fail fast without running the block`() {
        val coordinator = coordinator()
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead { throw unauthorized() } }
        }

        var blockRuns = 0
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead { blockRuns++ } }
        }
        assertThat(blockRuns).isEqualTo(0)
    }

    @Test
    fun `autoRefresh off never consults the re-mint hook`() {
        var mintCalls = 0
        val coordinator = coordinator(
            policy = PortalSessionPolicy(autoRefresh = false),
            onSessionTokenNeeded = {
                mintCalls++
                "new-token"
            },
        )

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead { throw unauthorized() } }
        }
        assertThat(mintCalls).isEqualTo(0)
    }

    // ---------- refresh (re-mint + rebuild) ----------

    @Test
    fun `auth failure re-mints, installs the token and retries the read once`() = runBlocking {
        val installer = InstallRecorder()
        var hookCalls = 0
        val coordinator = coordinator(
            onSessionTokenNeeded = { "new-token" },
            onSessionExpired = { hookCalls++ },
            installer = installer,
        )
        var attempts = 0

        val result = coordinator.executeRead {
            attempts++
            if (attempts == 1) throw unauthorized()
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(attempts).isEqualTo(2)
        assertThat(installer.tokens).containsExactly("new-token")
        assertThat(hookCalls).isEqualTo(0)
        assertThat(coordinator.currentState()).isEqualTo(PortalSessionState.Active)
    }

    @Test
    fun `a write rejected with 401 is retried once after the token swap`() = runBlocking {
        val coordinator = coordinator(onSessionTokenNeeded = { "new-token" })
        var attempts = 0

        val hash = coordinator.executeWrite {
            attempts++
            if (attempts == 1) throw invalidApiKey()
            "0xhash"
        }

        assertThat(hash).isEqualTo("0xhash")
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `auth failure after a refresh is terminal - no second re-mint`() {
        var mintCalls = 0
        var hookCalls = 0
        val coordinator = coordinator(
            onSessionTokenNeeded = {
                mintCalls++
                "still-bad"
            },
            onSessionExpired = { hookCalls++ },
        )
        var attempts = 0

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                coordinator.executeRead {
                    attempts++
                    throw unauthorized()
                }
            }
        }

        assertThat(attempts).isEqualTo(2)
        assertThat(mintCalls).isEqualTo(1)
        assertThat(hookCalls).isEqualTo(1)
        assertThat(coordinator.currentState()).isEqualTo(PortalSessionState.Expired)
    }

    @Test
    fun `host declining the re-mint surfaces TokenExpired and fires the hook`() {
        val installer = InstallRecorder()
        var hookCalls = 0
        val coordinator = coordinator(
            onSessionTokenNeeded = { null },
            onSessionExpired = { hookCalls++ },
            installer = installer,
        )

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead { throw unauthorized() } }
        }
        assertThat(installer.tokens).isEmpty()
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `re-mint hook throwing surfaces TokenExpired, not the hook's exception`() {
        var hookCalls = 0
        val coordinator = coordinator(
            onSessionTokenNeeded = { throw IllegalStateException("backend down") },
            onSessionExpired = { hookCalls++ },
        )

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead { throw unauthorized() } }
        }
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `client rebuild failing surfaces TokenExpired and fires the hook`() {
        val installer = InstallRecorder().apply { failWith = IllegalStateException("rebuild failed") }
        var hookCalls = 0
        val coordinator = coordinator(
            onSessionTokenNeeded = { "new-token" },
            onSessionExpired = { hookCalls++ },
            installer = installer,
        )

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead { throw unauthorized() } }
        }
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `an Expired session is re-minted before the next call instead of failing fast`() = runBlocking {
        var mint: String? = null
        val installer = InstallRecorder()
        var hookCalls = 0
        val coordinator = coordinator(
            onSessionTokenNeeded = { mint },
            onSessionExpired = { hookCalls++ },
            installer = installer,
        )
        // First death: the host has no token yet.
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead { throw unauthorized() } }
        }
        assertThat(coordinator.currentState()).isEqualTo(PortalSessionState.Expired)

        // The host re-authenticated; the next call installs the new token first.
        mint = "fresh"
        var blockRuns = 0
        val result = coordinator.executeRead {
            blockRuns++
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(blockRuns).isEqualTo(1)
        assertThat(installer.tokens).containsExactly("fresh")
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `the expiry hook re-arms after a successful refresh`() {
        val tokens = ArrayDeque(listOf("second", "third"))
        var hookCalls = 0
        val coordinator = coordinator(
            onSessionTokenNeeded = { tokens.removeFirstOrNull() },
            onSessionExpired = { hookCalls++ },
        )

        // Death 1: refresh to "second" succeeds but the retry is rejected again -> hook fires.
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead { throw unauthorized() } }
        }
        assertThat(hookCalls).isEqualTo(1)

        // Death 2: refresh to "third" succeeds but the retry is rejected again -> hook fires again.
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead { throw unauthorized() } }
        }
        assertThat(hookCalls).isEqualTo(2)
    }

    @Test
    fun `concurrent callers rejected on the same token share one re-mint`() = runTest {
        val mintCalls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val installer = InstallRecorder()
        val coordinator = PortalSessionCoordinator(
            onSessionTokenNeeded = {
                mintCalls.incrementAndGet()
                gate.await()
                "new-token"
            },
            installToken = installer.fn,
            retryDelay = { },
        )
        val attempts = AtomicInteger(0)
        val block: suspend () -> String = {
            if (attempts.incrementAndGet() <= 2) throw unauthorized() else "ok"
        }

        val a = async { coordinator.executeRead(block) }
        val b = async { coordinator.executeRead(block) }
        runCurrent()
        gate.complete(Unit)

        assertThat(a.await()).isEqualTo("ok")
        assertThat(b.await()).isEqualTo("ok")
        assertThat(mintCalls.get()).isEqualTo(1)
        assertThat(installer.tokens).containsExactly("new-token")
    }

    @Test
    fun `a token swapped while a call was in flight makes the call retry instead of expiring`() = runBlocking {
        var hookCalls = 0
        val coordinator = coordinator(onSessionExpired = { hookCalls++ })
        var attempts = 0

        val result = coordinator.executeRead {
            attempts++
            if (attempts == 1) {
                // The host installs a new token out of band while this attempt is in flight,
                // then the old token's rejection arrives.
                coordinator.installNow("out-of-band")
                throw unauthorized()
            }
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(attempts).isEqualTo(2)
        assertThat(hookCalls).isEqualTo(0)
        assertThat(coordinator.currentState()).isEqualTo(PortalSessionState.Active)
    }

    @Test
    fun `queued callers share a failed mint instead of re-minting once each`() = runTest {
        val mintCalls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        var hookCalls = 0
        val coordinator = PortalSessionCoordinator(
            onSessionTokenNeeded = {
                mintCalls.incrementAndGet()
                gate.await()
                null
            },
            onSessionExpired = { hookCalls++ },
            retryDelay = { },
        )

        val a = async { runCatching { coordinator.executeRead { throw unauthorized() } } }
        val b = async { runCatching { coordinator.executeRead { throw unauthorized() } } }
        val c = async { runCatching { coordinator.executeRead { throw unauthorized() } } }
        runCurrent()
        gate.complete(Unit)

        assertThat(a.await().exceptionOrNull()).isInstanceOf(RainError.TokenExpired::class.java)
        assertThat(b.await().exceptionOrNull()).isInstanceOf(RainError.TokenExpired::class.java)
        assertThat(c.await().exceptionOrNull()).isInstanceOf(RainError.TokenExpired::class.java)
        assertThat(mintCalls.get()).isEqualTo(1)
        assertThat(hookCalls).isEqualTo(1)
        assertThat(coordinator.currentState()).isEqualTo(PortalSessionState.Expired)
    }

    @Test
    fun `cancellation during the mint does not leave the state on Refreshing`() = runTest {
        val gate = CompletableDeferred<String?>()
        val coordinator = PortalSessionCoordinator(
            onSessionTokenNeeded = { gate.await() },
            retryDelay = { },
        )
        coordinator.executeRead { "warm" }
        assertThat(coordinator.currentState()).isEqualTo(PortalSessionState.Active)

        val call = async { coordinator.executeRead { throw unauthorized() } }
        runCurrent()
        assertThat(coordinator.currentState()).isEqualTo(PortalSessionState.Refreshing)
        call.cancel()
        runCurrent()

        assertThat(coordinator.currentState()).isEqualTo(PortalSessionState.Active)
    }

    @Test
    fun `refreshNow re-mints and re-arms without a failing call`() = runBlocking {
        val installer = InstallRecorder()
        val coordinator = coordinator(onSessionTokenNeeded = { "manual" }, installer = installer)

        coordinator.refreshNow()

        assertThat(installer.tokens).containsExactly("manual")
        assertThat(coordinator.currentState()).isEqualTo(PortalSessionState.Active)
    }

    @Test
    fun `refreshNow without a re-mint hook throws TokenExpired`() {
        var hookCalls = 0
        val coordinator = coordinator(onSessionExpired = { hookCalls++ })

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.refreshNow() }
        }
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `installNow swaps a host-minted token and revives an Expired session`() = runBlocking {
        val installer = InstallRecorder()
        val coordinator = coordinator(installer = installer)
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead { throw unauthorized() } }
        }

        coordinator.installNow("host-minted")
        val result = coordinator.executeRead { "ok" }

        assertThat(installer.tokens).containsExactly("host-minted")
        assertThat(result).isEqualTo("ok")
        assertThat(coordinator.currentState()).isEqualTo(PortalSessionState.Active)
    }

    @Test
    fun `installNow rejects a blank token`() {
        val coordinator = coordinator()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { coordinator.installNow("  ") }
        }
    }

    // ---------- the boundary: nothing leaves as a vendor type ----------

    @Test
    fun `a failure the coordinator does not refresh or retry leaves as ProviderError with its cause`() {
        val coordinator = coordinator()
        val raw = IllegalStateException("bad request shape")

        val thrown = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { coordinator.executeRead<String> { throw raw } }
        }
        assertThat(thrown.cause).isSameInstanceAs(raw)
    }

    @Test
    fun `a RainError raised inside the block passes through the boundary untouched`() {
        val coordinator = coordinator()
        val raised = RainError.InvalidConfig("no RPC endpoint")

        val thrown = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { coordinator.executeRead<String> { throw raised } }
        }
        assertThat(thrown).isSameInstanceAs(raised)
    }

    @Test
    fun `a cancellation raised inside the block is rethrown as itself, never mapped`() {
        val coordinator = coordinator()
        val cancel = kotlinx.coroutines.CancellationException("caller went away")

        val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { coordinator.executeRead<String> { throw cancel } }
        }
        assertThat(thrown).isSameInstanceAs(cancel)
    }

    @Test
    fun `a bare TokenExpired raised inside the block is an auth failure, not a mapped failure`() {
        var hookCalls = 0
        val coordinator = coordinator(onSessionExpired = { hookCalls++ })

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead<String> { throw RainError.TokenExpired() } }
        }
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `an unmapped failure is logged once at warning level and a RainError not at all`() {
        val coordinator = coordinator()
        val raw = IllegalStateException("bad request shape")
        val entries = mutableListOf<Pair<Int, Throwable?>>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                entries += priority to t
            }
        }
        timber.log.Timber.plant(tree)
        try {
            runCatching { runBlocking { coordinator.executeRead<String> { throw raw } } }
            val vendorEntries = entries.toList()
            entries.clear()
            runCatching { runBlocking { coordinator.executeRead<String> { throw RainError.InvalidConfig("ours") } } }
            assertThat(vendorEntries).hasSize(1)
            assertThat(vendorEntries.single().first).isEqualTo(android.util.Log.WARN)
            assertThat(vendorEntries.single().second).isSameInstanceAs(raw)
            assertThat(entries).isEmpty()
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    @Test
    fun `a rejection in a non-Portal failure keeps its code through the boundary`() {
        val coordinator = coordinator()

        assertThrows(RainError.UserRejected::class.java) {
            runBlocking {
                coordinator.executeWrite<String> { throw IllegalStateException("user rejected the request") }
            }
        }
    }

    @Test
    fun `a funds shortfall in a non-Portal failure keeps its code through the boundary`() {
        val coordinator = coordinator()

        assertThrows(RainError.InsufficientFunds::class.java) {
            runBlocking {
                coordinator.executeRead<String> { throw IllegalStateException("insufficient funds for gas") }
            }
        }
    }

    // ---------- transient backoff ----------

    @Test
    fun `reads retry transient HTTP failures with exponential backoff`() = runBlocking {
        val delays = RecordingDelay()
        val coordinator = coordinator(
            policy = PortalSessionPolicy(maxTransientRetries = 3, initialRetryDelayMs = 100, maxRetryDelayMs = 250),
            delayRecorder = delays,
        )
        var attempts = 0

        val result = coordinator.executeRead {
            attempts++
            when (attempts) {
                1 -> throw httpFailed(503)
                2 -> throw RainError.ProviderError(httpFailed(429))
                3 -> throw IOException("reset")
                else -> "ok"
            }
        }

        assertThat(result).isEqualTo("ok")
        assertThat(attempts).isEqualTo(4)
        assertThat(delays.delays).containsExactly(100L, 200L, 250L).inOrder()
    }

    @Test
    fun `the connection-level HttpRequestFailed is transient`() = runBlocking {
        val coordinator = coordinator()
        var attempts = 0
        val result = coordinator.executeRead {
            attempts++
            if (attempts == 1) {
                throw PortalException.Api.HttpRequestFailed(
                    "Request failed: unable to receive a valid HTTP response"
                )
            }
            "ok"
        }
        assertThat(result).isEqualTo("ok")
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `reads give up after maxTransientRetries and surface the last failure as a RainError`() {
        val coordinator = coordinator(policy = PortalSessionPolicy(maxTransientRetries = 1))
        var attempts = 0

        val thrown = assertThrows(RainError.ProviderError::class.java) {
            runBlocking {
                coordinator.executeRead {
                    attempts++
                    throw httpFailed(500)
                }
            }
        }
        assertThat(thrown.cause).isInstanceOf(PortalException.Api.HttpRequestFailed::class.java)
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `writes never retry transient failures`() {
        val delays = RecordingDelay()
        val coordinator = coordinator(delayRecorder = delays)
        var attempts = 0

        val thrown = assertThrows(RainError.ProviderError::class.java) {
            runBlocking {
                coordinator.executeWrite {
                    attempts++
                    throw httpFailed(503)
                }
            }
        }
        assertThat(thrown.cause).isInstanceOf(PortalException.Api.HttpRequestFailed::class.java)
        assertThat(attempts).isEqualTo(1)
        assertThat(delays.delays).isEmpty()
    }

    @Test
    fun `non-transient non-auth failures leave once, as a RainError carrying the vendor cause`() {
        val coordinator = coordinator()
        var attempts = 0

        val thrown = assertThrows(RainError.ProviderError::class.java) {
            runBlocking {
                coordinator.executeRead {
                    attempts++
                    throw httpFailed(400)
                }
            }
        }
        assertThat(thrown.cause).isInstanceOf(PortalException.Api.HttpRequestFailed::class.java)
        assertThat(attempts).isEqualTo(1)
    }

    // ---------- lifecycle ----------

    @Test
    fun `a stopped coordinator never fires the hook or death callbacks`() {
        var hookCalls = 0
        var deathCalls = 0
        val coordinator = coordinator(onSessionExpired = { hookCalls++ })
        coordinator.onSessionDeath { deathCalls++ }
        coordinator.stop()

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead { throw unauthorized() } }
        }
        assertThat(hookCalls).isEqualTo(0)
        assertThat(deathCalls).isEqualTo(0)
    }

    @Test
    fun `death callbacks run before the host hook and survive a throwing callback`() {
        val order = mutableListOf<String>()
        val coordinator = coordinator(onSessionExpired = { order += "hook" })
        coordinator.onSessionDeath {
            order += "evict"
            throw IllegalStateException("boom")
        }

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead { throw unauthorized() } }
        }
        assertThat(order).containsExactly("evict", "hook").inOrder()
    }

    @Test
    fun `policy rejects invalid values`() {
        assertThrows(IllegalArgumentException::class.java) { PortalSessionPolicy(maxTransientRetries = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            PortalSessionPolicy(initialRetryDelayMs = 10, maxRetryDelayMs = 5)
        }
    }
}
