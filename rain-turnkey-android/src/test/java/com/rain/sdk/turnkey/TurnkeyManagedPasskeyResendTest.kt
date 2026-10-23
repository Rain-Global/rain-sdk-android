package com.rain.sdk.turnkey

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * The passkey sign-up's kept registration in [TurnkeyManagedAuthController]: a sign-up whose answer
 * Rain left in doubt keeps the passkey it created, and the next call resends it instead of opening
 * the sheet, so Rain records the account the first call created rather than joining a second one
 * to it. Which answers keep the registration, which settle it and what each resend answers are
 * pinned here; the rest of the passkey flows are in [TurnkeyManagedPasskeyTest], whose controller
 * seam this copies.
 */
class TurnkeyManagedPasskeyResendTest {

    private var hookCalls = 0
    private val activity: Activity = mockk(relaxed = true)
    private val tokenRequests = mutableListOf<LoginContact?>()

    @Before
    fun setUp() = assumeJdk24()

    private fun controller(
        turnkey: MockTurnkey,
        rainApi: MockRainApiAuth = MockRainApiAuth(),
        authTokenProvider: suspend (LoginContact?) -> String = { "wat_1" },
    ) = TurnkeyManagedAuthController(
        context = turnkey,
        rainApi = rainApi,
        authTokenProvider = { contact ->
            tokenRequests += contact
            authTokenProvider(contact)
        },
        coordinator = TurnkeySessionCoordinator(turnkey = turnkey, onSessionExpired = { hookCalls++ }, retryDelay = { }),
        configure = { null },
        passkeyDomain = "passkeys.example.com",
        nowEpochSeconds = { 1_700_000_000.5 },
    )

    /** The client's transport failure on the sign-up leg, as [RainApiAuthClient] throws it. */
    private fun lostAnswer() = RainError.NetworkError(
        "Rain API /v1/issuing/embedded-wallets/signup/passkey: SocketTimeoutException",
        java.net.SocketTimeoutException(),
    )

    private fun inProgress() = MockRainApiAuth.refusal(RainApiAuthFailure.Kind.SignupInProgress, 409, MockRainApiAuth.SIGNUP_PASSKEY_PATH)

    private fun passkeyTaken() = MockRainApiAuth.refusal(RainApiAuthFailure.Kind.PasskeyTaken, 409, MockRainApiAuth.SIGNUP_PASSKEY_PATH)

    @Test
    fun `a sign-up Rain never answered keeps the passkey, and the next call resends it with a fresh token and no sheet`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onPasskeyLogin = { turnkey.authenticate() }
        val rainApi = MockRainApiAuth()
        var answers = 0
        rainApi.signupPasskey = { if (answers++ == 0) throw lostAnswer() else RainApiSignup("sub-passkey") }
        var minted = 0
        val controller = controller(turnkey, rainApi, authTokenProvider = { "wat_${++minted}" })

        expectThrows<RainError.NetworkError> { controller.signUpWithPasskey(activity) }

        assertThat(turnkey.passkeyLoginCalls).isEmpty()
        assertThat(turnkey.selectedSessionKey).isNull()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)

        controller.signUpWithPasskey(activity)

        // One sheet across both calls; the resend carries the first ceremony's challenge and
        // attestation under a token minted for this call.
        assertThat(turnkey.createPasskeyCalls).hasSize(1)
        assertThat(tokenRequests).containsExactly(null, null)
        val (first, resent) = rainApi.signupPasskeyCalls
        assertThat(resent.challenge).isEqualTo(first.challenge)
        assertThat(resent.attestation).isEqualTo(first.attestation)
        assertThat(rainApi.signupPasskeyCalls.map { it.token }).containsExactly("wat_1", "wat_2").inOrder()
        assertThat(turnkey.passkeyLoginCalls.single().organizationId).isEqualTo("sub-passkey")
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `a 5xx and an unreadable answer from Rain's sign-up keep the passkey like a lost one`() = runTest {
        val inDoubt = listOf<Pair<String, Throwable>>(
            "a 503" to MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 503, MockRainApiAuth.SIGNUP_PASSKEY_PATH),
            "an unreadable 200" to RainError.InternalError("Rain API /v1/issuing/embedded-wallets/signup/passkey: unreadable answer"),
        )
        inDoubt.forEach { (name, failure) ->
            val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
            turnkey.onPasskeyLogin = { turnkey.authenticate() }
            val rainApi = MockRainApiAuth()
            var answers = 0
            rainApi.signupPasskey = { if (answers++ == 0) throw failure else RainApiSignup("sub-passkey") }
            val controller = controller(turnkey, rainApi)

            expectThrows<RainError> { controller.signUpWithPasskey(activity) }
            controller.signUpWithPasskey(activity)

            assertWithMessage(name).that(turnkey.createPasskeyCalls).hasSize(1)
            assertWithMessage(name).that(rainApi.signupPasskeyCalls).hasSize(2)
            assertWithMessage(name).that(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        }
    }

    @Test
    fun `Rain still creating the account on a resend keeps the passkey and arrives as RAIN_301`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onPasskeyLogin = { turnkey.authenticate() }
        val rainApi = MockRainApiAuth()
        val answers = ArrayDeque<() -> RainApiSignup>(
            listOf({ throw lostAnswer() }, { throw inProgress() }, { RainApiSignup("sub-passkey") }),
        )
        rainApi.signupPasskey = { answers.removeFirst()() }
        val controller = controller(turnkey, rainApi)

        expectThrows<RainError.NetworkError> { controller.signUpWithPasskey(activity) }
        val stillCreating = expectThrows<RainError.NetworkError> { controller.signUpWithPasskey(activity) }
        controller.signUpWithPasskey(activity)

        assertThat(stillCreating).hasMessageThat().contains("still creating")
        assertThat(stillCreating).hasCauseThat().isInstanceOf(RainError.ProviderError::class.java)
        assertThat(turnkey.createPasskeyCalls).hasSize(1)
        assertThat(rainApi.signupPasskeyCalls).hasSize(3)
        assertThat(turnkey.passkeyLoginCalls.single().organizationId).isEqualTo("sub-passkey")
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `Rain answering that the passkey already has a wallet drops it and sends the host to loginWithPasskey`() = runTest {
        // On a resend: the first call's account was saved. On a first send: impossible for a fresh
        // credential, but Rain's word is final either way.
        listOf("on a resend" to true, "on a first send" to false).forEach { (name, resend) ->
            val turnkey = MockTurnkey(session = null)
            val rainApi = MockRainApiAuth()
            val answers = ArrayDeque<() -> RainApiSignup>()
            if (resend) answers += { throw lostAnswer() }
            answers += { throw passkeyTaken() }
            answers += { throw lostAnswer() }
            rainApi.signupPasskey = { answers.removeFirst()() }
            val controller = controller(turnkey, rainApi)

            if (resend) expectThrows<RainError.NetworkError> { controller.signUpWithPasskey(activity) }
            val taken = expectThrows<RainError.InvalidConfig> { controller.signUpWithPasskey(activity) }
            expectThrows<RainError.NetworkError> { controller.signUpWithPasskey(activity) }

            assertWithMessage(name).that(taken).hasMessageThat().contains("loginWithPasskey")
            assertWithMessage(name).that(turnkey.passkeyLoginCalls).isEmpty()
            // The last call opened the sheet again: Rain's answer dropped the registration.
            assertWithMessage(name).that(turnkey.createPasskeyCalls).hasSize(2)
            assertWithMessage(name).that(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        }
    }

    @Test
    fun `a refusal before Rain created anything drops the passkey, so the next call opens the sheet again`() = runTest {
        val refusals = listOf(
            "a 403" to MockRainApiAuth.forbidden(MockRainApiAuth.SIGNUP_PASSKEY_PATH),
            "a 401 after the renewal" to MockRainApiAuth.tokenRejected(MockRainApiAuth.SIGNUP_PASSKEY_PATH),
            "a 400" to MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 400, MockRainApiAuth.SIGNUP_PASSKEY_PATH),
            "a 409 on a first send" to MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 409, MockRainApiAuth.SIGNUP_PASSKEY_PATH),
        )
        refusals.forEach { (name, refusal) ->
            val turnkey = MockTurnkey(session = null)
            val rainApi = MockRainApiAuth()
            rainApi.signupPasskey = { throw refusal }
            val controller = controller(turnkey, rainApi)

            expectThrows<RainError> { controller.signUpWithPasskey(activity) }
            expectThrows<RainError> { controller.signUpWithPasskey(activity) }

            assertWithMessage(name).that(turnkey.createPasskeyCalls).hasSize(2)
            assertWithMessage(name).that(turnkey.passkeyLoginCalls).isEmpty()
        }
    }

    @Test
    fun `a resend refused for the token, the tenant, the rate or a used token keeps the passkey, and a 400 drops it`() = runTest {
        val outcomes = listOf(
            Triple("a 401 after the renewal", MockRainApiAuth.tokenRejected(MockRainApiAuth.SIGNUP_PASSKEY_PATH), 1),
            Triple("a 403", MockRainApiAuth.forbidden(MockRainApiAuth.SIGNUP_PASSKEY_PATH), 1),
            Triple("a 429", MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 429, MockRainApiAuth.SIGNUP_PASSKEY_PATH), 1),
            Triple("a 409 for a used token", MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 409, MockRainApiAuth.SIGNUP_PASSKEY_PATH), 1),
            Triple("a 400", MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 400, MockRainApiAuth.SIGNUP_PASSKEY_PATH), 2),
        )
        outcomes.forEach { (name, refusal, sheets) ->
            val turnkey = MockTurnkey(session = null)
            val rainApi = MockRainApiAuth()
            val answers = ArrayDeque<() -> RainApiSignup>(listOf({ throw lostAnswer() }, { throw refusal }, { throw lostAnswer() }))
            rainApi.signupPasskey = { answers.firstOrNull()?.also { answers.removeFirst() }?.invoke() ?: throw refusal }
            val controller = controller(turnkey, rainApi)

            expectThrows<RainError.NetworkError> { controller.signUpWithPasskey(activity) }
            expectThrows<RainError> { controller.signUpWithPasskey(activity) }
            expectThrows<RainError> { controller.signUpWithPasskey(activity) }

            // The third call opens the sheet only when the second answer settled the first call's outcome.
            assertWithMessage(name).that(turnkey.createPasskeyCalls).hasSize(sheets)
            assertWithMessage(name).that(turnkey.passkeyLoginCalls).isEmpty()
        }
    }

    @Test
    fun `a sign-up request cancelled in flight keeps the passkey for the next call`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onPasskeyLogin = { turnkey.authenticate() }
        val rainApi = MockRainApiAuth()
        val inFlight = CompletableDeferred<RainApiSignup>()
        var answers = 0
        rainApi.signupPasskey = { if (answers++ == 0) inFlight.await() else RainApiSignup("sub-passkey") }
        val controller = controller(turnkey, rainApi)

        val cancelled = launch { controller.signUpWithPasskey(activity) }
        runCurrent()
        assertThat(rainApi.signupPasskeyCalls).hasSize(1)
        cancelled.cancel()
        cancelled.join()

        controller.signUpWithPasskey(activity)

        assertThat(turnkey.createPasskeyCalls).hasSize(1)
        assertThat(rainApi.signupPasskeyCalls).hasSize(2)
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `a kept passkey survives a refusal over a live session and the logout after it`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onPasskeyLogin = { turnkey.authenticate() }
        val rainApi = MockRainApiAuth()
        var answers = 0
        rainApi.signupPasskey = { if (answers++ == 0) throw lostAnswer() else RainApiSignup("sub-passkey") }
        val controller = controller(turnkey, rainApi)

        expectThrows<RainError.NetworkError> { controller.signUpWithPasskey(activity) }
        // A code login lands in between: the sign-up is refused over the live session, with no sheet and no Rain call.
        turnkey.selectedSessionKey = MockTurnkey.DEFAULT_SESSION_KEY
        turnkey.authenticate()
        expectThrows<RainError.InvalidConfig> { controller.signUpWithPasskey(activity) }
        assertThat(turnkey.createPasskeyCalls).hasSize(1)
        assertThat(rainApi.signupPasskeyCalls).hasSize(1)

        controller.logout()
        controller.signUpWithPasskey(activity)

        assertThat(turnkey.createPasskeyCalls).hasSize(1)
        assertThat(rainApi.signupPasskeyCalls).hasSize(2)
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }
}
