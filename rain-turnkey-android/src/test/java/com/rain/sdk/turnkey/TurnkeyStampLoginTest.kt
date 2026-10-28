package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import com.turnkey.types.Externaldatav1Timestamp
import com.turnkey.types.TStampLoginResponse
import com.turnkey.types.V1Activity
import com.turnkey.types.V1ActivityStatus
import com.turnkey.types.V1ActivityType
import com.turnkey.types.V1Intent
import com.turnkey.types.V1Result
import com.turnkey.types.V1StampLoginResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * The stamp rule behind `TurnkeyContextAdapter.stampLoginWithDeviceKey`, on the two functions it
 * calls: [deviceKeyStampLoginBody] is the request and [stampLoginSessionOrNull] sorts the stamp's
 * endings into a session, a refusal (null) and the errors. The sign-up tests answer the stamp through
 * `MockTurnkey.deviceKeyLogin`, so the endings are pinned here only; what each one means to a host is
 * the `signUpWithPasskey` row in `docs/METHODS.md`. The test class names no Turnkey type in a method
 * or field signature (see [TurnkeyErrorMappingTest] for why); the fixture is a file-level function,
 * compiled into a class JUnit never scans.
 */
class TurnkeyStampLoginTest {

    @Before
    fun requireJdk24() = assumeJdk24()

    @Test
    fun `the body asks a session bound to the fresh session key on the organization, for 900 seconds, revoking the others`() {
        val body = deviceKeyStampLoginBody(sessionPublicKey = "04-session-key", organizationId = "sub-org-1")

        assertThat(body.organizationId).isEqualTo("sub-org-1")
        assertThat(body.publicKey).isEqualTo("04-session-key")
        assertThat(body.expirationSeconds).isEqualTo("900")
        assertThat(body.invalidateExisting).isTrue()
    }

    @Test
    fun `a stamp answers the session it issued, or null when Turnkey refused it or completed it without one`() = runBlocking<Unit> {
        assertThat(stampLoginSessionOrNull { stampLoginResponse(session = "jwt") }).isEqualTo("jwt")

        // The generated client reports a non-2xx on submit as "Code: N" with the body, and on the
        // activity poll as "from <path>: N" without it.
        val refusals = listOf(
            "4xx on submit" to RuntimeException("HTTP error calling ACTIVITY_TYPE_STAMP_LOGIN request\nError: {\"message\":\"nope\"}\nCode: 400"),
            "4xx on poll" to RuntimeException("HTTP error from /public/v1/query/get_activity: 404"),
            "no session" to MockTurnkey.activityNotCompleted(
                path = "/public/v1/submit/stamp_login",
                type = V1ActivityType.ACTIVITY_TYPE_STAMP_LOGIN,
                status = V1ActivityStatus.ACTIVITY_STATUS_FAILED,
                failureMessage = "no api key for this stamper",
            ),
        )

        refusals.forEach { (case, failure) ->
            assertWithMessage(case).that(stampLoginSessionOrNull { throw failure }).isNull()
        }
    }

    @Test
    fun `a failed stamp leaves as thrown, except a transport failure, which leaves as NetworkError named by class`() = runBlocking<Unit> {
        val rethrown = listOf(
            "5xx" to RuntimeException("HTTP error calling ACTIVITY_TYPE_STAMP_LOGIN request\nError: {\"message\":\"down\"}\nCode: 503"),
            "no status" to IllegalArgumentException("Unexpected JSON token at offset 0: Expected string literal"),
            "cancellation" to CancellationException("caller went away"),
        )

        rethrown.forEach { (case, failure) ->
            val thrown = expectThrows<Throwable> { stampLoginSessionOrNull { throw failure } }

            assertWithMessage(case).that(thrown).isSameInstanceAs(failure)
        }

        val marker = "body-the-host-must-not-see"
        val transport = SocketTimeoutException("read timed out: $marker")

        val thrown = expectThrows<RainError.NetworkError> { stampLoginSessionOrNull { throw transport } }

        assertThat(thrown.errorCode).isEqualTo(RainErrorCode.NETWORK_ERROR)
        assertThat(thrown.message).contains("SocketTimeoutException")
        assertThat(thrown.message).doesNotContain(marker)
        assertThat(thrown.cause).isSameInstanceAs(transport)
    }
}

/** A stamp-login answer in the generated client's shape: the completed activity whose result holds the session. */
private fun stampLoginResponse(session: String): TStampLoginResponse {
    val result = V1StampLoginResult(session = session)
    return TStampLoginResponse(
        activity = V1Activity(
            canApprove = false,
            canReject = false,
            createdAt = Externaldatav1Timestamp(nanos = "0", seconds = "1714521600"),
            fingerprint = "fingerprint",
            id = "activity-1",
            intent = V1Intent(),
            organizationId = "sub-org-1",
            result = V1Result(stampLoginResult = result),
            status = V1ActivityStatus.ACTIVITY_STATUS_COMPLETED,
            type = V1ActivityType.ACTIVITY_TYPE_STAMP_LOGIN,
            updatedAt = Externaldatav1Timestamp(nanos = "0", seconds = "1714521600"),
            votes = emptyList(),
        ),
        result = result,
    )
}
