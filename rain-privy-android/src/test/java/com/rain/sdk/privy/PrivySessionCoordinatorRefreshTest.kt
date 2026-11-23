package com.rain.sdk.privy

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.privy.auth.AuthState
import io.privy.auth.AuthenticationException
import io.privy.auth.PrivyUser
import io.privy.network.ApiResult
import io.privy.network.NoNetworkException
import io.privy.network.PrivyApiException
import io.privy.network.toResult
import io.privy.sdk.Privy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.ConnectException

/**
 * The manual refresh, `refreshNow`, split out of [PrivySessionCoordinatorTest] for size: which
 * refresh failures are a session death (hook fired, `RAIN_201`) and which leave the session and
 * the hook alone (`RAIN_301` for no answer, `RAIN_501` otherwise), plus the vendor-wrapped
 * cancellation and the session restored offline.
 */
class PrivySessionCoordinatorRefreshTest {

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

    /** A 5xx as the vendor reports it: status-less, with ktor's ServerResponseException-shaped cause. */
    private fun vendorServerError() = PrivyApiException(
        null,
        null,
        "Something went wrong",
        IllegalStateException("Server error(https://auth.privy.io/api/v1/sessions: 503 Service Unavailable"),
    )

    // ---------- manual refresh ----------

    @Test
    fun `refreshNow refreshes through the Privy user`() = runBlocking {
        val user = mockk<PrivyUser>()
        coEvery { user.refresh() } returns Result.success(Unit)
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        val coordinator = coordinator(privy)

        coordinator.refreshNow()
    }

    @Test
    fun `refreshNow surfaces TokenExpired and fires the hook once when Privy refuses the refresh`() {
        for (refusal in listOf<Throwable>(
            AuthenticationException("User must be authenticated before calling refresh user."),
            apiException(401),
        )) {
            val user = mockk<PrivyUser>()
            coEvery { user.refresh() } returns Result.failure(refusal)
            val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
            val privy = authenticatedPrivy(auth, user)
            var hookCalls = 0
            val coordinator = coordinator(privy, onSessionExpired = { hookCalls++ })

            repeat(2) {
                assertThrows(RainError.TokenExpired::class.java) {
                    runBlocking { coordinator.refreshNow() }
                }
            }
            assertThat(hookCalls).isEqualTo(1)
        }
    }

    @Test
    fun `refreshNow offline surfaces NetworkError, keeps the hook silent, and the next refresh online works`() {
        // Beta QA PV-SES-01, 2026-10-02: airplane mode on, "Refresh session" gave RAIN_201 and fired
        // the hook; airplane mode off, the next refresh succeeded. The session never died.
        val user = mockk<PrivyUser>()
        val offline = offlineRefusal()
        coEvery { user.refresh() } returns Result.failure(offline) andThen Result.success(Unit)
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var hookCalls = 0
        var deaths = 0
        val coordinator = coordinator(privy, onSessionExpired = { hookCalls++ })
        coordinator.onSessionDeath { deaths++ }

        val thrown = assertThrows(RainError.NetworkError::class.java) {
            runBlocking { coordinator.refreshNow() }
        }

        assertThat(thrown.cause).isSameInstanceAs(offline)
        assertThat(hookCalls).isEqualTo(0)
        assertThat(deaths).isEqualTo(0)

        runBlocking { coordinator.refreshNow() }
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `refreshNow surfaces TokenExpired and fires the hook once when Privy dropped the session while answering`() {
        // privy-core 0.15.0 logs the user out on any 4xx to its token refresh, and on a 200 whose
        // action is "clear"; neither arrives as a 401 or as the auth prose the mapping knows.
        for (failure in listOf<Throwable>(
            apiException(400),
            AuthenticationException("Authenticate was successful, but Privy backend requested this user be logged out."),
        )) {
            val user = mockk<PrivyUser>()
            val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
            // The vendor flips its own state before the failure comes back.
            coEvery { user.refresh() } coAnswers {
                auth.value = AuthState.Unauthenticated
                Result.failure(failure)
            }
            val privy = authenticatedPrivy(auth, user)
            var hookCalls = 0
            val coordinator = coordinator(privy, onSessionExpired = { hookCalls++ })

            assertThrows(RainError.TokenExpired::class.java) { runBlocking { coordinator.refreshNow() } }
            assertThat(hookCalls).isEqualTo(1)

            // The session is gone: the next refresh finds no user and the hook stays at one.
            assertThrows(RainError.TokenExpired::class.java) { runBlocking { coordinator.refreshNow() } }
            assertThat(hookCalls).isEqualTo(1)
        }
    }

    @Test
    fun `refreshNow on a session the vendor still cannot verify throws NetworkError with the hook silent`() {
        // AuthenticatedUnverified: a cold start offline, or a restore that failed while online. The
        // coordinator asks the vendor to verify (onNetworkRestored) and reads the state again.
        val auth = MutableStateFlow<AuthState>(mockk<AuthState.AuthenticatedUnverified>())
        val privy = mockk<Privy>()
        every { privy.authState } returns auth
        coEvery { privy.getAuthState() } answers { auth.value }
        coEvery { privy.onNetworkRestored() } just Runs
        var hookCalls = 0
        val coordinator = coordinator(privy, onSessionExpired = { hookCalls++ })

        assertThrows(RainError.NetworkError::class.java) { runBlocking { coordinator.refreshNow() } }
        assertThrows(RainError.NetworkError::class.java) { runBlocking { coordinator.requireUser() } }
        assertThat(hookCalls).isEqualTo(0)
        assertThat(coordinator.currentState()).isEqualTo(PrivySessionState.Unverified)
        coVerify(exactly = 2) { privy.onNetworkRestored() }
    }

    @Test
    fun `an unverified session the vendor verifies on request proceeds as a live one`() {
        val user = mockk<PrivyUser>()
        coEvery { user.refresh() } returns Result.success(Unit)
        val auth = MutableStateFlow<AuthState>(mockk<AuthState.AuthenticatedUnverified>())
        val privy = mockk<Privy>()
        every { privy.authState } returns auth
        coEvery { privy.getAuthState() } answers { auth.value }
        coEvery { privy.onNetworkRestored() } coAnswers { auth.value = AuthState.Authenticated(user) }
        var hookCalls = 0
        val coordinator = coordinator(privy, onSessionExpired = { hookCalls++ })

        runBlocking { coordinator.refreshNow() }
        assertThat(runBlocking { coordinator.requireUser() }).isSameInstanceAs(user)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `an unverified session the vendor rejects on request is a death`() {
        val auth = MutableStateFlow<AuthState>(mockk<AuthState.AuthenticatedUnverified>())
        val privy = mockk<Privy>()
        every { privy.authState } returns auth
        coEvery { privy.getAuthState() } answers { auth.value }
        coEvery { privy.onNetworkRestored() } coAnswers { auth.value = AuthState.Unauthenticated }
        val coordinator = coordinator(privy)

        assertThrows(RainError.TokenExpired::class.java) { runBlocking { coordinator.refreshNow() } }
        assertThrows(RainError.TokenExpired::class.java) { runBlocking { coordinator.requireUser() } }
    }

    @Test
    fun `refreshNow offline once the JWT has expired surfaces NetworkError with the hook silent`() {
        // PrivyUser.refresh runs the vendor's token refresh once the JWT has expired, which refuses
        // a confirmed-offline device with a bare NoNetworkException before any request.
        val user = mockk<PrivyUser>()
        coEvery { user.refresh() } returns Result.failure(NoNetworkException)
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var hookCalls = 0
        val coordinator = coordinator(privy, onSessionExpired = { hookCalls++ })

        val thrown = assertThrows(RainError.NetworkError::class.java) { runBlocking { coordinator.refreshNow() } }
        assertThat(thrown.cause).isSameInstanceAs(NoNetworkException)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `a cancellation the vendor wrapped leaves refreshNow as itself with the hook silent`() {
        val user = mockk<PrivyUser>()
        val cancel = kotlinx.coroutines.CancellationException("caller went away")
        coEvery { user.refresh() } returns
            Result.failure(PrivyApiException(null, null, "Something went wrong", cancel))
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var hookCalls = 0
        val coordinator = coordinator(privy, onSessionExpired = { hookCalls++ })

        val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { coordinator.refreshNow() }
        }
        assertThat(thrown).isSameInstanceAs(cancel)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `refreshNow on a transport failure the vendor wrapped surfaces NetworkError with the hook silent`() {
        val user = mockk<PrivyUser>()
        val failure = wrappedTransportFailure()
        coEvery { user.refresh() } returns Result.failure(failure)
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var hookCalls = 0
        val coordinator = coordinator(privy, onSessionExpired = { hookCalls++ })

        val thrown = assertThrows(RainError.NetworkError::class.java) {
            runBlocking { coordinator.refreshNow() }
        }

        assertThat(thrown.cause).isSameInstanceAs(failure)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `refreshNow on a failure that is neither the session nor the network surfaces ProviderError with the hook silent`() {
        val user = mockk<PrivyUser>()
        val failure = vendorServerError()
        coEvery { user.refresh() } returns Result.failure(failure)
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var hookCalls = 0
        val coordinator = coordinator(privy, onSessionExpired = { hookCalls++ })

        val thrown = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { coordinator.refreshNow() }
        }

        assertThat(thrown.cause).isSameInstanceAs(failure)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `a refresh that failed offline leaves the hook armed for a later real death`() {
        val user = mockk<PrivyUser>()
        coEvery { user.refresh() } returns
            Result.failure(offlineRefusal()) andThen Result.failure(apiException(401))
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val privy = authenticatedPrivy(auth, user)
        var hookCalls = 0
        val coordinator = coordinator(privy, onSessionExpired = { hookCalls++ })

        assertThrows(RainError.NetworkError::class.java) { runBlocking { coordinator.refreshNow() } }
        assertThat(hookCalls).isEqualTo(0)

        assertThrows(RainError.TokenExpired::class.java) { runBlocking { coordinator.refreshNow() } }
        assertThat(hookCalls).isEqualTo(1)
    }
}
