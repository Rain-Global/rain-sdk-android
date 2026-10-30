package com.rain.sdk.turnkey

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import com.turnkey.core.internal.utils.ClientSignature
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * The two-code login on [TurnkeyManagedAuthController]: an account Rain protects that way answers
 * the first verify with a call for a second code instead of a token; the device keeps the key the
 * first code was encrypted to, sends and confirms the second code under the same wallet auth token
 * with the signature over Rain's login message, and Rain answers the session, issued to that key,
 * with `/login` never called. Each failure exit asserts the four reads a host makes right after the
 * throw ([assertLocalState]), as [TurnkeyManagedCodeOutcomeTest] does for the one-code login; same
 * fixtures, same JDK 24 gate, same reset of the process-global configurator around every test.
 */
class TurnkeyManagedTwoCodeTest {

    private var hookCalls = 0
    private val activity: Activity = mockk(relaxed = true)
    private val tokenRequests = mutableListOf<LoginContact?>()

    @Before
    fun setUp() {
        assumeJdk24()
        TurnkeyManagedConfigurator.resetForTest()
    }

    @After
    fun tearDown() = TurnkeyManagedConfigurator.resetForTest()

    private fun controller(turnkey: MockTurnkey, rainApi: MockRainApiAuth) = TurnkeyManagedAuthController(
        context = turnkey,
        rainApi = rainApi,
        authTokenProvider = { contact ->
            tokenRequests += contact
            "wat_${tokenRequests.size}"
        },
        coordinator = TurnkeySessionCoordinator(turnkey = turnkey, onSessionExpired = { hookCalls++ }, retryDelay = { }),
        configure = { null },
        passkeyDomain = "passkeys.example.com",
    )

    private val email = LoginContact.Email("user@example.com")
    private val phone = LoginContact.Phone("+19999999999")
    private val maskedPhone = TurnkeyMaskedContact(LoginContactType.PHONE, "+•••• 4567")
    private val maskedEmail = TurnkeyMaskedContact(LoginContactType.EMAIL, "j•••@example.com")

    /** The vendor, signed out and ready to store, with the Ethereum account only, so the login's backfill shows. */
    private fun vendor(): MockTurnkey = MockTurnkey(wallets = listOf(MockTurnkey.defaultWallet()), session = null).apply {
        onStoreSession = { authenticate() }
    }

    /**
     * Rain for an account that needs two codes: each code request answers a new challenge, a verify
     * without a signature asks for the second code on [second], naming the key the code was
     * encrypted to, and a verify with one answers the session.
     */
    private fun rain(second: OtpChannel = OtpChannel.SMS): MockRainApiAuth = MockRainApiAuth().apply {
        var sends = 0
        initOtp = {
            sends++
            RainApiOtpChallenge("otp-$sends", "b$sends")
        }
        verifyOtp = { call -> if (call.clientSignature == null) secondCode(call, second) else MockRainApiAuth.defaultLogin() }
    }

    private fun secondCode(call: MockRainApiAuth.VerifyOtpCall, second: OtpChannel = OtpChannel.SMS) =
        MockRainApiAuth.secondCodeRequired(call.deviceKey, second)

    private fun assertLocalState(
        controller: TurnkeyManagedAuthController,
        turnkey: MockTurnkey,
        selectedKey: String?,
        active: Boolean,
        auth: TurnkeyAuthState,
    ) {
        assertThat(turnkey.selectedSessionKey).isEqualTo(selectedKey)
        assertThat(controller.hasActiveSession()).isEqualTo(active)
        assertThat(controller.currentAuthState()).isEqualTo(auth)
        assertThat(hookCalls).isEqualTo(0)
    }

    private fun signedOut(controller: TurnkeyManagedAuthController, turnkey: MockTurnkey) =
        assertLocalState(controller, turnkey, selectedKey = null, active = false, auth = TurnkeyAuthState.Unauthenticated)

    /** Nothing waits for a second code: neither second-code call has an attempt to work on. */
    private suspend fun assertNoAttempt(controller: TurnkeyManagedAuthController) {
        expectThrows<RainError.InvalidConfig> { controller.sendSecondLoginCode() }
        expectThrows<RainError.InvalidConfig> { controller.confirmSecondLoginCode("222222") }
    }

    /** A refusal on a second-code leg: the error it leaves as, and whether that error's message asks for a new login code. */
    private data class Ending(
        val name: String,
        val expected: Class<out RainError>,
        val asksForNewCode: Boolean,
        val arrange: (MockRainApiAuth) -> Unit,
    )

    private fun unavailable(path: String) = MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 503, path)

    /** The vendor's exception chain for a passkey sheet the user dismissed, as [TurnkeyManagedPasskeyTest] builds it. */
    private fun dismissedSheet(): Exception = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginWithPasskey(
        com.turnkey.stamper.utils.TurnkeyStamperError.AssertionFailed(
            com.turnkey.passkey.utils.TurnkeyPasskeyError.AssertionFailed(
                androidx.credentials.exceptions.GetCredentialCancellationException("dismissed")
            )
        )
    )

    @Test
    fun `an account that needs two codes signs in under one token and one key, email first or phone first`() = runTest {
        val orderings = listOf(
            Triple(email, OtpChannel.SMS, TurnkeyLoginOutcome.SecondCodeRequired(maskedPhone)),
            Triple(phone, OtpChannel.EMAIL, TurnkeyLoginOutcome.SecondCodeRequired(maskedEmail)),
        )
        orderings.forEach { (first, second, expected) ->
            tokenRequests.clear()
            val turnkey = vendor()
            val rainApi = rain(second)
            val controller = controller(turnkey, rainApi)
            controller.sendLoginCode(first)

            val outcome = controller.confirmLoginCode("111111")

            // The first code is spent, the key is kept for the attempt, nothing is logged in and no session was touched.
            assertWithMessage(first.toString()).that(outcome).isEqualTo(expected)
            assertThat(rainApi.verifyOtpCalls.single().clientSignature).isNull()
            assertThat(rainApi.loginCalls).isEmpty()
            assertThat(turnkey.liveDeviceKeys).containsExactly("device-key-1")
            assertThat(turnkey.storeSessionCalls).isEmpty()
            signedOut(controller, turnkey)
            expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("111111") }

            controller.sendSecondLoginCode()

            // The same token carries the second code: the host is not asked again, and the device names no contact.
            assertThat(rainApi.initOtpCalls).containsExactly("wat_1", "wat_1")
            assertThat(tokenRequests).containsExactly(first)

            controller.confirmSecondLoginCode("222222")

            val secondVerify = rainApi.verifyOtpCalls.last()
            assertThat(secondVerify.token).isEqualTo("wat_1")
            assertThat(secondVerify.otpId).isEqualTo("otp-2")
            assertThat(secondVerify.encryptedOtpBundle).isEqualTo("encrypted(222222,b2,device-key-1)")
            val signature = checkNotNull(secondVerify.clientSignature)
            assertThat(signature.publicKey).isEqualTo("device-key-1")
            assertThat(signature.message).isEqualTo(MockRainApiAuth.loginMessage("device-key-1"))
            assertThat(turnkey.clientSignatureCalls).containsExactly(MockTurnkey.ClientSignatureCall(signature.message, "device-key-1"))
            assertThat(rainApi.loginCalls).isEmpty()
            // The session Rain answered is stored under a fresh key, bound to the device key, selected and backfilled.
            assertThat(turnkey.storeSessionCalls.single().jwt).isEqualTo(MockRainApiAuth.DEFAULT_SESSION_JWT)
            assertThat(turnkey.createdDeviceKeys).containsExactly("device-key-1")
            assertThat(turnkey.liveDeviceKeys).containsExactly("device-key-1")
            assertThat(turnkey.createWalletAccountsCalls).hasSize(1)
            assertLocalState(
                controller,
                turnkey,
                selectedKey = turnkey.storeSessionCalls.single().sessionKey,
                active = true,
                auth = TurnkeyAuthState.Authenticated,
            )
            assertNoAttempt(controller)
        }
    }

    @Test
    fun `a wrong code at either step keeps the attempt, and the second step keeps its key too`() = runTest {
        val turnkey = vendor()
        val rainApi = rain()
        rainApi.verifyOtp = MockRainApiAuth.answers(
            { throw MockRainApiAuth.wrongCode() },
            { call -> secondCode(call) },
            { throw MockRainApiAuth.wrongCode() },
            { MockRainApiAuth.defaultLogin() },
        )
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(email)

        expectThrows<RainError.InvalidLoginCode> { controller.confirmLoginCode("000000") }

        // The first step keeps the challenge and, as before, makes a fresh key per confirm.
        assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
        signedOut(controller, turnkey)
        assertThat(controller.confirmLoginCode("111111")).isInstanceOf(TurnkeyLoginOutcome.SecondCodeRequired::class.java)
        assertThat(rainApi.initOtpCalls).hasSize(1)
        assertThat(turnkey.liveDeviceKeys).containsExactly("device-key-2")
        controller.sendSecondLoginCode()

        val refused = expectThrows<RainError.InvalidLoginCode> { controller.confirmSecondLoginCode("000000") }

        // The second step keeps the attempt, its code and its key: the retype goes out under the same otpId and key.
        assertThat(refused.errorCode).isEqualTo(RainErrorCode.INVALID_LOGIN_CODE)
        assertThat(turnkey.liveDeviceKeys).containsExactly("device-key-2")
        signedOut(controller, turnkey)
        controller.confirmSecondLoginCode("222222")
        assertThat(rainApi.initOtpCalls).hasSize(2)
        assertThat(rainApi.verifyOtpCalls.takeLast(2).map { it.otpId }).containsExactly("otp-2", "otp-2")
        assertThat(rainApi.verifyOtpCalls.takeLast(2).map { it.clientSignature?.publicKey }).containsExactly("device-key-2", "device-key-2")
        assertThat(turnkey.createdDeviceKeys).containsExactly("device-key-1", "device-key-2").inOrder()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `every refusal but a wrong code ends the attempt, deletes the key and renews no token`() = runTest {
        val rows = listOf(
            Ending("a 400 'start again' on the verify", RainError.ProviderError::class.java, asksForNewCode = true) { api ->
                api.verifyOtp = MockRainApiAuth.answers({ call -> secondCode(call) }, { throw MockRainApiAuth.attemptEnded() })
            },
            Ending("a 400 'start again' on the send", RainError.ProviderError::class.java, asksForNewCode = true) { api ->
                api.initOtp = MockRainApiAuth.answers(
                    { RainApiOtpChallenge("otp-1", "b1") },
                    { throw MockRainApiAuth.attemptEnded(MockRainApiAuth.INIT_PATH) },
                    { RainApiOtpChallenge("otp-3", "b3") },
                )
            },
            Ending("a 401 on the verify", RainError.Unauthorized::class.java, asksForNewCode = false) { api ->
                api.verifyOtp = MockRainApiAuth.answers(
                    { call -> secondCode(call) },
                    { throw MockRainApiAuth.tokenRejected(MockRainApiAuth.VERIFY_PATH) },
                )
            },
            Ending("a 401 on the send", RainError.Unauthorized::class.java, asksForNewCode = false) { api ->
                api.initOtp = MockRainApiAuth.answers(
                    { RainApiOtpChallenge("otp-1", "b1") },
                    { throw MockRainApiAuth.tokenRejected(MockRainApiAuth.INIT_PATH) },
                    { RainApiOtpChallenge("otp-3", "b3") },
                )
            },
            Ending("a 503 on the verify", RainError.ProviderError::class.java, asksForNewCode = false) { api ->
                api.verifyOtp = MockRainApiAuth.answers({ call -> secondCode(call) }, { throw unavailable(MockRainApiAuth.VERIFY_PATH) })
            },
            Ending("a 503 on the send", RainError.ProviderError::class.java, asksForNewCode = false) { api ->
                api.initOtp = MockRainApiAuth.answers(
                    { RainApiOtpChallenge("otp-1", "b1") },
                    { throw unavailable(MockRainApiAuth.INIT_PATH) },
                    { RainApiOtpChallenge("otp-3", "b3") },
                )
            },
        )
        rows.forEach { row ->
            tokenRequests.clear()
            val turnkey = vendor()
            val rainApi = rain()
            row.arrange(rainApi)
            val controller = controller(turnkey, rainApi)
            controller.sendLoginCode(email)
            controller.confirmLoginCode("111111")

            val thrown = expectThrows<RainError> {
                controller.sendSecondLoginCode()
                controller.confirmSecondLoginCode("222222")
            }

            assertWithMessage(row.name).that(thrown).isInstanceOf(row.expected)
            if (row.asksForNewCode) assertWithMessage(row.name).that(thrown).hasMessageThat().contains("request a new login code")
            assertWithMessage(row.name).that(thrown).hasMessageThat().doesNotContain("example.com")
            assertWithMessage(row.name).that(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
            assertWithMessage(row.name).that(tokenRequests).hasSize(1)
            assertWithMessage(row.name).that(turnkey.storeSessionCalls).isEmpty()
            signedOut(controller, turnkey)
            assertNoAttempt(controller)
            // A new first code starts over, under a new token.
            controller.sendLoginCode(email)
            assertWithMessage(row.name).that(tokenRequests).hasSize(2)
        }
    }

    @Test
    fun `a transport failure keeps the attempt and the key, so the same code and key retry`() = runTest {
        val turnkey = vendor()
        val rainApi = rain()
        val lost = RainError.NetworkError("Rain API ${MockRainApiAuth.VERIFY_PATH}: SocketTimeoutException")
        rainApi.verifyOtp = MockRainApiAuth.answers({ call -> secondCode(call) }, { throw lost }, { MockRainApiAuth.defaultLogin() })
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(email)
        controller.confirmLoginCode("111111")
        controller.sendSecondLoginCode()

        expectThrows<RainError.NetworkError> { controller.confirmSecondLoginCode("222222") }

        assertThat(turnkey.liveDeviceKeys).containsExactly("device-key-1")
        signedOut(controller, turnkey)

        controller.confirmSecondLoginCode("222222")

        assertThat(rainApi.verifyOtpCalls.takeLast(2).map { it.otpId }.toSet()).containsExactly("otp-2")
        assertThat(rainApi.initOtpCalls).hasSize(2)
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `a second code must be sent before it is confirmed`() = runTest {
        val turnkey = vendor()
        val rainApi = rain()
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(email)
        controller.confirmLoginCode("111111")

        val refused = expectThrows<RainError.InvalidConfig> { controller.confirmSecondLoginCode("222222") }

        assertThat(refused).hasMessageThat().contains("call sendSecondLoginCode first")
        assertThat(rainApi.verifyOtpCalls).hasSize(1)
        assertThat(turnkey.liveDeviceKeys).containsExactly("device-key-1")
        signedOut(controller, turnkey)
    }

    @Test
    fun `a resend replaces the second code, so the confirm carries the newest otpId`() = runTest {
        val turnkey = vendor()
        val rainApi = rain()
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(email)
        controller.confirmLoginCode("111111")
        controller.sendSecondLoginCode()
        controller.sendSecondLoginCode()

        controller.confirmSecondLoginCode("222222")

        assertThat(rainApi.initOtpCalls).containsExactly("wat_1", "wat_1", "wat_1")
        assertThat(rainApi.verifyOtpCalls.last().otpId).isEqualTo("otp-3")
        assertThat(rainApi.verifyOtpCalls.last().encryptedOtpBundle).isEqualTo("encrypted(222222,b3,device-key-1)")
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `an unreadable answer to the second verify drops the attempt and deletes the key, since Rain issued the session`() = runTest {
        val turnkey = vendor()
        val rainApi = rain()
        rainApi.verifyOtp = MockRainApiAuth.answers(
            { call -> secondCode(call) },
            { throw RainError.InternalError("Rain API ${MockRainApiAuth.VERIFY_PATH}: unreadable answer (JSONException)") },
        )
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(email)
        controller.confirmLoginCode("111111")
        controller.sendSecondLoginCode()

        expectThrows<RainError.InternalError> { controller.confirmSecondLoginCode("222222") }

        assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
        assertThat(turnkey.storeSessionCalls).isEmpty()
        signedOut(controller, turnkey)
        assertNoAttempt(controller)
    }

    @Test
    fun `a new sendLoginCode, logout, close and a passkey call abandon the attempt and delete its key`() = runTest {
        val rows = listOf<Triple<String, MockTurnkey.() -> Unit, suspend (TurnkeyManagedAuthController) -> Unit>>(
            Triple("a new sendLoginCode", {}, { it.sendLoginCode(email) }),
            Triple("logout", {}, { it.logout() }),
            Triple("close", {}, { it.close() }),
            Triple("a passkey login", { onPasskeyLogin = { authenticate() } }, { it.loginWithPasskey(activity) }),
            Triple(
                "a dismissed passkey sheet",
                { passkeyLoginError = dismissedSheet() },
                { expectThrows<RainError.UserRejected> { it.loginWithPasskey(activity) } },
            ),
            Triple("a passkey sign-up", {}, { it.signUpWithPasskey(activity) }),
        )
        rows.forEach { (name, vendorSetup, abandon) ->
            val turnkey = vendor().apply(vendorSetup)
            val rainApi = rain()
            val controller = controller(turnkey, rainApi)
            controller.sendLoginCode(email)
            controller.confirmLoginCode("111111")
            controller.sendSecondLoginCode()

            abandon(controller)

            // The attempt's key goes once, by the abandon, before a passkey ceremony's sweep could find it.
            assertWithMessage(name).that(turnkey.deletedDeviceKeys.count { it == "device-key-1" }).isEqualTo(1)
            assertWithMessage(name).that(turnkey.liveDeviceKeys).doesNotContain("device-key-1")
            assertWithMessage(name).that(hookCalls).isEqualTo(0)
            assertNoAttempt(controller)
        }

        // The new first code starts the login over, under a fresh key.
        val turnkey = vendor()
        val rainApi = rain()
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(email)
        controller.confirmLoginCode("111111")
        controller.sendLoginCode(email)
        assertThat(controller.confirmLoginCode("111111")).isInstanceOf(TurnkeyLoginOutcome.SecondCodeRequired::class.java)
        assertThat(turnkey.liveDeviceKeys).containsExactly("device-key-2")
        assertThat(rainApi.verifyOtpCalls.last().deviceKey).isEqualTo("device-key-2")
    }

    @Test
    fun `a first verify answered start again spends the code, deletes the key and asks for a new one`() = runTest {
        val turnkey = vendor()
        val rainApi = rain()
        rainApi.verifyOtp = { throw MockRainApiAuth.attemptEnded() }
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(email)

        val thrown = expectThrows<RainError.ProviderError> { controller.confirmLoginCode("111111") }

        assertThat(thrown.errorCode).isEqualTo(RainErrorCode.PROVIDER_ERROR)
        assertThat(thrown).hasMessageThat().contains("request a new login code")
        assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
        signedOut(controller, turnkey)
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("111111") }
        assertThat(rainApi.verifyOtpCalls).hasSize(1)
        assertNoAttempt(controller)
    }

    @Test
    fun `the message Rain wants signed must be the vendor's login usage naming the device key, or nothing is kept`() = runTest {
        val vendorsOwn = ClientSignature.forLogin(verificationToken(publicKey = "device-key-1")).message
        val rows = listOf(
            "the vendor's own login message" to vendorsOwn,
            "a login naming another key" to MockRainApiAuth.loginMessage("device-key-9"),
            "a sign-up usage" to """{"signupV2":{"email":"user@example.com"},"tokenId":"tok-1","type":"USAGE_TYPE_SIGNUP"}""",
            "not JSON" to "sign this",
        )
        rows.forEach { (name, message) ->
            val turnkey = vendor()
            val rainApi = rain()
            rainApi.verifyOtp = { RainApiSecondCodeRequired(OtpChannel.SMS, "+•••• 4567", message) }
            val controller = controller(turnkey, rainApi)
            controller.sendLoginCode(email)

            if (message == vendorsOwn) {
                assertWithMessage(name).that(controller.confirmLoginCode("111111"))
                    .isEqualTo(TurnkeyLoginOutcome.SecondCodeRequired(maskedPhone))
                assertWithMessage(name).that(turnkey.liveDeviceKeys).containsExactly("device-key-1")
                return@forEach
            }
            val thrown = expectThrows<RainError.InternalError> { controller.confirmLoginCode("111111") }

            assertWithMessage(name).that(thrown).hasMessageThat().contains("request a new login code")
            assertWithMessage(name).that(thrown).hasMessageThat().doesNotContain("example.com")
            assertWithMessage(name).that(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
            assertWithMessage(name).that(turnkey.clientSignatureCalls).isEmpty()
            signedOut(controller, turnkey)
            assertNoAttempt(controller)
        }
    }

    @Test
    fun `an account that needs one code signs in as before and leaves nothing for a second code`() = runTest {
        val turnkey = vendor()
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(email)

        assertThat(controller.confirmLoginCode("111111")).isEqualTo(TurnkeyLoginOutcome.SignedIn)

        assertThat(rainApi.verifyOtpCalls.single().clientSignature).isNull()
        assertThat(rainApi.loginCalls).hasSize(1)
        assertThat(turnkey.clientSignatureCalls).isEmpty()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        assertNoAttempt(controller)
    }

    /** A verification token in the shape Rain's verify answers, as [ClientSignatureMessageTest] builds it; nobody checks its signature. */
    private fun verificationToken(publicKey: String): String {
        val payload = JSONObject()
            .put("contact", "user@example.com")
            .put("exp", 1_700_000_600)
            .put("id", "tok-1")
            .put("public_key", publicKey)
            .put("verification_type", "VERIFICATION_TYPE_EMAIL")
            .put("organization_id", "org-1")
        val b64 = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"none"}""", payload.toString(), "sig").joinToString(".") { b64.encodeToString(it.toByteArray()) }
    }
}
