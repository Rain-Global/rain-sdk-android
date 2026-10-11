package com.rain.sdk.privy

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.privy.auth.AuthState
import io.privy.auth.AuthenticationException
import io.privy.auth.PrivyUser
import io.privy.network.ApiResult
import io.privy.network.NoNetworkException
import io.privy.network.PrivyApiException
import io.privy.network.toResult
import io.privy.sdk.Privy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class PrivySessionCoordinatorTest {

    private class RecordingDelay {
        val delays = mutableListOf<Long>()
        val fn: suspend (Long) -> Unit = { delays += it }
    }

    private fun authenticatedPrivy(
        auth: MutableStateFlow<AuthState>,
        user: PrivyUser = mockk(),
    ): Privy {
        val privy = mockk<Privy>()
        every { privy.authState } returns auth
        coEvery { privy.getAuthState() } answers { auth.value }
        coEvery { privy.getUser() } returns user
        return privy
    }

    private fun coordinator(
        privy: Privy,
        policy: PrivySessionPolicy = PrivySessionPolicy(),
        onSessionExpired: (() -> Unit)? = null,
        delayRecorder: RecordingDelay = RecordingDelay(),
    ) = PrivySessionCoordinator(
        privy = privy,
        policy = policy,
        onSessionExpired = onSessionExpired,
        retryDelay = delayRecorder.fn,
    )

    private fun apiException(status: Int?) =
        PrivyApiException(status, null, "api failure", RuntimeException("api failure"))

    /** The vendor's confirmed-offline answer to an API call, built the way its `toResult()` builds it. */
    private fun offlineRefusal(): Throwable =
        checkNotNull(ApiResult.Error.NoNetworkError.toResult().exceptionOrNull())

    /** A transport failure the vendor wrapped: `ConnectException` to auth.privy.io (QA, 2026-10-02). */
    private fun wrappedTransportFailure() = PrivyApiException(
        null,
        null,
        "Something went wrong",
        ConnectException("No route to host"),
    )

    /** The answer to a request that left the device never came back: an unknown fate on a send. */
    private fun lostAnswer() = PrivyApiException(null, null, "Something went wrong", SocketTimeoutException("timeout"))

    // ---------- auth-state guard ----------

    @Test
    fun `unauthenticated state throws TokenExpired without running the call`() {
        val auth = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
        val privy = mockk<Privy>()
        every { privy.authState } returns auth
        coEvery { privy.getAuthState() } answers { auth.value }
        var blockRuns = 0
        val coordinator = coordinator(privy)

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead { blockRuns++ } }
        }
        assertThat(blockRuns).isEqualTo(0)
    }

    @Test
    fun `never-logged-in user gets TokenExpired but the expiry hook stays silent`() {
        val auth = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
        val privy = mockk<Privy>()
        every { privy.authState } returns auth
        coEvery { privy.getAuthState() } answers { auth.value }
        var hookCalls = 0
        val coordinator = coordinator(privy, onSessionExpired = { hookCalls++ })

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead { } }
        }
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `call during credential restore waits for NotReady to resolve instead of expiring`() = runTest {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.NotReady)
        val privy = authenticatedPrivy(auth, user)
        var hookCalls = 0
        val coordinator = PrivySessionCoordinator(
            privy = privy,
            onSessionExpired = { hookCalls++ },
            retryDelay = { },
        )

        val call = async { coordinator.executeRead { "ok" } }
        runCurrent()
        assertThat(call.isCompleted).isFalse()

        auth.value = AuthState.Authenticated(user)
        runCurrent()

        assertThat(call.await()).isEqualTo("ok")
        assertThat(hookCalls).isEqualTo(0)
    }

    // ---------- auth-failure surfacing ----------

    @Test
    fun `Privy auth failure surfaces TokenExpired and fires the hook once`() {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var hookCalls = 0
        val coordinator = coordinator(privy, onSessionExpired = { hookCalls++ })

        repeat(2) {
            assertThrows(RainError.TokenExpired::class.java) {
                runBlocking {
                    coordinator.executeRead {
                        throw AuthenticationException("User must be authenticated before calling refresh.")
                    }
                }
            }
        }
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `HTTP 401 from the Privy indexer surfaces TokenExpired without retries`() {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var attempts = 0
        val coordinator = coordinator(privy)

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                coordinator.executeRead {
                    attempts++
                    throw apiException(401)
                }
            }
        }
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `auth failure on a write is never retried`() {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var attempts = 0
        val coordinator = coordinator(privy)

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                coordinator.executeWrite {
                    attempts++
                    throw AuthenticationException("not logged in")
                }
            }
        }
        assertThat(attempts).isEqualTo(1)
    }

    // ---------- transient backoff ----------

    @Test
    fun `transient 500 on a read retries with exponential backoff and then succeeds`() = runBlocking {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        val delays = RecordingDelay()
        val coordinator = coordinator(privy, delayRecorder = delays)
        var failures = 2

        val result = coordinator.executeRead {
            if (failures > 0) {
                failures--
                throw apiException(500)
            }
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(delays.delays).containsExactly(500L, 1000L).inOrder()
    }

    @Test
    fun `transient failures beyond maxTransientRetries surface as NetworkError carrying the cause`() {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        val delays = RecordingDelay()
        val coordinator = coordinator(
            privy,
            policy = PrivySessionPolicy(maxTransientRetries = 2),
            delayRecorder = delays,
        )
        var attempts = 0

        val thrown = assertThrows(RainError.NetworkError::class.java) {
            runBlocking {
                coordinator.executeRead<String> {
                    attempts++
                    throw IOException("connection reset")
                }
            }
        }
        assertThat(thrown).hasCauseThat().isInstanceOf(IOException::class.java)
        assertThat(thrown).hasCauseThat().hasMessageThat().contains("connection reset")
        assertThat(attempts).isEqualTo(3)
        assertThat(delays.delays).hasSize(2)
    }

    @Test
    fun `backoff delays are capped at maxRetryDelayMs`() {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        val delays = RecordingDelay()
        val coordinator = coordinator(
            privy,
            policy = PrivySessionPolicy(
                maxTransientRetries = 4,
                initialRetryDelayMs = 500,
                maxRetryDelayMs = 1_000,
            ),
            delayRecorder = delays,
        )

        val thrown = assertThrows(RainError.NetworkError::class.java) {
            runBlocking { coordinator.executeRead<String> { throw NoNetworkException } }
        }
        assertThat(thrown.cause).isSameInstanceAs(NoNetworkException)
        assertThat(delays.delays).containsExactly(500L, 1000L, 1000L, 1000L).inOrder()
    }

    @Test
    fun `transient failure on a write is not retried`() {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var attempts = 0
        val coordinator = coordinator(privy)

        val thrown = assertThrows(RainError.NetworkError::class.java) {
            runBlocking {
                coordinator.executeWrite<String> {
                    attempts++
                    throw NoNetworkException
                }
            }
        }
        assertThat(thrown.cause).isSameInstanceAs(NoNetworkException)
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `a transport failure after a send left the device is ProviderError, never a retry hint`() {
        // Privy signs and broadcasts inside the request, so a lost answer may follow a broadcast:
        // RAIN_301 would invite a host to send twice.
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var attempts = 0
        val coordinator = coordinator(privy)
        val failure = lostAnswer()

        val thrown = assertThrows(RainError.ProviderError::class.java) {
            runBlocking {
                coordinator.executeWrite<String> {
                    attempts++
                    throw failure
                }
            }
        }
        assertThat(thrown.cause).isSameInstanceAs(failure)
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `a failure before a send left the device is NetworkError`() {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        val coordinator = coordinator(privy)

        for (failure in listOf(wrappedTransportFailure(), offlineRefusal())) {
            val thrown = assertThrows(RainError.NetworkError::class.java) {
                runBlocking { coordinator.executeWrite<String> { throw failure } }
            }
            assertThat(thrown.cause).isSameInstanceAs(failure)
        }
    }

    @Test
    fun `a lost answer on a read is retried and leaves as NetworkError`() {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        val delays = RecordingDelay()
        val coordinator = coordinator(privy, policy = PrivySessionPolicy(maxTransientRetries = 2), delayRecorder = delays)
        var attempts = 0

        assertThrows(RainError.NetworkError::class.java) {
            runBlocking {
                coordinator.executeRead<String> {
                    attempts++
                    throw lostAnswer()
                }
            }
        }
        assertThat(attempts).isEqualTo(3)
        assertThat(delays.delays).hasSize(2)
    }

    @Test
    fun `a call whose failure left the vendor without a session is a death, on a send too`() {
        // The vendor logs out on a 4xx to the token refresh it runs inside a wallet call; that
        // failure arrives as neither an auth failure nor a transient one.
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var hookCalls = 0
        val coordinator = coordinator(privy, onSessionExpired = { hookCalls++ })

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                coordinator.executeWrite<String> {
                    auth.value = AuthState.Unauthenticated
                    throw apiException(400)
                }
            }
        }
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `a read on an unverified session asks the vendor to verify once per call and is not retried`() {
        // The attempt is a vendor request that can wait out the vendor's 60 s HTTP timeout, so a
        // read the vendor still cannot verify leaves at once instead of asking twice more with
        // backoff; the host's next call asks again.
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(mockk<AuthState.AuthenticatedUnverified>())
        val privy = mockk<Privy>()
        every { privy.authState } returns auth
        coEvery { privy.getAuthState() } answers { auth.value }
        var verifications = 0
        coEvery { privy.onNetworkRestored() } coAnswers {
            verifications++
            if (verifications == 2) auth.value = AuthState.Authenticated(user)
        }
        val delays = RecordingDelay()
        var attempts = 0
        val coordinator = coordinator(privy, delayRecorder = delays)

        assertThrows(RainError.NetworkError::class.java) {
            runBlocking {
                coordinator.executeRead {
                    attempts++
                    coordinator.requireUser()
                }
            }
        }
        assertThat(attempts).isEqualTo(1)
        assertThat(verifications).isEqualTo(1)
        assertThat(delays.delays).isEmpty()

        val resolved = runBlocking { coordinator.executeRead { coordinator.requireUser() } }

        assertThat(resolved).isSameInstanceAs(user)
        assertThat(verifications).isEqualTo(2)
        assertThat(delays.delays).isEmpty()
    }

    @Test
    fun `a cancellation the vendor wrapped leaves a guarded call as itself`() {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        var hookCalls = 0
        val coordinator = coordinator(authenticatedPrivy(auth, user), onSessionExpired = { hookCalls++ })
        val cancel = kotlinx.coroutines.CancellationException("caller went away")
        val wrapped = PrivyApiException(null, null, "Something went wrong", cancel)

        val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { coordinator.executeWrite<String> { throw wrapped } }
        }
        assertThat(thrown).isSameInstanceAs(cancel)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `the vendor's confirmed-offline answer on a read is retried like a bare network failure`() = runBlocking {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        val delays = RecordingDelay()
        val coordinator = coordinator(privy, delayRecorder = delays)
        var failures = 1

        val result = coordinator.executeRead {
            if (failures > 0) {
                failures--
                throw offlineRefusal()
            }
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(delays.delays).containsExactly(500L).inOrder()
    }

    @Test
    fun `non-transient non-auth errors are not retried on reads`() {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var attempts = 0
        val coordinator = coordinator(privy)

        // Not an auth failure and not transient, so it leaves once, as a RainError rather than raw.
        val thrown = assertThrows(RainError.ProviderError::class.java) {
            runBlocking {
                coordinator.executeRead<String> {
                    attempts++
                    throw IllegalStateException("bad request shape")
                }
            }
        }
        assertThat(thrown).hasCauseThat().isInstanceOf(IllegalStateException::class.java)
        assertThat(attempts).isEqualTo(1)
    }

    // ---------- the boundary: nothing leaves as a vendor type ----------

    @Test
    fun `a RainError raised inside the block passes through the boundary untouched`() {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val coordinator = coordinator(authenticatedPrivy(auth, user))
        val raised = RainError.InvalidConfig("no RPC endpoint")

        val thrown = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { coordinator.executeRead<String> { throw raised } }
        }
        assertThat(thrown).isSameInstanceAs(raised)
    }

    @Test
    fun `a cancellation raised inside the block is rethrown as itself, never mapped`() {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val coordinator = coordinator(authenticatedPrivy(auth, user))
        val cancel = kotlinx.coroutines.CancellationException("caller went away")

        val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { coordinator.executeRead<String> { throw cancel } }
        }
        assertThat(thrown).isSameInstanceAs(cancel)
    }

    @Test
    fun `a bare TokenExpired raised inside the block is an auth failure, not a mapped failure`() {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        var hookCalls = 0
        val coordinator = coordinator(authenticatedPrivy(auth, user), onSessionExpired = { hookCalls++ })

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.executeRead<String> { throw RainError.TokenExpired() } }
        }
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `an unmapped failure is logged once at warning level and a RainError not at all`() {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val coordinator = coordinator(authenticatedPrivy(auth, user))
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

    // ---------- session state ----------

    @Test
    fun `currentState reflects the Privy auth state`() {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.NotReady)
        val privy = mockk<Privy>()
        every { privy.authState } returns auth
        coEvery { privy.getAuthState() } answers { auth.value }
        val coordinator = coordinator(privy)

        assertThat(coordinator.currentState()).isEqualTo(PrivySessionState.Loading)

        auth.value = AuthState.Authenticated(user)
        assertThat(coordinator.currentState()).isEqualTo(PrivySessionState.Active)

        auth.value = mockk<AuthState.AuthenticatedUnverified>()
        assertThat(coordinator.currentState()).isEqualTo(PrivySessionState.Unverified)

        auth.value = AuthState.Unauthenticated
        assertThat(coordinator.currentState()).isEqualTo(PrivySessionState.Unauthenticated)
    }

    // ---------- passive watcher ----------

    @Test
    fun `watcher fires the hook and death callbacks once when an active session dies`() = runTest {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var hookCalls = 0
        var evictions = 0
        val coordinator = PrivySessionCoordinator(
            privy = privy,
            onSessionExpired = { hookCalls++ },
            retryDelay = { },
        )
        coordinator.onSessionDeath { evictions++ }
        val monitorScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        coordinator.startMonitoring(monitorScope)
        runCurrent()

        auth.value = AuthState.Unauthenticated
        runCurrent()

        assertThat(hookCalls).isEqualTo(1)
        assertThat(evictions).isEqualTo(1)

        // A repeated emission must not re-fire for the same death.
        auth.value = AuthState.NotReady
        runCurrent()
        auth.value = AuthState.Unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(1)
        monitorScope.cancel()
    }

    @Test
    fun `watcher fires when an active session dies through a NotReady transition`() = runTest {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var hookCalls = 0
        val coordinator = PrivySessionCoordinator(
            privy = privy,
            onSessionExpired = { hookCalls++ },
            retryDelay = { },
        )
        val monitorScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        coordinator.startMonitoring(monitorScope)
        runCurrent()

        auth.value = AuthState.NotReady
        runCurrent()
        auth.value = AuthState.Unauthenticated
        runCurrent()

        assertThat(hookCalls).isEqualTo(1)
        monitorScope.cancel()
    }

    @Test
    fun `hook re-arms after a re-login and fires again for a second death`() = runTest {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var hookCalls = 0
        val coordinator = PrivySessionCoordinator(
            privy = privy,
            onSessionExpired = { hookCalls++ },
            retryDelay = { },
        )
        val monitorScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        coordinator.startMonitoring(monitorScope)
        runCurrent()

        auth.value = AuthState.Unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(1)

        // Re-login re-arms; a second death must fire again.
        auth.value = AuthState.Authenticated(user)
        runCurrent()
        auth.value = AuthState.Unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(2)
        monitorScope.cancel()
    }

    @Test
    fun `watcher stays silent for a user who never logged in`() = runTest {
        val auth = MutableStateFlow<AuthState>(AuthState.NotReady)
        val privy = mockk<Privy>()
        every { privy.authState } returns auth
        coEvery { privy.getAuthState() } answers { auth.value }
        var hookCalls = 0
        val coordinator = PrivySessionCoordinator(
            privy = privy,
            onSessionExpired = { hookCalls++ },
            retryDelay = { },
        )
        val monitorScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        coordinator.startMonitoring(monitorScope)
        runCurrent()

        auth.value = AuthState.Unauthenticated
        runCurrent()

        assertThat(hookCalls).isEqualTo(0)
        monitorScope.cancel()
    }
}
