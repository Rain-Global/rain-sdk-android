package com.rain.sdk.turnkey

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * The passkey sign-up of [TurnkeyManagedAuthController]: one ceremony, Rain's sign-up with a device
 * key, Turnkey's login stamped with that key, the passkey sheet when Turnkey refuses the stamp, and
 * what each failure leaves behind. The `signUpWithPasskey` row in `docs/METHODS.md` states the
 * contract. The sign-in and add-passkey flows are in [TurnkeyManagedPasskeyTest], the kept
 * registration in [TurnkeyManagedPasskeyResendTest], the stamp itself in [TurnkeyStampLoginTest]
 * and the session store's rule in the code login's [TurnkeyManagedCodeOutcomeTest]. Vendor types
 * stay inside method bodies; the Activity is a MockK stand-in the mock never touches.
 */
class TurnkeyManagedPasskeySignupTest {

    private var hookCalls = 0
    private val activity: Activity = mockk(relaxed = true)
    private val domain = "passkeys.example.com"
    private val tokenRequests = mutableListOf<LoginContact?>()

    @Before
    fun setUp() = assumeJdk24()

    private fun controller(
        turnkey: MockTurnkey,
        rainApi: MockRainApiAuth = MockRainApiAuth(),
        nowEpochSeconds: () -> Double = { 1_700_000_000.5 },
    ) = TurnkeyManagedAuthController(
        context = turnkey,
        rainApi = rainApi,
        authTokenProvider = { contact ->
            tokenRequests += contact
            "wat_1"
        },
        coordinator = TurnkeySessionCoordinator(turnkey = turnkey, onSessionExpired = { hookCalls++ }, retryDelay = { }),
        configure = { null },
        passkeyDomain = domain,
        nowEpochSeconds = nowEpochSeconds,
    )

    @Test
    fun `passkey sign-up runs one ceremony, sends the device key to Rain, stamps the login with it and keeps it as the session's key`() =
        runTest {
            val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
            turnkey.onStoreSession = { turnkey.authenticate() }
            val rainApi = MockRainApiAuth()
            rainApi.signupPasskey = { RainApiSignup("sub-passkey") }
            val controller = controller(turnkey, rainApi)

            controller.signUpWithPasskey(activity)

            assertThat(turnkey.createPasskeyCalls).hasSize(1)
            val deviceKey = turnkey.createdDeviceKeys.single()
            assertThat(rainApi.signupPasskeyCalls.single().publicKey).isEqualTo(deviceKey)
            assertThat(turnkey.deviceKeyLoginCalls).containsExactly(MockTurnkey.DeviceKeyLoginCall(deviceKey, "sub-passkey"))
            val stored = turnkey.storeSessionCalls.single()
            assertThat(stored.jwt).isEqualTo("jwt-for-$deviceKey")
            assertThat(stored.sessionKey).startsWith("rain-turnkey-")
            assertThat(turnkey.selectedSessionKey).isEqualTo(stored.sessionKey)
            assertThat(turnkey.passkeyLoginCalls).isEmpty()
            assertThat(turnkey.liveDeviceKeys).containsExactly(deviceKey)
            assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        }

    @Test
    fun `Turnkey refusing the stamp signs in through the sheet bound to the answered sub-organization, with the device key deleted`() =
        runTest {
            val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
            turnkey.deviceKeyLogin = { null }
            turnkey.onPasskeyLogin = { turnkey.authenticate() }
            val rainApi = MockRainApiAuth()
            rainApi.signupPasskey = { RainApiSignup("sub-passkey") }
            val controller = controller(turnkey, rainApi)

            controller.signUpWithPasskey(activity)

            assertThat(turnkey.createPasskeyCalls).hasSize(1)
            assertThat(turnkey.deviceKeyLoginCalls).containsExactly(MockTurnkey.DeviceKeyLoginCall("device-key-1", "sub-passkey"))
            assertThat(turnkey.storeSessionCalls).isEmpty()
            val login = turnkey.passkeyLoginCalls.single()
            assertThat(login.organizationId).isEqualTo("sub-passkey")
            assertThat(turnkey.selectedSessionKey).isEqualTo(login.sessionKey)
            assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
            assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        }

    @Test
    fun `a stamp lost in transport is NetworkError and one Turnkey failed is ProviderError, with no sheet and the device key deleted`() =
        runTest {
            val failures = listOf(
                Triple(
                    "a transport failure",
                    RainError.NetworkError("Turnkey stamp login: SocketTimeoutException", SocketTimeoutException()),
                    RainErrorCode.NETWORK_ERROR,
                ),
                Triple(
                    "a 503",
                    RuntimeException("HTTP error calling ACTIVITY_TYPE_STAMP_LOGIN request\nError: {}\nCode: 503"),
                    RainErrorCode.PROVIDER_ERROR,
                ),
            )
            failures.forEach { (name, failure, code) ->
                val turnkey = MockTurnkey(session = null)
                turnkey.deviceKeyLogin = { throw failure }
                val rainApi = MockRainApiAuth()
                val controller = controller(turnkey, rainApi)

                val thrown = expectThrows<RainError> { controller.signUpWithPasskey(activity) }

                assertWithMessage(name).that(thrown.errorCode).isEqualTo(code)
                assertWithMessage(name).that(rainApi.signupPasskeyCalls).hasSize(1)
                assertWithMessage(name).that(turnkey.passkeyLoginCalls).isEmpty()
                assertWithMessage(name).that(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
                assertWithMessage(name).that(turnkey.selectedSessionKey).isNull()
                assertWithMessage(name).that(controller.hasActiveSession()).isFalse()
                assertWithMessage(name).that(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
            }
        }

    @Test
    fun `a live selected session refuses the sign-up up front with no vendor, host or Rain call, inside the refresh buffer too`() =
        runTest {
            class Row(val name: String, val turnkey: MockTurnkey, val now: Double, val activeBefore: Boolean)
            val nearExpiry = MockTurnkey.nearExpirySession()
            val rows = listOf(
                Row("a live session", MockTurnkey(), now = 1_700_000_000.5, activeBefore = true),
                // The clock sits ten seconds before the expiry: hasActiveSession() reads false, the session is live.
                Row("inside the refresh buffer", MockTurnkey(session = nearExpiry), now = nearExpiry.expiry - 10, activeBefore = false),
            )
            rows.forEach { row ->
                val rainApi = MockRainApiAuth()
                val controller = controller(row.turnkey, rainApi, nowEpochSeconds = { row.now })
                assertWithMessage(row.name).that(controller.hasActiveSession()).isEqualTo(row.activeBefore)

                val refused = expectThrows<RainError.InvalidConfig> { controller.signUpWithPasskey(activity) }

                assertWithMessage(row.name).that(refused).hasMessageThat().contains("log out")
                assertWithMessage(row.name).that(row.turnkey.createPasskeyCalls).isEmpty()
                assertWithMessage(row.name).that(row.turnkey.createdDeviceKeys).isEmpty()
                assertWithMessage(row.name).that(tokenRequests).isEmpty()
                assertWithMessage(row.name).that(rainApi.signupPasskeyCalls).isEmpty()
                assertWithMessage(row.name).that(row.turnkey.selectedSessionKey).isEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
                assertWithMessage(row.name).that(row.turnkey.clearSelectedSessionCallCount).isEqualTo(0)
            }
        }

    @Test
    fun `a dead selected session is cleared silently and the sign-up proceeds, or aborts with nothing asked of Rain`() = runTest {
        // Cleared the way a logout clears it, hook silent; with nothing selected, the vendor selects the session it stores.
        val cleared = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = MockTurnkey.expiredSession())
        cleared.onStoreSession = { cleared.authenticate() }
        val controller = controller(cleared)
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)

        controller.signUpWithPasskey(activity)

        assertThat(cleared.clearSelectedSessionCallCount).isEqualTo(1)
        assertThat(cleared.selectedSessionKey).isEqualTo(cleared.storeSessionCalls.single().sessionKey)
        assertThat(cleared.liveDeviceKeys).containsExactly("device-key-1")
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        assertThat(hookCalls).isEqualTo(0)

        // A dismissed ceremony over it: the dead session is gone, no key, no account, nothing selected.
        val dismissed = MockTurnkey(session = MockTurnkey.expiredSession())
        dismissed.createPasskeyError = com.turnkey.passkey.utils.TurnkeyPasskeyError.RegistrationFailed(
            androidx.credentials.exceptions.CreateCredentialCancellationException("dismissed")
        )
        val dismissedRain = MockRainApiAuth()
        val dismissedController = controller(dismissed, dismissedRain)

        expectThrows<RainError.UserRejected> { dismissedController.signUpWithPasskey(activity) }

        assertThat(dismissed.clearSelectedSessionCallCount).isEqualTo(1)
        assertThat(dismissed.createdDeviceKeys).isEmpty()
        assertThat(dismissedRain.signupPasskeyCalls).isEmpty()
        assertThat(dismissed.selectedSessionKey).isNull()
        assertThat(dismissedController.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        assertThat(hookCalls).isEqualTo(0)

        // A dead session that will not clear aborts before the sheet, still selected.
        val stuck = MockTurnkey(session = MockTurnkey.expiredSession())
        stuck.clearSelectedSessionError = RuntimeException("storage locked")

        expectThrows<RainError.ProviderError> { controller(stuck).signUpWithPasskey(activity) }

        assertThat(stuck.createPasskeyCalls).isEmpty()
        assertThat(stuck.createdDeviceKeys).isEmpty()
        assertThat(stuck.selectedSessionKey).isEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
    }

    @Test
    fun `Rain refusing the sign-up maps, stamps no login, opens no sheet, leaves nothing selected and deletes the device key`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val rainApi = MockRainApiAuth()
        rainApi.signupPasskey = {
            throw MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 409, MockRainApiAuth.SIGNUP_PASSKEY_PATH)
        }
        val controller = controller(turnkey, rainApi)

        expectThrows<RainError.ProviderError> { controller.signUpWithPasskey(activity) }

        assertThat(turnkey.deviceKeyLoginCalls).isEmpty()
        assertThat(turnkey.passkeyLoginCalls).isEmpty()
        assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
        assertThat(turnkey.selectedSessionKey).isNull()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `a cancellation that lands in the stamp propagates, deletes the device key and selects nothing`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val stamping = CompletableDeferred<String>()
        turnkey.deviceKeyLogin = { stamping.await() }
        val controller = controller(turnkey)

        var caught: Throwable? = null
        val job = launch {
            try {
                controller.signUpWithPasskey(activity)
            } catch (t: Throwable) {
                caught = t
                throw t
            }
        }
        runCurrent()
        assertThat(turnkey.deviceKeyLoginCalls).hasSize(1)
        job.cancelAndJoin()

        assertThat(caught).isInstanceOf(CancellationException::class.java)
        assertThat(caught).isNotInstanceOf(RainError::class.java)
        assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
        assertThat(turnkey.passkeyLoginCalls).isEmpty()
        assertThat(turnkey.selectedSessionKey).isNull()
        assertThat(controller.hasActiveSession()).isFalse()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        assertThat(hookCalls).isEqualTo(0)
    }
}
