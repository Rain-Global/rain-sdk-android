package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.turnkey.core.models.errors.TurnkeyKotlinError
import com.turnkey.types.TGetActivitiesBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The coordinator's handling of a session Turnkey refuses to refresh: a login on another device
 * revokes this device's session server-side (`invalidateExisting`), the stored copy keeps its local
 * expiry, and the stamp-login request the refresh makes comes back 401. The refusal clears the
 * stored session so [TurnkeySessionCoordinator.currentState] and the watcher read it as dead at
 * once; a refresh that failed for any other reason leaves the session stored. Split from
 * [TurnkeySessionCoordinatorTest] for size.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnkeySessionCoordinatorRevokedSessionTest {

    @Before
    fun requireJdk24() = assumeJdk24()

    private fun coordinator(turnkey: MockTurnkey, onSessionExpired: (() -> Unit)? = null) =
        TurnkeySessionCoordinator(turnkey = turnkey, onSessionExpired = onSessionExpired, retryDelay = { })

    private fun activitiesBody(organizationId: String) = TGetActivitiesBody(organizationId = organizationId)

    @Test
    fun `a refresh Turnkey refused clears the revoked session and the state reads flip at once`() = runTest {
        val turnkey = MockTurnkey(session = MockTurnkey.nearExpirySession())
        turnkey.refreshSessionError = revokedSessionRefreshFailure()
        var hookCalls = 0
        val coordinator = coordinator(turnkey, onSessionExpired = { hookCalls++ })
        coordinator.startMonitoring(backgroundScope)
        runCurrent()

        expectThrows<RainError.TokenExpired> {
            coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
        }
        // Read before the scheduler runs the watcher: the vendor flips its state inline inside
        // clearSession and currentState derives from it on demand, so nothing may lag behind the throw.
        assertThat(turnkey.clearSessionCalls).containsExactly(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(turnkey.session).isNull()
        assertThat(turnkey.selectedSessionKey).isNull()
        assertThat(coordinator.currentState()).isEqualTo(TurnkeySessionState.Unauthenticated)
        assertThat(coordinator.deathEpoch).isEqualTo(1)
        assertThat(hookCalls).isEqualTo(1)
        runCurrent()

        // The watcher saw the same Active → Unauthenticated transition: still one death, one firing.
        assertThat(coordinator.deathEpoch).isEqualTo(1)
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `a proactive refresh that got no answer is tried once, then proceeds on a live session and ends an expired one`() = runTest {
        val rows = listOf(
            Triple("live", MockTurnkey.nearExpirySession(), false),
            Triple("expired", MockTurnkey.expiredSession(), true),
        )
        rows.forEach { (name, session, dies) ->
            val turnkey = MockTurnkey(session = session)
            turnkey.refreshSessionError = TurnkeyKotlinError.FailedToRefreshSession(IOException("offline"))
            var hookCalls = 0
            var attempts = 0
            val coordinator = coordinator(turnkey, onSessionExpired = { hookCalls++ })

            val error = try {
                coordinator.executeRead { _, _ -> if (attempts++ < 2) throw IOException("offline") }
                null
            } catch (e: RainError) {
                e
            }

            assertWithMessage(name).that(error is RainError.TokenExpired).isEqualTo(dies)
            assertWithMessage(name).that(hookCalls).isEqualTo(if (dies) 1 else 0)
            assertWithMessage(name).that(turnkey.refreshSessionCallCount).isEqualTo(1)
            assertWithMessage(name).that(turnkey.clearSessionCalls).isEmpty()
            assertWithMessage(name).that(turnkey.session).isNotNull()
            assertWithMessage(name).that(attempts).isEqualTo(if (dies) 0 else 3)
        }
    }

    @Test
    fun `refreshNow that got no answer throws a network or provider error and leaves the session and the hook alone`() = runTest {
        val rows = listOf(
            Triple("offline", IOException("offline"), RainError.NetworkError::class.java),
            Triple("503", RuntimeException("HTTP error from /public/v1/submit/stamp_login: 503"), RainError.ProviderError::class.java),
        )
        rows.forEach { (name, cause, expected) ->
            val turnkey = MockTurnkey()
            turnkey.refreshSessionError = TurnkeyKotlinError.FailedToRefreshSession(cause)
            var hookCalls = 0
            val coordinator = coordinator(turnkey, onSessionExpired = { hookCalls++ })

            val error = try {
                coordinator.refreshNow()
                null
            } catch (e: RainError) {
                e
            }

            assertWithMessage(name).that(error).isInstanceOf(expected)
            assertWithMessage(name).that(turnkey.clearSessionCalls).isEmpty()
            assertWithMessage(name).that(coordinator.currentState()).isInstanceOf(TurnkeySessionState.Active::class.java)
            assertWithMessage(name).that(hookCalls).isEqualTo(0)
        }
    }

    @Test
    fun `a refused refresh for a session a concurrent login replaced proceeds with the new session`() = runTest {
        val turnkey = MockTurnkey(session = MockTurnkey.nearExpirySession())
        turnkey.refreshSessionError = revokedSessionRefreshFailure()
        val gate = CompletableDeferred<Unit>()
        turnkey.refreshSessionGate = gate
        var hookCalls = 0
        val coordinator = coordinator(turnkey, onSessionExpired = { hookCalls++ })

        val call = async { coordinator.executeRead { s, _ -> s.publicKey } }
        runCurrent()
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)

        // While the refresh is on the network, a login selects a new session.
        val replacement = MockTurnkey.defaultSession().copy(publicKey = "other-pubkey")
        turnkey.session = replacement
        turnkey.selectedSessionKey = "fresh-key"
        gate.complete(Unit)

        // The refusal was the old session's; the call runs against the live one and no death is reported.
        assertThat(call.await()).isEqualTo("other-pubkey")
        assertThat(turnkey.clearSessionCalls).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(turnkey.session).isSameInstanceAs(replacement)
        assertThat(turnkey.selectedSessionKey).isEqualTo("fresh-key")
        assertThat(coordinator.currentState()).isInstanceOf(TurnkeySessionState.Active::class.java)
        assertThat(hookCalls).isEqualTo(0)
        assertThat(coordinator.deathEpoch).isEqualTo(0)
    }

    @Test
    fun `a refused refresh skips the clear when a login has selected a new key but not yet its session`() = runTest {
        val turnkey = MockTurnkey(session = MockTurnkey.nearExpirySession())
        turnkey.refreshSessionError = revokedSessionRefreshFailure()
        val gate = CompletableDeferred<Unit>()
        turnkey.refreshSessionGate = gate
        val coordinator = coordinator(turnkey)

        val call = async {
            expectThrows<RainError.TokenExpired> {
                coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
            }
        }
        runCurrent()

        // The vendor selects a session in two steps, key first: a login is halfway through.
        turnkey.selectedSessionKey = "fresh-key"
        gate.complete(Unit)
        call.await()

        // Not this coordinator's session to clear any more; the login clears the old key itself.
        assertThat(turnkey.clearSessionCalls).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(turnkey.selectedSessionKey).isEqualTo("fresh-key")
        assertThat(turnkey.session).isNotNull()
    }

    @Test
    fun `a clear that fails still surfaces TokenExpired once and leaves the session stored`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.refreshSessionError = revokedSessionRefreshFailure()
        turnkey.clearSessionError = TurnkeyKotlinError.FailedToClearSession(RuntimeException("storage"))
        var hookCalls = 0
        val coordinator = coordinator(turnkey, onSessionExpired = { hookCalls++ })

        expectThrows<RainError.TokenExpired> { coordinator.refreshNow() }

        // Best effort: the clear was attempted for the selected key, the refusal is what surfaces,
        // and the stored session stays for the next call to try again.
        assertThat(turnkey.clearSessionCalls).containsExactly(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(turnkey.session).isNotNull()
        assertThat(coordinator.currentState()).isInstanceOf(TurnkeySessionState.Active::class.java)
        assertThat(hookCalls).isEqualTo(1)
        assertThat(coordinator.deathEpoch).isEqualTo(1)
    }

    @Test
    fun `concurrent 401s after a revocation refresh once, clear once and both fail as TokenExpired`() = runTest {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.getActivitiesError = RuntimeException("HTTP error from /public/v1/query/get_activities: 401")
        turnkey.refreshSessionError = revokedSessionRefreshFailure()
        val gate = CompletableDeferred<Unit>()
        turnkey.refreshSessionGate = gate
        var hookCalls = 0
        val coordinator = coordinator(turnkey, onSessionExpired = { hookCalls++ })

        val first = async {
            expectThrows<RainError.TokenExpired> {
                coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
            }
        }
        val second = async {
            expectThrows<RainError.TokenExpired> {
                coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
            }
        }
        runCurrent()
        // The first holds the lock while its refresh is on the network; the second waits on the lock.
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
        gate.complete(Unit)
        first.await()
        second.await()

        // The waiter found no session and reported the same death without a second refresh.
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
        assertThat(turnkey.clearSessionCalls).containsExactly(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(hookCalls).isEqualTo(1)
        assertThat(coordinator.deathEpoch).isEqualTo(1)
    }

    @Test
    fun `a 401 mid-call whose refresh Turnkey also refused clears the session`() = runTest {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.getActivitiesError = RuntimeException("HTTP error from /public/v1/query/get_activities: 401")
        turnkey.refreshSessionError = revokedSessionRefreshFailure()
        var hookCalls = 0
        val coordinator = coordinator(turnkey, onSessionExpired = { hookCalls++ })

        expectThrows<RainError.TokenExpired> {
            coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
        }

        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
        assertThat(turnkey.clearSessionCalls).containsExactly(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(turnkey.session).isNull()
        assertThat(coordinator.currentState()).isEqualTo(TurnkeySessionState.Unauthenticated)
        assertThat(hookCalls).isEqualTo(1)

        // The next call finds no session and fails the same way, without another refresh.
        expectThrows<RainError.TokenExpired> {
            coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
        }
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `refreshNow clears a session Turnkey refused to refresh`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.refreshSessionError = revokedSessionRefreshFailure()
        var hookCalls = 0
        val coordinator = coordinator(turnkey, onSessionExpired = { hookCalls++ })

        expectThrows<RainError.TokenExpired> { coordinator.refreshNow() }

        assertThat(turnkey.clearSessionCalls).containsExactly(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(turnkey.session).isNull()
        assertThat(coordinator.currentState()).isEqualTo(TurnkeySessionState.Unauthenticated)
        assertThat(coordinator.deathEpoch).isEqualTo(1)
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `refreshNow after the session was cleared reports the death without a vendor call`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.refreshSessionError = revokedSessionRefreshFailure()
        val coordinator = coordinator(turnkey)
        expectThrows<RainError.TokenExpired> { coordinator.refreshNow() }
        assertThat(turnkey.session).isNull()

        expectThrows<RainError.TokenExpired> { coordinator.refreshNow() }

        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
    }
}
