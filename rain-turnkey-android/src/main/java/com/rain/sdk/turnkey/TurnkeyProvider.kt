package com.rain.sdk.turnkey

import android.app.Activity
import android.app.Application
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.utils.validateAndChecksumAddress
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderDescriptor
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.WalletProvider
import com.turnkey.core.TurnkeyContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.util.concurrent.atomic.AtomicBoolean

/** The message every call on a closed provider carries; one literal so the two guards cannot drift. */
internal const val TURNKEY_PROVIDER_CLOSED_MESSAGE = "This provider was closed; build a new one"

/**
 * Configuration for the Turnkey provider.
 *
 * - **Bring-your-own** — `TurnkeyConfig(turnkey)`, the public Turnkey integration: the host drives
 *   Turnkey's Kotlin SDK itself (passkeys, auth proxy, OAuth, OTP), completes login, and hands the
 *   authenticated [TurnkeyContext] singleton here. The SDK never touches authentication.
 * - **Managed** — `TurnkeyConfig(application, organizationId, rainApiBaseUrl, authTokenProvider)`:
 *   the SDK owns authentication (a one-time code by email or SMS through Rain's embedded-wallet
 *   endpoints, or a passkey; Ethereum and Solana accounts provisioned on first login). Internal
 *   API, marked
 *   [InternalRainTurnkeyApi]: the building block of the Rain wallet provider (`RainProvider` in `rain-wallet-android`), not a host-facing mode.
 *
 * @param turnkey The `TurnkeyContext` singleton every wallet call goes through — authenticated by
 *                the host in bring-your-own mode, configured and authenticated by the SDK in
 *                managed mode.
 * @param walletAddress Optional explicit EVM address override; when null Rain uses the first
 *                      available Ethereum account from the context. It also anchors key export:
 *                      the recovery phrase and the Ethereum key come from the account at this
 *                      address, and a value that names no Ethereum account of the organization
 *                      fails every export before any export call.
 * @param sessionPolicy Expiry/refresh/retry behavior for the session guarding every wallet call.
 * @param onSessionExpired Re-auth hook: invoked once per session death when the Turnkey session
 *                         dies and cannot be refreshed — whether that is discovered during a
 *                         wallet call or by the passive session watcher. May run on the calling
 *                         coroutine's thread or a watcher thread; hop to the main thread before
 *                         touching UI, and never call back into the SDK synchronously from it.
 *                         Restart authentication from here (in managed mode: `sendLoginCode` /
 *                         `confirmLoginCode` again, or `loginWithPasskey`). A deliberate `logout()`
 *                         does not fire it.
 * @param sponsorGas When true, every EVM send on a Turnkey broadcast chain is sponsored —
 *                   transfers, collateral withdrawals, Auth Pull approvals, and raw
 *                   `sendTransaction` calls alike: Turnkey's Gas Station builds and pays the
 *                   fee (gasless for the end user); fee estimates still quote what the wallet
 *                   would pay itself. Sponsorship cost passes through to the partner that turns
 *                   this on. Solana sends are sponsored too, the network fee only: the rent of a
 *                   first-time recipient's token account follows [sponsorRent].
 *                   Defaults to true: sponsorship is the product, and Turnkey enables it at the
 *                   parent-organization level. On an organization where it is not enabled,
 *                   Turnkey rejects sponsored sends, so pass false there (Rain ops enables it
 *                   per Turnkey organization). Sponsored sends have no client-side revert
 *                   preflight; failures surface through Turnkey's decoded FAILED status.
 * @param sponsorRent Whether the organization's "Sponsor Solana Rent" switch is on, so the SDK
 *                    stops demanding the rent of a recipient token account a Solana send creates
 *                    from the sender; read with [sponsorGas], and off by default like the switch.
 *                    *Rent* under Solana notes in `docs/TURNKEY_SUPPORT.md` has the contract.
 */
class TurnkeyConfig
@Suppress("LongParameterList") // one knob per parameter plus the managed ids; the secondary constructors default most of them
internal constructor(
    val turnkey: TurnkeyContext,
    val walletAddress: String?,
    val sessionPolicy: TurnkeySessionPolicy,
    val onSessionExpired: (() -> Unit)?,
    val sponsorGas: Boolean,
    val sponsorRent: Boolean,
    /** Managed-mode ids; null in bring-your-own mode, where the host owns authentication. */
    internal val managed: ManagedIds?,
) {
    /** SDK-managed auth against the Turnkey organization and Rain's API. */
    internal class ManagedIds(
        val application: Application,
        val organizationId: String,
        /** Rain's API host; https, validated at construction. */
        val rainApiBaseUrl: HttpUrl,
        /** The host's wallet auth token supplier; asked with no contact on a passkey sign-up. */
        val authTokenProvider: suspend (LoginContact?) -> String,
        /** The passkey relying-party domain, normalized; null when passkeys are off. */
        val passkeyDomain: String?,
    ) {
        /** The base URL without the trailing slash `HttpUrl` prints on a bare host; the one-shot configuration keys on it. */
        val rainApiBaseUrlText: String get() = rainApiBaseUrl.toString().trimEnd('/')
    }

    /** Managed mode's organization id; null in bring-your-own mode. Read by the Rain wallet's tests. */
    @InternalRainTurnkeyApi
    val managedOrganizationId: String? get() = managed?.organizationId

    /** Managed mode's Rain API base URL without a trailing slash; null in bring-your-own mode. */
    @InternalRainTurnkeyApi
    val managedRainApiBaseUrl: String? get() = managed?.rainApiBaseUrlText

    /** Managed mode's wallet auth token supplier; null in bring-your-own mode. Read by the Rain wallet's tests. */
    @InternalRainTurnkeyApi
    val managedAuthTokenProvider: (suspend (LoginContact?) -> String)? get() = managed?.authTokenProvider

    /**
     * Managed mode's passkey relying-party domain as normalized at construction; null in
     * bring-your-own mode and when passkeys are off. Read by the Rain wallet's tests.
     */
    @InternalRainTurnkeyApi
    val managedPasskeyDomain: String? get() = managed?.passkeyDomain

    /**
     * Bring-your-own mode — the public Turnkey integration.
     *
     * @param turnkey The authenticated `TurnkeyContext` singleton.
     * @param walletAddress Optional EVM address override, validated and stored in EIP-55 checksum
     *   form; it becomes the account the SDK signs with and reads for. Null or blank means no override.
     * @throws RainError.InvalidConfig (`RAIN_102`) when [walletAddress] is malformed or carries a
     *   wrong mixed-case checksum.
     */
    constructor(
        turnkey: TurnkeyContext,
        walletAddress: String? = null,
        sessionPolicy: TurnkeySessionPolicy = TurnkeySessionPolicy(),
        onSessionExpired: (() -> Unit)? = null,
        sponsorGas: Boolean = true,
        sponsorRent: Boolean = false,
    ) : this(turnkey, walletAddress.checksummedOrNull(), sessionPolicy, onSessionExpired, sponsorGas, sponsorRent, managed = null)

    /**
     * Managed mode — internal API reserved for the Rain wallet provider (`RainProvider` in `rain-wallet-android`), see [InternalRainTurnkeyApi].
     *
     * The Turnkey configuration is one-shot per app launch: the SDK applies it on the first
     * authentication call (or at provider resolution), `rainApiBaseUrl` and `passkeyDomain`
     * included. A blank id, a second managed provider with a *different* id, URL or domain, or a
     * `TurnkeyContext` the app initialized itself make every auth call throw
     * `RainError.InvalidConfig` — relaunch the app to change them; a failed Turnkey initialization
     * makes them throw `RainError.InternalError` until relaunch.
     *
     * A successful [TurnkeyProvider.loginWithPasskey] revokes the user's other Turnkey sessions on
     * every device (`invalidateExisting`): a passkey login on a second phone signs the first one
     * out, and that device's [onSessionExpired] fires at its next call. A successful
     * [TurnkeyProvider.confirmLoginCode] leaves other devices' sessions alone.
     *
     * @param application The host application; Turnkey's Kotlin SDK needs it for secure storage.
     * @param organizationId Your Turnkey parent organization id.
     * @param rainApiBaseUrl The Rain API host the authentication runs against, such as
     *   `https://api-dev.rain.xyz`; validated at construction.
     * @param authTokenProvider Returns the wallet auth token the host's backend mints for the contact
     *   given, the canonical contact of a code request or attach, or null for a passkey sign-up. It
     *   runs under the provider's authentication lock, so it must not call back into the provider,
     *   and it should carry its own timeout. docs/METHODS.md states the full contract under
     *   `RainWalletConfig.authTokenProvider`, the retry per refused leg and how a failure surfaces
     *   included.
     * @param walletAddress Optional EVM address override, validated and stored in EIP-55 checksum form; null or blank means no override.
     * @param passkeyDomain The passkey relying-party domain: a registrable domain of at least two labels
     *   the host controls, such as `passkeys.example.com`, whose `/.well-known/assetlinks.json` follows
     *   Google's passkey example: a `get_login_creds` statement for the site itself and an app statement
     *   with both `handle_all_urls` and `get_login_creds` listing the app's package name and
     *   signing-certificate fingerprints (the app statement alone is refused at the sheet). Null or blank
     *   turns the passkey methods off; they then throw
     *   `RainError.InvalidConfig`. The domain is permanent: every passkey created against it stops
     *   working when it changes. It is part of the one-shot configuration, so a second managed
     *   provider with a different domain in the same app launch makes every auth call throw
     *   `RainError.InvalidConfig` until relaunch.
     * @throws RainError.InvalidConfig (`RAIN_102`) when [rainApiBaseUrl] is not an absolute https URL
     *   naming a host and nothing more (a path, query, fragment or userinfo is refused), when
     *   [walletAddress] is malformed or carries a wrong mixed-case checksum, or when
     *   [passkeyDomain] is not a domain of at least two labels made of letters, digits and hyphens (a
     *   scheme, port, path or a single label such as `localhost` is refused).
     */
    @InternalRainTurnkeyApi
    @Suppress("LongParameterList") // the BYO constructor's parameters plus the managed ids and the passkey domain; six have defaults
    constructor(
        application: Application,
        organizationId: String,
        rainApiBaseUrl: String,
        authTokenProvider: suspend (LoginContact?) -> String,
        walletAddress: String? = null,
        sessionPolicy: TurnkeySessionPolicy = TurnkeySessionPolicy(),
        onSessionExpired: (() -> Unit)? = null,
        sponsorGas: Boolean = true,
        sponsorRent: Boolean = false,
        passkeyDomain: String? = null,
    ) : this(
        // The vendor context is a process-wide object; managed mode configures it lazily, on the
        // first authentication call, through the provider's controller.
        TurnkeyContext,
        walletAddress.checksummedOrNull(),
        sessionPolicy,
        onSessionExpired,
        sponsorGas,
        sponsorRent,
        managed = ManagedIds(
            application,
            organizationId,
            rainApiBaseUrlOrThrow(rainApiBaseUrl),
            authTokenProvider,
            TurnkeyPasskeys.normalizedDomainOrNull(passkeyDomain),
        ),
    )

    private companion object {
        /** The override is validated at construction, so a typo fails here and not as a foreign address on every read. */
        /** Null or blank means no override; anything else must be a valid EVM address. */
        fun String?.checksummedOrNull(): String? =
            this?.takeIf { it.isNotBlank() }?.let { validateAndChecksumAddress(it, "walletAddress") }
    }
}

/**
 * Turnkey adapter — the registrable [ProviderDescriptor] for Turnkey's P256-stamper signer.
 *
 * Ships as `rain-turnkey-android` and owns the Turnkey SDK as its own dependency, so an app that
 * registers another provider never links Turnkey. It implements the port and owns all
 * Turnkey-specific wiring.
 *
 * In managed mode the provider is also the authentication surface: construct it, run
 * [sendLoginCode] / [confirmLoginCode], [loginWithPasskey] or [signUpWithPasskey] on it, then build
 * the SDK and resolve. Resolving before a
 * session is live fails with `RainError.TokenExpired`. In both modes it also exports the wallet's
 * recovery phrase and private keys, see [exportRecoveryPhrase] and [exportPrivateKey].
 */
@Suppress("TooManyFunctions") // the descriptor is also the managed authentication surface, by design
class TurnkeyProvider internal constructor(
    private val config: TurnkeyConfig,
    private val contextOverride: TurnkeyContextProtocol?,
    /** Test seam for the Rain API client. */
    private val rainApiOverride: RainApiAuthClientProtocol? = null,
    private val pendingPasskeySignupsOverride: PendingPasskeySignupStore? = null,
) : ProviderDescriptor {

    constructor(config: TurnkeyConfig) : this(config, contextOverride = null)

    override val id: ProviderId get() = ProviderId.TURNKEY

    /**
     * Turnkey holds EVM + Solana accounts, signs with a device key (no biometric prompt gates signing), and exports
     * its keys through [exportRecoveryPhrase] and [exportPrivateKey], so [Capability.EXPORT] is
     * always advertised. With [TurnkeyConfig.sponsorGas] on it also advertises
     * [Capability.GAS_SPONSORSHIP]. `RainSdk` copies this set onto the resolved client, so it comes
     * from the same function as the wallet provider's own set and the two cannot drift.
     */
    override val capabilities: Set<Capability> =
        TurnkeyWalletProvider.capabilitiesFor(config.sponsorGas)

    private val turnkeyContext: TurnkeyContextProtocol by lazy {
        contextOverride ?: TurnkeyContextAdapter(config.turnkey)
    }

    private val coordinator: TurnkeySessionCoordinator by lazy {
        TurnkeySessionCoordinator(
            turnkey = turnkeyContext,
            policy = config.sessionPolicy,
            onSessionExpired = config.onSessionExpired,
        )
    }

    /** Present in managed mode only; owns the one-time-code, passkey and contact-attach flows. */
    private val managedAuth: TurnkeyManagedAuthController? by lazy {
        config.managed?.let { ids ->
            TurnkeyManagedAuthController(
                context = turnkeyContext,
                rainApi = rainApiOverride ?: RainApiAuthClient(ids.rainApiBaseUrl),
                authTokenProvider = ids.authTokenProvider,
                coordinator = coordinator,
                configure = {
                    TurnkeyManagedConfigurator.configure(
                        ids.application,
                        ids.organizationId,
                        ids.rainApiBaseUrlText,
                        ids.passkeyDomain,
                    )
                },
                passkeyDomain = ids.passkeyDomain,
                pendingPasskeySignups = pendingPasskeySignupsOverride ?: SharedPreferencesPendingPasskeySignupStore(ids.application),
            )
        }
    }

    /** Both modes, from construction: the sample exports right after login, before resolution. */
    private val keyExporter: TurnkeyKeyExporter by lazy {
        TurnkeyKeyExporter(
            turnkey = turnkeyContext,
            sessions = coordinator,
            walletAddressOverride = config.walletAddress,
            // Managed mode configures the vendor and waits for its restore first; a no-op otherwise.
            ensureConfigured = { managedAuth?.ensureConfigured() },
        )
    }

    // The coordinator's stop() gates nothing, so the export path keeps its own closed flag.
    private val closed = AtomicBoolean(false)

    // Lives for the provider's lifetime; the Turnkey singleton it watches is process-wide anyway.
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The Turnkey session as seen at the Rain boundary, over time. Emits on every Turnkey
     * auth/session change and when an active session passes its expiry, so a host can react to
     * a session dying silently without waiting for a wallet call to fail.
     */
    val sessionState: Flow<TurnkeySessionState> get() = coordinator.sessionStates

    /** Snapshot of [sessionState] right now. */
    fun currentSessionState(): TurnkeySessionState = coordinator.currentState()

    /**
     * Forces a Turnkey session refresh (new JWT, extended expiry) regardless of remaining
     * lifetime. Throws `RainError.TokenExpired` when the session cannot be refreshed — the
     * host must re-authenticate. When Turnkey refused the refresh (HTTP 401: a login on another
     * device revoked the session), the stored session is cleared before the throw and
     * `onSessionExpired` fires once, so [hasActiveSession], [currentAuthState] and
     * [currentSessionState] read signed out when this returns. A refresh that failed for another
     * reason (offline, a 5xx) throws the same error but leaves the session stored. The same happens
     * inside any wallet call that refreshes with [TurnkeySessionPolicy.autoRefresh] on.
     */
    suspend fun refreshSession() {
        requireOpen()
        coordinator.refreshNow()
    }

    /**
     * Stops the passive session watcher. Call when discarding this provider (e.g. rebuilding
     * the SDK for a new login) so a stale provider stops observing the process-wide Turnkey
     * singleton and can never fire its expiry hook again. In managed mode the provider is inert
     * afterwards: every auth call throws `RainError.InvalidConfig`, [currentAuthState] reads
     * `Unauthenticated` and [hasActiveSession] is false. In both modes [exportRecoveryPhrase] and
     * [exportPrivateKey] refuse afterwards with the same error; the check runs on entry, so an
     * export already in flight completes, and cancelling its coroutine is how to abort it.
     */
    override fun close() {
        closed.set(true)
        coordinator.stop()
        monitorScope.cancel()
        managedAuth?.close()
    }

    override suspend fun create(context: ProviderContext): WalletProvider {
        // Always watch: beyond the optional host hook, the watcher advances the coordinator's
        // death count on session death, which stales the manager's cached accounts, so a re-login
        // can never sign with the previous user's cached address.
        coordinator.startMonitoring(monitorScope)

        // Managed mode: a session whose provisioning failed after login heals here, before the
        // probe below could reject it for a missing account.
        managedAuth?.ensureAccounts()

        // Built per create(), never lazily on the descriptor: reset() re-runs create(), and the
        // manager's address cache belongs to the session that filled it.
        val manager = TurnkeyManager(
            turnkey = turnkeyContext,
            rpcEndpoints = context.rpcEndpoints,
            solanaRpcClient = context.solanaSupport.rpc,
            sponsorGas = config.sponsorGas,
            walletAddressOverride = config.walletAddress,
            sessionCoordinator = coordinator,
        )
        val provider = TurnkeyWalletProvider(
            manager = manager,
            chainReader = context.evmChainReader,
            solanaChainReader = context.solanaSupport.chainReader,
            solanaTransferComposer = context.solanaSupport.composer,
            tokenStore = context.tokenStore,
            sponsorRent = config.sponsorRent,
        )

        // Probe — ensures Turnkey has an EVM wallet available before the provider is handed out.
        withContext(Dispatchers.IO) {
            provider.getWalletAddress()
        }

        return provider
    }

    // ---------- Key export (both modes) ----------

    /**
     * The wallet's BIP-39 recovery phrase, as the wallet was created. Managed wallets have 12
     * words. A bring-your-own wallet has the length it was created with. The phrase is decrypted
     * on this device by the vendor and returned once. The SDK never logs, caches or persists it.
     * Requires a live session and works in bring-your-own and managed mode alike.
     *
     * The phrase restores every account derived from that wallet's seed and nothing else. Managed
     * wallets keep their Ethereum and Solana accounts on one seed, so one phrase covers both. A
     * bring-your-own organization with several wallets gets the phrase of the wallet holding the
     * Ethereum account the SDK signs with, or of the first wallet when there is none, so the phrase
     * and [exportPrivateKey] for [TurnkeyKeyFamily.ETHEREUM] always derive the same address.
     *
     * Everything after the return is the host's duty; *Host duties* under *Key export* in
     * `docs/TURNKEY_SUPPORT.md` lists them. Fails with `RainError.InvalidConfig`,
     * `RainError.TokenExpired`, `RainError.WalletUnavailable`, `RainError.Unauthorized` or
     * `RainError.ProviderError`; the *Key export errors* table there says which condition maps to
     * which. A transient failure is retried with a new request.
     *
     * @see exportPrivateKey
     */
    suspend fun exportRecoveryPhrase(): String {
        requireOpen()
        return keyExporter.exportRecoveryPhrase()
    }

    /**
     * The private key of the account the SDK signs with for [family]. For
     * [TurnkeyKeyFamily.ETHEREUM] it is the 32-byte secp256k1 key as `0x` plus 64 lowercase hex
     * characters. For [TurnkeyKeyFamily.SOLANA] it is the 64-byte keypair, the seed followed by the
     * public key, in plain Base58, the string Solana wallets import. The key is decrypted on this
     * device and returned once. The SDK never logs, caches or persists it, and before returning
     * anything it checks that the key is 32 bytes and that the address it derives, on either
     * curve, is the account's. Requires a live session and works in bring-your-own and managed
     * mode alike.
     *
     * On a managed wallet both keys derive from the seed behind [exportRecoveryPhrase]. On a
     * bring-your-own organization with several wallets, each key comes from the account the SDK
     * signs with for that family, which may sit on another wallet than the phrase.
     *
     * Everything after the return is the host's duty; *Host duties* under *Key export* in
     * `docs/TURNKEY_SUPPORT.md` lists them. Fails with `RainError.InvalidConfig`,
     * `RainError.TokenExpired`, `RainError.WalletUnavailable`, `RainError.Unauthorized`,
     * `RainError.ProviderError` or `RainError.InternalError`; the *Key export errors* table there
     * says which condition maps to which. A transient failure is retried with a new request.
     *
     * @see exportRecoveryPhrase
     */
    suspend fun exportPrivateKey(family: TurnkeyKeyFamily): String {
        requireOpen()
        return keyExporter.exportPrivateKey(family)
    }

    private fun requireOpen() {
        if (closed.get()) throw RainError.InvalidConfig(TURNKEY_PROVIDER_CLOSED_MESSAGE)
    }

    // ---------- Managed authentication (one-time code or passkey) — internal API, see InternalRainTurnkeyApi ----------

    /**
     * Where managed authentication stands, over time: [TurnkeyAuthState.Loading] until the first
     * auth call has configured Turnkey and any previous session has been restored,
     * [TurnkeyAuthState.Authenticated] once a session is live,
     * [TurnkeyAuthState.Unauthenticated] otherwise. Always `Unauthenticated` in bring-your-own
     * mode, where the host owns authentication.
     */
    @InternalRainTurnkeyApi
    val authState: Flow<TurnkeyAuthState>
        get() = managedAuth?.authState ?: flowOf(TurnkeyAuthState.Unauthenticated)

    /** Snapshot of [authState] right now. */
    @InternalRainTurnkeyApi
    fun currentAuthState(): TurnkeyAuthState =
        managedAuth?.currentAuthState() ?: TurnkeyAuthState.Unauthenticated

    /**
     * True when an unexpired session is already live — restored, or just established — with more
     * than 30 seconds left, so the one-time-code step can be skipped. The answer comes from the
     * stored session, and a session Turnkey refuses to refresh is cleared with that failure (see
     * [refreshSession]), so this reads false from then on; until a refresh asks, a session revoked
     * from another device still reads as active. False until the first auth call has configured
     * Turnkey, and always false in bring-your-own mode.
     */
    @InternalRainTurnkeyApi
    fun hasActiveSession(): Boolean = managedAuth?.hasActiveSession() ?: false

    /**
     * Waits for the asynchronous session restore that follows configuration, so a returning user's
     * session can be reused without a new code. A timeout returns normally and leaves [authState]
     * at [TurnkeyAuthState.Loading]. As the first auth call of a launch it also runs Turnkey's
     * one-shot initialization first, which [timeoutMs] does not bound. A no-op in bring-your-own
     * mode. Throws `RainError.InvalidConfig` when the ids are blank, the ids or the passkey domain
     * conflict with the ones the process was configured with, or Turnkey was configured outside the SDK, and
     * `RainError.InternalError` when Turnkey's initialization failed.
     */
    @InternalRainTurnkeyApi
    suspend fun awaitSessionRestore(
        timeoutMs: Long = TurnkeyManagedAuthController.DEFAULT_RESTORE_TIMEOUT_MS,
    ) {
        managedAuth?.awaitSessionRestore(timeoutMs)
    }

    /**
     * Sends a one-time login code to [contact], by email or by SMS, under a wallet auth token from
     * the configuration's `authTokenProvider`; touches no existing session. Applies the one-shot
     * Turnkey configuration when it is the first auth call. Calling it again for the same contact
     * issues a new code and replaces the pending one, which is how a code is resent; calling it for
     * another contact or channel retires the pending code first, so a failed switch leaves nothing
     * confirmable. The contact is canonicalized once (see [LoginContact]) and the same string names
     * the account on sign-up. Throws `RainError.InvalidConfig`, `RainError.Unauthorized`,
     * `RainError.NetworkError`, `RainError.ProviderError` or `RainError.InternalError`; the
     * `sendLoginCode` row under *Members* in `docs/TURNKEY_SUPPORT.md` says which condition maps to
     * which. Managed mode only.
     */
    @InternalRainTurnkeyApi
    suspend fun sendLoginCode(contact: LoginContact) = requireManagedAuth().sendLoginCode(contact)

    /**
     * Confirms the code from [sendLoginCode]: Rain verifies the code and logs the contact's wallet
     * in, or signs the contact up when this tenant has none, creating one wallet holding an Ethereum
     * and a Solana account inside the sign-up; a returning login backfills a missing account onto
     * the existing wallet. A rejected code keeps the challenge, so the user can retype it, and so
     * does any other failure of the code check unless the answer was unreadable, since Rain accepted
     * the code by then; a failure after the code was accepted drops the challenge. A failed switch
     * to the new session leaves the previous session selected; a provisioning failure keeps the new
     * session, which heals at resolution. A code login leaves the user's sessions on other devices
     * alone. The channel the code went out on makes no difference here. Throws
     * `RainError.InvalidLoginCode`, `RainError.InvalidConfig`, `RainError.Unauthorized`,
     * `RainError.NetworkError`, `RainError.ProviderError`, `RainError.InternalError` or, from the
     * session switch, `RainError.TokenExpired`; the `confirmLoginCode` row under *Members* in
     * `docs/TURNKEY_SUPPORT.md` says which condition maps to which. Managed mode only.
     */
    @InternalRainTurnkeyApi
    suspend fun confirmLoginCode(code: String) = requireManagedAuth().confirmLoginCode(code)

    /**
     * Signs an existing user in with a passkey bound to [TurnkeyConfig]'s `passkeyDomain`, through
     * the system passkey sheet. The account is the one the passkey was created for; a first-time
     * user has none and uses [signUpWithPasskey] or a login code. A successful login stores the
     * session under a fresh key, selects it, clears the previous session, revokes the user's other
     * Turnkey sessions on every device (`invalidateExisting`) and backfills a missing Ethereum or
     * Solana account onto the existing wallet. A dismissed sheet, a refused passkey or a failed
     * ceremony leaves the current session untouched. Throws `RainError.InvalidConfig` before any
     * vendor call when no `passkeyDomain` is configured, or when the association file does not vouch
     * for this build, `RainError.UserRejected` when the sheet
     * was dismissed or the device holds no passkey for the domain, `RainError.ProviderError`
     * for a ceremony the device or the backend refused for another reason or answered without a
     * session, and `RainError.InternalError` when the session store found the session key
     * occupied. The session switch and the account backfill run
     * after the ceremony, so a login that succeeded can still end in `RainError.TokenExpired` or
     * `RainError.Unauthorized`; a backfill failure keeps the session, which heals at resolution.
     * Managed mode only.
     *
     * @param activity The foreground Activity the system passkey sheet is presented from; the
     *   vendor requires an Activity so the sheet lands in the app's task. The call suspends until
     *   the sheet closes, which can take as long as the user takes. Cancelling the calling
     *   coroutine dismisses the sheet on Android 14 and later and, on earlier versions, abandons
     *   its result when it arrives; either way the call ends with the cancellation, never with a
     *   mapped error. Run it in a scope that survives configuration changes, such as a ViewModel
     *   scope, and pass the Activity at the call: the SDK uses it only to launch the sheet and
     *   retains it no longer than the call.
     */
    @InternalRainTurnkeyApi
    suspend fun loginWithPasskey(activity: Activity) {
        requireManagedAuth().loginWithPasskey(activity)
    }

    /**
     * Creates a new account with a passkey as its only login method and an Ethereum and a Solana
     * account in one wallet, and signs it in with one prompt: the sheet creates the passkey, Rain
     * registers a device key on the new account for a minute, and the SDK logs in with a login
     * stamped by that key. When the account holds no key for this device, the sheet opens again and
     * the passkey signs it in. Each new passkey is a new account, so a returning user signs in with
     * [loginWithPasskey] or a login code; call [logout] first unless [currentAuthState] reads
     * `Unauthenticated` ([hasActiveSession] reads false in a session's last 30 seconds, which still
     * refuses). A failure can leave an account behind; the `signUpWithPasskey` row in
     * `docs/TURNKEY_SUPPORT.md` says when, and which code tells you.
     * Throws `RainError.InvalidConfig`, `RainError.UserRejected`, `RainError.Unauthorized`,
     * `RainError.NetworkError`, `RainError.ProviderError`, `RainError.InternalError` or, after the
     * login, `RainError.TokenExpired`. Managed mode only.
     *
     * @param activity As for [loginWithPasskey].
     */
    @InternalRainTurnkeyApi
    suspend fun signUpWithPasskey(activity: Activity) {
        requireManagedAuth().signUpWithPasskey(activity)
    }

    /**
     * Registers a passkey bound to [TurnkeyConfig]'s `passkeyDomain` on the signed-in account, so
     * the next sign-in can use it. Requires a live session, checked before the sheet:
     * `RainError.TokenExpired` fires before any biometric prompt. A session revoked server-side
     * between that check and the registration still surfaces as `RainError.TokenExpired` after the
     * prompt, with [TurnkeyConfig.onSessionExpired] firing; a registration the backend refused
     * leaves a passkey on the device that signs into nothing. The passkey request asks the
     * credential provider for user verification as preferred, not required, so gate this call as
     * the host gates export. No new account and no session change; other authentication calls wait
     * while the sheet is open. One passkey per device is enough, and each call registers another
     * with the credential provider. Throws
     * `RainError.InvalidConfig` when no `passkeyDomain` is configured or the association file does
     * not vouch for this build, `RainError.UserRejected` when the sheet was dismissed,
     * `RainError.Unauthorized` when the backend refuses the registration, and `RainError.ProviderError`
     * for a ceremony the device refused or a registration the backend failed, its per-user limit
     * included. Managed mode only.
     *
     * @param activity As for [loginWithPasskey].
     */
    @InternalRainTurnkeyApi
    suspend fun addPasskey(activity: Activity) {
        requireManagedAuth().addPasskey(activity)
    }

    /**
     * Sends a verification code to a contact the signed-in user wants to attach to this account,
     * so that contact becomes a login method for it; distinct from [sendLoginCode], which starts a
     * login. Requires a live session: `RainError.TokenExpired` before anything is sent. Asks the
     * configuration's `authTokenProvider` for a token bound to the canonical contact, then has Rain
     * deliver the code. The contact is canonicalized like a login contact (see [LoginContact]) and
     * that string is what the account stores; calling it again for the same contact replaces the
     * pending code, a call for another contact or channel retires it before anything is sent, and a
     * login or a [logout] drops it. Accounts are never merged; a contact another account signs in
     * with is refused on confirm, see [confirmContactVerification]. Throws
     * `RainError.InvalidConfig` for a blank or malformed contact or a failed token provider,
     * `RainError.Unauthorized` when Rain refuses the token or the tenant, `RainError.NetworkError`
     * when Rain cannot be reached, `RainError.ProviderError` when the code request is refused, and
     * `RainError.InternalError` when Rain's answer could not be read. Managed mode only.
     */
    @InternalRainTurnkeyApi
    suspend fun sendContactVerificationCode(contact: LoginContact) {
        requireManagedAuth().sendContactVerificationCode(contact)
    }

    /**
     * Confirms the code from [sendContactVerificationCode] and attaches the verified contact. The
     * session is checked before the code is spent. Once the code is accepted, the account's email or
     * phone is set to the contact under the live session; a contact another wallet in the tenant
     * signs in with is refused with `RainError.Unauthorized` first, the code spent. A rejected code keeps the
     * challenge, so the user can retype it; a failure after the code was accepted drops it, so the
     * user requests a new code. Fails with `RainError.InvalidConfig`, `RainError.TokenExpired`,
     * `RainError.InvalidLoginCode`, `RainError.Unauthorized`, `RainError.NetworkError`,
     * `RainError.ProviderError` or `RainError.InternalError`; the `confirmContactVerification` row
     * under *Members* in `docs/TURNKEY_SUPPORT.md` says which condition maps to which. Managed mode
     * only.
     */
    @InternalRainTurnkeyApi
    suspend fun confirmContactVerification(code: String) {
        requireManagedAuth().confirmContactVerification(code)
    }

    /**
     * Clears the selected session (full logout) — after waiting for a restore in flight to
     * settle — without firing [TurnkeyConfig.onSessionExpired]; cached accounts still go stale
     * and a pending login code and a pending contact verification are dropped. [hasActiveSession] and [currentAuthState] read
     * unauthenticated as soon as it returns. A no-op when no session is selected. Managed mode only —
     * throws `RainError.InvalidConfig` in bring-your-own mode, where the host owns the session.
     */
    @InternalRainTurnkeyApi
    suspend fun logout() = requireManagedAuth().logout()

    private fun requireManagedAuth(): TurnkeyManagedAuthController =
        managedAuth ?: throw RainError.InvalidConfig(
            "Authentication methods are only available in managed mode — construct the provider " +
                "with TurnkeyConfig(application, organizationId, rainApiBaseUrl, authTokenProvider); in " +
                "bring-your-own mode the host owns authentication"
        )
}
