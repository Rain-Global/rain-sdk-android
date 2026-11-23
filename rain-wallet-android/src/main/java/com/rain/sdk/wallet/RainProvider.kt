package com.rain.sdk.wallet

import android.app.Activity
import android.app.Application
import com.rain.sdk.error.RainError
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderDescriptor
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.WalletProvider
import com.rain.sdk.turnkey.TurnkeyProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Rain wallet: a registrable [ProviderDescriptor] under which the SDK owns authentication,
 * wallet provisioning, sessions and key export, so a host integrates one product under Rain's
 * names, supplies the wallet auth token its backend mints, and tunes behaviour.
 *
 * ```kotlin
 * val wallet = RainProvider(
 *     application,
 *     RainWalletConfig(
 *         // Your backend mints it with your Api-Key: POST /v1/issuing/embedded-wallets/auth-tokens
 *         authTokenProvider = { contact -> myBackend.mintWalletAuthToken(contact) },
 *         // The default is Production
 *         environment = RainWalletEnvironment.Sandbox,
 *     ),
 * )
 *
 * wallet.awaitSessionRestore()
 * if (!wallet.hasActiveSession()) {
 *     wallet.sendLoginCode(RainWalletContact.Email("user@example.com"))
 *     val outcome = wallet.confirmLoginCode(code) // sign-up or login, plus wallet provisioning
 *     if (outcome is RainWalletLoginOutcome.SecondCodeRequired) {
 *         wallet.sendSecondLoginCode() // to the account's other contact, outcome.contact.masked
 *         wallet.confirmSecondLoginCode(secondCode)
 *     }
 *     // or: wallet.loginWithPasskey(activity)
 * }
 *
 * val rain = RainSdk.builder()
 *     .rpcEndpoints(mapOf(8453 to "https://…"))
 *     .register(wallet)
 *     .build()
 * val client = rain.provider(ProviderId.RAIN)
 * ```
 *
 * Send a one-time login code to the user's email or phone and confirm it. A first login signs the
 * user up and creates one wallet holding an Ethereum and a Solana account on a single seed; a
 * returning login finds the account. An account Rain protects with two codes asks for the code
 * sent to its other contact too. With [RainWalletConfig.passkeyDomain] set, a passkey is the other
 * first login: [signUpWithPasskey] creates the account and [loginWithPasskey] returns to it.
 * [addPasskey] gives a code-created account a passkey too, and [sendContactVerificationCode] gives
 * a passkey-created account an email or phone to sign in with. Then register the provider and
 * resolve `rain.provider(ProviderId.RAIN)`; resolving before a session is live fails with
 * `RainError.TokenExpired` (`RAIN_201`).
 *
 * rain-wallet-android/README.md has the rules: the wallet auth token your backend mints and hands
 * the app through [RainWalletConfig.authTokenProvider], what leaves the device, the one-shot
 * configuration per launch and every member's row. The `build()` row under `RainSdk.Builder` in
 * `docs/METHODS.md` says why `RainSdk.Builder.build()` refuses a registry holding this provider
 * and the SDK's bring-your-own adapter for the same backend.
 */
@Suppress("TooManyFunctions") // the descriptor is also the authentication, session and export surface, by design
class RainProvider internal constructor(
    private val backing: TurnkeyProvider,
) : ProviderDescriptor {

    /**
     * Creates the Rain wallet provider for this app.
     *
     * @param application The host application; the wallet backend needs it for secure storage.
     * @param config The token supplier, the environment and behaviour the host may tune.
     * @throws RainError.InvalidConfig (`RAIN_102`) when [RainWalletConfig.passkeyDomain] is not a
     *   registrable domain of at least two labels made of letters, digits and hyphens (a scheme,
     *   port, path or a single label such as `localhost` is refused).
     */
    constructor(application: Application, config: RainWalletConfig) : this(
        TurnkeyProvider(config.toBacking(application))
    )

    private val closed = AtomicBoolean(false)

    override val id: ProviderId get() = ProviderId.RAIN

    /**
     * [Capability.EXPORT] and [Capability.MULTI_CHAIN] always, plus [Capability.GAS_SPONSORSHIP]
     * while [RainWalletConfig.sponsorGas] is on. `RainSdk` copies this set onto the resolved client.
     * Signing is not gated behind a biometric prompt; gate export yourself, see [exportRecoveryPhrase].
     */
    override val capabilities: Set<Capability> get() = backing.capabilities

    /**
     * Materializes the wallet for the registry; core calls it on the first
     * `rain.provider(ProviderId.RAIN)`. It re-checks the account set, so a login whose provisioning
     * failed heals here, and probes the Ethereum account. The `create` row in
     * rain-wallet-android/README.md has the rules.
     *
     * @throws RainError.TokenExpired (`RAIN_201`)
     * @throws RainError.InvalidConfig (`RAIN_102`)
     * @throws RainError.WalletUnavailable (`RAIN_404`)
     * @throws RainError.InternalError (`RAIN_502`)
     */
    override suspend fun create(context: ProviderContext): WalletProvider = backing.create(context)

    /**
     * Stops the passive session watcher and makes this provider inert, so a discarded provider can
     * never fire its expiry hook; call it when rebuilding the SDK for a new login. Afterwards every
     * authentication and export call throws `RainError.InvalidConfig` (`RAIN_102`). [sessionState]
     * and [currentSessionState] still report the process-wide backend session afterwards; collect
     * [sessionState] in a scope you cancel with the provider. The `close` row in
     * rain-wallet-android/README.md has the rules.
     */
    override fun close() {
        closed.set(true)
        backing.close()
    }

    /** The id, the advertised capabilities and whether [close] ran; never a contact or a session. */
    override fun toString(): String =
        "RainProvider(id=${id.value}, capabilities=${backing.capabilities}, closed=${closed.get()})"

    // ---------- Session ----------

    /**
     * The wallet session over time. Emits on every authentication or session change and when an
     * active session passes its expiry, so a host can react to a session dying silently without
     * waiting for a wallet call to fail. The same instance on every read, so a Compose collector
     * keyed on it stays subscribed across recompositions.
     */
    val sessionState: Flow<RainWalletSessionState> by lazy {
        backing.sessionState.map { it.toRainWallet() }
    }

    /** Snapshot of [sessionState] right now. */
    fun currentSessionState(): RainWalletSessionState = backing.currentSessionState().toRainWallet()

    /**
     * Forces a session refresh regardless of remaining lifetime. It is the call that tells a revoked
     * session apart from a stored one: a refresh the backend refused clears the stored session
     * before the throw and fires [RainWalletConfig.onSessionExpired] once. The `refreshSession` row
     * in rain-wallet-android/README.md and *Session expiry, refresh, and retry* in
     * docs/TURNKEY_SUPPORT.md have the rules.
     *
     * @throws RainError.TokenExpired (`RAIN_201`)
     * @throws RainError.NetworkError (`RAIN_301`)
     * @throws RainError.ProviderError (`RAIN_501`)
     * @throws RainError.InvalidConfig (`RAIN_102`)
     */
    suspend fun refreshSession() {
        backing.refreshSession()
    }

    // ---------- Authentication (one-time code by email or SMS) ----------

    /**
     * Where authentication stands, over time: [RainWalletAuthState.Loading] until the first
     * authentication call has configured the wallet backend and any previous session has been
     * restored, [RainWalletAuthState.Authenticated] once a session is live,
     * [RainWalletAuthState.Unauthenticated] otherwise. The same instance on every read.
     */
    val authState: Flow<RainWalletAuthState> by lazy {
        backing.authState.map { it.toRainWallet() }
    }

    /** Snapshot of [authState] right now. */
    fun currentAuthState(): RainWalletAuthState = backing.currentAuthState().toRainWallet()

    /**
     * True when an unexpired session is already live, restored or just established, with more than
     * 30 seconds left, so the one-time-code step can be skipped. A session revoked from another
     * device still reads as active until a refresh asks; [refreshSession] is the call that asks.
     * False until the first authentication call has configured the wallet backend. The
     * `hasActiveSession` row in rain-wallet-android/README.md has the rules.
     */
    fun hasActiveSession(): Boolean = backing.hasActiveSession()

    /**
     * Waits for the asynchronous restore of a persisted session that follows configuration, so a
     * returning user's session is reused without a new code. A timeout returns normally and leaves
     * [authState] at [RainWalletAuthState.Loading]. On a launch's first authentication call the
     * backend's one-shot initialization runs first, which [timeoutMs] does not bound; the
     * `awaitSessionRestore` row in rain-wallet-android/README.md has the rules.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`)
     * @throws RainError.InternalError (`RAIN_502`)
     */
    suspend fun awaitSessionRestore(timeoutMs: Long = DEFAULT_RESTORE_TIMEOUT_MS) {
        backing.awaitSessionRestore(timeoutMs)
    }

    /**
     * Sends a one-time login code to [contact], by email or by SMS, under a wallet auth token from
     * [RainWalletConfig.authTokenProvider]; touches no existing session. Calling it again for the
     * same contact is how a code is resent. The `sendLoginCode` row in rain-wallet-android/README.md
     * has the rules.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`)
     * @throws RainError.Unauthorized (`RAIN_202`)
     * @throws RainError.NetworkError (`RAIN_301`)
     * @throws RainError.ProviderError (`RAIN_501`)
     * @throws RainError.InternalError (`RAIN_502`)
     * @see confirmLoginCode
     */
    suspend fun sendLoginCode(contact: RainWalletContact) {
        backing.sendLoginCode(contact.toBacking())
    }

    /**
     * Confirms the code from [sendLoginCode]: logs the contact's wallet in, or signs the contact up
     * when this tenant has none. A rejected code keeps the challenge, so the user can retype it; a
     * failure after the code was accepted drops it, so request a new code. The `confirmLoginCode`
     * row in rain-wallet-android/README.md has the rules.
     *
     * @return [RainWalletLoginOutcome.SignedIn], or [RainWalletLoginOutcome.SecondCodeRequired]
     *   carrying the contact the second code goes to, masked by Rain, with nothing logged in yet;
     *   [sendSecondLoginCode] then [confirmSecondLoginCode] finish the login.
     * @throws RainError.InvalidLoginCode (`RAIN_203`)
     * @throws RainError.InvalidConfig (`RAIN_102`)
     * @throws RainError.Unauthorized (`RAIN_202`)
     * @throws RainError.NetworkError (`RAIN_301`)
     * @throws RainError.ProviderError (`RAIN_501`)
     * @throws RainError.TokenExpired (`RAIN_201`)
     * @throws RainError.InternalError (`RAIN_502`)
     * @see sendLoginCode
     */
    suspend fun confirmLoginCode(code: String): RainWalletLoginOutcome = backing.confirmLoginCode(code).toRainWallet()

    /**
     * Has Rain send the second code of a two-code login to the account's other contact, the one
     * [RainWalletLoginOutcome.SecondCodeRequired] named, or sends it again. The attempt's token is
     * reused, so [RainWalletConfig.authTokenProvider] is not called; the `sendSecondLoginCode` row
     * in rain-wallet-android/README.md has the rules.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`)
     * @throws RainError.Unauthorized (`RAIN_202`)
     * @throws RainError.NetworkError (`RAIN_301`)
     * @throws RainError.ProviderError (`RAIN_501`)
     * @throws RainError.InternalError (`RAIN_502`)
     * @see confirmSecondLoginCode
     */
    suspend fun sendSecondLoginCode() {
        backing.sendSecondLoginCode()
    }

    /**
     * Confirms the code from [sendSecondLoginCode] and completes the login as [confirmLoginCode]
     * does. A rejected code keeps the attempt, so the user can retype it; every other failure of
     * the verify, Rain unreachable included, ends it, so start again with [sendLoginCode]. Each
     * ending's code is in the `confirmSecondLoginCode` row of rain-wallet-android/README.md.
     *
     * @throws RainError.InvalidLoginCode (`RAIN_203`)
     * @throws RainError.InvalidConfig (`RAIN_102`)
     * @throws RainError.Unauthorized (`RAIN_202`)
     * @throws RainError.NetworkError (`RAIN_301`)
     * @throws RainError.ProviderError (`RAIN_501`)
     * @throws RainError.TokenExpired (`RAIN_201`)
     * @throws RainError.InternalError (`RAIN_502`)
     * @see sendSecondLoginCode
     */
    suspend fun confirmSecondLoginCode(code: String) {
        backing.confirmSecondLoginCode(code)
    }

    /**
     * Clears the stored session without firing [RainWalletConfig.onSessionExpired]; a pending login
     * code, a pending contact verification and a two-code attempt (its key deleted) are dropped.
     * [hasActiveSession] and [currentAuthState] read unauthenticated as soon as it returns. With no
     * session selected it clears no session but still drops them and runs the readiness checks. The
     * `logout` row in rain-wallet-android/README.md has the rules.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`)
     * @throws RainError.InternalError (`RAIN_502`)
     */
    suspend fun logout() {
        backing.logout()
    }

    // ---------- Passkeys ----------

    /**
     * Signs an existing user in with a passkey through the system passkey sheet. The account is
     * the one the passkey was created for; a first-time user has none and uses [signUpWithPasskey]
     * or a login code. A successful login revokes the user's sessions on other devices; a dismissed
     * sheet, a failed ceremony or a refused passkey leaves the current session untouched. The
     * `loginWithPasskey` row in rain-wallet-android/README.md has the rules, and *Errors* there the
     * ceremony's codes.
     *
     * @param activity The foreground Activity the system passkey sheet is presented from, so the
     *   sheet lands in the app's task. The call suspends until the sheet closes, which can take as
     *   long as the user takes. Cancelling the calling coroutine dismisses the sheet on Android 14
     *   and later and, on earlier versions, abandons its result when it arrives; either way the
     *   call ends with the cancellation, never with a mapped error. Run it in a scope that survives
     *   configuration changes, such as a ViewModel scope, and pass the Activity at the call: the
     *   SDK uses it only to launch the sheet and retains it no longer than the call.
     * @throws RainError.InvalidConfig (`RAIN_102`)
     * @throws RainError.UserRejected (`RAIN_401`)
     * @throws RainError.ProviderError (`RAIN_501`)
     * @throws RainError.TokenExpired (`RAIN_201`)
     * @throws RainError.Unauthorized (`RAIN_202`)
     * @throws RainError.InternalError (`RAIN_502`)
     * @see signUpWithPasskey
     */
    suspend fun loginWithPasskey(activity: Activity) {
        backing.loginWithPasskey(activity)
    }

    /**
     * Creates a new account whose only login is a passkey bound to [RainWalletConfig.passkeyDomain],
     * with an Ethereum and a Solana account in one wallet, and signs it in with one prompt. Each new
     * passkey is a new account, so a returning user signs in with [loginWithPasskey] or a login
     * code. Call [logout] first unless [currentAuthState] reads `Unauthenticated`; [hasActiveSession]
     * reads false in a session's last 30 seconds, which still refuses. A failure can leave an
     * account behind; the `signUpWithPasskey` row and section in rain-wallet-android/README.md say when,
     * which code tells you, and when the sheet opens a second time.
     *
     * @param activity The foreground Activity the sheet is presented from; see [loginWithPasskey]
     *   for its lifetime and what cancellation does.
     * @throws RainError.InvalidConfig (`RAIN_102`)
     * @throws RainError.UserRejected (`RAIN_401`)
     * @throws RainError.Unauthorized (`RAIN_202`)
     * @throws RainError.NetworkError (`RAIN_301`)
     * @throws RainError.ProviderError (`RAIN_501`)
     * @throws RainError.TokenExpired (`RAIN_201`)
     * @throws RainError.InternalError (`RAIN_502`)
     * @see loginWithPasskey
     */
    suspend fun signUpWithPasskey(activity: Activity) {
        backing.signUpWithPasskey(activity)
    }

    /**
     * Registers a passkey on the signed-in account, so the next sign-in can use [loginWithPasskey];
     * no new account and no session change. Requires a live session, checked before the sheet. Gate
     * the call as you gate export; *Passkeys and login contacts* in rain-wallet-android/README.md
     * says why. The `addPasskey` row there has the rules, and *Errors* the ceremony's codes.
     *
     * @param activity The foreground Activity the sheet is presented from; see [loginWithPasskey]
     *   for its lifetime and what cancellation does.
     * @throws RainError.InvalidConfig (`RAIN_102`)
     * @throws RainError.TokenExpired (`RAIN_201`)
     * @throws RainError.UserRejected (`RAIN_401`)
     * @throws RainError.Unauthorized (`RAIN_202`)
     * @throws RainError.ProviderError (`RAIN_501`)
     * @throws RainError.InternalError (`RAIN_502`)
     */
    suspend fun addPasskey(activity: Activity) {
        backing.addPasskey(activity)
    }

    // ---------- Attach a login contact ----------

    /**
     * Starts attaching [contact] to the signed-in account as a further login method: Rain sends it a
     * verification code for [confirmContactVerification]. Not a login; that is [sendLoginCode].
     * Requires a live session. Gate the call as you gate export, and show the user which contacts
     * sign in to the account; *Attaching a contact* in rain-wallet-android/README.md says why, and
     * the `sendContactVerificationCode` row there has the rules.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`)
     * @throws RainError.TokenExpired (`RAIN_201`)
     * @throws RainError.Unauthorized (`RAIN_202`)
     * @throws RainError.NetworkError (`RAIN_301`)
     * @throws RainError.ProviderError (`RAIN_501`)
     * @throws RainError.InternalError (`RAIN_502`)
     * @see confirmContactVerification
     */
    suspend fun sendContactVerificationCode(contact: RainWalletContact) {
        backing.sendContactVerificationCode(contact.toBacking())
    }

    /**
     * Confirms the code from [sendContactVerificationCode] and attaches the verified contact to the
     * signed-in account. Requires a live session, checked before the code is spent. A contact
     * another account in this tenant signs in with is refused once the code is accepted, nothing
     * changed. Retry rules as for [confirmLoginCode]; the `confirmContactVerification` row in
     * rain-wallet-android/README.md has the rules, and *Attaching a contact* there says what an
     * attach refuses and what it replaces.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`)
     * @throws RainError.TokenExpired (`RAIN_201`)
     * @throws RainError.InvalidLoginCode (`RAIN_203`)
     * @throws RainError.Unauthorized (`RAIN_202`)
     * @throws RainError.NetworkError (`RAIN_301`)
     * @throws RainError.ProviderError (`RAIN_501`)
     * @throws RainError.InternalError (`RAIN_502`)
     * @see sendContactVerificationCode
     */
    suspend fun confirmContactVerification(code: String) {
        backing.confirmContactVerification(code)
    }

    // ---------- Key export ----------

    /**
     * The wallet's BIP-39 recovery phrase, 12 words, decrypted on this device and returned once.
     * The SDK never logs, caches or persists it. Requires a live session. The wallet keeps its
     * Ethereum account at `m/44'/60'/0'/0/0` and its Solana account at `m/44'/501'/0'/0'` on this
     * one seed, a cross-platform contract shared by Rain's SDKs. So this one phrase restores both
     * in any wallet that derives those paths. Everything after the return is the host's duty:
     * gating, display and the clipboard; *Key export* in docs/TURNKEY_SUPPORT.md has the rules and *Key export errors* there the
     * codes.
     *
     * @throws RainError.TokenExpired (`RAIN_201`)
     * @throws RainError.Unauthorized (`RAIN_202`)
     * @throws RainError.WalletUnavailable (`RAIN_404`)
     * @throws RainError.InvalidConfig (`RAIN_102`)
     * @throws RainError.ProviderError (`RAIN_501`)
     * @see exportPrivateKey
     */
    suspend fun exportRecoveryPhrase(): String = backing.exportRecoveryPhrase()

    /**
     * The private key of the wallet's [account], decrypted on this device and returned once. For
     * [RainWalletKeyAccount.ETHEREUM] it is the 32-byte secp256k1 key as `0x` plus 64 lowercase hex
     * characters, the form Ethereum wallets import. For [RainWalletKeyAccount.SOLANA] it is the
     * 64-byte keypair, the seed followed by the public key, in plain Base58, the string Solana
     * wallets import. Both keys derive from the seed behind [exportRecoveryPhrase], and the formats
     * are a cross-platform contract shared by Rain's SDKs. Requires a live session; host duties as
     * for [exportRecoveryPhrase], and *Key export* in docs/TURNKEY_SUPPORT.md has the rules and *Key export errors* there the
     * codes.
     *
     * @throws RainError.TokenExpired (`RAIN_201`)
     * @throws RainError.Unauthorized (`RAIN_202`)
     * @throws RainError.WalletUnavailable (`RAIN_404`)
     * @throws RainError.InvalidConfig (`RAIN_102`)
     * @throws RainError.ProviderError (`RAIN_501`)
     * @throws RainError.InternalError (`RAIN_502`)
     * @see exportRecoveryPhrase
     */
    suspend fun exportPrivateKey(account: RainWalletKeyAccount): String =
        backing.exportPrivateKey(account.toBacking())

    companion object {
        /** How long [awaitSessionRestore] waits by default, in milliseconds; inlined into callers. */
        const val DEFAULT_RESTORE_TIMEOUT_MS: Long = 5_000L
    }
}
