package com.rain.sdk.portal

import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderDescriptor
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.WalletProvider
import io.portalhq.android.Portal
import io.portalhq.android.mpc.data.FeatureFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * Configuration for the Portal provider.
 *
 * @param sessionToken A valid Portal session token (Portal API key).
 * @param chainId Optional default chain id for Portal's legacy single-chain operations. When null,
 *                Avalanche Mainnet is used if configured, otherwise the first configured chain.
 * @param sessionPolicy Refresh, auth-guard and transient-retry behavior for every wallet call.
 * @param onSessionTokenNeeded Called when Portal rejects the token: return a fresh token for the
 *                             SAME Portal client (the SDK rebuilds the client and retries once), or
 *                             null to decline. Runs under the refresh lock — never call back into
 *                             this provider from it.
 * @param onSessionExpired Fired once per session death when no fresh token could be installed.
 *                         Not on the main thread; restart authentication from here.
 * @param autoApprove Whether the adapter approves Portal's signing requests for the host; defaults
 *                    to `true`. Pass `false` only if the host gates signing itself, answering
 *                    `PortalEvents.PortalSigningRequested` on the `onPortalCreated` instance.
 *                    *Portal construction* in rain-portal-android/README.md has the rules, what
 *                    `true` auto-approves included. Last so existing positional callers keep
 *                    compiling.
 */
class PortalConfig(
    val sessionToken: String,
    val chainId: Int? = null,
    val sessionPolicy: PortalSessionPolicy = PortalSessionPolicy(),
    val onSessionTokenNeeded: (suspend () -> String?)? = null,
    val onSessionExpired: (() -> Unit)? = null,
    val autoApprove: Boolean = true,
)

/**
 * Portal adapter — the registrable [ProviderDescriptor] for Portal's MPC signer.
 *
 * Lives in the `rain-portal-android` module and owns the `portal-android` dependency. Core never
 * imports Portal; linking this module is what pulls Portal onto the classpath.
 *
 * @param onPortalCreated Optional hook invoked with the underlying [Portal] instance once it is
 *   constructed during resolution, for Portal-specific APIs like `backupWallet`/`recoverWallet`.
 *   Re-invoked after every session-token refresh, because the refresh rebuilds the client.
 */
class PortalProvider internal constructor(
    private val config: PortalConfig,
    private val onPortalCreated: ((Portal) -> Unit)?,
    private val portalManagerFactory: () -> PortalManager,
) : ProviderDescriptor {

    constructor(
        config: PortalConfig,
        onPortalCreated: ((Portal) -> Unit)? = null,
    ) : this(config, onPortalCreated, ::PortalManager)

    override val id: ProviderId get() = ProviderId.PORTAL

    /** Portal is an EVM MPC signer with key export/recovery via MPC backups. */
    override val capabilities: Set<Capability> =
        setOf(Capability.EXPORT, Capability.RECOVERY)

    @Volatile
    private var portalManager: PortalManager? = null

    private val coordinator: PortalSessionCoordinator by lazy {
        PortalSessionCoordinator(
            policy = config.sessionPolicy,
            onSessionTokenNeeded = config.onSessionTokenNeeded,
            onSessionExpired = config.onSessionExpired,
            installToken = { token ->
                val manager = portalManager ?: throw RainError.SdkNotInitialized()
                manager.reinitialize(token)
                onPortalCreated?.invoke(manager.getPortalInstance())
            },
        )
    }

    /** The session over time, driven by call outcomes and refreshes (Portal has no auth state). */
    val sessionState: StateFlow<PortalSessionState> get() = coordinator.sessionStates

    /** Snapshot of [sessionState] right now. */
    fun currentSessionState(): PortalSessionState = coordinator.currentState()

    /** Re-mints via `onSessionTokenNeeded` and rebuilds the client; `TokenExpired` on failure. */
    suspend fun refreshSession() {
        if (portalManager == null) throw RainError.SdkNotInitialized()
        coordinator.refreshNow()
    }

    /** Installs a host-minted token (same Portal client) and rebuilds the client around it. */
    suspend fun updateSessionToken(sessionToken: String) {
        if (portalManager == null) throw RainError.SdkNotInitialized()
        coordinator.installNow(sessionToken)
    }

    /**
     * Discards this provider: silences the session hooks and tears the Portal client down
     * (cancelling its auto-approve signing handler). Idempotent, and terminal — build a new
     * [PortalProvider] to use Portal again.
     */
    override fun close() {
        coordinator.stop()
        portalManager?.destroy()
        portalManager = null
    }

    @Suppress("ThrowsCount") // the empty-token guard plus the pass-through-or-map catch
    override suspend fun create(context: ProviderContext): WalletProvider {
        // Portal treats the session token as its API key; an empty one would fail every call
        // downstream with an opaque vendor error. Fail fast instead.
        if (config.sessionToken.isBlank()) {
            throw RainError.Unauthorized("Portal session token must not be empty")
        }

        val rpcEndpoints = context.rpcEndpoints

        // Portal addresses chains as CAIP-2 `eip155:<chainId>` identifiers.
        val eip155RpcConfig = rpcEndpoints.entries.associate { (id, url) ->
            "$EIP155_NAMESPACE:$id" to url
        }

        val legacyChainId = config.chainId
            ?: if (rpcEndpoints.containsKey(RainChain.AVALANCHE_MAINNET)) {
                RainChain.AVALANCHE_MAINNET
            } else {
                rpcEndpoints.keys.first()
            }

        val manager = portalManagerFactory()
        try {
            manager.initialize(
                apiKey = config.sessionToken,
                legacyEthChainId = legacyChainId,
                rpcConfig = eip155RpcConfig,
                featureFlags = FeatureFlags(isMultiBackupEnabled = true),
                autoApprove = config.autoApprove,
            )
            // Portal's constructor is offline; without this probe a bad token would only fail on
            // the first later call. Turnkey and Privy probe at creation too.
            manager.verifySession()
        } catch (e: Exception) {
            if (e is CancellationException || e is RainError) throw e
            throw PortalErrorMapping.mapAuthOrNull(e) ?: RainError.ProviderError(e)
        }
        portalManager = manager
        onPortalCreated?.invoke(manager.getPortalInstance())

        return PortalWalletProvider(manager, context.tokenStore, coordinator)
    }
}

private const val EIP155_NAMESPACE = "eip155"
