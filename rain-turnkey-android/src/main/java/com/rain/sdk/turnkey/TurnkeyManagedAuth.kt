package com.rain.sdk.turnkey

import android.app.Activity
import android.app.Application
import com.rain.sdk.error.RainError
import com.turnkey.core.TurnkeyContext
import com.turnkey.core.models.AuthConfig
import com.turnkey.core.models.AuthState
import com.turnkey.core.models.Session
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1Curve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration.Companion.milliseconds
import com.turnkey.core.models.TurnkeyConfig as VendorTurnkeyConfig
import kotlin.concurrent.withLock as withJavaLock

/**
 * Guards the vendor's process-wide configuration.
 *
 * `TurnkeyContext` is a Kotlin `object` whose `initSuspend` silently returns when it has already
 * run, so without this guard a host that changed ids — or a context configured outside the SDK —
 * would keep transacting against the first organization with no signal. Configuring again with
 * the same organization id, Rain API base URL and passkey domain is a no-op; with different ones,
 * or over a foreign configuration, it is an error the provider surfaces on every auth call (the
 * app has to relaunch to change them). The base URL is part of the configuration because two
 * providers talking to two Rain deployments would still share the one vendor context and its
 * sessions; the passkey domain because the vendor reads the relying party for its passkey login
 * from there, the cross-platform contract shared by Rain's SDKs; the add-passkey ceremony takes it
 * explicitly. The vendor gets no auth-proxy configuration: the managed login runs against Rain's
 * endpoints, and with a null id the vendor skips its wallet-kit fetch (sdk-kotlin 2.1.0
 * `TurnkeyContext.initSuspend`).
 *
 * Known limit: the foreign-configuration probe reads a field the vendor assigns asynchronously,
 * so a host `TurnkeyContext.init` still in flight from `Application.onCreate` can slip past it.
 */
internal object TurnkeyManagedConfigurator {
    private val mutex = Mutex()

    @Volatile
    private var configuredWith: Triple<String, String, String?>? = null

    /**
     * Whether this SDK ran the vendor's initialization itself. The vendor assigns its context and
     * marks itself initialized before the work that can fail, so after a failed attempt the
     * foreign-init probe reads true although nobody else configured it; this flag keeps that from
     * being reported as "configured outside the SDK".
     */
    @Volatile
    private var initAttempted = false

    private val defaultInit: suspend (Application, String, String?) -> Unit = { app, organizationId, passkeyDomain ->
        // The vendor's own `init` launches `initSuspend` on Dispatchers.Main.immediate. Running it
        // there ourselves keeps its lifecycle-observer registration on the main thread while
        // surfacing a failure to this coroutine instead of an uncaught crash on the vendor's scope.
        // NonCancellable, because the vendor records whatever ends its first init in a process-wide
        // readiness signal and replays it to every later caller: a caller cancelled mid-init must
        // not be what ends it. The cost is that the configure mutex is held across a vendor init
        // that hangs.
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            TurnkeyContext.initSuspend(
                app,
                VendorTurnkeyConfig(
                    organizationId = organizationId,
                    // No auth-proxy configuration id: Rain's endpoints replace the proxy, and a null
                    // id keeps the vendor from fetching a wallet-kit configuration it would not use.
                    // Null when passkeys are off: the code-only configuration the vendor got before.
                    authConfig = passkeyDomain?.let { AuthConfig(rpId = it) },
                ),
            )
        }
    }

    /** `appContext` is a public `lateinit` the vendor assigns only inside `initSuspend`. */
    private val defaultProbe: () -> Boolean = { runCatching { TurnkeyContext.appContext }.isSuccess }

    /** Test seam: replaces the vendor configure call. */
    @Volatile
    internal var initImpl: suspend (Application, String, String?) -> Unit = defaultInit

    /** Test seam: whether the vendor singleton was already initialized, by anyone. */
    @Volatile
    internal var vendorInitializedProbe: () -> Boolean = defaultProbe

    /**
     * Configures the vendor once per process. Returns the error to surface on every auth call
     * when the organization id or the Rain API base URL is blank, either or the passkey domain
     * differs from what the process was configured with, the vendor was configured outside the
     * SDK, or its initialization failed; null when this provider may proceed.
     */
    suspend fun configure(
        application: Application,
        organizationId: String,
        rainApiBaseUrl: String,
        passkeyDomain: String?,
    ): RainError? {
        // Steady state — every auth call re-checks — needs no lock: a stale read only falls through.
        if (configuredWith == Triple(organizationId, rainApiBaseUrl, passkeyDomain)) return null
        return mutex.withLock { configureLocked(application, organizationId, rainApiBaseUrl, passkeyDomain) }
    }

    private suspend fun configureLocked(
        application: Application,
        organizationId: String,
        rainApiBaseUrl: String,
        passkeyDomain: String?,
    ): RainError? {
        val requested = Triple(organizationId, rainApiBaseUrl, passkeyDomain)
        val existing = configuredWith
        return when {
            organizationId.isBlank() || rainApiBaseUrl.isBlank() ->
                RainError.InvalidConfig("organizationId and rainApiBaseUrl must not be blank")
            existing != null -> if (existing == requested) {
                null
            } else {
                RainError.InvalidConfig(
                    "The wallet backend is already configured with different ids, Rain API base URL or passkey " +
                        "domain for this app launch; relaunch the app to change them"
                )
            }
            vendorInitializedProbe() && !initAttempted -> RainError.InvalidConfig(
                "The wallet backend was already configured outside the SDK for this app launch; " +
                    "managed mode has to own that configuration; remove the app's own wallet-backend " +
                    "initialization, or use the bring-your-own provider instead"
            )
            else -> initializeVendor(application, requested)
        }
    }

    /** Runs the vendor's one-shot initialization and records the ids only once it succeeded. */
    @Suppress("TooGenericExceptionCaught") // the vendor's init failure is untyped; every one becomes InternalError
    private suspend fun initializeVendor(application: Application, requested: Triple<String, String, String?>): RainError? {
        initAttempted = true
        try {
            initImpl(application, requested.first, requested.third)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing recorded, so the next call retries. The real vendor treats a second init as a
            // no-op once it has marked itself initialized: that retry then records the ids and the
            // readiness wait surfaces the original failure as the same InternalError.
            return RainError.InternalError(
                "The wallet backend failed to initialize for this app launch; relaunch the app to retry",
                e
            )
        }
        configuredWith = requested
        return null
    }

    internal fun resetForTest() {
        configuredWith = null
        initAttempted = false
        initImpl = defaultInit
        vendorInitializedProbe = defaultProbe
    }
}

/**
 * Owns the managed authentication of a [TurnkeyProvider]: the one-time-code login, email or SMS,
 * against Rain's embedded-wallet endpoints (code send, code verify, sign-up and login) with the
 * vendor's device primitives around them (a device key, the code's encryption, the client
 * signatures, the session store), and its two-code form for an account Rain protects that way
 * ([sendSecondLoginCode], [confirmSecondLoginCode]); account provisioning, session restore and logout; the passkey
 * flows, sign-in, sign-up and add-passkey, when a relying-party domain is configured; and contact
 * attach, a verified email or phone added to the signed-in account as a login method. Every Rain
 * leg carries a wallet auth token the host's backend mints through [authTokenProvider], bound to
 * the tenant and to the contact of the flow; no leg sends the contact as a field of its own: it
 * reaches Rain inside the verification token and the signed sign-up message. Every vendor and
 * Rain failure is mapped to a [RainError] before it surfaces; cancellation
 * is never mapped.
 *
 * Sessions: each login stores its session under a fresh key and then selects it. The vendor's
 * `createSession` rejects only a duplicate *key*, so no stored session has to be cleared before a
 * login, and a rejected code cannot cost the user the session they already have. Once the switch
 * succeeds the previous session is cleared locally. A code login revokes nothing on the server; a
 * passkey login revokes the user's other sessions, the vendor's `invalidateExisting`.
 *
 * One auth operation runs at a time: send, confirm, logout and provisioning all read-then-write
 * the same process-wide vendor state.
 */
@Suppress("TooManyFunctions", "LargeClass") // one mutex serializes the whole auth flow
internal class TurnkeyManagedAuthController
@Suppress("LongParameterList")
constructor(
    private val context: TurnkeyContextProtocol,
    private val rainApi: RainApiAuthClientProtocol,
    /** The host's wallet auth token for the flow's canonical contact, or for none on a passkey sign-up; [authToken] has the contract. */
    private val authTokenProvider: suspend (LoginContact?) -> String,
    private val coordinator: TurnkeySessionCoordinator,
    private val configure: suspend () -> RainError?,
    /** The passkey relying-party domain, already normalized; null means passkeys are off. */
    private val passkeyDomain: String? = null,
    /**
     * The registration of a sign-up Rain left in doubt, for the next [signUpWithPasskey] to resend:
     * only the same credential sent again makes Rain record an account its create finished late.
     * Written under [flowMutex], cleared by a settled answer only, kept across [close].
     */
    private val pendingPasskeySignups: PendingPasskeySignupStore = InMemoryPendingPasskeySignupStore(),
    private val nowEpochSeconds: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
) {
    /**
     * The host's token for one contact, replaced in place after a refused token so the legs that
     * follow in the flow carry the fresh one.
     */
    private class AuthToken(val contact: LoginContact?, @Volatile var value: String)

    private class PendingOtp(val challenge: OtpChallenge, val contact: String, val token: AuthToken)

    private val flowMutex = Mutex()
    private val pendingLock = ReentrantLock()
    private var pendingOtp: PendingOtp? = null

    /**
     * The verification code for a contact being attached, kept apart from [pendingOtp] so a login
     * cannot consume a verification code and a verification cannot consume a login code.
     */
    private var pendingContactOtp: PendingOtp? = null

    /**
     * A two-code login between its codes: the token Rain keys the attempt on, the device key both
     * codes are encrypted to and the session is issued to, the channel of the second code, the
     * login message to sign with that key, and the second code's challenge once one was sent.
     * Replaced whole under [pendingLock].
     */
    private class PendingSecondCode(
        val token: AuthToken,
        val deviceKey: String,
        val channel: OtpChannel,
        val clientSignatureMessage: String,
        val challenge: OtpChallenge? = null,
    ) {
        fun withChallenge(challenge: OtpChallenge) = PendingSecondCode(token, deviceKey, channel, clientSignatureMessage, challenge)
    }

    private var pendingSecondCode: PendingSecondCode? = null

    /** What the first code's verify led to: Rain's session, or the second-code outcome for the host. */
    private sealed interface FirstCodeResult {
        class Session(val login: RainApiLogin) : FirstCodeResult

        class SecondCode(val outcome: TurnkeyLoginOutcome.SecondCodeRequired) : FirstCodeResult
    }

    private val closed = AtomicBoolean(false)
    private val closedFlow = MutableStateFlow(false)

    /**
     * [TurnkeySessionCoordinator.sessionStates] as a login screen sees them. Combined with the
     * closed flag so [close] itself emits [TurnkeyAuthState.Unauthenticated]; the session flow alone
     * would stay silent until the session next changed.
     */
    val authState: Flow<TurnkeyAuthState> =
        combine(coordinator.sessionStates, closedFlow) { state, isClosed ->
            if (isClosed) TurnkeyAuthState.Unauthenticated else state.toAuthState()
        }.distinctUntilChanged()

    fun currentAuthState(): TurnkeyAuthState =
        if (closed.get()) TurnkeyAuthState.Unauthenticated else coordinator.currentState().toAuthState()

    /**
     * True when a session is live with more than [SESSION_MIN_REMAINING_SECONDS] left, so the
     * one-time-code step can be skipped. [authState] answers "is there a session"; this answers
     * "can the flow skip the code" — the floor keeps a session from dying during its first call.
     * The answer comes from the stored session, and a session Turnkey refuses to refresh is cleared
     * with that failure, so this reads false from then on; a refresh that failed for another reason
     * (offline, a 5xx) leaves the session stored. Until a refresh asks, a session revoked from
     * another device still reads as active.
     */
    fun hasActiveSession(): Boolean {
        if (closed.get()) return false
        val state = coordinator.currentState()
        return state is TurnkeySessionState.Active &&
            state.expiresAtEpochSeconds - nowEpochSeconds() > SESSION_MIN_REMAINING_SECONDS
    }

    /**
     * Waits for the vendor to initialize and restore any persisted session, or for [timeoutMs] to
     * elapse — a timeout returns normally and leaves [authState] at [TurnkeyAuthState.Loading].
     * Throws [RainError.InvalidConfig] on a configuration mismatch and [RainError.InternalError]
     * when the vendor's initialization itself failed.
     */
    suspend fun awaitSessionRestore(timeoutMs: Long = DEFAULT_RESTORE_TIMEOUT_MS) {
        requireOpenAndConfigured()
        withTimeoutOrNull(timeoutMs.milliseconds) {
            ready()
            awaitRestoreSettled(timeoutMs)
        }
    }

    /**
     * Sends a one-time code to [contact] on its channel: a wallet auth token for the canonical
     * contact from the host, then `POST /otp/init` under it, which delivers the code to the contact
     * the token is bound to. Touches no session. A second call for the same contact replaces the
     * pending challenge on success and keeps it on failure, so a failed resend leaves a code the
     * user can still type; a call for another contact or channel retires the pending challenge
     * before anything is sent, so a failed switch leaves nothing confirmable, and so is a two-code
     * login waiting for its second code, with its device key deleted. The contact is
     * canonicalized once here ([canonicalize]) and that string names the account on sign-up. A
     * blank or malformed contact throws [RainError.InvalidConfig] before the host or Rain is
     * called and changes nothing.
     */
    suspend fun sendLoginCode(contact: LoginContact) {
        flowMutex.withLock {
            prepare()
            val (canonical, channel) = canonicalize(contact)
            abandonSecondCode()
            retirePendingOtpUnlessFor(canonical, channel)
            val pending = requestCode(canonical, channel)
            pendingLock.withJavaLock {
                pendingOtp = pending
            }
        }
    }

    /**
     * Confirms the code from [sendLoginCode], login first: a fresh device key, the code encrypted
     * to the challenge's bundle with it, `POST /otp/verify` for the verification token, then
     * `POST /login`; a 404 there means no wallet for this contact in this tenant, so `POST /signup`
     * creates the account with [MANAGED_WALLET] and `POST /login` runs again with the
     * sub-organization it answered with. The session Rain issues is stored under a fresh key and
     * selected, the previous session is cleared, then the Ethereum and Solana accounts are checked.
     *
     * A rejected code throws [RainError.InvalidLoginCode] and keeps the challenge, so the user can
     * retype it; so does any other failure of the verify step, a transport failure or a 5xx
     * included. A failure once the verify answered (the login, the sign-up, the session store)
     * drops the challenge, because the verification token cannot be reused. The device key is
     * deleted on every failure before the session store; a failed store follows [storeSession]'s
     * rule. A failure while selecting the new session keeps the previous session selected, since
     * Rain's login revoked nothing, and the thrown error is the host's signal. A provisioning
     * failure once the new session is live throws with the session kept: [ensureAccounts] runs
     * again at provider resolution, so it heals without a new code.
     *
     * For an account Rain protects with two codes the verify answers no token: Rain keeps it and
     * names the account's other contact, and this returns [TurnkeyLoginOutcome.SecondCodeRequired]
     * with the challenge spent, the device key kept for the whole attempt and no session touched;
     * [sendSecondLoginCode] and [confirmSecondLoginCode] finish the login. A 400 "start again" from
     * the verify ends as any refusal that spends the code: the challenge dropped, the key deleted,
     * the thrown error asking for a new code.
     */
    suspend fun confirmLoginCode(code: String): TurnkeyLoginOutcome = flowMutex.withLock {
        prepare()
        val pending = requirePendingOtp()
        val trimmed = requireCode(code)
        val previousKey = context.selectedSessionKey
        val sessionKey = SESSION_KEY_PREFIX + UUID.randomUUID()
        val deviceKey = guarded { context.createDeviceKey() }
        val result = onFailure({ deleteDeviceKey(deviceKey) }) { firstCode(pending, trimmed, deviceKey) }
        val login = when (result) {
            is FirstCodeResult.SecondCode -> return@withLock result.outcome
            is FirstCodeResult.Session -> result.login
        }
        storeSession(login.sessionToken, sessionKey, deviceKey)
        switchToSession(sessionKey, previousKey, previousRevoked = false)
        ensureAccountsLocked()
        TurnkeyLoginOutcome.SignedIn
    }

    // ---------- the second code ----------

    /**
     * `POST /otp/init` under the attempt's token, no renewal, since Rain keys the attempt on it;
     * the `sendSecondLoginCode` row in `docs/METHODS.md` has the rules.
     */
    suspend fun sendSecondLoginCode() {
        flowMutex.withLock {
            prepare()
            val attempt = requirePendingSecondCode()
            val answer = attemptLeg(attempt) { rainApi.initOtp(it) }
            val challenge = OtpChallenge(answer.otpId, answer.otpEncryptionTargetBundle, attempt.channel)
            pendingLock.withJavaLock {
                if (pendingSecondCode === attempt) pendingSecondCode = attempt.withChallenge(challenge)
            }
        }
    }

    /**
     * The second verify, with the attempt's key signing Rain's login message, then the session
     * handling of [confirmLoginCode]; the `confirmSecondLoginCode` row in `docs/METHODS.md` has
     * the rules.
     */
    suspend fun confirmSecondLoginCode(code: String) {
        flowMutex.withLock {
            prepare()
            val attempt = requirePendingSecondCode()
            val challenge = attempt.challenge
                ?: throw RainError.InvalidConfig("No second login code was requested; call sendSecondLoginCode first")
            val trimmed = requireCode(code)
            val previousKey = context.selectedSessionKey
            val sessionKey = SESSION_KEY_PREFIX + UUID.randomUUID()
            val login = verifySecondCode(attempt, challenge, trimmed)
            storeSession(login.sessionToken, sessionKey, attempt.deviceKey)
            switchToSession(sessionKey, previousKey, previousRevoked = false)
            ensureAccountsLocked()
        }
    }

    // ---------- passkeys ----------

    /**
     * Signs an existing user in with a passkey bound to the configured domain, through the system
     * passkey sheet presented from [activity]. The account is the one the passkey was created for.
     * A successful login stores the session under a fresh key, selects it, clears the previous
     * session, revokes the user's other sessions on every device and backfills a missing account.
     * A failed switch signs the device out ([abandonSwitch]), since this login revoked the previous
     * session server-side. A dismissed sheet, a refused passkey or a failed ceremony leaves the
     * current session untouched; a session the vendor stored under the fresh key without selecting
     * it is cleared. A pending login code survives, because it binds no device key; a two-code
     * login between its codes is abandoned first ([abandonSecondCode]), since the vendor's ceremony
     * deletes every device key no stored session names; a pending contact verification does not
     * survive either, because the account it named changed. Throws [RainError.InvalidConfig]
     * before touching the vendor when no domain is configured.
     */
    suspend fun loginWithPasskey(activity: Activity) {
        flowMutex.withLock {
            requirePasskeysConfigured()
            prepare()
            abandonSecondCode()
            val previousKey = context.selectedSessionKey
            val sessionKey = SESSION_KEY_PREFIX + UUID.randomUUID()
            ceremony(sessionKey) { context.completePasskeyLogin(activity, sessionKey, organizationId = null) }
            switchToSession(sessionKey, previousKey, previousRevoked = true)
            ensureAccountsLocked()
        }
    }

    /**
     * Creates a passkey account and signs it in with one prompt: the host's contact-less token, the
     * ceremony, `POST /signup/passkey` with the attestation and a device key Rain registers on the
     * account for a minute, then Turnkey's `stampLogin` stamped with that key and bound to a fresh key
     * of the device's; the device key is deleted once Rain's sign-up and the stamped login have
     * answered. An account with no key for the device (adopted from an earlier attempt, or found saved
     * on a resend) signs in through the sheet instead. Refused over a live session; a dead one is
     * cleared first, and a two-code login between its codes is abandoned, as in [loginWithPasskey].
     * A sign-up Rain left in doubt is resent from [pendingPasskeySignups] by the next
     * call; [signUpOnRain] has the rule, and the `signUpWithPasskey` row in `docs/METHODS.md` says
     * what each failure leaves behind.
     */
    suspend fun signUpWithPasskey(activity: Activity) {
        flowMutex.withLock {
            val rpId = requirePasskeysConfigured()
            prepare()
            awaitRestoreSettled(TurnkeySessionCoordinator.AUTH_RESTORE_TIMEOUT_MS)
            clearDeadSessionOrRefuse()
            abandonSecondCode()
            val token = AuthToken(contact = null, value = authToken(null))
            val sessionKey = SESSION_KEY_PREFIX + UUID.randomUUID()
            val kept = pendingPasskeySignups.load()
            // Kept before the send: a process that dies with Rain's answer outstanding resends it after the relaunch.
            val registration = kept ?: guarded {
                context.createPasskeyCredential(activity, rpId, TurnkeyPasskeys.authenticatorName(nowEpochSeconds()))
            }.also { pendingPasskeySignups.save(it) }
            val deviceKey = guarded { context.createDeviceKey() }
            val (signup, login) = onFailure({ deleteDeviceKey(deviceKey) }) {
                val signup = signUpOnRain(token, registration, deviceKey, resend = kept != null)
                val created = signup as? PasskeySignup.Created
                signup to created?.let { loginWithDeviceKey(deviceKey, it.subOrganizationId) }
            }
            // The device key only signs the login; the session has a key of its own, so nothing uses it from here.
            withContext(NonCancellable) { deleteDeviceKey(deviceKey) }
            if (login != null) {
                storeSession(login.jwt, sessionKey, login.sessionPublicKey)
            } else {
                // No API key for the device on the account: Rain adopted it from an earlier attempt,
                // which registers none, or a resend found it saved. The passkey signs in instead.
                val organizationId = (signup as? PasskeySignup.Created)?.subOrganizationId
                ceremony(sessionKey) { context.completePasskeyLogin(activity, sessionKey, organizationId = organizationId) }
            }
            switchToSession(sessionKey, previousKey = null, previousRevoked = true)
            ensureAccountsLocked()
        }
    }

    /**
     * Registers a passkey bound to the configured domain on the signed-in account, through the
     * system sheet presented from [activity], so the next sign-in can use it. No new account and no
     * session change. The session is checked before the sheet through the coordinator, which waits
     * out a restore in flight and refreshes a session near its expiry, so the user is never asked
     * for a biometric the backend cannot use; without a session this throws
     * [RainError.TokenExpired]. The ceremony runs outside the coordinator, because a retry there
     * would re-prompt the user; the registration runs inside it, retried once after a 401 refresh
     * (the backend answered before executing anything, so no duplicate). A dismissed sheet leaves
     * the account untouched; a registration the backend refused leaves a passkey on the device
     * that signs into nothing. Under [flowMutex], so a logout or login cannot swap the session
     * between the check and the registration; other auth calls wait while the sheet is open.
     */
    suspend fun addPasskey(activity: Activity) {
        flowMutex.withLock {
            val rpId = requirePasskeysConfigured()
            prepare()
            requireLiveSession()
            val name = TurnkeyPasskeys.authenticatorName(nowEpochSeconds())
            val registration = guarded { context.createPasskeyCredential(activity, rpId, name) }
            coordinator.executeWrite { session, _ ->
                context.registerAuthenticator(session.organizationId, session.userId, name, registration)
            }
        }
    }

    // ---------- contact attach ----------

    /**
     * Sends a verification code to [contact], a contact the signed-in user wants to attach to this
     * account as a login method, distinct from [sendLoginCode], which starts a login: a wallet auth
     * token for the canonical contact from the host, then `POST /otp/init` under it. Requires a live
     * session, checked before anything is sent. The contact is canonicalized like a login contact
     * and that string is what the account stores. The pending code follows the login send's rule:
     * a second call for the same contact replaces it on success and keeps it on failure, and a call
     * for another contact or channel retires it before anything is sent, so a failed switch leaves
     * nothing confirmable. Accounts are never merged; a contact another account in this tenant signs
     * in with is refused on confirm, see [confirmContactVerification].
     */
    suspend fun sendContactVerificationCode(contact: LoginContact) {
        flowMutex.withLock {
            prepare()
            val (canonical, channel) = canonicalize(contact)
            retirePendingContactOtpUnlessFor(canonical, channel)
            requireLiveSession()
            val pending = requestCode(canonical, channel)
            pendingLock.withJavaLock {
                pendingContactOtp = pending
            }
        }
    }

    /**
     * Confirms the code from [sendContactVerificationCode] and attaches the contact, verified. The
     * session is checked before the code is spent, so a dead session costs no code. The code is
     * verified against Rain with a throwaway device key, deleted afterwards, so a wrong code never
     * touches the live session's key; a rejected code throws [RainError.InvalidLoginCode] and keeps
     * the challenge, as [confirmLoginCode] does, and so does any other failure of the verify step.
     * Once the verify answered the code is spent, so the challenge is dropped whether or not what
     * follows succeeds: the account's email or phone is set through the vendor's update-user
     * activity under the live session with the verification token. Rain's verify names the wallet
     * this tenant saved for the contact, when one exists: another wallet's contact is refused with
     * [RainError.Unauthorized] before anything changes, the code spent; a contact two saved wallets
     * already share fails the verify itself with a 5xx. A failed update surfaces as its own error
     * with the code spent, and the user requests a new code.
     */
    suspend fun confirmContactVerification(code: String) {
        flowMutex.withLock {
            prepare()
            val pending = requirePendingContactOtp()
            val trimmed = requireCode(code)
            val signedIn = requireLiveSession()
            val throwaway = guarded { context.createDeviceKey() }
            val answer = try {
                verifyCode(pending, trimmed, throwaway, ::clearPendingContactOtp)
            } finally {
                // Nothing else removes the throwaway key; the token stays usable without it.
                withContext(NonCancellable) { deleteDeviceKey(throwaway) }
            }
            val verification = attachVerification(answer)
            val owner = verification.walletSubOrganizationId
            if (owner != null && owner != signedIn.organizationId) throw RainError.Unauthorized(CONTACT_OWNED_MESSAGE)
            val verificationToken = verification.verificationToken
            // The coordinator is its own mapping boundary: it rethrows cancellation and maps the rest.
            coordinator.executeWrite { live, _ ->
                when (pending.challenge.channel) {
                    OtpChannel.EMAIL ->
                        context.setUserEmail(live.organizationId, live.userId, pending.contact, verificationToken)
                    OtpChannel.SMS ->
                        context.setUserPhoneNumber(live.organizationId, live.userId, pending.contact, verificationToken)
                }
            }
        }
    }

    /**
     * The verification an attach can use, or the refusal: a call for a second code means the
     * contact signs in to an account Rain protects with two codes, refused as another account's,
     * with the token Rain kept never handed to the attach; a session is an answer no attach asked for.
     */
    private fun attachVerification(answer: RainApiVerifyAnswer): RainApiVerification = when (answer) {
        is RainApiVerification -> answer
        is RainApiSecondCodeRequired -> throw RainError.Unauthorized(CONTACT_OWNED_MESSAGE)
        is RainApiLogin -> throw RainError.InternalError(UNEXPECTED_SESSION_MESSAGE)
    }

    /** Mirrors [retirePendingOtpUnlessFor] for the verification slot. */
    private fun retirePendingContactOtpUnlessFor(contact: String, channel: OtpChannel) {
        pendingLock.withJavaLock {
            val pending = pendingContactOtp ?: return
            if (pending.contact != contact || pending.challenge.channel != channel) pendingContactOtp = null
        }
    }

    private fun requirePendingContactOtp(): PendingOtp =
        pendingLock.withJavaLock { pendingContactOtp }
            ?: throw RainError.InvalidConfig("No verification code was requested; call sendContactVerificationCode first")

    private fun clearPendingContactOtp() {
        pendingLock.withJavaLock { pendingContactOtp = null }
    }

    // ---------- the Rain legs ----------

    /** A token for the canonical contact from the host, then `POST /otp/init` under it. */
    private suspend fun requestCode(canonical: String, channel: OtpChannel): PendingOtp {
        val contact = channel.contactOf(canonical)
        val token = AuthToken(contact, authToken(contact))
        val answer = rainLeg(token) { rainApi.initOtp(it) }
        val challenge = OtpChallenge(
            otpId = answer.otpId,
            encryptionTargetBundle = answer.otpEncryptionTargetBundle,
            channel = channel,
        )
        return PendingOtp(challenge, canonical, token)
    }

    /**
     * The verify step under the pending token; [clearPending] runs once Rain accepted the code,
     * whether or not the answer decoded, and on a refusal that ends a two-code attempt, since the
     * code is spent either way.
     */
    private suspend fun verifyCode(
        pending: PendingOtp,
        code: String,
        deviceKey: String,
        clearPending: () -> Unit,
    ): RainApiVerifyAnswer {
        val encrypted = guarded { context.encryptOtpCode(code, pending.challenge.encryptionTargetBundle, deviceKey) }
        val verification = try {
            rainLeg(pending.token) { rainApi.verifyOtp(it, pending.challenge.otpId, encrypted) }
        } catch (e: RainError.InternalError) {
            // A 2xx the client could not read: Rain accepted the code, so it is spent.
            clearPending()
            throw e
        } catch (e: RainError.ProviderError) {
            if ((e.cause as? RainApiAuthFailure)?.kind == RainApiAuthFailure.Kind.AttemptEnded) clearPending()
            throw e
        }
        clearPending()
        return verification
    }

    /**
     * Rain's login or sign-up on a plain verification; on a call for a second code, the attempt
     * kept with [deviceKey] once Rain's message proved to be a login naming that key.
     */
    private suspend fun firstCode(pending: PendingOtp, code: String, deviceKey: String): FirstCodeResult =
        when (val answer = verifyCode(pending, code, deviceKey, ::clearPendingOtp)) {
            is RainApiVerification -> FirstCodeResult.Session(loginOrSignUp(pending, answer.verificationToken))
            is RainApiSecondCodeRequired -> {
                requireLoginMessageFor(answer.clientSignatureMessage, deviceKey)
                pendingLock.withJavaLock {
                    pendingSecondCode = PendingSecondCode(pending.token, deviceKey, answer.channel, answer.clientSignatureMessage)
                }
                val contact = TurnkeyMaskedContact(answer.channel.toContactType(), answer.maskedContact)
                FirstCodeResult.SecondCode(TurnkeyLoginOutcome.SecondCodeRequired(contact))
            }
            is RainApiLogin -> throw RainError.InternalError(UNEXPECTED_SESSION_MESSAGE)
        }

    /**
     * The second code's verify: the code encrypted with the attempt's key, the message signed with
     * it, both sent under the attempt's token. Rain's 2xx ends the attempt either way: the session
     * is issued to the key, so the key stays only when the answer is the session.
     */
    private suspend fun verifySecondCode(attempt: PendingSecondCode, challenge: OtpChallenge, code: String): RainApiLogin {
        val encrypted = guarded { context.encryptOtpCode(code, challenge.encryptionTargetBundle, attempt.deviceKey) }
        val signature = guarded { context.clientSignatureFor(attempt.clientSignatureMessage, attempt.deviceKey) }
        val answer = try {
            attemptLeg(attempt) { rainApi.verifyOtp(it, challenge.otpId, encrypted, signature) }
        } catch (e: RainError.InternalError) {
            endSecondCode(attempt, deleteKey = true)
            throw e
        }
        endSecondCode(attempt, deleteKey = answer !is RainApiLogin)
        return answer as? RainApiLogin ?: throw RainError.InternalError(
            "Rain API otp/verify answered a second code without a session; request a new login code"
        )
    }

    /**
     * One leg of a two-code attempt under its token, with no renewal, since Rain keys the attempt
     * on the token. A wrong code keeps the attempt; every other refusal ends it, because Rain may
     * already have and the client cannot tell; a transport failure keeps it.
     */
    private suspend fun <T> attemptLeg(attempt: PendingSecondCode, call: suspend (token: String) -> T): T {
        val refusal = try {
            return guarded { call(attempt.token.value) }
        } catch (e: RainApiAuthFailure) {
            e
        }
        if (refusal.kind != RainApiAuthFailure.Kind.InvalidCode) endSecondCode(attempt, deleteKey = true)
        throw TurnkeyErrorMapping.mapAuthError(refusal)
    }

    /** Drops the attempt, and its device key unless the session Rain issued is bound to it. */
    private suspend fun endSecondCode(attempt: PendingSecondCode, deleteKey: Boolean) {
        pendingLock.withJavaLock { if (pendingSecondCode === attempt) pendingSecondCode = null }
        if (deleteKey) withContext(NonCancellable) { deleteDeviceKey(attempt.deviceKey) }
    }

    /** Drops a two-code login between its codes and deletes its device key; Rain's side lapses on its own. */
    private suspend fun abandonSecondCode() {
        val attempt = pendingLock.withJavaLock { pendingSecondCode.also { pendingSecondCode = null } } ?: return
        withContext(NonCancellable) { deleteDeviceKey(attempt.deviceKey) }
    }

    private fun requirePendingSecondCode(): PendingSecondCode =
        pendingLock.withJavaLock { pendingSecondCode }
            ?: throw RainError.InvalidConfig("No login is waiting for a second code; confirmLoginCode answers SecondCodeRequired first")

    /**
     * Refuses to hold [message] for signing unless it is the vendor's login usage naming
     * [deviceKey] (`ClientSignatureMessageTest` pins the shape), so the key signs nothing else.
     */
    private fun requireLoginMessageFor(message: String, deviceKey: String) {
        val isLogin = runCatching {
            val json = JSONObject(message)
            json.getString("type") == LOGIN_USAGE_TYPE && json.getJSONObject("login").getString("publicKey") == deviceKey
        }.getOrDefault(false)
        if (!isLogin) {
            throw RainError.InternalError(
                "Rain API otp/verify: the message to sign is not a login for this device's key; request a new login code"
            )
        }
    }

    private fun OtpChannel.toContactType(): LoginContactType = when (this) {
        OtpChannel.EMAIL -> LoginContactType.EMAIL
        OtpChannel.SMS -> LoginContactType.PHONE
    }

    /**
     * `POST /login`; on a no-wallet refusal, `POST /signup` and then `POST /login` again with the
     * sub-organization id the sign-up answered with, which spares Rain its lookup. Every other
     * refusal maps as it comes.
     */
    private suspend fun loginOrSignUp(pending: PendingOtp, verificationToken: String): RainApiLogin {
        val loginSignature = guarded { context.clientSignatureForLogin(verificationToken) }
        val existing = rainLegOrNull(pending.token, consume = { it.kind == RainApiAuthFailure.Kind.NoWallet }) { token ->
            rainApi.login(token, verificationToken, loginSignature, subOrganizationId = null)
        }
        if (existing != null) return existing
        val signUpSignature = guarded {
            context.clientSignatureForSignUp(verificationToken, pending.contact, pending.challenge.channel)
        }
        val signup = rainLeg(pending.token) { token -> rainApi.signup(token, verificationToken, signUpSignature) }
        return rainLeg(pending.token) { token ->
            rainApi.login(token, verificationToken, loginSignature, subOrganizationId = signup.subOrganizationId)
        }
    }

    /**
     * One leg against Rain under [token], through [guarded], which passes the client's own refusal
     * through raw so its kind can be read here. A refused or spent token gets one fresh token from
     * the host and one retry, and the fresh token replaces the old one for the legs that follow; a second
     * refusal is an error like any other. A refusal [consume] accepts returns null for the caller
     * to act on; every other refusal leaves through [TurnkeyErrorMapping.mapAuthError]. [onRefusal]
     * sees every refusal raw, before the renewal or the mapping.
     */
    private suspend fun <T> rainLegOrNull(
        token: AuthToken,
        consume: (RainApiAuthFailure) -> Boolean,
        onRefusal: (RainApiAuthFailure) -> Unit = {},
        call: suspend (token: String) -> T,
    ): T? {
        var refetched = false
        while (true) {
            val refusal = try {
                return guarded { call(token.value) }
            } catch (e: RainApiAuthFailure) {
                e
            }
            onRefusal(refusal)
            when {
                (refusal.kind == RainApiAuthFailure.Kind.TokenRejected || refusal.kind == RainApiAuthFailure.Kind.TokenUsed) && !refetched -> {
                    refetched = true
                    token.value = authToken(token.contact)
                }
                consume(refusal) -> return null
                else -> throw TurnkeyErrorMapping.mapAuthError(refusal)
            }
        }
    }

    /** [rainLegOrNull] for a leg that consumes no refusal. */
    private suspend fun <T> rainLeg(token: AuthToken, call: suspend (token: String) -> T): T =
        checkNotNull(rainLegOrNull(token, consume = { false }, call = call)) { "a leg that consumes no refusal answered null" }

    /**
     * `POST /signup/passkey` for [registration] and the device key [publicKey], with [rainLeg]'s one
     * token renewal, and the fate of the registration, saved before the send, decided from the
     * answer: a 200 clears it ([PasskeySignup.Created]), and so does a [resend] answered "already has
     * a wallet" ([PasskeySignup.Saved]: the first call's account was saved). In doubt, so left for the
     * next call: a lost or unreadable answer, a 5xx, a 429, a cancellation, and "already in progress",
     * which leaves as [RainError.NetworkError] for the host to retry. Dropped: every other refusal
     * on a first send (Rain created nothing; "already has a wallet" leaves as the mapper's
     * [RainError.InvalidConfig]), and a 400 on a resend (Turnkey refused the attestation). A refused
     * token or tenant or a 404 on a resend say nothing about the first call, so the registration stays.
     */
    private suspend fun signUpOnRain(
        token: AuthToken,
        registration: PasskeyRegistration,
        publicKey: String,
        resend: Boolean,
    ): PasskeySignup {
        // The refusal that ended the leg, raw: the mapped error does not always carry it.
        var refusal: RainApiAuthFailure? = null
        val signup = try {
            rainLegOrNull(
                token,
                consume = { resend && it.kind == RainApiAuthFailure.Kind.PasskeyTaken },
                onRefusal = { refusal = it },
            ) {
                rainApi.signupPasskey(it, registration.challenge, registration.toRainApi(), publicKey)
            }
        } catch (e: RainError) {
            throw settlePasskeySignup(e, refusal, resend)
        }
        withContext(NonCancellable) { pendingPasskeySignups.clear() }
        return signup?.let { PasskeySignup.Created(it.subOrganizationId) } ?: PasskeySignup.Saved
    }

    /** [signUpOnRain]'s failure exit: drops the kept registration or leaves it, and names the error that leaves. */
    private suspend fun settlePasskeySignup(e: RainError, refusal: RainApiAuthFailure?, resend: Boolean): RainError {
        val inProgress = refusal?.kind == RainApiAuthFailure.Kind.SignupInProgress
        // A lost retry after the one token renewal keeps it too: that retry's outcome is the unknown one.
        val lostOrFailed = e is RainError.NetworkError || e is RainError.InternalError ||
            (refusal != null && refusal.status >= HttpURLConnection.HTTP_INTERNAL_ERROR)
        val keep = when {
            inProgress || lostOrFailed || refusal?.status == HTTP_TOO_MANY_REQUESTS -> true
            refusal?.kind == RainApiAuthFailure.Kind.PasskeyTaken -> false
            resend -> refusal?.status != HttpURLConnection.HTTP_BAD_REQUEST
            else -> false
        }
        // A drop lands even when the caller is being cancelled; a kept registration is on disk since before the send.
        if (!keep) withContext(NonCancellable) { pendingPasskeySignups.clear() }
        return if (inProgress) {
            RainError.NetworkError(
                "Rain is still creating this account from an earlier sign-up; call signUpWithPasskey again in a " +
                    "moment, which resends the same passkey",
                e,
            )
        } else {
            e
        }
    }

    /** What `POST /signup/passkey` settled. */
    private sealed interface PasskeySignup {
        /** Rain answered with the account's id: created now, with the device key registered, or adopted with none. */
        class Created(val subOrganizationId: String) : PasskeySignup

        /** A resend Rain answered "already has a wallet": the account is saved, and only the passkey reaches it. */
        object Saved : PasskeySignup
    }

    /**
     * The host's token for [contact], null on a passkey sign-up. A [RainError] the host throws and
     * this coroutine's own cancellation pass through; anything else becomes NetworkError (an
     * IOException in its causes) or InvalidConfig, named by class only, because the host's message
     * may carry the contact. A blank token is InvalidConfig too.
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount", "SwallowedException")
    private suspend fun authToken(contact: LoginContact?): String {
        val token = try {
            authTokenProvider(contact)
        } catch (e: RainError) {
            throw e
        } catch (e: CancellationException) {
            // Only this coroutine's own cancellation passes, as in ready(). A cancellation raised
            // inside the host's provider is the provider failing; left as one it would skip the
            // host's `catch (e: RainError)` and, on a refetch, spend the code silently.
            currentCoroutineContext().ensureActive()
            throw providerFailure(e)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw providerFailure(e)
        }
        if (token.isBlank()) throw RainError.InvalidConfig("authTokenProvider returned a blank wallet auth token")
        return token
    }

    /** A host failure under [authToken]'s rule: class name only, NetworkError when its backend was out of reach. */
    private fun providerFailure(e: Exception): RainError {
        val name = e.javaClass.simpleName
        return if (e.causeChain().any { it is IOException }) {
            RainError.NetworkError("authTokenProvider could not reach your backend ($name); no wallet auth token was minted")
        } else {
            RainError.InvalidConfig(
                "authTokenProvider failed with $name; it must return the wallet auth token your backend minted for this contact"
            )
        }
    }

    /** Runs [block]; on a [RainError] or the caller's cancellation, runs [cleanup] first, then rethrows. */
    private suspend fun <T> onFailure(cleanup: suspend () -> Unit, block: suspend () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { cleanup() }
            throw e
        } catch (e: RainError) {
            cleanup()
            throw e
        }
    }

    /** Best-effort removal of a device key no session uses; a failed delete is logged, not surfaced. */
    @Suppress("TooGenericExceptionCaught") // best-effort cleanup; the failure that matters is the caller's
    private suspend fun deleteDeviceKey(publicKey: String) {
        try {
            context.deleteDeviceKey(publicKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Rain SDK: could not delete a device key (%s)", e.javaClass.simpleName)
        }
    }

    /** [deleteDeviceKey] for [close], which cannot suspend: on the calling thread, logged on failure. */
    @Suppress("TooGenericExceptionCaught") // best-effort cleanup on a terminal call
    private fun discardDeviceKey(publicKey: String) {
        try {
            context.discardDeviceKey(publicKey)
        } catch (e: Exception) {
            Timber.w("Rain SDK: could not delete a device key (%s)", e.javaClass.simpleName)
        }
    }

    private fun PasskeyRegistration.toRainApi(): RainApiPasskeyAttestation = RainApiPasskeyAttestation(
        credentialId = attestation.credentialId,
        clientDataJson = attestation.clientDataJson,
        attestationObject = attestation.attestationObject,
        transports = attestation.transports.map { it.name },
    )

    private fun OtpChannel.contactOf(canonical: String): LoginContact = when (this) {
        OtpChannel.EMAIL -> LoginContact.Email(canonical)
        OtpChannel.SMS -> LoginContact.Phone(canonical)
    }

    // ---------- sessions ----------

    /**
     * A live or refreshable session, or [RainError.TokenExpired]: the coordinator's own check, run
     * as a read that returns the session it validated. It waits out a restore in flight and
     * refreshes inside the expiry buffer; [hasActiveSession] alone reads false while the vendor's
     * asynchronous restore is still loading.
     */
    private suspend fun requireLiveSession(): Session = coordinator.executeRead { session, _ -> session }

    /**
     * The relying-party domain, or [RainError.InvalidConfig] before the vendor is touched. The
     * vendor holds the same value from the one-shot configuration for its passkey login; the
     * add-passkey ceremony and the passkey sign-up's ceremony are handed it explicitly.
     */
    private fun requirePasskeysConfigured(): String =
        passkeyDomain ?: throw RainError.InvalidConfig(TurnkeyPasskeys.NOT_CONFIGURED_MESSAGE)

    /**
     * Makes room for a passkey sign-up. A selected session that is live, or still restoring after
     * the bounded wait, refuses the call: one account is signed in at a time, the rule the sample
     * and the docs state. A selected session that is dead is cleared the way [logout] clears one,
     * host hook silent, so the ceremony can run and the vendor selects what it creates; the sample
     * offers the sign-up button in exactly that state, and refusing would tell the user to log out
     * of an account the screen says they are not in. The pending contact verification goes with
     * the dead session, since it named that account; a pending login code binds no device key and
     * survives, as it does across a login.
     */
    private suspend fun clearDeadSessionOrRefuse() {
        if (context.selectedSessionKey == null) return
        when (coordinator.currentState()) {
            is TurnkeySessionState.Expired, is TurnkeySessionState.Unauthenticated -> {
                clearSelectedSessionSilently()
                clearPendingContactOtp()
            }
            is TurnkeySessionState.Active, is TurnkeySessionState.Loading ->
                throw RainError.InvalidConfig(TurnkeyPasskeys.ALREADY_SIGNED_IN_MESSAGE)
        }
    }

    /**
     * Clears the selected session with the host's re-auth hook held silent for the death this
     * causes. The suppression is released when the clear did not happen, so the host still hears
     * about a later, genuine one.
     */
    private suspend fun clearSelectedSessionSilently() {
        coordinator.suppressNextHostHook()
        var cleared = false
        try {
            guarded { context.clearSelectedSession() }
            cleared = true
        } finally {
            if (!cleared) coordinator.releaseHostHookSuppression()
        }
    }

    /**
     * Turnkey's login stamped with [deviceKey] on [organizationId], bound to a fresh key of the
     * device's: the session with that key, or null for a stamp Turnkey refused or answered without
     * a session, the fresh key deleted again; a failure or the caller's cancellation deletes it too.
     */
    private suspend fun loginWithDeviceKey(deviceKey: String, organizationId: String): DeviceKeyLogin? {
        val sessionPublicKey = guarded { context.createDeviceKey() }
        val jwt = onFailure({ deleteDeviceKey(sessionPublicKey) }) {
            guarded { context.stampLoginWithDeviceKey(deviceKey, sessionPublicKey, organizationId) }
        }
        if (jwt == null) {
            withContext(NonCancellable) { deleteDeviceKey(sessionPublicKey) }
            return null
        }
        return DeviceKeyLogin(jwt, sessionPublicKey)
    }

    /** A session Turnkey issued to a device-key stamp, and the fresh key it is bound to. */
    private class DeviceKeyLogin(val jwt: String, val sessionPublicKey: String)

    /**
     * Stores the session Rain issued under [sessionKey], the vendor's `createSession`, which, when
     * nothing is selected, also selects it and refreshes the user and the wallets over the network,
     * so a failure or the caller's cancellation can land with the session already stored, selected
     * or not. A session the vendor selected is left signed in with [boundKey], the key the session
     * is bound to (the code login's device key, a passkey sign-up's fresh session key), since the
     * device stamps with it from then on; otherwise [abandonStore] deletes the key and clears the
     * stored session, so the failed login leaves neither behind.
     */
    private suspend fun storeSession(jwt: String, sessionKey: String, boundKey: String) {
        onFailure({ abandonStore(sessionKey, boundKey) }) { guarded { context.storeSession(jwt, sessionKey) } }
    }

    /**
     * Cleans up after a store that failed, unless the vendor selected the session first. Deletes
     * the key first, while it is certainly still there; the vendor's purge of a stored session
     * deletes the key it finds and is silent about one already gone.
     */
    private suspend fun abandonStore(sessionKey: String, boundKey: String) {
        if (context.selectedSessionKey == sessionKey) return
        deleteDeviceKey(boundKey)
        clearUnselected(sessionKey)
    }

    /**
     * Runs a passkey ceremony that stores its session under [sessionKey] on success. On a mapped
     * failure or the caller's cancellation, the fresh key is cleared unless the vendor already
     * selected it: the vendor's key cleanup can throw after its session store, and a cancellation
     * can land after it, and either would otherwise leave a never-selected session in the registry
     * for good. A session the vendor selected before the failure is left signed in, as the code
     * login leaves it.
     */
    private suspend fun ceremony(sessionKey: String, block: suspend () -> Unit) {
        onFailure({ clearUnselected(sessionKey) }) { guarded(block = block) }
    }

    /** Drops a pending challenge issued for another contact or channel; a same-contact resend keeps it. */
    private fun retirePendingOtpUnlessFor(contact: String, channel: OtpChannel) {
        pendingLock.withJavaLock {
            val pending = pendingOtp ?: return
            if (pending.contact != contact || pending.challenge.channel != channel) pendingOtp = null
        }
    }

    private fun requirePendingOtp(): PendingOtp =
        pendingLock.withJavaLock { pendingOtp }
            ?: throw RainError.InvalidConfig("No login code was requested; call sendLoginCode first")

    private fun requireCode(code: String): String =
        code.trim().ifEmpty { throw RainError.InvalidConfig("code must not be blank") }

    /**
     * The identity string the account is keyed on, and the channel the code travels on. The
     * backend matches an email case-sensitively, so `Jo@Example.com` and `jo@example.com` would
     * sign up as two accounts with two wallets; lowercasing folds them into one. Kotlin's
     * `lowercase()` is locale-independent, so the device locale cannot change the identity.
     */
    private fun canonicalize(contact: LoginContact): Pair<String, OtpChannel> = when (contact) {
        is LoginContact.Email -> {
            val email = contact.value.trim().lowercase()
                .ifEmpty { throw RainError.InvalidConfig("email must not be blank") }
            email to OtpChannel.EMAIL
        }
        is LoginContact.Phone -> requirePhoneNumber(contact.value) to OtpChannel.SMS
    }

    /**
     * Removes the separators people type (spaces, dots, hyphens, parentheses) and requires the
     * E.164 shape. No country inference: a national number without `+` is refused, and so is a
     * parenthesised trunk zero (`+44 (0) 20 ...`), which stripping would fold into a different,
     * well-formed number. The fixed message never echoes the input.
     */
    private fun requirePhoneNumber(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw RainError.InvalidConfig("phoneNumber must not be blank")
        val digits = trimmed.replace(PHONE_SEPARATORS, "")
        if (TRUNK_PREFIX.containsMatchIn(trimmed) || !E164.matches(digits)) {
            throw RainError.InvalidConfig(
                "phoneNumber must be in E.164 format: '+' then country code and number, digits only, " +
                    "for example +13214567890"
            )
        }
        return digits
    }

    /**
     * Ensures the authenticated organization holds an Ethereum (secp256k1) and a Solana (ed25519)
     * account. Idempotent. A sign-up already gets both inside Rain's sign-up ([MANAGED_WALLET]);
     * this backfills organizations that predate that or were created outside the flow. Missing
     * accounts are added to the wallet Rain resolves; only an organization with no wallet at all
     * gets a new one — so the user has a single mnemonic to back up either way. Also run at
     * provider resolution, so a session whose provisioning failed after login heals itself without
     * a new code.
     */
    suspend fun ensureAccounts() {
        flowMutex.withLock { ensureAccountsLocked() }
    }

    /**
     * Clears the selected session. Deliberate, so the host's `onSessionExpired` re-auth hook stays
     * silent for the death this causes; cached accounts still go stale. A pending login code, a
     * pending contact verification and a two-code login between its codes, with its device key,
     * are dropped once the clear was attempted, whether or not it succeeded. Reads made right after
     * this returns already see no session: the vendor flips its auth state, selected key and
     * session inline inside `clearSession`, and [hasActiveSession] and [currentAuthState] derive
     * from those values on demand, so no wait for the flip is needed here.
     */
    suspend fun logout() {
        flowMutex.withLock {
            prepare()
            // Do not clear mid-restore: the vendor completes its readiness signal before the last
            // of its configuration is assigned, and a clear in that window half-applies. The wait is
            // bounded by the coordinator's own restore timeout, so logout never outlasts a wallet call.
            awaitRestoreSettled(TurnkeySessionCoordinator.AUTH_RESTORE_TIMEOUT_MS)
            if (context.selectedSessionKey == null) {
                // Nothing to clear, so nothing to suppress — an armed suppression with no death to
                // consume it would silence the next genuine one.
                clearPendingOtp()
                clearPendingContactOtp()
                abandonSecondCode()
                return
            }
            try {
                clearSelectedSessionSilently()
            } finally {
                clearPendingOtp()
                clearPendingContactOtp()
                abandonSecondCode()
            }
        }
    }

    /**
     * Makes this controller inert: every auth call throws, the state reads unauthenticated, and
     * both pending codes are dropped, each with the host's bearer token it holds, as is a two-code
     * login between its codes, whose device key is deleted on the calling thread. A kept
     * passkey registration stays in its store for the next controller to resend.
     */
    fun close() {
        closed.set(true)
        closedFlow.value = true
        val attempt = pendingLock.withJavaLock {
            pendingOtp = null
            pendingContactOtp = null
            pendingSecondCode.also { pendingSecondCode = null }
        }
        attempt?.let { discardDeviceKey(it.deviceKey) }
    }

    private suspend fun ensureAccountsLocked() {
        prepare()
        // The same contract as every wallet read: waits out a restore in flight, throws
        // TokenExpired when no session can be produced (an unsettled restore never provisions
        // against a stale list), and retries transient failures.
        coordinator.executeRead { _, _ -> context.refreshWallets() }
        val wallets = context.wallets
        val formats = wallets.flatMap { it.accounts }.map { it.addressFormat }.toSet()
        val missing = buildList {
            if (V1AddressFormat.ADDRESS_FORMAT_ETHEREUM !in formats) add(ETHEREUM_ACCOUNT)
            if (V1AddressFormat.ADDRESS_FORMAT_SOLANA !in formats) add(SOLANA_ACCOUNT)
        }
        if (missing.isEmpty()) return
        guarded {
            val target = wallets.firstOrNull { wallet ->
                wallet.accounts.any { it.addressFormat == V1AddressFormat.ADDRESS_FORMAT_ETHEREUM }
            } ?: wallets.firstOrNull()
            if (target == null) {
                context.createWallet(MANAGED_WALLET_NAME, missing)
            } else {
                context.createWalletAccounts(target.id, missing)
            }
            // Deterministic for the caller's immediate read; the vendor's own auto-refresh is a
            // configuration default this module does not own.
            coordinator.executeRead { _, _ -> context.refreshWallets() }
        }
    }

    /**
     * On a first login the vendor selects the new session itself; over a live session it only
     * stores it, so the switch has to be explicit. Afterwards the coordinator's death count
     * advances and stales the manager's cached addresses, since an Active→Active transition is not
     * a death the watcher notices, and the previous session is cleared locally. A failed switch is
     * abandoned on both exits by [abandonSwitch]; [previousRevoked] says whether the login revoked
     * the previous session server-side.
     */
    private suspend fun switchToSession(sessionKey: String, previousKey: String?, previousRevoked: Boolean) {
        if (context.selectedSessionKey != sessionKey) {
            onFailure({ abandonSwitch(sessionKey, previousKey, previousRevoked) }) { guarded { context.selectSession(sessionKey) } }
        }
        // The account changed and the verification slot named the one left behind. Not earlier: a
        // switch that failed above kept the previous account, whose pending attach still stands.
        clearPendingContactOtp()
        coordinator.notifySessionReplaced()
        if (previousKey != null) clearUnselected(previousKey)
    }

    /**
     * Cleans up after a switch that failed. The fresh key is cleared only when the selection
     * really did not take effect: the vendor persists it before its auto-refresh can fail. The
     * previous session, still the selected one, is signed out only when the login revoked it
     * server-side ([previousRevoked], a passkey login's `invalidateExisting`): left selected, it
     * would fail at the next wallet call as an unexplained expiry. The re-auth hook stays silent
     * for that sign-out, because the caller's exception is the host's signal, as after [logout].
     * After a code login, which revoked nothing, it stays selected and as live as before.
     */
    @Suppress(
        "TooGenericExceptionCaught"
    ) // best-effort cleanup: a clear that fails is logged, the select failure is what surfaces
    private suspend fun abandonSwitch(sessionKey: String, previousKey: String?, previousRevoked: Boolean) {
        clearUnselected(sessionKey)
        if (!previousRevoked || previousKey == null || context.selectedSessionKey != previousKey) return
        coordinator.suppressNextHostHook()
        try {
            context.clearSelectedSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The death did not happen, so the host must still hear about a later one.
            coordinator.releaseHostHookSuppression()
            Timber.w(e, "Rain SDK: could not clear the superseded wallet session")
        } finally {
            // The account this sign-out leaves is gone, pending attach included, as after logout().
            clearPendingContactOtp()
        }
    }

    /** Best-effort removal of a stored session that is not the selected one. */
    @Suppress(
        "TooGenericExceptionCaught"
    ) // best-effort cleanup: a stale key that will not clear is logged, not surfaced
    private suspend fun clearUnselected(sessionKey: String) {
        if (context.selectedSessionKey == sessionKey) return
        try {
            context.clearSession(sessionKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Rain SDK: could not clear a stale wallet session")
        }
    }

    /** Waits, bounded, for the vendor's asynchronous session restore to leave `loading`. */
    private suspend fun awaitRestoreSettled(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs.milliseconds) {
            guarded { context.authState.first { it != AuthState.loading } }
        }
    }

    private fun clearPendingOtp() {
        pendingLock.withJavaLock { pendingOtp = null }
    }

    /** The entry guard of every mutating call: open, configured for this process, vendor ready. */
    private suspend fun prepare() {
        requireOpenAndConfigured()
        ready()
    }

    /**
     * The same guard for a call outside authentication that needs the vendor configured and its
     * session restore settled, such as key export. Without it, the first such call of a launch
     * would wait out the coordinator's restore timeout and report `TokenExpired`.
     */
    internal suspend fun ensureConfigured() = prepare()

    private suspend fun requireOpenAndConfigured() {
        if (closed.get()) throw RainError.InvalidConfig(TURNKEY_PROVIDER_CLOSED_MESSAGE)
        configure()?.let { throw it }
    }

    /**
     * Waits, bounded, for the vendor's one-shot initialization. A failed or never-finishing init is
     * permanent for the process, so both surface as [RainError.InternalError] naming a relaunch.
     */
    @Suppress(
        "TooGenericExceptionCaught",
        "ThrowsCount"
    ) // untyped vendor failure; cancellation, failure and timeout are distinct exits
    private suspend fun ready() {
        val ready = try {
            withTimeoutOrNull(READY_TIMEOUT_MS.milliseconds) {
                context.awaitReady()
                true
            } ?: false
        } catch (e: CancellationException) {
            // The vendor stores a cancelled first initialization in its process-wide readiness signal
            // and replays it to every later caller, so only this coroutine's own cancellation passes.
            currentCoroutineContext().ensureActive()
            throw RainError.InternalError(
                "The wallet backend failed to initialize for this app launch; relaunch the app to retry",
                e
            )
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw RainError.InternalError(
                "The wallet backend failed to initialize for this app launch; relaunch the app to retry",
                e
            )
        }
        if (!ready) {
            throw RainError.InternalError(
                "The wallet backend did not finish initializing for this app launch; relaunch the app to retry"
            )
        }
    }

    /**
     * Runs a vendor or Rain call under the error contract: cancellation propagates untouched, a
     * [RainError] passes through, a [RainApiAuthFailure] passes through raw so [rainLegOrNull] can
     * read its kind, and anything else is mapped through [TurnkeyErrorMapping.mapAuthError].
     * `ensureActive()`, not a cause-chain walk, decides whether a caught exception is really this
     * coroutine's cancellation: the vendor wraps cancellation in its own error types, but it also
     * re-emits a sibling's failure as a wrapped `JobCancellationException` while this caller is
     * still active.
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount") // the mapping boundary: three pass-throughs and one mapped exit
    private suspend fun <T> guarded(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RainError) {
            throw e
        } catch (e: RainApiAuthFailure) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw TurnkeyErrorMapping.mapAuthError(e)
        }
    }

    internal companion object {
        private const val MILLIS_PER_SECOND = 1000.0

        /** Host-facing default for [awaitSessionRestore]. */
        const val DEFAULT_RESTORE_TIMEOUT_MS = 5_000L

        /** Longest a call waits for the vendor's readiness signal before giving up on this launch. */
        const val READY_TIMEOUT_MS = 15_000L

        const val SESSION_MIN_REMAINING_SECONDS = 30.0
        const val SESSION_KEY_PREFIX = "rain-turnkey-"

        /**
         * The refusal for a contact another wallet signs in with, and for one whose account Rain
         * protects with two codes, which an attach never gets a token for; names no contact.
         */
        const val CONTACT_OWNED_MESSAGE = "This contact belongs to another account"

        /** A session answered to a verify that sent no client signature: an answer the device never asked for. */
        const val UNEXPECTED_SESSION_MESSAGE = "Rain API otp/verify answered a session to a code that asked for none; request a new login code"

        /** The vendor's `V1UsageType` for a login, the one message the device key signs for Rain. */
        private const val LOGIN_USAGE_TYPE = "USAGE_TYPE_LOGIN"

        /** `HttpURLConnection` names no 429. */
        private const val HTTP_TOO_MANY_REQUESTS = 429
        const val MANAGED_WALLET_NAME = "Wallet"

        /** Separators people type into a phone number; removed before the E.164 check. */
        private val PHONE_SEPARATORS = Regex("""[\s().-]""")

        /** E.164: `+`, a country code that never starts with 0, at most 15 digits in total. */
        private val E164 = Regex("""^\+[1-9]\d{1,14}$""")

        /** A national trunk zero in parentheses; stripping it would yield a wrong but valid-looking number. */
        private val TRUNK_PREFIX = Regex("""\(\s*0\s*\)""")

        val ETHEREUM_ACCOUNT = TurnkeyAccountSpec(
            addressFormat = V1AddressFormat.ADDRESS_FORMAT_ETHEREUM,
            curve = V1Curve.CURVE_SECP256K1,
            path = "m/44'/60'/0'/0/0",
        )
        val SOLANA_ACCOUNT = TurnkeyAccountSpec(
            addressFormat = V1AddressFormat.ADDRESS_FORMAT_SOLANA,
            curve = V1Curve.CURVE_ED25519,
            path = "m/44'/501'/0'/0'",
        )

        /**
         * The wallet every managed sign-up gets: one seed carrying both accounts. Rain's sign-up
         * endpoints create it inside the sign-up itself, from a spec of their own that equals this
         * one, and [ensureAccounts] completes an older organization to the same shape. Name,
         * mnemonic length (Turnkey's default of 12) and derivation paths are a cross-platform
         * contract shared by Rain's SDKs and rain-api: change it everywhere or nowhere.
         */
        val MANAGED_WALLET = TurnkeyWalletSpec(MANAGED_WALLET_NAME, listOf(ETHEREUM_ACCOUNT, SOLANA_ACCOUNT))
    }
}
