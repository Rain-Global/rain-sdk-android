package com.rain.sdk.turnkey

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.abi.Erc20Abi
import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.network.chainreader.ChainReader
import com.rain.sdk.internal.solana.SolanaTransferComposer
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.models.UnsignedSolanaTransfer
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.WalletProvider
import com.rain.sdk.utils.EthereumConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turnkey-based implementation of [WalletProvider]. Materialized by [TurnkeyProvider] when a
 * registered Turnkey provider is resolved.
 *
 * The port over [TurnkeyManager]: this class routes by chain family, gates sends, composes Solana
 * transfers through core's Solana support and shapes each [WalletProvider] method; every Turnkey
 * call belongs to the manager, the way `PortalWalletProvider` sits over `PortalManager`.
 *
 * Balance reads route through Turnkey's `get_wallet_address_balances` when the chain is in
 * [TurnkeyBroadcastChains.BALANCE_API_CHAIN_IDS] and the organization has the balance feature, and the
 * token list adds, from the injected [ChainReader], the registry and host-registered tokens that answer
 * left out; everything else falls through to the [ChainReader] alone (parallel `eth_call` + Multicall3
 * where deployed), a refused backend read included: on the feature gate for both reads, on any other
 * refused status for the native balance only (see [backendBalancesOrNull]).
 */
internal class TurnkeyWalletProvider(
    private val manager: TurnkeyManager,
    private val chainReader: ChainReader,
    private val solanaChainReader: ChainReader,
    private val solanaTransferComposer: SolanaTransferComposer,
    private val tokenStore: TokenMetadataStore,
    private val registryReadTimeoutMs: Long = REGISTRY_READ_TIMEOUT_MS,
) : WalletProvider {

    override val id: ProviderId get() = ProviderId.TURNKEY

    /**
     * Turnkey holds EVM + Solana accounts (multi-chain) and signs with a device key (no biometric prompt gates signing).
     * With [sponsorGas] on it also advertises [Capability.GAS_SPONSORSHIP], which tells core to
     * skip the self-paid preflights that would charge the fee to the wallet (the Solana
     * collateral-withdrawal dry run) when composing transactions this provider will sign.
     * Shared with [TurnkeyProvider] through [capabilitiesFor]: `RainSdk` copies the descriptor's
     * set onto the resolved client, so the two must never disagree.
     */
    override val capabilities: Set<Capability> get() = capabilitiesFor(sponsorGas)

    /** One value for the whole adapter: the manager's flag decides the send body, this decides the estimate. */
    private val sponsorGas: Boolean get() = manager.sponsorGas

    /** Core's up-front gate for withdrawals and approvals; the registry answers, as for transfers. */
    override fun requireSendSupport(chainId: Int) = TurnkeyBroadcastChains.requireSendSupport(chainId)

    /** Sponsorship applies exactly where Turnkey can broadcast; elsewhere the wallet pays. */
    override fun sponsorsFees(chainId: Int): Boolean =
        sponsorGas && TurnkeyBroadcastChains.supportsSend(chainId)

    /** Picks the reader for [chainId]'s chain family. */
    private fun chainReaderFor(chainId: Int): ChainReader =
        if (SolanaChains.isSolanaChain(chainId)) solanaChainReader else chainReader

    internal companion object {
        /**
         * How long a balance list waits for the registry read that runs alongside the wallet
         * backend's call: long enough for a healthy RPC round trip, well under the JSON-RPC client's
         * 10-second call timeout, so a dead RPC delays a balance poll by at most this much.
         */
        const val REGISTRY_READ_TIMEOUT_MS = 5_000L

        /**
         * The capabilities a Turnkey provider advertises for a given [sponsorGas] setting. The one
         * source for both the [TurnkeyProvider] descriptor (what hosts see through
         * `client.capabilities` and `rain.first { }`) and the wallet provider it creates (what core
         * reads when composing transactions), so a host and core can never disagree about
         * whether this provider's sends are sponsored. [Capability.EXPORT] is always on, because
         * the descriptor exports the recovery phrase and private keys in both modes.
         * [Capability.BIOMETRIC_GATE] is not advertised: the vendor stamps with a device key without
         * a user-verification prompt, so nothing here gates signing behind biometrics.
         */
        fun capabilitiesFor(sponsorGas: Boolean): Set<Capability> = buildSet {
            add(Capability.EXPORT)
            add(Capability.MULTI_CHAIN)
            if (sponsorGas) add(Capability.GAS_SPONSORSHIP)
        }
    }

    /** True when Turnkey's `get-balances` API covers [chainId] (EVM allowlist or any Solana cluster). */
    private fun usesTurnkeyForBalances(chainId: Int): Boolean =
        chainId in TurnkeyBroadcastChains.BALANCE_API_CHAIN_IDS || SolanaChains.isSolanaChain(chainId)

    // ---------- address ----------

    override suspend fun getWalletAddress(): String = manager.getAddress()

    /**
     * Chain-aware address. Solana chains resolve the Turnkey Solana account (base58, ed25519);
     * every other chain shares the Ethereum account. Internal balance / send paths use this so
     * a Solana request never reads or signs with the EVM address.
     */
    override suspend fun getWalletAddress(chainId: Int): String = manager.getWalletAddress(chainId)

    // ---------- high-level send ----------

    override suspend fun sendNativeToken(
        chainId: Int,
        toAddress: String,
        amountInEth: BigDecimal
    ): String {
        TurnkeyBroadcastChains.requireSendSupport(chainId)
        if (SolanaChains.isSolanaChain(chainId)) {
            return sendSolanaNative(chainId, toAddress, amountInEth)
        }
        val from = getWalletAddress(chainId)
        val decimals = tokenStore.nativeCurrency(chainId).decimals
        val valueHex = EthereumConverter.convertEthToWeiHex(amountInEth, decimals)
        return manager.sendEvmTransaction(
            chainId = chainId,
            from = from,
            to = toAddress,
            data = "0x",
            value = valueHex
        )
    }

    override suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        toAddress: String,
        amount: BigDecimal,
        decimals: Int
    ): String {
        TurnkeyBroadcastChains.requireSendSupport(chainId)
        if (SolanaChains.isSolanaChain(chainId)) {
            // `decimals` is deliberately unread — it is not authoritative here. sendSolanaSplToken
            // reads the mint's own scale from the chain, which `TransferChecked` then enforces.
            return sendSolanaSplToken(chainId, contractAddress, toAddress, amount)
        }
        val from = getWalletAddress(chainId)
        val data = Erc20Abi.encodeTransfer(toAddress, amount, decimals)
        return manager.sendEvmTransaction(
            chainId = chainId,
            from = from,
            to = contractAddress,
            data = data,
            value = "0x0"
        )
    }

    // ---------- low-level send / sign / fee ----------

    /**
     * Raw sends follow [sponsorGas] exactly like the transfer entries. Withdrawals, Auth Pull
     * approvals, and host-composed calldata arrive here, and Turnkey sponsors any
     * `ethSendTransaction`, not just plain transfers. A zero-balance card user's first action
     * is often the Auth Pull approval, so leaving these self-paid would defeat the feature.
     * Sponsorship cost passes through to the partner that turned the flag on.
     */
    override suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): String = manager.sendEvmTransaction(chainId, from, to, data, value)

    override suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String
    ): String {
        requireEvmChain(chainId, "signTypedData")
        return manager.signTypedData(walletAddress, typedDataJson)
    }

    /**
     * Quotes what the wallet would pay to send this transaction itself, sponsored or not:
     * `eth_estimateGas` times `eth_gasPrice` over the chain's RPC. On a sponsored chain a sponsor pays
     * instead, through its own outer transaction whose cost is not quoted; the number lets a host show
     * what sponsorship saves the user.
     */
    override suspend fun estimateTransactionFee(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): BigDecimal {
        requireEvmChain(chainId, "estimateTransactionFee")
        return manager.estimateTransactionFee(chainId, from, to, data, value)
    }

    // ---------- balances ----------

    override suspend fun getBalance(chainId: Int, token: Token): Balance {
        val walletAddress = getWalletAddress(chainId)

        // Solana has its own balance policy (Turnkey-first with an RPC fallback),
        // so it branches out before the EVM logic below.
        if (SolanaChains.isSolanaChain(chainId)) {
            return solanaBalance(chainId, walletAddress, token)
        }

        return when (token) {
            is Token.Contract -> {
                // `eth_call balanceOf` is the same operation everywhere — delegate to the
                // chain reader so the SDK has one implementation rather than per-adapter copies.
                val info = tokenStore.tokenInfo(chainId, token.address)
                chainReaderFor(chainId).getBalance(
                    chainId = chainId,
                    walletAddress = walletAddress,
                    token = token,
                    tokenInfo = info
                )
            }
            is Token.Native -> {
                if (!usesTurnkeyForBalances(chainId)) {
                    nativeBalanceFromNode(chainId, walletAddress)
                } else {
                    var refusal: RainError? = null
                    backendBalancesOrNull(gateOnly = false, onRefused = { refusal = it }) {
                        manager.evmNativeBalance(chainId, walletAddress, tokenStore)
                    } ?: nativeBalanceFromNode(chainId, walletAddress, insteadOf = refusal)
                }
            }
        }
    }

    /**
     * Solana balance read. Turnkey is the primary source, with the Solana RPC reader as the
     * fallback for both native SOL and SPL tokens — Turnkey does not index every cluster (devnet
     * in particular), and the node always does.
     */
    private suspend fun solanaBalance(chainId: Int, walletAddress: String, token: Token): Balance {
        val fromTurnkey = runCatching {
            manager.solanaBalanceOrNull(chainId, walletAddress, token, tokenStore)
        }.getOrElse {
            if (it is CancellationException) throw it
            // A dead session must surface, not be masked by the node fallback — the coordinator
            // already tried a refresh before this error was thrown.
            if (it is RainError.TokenExpired) throw it
            return nodeBalance(chainId, walletAddress, token)
        }
        // Turnkey omits zero balances, and on a cluster it does not index every mint looks like a
        // zero — so a missing SPL entry is read from the node, once, rather than reported as zero
        // with unknown decimals. A failing node read surfaces, as it does on every other path.
        return fromTurnkey ?: nodeBalance(chainId, walletAddress, token)
    }

    /** The node's answer for [token], named from the host's registry when it has an entry. */
    private suspend fun nodeBalance(chainId: Int, walletAddress: String, token: Token): Balance =
        chainReaderFor(chainId).getBalance(
            chainId = chainId,
            walletAddress = walletAddress,
            token = token,
            tokenInfo = (token as? Token.Contract)?.let { registeredSplToken(chainId, it.address) }
        )

    /**
     * Host-registered metadata for [mint], if any.
     *
     * Reads only the registry — never [TokenMetadataStore.tokenInfo], whose enrichment path goes
     * through the EVM reader and cannot describe an SPL mint. This is how a caller names a token
     * that no indexer covers, via `registerTokens`.
     */
    private suspend fun registeredSplToken(chainId: Int, mint: String): TokenInfo? =
        tokenStore.registeredTokens(chainId).firstOrNull { it.address.equals(mint, ignoreCase = true) }

    override suspend fun getBalances(chainId: Int): List<Balance> {
        val walletAddress = getWalletAddress(chainId)

        if (!usesTurnkeyForBalances(chainId)) {
            return balancesFromNode(chainId, walletAddress)
        }

        if (SolanaChains.isSolanaChain(chainId)) {
            return manager.solanaBalancesOrNull(chainId, walletAddress, tokenStore)
                ?: balancesFromNode(chainId, walletAddress)
        }

        return evmBalancesWithRegistry(chainId, walletAddress)
    }

    /**
     * The wallet backend's balance list plus the registry and host-registered tokens it left out,
     * read from the chain. The backend answers only for the assets it catalogues, so a token outside
     * that catalogue (Rain's sandbox collateral token on Base Sepolia, for one) would never reach the
     * list, however the host registered it. The chain batch runs concurrently with the backend call,
     * so the list waits for the slower of the two, the chain read for at most [registryReadTimeoutMs],
     * and a backend failure cancels the chain read. Where both answer for a token the backend's row
     * wins, zero rows are dropped, as on every other path, and a chain with nothing registered, or no
     * RPC endpoint, is not read.
     *
     * When the backend refuses the read on the feature gate (see [backendBalancesOrNull]) the chain is
     * the only source and answers the way it does on a chain the backend never covered: the batch
     * already in flight is used when it answered, a batch that gave up is repeated as a strict read
     * whose failure surfaces, and once the gate is known the backend is not asked and no batch is
     * started alongside it.
     */
    private suspend fun evmBalancesWithRegistry(chainId: Int, walletAddress: String): List<Balance> = coroutineScope {
        if (balanceServiceRefused.get()) return@coroutineScope balancesFromNode(chainId, walletAddress)
        val registered = tokenStore.registeredTokens(chainId)
        val fromChain = registered.takeIf { it.isNotEmpty() && registryReadable(chainId) }
            ?.let { tokens -> async { registeredTokensFromChain(chainId, walletAddress, tokens) } }
        val listed = backendBalancesOrNull(gateOnly = true) { manager.evmBalances(chainId, walletAddress, tokenStore) }
            ?: return@coroutineScope fromChain?.await()?.droppingZeroTokens() ?: balancesFromNode(chainId, walletAddress)
        if (fromChain == null) return@coroutineScope listed
        val listedTokens = listed.mapTo(HashSet()) { it.token }
        listed + fromChain.await().orEmpty().filter { balance ->
            balance.token is Token.Contract && balance.token !in listedTokens && balance.rawAmount > BigInteger.ZERO
        }
    }

    /** Chains whose registry read has been reported once, so a dead RPC or a missing endpoint does not log per poll. */
    private val registryReadReported: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /**
     * True when the SDK has an RPC endpoint for [chainId] to read the registry from. Without one the
     * list comes from the backend alone (or, on the feature gate, fails with `RAIN_102`, see
     * [evmBalancesWithRegistry]), said once per chain at info level: `getTokenBalances` on a chain
     * the host never configured is the host's decision, not a failure to warn about per poll.
     */
    private fun registryReadable(chainId: Int): Boolean {
        if (manager.hasRpcEndpoint(chainId)) return true
        if (registryReadReported.add(chainId)) {
            Timber.i(
                "Rain SDK: no RPC endpoint for chainId=%d; the registry is not read and the balance list comes from the wallet backend alone",
                chainId
            )
        }
        return false
    }

    /**
     * The registry and host-registered tokens' balances from the chain, in one batch, best effort: a
     * read that fails for any reason but cancellation, or that outlasts [registryReadTimeoutMs],
     * answers null, so it contributes nothing, and is reported once per chain, the way the chain
     * reader treats a token whose `balanceOf` reverts. The backend's rows are read regardless, and a host polling balances
     * through a rate-limited public RPC would otherwise lose a whole chain from `getAllBalances`.
     */
    @Suppress("TooGenericExceptionCaught") // best effort: cancellation is rethrown first, everything else is reported
    private suspend fun registeredTokensFromChain(
        chainId: Int,
        walletAddress: String,
        tokens: List<TokenInfo>
    ): List<Balance>? = try {
        withTimeoutOrNull(registryReadTimeoutMs) { chainReaderFor(chainId).getBalances(chainId, walletAddress, tokens) }
            ?: run {
                reportRegistryRead(chainId, failure = null)
                null
            }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reportRegistryRead(chainId, e)
        null
    }

    /** One warning per chain and provider for a registry read that failed ([failure]) or timed out (null). */
    private fun reportRegistryRead(chainId: Int, failure: Exception?) {
        if (!registryReadReported.add(chainId)) return
        Timber.w(
            failure,
            "Rain SDK: registry and registered tokens not read from the chain for chainId=%d (%s); " +
                "the balance list comes from the wallet backend alone",
            chainId,
            if (failure == null) "no answer within $registryReadTimeoutMs ms" else "read failed"
        )
    }

    /**
     * The native balance from the chain's RPC endpoint, named from the registry. [insteadOf] is the
     * backend refusal this read stands in for, if any. A chain with no usable endpoint cannot stand
     * in, and after a refusal that was not the feature gate the refusal surfaces rather than
     * `RAIN_102`: a backend outage is not a setup error, and a host does not retry one.
     */
    private suspend fun nativeBalanceFromNode(
        chainId: Int,
        walletAddress: String,
        insteadOf: RainError? = null
    ): Balance = try {
        chainReaderFor(chainId).getBalance(
            chainId = chainId,
            walletAddress = walletAddress,
            token = Token.Native,
            tokenInfo = null
        )
    } catch (e: RainError.InvalidConfig) {
        throw insteadOf?.takeUnless { it is RainError.Unauthorized } ?: e
    }

    /**
     * Native plus the tokens the chain reader lists for the wallet, read from the node: on an EVM
     * chain the registry and host-registered tokens, in one batch; on Solana every token account the
     * node reports, named from host registrations so a mint no indexer covers is not a bare address.
     * Zero balances are dropped, matching every other path.
     */
    private suspend fun balancesFromNode(chainId: Int, walletAddress: String): List<Balance> =
        chainReaderFor(chainId).getBalances(chainId, walletAddress, tokenStore.registeredTokens(chainId)).droppingZeroTokens()

    /** The chain reader's rows without the zero token balances: native stays, a token at zero goes, as on every path. */
    private fun List<Balance>.droppingZeroTokens(): List<Balance> =
        filter { balance -> balance.token is Token.Native || balance.rawAmount > BigInteger.ZERO }

    /**
     * Set when the wallet backend refused its balance service with HTTP 403, the feature gate. From
     * then on, for this provider's lifetime, balance reads skip the backend and go to the chain: the
     * gate is an organization setting, not a passing failure, and asking again on every read would
     * cost a doomed round trip and a warning per read. A provider resolved after `reset()` asks again.
     */
    private val balanceServiceRefused = AtomicBoolean(false)

    /** Set once the balance feature gate has been logged, so a wallet without the feature logs it once, not per read. */
    private val balanceGateLogged = AtomicBoolean(false)

    /**
     * [read] against the wallet backend's balance service, or null when the backend refused it and the
     * chain should answer instead; [onRefused] hears the refusal first. Which refusals qualify depends
     * on what the chain can replace. The native balance is the same number from either source, so any
     * refusal qualifies: HTTP 403, or any other HTTP status, retried first when transient. The token
     * list is not the same from either source, because the backend and the registry each list tokens
     * the other lacks, so with [gateOnly] only the feature gate (HTTP 403) qualifies and every other
     * refused status surfaces for the host to retry, as it did before the fallback existed. After a
     * 403 the backend is not asked again (see [balanceServiceRefused]). A dead session, a transport
     * failure and any other error surface: a [RainError] as it is, anything else through the mapper.
     */
    @Suppress("TooGenericExceptionCaught") // a refusal falls back to the chain; everything else is rethrown, cancellation first
    private inline fun <T> backendBalancesOrNull(
        gateOnly: Boolean,
        onRefused: (RainError) -> Unit = {},
        read: () -> T
    ): T? {
        if (balanceServiceRefused.get()) return null
        return try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RainError) {
            recordRefusal(e, gateOnly)
            onRefused(e)
            null
        } catch (e: Exception) {
            throw TurnkeyErrorMapping.map(e)
        }
    }

    /**
     * Rethrows [e] unless it is a refusal the chain may answer for (see [backendBalancesOrNull]). A
     * refusal on the feature gate flips the routing latch; every refusal is logged.
     */
    private fun recordRefusal(e: RainError, gateOnly: Boolean) {
        if (!isBackendRefusal(e, gateOnly)) throw e
        if (e is RainError.Unauthorized) balanceServiceRefused.set(true)
        logRefusal(
            gateLogged = balanceGateLogged,
            gateMessage = "Rain SDK: the wallet backend's balance service is not enabled for this organization; " +
                "balances come from the chain and list the SDK's registry and registered tokens only",
            warnMessage = "Rain SDK: balance read refused by the wallet backend, reading the chain instead",
            e = e
        )
    }

    /**
     * Logs a refusal the way both fallbacks do: the feature gate (HTTP 403, [RainError.Unauthorized])
     * once per provider at info level, tracked by [gateLogged]; any other refused status at warning
     * level with the failure attached, every time. Logging only: the routing latch is the caller's.
     */
    private fun logRefusal(gateLogged: AtomicBoolean, gateMessage: String, warnMessage: String, e: Exception) {
        if (e is RainError.Unauthorized) {
            if (gateLogged.compareAndSet(false, true)) Timber.i(gateMessage)
            return
        }
        Timber.w(e, warnMessage)
    }

    // ---------- transactions ----------

    /** Set once the feature gate has been logged, so a wallet without the feature logs it once, not per page. */
    private val indexedHistoryGateLogged = AtomicBoolean(false)

    /**
     * Transaction history. Turnkey's indexed history queries, issued through the vendor's own
     * client, are the primary source, since they cover the wallet's full on-chain history (receives
     * and externally-submitted transactions included). When the wallet backend refuses the indexed
     * query, most commonly because the history feature is not enabled for the Turnkey organization,
     * the provider falls back to the activity log, which lists only transactions sent through
     * Turnkey. Every other failure surfaces: a dead session or a transport failure would fail the
     * activity path the same way, and a page the client could not decode is a defect to report, not
     * a reason to show a shorter history.
     */
    override suspend fun getTransactions(
        chainId: Int,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?
    ): List<RainTransaction> {
        try {
            return if (SolanaChains.isSolanaChain(chainId)) {
                manager.indexedSolanaTransactions(chainId, limit, offset, order)
            } else {
                manager.indexedEvmTransactions(chainId, limit, offset, order)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The coordinator hands out RainErrors; anything else escaped the row mapping itself and
            // leaves through the mapper, floored at a provider failure, never as a bare exception core
            // cannot classify.
            if (!isBackendRefusal(e)) throw TurnkeyErrorMapping.map(e)
            logRefusal(
                gateLogged = indexedHistoryGateLogged,
                gateMessage = "Rain SDK: indexed transaction history is not enabled for this wallet backend organization; " +
                    "history comes from the activity log, which lists sends only",
                warnMessage = "Rain SDK: indexed history refused by the wallet backend, falling back to activities",
                e = e
            )
        }
        return if (SolanaChains.isSolanaChain(chainId)) {
            manager.getSolanaTransactionsFromActivities(chainId, limit, offset, order)
        } else {
            manager.getEvmTransactionsFromActivities(chainId, limit, offset, order)
        }
    }

    /**
     * True when the wallet backend answered a query and refused it, the case a fallback covers: the
     * activity log for indexed history, the chain for balances. An HTTP 403 (each feature is enabled
     * per organization; the vendor client drops the body that says so) arrives as
     * [RainError.Unauthorized]; any other HTTP status the coordinator did not retry, or gave up
     * retrying, arrives as [RainError.ProviderError] over the vendor failure that carries the status
     * in its message, and counts only when [gateOnly] is false.
     */
    private fun isBackendRefusal(e: Exception, gateOnly: Boolean = false): Boolean = when (e) {
        is RainError.Unauthorized -> true
        is RainError.ProviderError ->
            !gateOnly && e.cause?.causeChain()?.any { TurnkeyErrorMapping.turnkeyHttpStatus(it) != null } == true
        else -> false
    }

    // ---------- solana sends ----------

    private suspend fun sendSolanaNative(
        chainId: Int,
        toAddress: String,
        amountInSol: BigDecimal
    ): String {
        val from = getWalletAddress(chainId)
        val unsigned = solanaTransferComposer.composeNative(chainId, from, toAddress, amountInSol)
        return manager.submitSolanaTransaction(chainId, from, unsigned)
    }

    /**
     * Sends SPL tokens. Composition and every preflight (mint resolution, token-account
     * derivation/creation, balance and fee checks, simulation) live in [SolanaTransferComposer];
     * this method only signs and broadcasts through Turnkey.
     */
    private suspend fun sendSolanaSplToken(
        chainId: Int,
        mintAddress: String,
        toAddress: String,
        amount: BigDecimal
    ): String {
        val from = getWalletAddress(chainId)
        val unsigned = solanaTransferComposer.composeSplToken(
            chainId,
            from,
            mintAddress,
            toAddress,
            amount,
            sponsoredFees = sponsorGas
        )
        return manager.submitSolanaTransaction(chainId, from, unsigned)
    }

    /**
     * Signs and broadcasts a core-composed Solana transaction (e.g. a collateral withdrawal)
     * with the Turnkey Solana account. Follows [sponsorGas] like every other send: with it on,
     * Turnkey covers the fee, and because this provider then advertises
     * [Capability.GAS_SPONSORSHIP], core composes the withdrawal without its self-paid dry run
     * (which would charge the fee to a wallet that pays none). The composed message is submitted
     * as-is either way.
     */
    override suspend fun sendSolanaTransaction(
        chainId: Int,
        unsigned: UnsignedSolanaTransfer
    ): String {
        TurnkeyBroadcastChains.requireSendSupport(chainId)
        return manager.submitSolanaTransaction(chainId, getWalletAddress(chainId), unsigned)
    }
}

/** Rejects a Solana chain id on the EVM-only entry points, which have no Solana equivalent. */
internal fun requireEvmChain(chainId: Int, operation: String) {
    if (SolanaChains.isSolanaChain(chainId)) {
        throw RainError.InvalidConfig(
            "$operation is EVM-only; use sendNative/sendToken on Solana chainId=$chainId"
        )
    }
}
