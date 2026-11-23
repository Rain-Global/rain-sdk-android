package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import com.turnkey.core.models.errors.TurnkeyKotlinError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * The confirm's outcomes for [TurnkeyManagedAuthController] over the email channel: what the verify
 * step keeps and what the legs after it spend, the wallet auth token's one refetch per leg, the
 * host's token provider failing, and a cancelled confirm. Each failure exit asserts the four reads
 * a host makes right after the throw ([assertLocalState]). Split from [TurnkeyManagedAuthTest],
 * which keeps the code request, the happy paths, the session switch, state, logout and lifecycle;
 * same fixtures, same JDK 24 gate, same reset of the process-global configurator around every test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnkeyManagedCodeOutcomeTest {

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

    /** SMS-specific tests live in [TurnkeyManagedAuthSmsTest]. */
    private val email = LoginContact.Email("user@example.com")

    /**
     * The four reads a host makes right after a failed exit, before the scheduler runs anything
     * else: the selected key, `hasActiveSession()`, `currentAuthState()` and a silent re-auth hook.
     */
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

    // ---------- the verify step keeps the challenge; what follows spends it ----------

    @Test
    fun `a rejected code surfaces InvalidLoginCode, keeps the challenge, deletes the device key and never touches the live session`() =
        runTest {
            val turnkey = MockTurnkey()
            val rainApi = MockRainApiAuth()
            rainApi.verifyOtp = MockRainApiAuth.answers({ throw MockRainApiAuth.wrongCode() }, { RainApiVerification("vt") })
            val controller = controller(turnkey, rainApi)
            controller.sendLoginCode(email)

            val refused = expectThrows<RainError.InvalidLoginCode> { controller.confirmLoginCode("000000") }

            assertThat(refused.errorCode).isEqualTo(RainErrorCode.INVALID_LOGIN_CODE)
            assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
            assertThat(turnkey.storeSessionCalls).isEmpty()
            assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
            assertThat(turnkey.clearSessionCalls).isEmpty()
            assertThat(turnkey.selectSessionCalls).isEmpty()
            assertThat(turnkey.session).isNotNull()
            assertLocalState(controller, turnkey, selectedKey = MockTurnkey.DEFAULT_SESSION_KEY, active = true, auth = TurnkeyAuthState.Authenticated)
            assertThat(rainApi.loginCalls).isEmpty()

            // The challenge survives a rejected code so the user can simply retype it, under a new device key.
            controller.confirmLoginCode("123456")
            assertThat(rainApi.initOtpCalls).hasSize(1)
            assertThat(rainApi.verifyOtpCalls.map { it.otpId }.toSet()).hasSize(1)
            assertThat(turnkey.createdDeviceKeys).containsExactly("device-key-1", "device-key-2").inOrder()
            assertThat(turnkey.liveDeviceKeys).containsExactly("device-key-2")
        }

    @Test
    fun `a 5xx or a transport failure on the verify maps to its own code, keeps the challenge for a retry and leaves the device signed out`() =
        runTest {
            val rows = listOf<Pair<Throwable, Class<out RainError>>>(
                MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 503, MockRainApiAuth.VERIFY_PATH) to RainError.ProviderError::class.java,
                RainError.NetworkError("Rain API ${MockRainApiAuth.VERIFY_PATH}: SocketTimeoutException") to RainError.NetworkError::class.java,
            )
            rows.forEach { (failure, expected) ->
                val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
                turnkey.onStoreSession = { turnkey.authenticate() }
                val rainApi = MockRainApiAuth()
                rainApi.verifyOtp = MockRainApiAuth.answers({ throw failure }, { RainApiVerification("vt") })
                val controller = controller(turnkey, rainApi)
                controller.sendLoginCode(email)

                val thrown = expectThrows<RainError> { controller.confirmLoginCode("123456") }

                assertWithMessage(failure.javaClass.simpleName).that(thrown).isInstanceOf(expected)
                assertThat(thrown).hasMessageThat().doesNotContain("example.com")
                assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
                signedOut(controller, turnkey)

                controller.confirmLoginCode("123456")
                assertThat(rainApi.initOtpCalls).hasSize(1)
                assertThat(rainApi.verifyOtpCalls).hasSize(2)
                assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
            }
        }

    @Test
    fun `an unreadable verify answer is InternalError and drops the challenge, since Rain accepted the code`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val rainApi = MockRainApiAuth()
        rainApi.verifyOtp = { throw RainError.InternalError("Rain API ${MockRainApiAuth.VERIFY_PATH}: unreadable answer (JSONException)") }
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(email)

        expectThrows<RainError.InternalError> { controller.confirmLoginCode("123456") }

        assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
        signedOut(controller, turnkey)
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        assertThat(rainApi.verifyOtpCalls).hasSize(1)
    }

    @Test
    fun `a 400 on the login, or a 409 on the sign-up, drops the challenge, deletes the device key and leaves the device signed out`() =
        runTest {
            val rows = listOf<Pair<String, (MockRainApiAuth) -> Unit>>(
                "a 400 on the login" to { api -> api.login = { throw MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 400) } },
                "a 409 on the sign-up" to { api ->
                    api.login = { throw MockRainApiAuth.noWallet() }
                    api.signup = { throw MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 409, MockRainApiAuth.SIGNUP_PATH) }
                },
            )
            rows.forEach { (name, arrange) ->
                val turnkey = MockTurnkey(session = null)
                val rainApi = MockRainApiAuth()
                arrange(rainApi)
                val controller = controller(turnkey, rainApi)
                controller.sendLoginCode(email)

                val thrown = expectThrows<RainError.ProviderError> { controller.confirmLoginCode("123456") }

                assertWithMessage(name).that(thrown).isNotInstanceOf(RainError.InvalidLoginCode::class.java)
                assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
                assertThat(turnkey.storeSessionCalls).isEmpty()
                assertThat(rainApi.loginCalls).hasSize(1) // no SDK retry loop
                signedOut(controller, turnkey)
                // The verification token cannot be reused: the next attempt needs a new code.
                expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
                assertThat(rainApi.verifyOtpCalls).hasSize(1)
            }
        }

    @Test
    fun `a login that fails after Rain's sign-up drops the challenge, and the next code logs in with no second sign-up`() = runTest {
        // The account now exists on Rain with nothing stored on the device: the exit the
        // "Sessions and partial failure" rule names, and the one a second sign-up must not repeat.
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        val rainApi = MockRainApiAuth()
        rainApi.login = MockRainApiAuth.answers(
            { throw MockRainApiAuth.noWallet() },
            { throw MockRainApiAuth.refusal(RainApiAuthFailure.Kind.Refused, 503) },
            { MockRainApiAuth.defaultLogin() },
        )
        rainApi.signup = { RainApiSignup("sub-9") }
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(email)

        expectThrows<RainError.ProviderError> { controller.confirmLoginCode("123456") }

        assertThat(rainApi.signupCalls).hasSize(1)
        assertThat(rainApi.loginCalls.map { it.subOrganizationId }).containsExactly(null, "sub-9").inOrder()
        assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
        assertThat(turnkey.storeSessionCalls).isEmpty()
        signedOut(controller, turnkey)
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }

        // A fresh code logs in as a returning user: Rain's login finds the account, so no sign-up runs.
        turnkey.onStoreSession = { turnkey.authenticate() }
        controller.sendLoginCode(email)
        controller.confirmLoginCode("654321")
        assertThat(rainApi.signupCalls).hasSize(1)
        assertThat(rainApi.loginCalls.last().subOrganizationId).isNull()
        assertThat(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `a sign-up signature that fails after the 404 is a RainError with no sign-up sent, the key deleted and the challenge dropped`() =
        runTest {
            val turnkey = MockTurnkey(session = null)
            turnkey.signUpSignatureError = RuntimeException("stamper")
            val rainApi = MockRainApiAuth()
            rainApi.login = { throw MockRainApiAuth.noWallet() }
            val controller = controller(turnkey, rainApi)
            controller.sendLoginCode(email)

            expectThrows<RainError.ProviderError> { controller.confirmLoginCode("123456") }

            assertThat(turnkey.loginSignatureCalls).hasSize(1)
            assertThat(rainApi.signupCalls).isEmpty()
            assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
            signedOut(controller, turnkey)
            expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        }

    @Test
    fun `a failure storing the session drops the challenge and deletes the device key`() = runTest {
        val turnkey = MockTurnkey(session = null)
        // Rain logged the user in, then the vendor failed before storing the session: the token is spent.
        turnkey.storeSessionError = TurnkeyKotlinError.FailedToCreateSession(RuntimeException("io"))
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(email)

        val thrown = expectThrows<RainError> { controller.confirmLoginCode("123456") }

        assertThat(thrown).isNotInstanceOf(RainError.InvalidLoginCode::class.java)
        assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
        // Whether the vendor stored before failing is unknowable from outside, so the fresh key is cleared either way.
        assertThat(turnkey.clearSessionCalls).containsExactly(turnkey.storeSessionCalls.single().sessionKey)
        signedOut(controller, turnkey)
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        assertThat(rainApi.verifyOtpCalls).hasSize(1)
    }

    @Test
    fun `a store that failed after the vendor selected the session keeps the session and its device key`() = runTest {
        // A fresh install: the vendor stored and selected the session, then its refresh over the
        // network failed inside createSession. The device is signed in and stamps with that key.
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.storeSessionAppliesBeforeThrowing = true
        turnkey.onStoreSession = { turnkey.authenticate() }
        turnkey.storeSessionError = TurnkeyKotlinError.FailedToCreateSession(RuntimeException("refresh failed"))
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(email)

        val thrown = expectThrows<RainError> { controller.confirmLoginCode("123456") }

        assertThat(thrown).isNotInstanceOf(RainError.InvalidLoginCode::class.java)
        val fresh = turnkey.storeSessionCalls.single().sessionKey
        assertThat(turnkey.session).isNotNull()
        assertThat(turnkey.liveDeviceKeys).containsExactly("device-key-1")
        assertThat(turnkey.clearSessionCalls).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertLocalState(controller, turnkey, selectedKey = fresh, active = true, auth = TurnkeyAuthState.Authenticated)
        // The code is spent either way.
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
    }

    @Test
    fun `a store that failed over a live session clears the fresh session and its key and keeps the previous one`() = runTest {
        // Over a live session the vendor only stores: the fresh session sits unselected in the
        // registry with its key, and a failure there leaves the previous session as it was.
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        turnkey.storeSessionAppliesBeforeThrowing = true
        turnkey.storeSessionError = TurnkeyKotlinError.FailedToCreateSession(RuntimeException("io"))
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(LoginContact.Email("other@example.com"))

        val thrown = expectThrows<RainError> { controller.confirmLoginCode("123456") }

        assertThat(thrown).isNotInstanceOf(RainError.InvalidLoginCode::class.java)
        val fresh = turnkey.storeSessionCalls.single().sessionKey
        assertThat(turnkey.clearSessionCalls).containsExactly(fresh)
        assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertLocalState(controller, turnkey, selectedKey = MockTurnkey.DEFAULT_SESSION_KEY, active = true, auth = TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `a cancellation that lands after the vendor selected the session keeps it and its device key`() = runTest {
        // A fresh install: the vendor stored and selected the session and was refreshing over the
        // network when the caller went away. The signed-in session and its key stay as they are.
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        val refreshing = CompletableDeferred<Unit>()
        turnkey.onStoreSession = {
            turnkey.authenticate()
            refreshing.await()
        }
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(email)

        var caught: Throwable? = null
        val job = launch {
            try {
                controller.confirmLoginCode("123456")
            } catch (t: Throwable) {
                caught = t
                throw t
            }
        }
        runCurrent()
        job.cancelAndJoin()

        assertThat(caught).isInstanceOf(CancellationException::class.java)
        assertThat(caught).isNotInstanceOf(RainError::class.java)
        assertThat(turnkey.selectedSessionKey).isEqualTo(turnkey.storeSessionCalls.single().sessionKey)
        assertThat(turnkey.liveDeviceKeys).containsExactly("device-key-1")
        assertThat(turnkey.clearSessionCalls).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `a provisioning failure throws, keeps the new session and its device key, and drops the spent code`() = runTest {
        val turnkey = MockTurnkey(wallets = emptyList(), session = null)
        turnkey.onStoreSession = { turnkey.authenticate() }
        turnkey.createWalletError = TurnkeyKotlinError.FailedToCreateWallet(RuntimeException("server 500"))
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(email)

        val thrown = expectThrows<RainError> { controller.confirmLoginCode("123456") }

        assertThat(thrown).isNotInstanceOf(RainError.InvalidLoginCode::class.java)
        assertThat(turnkey.session).isNotNull()
        assertThat(turnkey.liveDeviceKeys).containsExactly("device-key-1")
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(turnkey.clearSessionCalls).isEmpty()
        assertLocalState(
            controller,
            turnkey,
            selectedKey = turnkey.storeSessionCalls.single().sessionKey,
            active = true,
            auth = TurnkeyAuthState.Authenticated,
        )
        // The code was consumed by the verify step; a retry cannot re-run it.
        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("123456") }
        assertThat(rainApi.verifyOtpCalls).hasSize(1)
    }

    @Test
    fun `a blank code is a caller error, not a rejected code`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val rainApi = MockRainApiAuth()
        val controller = controller(turnkey, rainApi)
        controller.sendLoginCode(LoginContact.Email("  user@example.com "))

        expectThrows<RainError.InvalidConfig> { controller.confirmLoginCode("   ") }

        // The challenge survives, the contact was normalized once, and no device key was made.
        assertThat(tokenRequests).containsExactly(email)
        assertThat(rainApi.verifyOtpCalls).isEmpty()
        assertThat(turnkey.createdDeviceKeys).isEmpty()
    }

    // ---------- the wallet auth token ----------

    @Test
    fun `a token refused twice is Unauthorized, with the challenge kept on the verify leg and the device key deleted`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val rainApi = MockRainApiAuth()
        rainApi.verifyOtp = { throw MockRainApiAuth.tokenRejected(MockRainApiAuth.VERIFY_PATH) }
        var minted = 0
        val controller = controller(turnkey, rainApi, authTokenProvider = { "wat_${++minted}" })
        controller.sendLoginCode(email)

        val refused = expectThrows<RainError.Unauthorized> { controller.confirmLoginCode("123456") }

        assertThat(refused.errorCode).isEqualTo(RainErrorCode.UNAUTHORIZED)
        assertThat(refused).hasMessageThat().doesNotContain("example.com")
        assertThat(rainApi.verifyOtpCalls).hasSize(2)
        assertThat(tokenRequests).hasSize(2)
        assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
        signedOut(controller, turnkey)
        // The verify never answered 200, so the challenge is kept: the next confirm tries again with the newest token.
        rainApi.verifyOtp = { RainApiVerification("vt") }
        turnkey.onStoreSession = { turnkey.authenticate() }
        controller.confirmLoginCode("123456")
        assertThat(rainApi.verifyOtpCalls.last().token).isEqualTo("wat_2")
        assertThat(rainApi.initOtpCalls).hasSize(1)
    }

    @Test
    fun `a refused or spent token is renewed once on each code leg, and the newest token carries into the next leg`() = runTest {
        val refusals = listOf<Pair<String, (String) -> RainApiAuthFailure>>(
            "a 401" to { path -> MockRainApiAuth.tokenRejected(path) },
            "a 409 for a spent token" to { path -> MockRainApiAuth.refusal(RainApiAuthFailure.Kind.TokenUsed, 409, path) },
        )
        refusals.forEach { (name, refuse) ->
            tokenRequests.clear()
            val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
            turnkey.onStoreSession = { turnkey.authenticate() }
            val rainApi = MockRainApiAuth()
            rainApi.initOtp = MockRainApiAuth.answers({ throw refuse(MockRainApiAuth.INIT_PATH) }, { RainApiOtpChallenge("otp-1", "bundle-1") })
            rainApi.verifyOtp = MockRainApiAuth.answers({ throw refuse(MockRainApiAuth.VERIFY_PATH) }, { RainApiVerification("vt") })
            rainApi.login = MockRainApiAuth.answers(
                { throw refuse(MockRainApiAuth.LOGIN_PATH) },
                { throw MockRainApiAuth.noWallet() },
                { MockRainApiAuth.defaultLogin() },
            )
            rainApi.signup = MockRainApiAuth.answers({ throw refuse(MockRainApiAuth.SIGNUP_PATH) }, { RainApiSignup("sub-9") })
            var minted = 0
            val controller = controller(turnkey, rainApi, authTokenProvider = { "wat_${++minted}" })

            controller.sendLoginCode(email)
            assertWithMessage(name).that(rainApi.initOtpCalls).containsExactly("wat_1", "wat_2").inOrder()

            controller.confirmLoginCode("123456")

            // Each refused leg sends twice, the second time under the token the host minted for it,
            // and the leg after starts with that token; the login after the sign-up is not refused
            // and sends once.
            assertWithMessage(name).that(rainApi.verifyOtpCalls.map { it.token }).containsExactly("wat_2", "wat_3").inOrder()
            assertWithMessage(name).that(rainApi.loginCalls.map { it.token }).containsExactly("wat_3", "wat_4", "wat_5").inOrder()
            assertWithMessage(name).that(rainApi.loginCalls.map { it.subOrganizationId }).containsExactly(null, null, "sub-9").inOrder()
            assertWithMessage(name).that(rainApi.signupCalls.map { it.token }).containsExactly("wat_4", "wat_5").inOrder()
            assertWithMessage(name).that(tokenRequests).containsExactly(email, email, email, email, email).inOrder()
            assertWithMessage(name).that(controller.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        }
    }

    @Test
    fun `an invalid-tenant 401 and a 403 are Unauthorized and never refetch the token`() = runTest {
        for (status in listOf(401, 403)) {
            tokenRequests.clear()
            val turnkey = MockTurnkey(session = null)
            val rainApi = MockRainApiAuth()
            rainApi.verifyOtp = { throw MockRainApiAuth.forbidden(MockRainApiAuth.VERIFY_PATH, status) }
            val controller = controller(turnkey, rainApi)
            controller.sendLoginCode(email)

            val refused = expectThrows<RainError.Unauthorized> { controller.confirmLoginCode("123456") }

            assertThat(refused.errorCode).isEqualTo(RainErrorCode.UNAUTHORIZED)
            assertThat(rainApi.verifyOtpCalls).hasSize(1)
            // One mint, at the send; a new token would fail the same way.
            assertThat(tokenRequests).hasSize(1)
            assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
            signedOut(controller, turnkey)
        }
    }

    @Test
    fun `the host's token provider failing is InvalidConfig, or NetworkError when it could not reach its backend, naming the class only`() =
        runTest {
            val turnkey = MockTurnkey(session = null)
            val rainApi = MockRainApiAuth()
            val failing = controller(turnkey, rainApi, authTokenProvider = { throw IllegalStateException("no key for user@example.com") })

            val thrown = expectThrows<RainError.InvalidConfig> { failing.sendLoginCode(email) }

            assertThat(thrown).hasMessageThat().contains("IllegalStateException")
            assertThat(thrown).hasMessageThat().contains("authTokenProvider")
            assertThat(thrown).hasMessageThat().doesNotContain("example.com")
            assertThat(thrown.cause).isNull()
            assertThat(rainApi.initOtpCalls).isEmpty()
            expectThrows<RainError.InvalidConfig> { failing.confirmLoginCode("123456") }

            // An IOException anywhere in the cause chain is the host's own backend out of reach: a
            // retryable RAIN_301, still naming the class only.
            val offline = controller(
                turnkey,
                rainApi,
                authTokenProvider = { throw IllegalStateException("mint for user@example.com", SocketTimeoutException("timeout")) },
            )
            val unreachable = expectThrows<RainError.NetworkError> { offline.sendLoginCode(email) }
            assertThat(unreachable.errorCode).isEqualTo(RainErrorCode.NETWORK_ERROR)
            assertThat(unreachable).hasMessageThat().contains("IllegalStateException")
            assertThat(unreachable).hasMessageThat().doesNotContain("example.com")
            assertThat(unreachable.cause).isNull()

            // A RainError from the host passes through as the host's own signal; a blank token is refused too.
            val hostError = RainError.Unauthorized("backend refused")
            val passing = controller(turnkey, rainApi, authTokenProvider = { throw hostError })
            assertThat(expectThrows<RainError.Unauthorized> { passing.sendLoginCode(email) }).isSameInstanceAs(hostError)
            val blank = controller(turnkey, rainApi, authTokenProvider = { "   " })
            assertThat(expectThrows<RainError.InvalidConfig> { blank.sendLoginCode(email) }).hasMessageThat().contains("blank")
            assertThat(rainApi.initOtpCalls).isEmpty()
        }

    @Test
    fun `a cancellation the host's provider raised while the caller is active is InvalidConfig, and on a refetch spends the code with an error`() =
        runTest {
            val turnkey = MockTurnkey(session = null)
            val rainApi = MockRainApiAuth()
            // The host's own withTimeout around its mint: a TimeoutCancellationException thrown while
            // this coroutine is still active, which must not leave the call as a cancellation.
            val timingOut = controller(turnkey, rainApi, authTokenProvider = { withTimeout(1) { awaitCancellation() } })

            val thrown = expectThrows<RainError.InvalidConfig> { timingOut.sendLoginCode(email) }

            assertThat(thrown).hasMessageThat().contains("TimeoutCancellationException")
            assertThat(rainApi.initOtpCalls).isEmpty()

            // The same failure on the refetch inside the confirm: the verify answered, so the code is
            // spent, and the host gets an error instead of a swallowed cancellation.
            var minted = 0
            val refetching = controller(
                turnkey,
                rainApi,
                authTokenProvider = { if (++minted == 1) "wat_1" else withTimeout(1) { awaitCancellation() } },
            )
            rainApi.login = { throw MockRainApiAuth.tokenRejected(MockRainApiAuth.LOGIN_PATH) }
            refetching.sendLoginCode(email)

            expectThrows<RainError.InvalidConfig> { refetching.confirmLoginCode("123456") }

            assertThat(rainApi.loginCalls).hasSize(1)
            assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
            signedOut(refetching, turnkey)
            expectThrows<RainError.InvalidConfig> { refetching.confirmLoginCode("123456") }
            assertThat(rainApi.verifyOtpCalls).hasSize(1)
        }

    // ---------- cancellation ----------

    @Test
    fun `a cancelled token provider or a cancelled confirm propagates the cancellation, never a mapped error, and the confirm deletes the key`() =
        runTest {
            // Cancelled while the host's provider is suspended: nothing was sent.
            val turnkey = MockTurnkey(session = null)
            val rainApi = MockRainApiAuth()
            val mint = CompletableDeferred<String>()
            val sending = controller(turnkey, rainApi, authTokenProvider = { mint.await() })
            var caughtOnSend: Throwable? = null
            val send = launch {
                try {
                    sending.sendLoginCode(email)
                } catch (t: Throwable) {
                    caughtOnSend = t
                    throw t
                }
            }
            runCurrent()
            send.cancelAndJoin()
            assertThat(caughtOnSend).isInstanceOf(CancellationException::class.java)
            assertThat(caughtOnSend).isNotInstanceOf(RainError::class.java)
            assertThat(rainApi.initOtpCalls).isEmpty()

            // Cancelled while the login leg is suspended: the device key goes, the session store never ran.
            val login = CompletableDeferred<RainApiLogin>()
            rainApi.login = { login.await() }
            val confirming = controller(turnkey, rainApi)
            confirming.sendLoginCode(email)
            var caughtOnConfirm: Throwable? = null
            val confirm = launch {
                try {
                    confirming.confirmLoginCode("123456")
                } catch (t: Throwable) {
                    caughtOnConfirm = t
                    throw t
                }
            }
            runCurrent()
            confirm.cancelAndJoin()

            assertThat(caughtOnConfirm).isInstanceOf(CancellationException::class.java)
            assertThat(caughtOnConfirm).isNotInstanceOf(RainError::class.java)
            assertThat(turnkey.deletedDeviceKeys).containsExactly("device-key-1")
            assertThat(turnkey.storeSessionCalls).isEmpty()
        }
}
