package com.rain.sdk.turnkey

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * The kept registration behind [TurnkeyManagedAuthController.signUpWithPasskey]: a sign-up Rain left
 * in doubt stays in the [PendingPasskeySignupStore], and the next call resends it under a fresh token
 * and device key with no sheet. The tests pin which answers keep, drop or settle it, and what a code
 * login, a logout, `close()` and a relaunch leave of it; the registration is saved before the send,
 * so a request cancelled in flight is kept the way a lost answer is, and so is one the process died
 * on. The host-facing rule is the `signUpWithPasskey` row of docs/METHODS.md. The
 * sign-up itself, the stamp fallback and the device key's deletion are in
 * [TurnkeyManagedPasskeySignupTest], whose controller seam this copies.
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
        pendingPasskeySignups: PendingPasskeySignupStore = InMemoryPendingPasskeySignupStore(),
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
        pendingPasskeySignups = pendingPasskeySignups,
        nowEpochSeconds = { 1_700_000_000.5 },
    )

    /** The client's transport failure on the sign-up leg. */
    private fun lostAnswer() = RainError.NetworkError(
        "Rain API /v1/issuing/embedded-wallets/signup/passkey: SocketTimeoutException",
        SocketTimeoutException(),
    )

    private fun unreadable() = RainError.InternalError("Rain API /v1/issuing/embedded-wallets/signup/passkey: unreadable answer")

    private fun tokenRejected() = MockRainApiAuth.tokenRejected(MockRainApiAuth.SIGNUP_PASSKEY_PATH)

    private fun inProgress() = MockRainApiAuth.refusal(RainApiAuthFailure.Kind.SignupInProgress, 409, MockRainApiAuth.SIGNUP_PASSKEY_PATH)

    private fun passkeyTaken() = MockRainApiAuth.refusal(RainApiAuthFailure.Kind.PasskeyTaken, 409, MockRainApiAuth.SIGNUP_PASSKEY_PATH)

    private fun tokenUsed() = MockRainApiAuth.refusal(RainApiAuthFailure.Kind.TokenUsed, 409, MockRainApiAuth.SIGNUP_PASSKEY_PATH)

    /** A refusal the client reads as the status alone: a 400, a 404, a phrase-less 409, a 429, a 5xx. */
    private fun refused(status: Int) = MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, status, MockRainApiAuth.SIGNUP_PASSKEY_PATH)

    /** A row of the in-doubt table: Rain's answers to the first call, what leaves it, and the sends across both calls. */
    private data class InDoubt(
        val name: String,
        val firstCall: List<() -> RainApiSignup>,
        val code: RainErrorCode,
        val sends: Int,
        val message: String? = null,
        val renewalUnreachable: Boolean = false,
    )

    /** A row of the refusal tables: Rain's answer to every send of the refused call, and the sheets and sends counted across the calls. */
    private data class RefusedSend(val name: String, val refusal: RainApiAuthFailure, val sheets: Int, val sends: Int)

    @Test
    fun `a sign-up Rain never answered keeps the passkey, and the next call resends it with a fresh token and key and no sheet`() =
        runTest {
            val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
            turnkey.onStoreSession = { turnkey.authenticate() }
            val rainApi = MockRainApiAuth()
            rainApi.signupPasskey = MockRainApiAuth.answers({ throw lostAnswer() }, { RainApiSignup("sub-passkey") })
            var minted = 0
            val controller = controller(turnkey, rainApi, authTokenProvider = { "wat_${++minted}" })

            val lost = expectThrows<RainError.NetworkError> { controller.signUpWithPasskey(activity) }
            assertThat(lost.errorCode).isEqualTo(RainErrorCode.NETWORK_ERROR)
            assertThat(turnkey.deviceKeyLoginCalls).isEmpty()
            assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)

            controller.signUpWithPasskey(activity)

            assertThat(turnkey.createPasskeyCalls).hasSize(1)
            assertThat(tokenRequests).containsExactly(null, null)
            val (first, resent) = rainApi.signupPasskeyCalls
            assertThat(resent.challenge).isEqualTo(first.challenge)
            assertThat(resent.attestation).isEqualTo(first.attestation)
            assertThat(rainApi.signupPasskeyCalls.map { it.token }).containsExactly("wat_1", "wat_2").inOrder()
            assertThat(rainApi.signupPasskeyCalls.map { it.publicKey }).containsExactly("device-key-1", "device-key-2").inOrder()
            assertThat(turnkey.deviceKeyLoginCalls).containsExactly(MockTurnkey.DeviceKeyLoginCall("device-key-2", "sub-passkey", "device-key-3"))
            val stored = turnkey.storeSessionCalls.single()
            assertThat(stored.jwt).isEqualTo("jwt-for-device-key-3")
            assertThat(turnkey.selectedSessionKey).isEqualTo(stored.sessionKey)
            assertThat(turnkey.passkeyLoginCalls).isEmpty()
            assertThat(turnkey.liveDeviceKeys).containsExactly("device-key-3")
            assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
            assertThat(hookCalls).isEqualTo(0)
        }

    @Test
    fun `the passkey is kept before Rain is asked, so a process that dies with the answer outstanding resends it, and a 200 clears it`() =
        runTest {
            val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
            turnkey.onStoreSession = { turnkey.authenticate() }
            val store = InMemoryPendingPasskeySignupStore()
            val rainApi = MockRainApiAuth()
            var keptDuringSend: PasskeyRegistration? = null
            rainApi.signupPasskey = {
                keptDuringSend = store.load()
                RainApiSignup("sub-passkey")
            }

            val controller = controller(turnkey, rainApi, pendingPasskeySignups = store)

            controller.signUpWithPasskey(activity)

            val sent = rainApi.signupPasskeyCalls.single()
            assertThat(keptDuringSend?.challenge).isEqualTo(sent.challenge)
            assertThat(store.load()).isNull()
            assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        }

    @Test
    fun `a sign-up cancelled with Rain's answer outstanding keeps the passkey and frees the device key, and the next call resends it`() =
        runTest {
            val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
            turnkey.onStoreSession = { turnkey.authenticate() }
            val store = InMemoryPendingPasskeySignupStore()
            val rainApi = MockRainApiAuth()
            val inFlight = CompletableDeferred<RainApiSignup>()
            var answers = 0
            rainApi.signupPasskey = { if (answers++ == 0) inFlight.await() else RainApiSignup("sub-passkey") }
            val controller = controller(turnkey, rainApi, pendingPasskeySignups = store)

            val cancelled = launch { controller.signUpWithPasskey(activity) }
            runCurrent()
            assertThat(rainApi.signupPasskeyCalls).hasSize(1)
            cancelled.cancel()
            cancelled.join()

            assertThat(store.load()).isEqualTo(turnkey.stubbedPasskeyRegistration)
            assertThat(turnkey.liveDeviceKeys).isEmpty()

            controller.signUpWithPasskey(activity)

            assertThat(turnkey.createPasskeyCalls).hasSize(1)
            assertThat(rainApi.signupPasskeyCalls).hasSize(2)
            assertThat(store.load()).isNull()
            assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        }

    @Test
    fun `an answer that leaves the first call in doubt keeps the passkey, and the next call resends it with no sheet`() = runTest {
        val rejected: () -> RainApiSignup = { throw tokenRejected() }
        val rows = listOf(
            InDoubt("a 503", listOf({ throw refused(503) }), RainErrorCode.PROVIDER_ERROR, sends = 2),
            InDoubt("an unreadable answer", listOf({ throw unreadable() }), RainErrorCode.INTERNAL_ERROR, sends = 2),
            InDoubt("a 429", listOf({ throw refused(429) }), RainErrorCode.PROVIDER_ERROR, sends = 2),
            InDoubt("already in progress", listOf({ throw inProgress() }), RainErrorCode.NETWORK_ERROR, sends = 2, message = "still creating"),
            InDoubt("a 401 then a lost retry", listOf(rejected, { throw lostAnswer() }), RainErrorCode.NETWORK_ERROR, sends = 3),
            InDoubt("a 401 then an unreadable retry", listOf(rejected, { throw unreadable() }), RainErrorCode.INTERNAL_ERROR, sends = 3),
            InDoubt(
                "a 401 then a renewal that threw with an IOException cause",
                listOf(rejected),
                RainErrorCode.NETWORK_ERROR,
                sends = 2,
                message = "authTokenProvider",
                renewalUnreachable = true,
            ),
        )
        rows.forEach { row ->
            val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
            turnkey.onStoreSession = { turnkey.authenticate() }
            val rainApi = MockRainApiAuth()
            val answers = ArrayDeque(row.firstCall)
            rainApi.signupPasskey = { answers.removeFirstOrNull()?.invoke() ?: RainApiSignup("sub-passkey") }
            var minted = 0
            val provider: suspend (LoginContact?) -> String = {
                minted++
                if (row.renewalUnreachable && minted == 2) throw IllegalStateException("backend down", IOException()) else "wat_$minted"
            }
            val controller = controller(turnkey, rainApi, authTokenProvider = provider)

            val inDoubt = expectThrows<RainError> { controller.signUpWithPasskey(activity) }
            assertWithMessage(row.name).that(inDoubt.errorCode).isEqualTo(row.code)
            row.message?.let { assertWithMessage(row.name).that(inDoubt).hasMessageThat().contains(it) }

            controller.signUpWithPasskey(activity)

            assertWithMessage(row.name).that(turnkey.createPasskeyCalls).hasSize(1)
            assertWithMessage(row.name).that(rainApi.signupPasskeyCalls).hasSize(row.sends)
            assertWithMessage(row.name).that(turnkey.deviceKeyLoginCalls.single().organizationId).isEqualTo("sub-passkey")
            assertWithMessage(row.name).that(turnkey.passkeyLoginCalls).isEmpty()
            assertWithMessage(row.name).that(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        }
    }

    @Test
    fun `already has a wallet settles a resend through the sheet with no sub-organization id, and drops a first send`() = runTest {
        listOf("on a resend" to true, "on a first send" to false).forEach { (name, resend) ->
            val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
            turnkey.onPasskeyLogin = { turnkey.authenticate() }
            val rainApi = MockRainApiAuth()
            var sent = 0
            rainApi.signupPasskey = { if (resend && sent++ == 0) throw lostAnswer() else throw passkeyTaken() }
            val store = InMemoryPendingPasskeySignupStore()
            val controller = controller(turnkey, rainApi, pendingPasskeySignups = store)

            if (resend) {
                // The first call's account is saved; the parent organization resolves which one the passkey opens.
                expectThrows<RainError.NetworkError> { controller.signUpWithPasskey(activity) }
                controller.signUpWithPasskey(activity)
                assertThat(turnkey.createPasskeyCalls).hasSize(1)
                assertThat(turnkey.passkeyLoginCalls.single().organizationId).isNull()
                assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
            } else {
                // Rain created nothing, so the next call starts over with a new passkey.
                val taken = expectThrows<RainError.InvalidConfig> { controller.signUpWithPasskey(activity) }
                expectThrows<RainError.InvalidConfig> { controller.signUpWithPasskey(activity) }
                assertThat(taken.errorCode).isEqualTo(RainErrorCode.INVALID_CONFIG)
                assertThat(taken).hasMessageThat().contains("loginWithPasskey")
                assertThat(turnkey.createPasskeyCalls).hasSize(2)
                assertThat(turnkey.passkeyLoginCalls).isEmpty()
                assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
            }
            assertWithMessage(name).that(store.load()).isNull()
            assertWithMessage(name).that(rainApi.signupPasskeyCalls).hasSize(2)
            assertWithMessage(name).that(turnkey.deviceKeyLoginCalls).isEmpty()
            assertWithMessage(name).that(turnkey.liveDeviceKeys).isEmpty()
        }
    }

    @Test
    fun `a refusal before Rain created anything drops the passkey, so the next call opens the sheet again`() = runTest {
        val refusals = listOf(
            RefusedSend("a 403", MockRainApiAuth.forbidden(MockRainApiAuth.SIGNUP_PASSKEY_PATH), sheets = 2, sends = 2),
            RefusedSend("a 401 after the renewal", tokenRejected(), sheets = 2, sends = 4),
            RefusedSend("a 409 for a spent token after the renewal", tokenUsed(), sheets = 2, sends = 4),
            RefusedSend("a 400", refused(400), sheets = 2, sends = 2),
            RefusedSend("a 404 from in front of Rain", refused(404), sheets = 2, sends = 2),
            RefusedSend("a 409 with no phrase the client reads", refused(409), sheets = 2, sends = 2),
        )
        refusals.forEach { row ->
            val (name, refusal, sheets) = row
            val sends = row.sends
            val turnkey = MockTurnkey(session = null)
            val rainApi = MockRainApiAuth()
            rainApi.signupPasskey = { throw refusal }
            val controller = controller(turnkey, rainApi)

            expectThrows<RainError> { controller.signUpWithPasskey(activity) }
            expectThrows<RainError> { controller.signUpWithPasskey(activity) }

            // A call refused for its token sends once more under a renewed one before the same refusal leaves.
            assertWithMessage(name).that(turnkey.createPasskeyCalls).hasSize(sheets)
            assertWithMessage(name).that(rainApi.signupPasskeyCalls).hasSize(sends)
            assertWithMessage(name).that(turnkey.passkeyLoginCalls).isEmpty()
        }
    }

    @Test
    fun `a resend refused for the token, the tenant or a 404 keeps the passkey, and a 400 drops it`() = runTest {
        val outcomes = listOf(
            RefusedSend("a 401 after the renewal", tokenRejected(), sheets = 1, sends = 4),
            RefusedSend("a 409 for a spent token after the renewal", tokenUsed(), sheets = 1, sends = 4),
            RefusedSend("a 403", MockRainApiAuth.forbidden(MockRainApiAuth.SIGNUP_PASSKEY_PATH), sheets = 1, sends = 3),
            RefusedSend("a 404 from in front of Rain", refused(404), sheets = 1, sends = 3),
            RefusedSend("a 400", refused(400), sheets = 2, sends = 3),
        )
        outcomes.forEach { row ->
            val (name, refusal, sheets) = row
            val sends = row.sends
            val turnkey = MockTurnkey(session = null)
            val rainApi = MockRainApiAuth()
            // The first and the third call lose their answer; the second is refused on every send.
            var attempt = 0
            rainApi.signupPasskey = { if (attempt == 2) throw refusal else throw lostAnswer() }
            val controller = controller(turnkey, rainApi)

            attempt = 1
            expectThrows<RainError.NetworkError> { controller.signUpWithPasskey(activity) }
            attempt = 2
            expectThrows<RainError> { controller.signUpWithPasskey(activity) }
            attempt = 3
            expectThrows<RainError.NetworkError> { controller.signUpWithPasskey(activity) }

            // The third call opens the sheet only when the refusal settled the first call's outcome.
            assertWithMessage(name).that(turnkey.createPasskeyCalls).hasSize(sheets)
            assertWithMessage(name).that(rainApi.signupPasskeyCalls).hasSize(sends)
            assertWithMessage(name).that(turnkey.passkeyLoginCalls).isEmpty()
        }
    }

    @Test
    fun `a 409 for a token another call spent is renewed once, like a 401, with the same passkey and device key`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onStoreSession = { turnkey.authenticate() }
        val rainApi = MockRainApiAuth()
        rainApi.signupPasskey = MockRainApiAuth.answers({ throw tokenUsed() }, { RainApiSignup("sub-passkey") })
        var minted = 0
        val controller = controller(turnkey, rainApi, authTokenProvider = { "wat_${++minted}" })

        controller.signUpWithPasskey(activity)

        assertThat(turnkey.createPasskeyCalls).hasSize(1)
        assertThat(tokenRequests).containsExactly(null, null)
        assertThat(rainApi.signupPasskeyCalls.map { it.token }).containsExactly("wat_1", "wat_2").inOrder()
        assertThat(rainApi.signupPasskeyCalls.map { it.publicKey }).containsExactly("device-key-1", "device-key-1")
        assertThat(turnkey.deviceKeyLoginCalls).containsExactly(MockTurnkey.DeviceKeyLoginCall("device-key-1", "sub-passkey", "device-key-2"))
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `a kept passkey survives a code login in between, the refusal over its live session and the logout after it`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onStoreSession = { turnkey.authenticate() }
        val rainApi = MockRainApiAuth()
        rainApi.signupPasskey = MockRainApiAuth.answers({ throw lostAnswer() }, { RainApiSignup("sub-passkey") })
        val store = InMemoryPendingPasskeySignupStore()
        val controller = controller(turnkey, rainApi, pendingPasskeySignups = store)

        expectThrows<RainError.NetworkError> { controller.signUpWithPasskey(activity) }
        controller.sendLoginCode(LoginContact.Email("user@example.com"))
        controller.confirmLoginCode("123456")
        assertThat(rainApi.loginCalls).hasSize(1)
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        assertThat(store.load()).isEqualTo(turnkey.stubbedPasskeyRegistration)
        // Over the live session the sign-up is refused with no sheet and no Rain call.
        expectThrows<RainError.InvalidConfig> { controller.signUpWithPasskey(activity) }
        assertThat(turnkey.createPasskeyCalls).hasSize(1)
        assertThat(rainApi.signupPasskeyCalls).hasSize(1)
        assertThat(store.load()).isEqualTo(turnkey.stubbedPasskeyRegistration)

        controller.logout()
        controller.signUpWithPasskey(activity)

        assertThat(turnkey.createPasskeyCalls).hasSize(1)
        assertThat(rainApi.signupPasskeyCalls).hasSize(2)
        assertThat(turnkey.deviceKeyLoginCalls).containsExactly(MockTurnkey.DeviceKeyLoginCall("device-key-3", "sub-passkey", "device-key-4"))
        assertThat(turnkey.passkeyLoginCalls).isEmpty()
        assertThat(store.load()).isNull()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `a kept passkey survives close() and a relaunch, and the controller that shares its store resends it with no ceremony`() = runTest {
        // A relaunch is a fresh vendor, Rain client and controller over the same store.
        val store = InMemoryPendingPasskeySignupStore()
        val firstLaunch = MockTurnkey(session = null)
        val firstRain = MockRainApiAuth()
        firstRain.signupPasskey = { throw lostAnswer() }
        val firstController = controller(firstLaunch, firstRain, pendingPasskeySignups = store)

        expectThrows<RainError.NetworkError> { firstController.signUpWithPasskey(activity) }
        firstController.close()

        assertThat(store.load()).isEqualTo(firstLaunch.stubbedPasskeyRegistration)
        val relaunch = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        relaunch.onStoreSession = { relaunch.authenticate() }
        val relaunchRain = MockRainApiAuth()
        relaunchRain.signupPasskey = { RainApiSignup("sub-passkey") }
        val controller = controller(relaunch, relaunchRain, pendingPasskeySignups = store)

        controller.signUpWithPasskey(activity)

        assertThat(relaunch.createPasskeyCalls).isEmpty()
        val first = firstRain.signupPasskeyCalls.single()
        val resent = relaunchRain.signupPasskeyCalls.single()
        assertThat(resent.challenge).isEqualTo(first.challenge)
        assertThat(resent.attestation).isEqualTo(first.attestation)
        assertThat(relaunch.deviceKeyLoginCalls).containsExactly(MockTurnkey.DeviceKeyLoginCall("device-key-1", "sub-passkey", "device-key-2"))
        assertThat(relaunch.passkeyLoginCalls).isEmpty()
        assertThat(store.load()).isNull()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }
}
