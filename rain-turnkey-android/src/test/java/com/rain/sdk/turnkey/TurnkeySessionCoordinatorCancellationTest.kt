package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.turnkey.core.models.errors.TurnkeyKotlinError
import com.turnkey.types.TGetActivitiesBody
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * The coordinator's handling of a cancellation the vendor wrapped in its own failure type: the
 * vendor's session calls catch `Throwable`, so a caller going away mid-call arrives as
 * `FailedToRefreshSession` or `FailedToSignRawPayload` and must still leave as the cancellation,
 * never as a session death or a mapped Rain error. Split from [TurnkeySessionCoordinatorTest] for size.
 */
class TurnkeySessionCoordinatorCancellationTest {

    @Before
    fun requireJdk24() = assumeJdk24()

    private fun coordinator(turnkey: MockTurnkey, onSessionExpired: (() -> Unit)? = null) =
        TurnkeySessionCoordinator(turnkey = turnkey, onSessionExpired = onSessionExpired, retryDelay = { })

    private fun activitiesBody(organizationId: String) = TGetActivitiesBody(organizationId = organizationId)

    @Test
    fun `a cancellation the vendor wrapped in a refresh failure is rethrown, not a session death`() {
        val turnkey = MockTurnkey()
        val cancel = kotlinx.coroutines.CancellationException("caller went away")
        turnkey.refreshSessionError = TurnkeyKotlinError.FailedToRefreshSession(cancel)
        var hookCalls = 0
        val coordinator = coordinator(turnkey, onSessionExpired = { hookCalls++ })
        val epochBefore = coordinator.deathEpoch

        val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { coordinator.refreshNow() }
        }

        assertThat(thrown).isSameInstanceAs(cancel)
        assertThat(hookCalls).isEqualTo(0)
        assertThat(coordinator.deathEpoch).isEqualTo(epochBefore)
        // A cancelled refresh says nothing about the session, so it is never cleared as a revoked one.
        assertThat(turnkey.clearSessionCalls).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
    }

    @Test
    fun `a cancellation wrapped in the refresh after a 401 is rethrown and the hook stays silent`() {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.getActivitiesError = RuntimeException("HTTP error from /activities: 401")
        val cancel = kotlinx.coroutines.CancellationException("caller went away")
        turnkey.refreshSessionError = TurnkeyKotlinError.FailedToRefreshSession(cancel)
        var hookCalls = 0
        val coordinator = coordinator(turnkey, onSessionExpired = { hookCalls++ })

        val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) } }
        }

        assertThat(thrown).isSameInstanceAs(cancel)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
        assertThat(hookCalls).isEqualTo(0)
        assertThat(coordinator.deathEpoch).isEqualTo(0)
        assertThat(turnkey.clearSessionCalls).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
    }

    @Test
    fun `a cancellation inside the clear of a revoked session is rethrown, not a session death`() {
        val turnkey = MockTurnkey()
        turnkey.refreshSessionError = revokedSessionRefreshFailure()
        val cancel = kotlinx.coroutines.CancellationException("caller went away")
        turnkey.clearSessionError = TurnkeyKotlinError.FailedToClearSession(cancel)
        var hookCalls = 0
        val coordinator = coordinator(turnkey, onSessionExpired = { hookCalls++ })

        val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { coordinator.refreshNow() }
        }

        // The refusal reached the clear, the caller went away during it, and that is what leaves:
        // no TokenExpired, no hook, no death counted.
        assertThat(thrown).isSameInstanceAs(cancel)
        assertThat(turnkey.clearSessionCalls).containsExactly(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(turnkey.session).isNotNull()
        assertThat(hookCalls).isEqualTo(0)
        assertThat(coordinator.deathEpoch).isEqualTo(0)
    }

    @Test
    fun `a cancellation the vendor wrapped inside the block leaves as itself, never mapped`() {
        val turnkey = MockTurnkey()
        val coordinator = coordinator(turnkey)
        val cancel = kotlinx.coroutines.CancellationException("caller went away")

        val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking {
                coordinator.executeWrite<String> { _, _ -> throw TurnkeyKotlinError.FailedToSignRawPayload(cancel) }
            }
        }

        assertThat(thrown).isSameInstanceAs(cancel)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
    }
}
