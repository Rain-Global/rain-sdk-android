package com.rain.sdk

import com.rain.sdk.error.RainError
import com.rain.sdk.error.noRpcEndpointConfigured
import com.rain.sdk.interfaces.CollateralAdminCheck
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.internal.core.ConfigManager
import com.rain.sdk.internal.core.RainSdkManager
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import com.rain.sdk.internal.error.ErrorMapper
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.solana.SolanaSupport
import com.rain.sdk.internal.tokenstore.TokenInfoValidation
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.internal.utils.RainHexUtils
import com.rain.sdk.internal.utils.isValidEthereumAddress
import com.rain.sdk.internal.utils.isZeroAddress
import com.rain.sdk.models.NetworkConfig
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainEIP712Message
import com.rain.sdk.models.RainTransactionParameters
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderDescriptor
import com.rain.sdk.provider.ProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry point for the modular Rain SDK.
 *
 * Built via [builder]; the host registers exactly the provider adapters it ships
 * (each a [com.rain.sdk.provider.ProviderDescriptor], such as `rain-portal-android`'s `PortalProvider`
 * or `rain-turnkey-android`'s `TurnkeyProvider`) and the chains it talks to. Nothing here references a concrete vendor
 * type — a provider whose module isn't on the classpath simply can't be registered.
 *
 * ```kotlin
 * val rain = RainSdk.builder()
 *     .rpcEndpoints(mapOf(43114 to "https://avalanche-c-chain-rpc.publicnode.com"))
 *     .register(PortalProvider(PortalConfig(sessionToken)))
 *     .build()
 *
 * // resolve by id…
 * val client = rain.provider(ProviderId.PORTAL)
 * // …or by capability
 * val exporter = rain.first { Capability.EXPORT in it.capabilities }
 * ```
 *
 * The multi-provider case is the design point; a single-provider app is just the trivial `N = 1`
 * instance — register one adapter and never call [provider] with a second.
 */
class RainSdk private constructor(
    private val rpcEndpoints: Map<Int, String>,
    private val registered: Map<ProviderId, ProviderDescriptor>,
    private val seedTokens: List<TokenInfo>,
    private val authPullConfig: RainAuthPullConfig?,
) {
    private val mutex = Mutex()

    private val errorMapper = ErrorMapper()

    // Concurrent so [reset] can evict safely without holding the resolution mutex.
    private val clients = ConcurrentHashMap<ProviderId, RainClient>()

    /**
     * Auth Pull targets for this instance, handed to every resolved client so an approval cannot
     * target another environment's chains or another token.
     *
     * Narrowed to chains that actually have an RPC endpoint: the allowance read and the approval
     * both go out over [rpcEndpoints], so a configured chain with no endpoint could never work.
     */
    private val authPullTokenAddresses: Map<Int, String> =
        authPullConfig?.tokenAddresses?.filterKeys { it in rpcEndpoints }.orEmpty()

    /**
     * The chains an Auth Pull approval is accepted on, for gating host UI before a client is
     * resolved; [RainClient.authPullChainIds] is the same set, and what the approval guard enforces.
     * Empty when no [Builder.authPullConfig] was supplied. The `authPullChainIds` section in
     * `docs/METHODS.md` has the rules.
     */
    val authPullChainIds: Set<Int> = authPullTokenAddresses.keys.toSet()

    @Volatile
    private var closed = false

    /**
     * Withdrawal-building primitives bound to this instance's endpoints. Instance-scoped, so two
     * SDKs configured differently never read each other's chain configuration.
     */
    private val builderImpl = RainTransactionBuilderImpl(rpcEndpoints)

    init {
        // Fail fast on a bad URL / chain id, before anything is resolved.
        ConfigManager().validateRpcEndpoints(rpcEndpoints)
    }

    /**
     * Shared, vendor-free infrastructure handed to every provider. Built exactly once, after
     * endpoint validation, so every client and [tokenMetadata] see one [TokenMetadataStore].
     */
    private val sharedContext: ProviderContext = run {
        val evm = EvmChainReader(rpcEndpoints = rpcEndpoints)
        ProviderContext(
            rpcEndpoints = rpcEndpoints,
            tokenStore = TokenMetadataStore(chainReader = evm, seedTokens = seedTokens),
            evmChainReader = evm,
            solanaSupport = SolanaSupport(rpcEndpoints),
        )
    }

    /** Ids of every provider the host registered. */
    val providerIds: Set<ProviderId> get() = registered.keys

    /** The registered provider descriptors in registration order, for capability resolution. */
    val providers: List<ProviderDescriptor> get() = registered.values.toList()

    // --- Wallet-agnostic transaction building ---------------------------------------------
    // These need no wallet provider — only the configured RPC endpoints — so they're available
    // straight off the SDK without resolving a [provider].

    /**
     * What [buildEIP712Message] binds as `nonce` when the caller passes null: the collateral's
     * current admin nonce, read from the chain. The `getLatestNonce` section in `docs/METHODS.md`
     * has the rules.
     */
    @Throws(RainError::class)
    suspend fun getLatestNonce(chainId: Int, proxyAddress: String): BigInteger =
        builderImpl.getLatestNonce(chainId, proxyAddress)

    /**
     * Whether [walletAddress] is an admin of the collateral contract at [proxyAddress]. The
     * `isCollateralAdmin` section in `docs/METHODS.md` has the rules.
     *
     * @return `true` or `false` as the contract answers, or `null` when the check could not run
     *   (an RPC failure, or a collateral exposing no `isAdmin`). A `null` means unknown: proceed,
     *   and never read it as "not authorized". A withdrawal proceeds on `null`; a non-zero Auth
     *   Pull approval is refused.
     */
    suspend fun isCollateralAdmin(
        chainId: Int,
        proxyAddress: String,
        walletAddress: String,
    ): Boolean? = when (builderImpl.isCollateralAdmin(chainId, proxyAddress, walletAddress)) {
        CollateralAdminCheck.Admin -> true
        CollateralAdminCheck.NotAdmin -> false
        is CollateralAdminCheck.Failed, CollateralAdminCheck.NotCollateral -> null
    }

    /**
     * The [RainEIP712Message] for a withdrawal: the typed data the wallet signs and the salt bound
     * into it. A null [nonce] reads the collateral's current nonce from the chain, as
     * [getLatestNonce] does. The `buildEIP712Message` section in `docs/METHODS.md` has the rules.
     */
    @Throws(RainError::class)
    suspend fun buildEIP712Message(
        chainId: Int,
        walletAddress: String,
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        nonce: BigInteger? = null,
    ): RainEIP712Message = builderImpl.buildEIP712Message(
        chainId = chainId,
        walletAddress = walletAddress,
        addresses = addresses,
        amount = amount,
        decimals = decimals,
        nonce = nonce,
    )

    /**
     * ABI-encodes the `withdrawAsset` call for the collateral controller. Pure encoding — no RPC,
     * so it needs no chain id.
     *
     * @param executorSignature Rain's authorization for this withdrawal, obtained by the host from the Rain API,
     *   `GET /v1/issuing/users/{userId}/signatures/withdrawals`.
     * @param walletSalt The salt from [RainEIP712Message.salt], unchanged.
     * @param walletSignature The wallet's hex signature over [RainEIP712Message.message].
     */
    @Throws(RainError::class)
    fun buildWithdrawTransactionData(
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        executorSignature: RainAdminSignature,
        walletSalt: ByteArray,
        walletSignature: String,
    ): String = builderImpl.buildWithdrawTransactionData(
        addresses = addresses,
        amount = amount,
        decimals = decimals,
        executorSignature = executorSignature,
        walletSalt = walletSalt,
        walletSignature = walletSignature,
    )

    /**
     * Composes Rain-owned transaction parameters. Rain-owned so the public surface does not leak
     * Portal/Turnkey types. Pure composition — no wallet provider and no RPC involved.
     *
     * @param walletAddress Address of the sender wallet.
     * @param contractAddress Target smart contract address.
     * @param transactionData Hex-encoded calldata.
     */
    fun buildTransactionParameters(
        walletAddress: String,
        contractAddress: String,
        transactionData: String,
    ): RainTransactionParameters = RainTransactionParameters(
        from = walletAddress,
        to = contractAddress,
        value = "0x0",
        data = transactionData,
    )

    /**
     * Resolves the [RainClient] backed by the provider registered under [id], materializing the
     * vendor wallet on first access and caching it thereafter.
     *
     * @throws RainError.ProviderNotRegistered if no provider was registered for [id].
     */
    suspend fun provider(id: ProviderId): RainClient = mutex.withLock {
        if (closed) throw RainError.SdkNotInitialized()
        clients[id]?.let { return it }
        val descriptor = registered[id]
            ?: throw RainError.ProviderNotRegistered("no provider registered for id '${id.value}'")
        val context = sharedContext
        // Init-time vendor failures (a rejected session token, most often) go through the same
        // error contract as every later call; a raw vendor exception here would bypass it exactly
        // when credentials tend to be wrong.
        val walletProvider = try {
            descriptor.create(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RainError) {
            throw e
        } catch (e: Exception) {
            throw errorMapper.mapProviderInitError(e)
        }
        RainSdkManager(
            walletProvider = walletProvider,
            rpcEndpoints = rpcEndpoints,
            tokenStore = context.tokenStore,
            transactionBuilder = builderImpl,
            providerId = descriptor.id,
            capabilities = descriptor.capabilities,
            chainReader = sharedContext.evmChainReader,
            authPullChainIds = authPullChainIds,
            authPullTokenAddresses = authPullTokenAddresses,
        ).also {
            clients[id] = it
            Timber.d("Rain SDK: Resolved provider '${id.value}'")
        }
    }

    /**
     * Resolves the first registered provider matching [predicate] (e.g. by capability) and returns
     * its [RainClient].
     *
     * @throws RainError.ProviderNotRegistered if no registered provider matches.
     */
    suspend fun first(predicate: (ProviderDescriptor) -> Boolean): RainClient {
        val match = registered.values.firstOrNull(predicate)
            ?: throw RainError.ProviderNotRegistered(
                "no registered provider matches the requested capability"
            )
        return provider(match.id)
    }

    // --- Token metadata ------------------------------------------------------------------

    /**
     * Resolves `symbol`, `name` and `decimals` for a token by address, with no wallet provider:
     * the built-in registry first, then host-registered tokens, then on-chain reads over the chain's
     * RPC endpoint. `null` when the decimals could not be established, never a guessed default,
     * since callers scale amounts with the value; a `null` result is not cached. The
     * `tokenMetadata` section in `docs/METHODS.md` has the rules.
     *
     * @param chainId Numeric chain ID the token lives on; it must have a configured RPC endpoint.
     * @param address Token contract address, or the SPL mint on Solana chains.
     * @return The token's metadata, or `null` when its decimals could not be established.
     * @throws RainError.SdkNotInitialized (`RAIN_101`) after [close]
     * @throws RainError.InvalidConfig (`RAIN_102`) when [chainId] has no RPC endpoint, a
     *   configuration error even for a registry token, or when [address] is malformed for its chain
     *   family. Also when the chain reports `decimals()` outside 0..77; nothing is cached then
     */
    @Throws(RainError::class)
    suspend fun tokenMetadata(chainId: Int, address: String): TokenInfo? {
        if (closed) throw RainError.SdkNotInitialized()
        if (chainId !in rpcEndpoints) throw noRpcEndpointConfigured(chainId)
        TokenInfoValidation.requireValidAddress(chainId, address)
        return sharedContext.tokenStore.tokenInfoOrNull(chainId, address)
    }

    /**
     * Registers token metadata after [Builder.build], without a resolved provider. The entries are
     * in the shared store before this returns, so a [tokenMetadata] call that follows, and every
     * resolved client, sees them. [Builder.registerTokens] seeds and [RainClient.registerTokens]
     * stores into the same store with the same checks; the `registerTokens` section under `RainSdk`
     * in `docs/METHODS.md` has the rules. The chain id of an entry is not checked against the
     * configured endpoints; a later [tokenMetadata] for a chain the SDK was not built with throws
     * regardless.
     *
     * @param tokens Tokens to add to the shared token store; an empty list is a no-op.
     * @throws RainError.SdkNotInitialized (`RAIN_101`) after [close]
     * @throws RainError.InvalidConfig (`RAIN_102`) when an entry's address is malformed for its chain
     *   family, or its `decimals` lies outside 0..77. EVM: `0x` followed by 40 hex characters with a
     *   correct EIP-55 checksum when mixed-case; Solana: base58 decoding to 32 bytes. The whole list
     *   is validated first, so nothing is registered
     */
    @Throws(RainError::class)
    suspend fun registerTokens(tokens: List<TokenInfo>) {
        if (closed) throw RainError.SdkNotInitialized()
        if (tokens.isEmpty()) return
        TokenInfoValidation.requireValid(tokens)
        sharedContext.tokenStore.register(tokens)
    }

    /**
     * Evicts the resolved clients; the next [provider] / [first] call re-resolves from scratch on
     * the same chain configuration, which is fixed at [Builder.build]. Idempotent. The `reset()`
     * section under `RainSdk` in `docs/METHODS.md` has the rules.
     *
     * The registered providers keep running, their vendor clients and session watchers included,
     * so a re-resolve is cheap. On logout, or whenever this instance is being discarded, call
     * [close] instead: after a reset alone the session watchers still observe the process-wide
     * vendor singletons and can fire `onSessionExpired`.
     */
    fun reset() {
        clients.values.forEach { runCatching { it.reset() } }
        clients.clear()
        Timber.d("Rain SDK: Reset (resolved clients evicted)")
    }

    /**
     * Full teardown: [reset], then closes every registered provider so their vendor clients and
     * session watchers stop and their `onSessionExpired` hooks can never fire again. Idempotent.
     *
     * Terminal, unlike [reset]: [provider], [first], [tokenMetadata] and [registerTokens] throw
     * [RainError.SdkNotInitialized] afterwards. Build a new [RainSdk] via [builder] for the next login.
     */
    fun close() {
        closed = true
        reset()
        registered.values.forEach { runCatching { it.close() } }
        Timber.d("Rain SDK: Closed (providers torn down)")
    }

    companion object {
        /**
         * Why `Builder.build()` refuses the Rain wallet and the Turnkey provider together; one literal so
         * the test cannot drift. It names the vendor on purpose: the host registered `TurnkeyProvider`
         * by that name, so the name is what makes the message actionable. The wallet module's own
         * docs stay vendor-free, which is a different surface with a different reader.
         */
        internal const val RAIN_AND_TURNKEY_CONFLICT_MESSAGE: String =
            "The Rain wallet provider and the Turnkey provider cannot both be registered; " +
                "they share one process-wide wallet backend"

        /** Starts a new [Builder]. */
        fun builder(): Builder = Builder()
    }

    /**
     * Assembles a [RainSdk]. Module dependencies decide which providers can be registered — the
     * builder never names a vendor SDK itself.
     */
    class Builder internal constructor() {
        private val descriptors = LinkedHashMap<ProviderId, ProviderDescriptor>()
        private var rpcEndpoints: Map<Int, String> = emptyMap()
        private val seedTokens = mutableListOf<TokenInfo>()
        private var authPullConfig: RainAuthPullConfig? = null

        /** Sets the `chainId → RPC URL` map every provider shares. Required. */
        fun rpcEndpoints(endpoints: Map<Int, String>): Builder = apply {
            rpcEndpoints = endpoints
        }

        /**
         * Sets the chains every provider shares, as [NetworkConfig] values. Replaces any
         * previously set endpoints rather than appending. A later duplicate [NetworkConfig.chainId]
         * wins.
         */
        fun rpcEndpoints(configs: List<NetworkConfig>): Builder = apply {
            rpcEndpoints = configs.associate { it.chainId to it.rpcUrl }
        }

        private val replaced = mutableListOf<ProviderDescriptor>()

        /**
         * Registers a provider adapter. Re-registering the same id replaces the prior descriptor;
         * [build] closes the replaced instance once the registry is valid, so a replaced descriptor's
         * session watcher does not outlive the registry; a build the token, Auth Pull or provider
         * checks refuse leaves the host's object untouched, while one that fails on a bad RPC URL or
         * chain id has already closed it. Registering the same instance twice is a no-op.
         */
        fun register(descriptor: ProviderDescriptor): Builder = apply {
            val previous = descriptors.put(descriptor.id, descriptor)
            // Registered again after being replaced: it is live, so it must not be closed at build.
            replaced.removeAll { it === descriptor }
            if (previous != null && previous !== descriptor) replaced += previous
        }

        /**
         * Seeds the shared token store with extra token metadata; [build] runs the checks of
         * [RainSdk.registerTokens] on the seeds and refuses the build on a bad entry.
         */
        fun registerTokens(tokens: List<TokenInfo>): Builder = apply {
            seedTokens += tokens
        }

        /**
         * Enables Auth Pull for the exact token contracts in [config]. Without this call, approval,
         * allowance, confirmation, and approval-fee methods fail closed.
         */
        fun authPullConfig(config: RainAuthPullConfig): Builder = apply {
            authPullConfig = config
        }

        /**
         * Zero registered providers is allowed: the SDK is then wallet-agnostic, exposing the
         * transaction-building helpers ([RainSdk.buildEIP712Message], [RainSdk.buildWithdrawTransactionData]),
         * [RainSdk.tokenMetadata] and [RainSdk.registerTokens]; resolving a [RainSdk.provider] still throws
         * [RainError.ProviderNotRegistered] until one is registered.
         *
         * @throws RainError.InvalidConfig if no RPC endpoints were configured, a seed token or the Auth
         *   Pull configuration is invalid, or both the Rain wallet provider and the Turnkey provider are
         *   registered: they drive one process-wide wallet backend, so an app uses one or the other.
         */
        fun build(): RainSdk {
            if (rpcEndpoints.isEmpty()) {
                throw RainError.InvalidConfig("At least one RPC endpoint is required")
            }
            TokenInfoValidation.requireValid(seedTokens)
            validateAuthPullConfig()
            requireOneWalletBackendProvider()
            // Ownership moves here: descriptors replaced during registration are closed only once the
            // registry is valid, and a throwing host `close()` cannot abort the build.
            replaced.forEach { runCatching { it.close() } }
            replaced.clear()
            return RainSdk(
                rpcEndpoints = rpcEndpoints.toMap(),
                registered = descriptors.toMap(),
                seedTokens = seedTokens.toList(),
                authPullConfig = authPullConfig,
            )
        }

        /**
         * Best-effort: a second RainSdk instance, or a provider that is never registered, can still
         * collide on the backend, and the backend's own configuration check reports that on the
         * first authentication call.
         */
        private fun requireOneWalletBackendProvider() {
            if (ProviderId.RAIN in descriptors && ProviderId.TURNKEY in descriptors) {
                throw RainError.InvalidConfig(RAIN_AND_TURNKEY_CONFLICT_MESSAGE)
            }
        }

        private fun validateAuthPullConfig() {
            val config = authPullConfig ?: return
            if (config.tokenAddresses.isEmpty()) {
                throw RainError.InvalidConfig("Auth Pull must configure at least one token contract")
            }

            // A configuration may name the chains of the environment its kind stands for; a custom
            // configuration may draw on either environment's set, and nothing outside them.
            val unexpected = config.tokenAddresses.keys - RainAuthPullChains.supported(config.kind)
            if (unexpected.isNotEmpty()) {
                throw RainError.InvalidConfig(
                    "Auth Pull chains ${unexpected.sorted()} are not Auth Pull chains for this configuration's environment"
                )
            }
            config.tokenAddresses.forEach { (chainId, address) ->
                if (!address.isValidEthereumAddress || address.isZeroAddress) {
                    throw RainError.InvalidConfig(
                        "Invalid Auth Pull token contract for chainId=$chainId: $address"
                    )
                }
                if (!RainHexUtils.hasValidChecksum(address)) {
                    throw RainError.InvalidConfig(
                        "Invalid Auth Pull token contract checksum for chainId=$chainId: $address"
                    )
                }
            }
            if (config.tokenAddresses.keys.none { it in rpcEndpoints }) {
                throw RainError.InvalidConfig(
                    "No RPC endpoint configured for any trusted Auth Pull chain"
                )
            }
        }
    }
}
