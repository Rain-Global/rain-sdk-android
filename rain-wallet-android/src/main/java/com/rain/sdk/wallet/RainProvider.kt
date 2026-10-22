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
 *         // Production is the default and is refused until Rain turns it on
 *         environment = RainWalletEnvironment.Sandbox,
 *     ),
 * )
 *
 * wallet.awaitSessionRestore()
 * if (!wallet.hasActiveSession()) {
 *     wallet.sendLoginCode(RainWalletContact.Email("user@example.com"))
 *     wallet.confirmLoginCode(code) // sign-up or login, plus wallet provisioning
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
 * Send a one-time login code to the user's email or phone, confirm it (a first login signs the
 * user up and creates one wallet holding an Ethereum and a Solana account on a single seed; a
 * returning login finds the account), then register the provider and resolve
 * `rain.provider(ProviderId.RAIN)`. Every call to Rain's endpoints (the code request, the confirm,
 * the passkey sign-up and the attach) runs under a wallet auth token your backend mints with its
 * Api-Key and hands the app through [RainWalletConfig.authTokenProvider]; what leaves the device is
 * listed in the module README. Resolving before a session is live fails with
 * `RainError.TokenExpired` (`RAIN_201`). With [RainWalletConfig.passkeyDomain] set, a passkey is
 * the other first login: [signUpWithPasskey] creates the account, [loginWithPasskey] returns to
 * it, [addPasskey] gives a code-created account a passkey too, and [sendContactVerificationCode]
 * gives a passkey-created account an email or phone to sign in with.
 *
 * The wallet backend's configuration is one-shot per app launch and the first authentication call
 * applies it, `environment` and `passkeyDomain` included. A backend the app configured itself, or
 * another provider in this process configured with a different environment or passkey domain, makes every
 * authentication call throw `RainError.InvalidConfig`
 * (`RAIN_102`) until the app relaunches; a failed backend initialization makes them throw
 * `RainError.InternalError` (`RAIN_502`) until then.
 *
 * The Rain wallet shares its process-wide wallet backend with the SDK's bring-your-own adapter for
 * that backend, so `RainSdk.Builder.build()` refuses a registry that holds both; the module README
 * names the adapter. That check is best-effort: a second `RainSdk` instance, or a provider that is
 * never registered, can still collide, which the backend reports as `RainError.InvalidConfig` on
 * the first authentication call.
 */
@Suppress("TooManyFunctions") // the descriptor is also the authentication, session and export surface, by design
class RainProvider internal constructor(
    private val backing: TurnkeyProvider,
) : ProviderDescriptor {

    /**
     * Creates the Rain wallet provider for this app.
     *
     * @param application The host application; the wallet backend needs it for secure storage.
     * @param config The token supplier, the environment and behaviour the host may tune; the
     *   defaults are the product, and the default environment, [RainWalletEnvironment.Production],
     *   is refused this release, so pass [RainWalletEnvironment.Sandbox].
     * @throws RainError.InvalidConfig (`RAIN_102`) when [RainWalletConfig.passkeyDomain] is not a
     *   registrable domain of at least two labels made of letters, digits and hyphens (a scheme,
     *   port, path or a single label such as `localhost` is refused), when a
     *   [RainWalletEnvironment.Custom] URL is not absolute https naming a host and nothing more, or
     *   is on production's host, or when the environment is [RainWalletEnvironment.Production],
     *   which this release refuses.
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
     * failed heals here, and probes the Ethereum account.
     *
     * @throws RainError.TokenExpired (`RAIN_201`) before a session is live.
     * @throws RainError.InvalidConfig (`RAIN_102`) when the backend was configured outside the SDK
     *   or with a different identity during this launch, or when this provider was closed.
     * @throws RainError.WalletUnavailable (`RAIN_404`) when the account has no Ethereum wallet.
     * @throws RainError.InternalError (`RAIN_502`) when the backend's initialization failed.
     */
    override suspend fun create(context: ProviderContext): WalletProvider = backing.create(context)

    /**
     * Stops the passive session watcher and makes this provider inert. Call it when discarding the
     * provider (for example when rebuilding the SDK for a new login), so a stale provider can never
     * fire its expiry hook again. Afterwards every authentication and export call throws
     * `RainError.InvalidConfig` (`RAIN_102`), [currentAuthState] reads
     * [RainWalletAuthState.Unauthenticated] and [hasActiveSession] is false. [sessionState] and
     * [currentSessionState] keep reporting the process-wide backend session, so collect
     * [sessionState] in a scope the host cancels when it discards the provider.
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
     * Forces a session refresh (extended expiry) regardless of remaining lifetime. When the wallet
     * backend refused the refresh (a login on another device revoked the session), the stored
     * session is cleared before the throw and `onSessionExpired` fires once, so [hasActiveSession],
     * [currentAuthState] and [currentSessionState] read signed out when this returns. A refresh
     * that failed for another reason (offline, a 5xx) throws the same error but leaves the session
     * stored. The same happens inside any wallet call that refreshes with
     * [RainWalletSessionPolicy.autoRefresh] on.
     *
     * @throws RainError.TokenExpired (`RAIN_201`) when the session cannot be refreshed; re-authenticate.
     * @throws RainError.InvalidConfig (`RAIN_102`) when this provider was closed.
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
     * 30 seconds left, so the one-time-code step can be skipped. The answer comes from the stored
     * session, and a session the wallet backend refuses to refresh is cleared with that failure
     * (see [refreshSession]), so this reads false from then on; until a refresh asks, a session
     * revoked from another device still reads as active. False until the first authentication call
     * has configured the wallet backend.
     */
    fun hasActiveSession(): Boolean = backing.hasActiveSession()

    /**
     * Waits for the asynchronous restore of a persisted session that follows configuration, so a
     * returning user's session is reused without a new code. A timeout returns normally and leaves
     * [authState] at [RainWalletAuthState.Loading]. As the first authentication call of a launch
     * it also runs the wallet backend's one-shot initialization first, which [timeoutMs] does not
     * bound.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`) when the backend was configured outside the SDK
     *   or with a different identity during this launch.
     * @throws RainError.InternalError (`RAIN_502`) when the backend's initialization failed.
     */
    suspend fun awaitSessionRestore(timeoutMs: Long = DEFAULT_RESTORE_TIMEOUT_MS) {
        backing.awaitSessionRestore(timeoutMs)
    }

    /**
     * Sends a one-time login code to [contact], by email or by SMS: asks
     * [RainWalletConfig.authTokenProvider] for a wallet auth token bound to the canonical contact,
     * then has Rain deliver the code; touches no existing session. Calling it again for the same
     * contact issues a new code and replaces the pending one, which is how a code is resent (a code
     * lives 5 minutes, and Rain limits code requests). Calling it for another contact or channel
     * retires the pending code first, so a failed switch leaves nothing confirmable. The contact is
     * canonicalized once, an email trimmed and lowercased (see [RainWalletContact]), and that string
     * names the account. The `sendLoginCode` row under *Rain wallet provider* in `docs/METHODS.md`
     * says which condition maps to which code.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`) on a bad contact, a failed token provider, a
     *   configuration conflict or a closed provider.
     * @throws RainError.Unauthorized (`RAIN_202`) when Rain refuses the token (after one fresh one)
     *   or the tenant.
     * @throws RainError.NetworkError (`RAIN_301`) when Rain, or your backend behind the token
     *   provider, cannot be reached.
     * @throws RainError.ProviderError (`RAIN_501`) when Rain refuses the code request.
     * @throws RainError.InternalError (`RAIN_502`) when the backend's initialization failed for
     *   this launch, or when Rain's answer could not be read.
     * @see confirmLoginCode
     */
    suspend fun sendLoginCode(contact: RainWalletContact) {
        backing.sendLoginCode(contact.toBacking())
    }

    /**
     * Confirms the code from [sendLoginCode]: logs the contact's wallet in, or signs the contact up
     * when this tenant has none, creating one wallet holding an Ethereum and a Solana account inside
     * the sign-up. A returning login backfills a missing account onto the existing wallet. A
     * rejected code keeps the challenge, so the user can retype it; a failure after the code was
     * accepted drops it, so request a new code. A failed switch to the new session leaves the
     * previous session selected; a provisioning failure keeps the new session, which heals at
     * resolution. A code login does not revoke the user's sessions on other devices. The
     * `confirmLoginCode` row under *Rain wallet provider* in `docs/METHODS.md` says which condition
     * maps to which code.
     *
     * @throws RainError.InvalidLoginCode (`RAIN_203`) when the code is rejected.
     * @throws RainError.InvalidConfig (`RAIN_102`) on a precondition: no code requested, a blank
     *   code, a failed token provider, a closed provider.
     * @throws RainError.Unauthorized (`RAIN_202`) when Rain refuses the token or the tenant, or from
     *   the account backfill.
     * @throws RainError.NetworkError (`RAIN_301`) when Rain, or your backend behind the token
     *   provider, cannot be reached.
     * @throws RainError.ProviderError (`RAIN_501`) for every other refusal by Rain.
     * @throws RainError.TokenExpired (`RAIN_201`) from the session switch after the code was accepted.
     * @throws RainError.InternalError (`RAIN_502`) when the backend's initialization failed for
     *   this launch, or when Rain's answer could not be read.
     * @see sendLoginCode
     */
    suspend fun confirmLoginCode(code: String) {
        backing.confirmLoginCode(code)
    }

    /**
     * Clears the stored session (full logout), after waiting for a restore in flight to settle,
     * without firing [RainWalletConfig.onSessionExpired]; a pending login code and a pending contact
     * verification are dropped.
     * [hasActiveSession] and [currentAuthState] read unauthenticated as soon as it returns. A no-op
     * when no session is selected, but not infallible: it runs the same backend readiness checks as
     * every other authentication call.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`) when this provider was closed, or on a wallet
     *   backend configuration conflict for this launch.
     * @throws RainError.InternalError (`RAIN_502`) when the backend's initialization failed for
     *   this launch; relaunch the app.
     */
    suspend fun logout() {
        backing.logout()
    }

    // ---------- Passkeys ----------

    /**
     * Signs an existing user in with a passkey through the system passkey sheet. The account is
     * the one the passkey was created for; a first-time user has none and uses [signUpWithPasskey]
     * or a login code. A successful login stores the session under a fresh key, selects it, clears
     * the previous session, revokes the user's other sessions on every device (the signed-out
     * device's [RainWalletConfig.onSessionExpired] fires at its next call) and backfills a missing
     * account onto the existing wallet. A dismissed sheet, a failed ceremony or a refused passkey
     * leaves the current session untouched.
     *
     * @param activity The foreground Activity the system passkey sheet is presented from, so the
     *   sheet lands in the app's task. The call suspends until the sheet closes, which can take as
     *   long as the user takes. Cancelling the calling coroutine dismisses the sheet on Android 14
     *   and later and, on earlier versions, abandons its result when it arrives; either way the
     *   call ends with the cancellation, never with a mapped error. Run it in a scope that survives
     *   configuration changes, such as a ViewModel scope, and pass the Activity at the call: the
     *   SDK uses it only to launch the sheet and retains it no longer than the call.
     * @throws RainError.InvalidConfig (`RAIN_102`) when [RainWalletConfig.passkeyDomain] is unset,
     *   when the domain's association file does not vouch for this build (its package name and
     *   signing-certificate fingerprint) or lacks the site's own statement, on a wallet backend
     *   configuration conflict for this launch, or when this provider was closed.
     * @throws RainError.UserRejected (`RAIN_401`) when the sheet was dismissed or the device holds
     *   no passkey for the domain.
     * @throws RainError.ProviderError (`RAIN_501`) for an interrupted ceremony, a device without a
     *   passkey provider, a login the backend refused, or any other failure of the ceremony.
     * @throws RainError.TokenExpired (`RAIN_201`) from the session switch after the ceremony
     *   succeeded.
     * @throws RainError.Unauthorized (`RAIN_202`) from the account backfill after the login itself
     *   succeeded; the session is kept and heals at resolution.
     * @throws RainError.InternalError (`RAIN_502`) when the backend's initialization failed for
     *   this launch (relaunch the app), or when the backend answered the ceremony with an unusable
     *   response (no session token, an occupied session key); retry the call.
     */
    suspend fun loginWithPasskey(activity: Activity) {
        backing.loginWithPasskey(activity)
    }

    /**
     * Creates a new account whose only login method is a passkey bound to
     * [RainWalletConfig.passkeyDomain], with one wallet holding an Ethereum and a Solana account
     * created inside the same request, then signs it in as [loginWithPasskey] does. Every call
     * mints a fresh account, so a returning user must use [loginWithPasskey] or a login code, or
     * they end up with a second, empty wallet; accounts are never merged. The sheet opens twice,
     * once to create the passkey and once to sign the new account in; the token provider is called
     * with no contact first. Call [logout] first when [hasActiveSession] is true, because a live
     * session stored as this device's current one is refused with `RainError.InvalidConfig`; one
     * that has already expired is cleared first, with the `onSessionExpired` hook silent. What a
     * failure leaves behind depends on when it happened: before Rain's sign-up answered, no account
     * exists; once it answered, the account exists and [loginWithPasskey] reaches it, a dismissed
     * second sheet included; when its answer was lost or could not be read, the account may exist,
     * so offer [loginWithPasskey] before calling this again. The `signUpWithPasskey` row under
     * *Rain wallet provider* in `docs/METHODS.md` says which code says which.
     * [exportRecoveryPhrase] is the backup path for an account whose only login is a passkey;
     * [sendContactVerificationCode] adds an email or phone as a second one.
     *
     * @param activity The foreground Activity the sheet is presented from; see [loginWithPasskey]
     *   for its lifetime and what cancellation does.
     * @throws RainError.InvalidConfig (`RAIN_102`) without a `passkeyDomain`, when the association
     *   file does not vouch for this build, while a live session is stored, on a failed token
     *   provider, a configuration conflict or a closed provider.
     * @throws RainError.UserRejected (`RAIN_401`) when either sheet was dismissed.
     * @throws RainError.Unauthorized (`RAIN_202`) when Rain refuses the token or the tenant, or from
     *   the account backfill.
     * @throws RainError.NetworkError (`RAIN_301`) when Rain, or your backend behind the token
     *   provider, cannot be reached.
     * @throws RainError.ProviderError (`RAIN_501`) for a ceremony, sign-up or login the device or
     *   Rain refused.
     * @throws RainError.TokenExpired (`RAIN_201`) from the session switch after the sign-up succeeded.
     * @throws RainError.InternalError (`RAIN_502`) when the backend's initialization failed for
     *   this launch, or when an answer could not be read.
     * @see loginWithPasskey
     */
    suspend fun signUpWithPasskey(activity: Activity) {
        backing.signUpWithPasskey(activity)
    }

    /**
     * Registers a passkey bound to [RainWalletConfig.passkeyDomain] on the signed-in account, so the
     * next sign-in can use [loginWithPasskey]. Requires a live session, checked before the sheet, so
     * a dead session fails before any biometric prompt. A session revoked server-side between that
     * check and the registration still surfaces as `RAIN_201` after the prompt, with
     * [RainWalletConfig.onSessionExpired] firing; a registration the backend refused leaves a
     * passkey on the device that signs into nothing. The passkey request asks the credential
     * provider for user verification as preferred, not required, so gate this call as you gate
     * export. No new account and no session change; other
     * authentication calls wait while the sheet is open. One passkey per device is enough; each
     * call registers another.
     *
     * @param activity The foreground Activity the sheet is presented from; see [loginWithPasskey]
     *   for its lifetime and what cancellation does.
     * @throws RainError.InvalidConfig (`RAIN_102`) when [RainWalletConfig.passkeyDomain] is unset,
     *   when the domain's association file does not vouch for this build, on a wallet backend
     *   configuration conflict for this launch, or when this provider was closed.
     * @throws RainError.TokenExpired (`RAIN_201`) without a live session.
     * @throws RainError.UserRejected (`RAIN_401`) when the sheet was dismissed.
     * @throws RainError.Unauthorized (`RAIN_202`) when the backend refuses the registration.
     * @throws RainError.ProviderError (`RAIN_501`) for an interrupted ceremony, a device without a
     *   passkey provider, or a registration the backend failed, its per-user limit included.
     * @throws RainError.InternalError (`RAIN_502`) when the backend's initialization failed for
     *   this launch (relaunch the app), or when the ceremony produced an unusable registration.
     */
    suspend fun addPasskey(activity: Activity) {
        backing.addPasskey(activity)
    }

    // ---------- Attach a login contact ----------

    /**
     * Sends a verification code to a contact the user wants to attach to the signed-in account, so
     * that contact becomes a login method for this account; distinct from [sendLoginCode], which
     * starts a login. Requires a live session. Asks [RainWalletConfig.authTokenProvider] for a token
     * bound to the canonical contact (see [RainWalletContact]), then has Rain deliver the code; that
     * canonical string is what the account stores. Accounts are never merged, and nothing checks
     * whether another account signs in with the contact; see [confirmContactVerification]. Calling
     * it again for the same contact replaces the pending code; a call for another contact or channel retires it
     * before anything is sent, so a failed switch leaves nothing confirmable; a login or a [logout]
     * drops it. The SDK adds no user-presence check before the attach beyond the live session,
     * which can be one restored at launch, so gate the call as you gate export, with a biometric
     * prompt or a fresh login, and show the user which contacts sign in to the account.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`) for a blank email or a phone number outside
     *   E.164, when the token provider failed, on a wallet backend configuration conflict for this
     *   launch, or when this provider was closed.
     * @throws RainError.TokenExpired (`RAIN_201`) without a live session; nothing is sent.
     * @throws RainError.Unauthorized (`RAIN_202`) when Rain refuses the wallet auth token or the tenant.
     * @throws RainError.NetworkError (`RAIN_301`) when Rain cannot be reached.
     * @throws RainError.ProviderError (`RAIN_501`) when Rain refuses the code request.
     * @throws RainError.InternalError (`RAIN_502`) when the backend's initialization failed for
     *   this launch, or when Rain's answer could not be read.
     * @see confirmContactVerification
     */
    suspend fun sendContactVerificationCode(contact: RainWalletContact) {
        backing.sendContactVerificationCode(contact.toBacking())
    }

    /**
     * Confirms the code from [sendContactVerificationCode] and attaches the verified contact to the
     * signed-in account. Requires a live session, checked before the code is spent. Once the code
     * is accepted, the update sets the account's email or phone to the contact, replacing the one
     * of the same kind it had, if any. Nothing checks whether another account in this tenant signs
     * in with the contact: an attach of such a contact succeeds and both accounts then lose code
     * login, a known issue; see the CHANGELOG. A rejected code keeps the challenge, so the user can
     * retype it; a failure after the code was accepted drops it, so request a new code. Fails with
     * the classes below; the `confirmContactVerification` row under *Rain wallet provider* in
     * `docs/METHODS.md` says which condition maps to which.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`) on a precondition: no code requested, a blank
     *   code, a failed token provider, a configuration conflict, a closed provider.
     * @throws RainError.TokenExpired (`RAIN_201`) without a live session.
     * @throws RainError.InvalidLoginCode (`RAIN_203`) when the code is rejected.
     * @throws RainError.Unauthorized (`RAIN_202`) when Rain refuses the token or the tenant, or the
     *   backend refuses the update.
     * @throws RainError.NetworkError (`RAIN_301`) when Rain cannot be reached.
     * @throws RainError.ProviderError (`RAIN_501`) when Rain or the backend failed.
     * @throws RainError.InternalError (`RAIN_502`) when the backend's initialization failed for
     *   this launch, or when Rain's answer could not be read.
     * @see sendContactVerificationCode
     */
    suspend fun confirmContactVerification(code: String) {
        backing.confirmContactVerification(code)
    }

    // ---------- Key export ----------

    /**
     * The wallet's BIP-39 recovery phrase, 12 words, decrypted on this device and returned once.
     * The SDK never logs, caches or persists it. The wallet keeps its Ethereum account at
     * `m/44'/60'/0'/0/0` and its Solana account at `m/44'/501'/0'/0'` on this one seed, a
     * cross-platform contract shared by Rain's SDKs, so this one phrase restores both in any wallet
     * that derives those paths. Export is the backup path for a user who signs in with a passkey
     * only.
     *
     * Everything after the return is the host's duty. Gate the call, for example behind biometrics.
     * Show the phrase where screenshots and screen recording are blocked. Keep it off the
     * clipboard, or clear it. A transient failure is retried with a new request.
     *
     * @throws RainError.TokenExpired (`RAIN_201`) without a live session.
     * @throws RainError.Unauthorized (`RAIN_202`) when the wallet backend refuses the export.
     * @throws RainError.WalletUnavailable (`RAIN_404`) when the account has no wallet yet.
     * @throws RainError.InvalidConfig (`RAIN_102`) when this provider was closed.
     * @throws RainError.ProviderError (`RAIN_501`) when the export is still pending after about four
     *   seconds or its bundle is rejected on this device.
     */
    suspend fun exportRecoveryPhrase(): String = backing.exportRecoveryPhrase()

    /**
     * The private key of the wallet's [account], decrypted on this device and returned once. For
     * [RainWalletKeyAccount.ETHEREUM] it is the 32-byte secp256k1 key as `0x` plus 64 lowercase hex
     * characters, the form Ethereum wallets import. For [RainWalletKeyAccount.SOLANA] it is the
     * 64-byte keypair, the seed followed by the public key, in plain Base58, the string Solana
     * wallets import. Before returning anything the SDK checks that the key is 32 bytes and that
     * the address it derives is the account's. Both keys derive from the seed behind
     * [exportRecoveryPhrase]. The formats are a cross-platform contract shared by Rain's SDKs.
     *
     * Everything after the return is the host's duty, as for [exportRecoveryPhrase]. A transient
     * failure is retried with a new request.
     *
     * @throws RainError.TokenExpired (`RAIN_201`) without a live session.
     * @throws RainError.Unauthorized (`RAIN_202`) when the wallet backend refuses the export.
     * @throws RainError.WalletUnavailable (`RAIN_404`) when the wallet has no account of that
     *   family, which a new login provisions, or when that account sits on another curve than the
     *   family's.
     * @throws RainError.InvalidConfig (`RAIN_102`) when this provider was closed.
     * @throws RainError.ProviderError (`RAIN_501`) when the export is still pending after about four
     *   seconds, its bundle is rejected on this device, or the bundle names another account than the
     *   one requested.
     * @throws RainError.InternalError (`RAIN_502`) when the exported material fails a check, in
     *   which case nothing is returned.
     */
    suspend fun exportPrivateKey(account: RainWalletKeyAccount): String =
        backing.exportPrivateKey(account.toBacking())

    companion object {
        /** How long [awaitSessionRestore] waits by default, in milliseconds; inlined into callers. */
        const val DEFAULT_RESTORE_TIMEOUT_MS: Long = 5_000L
    }
}
