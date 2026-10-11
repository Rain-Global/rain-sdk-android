package com.rain.sdk.turnkey

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Contact attach on [TurnkeyManagedAuthController]: a code verified without logging in, the
 * account lookup that refuses a contact another account signs in with, then the signed-in user's
 * email or phone set with the verification token, under a pending slot the login code cannot
 * consume and that a login or a logout drops. Vendor types stay inside method bodies (see
 * [TurnkeyErrorMappingTest] for why).
 */
class TurnkeyManagedContactAttachTest {

    private var hookCalls = 0
    private val activity: Activity = mockk(relaxed = true)

    @Before
    fun setUp() = assumeJdk24()

    private fun coordinator(turnkey: MockTurnkey) = TurnkeySessionCoordinator(
        turnkey = turnkey,
        onSessionExpired = { hookCalls++ },
        retryDelay = { },
    )

    private fun controller(
        turnkey: MockTurnkey,
        passkeyDomain: String? = "passkeys.example.com",
    ) = TurnkeyManagedAuthController(
        context = turnkey,
        coordinator = coordinator(turnkey),
        configure = { null },
        passkeyDomain = passkeyDomain,
    )

    private fun rejectedCode(): Exception = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(
        RuntimeException("HTTP error from /v1/otp_verify_v2: 401")
    )

    @Test
    fun `sending a verification code without a session is TokenExpired and sends nothing`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val controller = controller(turnkey)

        expectThrows<RainError.TokenExpired> { controller.sendContactVerificationCode(LoginContact.Email("user@example.com")) }

        assertThat(turnkey.sendOtpCalls).isEmpty()
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `the contact is canonicalised the way a login contact is, and malformed shapes are refused`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)

        controller.sendContactVerificationCode(LoginContact.Email("  User@Example.com "))
        controller.sendContactVerificationCode(LoginContact.Phone("+1 (321) 456-7890"))

        assertThat(turnkey.sendOtpCalls.map { it.contact }).containsExactly("user@example.com", "+13214567890").inOrder()
        assertThat(turnkey.sendOtpCalls.map { it.channel }).containsExactly(OtpChannel.EMAIL, OtpChannel.SMS).inOrder()

        expectThrows<RainError.InvalidConfig> { controller.sendContactVerificationCode(LoginContact.Email("   ")) }
        expectThrows<RainError.InvalidConfig> { controller.sendContactVerificationCode(LoginContact.Phone("+44 (0) 20 1234 5678")) }
        expectThrows<RainError.InvalidConfig> { controller.sendContactVerificationCode(LoginContact.Phone("1234")) }
        assertThat(turnkey.sendOtpCalls).hasSize(2)
    }

    @Test
    fun `a verification code and a login code coexist and neither confirm consumes the other's`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)
        turnkey.stubbedOtpChallenge = OtpChallenge(otpId = "otp-login", encryptionTargetBundle = "b1", channel = OtpChannel.EMAIL)
        controller.sendLoginCode(LoginContact.Email("login@example.com"))
        turnkey.stubbedOtpChallenge = OtpChallenge(otpId = "otp-attach", encryptionTargetBundle = "b2", channel = OtpChannel.EMAIL)
        controller.sendContactVerificationCode(LoginContact.Email("attach@example.com"))

        controller.confirmContactVerification("111111")

        assertThat(turnkey.verifyOtpTokenCalls.single().otpId).isEqualTo("otp-attach")
        assertThat(turnkey.completeOtpCalls).isEmpty()

        controller.confirmLoginCode("222222")

        assertThat(turnkey.completeOtpCalls.single().otpId).isEqualTo("otp-login")
        assertThat(turnkey.verifyOtpTokenCalls).hasSize(1)
    }

    @Test
    fun `a second send replaces the pending verification code, and a failed switch of contact leaves nothing confirmable`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)
        turnkey.stubbedOtpChallenge = OtpChallenge(otpId = "otp-1", encryptionTargetBundle = "b1", channel = OtpChannel.EMAIL)
        controller.sendContactVerificationCode(LoginContact.Email("first@example.com"))
        turnkey.stubbedOtpChallenge = OtpChallenge(otpId = "otp-2", encryptionTargetBundle = "b2", channel = OtpChannel.EMAIL)
        controller.sendContactVerificationCode(LoginContact.Email("second@example.com"))

        controller.confirmContactVerification("111111")

        assertThat(turnkey.verifyOtpTokenCalls.single().otpId).isEqualTo("otp-2")
        assertThat(turnkey.setUserEmailCalls.single().contact).isEqualTo("second@example.com")

        // The login send's rule: a failed resend for the same contact keeps its code, a failed send for
        // another contact has already retired the pending one, so nothing confirms the wrong contact.
        turnkey.stubbedOtpChallenge = OtpChallenge(otpId = "otp-3", encryptionTargetBundle = "b3", channel = OtpChannel.EMAIL)
        controller.sendContactVerificationCode(LoginContact.Email("third@example.com"))
        turnkey.sendOtpError = RainError.ProviderError(RuntimeException("refused"))
        expectThrows<RainError.ProviderError> { controller.sendContactVerificationCode(LoginContact.Email("third@example.com")) }
        turnkey.sendOtpError = null
        controller.confirmContactVerification("222222")
        assertThat(turnkey.verifyOtpTokenCalls.last().otpId).isEqualTo("otp-3")
        assertThat(turnkey.setUserEmailCalls.last().contact).isEqualTo("third@example.com")

        turnkey.stubbedOtpChallenge = OtpChallenge(otpId = "otp-4", encryptionTargetBundle = "b4", channel = OtpChannel.EMAIL)
        controller.sendContactVerificationCode(LoginContact.Email("fourth@example.com"))
        turnkey.sendOtpError = RainError.ProviderError(RuntimeException("refused"))
        expectThrows<RainError.ProviderError> { controller.sendContactVerificationCode(LoginContact.Email("fifth@example.com")) }
        turnkey.sendOtpError = null

        val nothing = expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("333333") }
        assertThat(nothing).hasMessageThat().contains("sendContactVerificationCode")
        assertThat(turnkey.verifyOtpTokenCalls).hasSize(2)
    }

    @Test
    fun `a transient status on the update is not retried and its body never reaches the message`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.setUserContactError = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_UPDATE_USER_EMAIL request\nError: {\"email\":\"user@example.com\"}\nCode: 503"
        )
        val controller = controller(turnkey)
        controller.sendContactVerificationCode(LoginContact.Email("user@example.com"))

        val failed = expectThrows<RainError.ProviderError> { controller.confirmContactVerification("123456") }

        // One update, no refresh: a retry would resubmit a single-use verification token.
        assertThat(turnkey.setUserEmailCalls).hasSize(1)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
        assertThat(failed).hasMessageThat().contains("503")
        assertThat(failed).hasMessageThat().contains("ACTIVITY_TYPE_UPDATE_USER_EMAIL")
        assertThat(failed).hasMessageThat().doesNotContain("example.com")
    }

    @Test
    fun `a cancellation inside the verify step propagates as itself and keeps the pending challenge`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)
        controller.sendContactVerificationCode(LoginContact.Email("user@example.com"))
        turnkey.verifyOtpTokenError = CancellationException("cancelled")

        expectThrows<CancellationException> { controller.confirmContactVerification("123456") }

        assertThat(turnkey.verifyOtpTokenCalls).hasSize(1)
        assertThat(turnkey.setUserEmailCalls).isEmpty()

        // The challenge survived: the retry spends the same one, with no new code requested.
        turnkey.verifyOtpTokenError = null
        controller.confirmContactVerification("123456")

        assertThat(turnkey.verifyOtpTokenCalls.map { it.otpId }.distinct()).hasSize(1)
        assertThat(turnkey.sendOtpCalls).hasSize(1)
        assertThat(turnkey.setUserEmailCalls).hasSize(1)
    }

    @Test
    fun `a cancellation inside the send step propagates as itself and leaves nothing to confirm`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)
        turnkey.sendOtpError = CancellationException("cancelled")

        expectThrows<CancellationException> { controller.sendContactVerificationCode(LoginContact.Email("user@example.com")) }

        turnkey.sendOtpError = null
        expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("123456") }
        assertThat(turnkey.verifyOtpTokenCalls).isEmpty()
    }

    @Test
    fun `confirming without a requested verification code is InvalidConfig`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)

        val refused = expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("123456") }

        assertThat(refused).hasMessageThat().contains("sendContactVerificationCode")
        assertThat(turnkey.verifyOtpTokenCalls).isEmpty()
    }

    @Test
    fun `confirming an email verifies the code then sets the email with the session's ids, the canonical contact and the token`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.stubbedOtpChallenge = OtpChallenge(otpId = "otp-1", encryptionTargetBundle = "bundle-1", channel = OtpChannel.EMAIL)
        val controller = controller(turnkey)
        controller.sendContactVerificationCode(LoginContact.Email(" user@example.com "))

        controller.confirmContactVerification(" 123456 ")

        val verify = turnkey.verifyOtpTokenCalls.single()
        assertThat(verify.otpId).isEqualTo("otp-1")
        assertThat(verify.otpCode).isEqualTo("123456")
        assertThat(verify.encryptionTargetBundle).isEqualTo("bundle-1")
        val set = turnkey.setUserEmailCalls.single()
        assertThat(set.organizationId).isEqualTo(MockTurnkey.DEFAULT_ORG_ID)
        assertThat(set.userId).isEqualTo("user-id")
        assertThat(set.contact).isEqualTo("user@example.com")
        assertThat(set.verificationToken).isEqualTo(turnkey.stubbedVerificationToken)
        assertThat(turnkey.setUserPhoneNumberCalls).isEmpty()
        // The ownership lookup ran between the verify and the update, for the canonical contact
        // with the same token, and nobody owning the contact let the update run.
        val lookup = turnkey.accountLookupCalls.single()
        assertThat(lookup.channel).isEqualTo(OtpChannel.EMAIL)
        assertThat(lookup.contact).isEqualTo("user@example.com")
        assertThat(lookup.verificationToken).isEqualTo(turnkey.stubbedVerificationToken)
        // The session is untouched and the slot is spent.
        assertThat(turnkey.selectedSessionKey).isEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
        expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("123456") }
    }

    @Test
    fun `confirming a phone number sets the phone number`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)
        controller.sendContactVerificationCode(LoginContact.Phone("+1 999 999 9999"))

        controller.confirmContactVerification("000000")

        val set = turnkey.setUserPhoneNumberCalls.single()
        assertThat(set.contact).isEqualTo("+19999999999")
        assertThat(set.userId).isEqualTo("user-id")
        assertThat(turnkey.setUserEmailCalls).isEmpty()
        val lookup = turnkey.accountLookupCalls.single()
        assertThat(lookup.channel).isEqualTo(OtpChannel.SMS)
        assertThat(lookup.contact).isEqualTo("+19999999999")
    }

    @Test
    fun `a contact another account signs in with is refused with Unauthorized before anything changes`() = runTest {
        for (contact in listOf(LoginContact.Email("Taken@Example.com"), LoginContact.Phone("+1 (999) 999-9999"))) {
            val turnkey = MockTurnkey()
            turnkey.accountLookupResult = "another-org-id"
            val controller = controller(turnkey)
            controller.sendContactVerificationCode(contact)

            val refused = expectThrows<RainError.Unauthorized> { controller.confirmContactVerification("123456") }

            assertThat(refused.errorCode).isEqualTo(RainErrorCode.UNAUTHORIZED)
            assertThat(refused).hasMessageThat().contains(TurnkeyManagedAuthController.CONTACT_OWNED_MESSAGE)
            assertThat(refused).hasMessageThat().doesNotContain("example.com")
            assertThat(refused).hasMessageThat().doesNotContain("9999")
            // The code was spent on the verify, the lookup ran for the canonical contact, nothing was written.
            assertThat(turnkey.verifyOtpTokenCalls).hasSize(1)
            val lookup = turnkey.accountLookupCalls.single()
            assertThat(lookup.contact).isEqualTo(if (contact is LoginContact.Email) "taken@example.com" else "+19999999999")
            assertThat(lookup.channel).isEqualTo(if (contact is LoginContact.Email) OtpChannel.EMAIL else OtpChannel.SMS)
            assertThat(turnkey.setUserEmailCalls).isEmpty()
            assertThat(turnkey.setUserPhoneNumberCalls).isEmpty()
            // The session is untouched, read at once, and the host hook stayed silent.
            assertThat(turnkey.selectedSessionKey).isEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
            assertThat(controller.hasActiveSession()).isTrue()
            assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
            assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
            assertThat(hookCalls).isEqualTo(0)
            // The challenge is dropped, as after any failure past the verify: the next attempt needs a new code.
            val spent = expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("123456") }
            assertThat(spent).hasMessageThat().contains("sendContactVerificationCode")
            assertThat(turnkey.verifyOtpTokenCalls).hasSize(1)
        }
    }

    @Test
    fun `a contact this account signs in with passes the lookup and the update runs`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.accountLookupResult = MockTurnkey.DEFAULT_ORG_ID
        val controller = controller(turnkey)
        controller.sendContactVerificationCode(LoginContact.Email("mine@example.com"))

        controller.confirmContactVerification("123456")

        assertThat(turnkey.accountLookupCalls).hasSize(1)
        assertThat(turnkey.setUserEmailCalls.single().contact).isEqualTo("mine@example.com")
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `a failed lookup is ProviderError whatever its status, runs no update and drops the challenge`() = runTest {
        // No session stamps the lookup, so its 401 is neither a session death nor a refresh to run,
        // and its 403 is not a permission the organization lacks.
        val failures = listOf(
            RuntimeException("HTTP error from /v1/account: 401"),
            RuntimeException("HTTP error from /v1/account: 403"),
            RuntimeException("HTTP error from /v1/account: 500"),
            java.io.IOException("offline"),
        )
        for (failure in failures) {
            val turnkey = MockTurnkey()
            turnkey.accountLookupError = failure
            val controller = controller(turnkey)
            controller.sendContactVerificationCode(LoginContact.Email("user@example.com"))

            val failed = expectThrows<RainError.ProviderError> { controller.confirmContactVerification("123456") }

            assertThat(failed).hasMessageThat().doesNotContain("example.com")
            assertThat(turnkey.accountLookupCalls).hasSize(1)
            assertThat(turnkey.setUserEmailCalls).isEmpty()
            assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
            assertThat(turnkey.selectedSessionKey).isEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
            assertThat(controller.hasActiveSession()).isTrue()
            assertThat(hookCalls).isEqualTo(0)
            // The code is spent, so the slot is gone: the next confirm needs a new code.
            expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("123456") }
        }
    }

    @Test
    fun `a cancellation inside the lookup propagates as itself and runs no update`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.accountLookupError = CancellationException("cancelled")
        val controller = controller(turnkey)
        controller.sendContactVerificationCode(LoginContact.Email("user@example.com"))

        expectThrows<CancellationException> { controller.confirmContactVerification("123456") }

        assertThat(turnkey.accountLookupCalls).hasSize(1)
        assertThat(turnkey.setUserEmailCalls).isEmpty()
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `a wrong code is InvalidLoginCode, keeps the challenge, and the right code then succeeds with no new send`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)
        controller.sendContactVerificationCode(LoginContact.Email("user@example.com"))
        turnkey.verifyOtpTokenError = rejectedCode()

        expectThrows<RainError.InvalidLoginCode> { controller.confirmContactVerification("000000") }

        assertThat(turnkey.setUserEmailCalls).isEmpty()
        turnkey.verifyOtpTokenError = null

        controller.confirmContactVerification("123456")

        assertThat(turnkey.sendOtpCalls).hasSize(1)
        assertThat(turnkey.verifyOtpTokenCalls).hasSize(2)
        assertThat(turnkey.setUserEmailCalls).hasSize(1)
    }

    @Test
    fun `an unclassifiable verify failure keeps the challenge`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)
        controller.sendContactVerificationCode(LoginContact.Email("user@example.com"))
        turnkey.verifyOtpTokenError = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(
            RuntimeException("HTTP error from /v1/otp_verify_v2: 500")
        )

        expectThrows<RainError.ProviderError> { controller.confirmContactVerification("123456") }

        turnkey.verifyOtpTokenError = null
        controller.confirmContactVerification("123456")
        assertThat(turnkey.setUserEmailCalls).hasSize(1)
    }

    @Test
    fun `a failure setting the contact after the code was accepted drops the challenge and maps`() = runTest {
        // 401: the coordinator refreshes once, the refresh is down, so the session is dead.
        val expired = MockTurnkey()
        expired.setUserContactError =
            RuntimeException("HTTP error calling ACTIVITY_TYPE_UPDATE_USER_EMAIL request\nError: {}\nCode: 401")
        expired.refreshSessionError = RuntimeException("refresh is down")
        val controller = controller(expired)
        controller.sendContactVerificationCode(LoginContact.Email("user@example.com"))

        expectThrows<RainError.TokenExpired> { controller.confirmContactVerification("123456") }

        assertThat(expired.verifyOtpTokenCalls).hasSize(1)
        assertThat(expired.refreshSessionCallCount).isEqualTo(1)
        // The code is spent by the verify, so the slot is gone: the next confirm needs a new code.
        val refused = expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("123456") }
        assertThat(refused).hasMessageThat().contains("sendContactVerificationCode")

        // 403 and a failed activity map without a refresh.
        val forbidden = MockTurnkey()
        forbidden.setUserContactError =
            RuntimeException("HTTP error calling ACTIVITY_TYPE_UPDATE_USER_EMAIL request\nError: {}\nCode: 403")
        val forbiddenController = controller(forbidden)
        forbiddenController.sendContactVerificationCode(LoginContact.Email("user@example.com"))
        expectThrows<RainError.Unauthorized> { forbiddenController.confirmContactVerification("123456") }

        val failed = MockTurnkey()
        // The vendor's typed error for an update activity Turnkey failed (http 2.2.0): the provider's failure.
        failed.setUserContactError = MockTurnkey.activityNotCompleted(
            path = "/public/v1/submit/update_user_email",
            type = com.turnkey.types.V1ActivityType.ACTIVITY_TYPE_UPDATE_USER_EMAIL,
            status = com.turnkey.types.V1ActivityStatus.ACTIVITY_STATUS_FAILED
        )
        val failedController = controller(failed)
        failedController.sendContactVerificationCode(LoginContact.Email("user@example.com"))
        expectThrows<RainError.ProviderError> { failedController.confirmContactVerification("123456") }
        assertThat(failed.refreshSessionCallCount).isEqualTo(0)
    }

    @Test
    fun `logout drops a pending verification`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)
        controller.sendContactVerificationCode(LoginContact.Email("user@example.com"))

        controller.logout()
        turnkey.authenticate()
        turnkey.selectedSessionKey = MockTurnkey.DEFAULT_SESSION_KEY

        expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("123456") }
        assertThat(turnkey.verifyOtpTokenCalls).isEmpty()
    }

    @Test
    fun `a passkey login and a confirmed login code drop a pending verification`() = runTest {
        val passkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        val passkeyController = controller(passkey)
        passkeyController.sendContactVerificationCode(LoginContact.Email("user@example.com"))

        passkeyController.loginWithPasskey(activity)

        expectThrows<RainError.InvalidConfig> { passkeyController.confirmContactVerification("123456") }
        assertThat(passkey.verifyOtpTokenCalls).isEmpty()

        val code = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        val codeController = controller(code)
        codeController.sendContactVerificationCode(LoginContact.Email("user@example.com"))
        codeController.sendLoginCode(LoginContact.Email("other@example.com"))

        codeController.confirmLoginCode("123456")

        expectThrows<RainError.InvalidConfig> { codeController.confirmContactVerification("123456") }
        assertThat(code.verifyOtpTokenCalls).isEmpty()
    }

    @Test
    fun `a blank code is InvalidConfig and keeps the challenge, and a closed controller refuses both calls`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)
        controller.sendContactVerificationCode(LoginContact.Email("user@example.com"))

        expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("   ") }
        assertThat(turnkey.verifyOtpTokenCalls).isEmpty()
        controller.confirmContactVerification("123456")
        assertThat(turnkey.setUserEmailCalls).hasSize(1)

        val closed = controller(MockTurnkey())
        closed.close()
        expectThrows<RainError.InvalidConfig> { closed.sendContactVerificationCode(LoginContact.Email("user@example.com")) }
        expectThrows<RainError.InvalidConfig> { closed.confirmContactVerification("123456") }
    }

    @Test
    fun `confirming without a live session is TokenExpired and spends no code`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)
        controller.sendContactVerificationCode(LoginContact.Email("user@example.com"))
        // The session died between the send and the confirm.
        turnkey.session = null
        turnkey.selectedSessionKey = null

        expectThrows<RainError.TokenExpired> { controller.confirmContactVerification("123456") }

        assertThat(turnkey.verifyOtpTokenCalls).isEmpty()
        assertThat(turnkey.setUserEmailCalls).isEmpty()
    }
}
