package com.rain.sdk.turnkey

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Contact attach on [TurnkeyManagedAuthController]: a wallet auth token for the new contact, a
 * code verified against Rain with a throwaway device key and without logging in, then the
 * signed-in user's email or phone set with the verification token, under a pending slot the login
 * code cannot consume and that a login or a logout drops. Nothing asks Rain whether another
 * account signs in with the contact (a known issue in the CHANGELOG), so no test here expects a
 * refusal on that ground. Vendor types stay inside method bodies (see [TurnkeyErrorMappingTest]
 * for why).
 */
class TurnkeyManagedContactAttachTest {

    private var hookCalls = 0
    private val activity: Activity = mockk(relaxed = true)
    private val tokenRequests = mutableListOf<LoginContact?>()

    @Before
    fun setUp() = assumeJdk24()

    private fun coordinator(turnkey: MockTurnkey) = TurnkeySessionCoordinator(
        turnkey = turnkey,
        onSessionExpired = { hookCalls++ },
        retryDelay = { },
    )

    private fun controller(
        turnkey: MockTurnkey,
        rainApi: MockRainApiAuth = rainApi(),
        authTokenProvider: suspend (LoginContact?) -> String = { "wat_1" },
        passkeyDomain: String? = "passkeys.example.com",
    ) = TurnkeyManagedAuthController(
        context = turnkey,
        rainApi = rainApi,
        authTokenProvider = { contact ->
            tokenRequests += contact
            authTokenProvider(contact)
        },
        coordinator = coordinator(turnkey),
        configure = { null },
        passkeyDomain = passkeyDomain,
    )

    private val email = LoginContact.Email("user@example.com")

    /** Rain's answers for an attach: the defaults; the attach never calls the login. */
    private fun rainApi() = MockRainApiAuth()

    @Test
    fun `sending a verification code without a session is TokenExpired and asks nobody for anything`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val rainApi = rainApi()
        val controller = controller(turnkey, rainApi)

        expectThrows<RainError.TokenExpired> { controller.sendContactVerificationCode(email) }

        assertThat(tokenRequests).isEmpty()
        assertThat(rainApi.initOtpCalls).isEmpty()
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `the contact is canonicalised the way a login contact is, the host gets that string, and malformed shapes are refused`() =
        runTest {
            val turnkey = MockTurnkey()
            val rainApi = rainApi()
            val controller = controller(turnkey, rainApi)

            controller.sendContactVerificationCode(LoginContact.Email("  User@Example.com "))
            controller.sendContactVerificationCode(LoginContact.Phone("+1 (321) 456-7890"))

            assertThat(tokenRequests).containsExactly(LoginContact.Email("user@example.com"), LoginContact.Phone("+13214567890")).inOrder()
            assertThat(rainApi.initOtpCalls).hasSize(2)

            expectThrows<RainError.InvalidConfig> { controller.sendContactVerificationCode(LoginContact.Email("   ")) }
            expectThrows<RainError.InvalidConfig> { controller.sendContactVerificationCode(LoginContact.Phone("+44 (0) 20 1234 5678")) }
            expectThrows<RainError.InvalidConfig> { controller.sendContactVerificationCode(LoginContact.Phone("1234")) }
            assertThat(tokenRequests).hasSize(2)
            assertThat(rainApi.initOtpCalls).hasSize(2)
        }

    @Test
    fun `a verification code and a login code coexist and neither confirm consumes the other's`() = runTest {
        val turnkey = MockTurnkey()
        val rainApi = rainApi()
        rainApi.initOtp = MockRainApiAuth.answers(
            { RainApiOtpChallenge("otp-login", "b1") },
            { RainApiOtpChallenge("otp-attach", "b2") },
        )
        rainApi.login = MockRainApiAuth.answers({ throw MockRainApiAuth.noWallet() }, { MockRainApiAuth.defaultLogin() })
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(LoginContact.Email("login@example.com"))
        controller.sendContactVerificationCode(LoginContact.Email("attach@example.com"))

        controller.confirmContactVerification("111111")

        assertThat(rainApi.verifyOtpCalls.single().otpId).isEqualTo("otp-attach")
        // An attach runs no login: nothing was asked of Rain's login and nothing stored.
        assertThat(rainApi.loginCalls).isEmpty()
        assertThat(turnkey.storeSessionCalls).isEmpty()

        controller.confirmLoginCode("222222")

        assertThat(rainApi.verifyOtpCalls.map { it.otpId }).containsExactly("otp-attach", "otp-login").inOrder()
        assertThat(turnkey.storeSessionCalls).hasSize(1)
    }

    @Test
    fun `a second send replaces the pending verification code, and a failed switch of contact leaves nothing confirmable`() = runTest {
        val turnkey = MockTurnkey()
        val rainApi = rainApi()
        val challenges = ArrayDeque(listOf("otp-1", "otp-2", "otp-3", "otp-4"))
        rainApi.initOtp = { RainApiOtpChallenge(challenges.removeFirst(), "bundle") }
        val controller = controller(turnkey, rainApi)
        controller.sendContactVerificationCode(LoginContact.Email("first@example.com"))
        controller.sendContactVerificationCode(LoginContact.Email("second@example.com"))

        controller.confirmContactVerification("111111")

        assertThat(rainApi.verifyOtpCalls.single().otpId).isEqualTo("otp-2")
        assertThat(turnkey.setUserEmailCalls.single().contact).isEqualTo("second@example.com")

        // The login send's rule: a failed resend for the same contact keeps its code, a failed send for
        // another contact has already retired the pending one, so nothing confirms the wrong contact.
        controller.sendContactVerificationCode(LoginContact.Email("third@example.com"))
        val saved = rainApi.initOtp
        rainApi.initOtp = { throw MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 503, MockRainApiAuth.INIT_PATH) }
        expectThrows<RainError.ProviderError> { controller.sendContactVerificationCode(LoginContact.Email("third@example.com")) }
        rainApi.initOtp = saved
        controller.confirmContactVerification("222222")
        assertThat(rainApi.verifyOtpCalls.last().otpId).isEqualTo("otp-3")
        assertThat(turnkey.setUserEmailCalls.last().contact).isEqualTo("third@example.com")

        controller.sendContactVerificationCode(LoginContact.Email("fourth@example.com"))
        rainApi.initOtp = { throw MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 503, MockRainApiAuth.INIT_PATH) }
        expectThrows<RainError.ProviderError> { controller.sendContactVerificationCode(LoginContact.Email("fifth@example.com")) }

        val nothing = expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("333333") }
        assertThat(nothing).hasMessageThat().contains("sendContactVerificationCode")
        assertThat(rainApi.verifyOtpCalls).hasSize(2)
    }

    @Test
    fun `a transient status on the update is not retried and its body never reaches the message`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.setUserContactError = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_UPDATE_USER_EMAIL request\nError: {\"email\":\"user@example.com\"}\nCode: 503"
        )
        val controller = controller(turnkey)
        controller.sendContactVerificationCode(email)

        val failed = expectThrows<RainError.ProviderError> { controller.confirmContactVerification("123456") }

        // One update, no refresh: a retry would resubmit a single-use verification token.
        assertThat(turnkey.setUserEmailCalls).hasSize(1)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
        assertThat(failed).hasMessageThat().contains("503")
        assertThat(failed).hasMessageThat().contains("ACTIVITY_TYPE_UPDATE_USER_EMAIL")
        assertThat(failed).hasMessageThat().doesNotContain("example.com")
    }

    @Test
    fun `a cancellation inside the verify step propagates as itself, keeps the pending challenge and deletes the throwaway key`() =
        runTest {
            val turnkey = MockTurnkey()
            val rainApi = rainApi()
            rainApi.verifyOtp = MockRainApiAuth.answers({ throw CancellationException("cancelled") }, { RainApiVerification("vt") })
            val controller = controller(turnkey, rainApi)
            controller.sendContactVerificationCode(email)

            expectThrows<CancellationException> { controller.confirmContactVerification("123456") }

            assertThat(rainApi.verifyOtpCalls).hasSize(1)
            assertThat(turnkey.setUserEmailCalls).isEmpty()
            assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")

            // The challenge survived: the retry spends the same one, with no new code requested.
            controller.confirmContactVerification("123456")

            assertThat(rainApi.verifyOtpCalls.map { it.otpId }.distinct()).hasSize(1)
            assertThat(rainApi.initOtpCalls).hasSize(1)
            assertThat(turnkey.setUserEmailCalls).hasSize(1)
            assertThat(turnkey.liveDeviceKeys).isEmpty()
        }

    @Test
    fun `a cancellation inside the send step propagates as itself and leaves nothing to confirm`() = runTest {
        val turnkey = MockTurnkey()
        val rainApi = rainApi()
        rainApi.initOtp = { throw CancellationException("cancelled") }
        val controller = controller(turnkey, rainApi)

        expectThrows<CancellationException> { controller.sendContactVerificationCode(email) }

        rainApi.initOtp = { RainApiOtpChallenge("otp-id", "bundle") }
        expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("123456") }
        assertThat(rainApi.verifyOtpCalls).isEmpty()
    }

    @Test
    fun `confirming without a requested verification code is InvalidConfig`() = runTest {
        val turnkey = MockTurnkey()
        val rainApi = rainApi()
        val controller = controller(turnkey, rainApi)

        val refused = expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("123456") }

        assertThat(refused).hasMessageThat().contains("sendContactVerificationCode")
        assertThat(rainApi.verifyOtpCalls).isEmpty()
        assertThat(turnkey.createdDeviceKeys).isEmpty()
    }

    @Test
    fun `confirming an email verifies the code under the new contact's token with a throwaway key, deletes it, then sets the email`() =
        runTest {
            val turnkey = MockTurnkey() // signed in as whoever holds the default session
            val rainApi = rainApi()
            rainApi.initOtp = { RainApiOtpChallenge("otp-1", "bundle-1") }
            rainApi.verifyOtp = { RainApiVerification("vt-attach") }
            val controller = controller(turnkey, rainApi, authTokenProvider = { "wat_attach" })
            controller.sendContactVerificationCode(LoginContact.Email(" user@example.com "))

            controller.confirmContactVerification(" 123456 ")

            // The token is the new contact's, not the session's, and nothing sent names the contact.
            assertThat(tokenRequests).containsExactly(LoginContact.Email("user@example.com"))
            assertThat(rainApi.initOtpCalls).containsExactly("wat_attach")
            assertThat(turnkey.encryptOtpCodeCalls.single()).isEqualTo(MockTurnkey.EncryptOtpCodeCall("123456", "bundle-1", "device-key-1"))
            val verify = rainApi.verifyOtpCalls.single()
            assertThat(verify.token).isEqualTo("wat_attach")
            assertThat(verify.otpId).isEqualTo("otp-1")
            assertThat(verify.encryptedOtpBundle).isEqualTo("encrypted(123456,bundle-1,device-key-1)")
            rainApi.everythingSent.forEach { assertThat(it).doesNotContain("example.com") }
            // The throwaway key never stamps anything and is gone before the update runs.
            assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
            assertThat(turnkey.liveDeviceKeys).isEmpty()
            val set = turnkey.setUserEmailCalls.single()
            assertThat(set.organizationId).isEqualTo(MockTurnkey.DEFAULT_ORG_ID)
            assertThat(set.userId).isEqualTo("user-id")
            assertThat(set.contact).isEqualTo("user@example.com")
            assertThat(set.verificationToken).isEqualTo("vt-attach")
            assertThat(turnkey.setUserPhoneNumberCalls).isEmpty()
            // No login and no signature: the attach asks Rain nothing about the contact's owner, and the
            // live session is untouched. The slot is spent.
            assertThat(rainApi.loginCalls).isEmpty()
            assertThat(turnkey.loginSignatureCalls).isEmpty()
            assertThat(turnkey.storeSessionCalls).isEmpty()
            assertThat(turnkey.selectedSessionKey).isEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
            assertThat(hookCalls).isEqualTo(0)
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
        assertThat(tokenRequests).containsExactly(LoginContact.Phone("+19999999999"))
    }

    @Test
    fun `a refused token on the attach verify fetches one fresh token for the new contact and retries once`() = runTest {
        val turnkey = MockTurnkey()
        val rainApi = rainApi()
        rainApi.verifyOtp = MockRainApiAuth.answers(
            { throw MockRainApiAuth.tokenRejected(MockRainApiAuth.VERIFY_PATH) },
            { RainApiVerification("vt") },
        )
        var minted = 0
        val controller = controller(turnkey, rainApi, authTokenProvider = { "wat_${++minted}" })
        controller.sendContactVerificationCode(email)

        controller.confirmContactVerification("123456")

        assertThat(rainApi.verifyOtpCalls.map { it.token }).containsExactly("wat_1", "wat_2").inOrder()
        assertThat(tokenRequests).containsExactly(email, email).inOrder()
        assertThat(turnkey.setUserEmailCalls).hasSize(1)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `a wrong code is InvalidLoginCode, keeps the challenge, and the right code then succeeds with no new send`() = runTest {
        val turnkey = MockTurnkey()
        val rainApi = rainApi()
        rainApi.verifyOtp = MockRainApiAuth.answers({ throw MockRainApiAuth.wrongCode() }, { RainApiVerification("vt") })
        val controller = controller(turnkey, rainApi)
        controller.sendContactVerificationCode(email)

        expectThrows<RainError.InvalidLoginCode> { controller.confirmContactVerification("000000") }

        assertThat(turnkey.setUserEmailCalls).isEmpty()
        assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")

        controller.confirmContactVerification("123456")

        assertThat(rainApi.initOtpCalls).hasSize(1)
        assertThat(rainApi.verifyOtpCalls).hasSize(2)
        assertThat(turnkey.setUserEmailCalls).hasSize(1)
        assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1", "device-key-2").inOrder()
    }

    @Test
    fun `a verify Rain could not finish keeps the challenge`() = runTest {
        val turnkey = MockTurnkey()
        val rainApi = rainApi()
        rainApi.verifyOtp = MockRainApiAuth.answers(
            { throw MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 500, MockRainApiAuth.VERIFY_PATH) },
            { RainApiVerification("vt") },
        )
        val controller = controller(turnkey, rainApi)
        controller.sendContactVerificationCode(email)

        expectThrows<RainError.ProviderError> { controller.confirmContactVerification("123456") }

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
        val rainApi = rainApi()
        val controller = controller(expired, rainApi)
        controller.sendContactVerificationCode(email)

        expectThrows<RainError.TokenExpired> { controller.confirmContactVerification("123456") }

        assertThat(rainApi.verifyOtpCalls).hasSize(1)
        assertThat(expired.refreshSessionCallCount).isEqualTo(1)
        // The code is spent by the verify, so the slot is gone: the next confirm needs a new code.
        val refused = expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("123456") }
        assertThat(refused).hasMessageThat().contains("sendContactVerificationCode")

        // 403 and a failed activity map without a refresh.
        val forbidden = MockTurnkey()
        forbidden.setUserContactError =
            RuntimeException("HTTP error calling ACTIVITY_TYPE_UPDATE_USER_EMAIL request\nError: {}\nCode: 403")
        val forbiddenController = controller(forbidden)
        forbiddenController.sendContactVerificationCode(email)
        expectThrows<RainError.Unauthorized> { forbiddenController.confirmContactVerification("123456") }

        val failed = MockTurnkey()
        // The vendor's typed error for an update activity Turnkey failed (http 2.2.0): the provider's failure.
        failed.setUserContactError = MockTurnkey.activityNotCompleted(
            path = "/public/v1/submit/update_user_email",
            type = com.turnkey.types.V1ActivityType.ACTIVITY_TYPE_UPDATE_USER_EMAIL,
            status = com.turnkey.types.V1ActivityStatus.ACTIVITY_STATUS_FAILED
        )
        val failedController = controller(failed)
        failedController.sendContactVerificationCode(email)
        expectThrows<RainError.ProviderError> { failedController.confirmContactVerification("123456") }
        assertThat(failed.refreshSessionCallCount).isEqualTo(0)
    }

    @Test
    fun `logout drops a pending verification`() = runTest {
        val turnkey = MockTurnkey()
        val rainApi = rainApi()
        val controller = controller(turnkey, rainApi)
        controller.sendContactVerificationCode(email)

        controller.logout()
        turnkey.authenticate()
        turnkey.selectedSessionKey = MockTurnkey.DEFAULT_SESSION_KEY

        expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("123456") }
        assertThat(rainApi.verifyOtpCalls).isEmpty()
    }

    @Test
    fun `a passkey login and a confirmed login code drop a pending verification`() = runTest {
        val passkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        val passkeyRain = rainApi()
        val passkeyController = controller(passkey, passkeyRain)
        passkeyController.sendContactVerificationCode(email)

        passkeyController.loginWithPasskey(activity)

        expectThrows<RainError.InvalidConfig> { passkeyController.confirmContactVerification("123456") }
        assertThat(passkeyRain.verifyOtpCalls).isEmpty()

        val code = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        val codeRain = rainApi()
        codeRain.login = { MockRainApiAuth.defaultLogin() }
        val codeController = controller(code, codeRain)
        codeController.sendContactVerificationCode(email)
        codeController.sendLoginCode(LoginContact.Email("other@example.com"))

        codeController.confirmLoginCode("123456")

        expectThrows<RainError.InvalidConfig> { codeController.confirmContactVerification("123456") }
        // The one verify was the login's.
        assertThat(codeRain.verifyOtpCalls).hasSize(1)
    }

    @Test
    fun `a blank code is InvalidConfig and keeps the challenge, and a closed controller refuses both calls`() = runTest {
        val turnkey = MockTurnkey()
        val rainApi = rainApi()
        val controller = controller(turnkey, rainApi)
        controller.sendContactVerificationCode(email)

        expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("   ") }
        assertThat(rainApi.verifyOtpCalls).isEmpty()
        assertThat(turnkey.createdDeviceKeys).isEmpty()
        controller.confirmContactVerification("123456")
        assertThat(turnkey.setUserEmailCalls).hasSize(1)

        val closed = controller(MockTurnkey())
        closed.close()
        expectThrows<RainError.InvalidConfig> { closed.sendContactVerificationCode(email) }
        expectThrows<RainError.InvalidConfig> { closed.confirmContactVerification("123456") }
    }

    @Test
    fun `confirming without a live session is TokenExpired and spends no code`() = runTest {
        val turnkey = MockTurnkey()
        val rainApi = rainApi()
        val controller = controller(turnkey, rainApi)
        controller.sendContactVerificationCode(email)
        // The session died between the send and the confirm.
        turnkey.session = null
        turnkey.selectedSessionKey = null

        expectThrows<RainError.TokenExpired> { controller.confirmContactVerification("123456") }

        assertThat(rainApi.verifyOtpCalls).isEmpty()
        assertThat(turnkey.createdDeviceKeys).isEmpty()
        assertThat(turnkey.setUserEmailCalls).isEmpty()
    }
}
