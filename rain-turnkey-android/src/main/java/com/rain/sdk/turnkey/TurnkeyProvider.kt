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
     * Managed mode: internal API reserved for the Rain wallet provider, see [InternalRainTurnkeyApi].
     * Surfaced to hosts as `RainProvider(application, RainWalletConfig)`. *Configuration* in
     * rain-wallet-android/README.md has the rules: the one-shot configuration per launch, the token
     * provider's contract and the passkey domain.
     *
     * @param application The host application; Turnkey's Kotlin SDK needs it for secure storage.
     * @param organizationId The Turnkey parent organization id.
     * @param rainApiBaseUrl The Rain API host the authentication runs against, such as
     *   `https://api-dev.rain.xyz`; validated at construction.
     * @param authTokenProvider Returns the wallet auth token the host's backend mints for the contact
     *   given, the canonical contact of a code request or attach, or null for a passkey sign-up.
     * @param walletAddress Optional EVM address override, validated and stored in EIP-55 checksum form; null or blank means no override.
     * @param passkeyDomain The passkey relying-party domain, normalized here; null or blank turns the
     *   passkey methods off.
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
 * [sendLoginCode] / [confirmLoginCode] (plus [sendSecondLoginCode] / [confirmSecondLoginCode] when
 * the account needs two codes), [loginWithPasskey] or [signUpWithPasskey] on it, then build
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
     * The Turnkey session as seen at the Rain boundary, over time; *Observing session state* in
     * `docs/TURNKEY_SUPPORT.md` says when it emits.
     */
    val sessionState: Flow<TurnkeySessionState> get() = coordinator.sessionStates

    /** Snapshot of [sessionState] right now. */
    fun currentSessionState(): TurnkeySessionState = coordinator.currentState()

    /**
     * Forces a Turnkey session refresh (new JWT, extended expiry) regardless of remaining lifetime.
     * Throws `RainError.TokenExpired` when Turnkey refuses the refresh, and the host must
     * re-authenticate; one that got no answer throws `RainError.NetworkError`, or
     * `RainError.ProviderError` for a 408, 429 or 5xx. *Session expiry, refresh, and retry* in
     * `docs/TURNKEY_SUPPORT.md` has the rules.
     */
    suspend fun refreshSession() {
        requireOpen()
        coordinator.refreshNow()
    }

    /**
     * Stops the passive session watcher, so a stale provider cannot fire its expiry hook after the
     * host discards it (a rebuild of the SDK for a new login). Afterwards [exportRecoveryPhrase] and
     * [exportPrivateKey] refuse with `RainError.InvalidConfig`; the check runs on entry, so an
     * export already in flight completes, and cancelling its coroutine is how to abort it. In
     * managed mode the provider is inert afterwards, surfaced to hosts as `RainProvider.close`,
     * whose row in rain-wallet-android/README.md has the rules.
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
     * *Which wallet and account* under *Key export* in `docs/TURNKEY_SUPPORT.md` says which wallet
     * the phrase comes from and what it restores.
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
     * The private key of the account the SDK signs with for [family], decrypted on this device and
     * returned once; the SDK never logs, caches or persists it. *Formats* under *Key export* in
     * `docs/TURNKEY_SUPPORT.md` states each family's string, and *Which wallet and account* says
     * which account it comes from, which on a bring-your-own organization may sit on another wallet
     * than the phrase. Session, host duties and retries as for [exportRecoveryPhrase]. Fails with
     * the classes named there plus `RainError.InternalError`, for exported material that fails a
     * check; the *Key export errors* table says which condition maps to which.
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
     * Where managed authentication stands, over time; always [TurnkeyAuthState.Unauthenticated] in
     * bring-your-own mode, where the host owns authentication. Surfaced to hosts as
     * `RainProvider.authState`. Its row in rain-wallet-android/README.md has the rules.
     */
    @InternalRainTurnkeyApi
    val authState: Flow<TurnkeyAuthState>
        get() = managedAuth?.authState ?: flowOf(TurnkeyAuthState.Unauthenticated)

    /** Snapshot of [authState] right now. */
    @InternalRainTurnkeyApi
    fun currentAuthState(): TurnkeyAuthState =
        managedAuth?.currentAuthState() ?: TurnkeyAuthState.Unauthenticated

    /**
     * True when an unexpired session with more than 30 seconds left is live; always false in
     * bring-your-own mode. Surfaced to hosts as `RainProvider.hasActiveSession`. Its row in
     * rain-wallet-android/README.md has the rules.
     */
    @InternalRainTurnkeyApi
    fun hasActiveSession(): Boolean = managedAuth?.hasActiveSession() ?: false

    /**
     * Waits for Turnkey's asynchronous restore of a persisted session, or for [timeoutMs]; a no-op
     * in bring-your-own mode. Surfaced to hosts as `RainProvider.awaitSessionRestore`. Its row in
     * rain-wallet-android/README.md has the rules.
     */
    @InternalRainTurnkeyApi
    suspend fun awaitSessionRestore(
        timeoutMs: Long = TurnkeyManagedAuthController.DEFAULT_RESTORE_TIMEOUT_MS,
    ) {
        managedAuth?.awaitSessionRestore(timeoutMs)
    }

    /**
     * Has Rain send a one-time code to [contact], email or SMS, under the configuration's
     * `authTokenProvider` token; the first auth call of a launch also applies the one-shot Turnkey
     * configuration. Surfaced to hosts as `RainProvider.sendLoginCode`. Its row in
     * rain-wallet-android/README.md has the rules. Managed mode only.
     */
    @InternalRainTurnkeyApi
    suspend fun sendLoginCode(contact: LoginContact) = requireManagedAuth().sendLoginCode(contact)

    /**
     * Confirms the code from [sendLoginCode] against Rain and switches to the session Rain issues;
     * returns [TurnkeyLoginOutcome.SignedIn], or [TurnkeyLoginOutcome.SecondCodeRequired] for an
     * account Rain protects with two codes, with nothing logged in yet. Surfaced to hosts as
     * `RainProvider.confirmLoginCode`. Its row in rain-wallet-android/README.md has the rules.
     * Managed mode only.
     */
    @InternalRainTurnkeyApi
    suspend fun confirmLoginCode(code: String): TurnkeyLoginOutcome = requireManagedAuth().confirmLoginCode(code)

    /**
     * Has Rain send the second code of a two-code login, or sends it again. Surfaced to hosts as
     * `RainProvider.sendSecondLoginCode`. Its row in rain-wallet-android/README.md has the rules.
     * Managed mode only.
     */
    @InternalRainTurnkeyApi
    suspend fun sendSecondLoginCode() = requireManagedAuth().sendSecondLoginCode()

    /**
     * Confirms the second code; completes the login like [confirmLoginCode]. Surfaced to hosts as
     * `RainProvider.confirmSecondLoginCode`, whose KDoc and row in rain-wallet-android/README.md
     * have the rules, which endings drop the attempt included. Managed mode only.
     */
    @InternalRainTurnkeyApi
    suspend fun confirmSecondLoginCode(code: String) = requireManagedAuth().confirmSecondLoginCode(code)

    /**
     * The managed passkey login, through the sheet presented from [activity]. A successful login
     * revokes the user's other Turnkey sessions on every device (`invalidateExisting`); a code login
     * revokes nothing. Surfaced to hosts as `RainProvider.loginWithPasskey`. Its row in
     * rain-wallet-android/README.md has the rules. Managed mode only.
     *
     * @param activity The Activity the vendor needs to present the sheet in the app's task;
     *   `RainProvider.loginWithPasskey` has the lifetime and cancellation rules.
     */
    @InternalRainTurnkeyApi
    suspend fun loginWithPasskey(activity: Activity) {
        requireManagedAuth().loginWithPasskey(activity)
    }

    /**
     * Creates a new account whose only login is the passkey and signs it in with one prompt. Rain
     * registers a device key on the new account for a minute, and the SDK logs in through Turnkey's
     * `stampLogin` stamped with that key; the sheet is the fallback when Turnkey refuses the stamp.
     * Surfaced to hosts as `RainProvider.signUpWithPasskey`. Its row in
     * rain-wallet-android/README.md has the rules. Managed mode only.
     *
     * @param activity As for [loginWithPasskey].
     */
    @InternalRainTurnkeyApi
    suspend fun signUpWithPasskey(activity: Activity) {
        requireManagedAuth().signUpWithPasskey(activity)
    }

    /**
     * Adds a passkey bound to [TurnkeyConfig]'s `passkeyDomain` to the signed-in account. Surfaced
     * to hosts as `RainProvider.addPasskey`. Its row in rain-wallet-android/README.md has the rules.
     * Managed mode only.
     *
     * @param activity As for [loginWithPasskey].
     */
    @InternalRainTurnkeyApi
    suspend fun addPasskey(activity: Activity) {
        requireManagedAuth().addPasskey(activity)
    }

    /**
     * Sends a verification code to [contact], the contact the signed-in user is attaching, under a
     * token from the configuration's `authTokenProvider` bound to the canonical contact (see
     * [LoginContact]). Surfaced to hosts as `RainProvider.sendContactVerificationCode`. Its row in
     * rain-wallet-android/README.md has the rules. Managed mode only.
     */
    @InternalRainTurnkeyApi
    suspend fun sendContactVerificationCode(contact: LoginContact) {
        requireManagedAuth().sendContactVerificationCode(contact)
    }

    /**
     * Confirms the code from [sendContactVerificationCode] and sets the account's email or phone to
     * the contact through Turnkey's update-user activity, under the live session. Surfaced to hosts
     * as `RainProvider.confirmContactVerification`. Its row in rain-wallet-android/README.md has
     * the rules. Managed mode only.
     */
    @InternalRainTurnkeyApi
    suspend fun confirmContactVerification(code: String) {
        requireManagedAuth().confirmContactVerification(code)
    }

    /**
     * Clears the selected session without firing [TurnkeyConfig.onSessionExpired]; cached accounts
     * still go stale. Managed mode only: throws `RainError.InvalidConfig` in bring-your-own mode,
     * where the host owns the session. Surfaced to hosts as `RainProvider.logout`. Its row in
     * rain-wallet-android/README.md has the rules.
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
