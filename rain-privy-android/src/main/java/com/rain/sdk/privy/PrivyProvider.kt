package com.rain.sdk.privy

import com.rain.sdk.error.RainError
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderDescriptor
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.WalletProvider
import io.privy.sdk.Privy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Configuration for the Privy provider.
 *
 * Privy authentication (SMS / email / OAuth / passkey) and `Privy.init` happen outside Rain via the
 * official Privy Android SDK: initialize the [Privy] singleton in `Application.onCreate()`, log the
 * user in, ensure an embedded Ethereum wallet exists (`user.createEthereumWallet()`), then hand the
 * singleton here — mirroring how `TurnkeyConfig` takes an authenticated `TurnkeyContext`.
 *
 * An Ethereum wallet is required even for Solana-only use — provider creation probes it, matching
 * Turnkey. Solana operations additionally need an embedded Solana wallet
 * (`user.createSolanaWallet()`); Rain uses the user's first one.
 *
 * @param privy The initialized, authenticated `Privy` singleton.
 * @param walletAddress Optional explicit embedded-wallet address; when null Rain uses the user's
 *                      first embedded Ethereum wallet. Ethereum only — Solana always uses the
 *                      user's first embedded Solana wallet.
 * @param sessionPolicy Auth-guard and transient-retry behavior for every wallet call.
 * @param onSessionExpired Re-auth hook: invoked once per session death when the Privy session
 *                         dies — whether that is discovered during a wallet call or by the
 *                         passive session watcher. A failure that leaves the session in place,
 *                         a call or refresh that got no answer from the network for one, is not
 *                         a death and leaves it silent; see [PrivyProvider.refreshSession]. May
 *                         run on the calling coroutine's thread or a watcher thread; hop to the
 *                         main thread before touching UI, and never call back into the SDK
 *                         synchronously from it. Restart authentication from here.
 */
class PrivyConfig(
    val privy: Privy,
    val walletAddress: String? = null,
    val sessionPolicy: PrivySessionPolicy = PrivySessionPolicy(),
    val onSessionExpired: (() -> Unit)? = null,
)

/**
 * Privy adapter — the registrable [ProviderDescriptor] for Privy's embedded-key signer.
 *
 * Lives in the `rain-privy-android` module and owns the Privy SDK as a dependency. Custody (signing,
 * broadcasting) routes through Privy's EIP-1193 embedded-wallet provider; balance/fee reads use
 * Rain's configured RPC. Core never imports Privy; linking this module is what pulls it onto the
 * classpath.
 */
class PrivyProvider(
    private val config: PrivyConfig,
) : ProviderDescriptor {

    override val id: ProviderId get() = ProviderId.PRIVY

    override val capabilities: Set<Capability> = CAPABILITIES

    private val coordinator: PrivySessionCoordinator by lazy {
        PrivySessionCoordinator(
            privy = config.privy,
            policy = config.sessionPolicy,
            onSessionExpired = config.onSessionExpired,
        )
    }

    // Lives for the provider's lifetime; the Privy singleton it watches is process-wide anyway.
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The Privy session as seen at the Rain boundary, over time. Emits on every Privy
     * auth-state change, so a host can react to a session dying silently without waiting for a
     * wallet call to fail.
     */
    val sessionState: Flow<PrivySessionState> get() = coordinator.sessionStates

    /** Snapshot of [sessionState] right now. */
    fun currentSessionState(): PrivySessionState = coordinator.currentState()

    /**
     * Forces a Privy session refresh (`PrivyUser.refresh`). Rarely needed — Privy refreshes its
     * own session before every call — but available for hosts that want an explicit health
     * check. The conditions behind each code are stated once, in the provider-adapters section
     * of `docs/METHODS.md`.
     *
     * @throws RainError.TokenExpired (`RAIN_201`), with [PrivyConfig.onSessionExpired] fired; see METHODS.
     * @throws RainError.NetworkError (`RAIN_301`); see METHODS.
     * @throws RainError.ProviderError (`RAIN_501`); see METHODS.
     */
    suspend fun refreshSession() = coordinator.refreshNow()

    /**
     * Stops the passive session watcher. Call when discarding this provider (e.g. rebuilding
     * the SDK for a new login) so a stale provider stops observing the process-wide Privy
     * singleton and can never fire its expiry hook again.
     */
    override fun close() {
        coordinator.stop()
        monitorScope.cancel()
    }

    override suspend fun create(context: ProviderContext): WalletProvider {
        // Always watch: beyond the optional host hook, the watcher drives cached-account
        // eviction on session death so a re-login can never sign with the previous user's
        // cached address.
        coordinator.startMonitoring(monitorScope)

        val provider = PrivyWalletProvider(
            manager = PrivyManager(config.privy, coordinator),
            rpcEndpoints = context.rpcEndpoints,
            tokenStore = context.tokenStore,
            walletAddressOverride = config.walletAddress,
            solanaSupport = context.solanaSupport,
            sessionCoordinator = coordinator,
        )

        // Probe — ensures Privy has an embedded Ethereum wallet available before handing it out.
        withContext(Dispatchers.IO) {
            provider.getWalletAddress()
        }

        return provider
    }

    internal companion object {
        /** Privy holds an exportable embedded key with a recovery flow, over EVM and Solana. */
        val CAPABILITIES: Set<Capability> =
            setOf(Capability.EXPORT, Capability.RECOVERY, Capability.MULTI_CHAIN)
    }
}
