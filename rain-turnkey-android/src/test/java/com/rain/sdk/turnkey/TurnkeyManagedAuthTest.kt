package com.rain.sdk.turnkey

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.turnkey.core.models.AuthState
import com.turnkey.core.models.errors.TurnkeyKotlinError
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1Curve
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Managed-auth tests for [TurnkeyManagedAuthController] over the email channel, run against
 * [MockTurnkey] for the vendor's device primitives and [MockRainApiAuth] for Rain's endpoints — no
 * vendor singleton and no network is ever touched. Gated on JDK 24 like every Turnkey suite. The
 * configurator is process-global state, so it is reset around every test; its own rules live in
 * [TurnkeyManagedConfiguratorTest]. The confirm's failure outcomes and the token refetch are in
 * [TurnkeyManagedCodeOutcomeTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnkeyManagedAuthTest {

    private var hookCalls = 0

    /** Every contact the host's token provider was asked for, in order: null is a passkey sign-up. */
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
        onSessionExpired = { hookCalls++ },
        retryDelay = { },
    )

    @Suppress("LongParameterList") // one knob per controller dependency, all but the vendor defaulted
    private fun controller(
        turnkey: MockTurnkey,
        rainApi: MockRainApiAuth = MockRainApiAuth(),
        authTokenProvider: suspend (LoginContact?) -> String = { "wat_1" },
        configurationError: RainError? = null,
        coordinator: TurnkeySessionCoordinator = coordinator(turnkey),
        passkeyDomain: String? = null,
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
    )

    /** Turnkey's documented sandbox number, never a real person's. SMS-specific tests live in [TurnkeyManagedAuthSmsTest]. */
    private val smsContact = LoginContact.Phone("+19999999999")
    private val email = LoginContact.Email("user@example.com")

    // ---------- the code request ----------

    @Test
    fun `sendLoginCode asks the host for a token bound to the canonical contact, requests the code under it and touches no session`() =
        runTest {
            val turnkey = MockTurnkey(session = null)
            val rainApi = MockRainApiAuth()
            val controller = controller(turnkey, rainApi)

            controller.sendLoginCode(LoginContact.Email("  User@Example.COM "))

            assertThat(tokenRequests).containsExactly(LoginContact.Email("user@example.com"))
            assertThat(rainApi.initOtpCalls).containsExactly("wat_1")
            assertThat(turnkey.awaitReadyCallCount).isEqualTo(1)
            assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
            assertThat(turnkey.clearSessionCalls).isEmpty()
            assertThat(turnkey.createdDeviceKeys).isEmpty()
        }

    @Test
    fun `a failed resend for the same email in another spelling keeps the pending challenge`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onStoreSession = { turnkey.authenticate() }
        val rainApi = MockRainApiAuth()
        rainApi.initOtp = MockRainApiAuth.answers(
            { RainApiOtpChallenge("otp-1", "bundle-1") },
            { throw MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 503, MockRainApiAuth.INIT_PATH) },
        )
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(email)

        expectThrows<RainError.ProviderError> { controller.sendLoginCode(LoginContact.Email("User@Example.com")) }

        // One address, one account: the spelling is not another contact, so the first code is still typable.
        controller.confirmLoginCode("123456")
        assertThat(rainApi.verifyOtpCalls.single().otpId).isEqualTo("otp-1")
    }

    @Test
    fun `confirmLoginCode without a prior sendLoginCode throws InvalidConfig`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi)

        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        assertThat(rainApi.verifyOtpCalls).isEmpty()
        assertThat(turnkey.createdDeviceKeys).isEmpty()
    }

    // ---------- the confirm: login first, sign-up on a 404 ----------

    @Test
    fun `a returning user logs in with a device key, the encrypted code, verify, login, and the session stored under a fresh key`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onStoreSession = { turnkey.authenticate() }
        val rainApi = MockRainApiAuth()
        rainApi.initOtp = { RainApiOtpChallenge("otp-1", "bundle-1") }
        val controller = controller(turnkey, rainApi)

        controller.sendLoginCode(email)
        controller.confirmLoginCode(" 123456 ")

        // The device key is created once and lives on: it is the session's key pair.
        assertThat(turnkey.createdDeviceKeys).containsExactly("device-key-1")
        assertThat(turnkey.liveDeviceKeys).containsExactly("device-key-1")
        val encrypted = turnkey.encryptOtpCodeCalls.single()
        assertThat(encrypted).isEqualTo(MockTurnkey.EncryptOtpCodeCall("123456", "bundle-1", "device-key-1"))
        val verify = rainApi.verifyOtpCalls.single()
        assertThat(verify).isEqualTo(MockRainApiAuth.VerifyOtpCall("wat_1", "otp-1", "encrypted(123456,bundle-1,device-key-1)"))
        assertThat(turnkey.loginSignatureCalls).containsExactly(MockRainApiAuth.DEFAULT_VERIFICATION_TOKEN)
        val login = rainApi.loginCalls.single()
        assertThat(login.token).isEqualTo("wat_1")
        assertThat(login.verificationToken).isEqualTo(MockRainApiAuth.DEFAULT_VERIFICATION_TOKEN)
        assertThat(login.clientSignature).isEqualTo(turnkey.stubbedClientSignature)
        assertThat(login.subOrganizationId).isNull()
        // A returning user: no sign-up, no sign-up signature.
        assertThat(rainApi.signupCalls).isEmpty()
        assertThat(turnkey.signUpSignatureCalls).isEmpty()
        val stored = turnkey.storeSessionCalls.single()
        assertThat(stored.jwt).isEqualTo(MockRainApiAuth.DEFAULT_SESSION_JWT)
        assertThat(stored.sessionKey).startsWith("rain-turnkey-")
        // The vendor auto-selected the first session, so no redundant re-select.
        assertThat(turnkey.selectSessionCalls).isEmpty()
        assertThat(turnkey.selectedSessionKey).isEqualTo(stored.sessionKey)
        assertThat(turnkey.createWalletCalls).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(turnkey.clearSessionCalls).isEmpty()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `a first login signs up after a 404 on login, naming the canonical contact, then logs in on the new sub-organization`() =
        runTest {
            val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
            turnkey.onStoreSession = { turnkey.authenticate() }
            val rainApi = MockRainApiAuth()
            rainApi.login = MockRainApiAuth.answers({ throw MockRainApiAuth.noWallet() }, { MockRainApiAuth.defaultLogin("sub-9") })
            rainApi.signup = { RainApiSignup("sub-9") }
            val controller = controller(turnkey, rainApi)

            controller.sendLoginCode(LoginContact.Email("  User@Example.COM "))
            controller.confirmLoginCode("123456")

            // The sign-up signature names the lowercased contact: the account's identity, the
            // string the vendor matches case-sensitively.
            assertThat(turnkey.signUpSignatureCalls).containsExactly(
                MockTurnkey.SignUpSignatureCall(MockRainApiAuth.DEFAULT_VERIFICATION_TOKEN, "user@example.com", OtpChannel.EMAIL)
            )
            val signup = rainApi.signupCalls.single()
            assertThat(signup.token).isEqualTo("wat_1")
            assertThat(signup.verificationToken).isEqualTo(MockRainApiAuth.DEFAULT_VERIFICATION_TOKEN)
            assertThat(rainApi.loginCalls.map { it.subOrganizationId }).containsExactly(null, "sub-9").inOrder()
            // One verification token through all three legs, one login signature reused.
            assertThat(rainApi.loginCalls.map { it.verificationToken }.toSet()).containsExactly(MockRainApiAuth.DEFAULT_VERIFICATION_TOKEN)
            assertThat(turnkey.loginSignatureCalls).hasSize(1)
            assertThat(turnkey.storeSessionCalls).hasSize(1)
            assertThat(turnkey.liveDeviceKeys).containsExactly("device-key-1")
            assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
            assertThat(hookCalls).isEqualTo(0)
        }

    @Test
    fun `the token is the bearer of every leg and the contact reaches Rain only inside the signed sign-up message`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onStoreSession = { turnkey.authenticate() }
        val rainApi = MockRainApiAuth()
        rainApi.login = MockRainApiAuth.answers({ throw MockRainApiAuth.noWallet() }, { MockRainApiAuth.defaultLogin() })
        val controller = controller(turnkey, rainApi)

        controller.sendLoginCode(email)
        controller.confirmLoginCode("123456")

        assertThat(rainApi.tokensSent).hasSize(5) // init, verify, login, signup, login
        assertThat(rainApi.tokensSent.toSet()).containsExactly("wat_1")
        // The device writes the contact into one thing it sends: the sign-up message it signs, which
        // names the account's email (ClientSignatureMessageTest pins the real message). No body
        // carries it as a field of its own, and the login legs never name it. (The verification
        // token names it too, but Rain minted that; the device forwards it unread.)
        assertThat(turnkey.signUpSignatureCalls.single().contact).isEqualTo("user@example.com")
        val signUpMessage = rainApi.signupCalls.single().clientSignature.message
        (rainApi.everythingSent - signUpMessage).forEach { sent ->
            assertThat(sent).doesNotContain("example.com")
            assertThat(sent).doesNotContain("@")
        }
        // The host is told the contact once, for the token.
        assertThat(tokenRequests).containsExactly(email)
    }

    @Test
    fun `logging in over a live session switches to a fresh key, evicts cached accounts, then clears the old session`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        val coordinator = coordinator(turnkey)
        val deathsBefore = coordinator.deathEpoch
        val controller = controller(turnkey, coordinator = coordinator)

        controller.sendLoginCode(LoginContact.Email("other@example.com"))
        controller.confirmLoginCode("123456")

        val fresh = turnkey.storeSessionCalls.single().sessionKey
        assertThat(fresh).isNotEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(turnkey.selectSessionCalls).containsExactly(fresh)
        assertThat(turnkey.selectedSessionKey).isEqualTo(fresh)
        // Never a pre-login clear; the previous session goes only after the switch succeeded.
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(turnkey.clearSessionCalls).containsExactly(MockTurnkey.DEFAULT_SESSION_KEY)
        // Active→Active is not a death the watcher notices, so eviction is explicit — host hook silent.
        assertThat(coordinator.deathEpoch).isEqualTo(deathsBefore + 1)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `an Ethereum-only account gets a Solana account added to its existing wallet`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.defaultWallet()), session = null)
        turnkey.onStoreSession = { turnkey.authenticate() }
        val controller = controller(turnkey)

        controller.sendLoginCode(email)
        controller.confirmLoginCode("123456")

        // Added to the wallet Rain resolves, not minted as a second wallet (a second mnemonic).
        assertThat(turnkey.createWalletCalls).isEmpty()
        val added = turnkey.createWalletAccountsCalls.single()
        assertThat(added.walletId).isEqualTo("wallet-id")
        assertThat(added.accounts.map { it.addressFormat }).containsExactly(V1AddressFormat.ADDRESS_FORMAT_SOLANA)
        assertThat(added.accounts.single().curve).isEqualTo(V1Curve.CURVE_ED25519)
        assertThat(added.accounts.single().path).isEqualTo("m/44'/501'/0'/0'")
        assertThat(turnkey.refreshWalletsCallCount).isEqualTo(2)
    }

    @Test
    fun `an organization with no wallet at all gets one wallet holding both accounts`() = runTest {
        // An account created outside the sign-up flow (or predating the wallet-at-signup request).
        val turnkey = MockTurnkey(wallets = emptyList(), session = null)
        turnkey.onStoreSession = { turnkey.authenticate() }
        val controller = controller(turnkey)

        controller.sendLoginCode(email)
        controller.confirmLoginCode("123456")

        val created = turnkey.createWalletCalls.single()
        assertThat(created.walletName).isEqualTo("Wallet")
        assertThat(created.accounts.map { it.addressFormat }).containsExactly(
            V1AddressFormat.ADDRESS_FORMAT_ETHEREUM,
            V1AddressFormat.ADDRESS_FORMAT_SOLANA
        ).inOrder()
        assertThat(created.accounts.map { it.curve }).containsExactly(
            V1Curve.CURVE_SECP256K1,
            V1Curve.CURVE_ED25519
        ).inOrder()
        assertThat(turnkey.createWalletAccountsCalls).isEmpty()
    }

    // ---------- the session switch ----------

    @Test
    fun `ensureAccounts is idempotent and can be re-run to heal a half-provisioned session`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.defaultWallet()))
        turnkey.onCreateWalletAccounts = { turnkey.wallets = listOf(MockTurnkey.walletWithEthAndSolana()) }
        val controller = controller(turnkey)

        controller.ensureAccounts()
        controller.ensureAccounts()

        assertThat(turnkey.createWalletAccountsCalls).hasSize(1)
        assertThat(turnkey.createWalletCalls).isEmpty()
    }

    @Test
    fun `a select failure clears the fresh session and keeps the previous one selected, the hook silent`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        turnkey.selectSessionError = TurnkeyKotlinError.FailedToSetSelectedSession(RuntimeException("io"))
        val coordinator = coordinator(turnkey)
        val controller = controller(turnkey, coordinator = coordinator)
        coordinator.startMonitoring(backgroundScope)
        runCurrent()
        controller.sendLoginCode(LoginContact.Email("other@example.com"))

        expectThrows<RainError> { controller.confirmLoginCode("123456") }
        runCurrent()

        // The fresh session never took effect and is dropped, its key pair with it. Rain's login
        // revoked nothing, so the previous session is as live as before and stays the device's.
        val fresh = turnkey.storeSessionCalls.single().sessionKey
        assertThat(turnkey.clearSessionCalls).containsExactly(fresh)
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(turnkey.selectedSessionKey).isEqualTo(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(turnkey.session).isNotNull()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        assertThat(controller.hasActiveSession()).isTrue()
        assertThat(turnkey.createWalletCalls).isEmpty()
        assertThat(hookCalls).isEqualTo(0)

        // A genuine death later still notifies the host.
        turnkey.session = null
        turnkey.authStateFlow.value = AuthState.unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `a select failure after the selection took effect keeps the new session and clears nothing`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        turnkey.selectSessionAppliesBeforeThrowing = true
        turnkey.selectSessionError = TurnkeyKotlinError.FailedToSetSelectedSession(RuntimeException("refresh 503"))
        val controller = controller(turnkey)
        controller.sendLoginCode(LoginContact.Email("other@example.com"))

        expectThrows<RainError> { controller.confirmLoginCode("123456") }

        val fresh = turnkey.storeSessionCalls.single().sessionKey
        assertThat(turnkey.selectedSessionKey).isEqualTo(fresh)
        assertThat(turnkey.clearSessionCalls).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
    }

    @Test
    fun `a configuration error makes every auth call throw InvalidConfig before touching the vendor, the host or Rain`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val rainApi = MockRainApiAuth()
        val controller = controller(
            turnkey,
            rainApi,
            configurationError = RainError.InvalidConfig("mismatch"),
            passkeyDomain = "passkeys.example.com",
        )
        val activity = mockk<Activity>(relaxed = true)

        expectThrows<RainError.InvalidConfig> { controller.sendLoginCode(email) }
        expectThrows<RainError.InvalidConfig> { controller.sendLoginCode(smsContact) }
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        expectThrows<RainError.InvalidConfig> { controller.loginWithPasskey(activity) }
        expectThrows<RainError.InvalidConfig> { controller.signUpWithPasskey(activity) }
        expectThrows<RainError.InvalidConfig> { controller.addPasskey(activity) }
        expectThrows<RainError.InvalidConfig> { controller.sendContactVerificationCode(email) }
        expectThrows<RainError.InvalidConfig> { controller.confirmContactVerification("123456") }
        expectThrows<RainError.InvalidConfig> { controller.awaitSessionRestore(timeoutMs = 100) }
        expectThrows<RainError.InvalidConfig> { controller.logout() }

        assertThat(turnkey.awaitReadyCallCount).isEqualTo(0)
        assertThat(tokenRequests).isEmpty()
        assertThat(rainApi.tokensSent).isEmpty()
        assertThat(turnkey.createPasskeyCalls).isEmpty()
        assertThat(turnkey.createdDeviceKeys).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
    }

    // ---------- state ----------

    @Test
    fun `authState maps every session state and collapses Expired into Unauthenticated`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val controller = controller(turnkey)
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)

        turnkey.authStateFlow.value = AuthState.loading
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Loading)

        turnkey.authenticate()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        assertThat(controller.authState.first()).isEqualTo(TurnkeyAuthState.Authenticated)

        turnkey.session = MockTurnkey.expiredSession()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        assertThat(controller.authState.first()).isEqualTo(TurnkeyAuthState.Unauthenticated)
    }

    @Test
    fun `hasActiveSession is true only for a session with comfortably more than the floor left`() {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)
        assertThat(controller.hasActiveSession()).isTrue()

        turnkey.session = MockTurnkey.nearExpirySession(remainingSeconds = 10)
        assertThat(controller.hasActiveSession()).isFalse()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)

        turnkey.session = MockTurnkey.expiredSession()
        assertThat(controller.hasActiveSession()).isFalse()

        turnkey.session = null
        assertThat(controller.hasActiveSession()).isFalse()
    }

    @Test
    fun `awaitSessionRestore returns once the restore settles and reports the session`() = runTest {
        val turnkey = MockTurnkey(session = null)
        turnkey.authStateFlow.value = AuthState.loading
        val controller = controller(turnkey)
        launch {
            delay(50)
            turnkey.authenticate()
        }

        controller.awaitSessionRestore(timeoutMs = 5_000)

        assertThat(turnkey.awaitReadyCallCount).isEqualTo(1)
        assertThat(controller.hasActiveSession()).isTrue()
    }

    @Test
    fun `awaitSessionRestore returns without throwing when the restore does not settle in time`() = runTest {
        val turnkey = MockTurnkey(session = null)
        turnkey.authStateFlow.value = AuthState.loading
        val controller = controller(turnkey)

        controller.awaitSessionRestore(timeoutMs = 250)

        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Loading)
        assertThat(controller.hasActiveSession()).isFalse()
    }

    @Test
    fun `a vendor that failed to initialize surfaces as InternalError, not a raw exception`() = runTest {
        val turnkey = MockTurnkey(session = null)
        turnkey.awaitReadyError = IllegalStateException("init failed")
        val controller = controller(turnkey)

        expectThrows<RainError.InternalError> { controller.awaitSessionRestore(timeoutMs = 100) }
        expectThrows<RainError.InternalError> { controller.sendLoginCode(email) }
        assertThat(tokenRequests).isEmpty()
    }

    @Test
    fun `a cancelled first initialization replayed by the vendor surfaces as InternalError, not a cancellation`() = runTest {
        // The vendor stores whatever ended its first init, a caller's cancellation included, in its
        // process-wide readiness signal and replays it to every later caller.
        val turnkey = MockTurnkey(session = null)
        turnkey.awaitReadyError = CancellationException("an earlier caller was cancelled during init")
        val controller = controller(turnkey)

        expectThrows<RainError.InternalError> { controller.awaitSessionRestore(timeoutMs = 100) }
        expectThrows<RainError.InternalError> { controller.sendLoginCode(email) }
    }

    @Test
    fun `a vendor that never becomes ready surfaces as InternalError after the bound instead of hanging`() = runTest {
        val turnkey = MockTurnkey(session = null)
        turnkey.awaitReadyGate = CompletableDeferred()
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi)

        expectThrows<RainError.InternalError> { controller.sendLoginCode(email) }
        assertThat(rainApi.initOtpCalls).isEmpty()
    }

    // ---------- logout ----------

    @Test
    fun `logout clears the selected session and drops the pending code without firing the re-auth hook`() = runTest {
        val turnkey = MockTurnkey()
        val coordinator = coordinator(turnkey)
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi, coordinator = coordinator)
        // backgroundScope: the watcher re-arms a real-clock expiry timer, so a foreground scope
        // would keep runTest advancing virtual time forever once the test body finishes.
        coordinator.startMonitoring(backgroundScope)
        runCurrent()
        controller.sendLoginCode(email)

        controller.logout()
        // Read before the scheduler runs anything else. The vendor flips its auth state, selected
        // key and session inline inside clearSession, and the coordinator derives these two answers
        // on demand, so nothing may lag behind the return.
        assertThat(controller.hasActiveSession()).isFalse()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        runCurrent()

        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(1)
        assertThat(turnkey.session).isNull()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        assertThat(hookCalls).isEqualTo(0)
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        assertThat(rainApi.verifyOtpCalls).isEmpty()

        // A genuine death after a re-login still notifies the host.
        turnkey.authenticate()
        runCurrent()
        turnkey.session = null
        turnkey.authStateFlow.value = AuthState.unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `logout drops the pending code even when the clear fails, and a later genuine death still notifies`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.clearSelectedSessionError = TurnkeyKotlinError.FailedToClearSession(RuntimeException("boom"))
        val coordinator = coordinator(turnkey)
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi, coordinator = coordinator)
        coordinator.startMonitoring(backgroundScope)
        runCurrent()
        controller.sendLoginCode(email)

        expectThrows<RainError> { controller.logout() }

        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        assertThat(rainApi.verifyOtpCalls).isEmpty()
        // The suppression armed for a logout that never happened must not swallow a real death.
        turnkey.session = null
        turnkey.authStateFlow.value = AuthState.unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(1)
    }

    // ---------- a session revoked from another device ----------

    @Test
    fun `a refresh the backend refused signs the device out and the reads flip before the scheduler runs`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.refreshSessionError = revokedSessionRefreshFailure()
        val coordinator = coordinator(turnkey)
        val controller = controller(turnkey, coordinator = coordinator)
        coordinator.startMonitoring(backgroundScope)
        runCurrent()
        assertThat(controller.hasActiveSession()).isTrue()

        // The provider's refreshSession() is this call; a wallet call that hits a 401 ends the same way.
        expectThrows<RainError.TokenExpired> { coordinator.refreshNow() }
        // Read before the scheduler runs anything else, as after logout: the vendor flips its state
        // inline inside clearSession and the controller derives both answers on demand.
        assertThat(controller.hasActiveSession()).isFalse()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        assertThat(turnkey.selectedSessionKey).isNull()
        runCurrent()

        assertThat(turnkey.clearSessionCalls).containsExactly(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(turnkey.session).isNull()
        assertThat(controller.authState.first()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        // Unlike a logout, this is a death the host has to recover from: the hook fires, once.
        assertThat(hookCalls).isEqualTo(1)

        // A fresh login works as a first login and re-arms the hook for a later death.
        controller.sendLoginCode(email)
        turnkey.onStoreSession = { turnkey.authenticate() }
        controller.confirmLoginCode("123456")
        runCurrent()
        assertThat(controller.hasActiveSession()).isTrue()
        turnkey.session = null
        turnkey.authStateFlow.value = AuthState.unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(2)
    }

    @Test
    fun `logout with no session clears nothing and leaves the re-auth hook armed for later`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val coordinator = coordinator(turnkey)
        val controller = controller(turnkey, coordinator = coordinator)
        coordinator.startMonitoring(backgroundScope)
        runCurrent()

        controller.logout()

        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        // A later login followed by a genuine death notifies the host.
        turnkey.authenticate()
        runCurrent()
        turnkey.session = null
        turnkey.authStateFlow.value = AuthState.unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(1)
    }

    // ---------- errors and cancellation ----------

    @Test
    fun `an arbitrary vendor exception surfaces as RainError from every public method`() = runTest {
        val turnkey = MockTurnkey(wallets = emptyList(), session = null)
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi)

        turnkey.awaitReadyError = null
        rainApi.initOtp = { throw IllegalStateException("boom") }
        expectThrows<RainError> { controller.sendLoginCode(email) }
        expectThrows<RainError> { controller.sendLoginCode(smsContact) }
        rainApi.initOtp = { RainApiOtpChallenge("otp-id", "bundle") }
        controller.sendLoginCode(email)

        turnkey.createDeviceKeyError = RuntimeException("keystore")
        expectThrows<RainError> { controller.confirmLoginCode("123456") }
        turnkey.createDeviceKeyError = null
        turnkey.encryptOtpCodeError = RuntimeException("crypto")
        expectThrows<RainError> { controller.confirmLoginCode("123456") }
        turnkey.encryptOtpCodeError = null
        turnkey.clientSignatureError = RuntimeException("stamper")
        expectThrows<RainError> { controller.confirmLoginCode("123456") }
        turnkey.clientSignatureError = null

        // That last failure came after the verify, so the code is spent: a new challenge first.
        controller.sendLoginCode(email)
        turnkey.onStoreSession = { turnkey.authenticate() }
        turnkey.createWalletError = RuntimeException("boom")
        expectThrows<RainError> { controller.confirmLoginCode("123456") }

        turnkey.clearSelectedSessionError = RuntimeException("boom")
        expectThrows<RainError> { controller.logout() }
    }

    @Test
    fun `a vendor error that merely wraps a cancellation while the caller is active is a RainError`() = runTest {
        val turnkey = MockTurnkey(session = null)
        turnkey.storeSessionError = TurnkeyKotlinError.FailedToCreateSession(CancellationException("inner job"))
        val controller = controller(turnkey)
        controller.sendLoginCode(email)

        val thrown = expectThrows<RainError> { controller.confirmLoginCode("123456") }

        assertThat(thrown).isInstanceOf(RainError.ProviderError::class.java)
    }

    // ---------- lifecycle ----------

    @Test
    fun `every auth method on a closed controller throws InvalidConfig and its state reads inert`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey, passkeyDomain = "passkeys.example.com")
        val activity = mockk<Activity>(relaxed = true)

        controller.close()

        expectThrows<RainError.InvalidConfig> { controller.sendLoginCode(email) }
        expectThrows<RainError.InvalidConfig> { controller.sendLoginCode(smsContact) }
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        expectThrows<RainError.InvalidConfig> { controller.loginWithPasskey(activity) }
        expectThrows<RainError.InvalidConfig> { controller.signUpWithPasskey(activity) }
        expectThrows<RainError.InvalidConfig> { controller.logout() }
        expectThrows<RainError.InvalidConfig> { controller.awaitSessionRestore(timeoutMs = 100) }
        assertThat(controller.hasActiveSession()).isFalse()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(tokenRequests).isEmpty()
    }

    @Test
    fun `close emits Unauthenticated on authState while the vendor session is still active`() = runTest {
        val turnkey = MockTurnkey()
        val controller = controller(turnkey)
        assertThat(controller.authState.first()).isEqualTo(TurnkeyAuthState.Authenticated)

        controller.close()

        // The vendor session did not change; the emission comes from the close itself.
        assertThat(controller.authState.first()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        assertThat(turnkey.authStateFlow.value).isNotNull()
    }
}
