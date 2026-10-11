package com.rain.sdk.internal.tokenstore

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.constants.RainConstants
import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.constants.TokenRegistry
import com.rain.sdk.internal.network.chainreader.ChainReader
import com.rain.sdk.internal.utils.RainAmountUtils
import com.rain.sdk.models.NativeCurrency
import com.rain.sdk.models.TokenInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import timber.log.Timber

/**
 * Owns per-chain token reference data plus a runtime enrichment cache.
 *
 * Seeded from [TokenRegistry] defaults and extendable at runtime via [register] (host apps
 * adding their own tokens). Unknown contract tokens are enriched on demand by reading
 * `decimals()` / `symbol()` through a [ChainReader], then cached so a given token is only
 * enriched once.
 *
 * A plain lock guards the registry and cache maps. It is held only for in-memory map access,
 * never across the enrichment RPC, so a registration is stored before its call returns and
 * concurrent `tokenInfo` calls for different tokens don't serialize behind each other's network
 * round-trips.
 */
@Suppress("TooManyFunctions") // one store: two lookups (display and strict), the registration entry points and their lock helpers
@RainAdapterApi
class TokenMetadataStore(
    private val chainReader: ChainReader,
    seedTokens: List<TokenInfo> = emptyList()
) {
    private val lock = Any()

    /**
     * Known tokens per chain: built-in registry plus host-registered. Insertion order is
     * preserved (registry order first, then registrations) so balance reads are deterministic.
     */
    private val knownTokens: MutableMap<Int, MutableList<TokenInfo>> =
        TokenRegistry.tokensByChainId
            .mapValues { (_, tokens) -> tokens.toMutableList() }
            .toMutableMap()

    /** Tokens discovered and enriched at runtime, keyed by chain ID then lowercased address. */
    private val enrichmentCache: MutableMap<Int, MutableMap<String, TokenInfo>> = mutableMapOf()

    init {
        seedTokens.forEach { upsert(it) }
    }

    /**
     * Adds host-supplied tokens. A token replaces an earlier host registration with the same
     * address (case-insensitive) on the same chain. Built-in registry tokens are never replaced.
     * The entries are stored before this returns, so a lookup that follows sees them.
     */
    @Suppress("RedundantSuspendModifier") // published signature; the lock inside never suspends
    suspend fun register(tokens: List<TokenInfo>) {
        registerNow(tokens)
    }

    /** [register] for callers that cannot suspend: the same lock and the same in-place application. */
    internal fun registerNow(tokens: List<TokenInfo>) {
        synchronized(lock) {
            tokens.forEach { upsert(it) }
        }
    }

    /** Native currency for a chain (gas token metadata). Solana clusters resolve SOL. */
    fun nativeCurrency(chainId: Int): NativeCurrency =
        if (SolanaChains.isSolanaChain(chainId)) {
            SolanaChains.NATIVE_CURRENCY
        } else {
            TokenRegistry.nativeCurrency(chainId)
        }

    /**
     * Native currency for a chain, or `null` when the chain is not in the registry. Unlike
     * [nativeCurrency], this never falls back to an ETH-like default, so callers that must not
     * show a wrong symbol (e.g. transaction history) can distinguish "unknown chain".
     */
    fun nativeCurrencyOrNull(chainId: Int): NativeCurrency? =
        if (SolanaChains.isSolanaChain(chainId)) {
            SolanaChains.NATIVE_CURRENCY
        } else {
            TokenRegistry.nativeCurrencyByChainId[chainId]
        }

    /** All known tokens for a chain (registry + host-registered), in deterministic order. */
    @Suppress("RedundantSuspendModifier") // published signature; the lock inside never suspends
    suspend fun registeredTokens(chainId: Int): List<TokenInfo> =
        synchronized(lock) { knownTokens[chainId]?.toList().orEmpty() }

    /**
     * Resolves metadata for a contract token: known tokens first, then the enrichment cache,
     * then a one-time on-chain `decimals()` / `symbol()` read (cached on success). Falls back to
     * the 18-decimal default when `decimals()` cannot be read or lies outside
     * [RainAmountUtils.DECIMALS_RANGE]; [decimalsOrNull] does not.
     */
    suspend fun tokenInfo(chainId: Int, address: String): TokenInfo {
        val key = address.lowercase()
        val cached = cachedOrNull(chainId, key)
        if (cached != null) return cached

        // Enrich outside the lock so a slow RPC doesn't block lookups for other tokens.
        val enriched = enrich(chainId, address)
        return if (enriched.decimalsResolved) {
            cacheEnriched(chainId, key, enriched.info)
        } else {
            // A fallback `decimals` is a guess, not a fact: caching it would pin a balance that is
            // wrong by orders of magnitude for the rest of the process. Retry on the next lookup.
            Timber.w(
                "Rain SDK: decimals() unresolved for token=%s chainId=%d; %d-decimal default used, not cached",
                address,
                chainId,
                RainConstants.DEFAULT_ERC20_DECIMALS
            )
            enriched.info
        }
    }

    /**
     * Strict metadata resolution: known tokens, then the enrichment cache, then, on EVM chains, a
     * one-time on-chain read; `null` when `decimals` could not be established, never the 18-decimal
     * default. The answer a caller needs before scaling a money amount for a token it knows only by
     * address. `symbol` and `name` may be null inside a non-null result. Solana chains resolve from
     * the registry and host-registered tokens only: the read path is EVM-only and an SPL mint carries
     * no on-chain symbol.
     *
     * @throws RainError.InvalidConfig when the chain reports `decimals()` outside
     *   [RainAmountUtils.DECIMALS_RANGE]: no money path can scale by such a value, so it is refused
     *   here, once, instead of at every caller. Nothing is cached, so a later call reads the chain again.
     */
    internal suspend fun tokenInfoOrNull(chainId: Int, address: String): TokenInfo? {
        val key = address.lowercase()
        val cached = cachedOrNull(chainId, key)
        if (cached != null || SolanaChains.isSolanaChain(chainId)) return cached

        // Enrich outside the lock so a slow RPC doesn't block lookups for other tokens.
        val enriched = enrich(chainId, address)
        enriched.rejectedDecimals?.let { rejected ->
            throw RainError.InvalidConfig(
                "Token $address reports $rejected decimals, outside the supported range ${RainAmountUtils.DECIMALS_RANGE}"
            )
        }
        return if (enriched.decimalsResolved) cacheEnriched(chainId, key, enriched.info) else null
    }

    /**
     * A contract token's decimals, or null when unknown to the registry and the on-chain read
     * failed. Never substitutes the 18-decimal default: money paths must not scale by a guess. Solana
     * chain ids resolve from the registry and host registrations only; no on-chain read is attempted
     * for a mint.
     *
     * @throws RainError.InvalidConfig when the chain reports `decimals()` outside
     *   [RainAmountUtils.DECIMALS_RANGE]; see [tokenInfoOrNull].
     */
    suspend fun decimalsOrNull(chainId: Int, address: String): Int? =
        tokenInfoOrNull(chainId, address)?.decimals

    // ---------- Lookup helpers ----------

    /** Known tokens, then the enrichment cache, under the lock. Null when neither holds the token. */
    private fun cachedOrNull(chainId: Int, key: String): TokenInfo? = synchronized(lock) {
        knownTokens[chainId]?.firstOrNull { it.address.lowercase() == key }
            ?: enrichmentCache[chainId]?.get(key)
    }

    /**
     * Caches a resolved enrichment under the lock. Another coroutine may have enriched the same
     * token while this one was off-lock; the earlier entry wins so both callers see one object.
     */
    private fun cacheEnriched(chainId: Int, key: String, info: TokenInfo): TokenInfo = synchronized(lock) {
        enrichmentCache.getOrPut(chainId) { mutableMapOf() }.getOrPut(key) { info }
    }

    // ---------- Enrichment ----------

    /**
     * An enrichment result: whether `decimals` came from the chain or the fallback, and the chain's
     * value when it was read but lies outside [RainAmountUtils.DECIMALS_RANGE].
     */
    private data class Enriched(val info: TokenInfo, val decimalsResolved: Boolean, val rejectedDecimals: Int? = null)

    /** What the `decimals()` read produced: the value to carry, whether it resolved, and a rejected raw value. */
    private data class DecimalsRead(val decimals: Int, val resolved: Boolean, val rejected: Int? = null)

    /**
     * Reads `decimals()`, `symbol()` and `name()` in parallel. A failed `decimals()` falls back to the
     * default and is reported as unresolved; one outside [RainAmountUtils.DECIMALS_RANGE] is reported
     * the same way with the raw value attached, so the strict lookups can refuse it; a failed
     * `symbol()` / `name()` leaves that field `null`.
     */
    private suspend fun enrich(chainId: Int, address: String): Enriched = coroutineScope {
        val decimalsTask = async {
            runCatching { chainReader.getDecimals(chainId, address) }
                .fold(
                    onSuccess = { decimals ->
                        if (decimals in RainAmountUtils.DECIMALS_RANGE) {
                            DecimalsRead(decimals, resolved = true)
                        } else {
                            // No money path can scale by such a value: the display lookup falls back to
                            // the default and caches nothing, the strict lookups refuse the token.
                            Timber.w(
                                "Rain SDK: decimals() for token=%s chainId=%d is %d, outside %s; metadata unresolved",
                                address,
                                chainId,
                                decimals,
                                RainAmountUtils.DECIMALS_RANGE
                            )
                            DecimalsRead(RainConstants.DEFAULT_ERC20_DECIMALS, resolved = false, rejected = decimals)
                        }
                    },
                    onFailure = { e ->
                        if (e is CancellationException) throw e
                        // The caller decides what an unresolved decimals means: `tokenInfo` falls
                        // back to the default for display, the strict lookups return null.
                        Timber.w(
                            e,
                            "Rain SDK: decimals() read failed for token=$address " +
                                "chainId=$chainId; metadata unresolved"
                        )
                        DecimalsRead(RainConstants.DEFAULT_ERC20_DECIMALS, resolved = false)
                    }
                )
        }
        val symbolTask = async {
            runCatching { chainReader.getSymbol(chainId, address) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    null
                }
        }
        val nameTask = async {
            runCatching { chainReader.getName(chainId, address) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    null
                }
        }

        val read = decimalsTask.await()
        Enriched(
            info = TokenInfo(
                chainId = chainId,
                address = address,
                symbol = symbolTask.await(),
                decimals = read.decimals,
                name = nameTask.await()
            ),
            decimalsResolved = read.resolved,
            rejectedDecimals = read.rejected
        )
    }

    // ---------- Helpers ----------

    /** Must be called while holding [lock]. */
    private fun upsert(token: TokenInfo) {
        val key = token.address.lowercase()
        // The registry is the trusted source for its own tokens: a host-supplied `decimals` for
        // one would rescale every balance and approval against it, so the registration is dropped.
        val trusted = TokenRegistry.tokensFor(token.chainId).firstOrNull { it.address.lowercase() == key }
        if (trusted != null) {
            // The same metadata under another spelling of the address (an API that lowercases, for
            // one) is a repeat, not an override, and gets no warning.
            if (trusted != token.copy(address = trusted.address)) {
                Timber.w(
                    "Rain SDK: Ignoring registration of %s on chain %d: built-in token %s (%d decimals) cannot be overridden",
                    token.address,
                    token.chainId,
                    trusted.symbol ?: "?",
                    trusted.decimals
                )
            }
            return
        }
        val list = knownTokens.getOrPut(token.chainId) { mutableListOf() }
        val index = list.indexOfFirst { it.address.lowercase() == key }
        if (index >= 0) {
            list[index] = token
        } else {
            list.add(token)
        }
    }
}
