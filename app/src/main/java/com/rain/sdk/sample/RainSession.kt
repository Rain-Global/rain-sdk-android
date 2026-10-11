package com.rain.sdk.sample

import android.app.Application
import com.rain.sdk.RainSdk
import com.rain.sdk.error.RainError
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.portal.PortalConfig
import com.rain.sdk.portal.PortalProvider
import com.rain.sdk.privy.PrivyConfig
import com.rain.sdk.privy.PrivyProvider
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.turnkey.TurnkeyConfig
import com.rain.sdk.turnkey.TurnkeyProvider
import com.rain.sdk.wallet.RainProvider
import com.rain.sdk.wallet.RainWalletAuthState
import com.rain.sdk.wallet.RainWalletConfig
import com.rain.sdk.wallet.RainWalletSessionPolicy
import com.turnkey.core.TurnkeyContext
import io.portalhq.android.Portal
import io.portalhq.android.storage.mobile.PortalNamespace
import io.privy.sdk.Privy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient

/**
 * App-side holder around the modular [RainSdk].
 *
 * The sample picks a provider at runtime (Portal, Rain wallet, Turnkey or Privy), so it builds the [RainSdk] lazily
 * once the user supplies credentials and then keeps the resolved [RainClient] here. Screens read
 * the real [client] directly — there is no fake `RainClient` wrapper. [client] is `null` until one
 * of the `initialize*` helpers has run.
 */
@Suppress("TooManyFunctions") // sample glue: one entry point per provider flow step
class RainSession {

    var rain: RainSdk? = null
        private set

    /** The provider-backed client, or `null` before initialization. */
    var client: RainClient? = null
        private set

    val isInitialized: Boolean get() = client?.isInitialized == true

    /** Typed because the session surface lives on the provider, not on [RainClient]. */
    private sealed interface ActiveProvider {
        data class Portal(val provider: PortalProvider) : ActiveProvider
        data class RainWallet(val provider: RainProvider) : ActiveProvider
        data class Turnkey(val provider: TurnkeyProvider) : ActiveProvider
        data class Privy(val provider: PrivyProvider) : ActiveProvider
    }

    private val activeProvider = MutableStateFlow<ActiveProvider?>(null)
    private val rainWalletProviders = MutableStateFlow<RainProvider?>(null)

    /**
     * Which provider's session died, emitted from the SDK's `onSessionExpired` hooks. The hooks live
     * as long as the providers, which is longer than any ViewModel, so they emit here and the
     * current ViewModel collects; a hook that captured a ViewModel's state would update a dead one
     * after an Activity recreation. Events, not state: two deaths are two emissions, a death with
     * no collector waits in the replay cache for the next ViewModel, and the consumer resets the
     * cache once it has reacted. [prepareRainWallet] and [reset] reset it too, so a retired
     * provider's death is never replayed over its successor.
     */
    val expiredProvider = MutableSharedFlow<SessionStore.Provider>(
        replay = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The Rain wallet's authentication state over time; `null` before a Rain wallet provider is
     * prepared. Collected by the Home screen, so the flow runs on a device.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val rainWalletAuth: Flow<RainWalletAuthState?> = rainWalletProviders.flatMapLatest { provider ->
        provider?.authState ?: flowOf(null)
    }

    /** The active provider's `sessionState` as a display model; `null` before resolution. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionStatus: Flow<WalletSessionStatus?> = activeProvider.flatMapLatest { active ->
        when (active) {
            null -> flowOf(null)
            is ActiveProvider.Portal -> active.provider.sessionState.map { it.toStatus() }
            is ActiveProvider.RainWallet -> active.provider.sessionState.map { it.toStatus() }
            is ActiveProvider.Turnkey -> active.provider.sessionState.map { it.toStatus() }
            is ActiveProvider.Privy -> active.provider.sessionState.map { it.toStatus() }
        }
    }

    /** Forces a refresh on the active provider; `RainError.TokenExpired` means re-auth. */
    suspend fun refreshSession() {
        when (val active = activeProvider.value) {
            null -> throw RainError.SdkNotInitialized()
            is ActiveProvider.RainWallet -> active.provider.refreshSession()
            is ActiveProvider.Turnkey -> active.provider.refreshSession()
            is ActiveProvider.Privy -> active.provider.refreshSession()
            is ActiveProvider.Portal -> active.provider.refreshSession()
        }
    }

    /** Portal only: installs a host-minted token for the same Portal client. */
    suspend fun updatePortalSessionToken(sessionToken: String) {
        val active = activeProvider.value as? ActiveProvider.Portal
            ?: throw RainError.SdkNotInitialized()
        active.provider.updateSessionToken(sessionToken)
    }

    /** A discarded SDK must never fire its providers' hooks. */
    private fun closeActiveProvider() {
        // The registered Rain wallet provider closes with the SDK; drop the sample's reference so no
        // later call (a resend, an export) reaches a closed provider.
        if ((activeProvider.value as? ActiveProvider.RainWallet)?.provider === rainWalletProvider) {
            rainWalletProvider = null
        }
        runCatching { rain?.close() }
        activeProvider.value = null
        // A closed SDK must not read as initialized on the next Activity recreation.
        client = null
        rain = null
    }

    // The Rain issuing API is the host's call, not the SDK's. This demo makes it from the device
    // with the program key typed on the Home card, which a shipped app must never do; the client it
    // builds is the reference for what a host runs on its backend. See RainApiClient.kt.
    private val rainApiHttpClient: OkHttpClient by lazy { RainApiClient.defaultHttpClient() }

    /** The demo's Rain API client, or null until an Api-Key and userId have been supplied. */
    var rainApi: RainApiClient? = null
        private set

    val isRainApiConfigured: Boolean get() = rainApi != null

    /** Builds (or replaces) the client from the typed pair; blank input clears it. */
    fun configureRainApi(apiKey: String, userId: String) {
        val key = apiKey.trim()
        val user = userId.trim()
        rainApi = if (key.isEmpty() || user.isEmpty()) {
            null
        } else {
            RainApiClient(
                baseUrl = SampleEnvironment.rainApi.baseUrl,
                apiKey = key,
                userId = user,
                httpClient = rainApiHttpClient,
            )
        }
    }

    /** Drops the client. Clear session calls this; [reset] does not, so a failed init keeps the typed pair usable. */
    fun clearRainApi() {
        rainApi = null
    }

    fun requireRainApi(): RainApiClient = rainApi ?: error(RAIN_API_CREDENTIALS_REQUIRED)

    /**
     * The user's collateral contract for [chain], picked by [match] (see [CollateralContractMatch]),
     * with each token's name, symbol and decimals resolved by the SDK from its address. A token the
     * SDK cannot resolve, or a contract on a chain this build has no RPC endpoint for, keeps null
     * metadata; the withdraw screen then disables its money actions.
     */
    suspend fun fetchCollateralContract(chain: WalletChain, match: CollateralContractMatch): CollateralContract? {
        val contract = chain.collateralContract(requireRainApi().fetchCollateralContracts(), match)
        val sdk = rain
        if (contract == null || sdk == null) return contract
        val tokens = tokensWithMetadata(
            contract = contract,
            lookup = sdk::tokenMetadata,
            onUnavailable = { token, error ->
                // A chain this build has no RPC endpoint for, or an address the SDK rejects: the token
                // stays unnamed rather than the screen guessing its scale.
                SampleLog.w(
                    "RainApi",
                    "token metadata unavailable for ${token.address} on chainId=${contract.chainId}: ${error.code}"
                )
            },
        )
        registerCollateralTokens(sdk, contract.chainId, tokens)
        return contract.copy(tokens = tokens)
    }

    /**
     * Registers the contract's resolved tokens with the SDK, so the wallet's `getTokenBalances` lists
     * them too: the built-in registry and the wallet backend's balance service know Rain's sandbox
     * tokens only in part. A refused registration is logged, never fatal, because the collateral
     * screens read the Rain API, not the token store.
     */
    private suspend fun registerCollateralTokens(sdk: RainSdk, chainId: Int, tokens: List<CollateralToken>) {
        val infos = registrableTokenInfos(chainId, tokens)
        if (infos.isEmpty()) return
        try {
            sdk.registerTokens(infos)
        } catch (e: RainError) {
            SampleLog.w("RainApi", "could not register ${infos.size} collateral token(s) on chainId=$chainId: ${e.code}")
        }
    }

    // Shared builder config: naming for every chain's testnet token plus the Auth Pull targets,
    // applied identically whichever provider is registered (see WalletChain.defaultTokenInfo).
    private fun RainSdk.Builder.withSharedConfig(): RainSdk.Builder = apply {
        registerTokens(WalletChain.selectable.map { it.defaultTokenInfo })
        authPullConfig(SampleEnvironment.authPullConfig)
    }

    // Captured during provider resolution so the sample can reach Portal-only APIs the Rain
    // surface doesn't expose (wallet generation, backup/recover).
    private var portal: Portal? = null

    /** Builds the SDK with the Portal provider and resolves the Portal-backed client. */
    suspend fun initializePortal(
        sessionToken: String,
        rpcEndpoints: Map<Int, String>,
        chainId: Int? = null,
        onSessionTokenNeeded: (suspend () -> String?)? = null,
        onSessionExpired: (() -> Unit)? = null,
    ) {
        closeActiveProvider()
        val provider = PortalProvider(
            config = PortalConfig(
                sessionToken = sessionToken,
                chainId = chainId,
                onSessionTokenNeeded = onSessionTokenNeeded,
                onSessionExpired = onSessionExpired,
            ),
            // Re-fired after every token refresh.
            onPortalCreated = { portal = it },
        )
        val sdk = RainSdk.builder()
            .rpcEndpoints(rpcEndpoints)
            .register(provider)
            .withSharedConfig()
            .build()
        rain = sdk
        client = sdk.provider(ProviderId.PORTAL)
        activeProvider.value = ActiveProvider.Portal(provider)
    }

    /**
     * Ensures this device can sign for the Portal client, running MPC key generation on first
     * use. Returns true if a wallet was created, false if one was already usable here.
     */
    suspend fun ensurePortalWallet(): Boolean {
        val portal = portal ?: error("Portal not initialized")

        // Two independent facts: the signing share lives in this device's keychain, the wallet
        // itself lives on the Portal client. Creating on a client that already has one fails.
        // An empty keychain throws rather than returning false, and that is the ordinary
        // first-run case — so a failed read here means "not on device", not an error.
        val onDevice = runCatching { portal.isWalletOnDeviceOrThrow(PortalNamespace.EIP155) }
            .getOrDefault(false)
        val onClient = portal.doesWalletExistOrThrow(PortalNamespace.EIP155)
        SampleLog.d("Portal.wallet", "ensurePortalWallet onDevice=$onDevice onClient=$onClient")

        if (onDevice) return false
        if (onClient) {
            error(
                "This Portal client already has a wallet, but its signing share is not on this " +
                    "device. Recover it from a backup, or use a client ID dedicated to this device."
            )
        }

        val created = portal.createWallet { status ->
            SampleLog.d("Portal.wallet", "keygen status=$status")
        }.getOrThrow()
        SampleLog.i("Portal.wallet", "created wallet eth=${created.ethereumAddress}")
        return true
    }

    /**
     * The Rain wallet provider, created by [prepareRainWallet]. Authentication (`sendLoginCode` /
     * `confirmLoginCode`) runs on it before Rain is initialized.
     */
    var rainWalletProvider: RainProvider? = null
        private set(value) {
            field = value
            rainWalletProviders.value = value
        }

    /**
     * Creates the Rain wallet provider. The SDK owns the wallet backend's configuration, the
     * one-time-code flow, email or SMS, and the passkey flows from here on. The previous provider is retired first,
     * whether or not it was built into an SDK: two providers must never share the process-wide
     * wallet backend. Session expiry is reported through [expiredProvider].
     */
    fun prepareRainWallet(application: Application): RainProvider {
        val previous = rainWalletProvider
        if (previous != null) {
            if ((activeProvider.value as? ActiveProvider.RainWallet)?.provider === previous) {
                closeActiveProvider()
            } else {
                previous.close()
            }
            expiredProvider.resetReplayCache()
        }
        // The defaults, spelled out, so every knob of the config runs through the SDK on a device.
        val provider = RainProvider(
            application,
            RainWalletConfig(
                sessionPolicy = RainWalletSessionPolicy(),
                onSessionExpired = { expiredProvider.tryEmit(SessionStore.Provider.RainWallet) },
                sponsorGas = true,
                // The demo's relying-party domain. Its association file, which lists this sample's
                // package name and the committed demo keystore's fingerprint, lives in
                // SignifyHQ/passkeys-demo. The domain is permanent, because every passkey created
                // against it stops working if it changes.
                passkeyDomain = "passkeys.uptop.xyz",
            ),
        )
        SampleLog.d("RainWallet.session", "prepared; session state now ${provider.currentSessionState()}")
        rainWalletProvider = provider
        return provider
    }

    /**
     * Builds the SDK with the prepared, authenticated Rain Wallet provider and resolves the
     * Rain Wallet-backed client. When that same provider is already registered (a second tap), the
     * existing SDK is kept — rebuilding would close the very provider about to be used. Each new
     * login goes through [prepareRainWallet] first, so the sample otherwise starts from a clean SDK;
     * the SDK itself also supports keeping one provider across logins (a fresh login evicts the
     * previous user's cached accounts).
     */
    suspend fun initializeRainWallet(rpcEndpoints: Map<Int, String>) {
        val provider = rainWalletProvider
            ?: throw RainError.InvalidConfig("Call prepareRainWallet before initializeRainWallet")
        val existing = rain
        if (existing != null && (activeProvider.value as? ActiveProvider.RainWallet)?.provider === provider) {
            client = existing.provider(ProviderId.RAIN)
            return
        }
        closeActiveProvider()
        val sdk = RainSdk.builder()
            .rpcEndpoints(rpcEndpoints)
            .register(provider)
            .withSharedConfig()
            .build()
        rain = sdk
        // Marked active before the suspending resolve, so a second tap after a cancelled resolve hits
        // the reuse guard above instead of closing the SDK that holds this very provider.
        activeProvider.value = ActiveProvider.RainWallet(provider)
        client = sdk.provider(ProviderId.RAIN)
    }

    /**
     * Managed Rain Wallet logout: clears the stored session so the next run needs a fresh code.
     * Returns false when the SDK refused, so the caller keeps its own state instead of pretending
     * the device is signed out while the session is still on it.
     */
    suspend fun logoutRainWallet(): Boolean {
        val provider = rainWalletProvider ?: return true
        return try {
            provider.logout()
            true
        } catch (e: RainError.InvalidConfig) {
            // A closed provider, or a backend another tab configured this launch: there is no Rain
            // wallet session of ours to clear, so clearing the rest must go ahead.
            SampleLog.w("RainWallet.session", "logout skipped: ${e.code} ${e.javaClass.simpleName}")
            true
        } catch (e: RainError) {
            SampleLog.w("RainWallet.session", "logout failed: ${e.code} ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Builds the SDK with a Turnkey context the host authenticated itself (bring-your-own mode) and
     * resolves the Turnkey-backed client. See [TurnkeyAuthSample] for the host-side login.
     */
    suspend fun initializeTurnkey(
        turnkey: TurnkeyContext,
        rpcEndpoints: Map<Int, String>,
    ) {
        closeActiveProvider()
        val provider = TurnkeyProvider(
            TurnkeyConfig(
                turnkey = turnkey,
                onSessionExpired = { expiredProvider.tryEmit(SessionStore.Provider.Turnkey) },
            )
        )
        val sdk = RainSdk.builder()
            .rpcEndpoints(rpcEndpoints)
            .register(provider)
            .withSharedConfig()
            .build()
        rain = sdk
        client = sdk.provider(ProviderId.TURNKEY)
        activeProvider.value = ActiveProvider.Turnkey(provider)
    }

    /** Builds the SDK with the Privy provider and resolves the Privy-backed client. */
    suspend fun initializePrivy(
        privy: Privy,
        rpcEndpoints: Map<Int, String>,
        walletAddress: String? = null,
        onSessionExpired: (() -> Unit)? = null,
    ) {
        closeActiveProvider()
        val provider = PrivyProvider(
            PrivyConfig(
                privy = privy,
                walletAddress = walletAddress,
                onSessionExpired = onSessionExpired,
            )
        )
        val sdk = RainSdk.builder()
            .rpcEndpoints(rpcEndpoints)
            .register(provider)
            .withSharedConfig()
            .build()
        rain = sdk
        client = sdk.provider(ProviderId.PRIVY)
        activeProvider.value = ActiveProvider.Privy(provider)
    }

    fun reset() {
        expiredProvider.resetReplayCache()
        // close() resets and tears the registered providers down, so their session watchers
        // stop and no stale hook survives into the next login.
        runCatching { rain?.close() }
        activeProvider.value = null
        client = null
        rain = null
        portal = null
        // Closing the SDK closed a registered provider; a prepared-but-unbuilt one is closed here.
        rainWalletProvider?.close()
        rainWalletProvider = null
    }

    companion object {
        /** Shown wherever a Rain API call needs the pair the Home card has not supplied yet. */
        const val RAIN_API_CREDENTIALS_REQUIRED = "Rain Api-Key and user ID required"
    }
}
