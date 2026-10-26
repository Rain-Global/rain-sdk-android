package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The SMS channel of [TurnkeyManagedAuthController], against [MockTurnkey] and [MockRainApiAuth].
 * The email channel and everything channel-neutral (sessions, provisioning, logout, cancellation)
 * is in [TurnkeyManagedAuthTest]; this class covers what differs for a phone number: the contact
 * the host's token provider is asked for, the channel the sign-up names, canonicalization, and
 * the pre-vendor checks. The verify step is channel-blind, so its outcomes live in
 * [TurnkeyManagedCodeOutcomeTest] only. Gated on JDK 24 like every Turnkey suite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnkeyManagedAuthSmsTest {

    private val tokenRequests = mutableListOf<LoginContact?>()

    @Before
    fun setUp() {
        assumeJdk24()
        TurnkeyManagedConfigurator.resetForTest()
    }

    @After
    fun tearDown() = TurnkeyManagedConfigurator.resetForTest()

    private fun coordinator(turnkey: MockTurnkey) = TurnkeySessionCoordinator(
        turnkey = turnkey,
        onSessionExpired = { },
        retryDelay = { },
    )

    private fun controller(
        turnkey: MockTurnkey,
        rainApi: MockRainApiAuth = MockRainApiAuth(),
        coordinator: TurnkeySessionCoordinator = coordinator(turnkey),
    ) = TurnkeyManagedAuthController(
        context = turnkey,
        rainApi = rainApi,
        authTokenProvider = { contact ->
            tokenRequests += contact
            "wat_1"
        },
        coordinator = coordinator,
        configure = { null },
    )

    /** Turnkey's documented sandbox number, never a real person's. */
    private val smsContact = LoginContact.Phone("+19999999999")
    private val canonical = LoginContact.Phone("+19999999999")

    @Test
    fun `an SMS sign-up binds the token to the E164 number, canonicalized once, and names the account by it on the sign-up signature`() =
        runTest {
            val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
            turnkey.onStoreSession = { turnkey.authenticate() }
            val rainApi = MockRainApiAuth()
            rainApi.login = MockRainApiAuth.answers({ throw MockRainApiAuth.noWallet() }, { MockRainApiAuth.defaultLogin() })
            val controller = controller(turnkey, rainApi)

            controller.sendLoginCode(LoginContact.Phone("  +1 (999) 999-9999 "))

            // The send: one token for the canonical number, the code requested under it, no session touched.
            assertThat(tokenRequests).containsExactly(canonical)
            assertThat(rainApi.initOtpCalls).containsExactly("wat_1")
            assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
            assertThat(turnkey.clearSessionCalls).isEmpty()

            controller.confirmLoginCode("123456")

            // The sign-up signature names the very same string, as the root user's phone number
            // (ClientSignatureMessageTest pins the real message); nothing else sent carries the number.
            val signUp = turnkey.signUpSignatureCalls.single()
            assertThat(signUp.channel).isEqualTo(OtpChannel.SMS)
            assertThat(signUp.contact).isEqualTo("+19999999999")
            val signUpMessage = rainApi.signupCalls.single().clientSignature.message
            (rainApi.everythingSent - signUpMessage).forEach { assertThat(it).doesNotContain("9999") }
            assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        }

    @Test
    fun `a malformed phone number is a caller error before the host or Rain is touched`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi)
        // No plus sign, a country code starting with 0, 16 digits, letters, a 00 prefix, a trunk zero.
        val malformed = listOf(
            "999-999-9999",
            "+0999999999",
            "+1234567890123456",
            "+1999abc9999",
            "0019999999999",
            "+44 (0) 20 9999 9999",
        )

        malformed.forEach { number ->
            val thrown = expectThrows<RainError.InvalidConfig> { controller.sendLoginCode(LoginContact.Phone(number)) }
            assertThat(thrown).hasMessageThat().contains("E.164")
            assertThat(thrown).hasMessageThat().doesNotContain(number)
        }

        assertThat(tokenRequests).isEmpty()
        assertThat(rainApi.initOtpCalls).isEmpty()
        // Validation runs after the readiness check, in the same order as the blank-email check.
        assertThat(turnkey.awaitReadyCallCount).isEqualTo(malformed.size)
    }

    @Test
    fun `a blank phone number is a caller error, not a rejected code`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi)

        val thrown = expectThrows<RainError.InvalidConfig> { controller.sendLoginCode(LoginContact.Phone("   ")) }

        assertThat(thrown).hasMessageThat().contains("blank")
        assertThat(rainApi.initOtpCalls).isEmpty()
    }

    @Test
    fun `a blank email through the typed contact is still a caller error`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi)

        expectThrows<RainError.InvalidConfig> { controller.sendLoginCode(LoginContact.Email("   ")) }

        assertThat(rainApi.initOtpCalls).isEmpty()
    }

    @Test
    fun `a second sendLoginCode on the other channel replaces the pending challenge`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onStoreSession = { turnkey.authenticate() }
        val rainApi = MockRainApiAuth()
        rainApi.initOtp = MockRainApiAuth.answers(
            { RainApiOtpChallenge("otp-1", "bundle-1") },
            { RainApiOtpChallenge("otp-2", "bundle-2") },
        )
        rainApi.login = MockRainApiAuth.answers({ throw MockRainApiAuth.noWallet() }, { MockRainApiAuth.defaultLogin() })
        val controller = controller(turnkey, rainApi)

        controller.sendLoginCode(LoginContact.Email("user@example.com"))
        controller.sendLoginCode(smsContact)
        controller.confirmLoginCode("123456")

        assertThat(rainApi.verifyOtpCalls.single().otpId).isEqualTo("otp-2")
        assertThat(turnkey.encryptOtpCodeCalls.single().encryptionTargetBundle).isEqualTo("bundle-2")
        assertThat(turnkey.signUpSignatureCalls.single().channel).isEqualTo(OtpChannel.SMS)
        assertThat(turnkey.signUpSignatureCalls.single().contact).isEqualTo("+19999999999")
    }

    @Test
    fun `a failed send for another contact retires the pending challenge`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val rainApi = MockRainApiAuth()
        rainApi.initOtp = MockRainApiAuth.answers(
            { RainApiOtpChallenge("otp-1", "bundle-1") },
            { throw MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 400, MockRainApiAuth.INIT_PATH) },
        )
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(LoginContact.Email("user@example.com"))

        expectThrows<RainError> { controller.sendLoginCode(smsContact) }

        // The email challenge must not be confirmable under the belief that an SMS login is happening.
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        assertThat(rainApi.verifyOtpCalls).isEmpty()
    }

    @Test
    fun `a failed resend for the same number keeps the pending challenge`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onStoreSession = { turnkey.authenticate() }
        val rainApi = MockRainApiAuth()
        rainApi.initOtp = MockRainApiAuth.answers(
            { RainApiOtpChallenge("otp-1", "bundle-1") },
            { throw MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 503, MockRainApiAuth.INIT_PATH) },
        )
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(smsContact)

        expectThrows<RainError> { controller.sendLoginCode(LoginContact.Phone("+1 (999) 999-9999")) }

        // The first code is still typable.
        controller.confirmLoginCode("123456")
        assertThat(rainApi.verifyOtpCalls.single().otpId).isEqualTo("otp-1")
    }

    @Test
    fun `a malformed contact leaves the pending challenge untouched`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onStoreSession = { turnkey.authenticate() }
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(smsContact)

        expectThrows<RainError.InvalidConfig> { controller.sendLoginCode(LoginContact.Phone("999-999-9999")) }

        controller.confirmLoginCode("123456")
        assertThat(rainApi.verifyOtpCalls).hasSize(1)
        assertThat(tokenRequests).containsExactly(canonical)
    }

    @Test
    fun `logout drops a pending SMS code`() = runTest {
        val turnkey = MockTurnkey()
        val coordinator = coordinator(turnkey)
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi, coordinator = coordinator)
        coordinator.startMonitoring(backgroundScope)
        runCurrent()
        controller.sendLoginCode(smsContact)

        controller.logout()
        runCurrent()

        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(1)
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        assertThat(rainApi.verifyOtpCalls).isEmpty()
    }
}
