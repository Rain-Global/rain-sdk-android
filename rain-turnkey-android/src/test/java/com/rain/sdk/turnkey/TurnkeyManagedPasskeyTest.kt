package com.rain.sdk.turnkey

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.turnkey.core.models.AuthState
import com.turnkey.types.V1AddressFormat
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * The passkey flows of [TurnkeyManagedAuthController], sign-in and sign-up, over the seam: the
 * session handling the code login pins in [TurnkeyManagedAuthTest], where a passkey login differs
 * (the user's other sessions revoked, so a failed switch signs the device out with the host hook
 * held silent), plus what a passkey ceremony adds (the domain, the fresh key cleared after a
 * failed ceremony). The sign-up's own legs are pinned in
 * [TurnkeyManagedPasskeySignupTest] and the registration a sign-up in doubt keeps and resends in
 * [TurnkeyManagedPasskeyResendTest]. Vendor types stay inside method bodies (see
 * [TurnkeyErrorMappingTest] for why); the Activity is a MockK stand-in the mock never touches.
 */
class TurnkeyManagedPasskeyTest {

    private var hookCalls = 0
    private val activity: Activity = mockk(relaxed = true)
    private val domain = "passkeys.example.com"
    private val tokenRequests = mutableListOf<LoginContact?>()

    @Before
    fun setUp() = assumeJdk24()

    private fun coordinator(turnkey: MockTurnkey) = TurnkeySessionCoordinator(
        turnkey = turnkey,
        onSessionExpired = { hookCalls++ },
        retryDelay = { },
    )

    @Suppress("LongParameterList") // one knob per controller dependency, all but the vendor defaulted
    private fun controller(
        turnkey: MockTurnkey,
        rainApi: MockRainApiAuth = MockRainApiAuth(),
        passkeyDomain: String? = domain,
        configurationError: RainError? = null,
        coordinator: TurnkeySessionCoordinator = coordinator(turnkey),
        nowEpochSeconds: () -> Double = { 1_700_000_000.5 },
        authTokenProvider: suspend (LoginContact?) -> String = { "wat_1" },
    ) = TurnkeyManagedAuthController(
        context = turnkey,
        rainApi = rainApi,
        authTokenProvider = { contact ->
            tokenRequests += contact
            authTokenProvider(contact)
        },
        coordinator = coordinator,
        configure = { configurationError },
        passkeyDomain = passkeyDomain,
        nowEpochSeconds = nowEpochSeconds,
    )

    /** The attestation the stubbed ceremony produces, in the shape Rain's sign-up takes. */
    private val stubbedAttestation = RainApiPasskeyAttestation(
        credentialId = "stub-credential",
        clientDataJson = "stub-client-data",
        attestationObject = "stub-attestation",
        transports = listOf("AUTHENTICATOR_TRANSPORT_INTERNAL"),
    )

    // ---------- sign-in ----------

    @Test
    fun `passkey login on a fresh install stores under a fresh key with the domain, selects nothing itself and backfills`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.defaultWallet()), session = null)
        turnkey.onPasskeyLogin = { turnkey.authenticate() }
        val controller = controller(turnkey)

        controller.loginWithPasskey(activity)

        val call = turnkey.passkeyLoginCalls.single()
        assertThat(call.sessionKey).startsWith("rain-turnkey-")
        // The parent organization resolves the passkey's sub-organization; no token, no Rain leg.
        assertThat(call.organizationId).isNull()
        assertThat(tokenRequests).isEmpty()
        // The vendor auto-selected the first session, so no redundant re-select and nothing cleared.
        assertThat(turnkey.selectSessionCalls).isEmpty()
        assertThat(turnkey.selectedSessionKey).isEqualTo(call.sessionKey)
        assertThat(turnkey.clearSessionCalls).isEmpty()
        // Backfill: the Ethereum-only fixture gets its Solana account on the existing wallet.
        val added = turnkey.createWalletAccountsCalls.single()
        assertThat(added.accounts.map { it.addressFormat }).containsExactly(V1AddressFormat.ADDRESS_FORMAT_SOLANA)
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `passkey login over a live code session switches to a fresh key, evicts cached accounts and clears the old key`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        val coordinator = coordinator(turnkey)
        val deathsBefore = coordinator.deathEpoch
        val controller = controller(turnkey, coordinator = coordinator)

        controller.loginWithPasskey(activity)

        val fresh = turnkey.passkeyLoginCalls.single().sessionKey
        assertThat(fresh).isNotEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(turnkey.selectSessionCalls).containsExactly(fresh)
        assertThat(turnkey.selectedSessionKey).isEqualTo(fresh)
        // Never a pre-login clear; the previous session goes only after the switch succeeded.
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(turnkey.clearSessionCalls).containsExactly(MockTurnkey.DEFAULT_SESSION_KEY)
        // Active to Active is not a death the watcher notices, so eviction is explicit; the hook stays silent.
        assertThat(coordinator.deathEpoch).isEqualTo(deathsBefore + 1)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `passkey login and sign-up with no domain throw InvalidConfig before the vendor or the host is touched`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi, passkeyDomain = null)

        val login = expectThrows<RainError.InvalidConfig> { controller.loginWithPasskey(activity) }
        val signUp = expectThrows<RainError.InvalidConfig> { controller.signUpWithPasskey(activity) }

        assertThat(login).hasMessageThat().contains("passkeyDomain")
        assertThat(signUp).hasMessageThat().contains("assetlinks.json")
        assertThat(turnkey.awaitReadyCallCount).isEqualTo(0)
        assertThat(turnkey.passkeyLoginCalls).isEmpty()
        assertThat(turnkey.createPasskeyCalls).isEmpty()
        assertThat(rainApi.signupPasskeyCalls).isEmpty()
        assertThat(tokenRequests).isEmpty()
    }

    @Test
    fun `a cancelled passkey login propagates the cancellation and clears a fresh key the vendor stored but did not select`() = runTest {
        // Over a live session: the vendor stored the fresh session, then the cancellation landed.
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        turnkey.passkeyStoresBeforeThrowing = true
        turnkey.passkeyLoginError = CancellationException("cancelled")
        val controller = controller(turnkey)

        expectThrows<CancellationException> { controller.loginWithPasskey(activity) }

        val fresh = turnkey.passkeyLoginCalls.single().sessionKey
        assertThat(turnkey.clearSessionCalls).containsExactly(fresh)
        assertThat(turnkey.selectedSessionKey).isEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(hookCalls).isEqualTo(0)

        // On a fresh install the vendor selected the session before failing: it stays signed in.
        val freshInstall = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        freshInstall.passkeyStoresBeforeThrowing = true
        freshInstall.passkeyLoginError = CancellationException("cancelled")
        freshInstall.onPasskeyLogin = { freshInstall.authenticate() }

        expectThrows<CancellationException> { controller(freshInstall).loginWithPasskey(activity) }

        assertThat(freshInstall.clearSessionCalls).isEmpty()
        assertThat(freshInstall.selectedSessionKey).isEqualTo(freshInstall.passkeyLoginCalls.single().sessionKey)
    }

    @Test
    fun `a select failure after a passkey login signs the device out without the hook, and a later genuine death still notifies`() =
        runTest {
            val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
            turnkey.selectSessionError = RuntimeException("select failed")
            val coordinator = coordinator(turnkey)
            val controller = controller(turnkey, coordinator = coordinator)
            // Monitored, as a host runs it: the sign-out below is a death the watcher sees and the
            // suppression has to consume, or the next genuine death would be swallowed.
            coordinator.startMonitoring(backgroundScope)
            runCurrent()

            expectThrows<RainError.ProviderError> { controller.loginWithPasskey(activity) }

            val fresh = turnkey.passkeyLoginCalls.single().sessionKey
            // The fresh key is cleared (the select did not take effect) and the previous session, already
            // revoked server-side by the login, is cleared too, with the host hook silent. Read before
            // the scheduler runs anything else: the host reads these right after the throw.
            assertThat(turnkey.clearSessionCalls).contains(fresh)
            assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(1)
            assertThat(turnkey.selectedSessionKey).isNull()
            assertThat(controller.hasActiveSession()).isFalse()
            assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
            assertThat(hookCalls).isEqualTo(0)
            runCurrent()
            assertThat(hookCalls).isEqualTo(0)

            // A genuine death after a re-login still notifies the host.
            turnkey.authenticate()
            runCurrent()
            turnkey.session = null
            turnkey.authStateFlow.value = AuthState.unauthenticated
            runCurrent()
            assertThat(hookCalls).isEqualTo(1)
        }

    @Test
    fun `a select failure whose sign-out also fails surfaces the select error, keeps the previous session and re-arms the hook`() =
        runTest {
            val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
            turnkey.selectSessionError = RuntimeException("select failed")
            turnkey.clearSelectedSessionError = RuntimeException("storage locked")
            val coordinator = coordinator(turnkey)
            val controller = controller(turnkey, coordinator = coordinator)
            coordinator.startMonitoring(backgroundScope)
            runCurrent()

            val thrown = expectThrows<RainError.ProviderError> { controller.loginWithPasskey(activity) }

            // The select failure is what surfaces; the failed clear is logged. The previous session is
            // still selected, so the device reads signed in, and the host hook stayed silent.
            assertThat(thrown.causeChain().any { it === turnkey.selectSessionError }).isTrue()
            assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(1)
            assertThat(turnkey.selectedSessionKey).isEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
            assertThat(controller.hasActiveSession()).isTrue()
            assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
            assertThat(hookCalls).isEqualTo(0)

            // The sign-out did not happen, so the suppression armed for it must not swallow a real death.
            turnkey.session = null
            turnkey.authStateFlow.value = AuthState.unauthenticated
            runCurrent()
            assertThat(hookCalls).isEqualTo(1)
        }

    @Test
    fun `a vendor failure during login is mapped and the live session stays selected`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        turnkey.passkeyLoginError = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginWithPasskey(
            com.turnkey.stamper.utils.TurnkeyStamperError.AssertionFailed(
                com.turnkey.passkey.utils.TurnkeyPasskeyError.AssertionFailed(
                    androidx.credentials.exceptions.GetCredentialCancellationException("dismissed")
                )
            )
        )
        val controller = controller(turnkey)

        expectThrows<RainError.UserRejected> { controller.loginWithPasskey(activity) }

        assertThat(turnkey.selectedSessionKey).isEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(turnkey.selectSessionCalls).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        // A best-effort clear of the fresh key only; the live key is never touched.
        assertThat(turnkey.clearSessionCalls).doesNotContain(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `a vendor failure after the session store keeps a session the vendor already selected`() = runTest {
        // A fresh install: the vendor stored and selected the fresh session, then its key cleanup threw
        // inside the login wrapper. The account exists and the device is signed in, so it stays so.
        val login = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        login.passkeyStoresBeforeThrowing = true
        login.onPasskeyLogin = { login.authenticate() }
        login.passkeyLoginError = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginWithPasskey(
            RuntimeException("key cleanup failed")
        )

        expectThrows<RainError.ProviderError> { controller(login).loginWithPasskey(activity) }

        assertThat(login.selectedSessionKey).isEqualTo(login.passkeyLoginCalls.single().sessionKey)
        assertThat(login.clearSessionCalls).isEmpty()
        assertThat(login.clearSelectedSessionCallCount).isEqualTo(0)

        // The same shape on the fallback sheet that ends a sign-up whose stamp Turnkey refused: Rain
        // created the account, the vendor stored and selected the session, then its cleanup threw.
        val signUp = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        signUp.deviceKeyLogin = { null }
        signUp.passkeyStoresBeforeThrowing = true
        signUp.onPasskeyLogin = { signUp.authenticate() }
        signUp.passkeyLoginError = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginWithPasskey(
            RuntimeException("key cleanup failed")
        )
        val rainApi = MockRainApiAuth()

        expectThrows<RainError.ProviderError> { controller(signUp, rainApi).signUpWithPasskey(activity) }

        assertThat(rainApi.signupPasskeyCalls).hasSize(1)
        assertThat(signUp.selectedSessionKey).isEqualTo(signUp.passkeyLoginCalls.single().sessionKey)
        assertThat(signUp.clearSessionCalls).isEmpty()
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `a provisioning failure after a passkey login throws and keeps the new session`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.defaultWallet()), session = null)
        turnkey.onPasskeyLogin = { turnkey.authenticate() }
        turnkey.createWalletAccountsError = RuntimeException("provisioning failed")
        val controller = controller(turnkey)

        expectThrows<RainError.ProviderError> { controller.loginWithPasskey(activity) }

        assertThat(turnkey.selectedSessionKey).isEqualTo(turnkey.passkeyLoginCalls.single().sessionKey)
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `a passkey login keeps a pending login code`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onPasskeyLogin = { turnkey.authenticate() }
        val controller = controller(turnkey)
        controller.sendLoginCode(LoginContact.Email("user@example.com"))

        controller.loginWithPasskey(activity)
        controller.confirmLoginCode("123456")

        // The code binds no device key until the confirm creates one, so the vendor's key cleanup
        // after the ceremony cannot hurt it, and the confirm is the ordinary login over a live session.
        val confirm = turnkey.storeSessionCalls.single()
        assertThat(turnkey.selectedSessionKey).isEqualTo(confirm.sessionKey)
        assertThat(turnkey.createdDeviceKeys).containsExactly("device-key-1")
    }

    @Test
    fun `a failing clear of the superseded key is logged, not surfaced`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        turnkey.clearSessionError = RuntimeException("clear failed")
        val controller = controller(turnkey)

        controller.loginWithPasskey(activity)

        assertThat(turnkey.selectedSessionKey).isEqualTo(turnkey.passkeyLoginCalls.single().sessionKey)
        assertThat(turnkey.clearSessionCalls).containsExactly(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    // ---------- add-passkey ----------

    @Test
    fun `addPasskey without a session is TokenExpired and runs no ceremony`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val controller = controller(turnkey)

        expectThrows<RainError.TokenExpired> { controller.addPasskey(activity) }

        assertThat(turnkey.createPasskeyCalls).isEmpty()
        assertThat(turnkey.registerAuthenticatorCalls).isEmpty()
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `addPasskey runs the ceremony then registers it with the session's ids, the name and the registration`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey, nowEpochSeconds = { 1_700_000_000.2 })

        controller.addPasskey(activity)

        assertThat(turnkey.createPasskeyCalls).containsExactly(MockTurnkey.CreatePasskeyCall(domain, "passkey-1700000000"))
        val registered = turnkey.registerAuthenticatorCalls.single()
        assertThat(registered.organizationId).isEqualTo(MockTurnkey.DEFAULT_ORG_ID)
        assertThat(registered.userId).isEqualTo("user-id")
        assertThat(registered.name).isEqualTo("passkey-1700000000")
        assertThat(registered.registration).isEqualTo(turnkey.stubbedPasskeyRegistration)
        // No session change: the same key stays selected and nothing was cleared.
        assertThat(turnkey.selectedSessionKey).isEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(turnkey.selectSessionCalls).isEmpty()
        assertThat(turnkey.clearSessionCalls).isEmpty()
    }

    @Test
    fun `a 401 on the registration refreshes the session once and registers again without a second sheet`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.registerAuthenticatorError =
            RuntimeException("HTTP error calling ACTIVITY_TYPE_CREATE_AUTHENTICATORS_V2 request\nError: {}\nCode: 401")
        turnkey.onRefreshSession = { turnkey.registerAuthenticatorError = null }
        val controller = controller(turnkey)

        controller.addPasskey(activity)

        assertThat(turnkey.createPasskeyCalls).hasSize(1)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
        assertThat(turnkey.registerAuthenticatorCalls).hasSize(2)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `a transient status on the registration is not retried and its body never reaches the message`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.registerAuthenticatorError = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_CREATE_AUTHENTICATORS_V2 request\nError: {\"detail\":\"try later\"}\nCode: 503"
        )
        val controller = controller(turnkey)

        val failed = expectThrows<RainError.ProviderError> { controller.addPasskey(activity) }

        // One registration, no refresh: a retry would resubmit the same attestation.
        assertThat(turnkey.registerAuthenticatorCalls).hasSize(1)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
        assertThat(failed).hasMessageThat().contains("503")
        assertThat(failed).hasMessageThat().doesNotContain("try later")
    }

    @Test
    fun `other auth calls wait while the passkey sheet is open`() = runTest {
        val turnkey = MockTurnkey()
        val sheet = CompletableDeferred<Unit>()
        turnkey.onCreatePasskey = { sheet.await() }
        val controller = controller(turnkey)

        val registration = launch { controller.addPasskey(activity) }
        runCurrent()
        val logout = launch { controller.logout() }
        runCurrent()

        // The sheet is open: the logout queued behind it, nothing cleared, nothing registered yet.
        assertThat(turnkey.createPasskeyCalls).hasSize(1)
        assertThat(turnkey.registerAuthenticatorCalls).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)

        sheet.complete(Unit)
        advanceUntilIdle()
        registration.join()
        logout.join()

        // The registration went to the session that was live when the sheet opened; the logout followed.
        assertThat(turnkey.registerAuthenticatorCalls.single().organizationId).isEqualTo(MockTurnkey.DEFAULT_ORG_ID)
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(1)
    }

    @Test
    fun `a cancelled add-passkey ceremony propagates the cancellation and registers nothing`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.createPasskeyError = CancellationException("cancelled")
        val controller = controller(turnkey)

        expectThrows<CancellationException> { controller.addPasskey(activity) }

        assertThat(turnkey.registerAuthenticatorCalls).isEmpty()
        assertThat(turnkey.selectedSessionKey).isEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
    }

    @Test
    fun `a refused add-passkey ceremony maps to UserRejected and registers nothing`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.createPasskeyError = com.turnkey.passkey.utils.TurnkeyPasskeyError.RegistrationFailed(
            androidx.credentials.exceptions.CreateCredentialCancellationException("dismissed")
        )
        val controller = controller(turnkey)

        expectThrows<RainError.UserRejected> { controller.addPasskey(activity) }

        assertThat(turnkey.registerAuthenticatorCalls).isEmpty()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `a failed registration activity is ProviderError and a 403 is Unauthorized`() = runTest {
        val failed = MockTurnkey()
        // The vendor's typed error for a registration activity Turnkey failed (http 2.2.0): the provider's failure, nothing to retry.
        failed.registerAuthenticatorError = MockTurnkey.activityNotCompleted(
            path = "/public/v1/submit/create_authenticators",
            type = com.turnkey.types.V1ActivityType.ACTIVITY_TYPE_CREATE_AUTHENTICATORS_V2,
            status = com.turnkey.types.V1ActivityStatus.ACTIVITY_STATUS_FAILED,
            failureMessage = "authenticator limit reached"
        )
        expectThrows<RainError.ProviderError> { controller(failed).addPasskey(activity) }
        assertThat(failed.createPasskeyCalls).hasSize(1)
        assertThat(failed.refreshSessionCallCount).isEqualTo(0)

        val refused = MockTurnkey()
        refused.registerAuthenticatorError =
            RuntimeException("HTTP error calling ACTIVITY_TYPE_CREATE_AUTHENTICATORS_V2 request\nError: {}\nCode: 403")
        expectThrows<RainError.Unauthorized> { controller(refused).addPasskey(activity) }
        assertThat(refused.registerAuthenticatorCalls).hasSize(1)
        // Neither leaves the session behind: the passkey exists on the device, not on the account.
        assertThat(refused.selectedSessionKey).isEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `addPasskey with no domain or on a closed controller refuses before the ceremony`() = runTest {
        val noDomain = MockTurnkey()
        val refused = expectThrows<RainError.InvalidConfig> { controller(noDomain, passkeyDomain = null).addPasskey(activity) }
        assertThat(refused).hasMessageThat().contains("passkeyDomain")
        assertThat(noDomain.createPasskeyCalls).isEmpty()
        assertThat(noDomain.awaitReadyCallCount).isEqualTo(0)

        val closed = MockTurnkey()
        val controller = controller(closed)
        controller.close()
        expectThrows<RainError.InvalidConfig> { controller.addPasskey(activity) }
        assertThat(closed.createPasskeyCalls).isEmpty()
    }

    // ---------- guards shared by both flows ----------

    @Test
    fun `a closed controller refuses both passkey flows with no vendor call`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)
        controller.close()

        expectThrows<RainError.InvalidConfig> { controller.loginWithPasskey(activity) }
        expectThrows<RainError.InvalidConfig> { controller.signUpWithPasskey(activity) }

        assertThat(turnkey.passkeyLoginCalls).isEmpty()
        assertThat(turnkey.createPasskeyCalls).isEmpty()
        assertThat(tokenRequests).isEmpty()
        assertThat(turnkey.awaitReadyCallCount).isEqualTo(0)
    }

    @Test
    fun `a configuration conflict refuses both passkey flows before touching the vendor`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val controller = controller(turnkey, configurationError = RainError.InvalidConfig("mismatch"))

        expectThrows<RainError.InvalidConfig> { controller.loginWithPasskey(activity) }
        expectThrows<RainError.InvalidConfig> { controller.signUpWithPasskey(activity) }

        assertThat(turnkey.awaitReadyCallCount).isEqualTo(0)
        assertThat(turnkey.passkeyLoginCalls).isEmpty()
        assertThat(turnkey.createPasskeyCalls).isEmpty()
        assertThat(tokenRequests).isEmpty()
    }
}
