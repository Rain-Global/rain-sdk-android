package com.rain.sdk.turnkey

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.network.chainreader.JsonRpcClient
import com.rain.sdk.internal.solana.SolanaConverter
import com.rain.sdk.internal.solana.SolanaRpcClient
import com.rain.sdk.internal.solana.SolanaTransactionDecoder
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.internal.utils.ChainIdFormat
import com.rain.sdk.internal.utils.strippingHexPrefix
import com.rain.sdk.internal.utils.validateAndChecksumAddress
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionCategory
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import com.rain.sdk.models.UnsignedSolanaTransfer
import com.rain.sdk.utils.EthereumConverter
import com.turnkey.types.Externaldatav1Timestamp
import com.turnkey.types.TEthSendTransactionBody
import com.turnkey.types.TGetActivitiesBody
import com.turnkey.types.TGetActivityBody
import com.turnkey.types.TGetNoncesBody
import com.turnkey.types.TGetSendTransactionStatusBody
import com.turnkey.types.TGetWalletAddressBalancesBody
import com.turnkey.types.TListEthTransactionHistoryBody
import com.turnkey.types.TListSolTransactionHistoryBody
import com.turnkey.types.TSolSendTransactionBody
import com.turnkey.types.V1Activity
import com.turnkey.types.V1ActivityStatus
import com.turnkey.types.V1ActivityType
import com.turnkey.types.V1AssetBalance
import com.turnkey.types.V1HashFunction
import com.turnkey.types.V1Intent
import com.turnkey.types.V1Pagination
import com.turnkey.types.V1PayloadEncoding
import com.turnkey.types.V1Result
import com.turnkey.types.V1SignRawPayloadResult
import com.turnkey.types.V1TransactionHistoryBlock
import com.turnkey.types.V1TransactionHistoryTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The Turnkey vendor wrapper: every wallet-operation call to Turnkey's context, client and history
 * API lives here, guarded by [sessions], and leaves as a Rain type or a `RainError`; the session
 * coordinator and managed authentication make their own session and login calls.
 * [TurnkeyWalletProvider] is the port over it, the way `PortalWalletProvider` sits over
 * `PortalManager`. Built once per `TurnkeyProvider.create()`; its address caches carry the
 * coordinator's death count, so a session death or a managed-auth login replacement stales them
 * before the next read. A live session replaced by another live one outside managed
 * authentication is not counted: a bring-your-own host rebuilds the SDK per login and closes the
 * discarded provider, as TURNKEY_SUPPORT.md describes.
 */
@Suppress("TooManyFunctions") // the vendor wrapper owns every Turnkey call, as PortalManager does
internal class TurnkeyManager(
    private val turnkey: TurnkeyContextProtocol,
    private val rpcEndpoints: Map<Int, String>,
    private val solanaRpcClient: SolanaRpcClient,
    internal val sponsorGas: Boolean,
    sessionCoordinator: TurnkeySessionCoordinator,
    private val walletAddressOverride: String? = null,
    httpClient: OkHttpClient = OkHttpClient(),
    private val pollingIntervalMs: Long = POLLING_INTERVAL_MS,
    private val jsonRpcClient: JsonRpcClient = JsonRpcClient(httpClient),
) {
    /** True when the SDK was built with an RPC endpoint for [chainId], so the chain can be read. */
    internal fun hasRpcEndpoint(chainId: Int): Boolean = chainId in rpcEndpoints

    // Guards every Turnkey call: expiry check, proactive refresh, refresh-on-401, backoff. Always the
    // descriptor's coordinator, never a private one: a death or a managed re-login seen there is what
    // stales the address caches below, and the host hook fires from there.
    private val sessions: TurnkeySessionCoordinator = sessionCoordinator

    // A cached account is only as good as the session that resolved it. Each cache carries the
    // coordinator's death count it was filled under: a later count means the previous user's
    // address, so the read re-resolves, and a resolution that straddled a death is not written back.
    private class CachedAddress(val address: String, val epoch: Int)

    // Once resolved, the wallet address is stable for the provider's lifetime, so cache
    // it. Mutex (rather than synchronized) so the suspend-friendly address() doesn't block
    // a thread while it's waiting on Turnkey's refresh.
    private val cachedAddressLock = Mutex()

    /** Covers nonce acquisition through Turnkey acceptance so concurrent sends cannot reuse it. */
    private val evmSendLock = Mutex()

    @Volatile
    private var cachedAddress: CachedAddress? = null

    @Volatile
    private var cachedSolanaAddress: CachedAddress? = null

    /** The cached address if it was resolved under the current session, else null. */
    private fun CachedAddress?.stillCurrent(): String? =
        this?.takeIf { it.epoch == sessions.deathEpoch }?.address

    internal companion object {
        const val DEFAULT_NATIVE_DECIMALS = 18
        const val DEFAULT_POLLING_ATTEMPTS = 30
        const val POLLING_INTERVAL_MS = 1_000L
        const val FALLBACK_GAS_LIMIT = 21_000L

        // Turnkey returns a status id, not a Solana signature, so the signature is read back
        // from chain — which lags broadcast by a beat. Retry briefly before giving up.
        const val SOLANA_SIGNATURE_LOOKUP_ATTEMPTS = 8

        /** Both types a Solana send is recorded under; see [getSolanaTransactionsFromActivities]. */
        val SOLANA_SEND_ACTIVITY_TYPES: List<V1ActivityType> = listOf(
            V1ActivityType.ACTIVITY_TYPE_SOL_SEND_TRANSACTION,
            V1ActivityType.ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2,
        )

        /**
         * Solana send activities read back when the vendor's client dropped the one it submitted:
         * Turnkey's page maximum, since the read runs once or twice per dropped send and the list's
         * order is not documented.
         */
        const val SOLANA_SEND_LOOKUP_LIMIT = 100

        /** Reads of the activity log for a dropped send, one polling interval apart, unless the vendor's own no-result throw dropped it. */
        const val SOLANA_SEND_LOOKUP_READS = 2

        /** Reads by id of a read-back activity Turnkey is still executing, one polling interval apart, before the send ends pending on it. */
        const val SOLANA_ACTIVITY_POLL_ATTEMPTS = 10

        private const val NANOS_PER_SECOND = 1_000_000_000.0

        /** Sort key for an indexed row with no mined block yet: newest, not 1970. */
        const val PENDING_ROW_EPOCH = Double.MAX_VALUE

        /** CAIP-19 asset namespace of a chain's native coin (ETH, SOL). */
        const val NATIVE_ASSET_NAMESPACE = "slip44"

        /** Widest plausible token scale (uint256 spans 78 digits); beyond this is hostile data. */
        const val MAX_TOKEN_DECIMALS = 77
    }

    private data class ActivityDraft(
        val id: String,
        val timestampSeconds: Double,
        val from: String,
        val to: String,
        val value: String?,
        val data: String?,
        val chainId: Int,
        val sendTransactionStatusId: String?
    )

    /** CAIP-2 for [chainId]: EIP-155 for EVM, genesis-hash form for Solana clusters. */
    private fun caip2For(chainId: Int): String =
        ChainIdFormat.namespaceFor(chainId).format(chainId)

    // ---------- address ----------

    internal suspend fun getAddress(): String {
        walletAddressOverride?.takeIf { it.isNotEmpty() }?.let { return it }

        cachedAddress.stillCurrent()?.let { return it }

        return cachedAddressLock.withLock {
            cachedAddress.stillCurrent()?.let { return@withLock it }

            // Cache only while no death happened mid-flight — a result resolved across a
            // session death may belong to the previous user.
            val epoch = sessions.deathEpoch
            fun cache(address: String): String =
                address.also { if (sessions.deathEpoch == epoch) cachedAddress = CachedAddress(it, epoch) }

            resolveEthereumWalletAddress(turnkey.wallets)?.let(::cache)
                ?: run {
                    // Session-guarded: an expired session surfaces as TokenExpired here rather
                    // than as the vendor's raw refresh-wallets failure.
                    sessions.executeRead { _, _ -> turnkey.refreshWallets() }
                    resolveEthereumWalletAddress(turnkey.wallets)?.let(::cache)
                        ?: throw RainError.WalletUnavailable("No Ethereum wallet available from the wallet backend")
                }
        }
    }

    /**
     * Chain-aware address. Solana chains resolve the Turnkey Solana account (base58, ed25519);
     * every other chain shares the Ethereum account. Internal balance / send paths use this so
     * a Solana request never reads or signs with the EVM address.
     */
    internal suspend fun getWalletAddress(chainId: Int): String =
        if (SolanaChains.isSolanaChain(chainId)) getSolanaAddress() else getAddress()

    private suspend fun getSolanaAddress(): String {
        cachedSolanaAddress.stillCurrent()?.let { return it }

        return cachedAddressLock.withLock {
            cachedSolanaAddress.stillCurrent()?.let { return@withLock it }

            val epoch = sessions.deathEpoch
            fun cache(address: String): String =
                address.also { if (sessions.deathEpoch == epoch) cachedSolanaAddress = CachedAddress(it, epoch) }

            resolveSolanaWalletAddress(turnkey.wallets)?.let(::cache)
                ?: run {
                    sessions.executeRead { _, _ -> turnkey.refreshWallets() }
                    resolveSolanaWalletAddress(turnkey.wallets)?.let(::cache)
                        ?: throw RainError.WalletUnavailable("No Solana wallet available from the wallet backend")
                }
        }
    }

    private fun resolveEthereumWalletAddress(wallets: List<com.turnkey.core.models.Wallet>): String? =
        TurnkeyAccounts.ethereumAccount(wallets)?.address

    private fun resolveSolanaWalletAddress(wallets: List<com.turnkey.core.models.Wallet>): String? =
        TurnkeyAccounts.solanaAccount(wallets)?.address

    // ---------- high-level send ----------

    /** Every EVM send, transfer or raw, follows [sponsorGas]; there is no per-call override. */
    internal suspend fun sendEvmTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): String {
        // Every EVM broadcast funnels through here, so this gate also covers the paths the
        // public transfer entries never see: withdrawals, approvals, and raw sends.
        TurnkeyBroadcastChains.requireSendSupport(chainId)
        return evmSendLock.withLock {
            requireEvmChain(chainId, "sendTransaction")
            // The body is rebuilt on a refresh-and-retry so the nonce and gas quotes stay fresh.
            val statusId = sessions.executeWrite { session, client ->
                val sendBody = buildSendTransactionBody(
                    session = session,
                    client = client,
                    chainId = chainId,
                    from = from,
                    to = to,
                    data = data,
                    value = value
                )
                client.ethSendTransaction(sendBody).result.sendTransactionStatusId
            }
            pollForTransactionHash(statusId)
        }
    }

    /** Signs EIP-712 typed data with the Ethereum account; the caller has already gated the chain. */
    internal suspend fun signTypedData(walletAddress: String, typedDataJson: String): String {
        val signature = sessions.executeWrite { _, _ ->
            turnkey.signRawPayload(
                signWith = walletAddress,
                payload = typedDataJson,
                encoding = V1PayloadEncoding.PAYLOAD_ENCODING_EIP712,
                hashFunction = V1HashFunction.HASH_FUNCTION_NO_OP
            )
        }
        return ethereumSignatureHex(signature)
    }

    /** Self-paid fee quote: `eth_estimateGas` times `eth_gasPrice`, read from the chain's RPC endpoint. */
    internal suspend fun estimateTransactionFee(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): BigDecimal {
        val estimateHex = rpcCallForHex(
            chainId = chainId,
            method = "eth_estimateGas",
            params = listOf(rpcTransactionObject(from, to, data, value))
        )
        val gasPriceHex = rpcCallForHex(
            chainId = chainId,
            method = "eth_gasPrice",
            params = emptyList()
        )

        // Strict: an empty/garbage RPC payload must fail, not silently estimate a zero fee.
        val gasLimit = EthereumConverter.parseHexToBigIntegerStrict(estimateHex)
        val gasPrice = EthereumConverter.parseHexToBigIntegerStrict(gasPriceHex)
        return EthereumConverter.convertWeiToEthDecimal(gasLimit.multiply(gasPrice))
    }

    /** The native balance Turnkey reports for an EVM chain it indexes. */
    internal suspend fun evmNativeBalance(
        chainId: Int,
        walletAddress: String,
        tokenStore: TokenMetadataStore
    ): Balance {
        val balances = fetchBalances(chainId, walletAddress)
        return nativeBalance(chainId, balances, caip2For(chainId), tokenStore)
    }

    /** Native plus every non-zero token balance Turnkey reports for an EVM chain it indexes. */
    internal suspend fun evmBalances(
        chainId: Int,
        walletAddress: String,
        tokenStore: TokenMetadataStore
    ): List<Balance> {
        val caip2 = caip2For(chainId)
        val balances = fetchBalances(chainId, walletAddress)

        val output = mutableListOf(nativeBalance(chainId, balances, caip2, tokenStore))
        for (balance in balances) {
            val caip19 = balance.caip19 ?: continue
            val tokenAddress = tokenAddressFromCaip19(caip19, caip2) ?: continue
            val raw = runCatching { BigInteger(balance.balance ?: "0") }.getOrDefault(BigInteger.ZERO)
            if (raw <= BigInteger.ZERO) continue
            output += contractBalanceFrom(chainId, tokenAddress, raw, balance, tokenStore)
        }
        return output
    }

    /**
     * A Solana balance from Turnkey's asset list, or null when Turnkey lists no entry for the SPL
     * mint: Turnkey omits zero balances, and on a cluster it does not index every mint looks like a
     * zero, so the caller re-reads a missing entry from the node rather than reporting it as zero
     * with unknown decimals. Native SOL is always answered.
     */
    internal suspend fun solanaBalanceOrNull(
        chainId: Int,
        walletAddress: String,
        token: Token,
        tokenStore: TokenMetadataStore
    ): Balance? {
        val balances = fetchBalances(chainId, walletAddress)
        val caip2 = caip2For(chainId)
        return when (token) {
            is Token.Native -> nativeBalance(chainId, balances, caip2, tokenStore)
            is Token.Contract -> {
                val asset = balances.firstOrNull { tokenAddressFromCaip19(it.caip19 ?: "", caip2) == token.address }
                    ?: return null
                val raw = runCatching { BigInteger(asset.balance ?: "0") }.getOrDefault(BigInteger.ZERO)
                contractBalanceFrom(chainId, token.address, raw, asset, tokenStore)
            }
        }
    }

    /**
     * Native SOL plus every SPL token the wallet holds — Turnkey-first.
     *
     * Turnkey does not index every cluster: on devnet / testnet it errors, or answers with SOL
     * and no SPL assets at all. Discovering the wallet's token accounts from the node covers
     * both cases; on mainnet, where Turnkey does list SPL assets, its richer metadata wins. Null
     * tells the caller the node has to answer.
     */
    internal suspend fun solanaBalancesOrNull(
        chainId: Int,
        walletAddress: String,
        tokenStore: TokenMetadataStore
    ): List<Balance>? {
        val caip2 = caip2For(chainId)
        val turnkeyAssets = try {
            fetchBalances(chainId, walletAddress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RainError.TokenExpired) {
            // A dead session must surface, not be masked by the node fallback.
            throw e
        } catch (_: Exception) {
            null
        }
        val listsSplAssets = turnkeyAssets.orEmpty().any {
            tokenAddressFromCaip19(it.caip19 ?: "", caip2) != null
        }
        if (turnkeyAssets == null || !listsSplAssets) {
            return null
        }

        val output = mutableListOf(nativeBalance(chainId, turnkeyAssets, caip2, tokenStore))
        for (balance in turnkeyAssets) {
            val caip19 = balance.caip19 ?: continue
            val tokenAddress = tokenAddressFromCaip19(caip19, caip2) ?: continue
            val raw = runCatching { BigInteger(balance.balance ?: "0") }.getOrDefault(BigInteger.ZERO)
            if (raw <= BigInteger.ZERO) continue
            output += contractBalanceFrom(chainId, tokenAddress, raw, balance, tokenStore)
        }
        return output
    }

    /**
     * Builds a contract-token [Balance] from a Turnkey asset entry. EVM tokens are enriched via
     * [tokenStore] (registry / on-chain `decimals()`+`symbol()`) with Turnkey's values taking
     * precedence; Solana SPL tokens use Turnkey's metadata directly, since the Solana reader can't
     * enrich (`getDecimals`/`getSymbol` throw) and would only cache misleading defaults.
     */
    private suspend fun contractBalanceFrom(
        chainId: Int,
        tokenAddress: String,
        raw: BigInteger,
        balance: V1AssetBalance?,
        tokenStore: TokenMetadataStore
    ): Balance {
        if (SolanaChains.isSolanaChain(chainId)) {
            return Balance(
                token = Token.Contract(tokenAddress),
                chainId = chainId,
                rawAmount = raw,
                decimals = balance?.decimals?.toInt() ?: 0,
                symbol = balance?.symbol,
                name = balance?.name
            )
        }
        val info = tokenStore.tokenInfo(chainId, tokenAddress)
        return Balance(
            token = Token.Contract(tokenAddress),
            chainId = chainId,
            rawAmount = raw,
            decimals = balance?.decimals?.toInt() ?: info.decimals,
            symbol = balance?.symbol ?: info.symbol,
            name = balance?.name ?: info.name
        )
    }

    /**
     * Builds the native [Balance] from a Turnkey asset list. Turnkey reports balances in raw
     * base units, so the string is parsed directly as [BigInteger] (no decimal reconstruction).
     */
    private suspend fun nativeBalance(
        chainId: Int,
        balances: List<V1AssetBalance>,
        caip2: String,
        tokenStore: TokenMetadataStore
    ): Balance {
        val nativeAsset = balances.firstOrNull { isNativeAsset(it, caip2) }
        val raw = runCatching { BigInteger(nativeAsset?.balance ?: "0") }.getOrDefault(BigInteger.ZERO)
        val native = tokenStore.nativeCurrency(chainId)
        return Balance(
            token = Token.Native,
            chainId = chainId,
            rawAmount = raw,
            decimals = nativeAsset?.decimals?.toInt() ?: native.decimals,
            symbol = native.symbol,
            name = native.name
        )
    }

    // ---------- transactions ----------

    internal suspend fun indexedEvmTransactions(
        chainId: Int,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?
    ): List<RainTransaction> {
        val walletAddress = getWalletAddress(chainId)
        val response = sessions.executeRead { session, client ->
            client.listEthTransactionHistory(
                TListEthTransactionHistoryBody(
                    organizationId = session.organizationId,
                    address = walletAddress,
                    caip2 = caip2For(chainId),
                    paginationOptions = historyPagination(limit, offset)
                )
            )
        }
        val rows = response.transactions.map { tx ->
            rfc3339EpochSeconds(tx.block.timestamp) to indexedTransaction(
                chainId = chainId,
                walletAddress = walletAddress,
                hash = tx.transactionHash,
                block = tx.block,
                status = tx.status,
                txFrom = tx.from,
                txTo = tx.to,
                transfer = tx.transfers.firstOrNull(),
                sponsored = tx.turnkey?.sponsored
            )
        }
        return sortAndSlice(rows, limit, offset, order)
    }

    internal suspend fun indexedSolanaTransactions(
        chainId: Int,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?
    ): List<RainTransaction> {
        val walletAddress = getWalletAddress(chainId)
        val response = sessions.executeRead { session, client ->
            client.listSolTransactionHistory(
                TListSolTransactionHistoryBody(
                    organizationId = session.organizationId,
                    address = walletAddress,
                    caip2 = caip2For(chainId),
                    paginationOptions = historyPagination(limit, offset)
                )
            )
        }
        val rows = response.transactions.map { tx ->
            rfc3339EpochSeconds(tx.block.timestamp) to indexedTransaction(
                chainId = chainId,
                walletAddress = walletAddress,
                hash = tx.signature,
                block = tx.block,
                status = tx.status,
                txFrom = tx.feePayer,
                txTo = null,
                transfer = tx.transfers.firstOrNull(),
                sponsored = tx.turnkey?.sponsored
            )
        }
        return sortAndSlice(rows, limit, offset, order)
    }

    /**
     * Maps one indexed history row onto the Rain model. The row's first transfer supplies the
     * counterparty, asset and amount; any further transfers on the same transaction (a swap's
     * received leg, a batch's other recipients) are not rendered as rows. A row without transfers
     * (e.g. a plain contract call) keeps the transaction-level addresses and carries no amount.
     */
    private fun indexedTransaction(
        chainId: Int,
        walletAddress: String,
        hash: String,
        block: V1TransactionHistoryBlock,
        status: String,
        txFrom: String,
        txTo: String?,
        transfer: V1TransactionHistoryTransfer?,
        sponsored: Boolean?
    ): RainTransaction {
        val incoming = transfer?.direction.equals("IN", ignoreCase = true)
        // Turnkey sends "" (not null) when the counterparty is unknown, Solana in particular.
        val counterparty = checksummedOrNull(chainId, transfer?.counterparty?.takeIf { it.isNotBlank() })
        val asset = transfer?.asset?.caip19?.let { caip19Asset(it, caip2For(chainId)) }
        val tokenAddress = checksummedOrNull(chainId, asset?.takeIf { it.namespace != NATIVE_ASSET_NAMESPACE }?.reference)
        val transactionTo = checksummedOrNull(chainId, txTo)
        // The sender is not optional on a row, so a value that is not an address stays as sent.
        val transactionFrom = checksummedOrNull(chainId, txFrom) ?: txFrom
        // Indexer-supplied; a value outside any real token's range must not scale the amount.
        val decimals = transfer?.asset?.decimals?.takeIf { it in 0..MAX_TOKEN_DECIMALS }?.toInt()
        val displayValues = buildMap {
            transfer?.display?.crypto?.let { put("crypto", it) }
            transfer?.display?.usd?.let { put("usd", it) }
        }.takeIf { it.isNotEmpty() }

        return RainTransaction(
            hash = hash,
            uniqueId = hash,
            blockNumber = block.number,
            timestamp = normalizedTimestamp(block.timestamp),
            from = when {
                transfer == null -> transactionFrom
                incoming -> counterparty ?: transactionFrom
                // OUT is relative to the queried address: the wallet is the sender even when the
                // transaction-level `from` is a sponsor, relayer or bundler.
                else -> walletAddress
            },
            to = when {
                transfer == null -> transactionTo
                incoming -> walletAddress
                else -> counterparty ?: transactionTo
            },
            value = transfer?.amount?.let { amount ->
                decimals?.let { scaledDecimal(amount, it) }
            },
            asset = transfer?.asset?.symbol,
            tokenAddress = tokenAddress,
            rawValue = transfer?.amount,
            decimals = decimals,
            category = indexedCategory(asset),
            chainId = chainId,
            metadata = RainTransaction.Metadata(
                caip2 = caip2For(chainId),
                status = indexerStatus(status),
                sponsored = sponsored,
                type = when {
                    transfer == null -> null
                    incoming -> "transferReceived"
                    else -> "transferSent"
                },
                displayValues = displayValues
            )
        )
    }

    /**
     * An indexer-supplied EVM address in EIP-55 form, or null when the value is not an address or
     * carries a wrong mixed-case checksum, so a row never surfaces a string a host might send funds
     * to. Solana rows pass through: their addresses are base58 and carry no checksum.
     */
    private fun checksummedOrNull(chainId: Int, address: String?): String? {
        if (address == null || SolanaChains.isSolanaChain(chainId)) return address
        return try {
            validateAndChecksumAddress(address, "address")
        } catch (_: RainError.InvalidConfig) {
            null
        }
    }

    private data class Caip19Asset(val namespace: String, val reference: String)

    /** Splits a CAIP-19 under [caip2] into asset namespace and reference; null when foreign. */
    private fun caip19Asset(caip19: String, caip2: String): Caip19Asset? {
        val rest = caip19.removePrefix("$caip2/")
        if (rest == caip19) return null
        val namespace = rest.substringBefore(':', "")
        val reference = rest.substringAfter(':', "").substringBefore('/')
        if (namespace.isEmpty() || reference.isEmpty()) return null
        return Caip19Asset(namespace, reference)
    }

    private fun indexedCategory(asset: Caip19Asset?): RainTransactionCategory =
        when (asset?.namespace) {
            null, NATIVE_ASSET_NAMESPACE -> RainTransactionCategory.External
            "erc20" -> RainTransactionCategory.Erc20
            "erc721" -> RainTransactionCategory.Erc721
            "erc1155" -> RainTransactionCategory.Erc1155
            else -> RainTransactionCategory.Token
        }

    /** `EXECUTION_REVERTED` becomes `executionReverted`, matching the Privy rows' vocabulary. */
    private fun indexerStatus(status: String): String? {
        val parts = status.lowercase(Locale.ROOT).split('_').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.first() + parts.drop(1).joinToString("") { part ->
            part.replaceFirstChar { it.uppercase(Locale.ROOT) }
        }
    }

    /**
     * Same fetch window as the activity path: enough rows to honor offset, capped by the API. The
     * API is proto3-JSON and takes the limit as a string.
     */
    private fun historyPagination(limit: Int?, offset: Int?): V1Pagination =
        V1Pagination(limit = minOf(maxOf((limit ?: 10) + (offset ?: 0), 1), 100).toString())

    private fun sortAndSlice(
        rows: List<Pair<Double, RainTransaction>>,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?
    ): List<RainTransaction> {
        // The API lists newest first; the index tiebreak keeps rows sharing a block timestamp in
        // that order under DESC and reverses them under ASC, matching the timestamp semantics.
        val indexed = rows.withIndex()
        val sorted = when (order ?: RainTransactionOrder.DESC) {
            RainTransactionOrder.ASC -> indexed.sortedWith(
                compareBy<IndexedValue<Pair<Double, RainTransaction>>> { it.value.first }
                    .thenByDescending { it.index }
            )
            RainTransactionOrder.DESC -> indexed.sortedWith(
                compareByDescending<IndexedValue<Pair<Double, RainTransaction>>> { it.value.first }
                    .thenBy { it.index }
            )
        }
        return sorted
            .drop(offset ?: 0)
            .let { if (limit != null) it.take(limit) else it }
            .map { it.value.second }
    }

    /**
     * Epoch seconds for an RFC 3339 timestamp. A row whose timestamp is empty or in a form the
     * parser does not know sorts as newest rather than 1970, so it stays on the first page instead
     * of being sliced off the end.
     */
    private fun rfc3339EpochSeconds(timestamp: String?): Double {
        if (timestamp.isNullOrEmpty()) return PENDING_ROW_EPOCH
        return runCatching { Instant.parse(timestamp) }
            .recoverCatching { OffsetDateTime.parse(timestamp).toInstant() }
            .mapCatching { it.toEpochMilli() / 1000.0 }
            .getOrDefault(PENDING_ROW_EPOCH)
    }

    /** The indexer's timestamp reduced to the second-precision Zulu the activity path emits. */
    private fun normalizedTimestamp(timestamp: String?): String? {
        if (timestamp.isNullOrEmpty()) return null
        val epoch = rfc3339EpochSeconds(timestamp)
        return if (epoch == PENDING_ROW_EPOCH) timestamp else iso8601(epoch)
    }

    /** Activity-log history, used when the indexed query is unavailable. Sends only, no receives. */
    internal suspend fun getEvmTransactionsFromActivities(
        chainId: Int,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?
    ): List<RainTransaction> {
        val requestedLimit = minOf(maxOf(((limit ?: 10) + (offset ?: 0)), 1), 100)
        val activities = sessions.executeRead { session, client ->
            client.getActivities(
                TGetActivitiesBody(
                    organizationId = session.organizationId,
                    filterByType = listOf(V1ActivityType.ACTIVITY_TYPE_ETH_SEND_TRANSACTION),
                    paginationOptions = V1Pagination(limit = requestedLimit.toString())
                )
            )
        }

        val drafts = activities.activities.mapNotNull { activity ->
            val intent = activity.intent.ethSendTransactionIntent ?: return@mapNotNull null
            val txChainId = chainIdFromCaip2(intent.caip2)
            if (txChainId != chainId) return@mapNotNull null

            ActivityDraft(
                id = activity.id,
                timestampSeconds = activity.createdAt.epochSeconds(),
                from = intent.from,
                to = intent.to,
                value = intent.value,
                data = intent.data,
                chainId = txChainId,
                sendTransactionStatusId = activity.result.ethSendTransactionResult?.sendTransactionStatusId
            )
        }

        val sorted = when (order ?: RainTransactionOrder.DESC) {
            RainTransactionOrder.ASC -> drafts.sortedBy { it.timestampSeconds }
            RainTransactionOrder.DESC -> drafts.sortedByDescending { it.timestampSeconds }
        }

        val sliced = sorted
            .drop(offset ?: 0)
            .let { if (limit != null) it.take(limit) else it }

        val transactions = sliced.map { draft ->
            val txHash = runCatching {
                resolveTransactionHash(draft.sendTransactionStatusId)
            }.getOrNull()

            RainTransaction(
                hash = txHash ?: draft.id,
                uniqueId = draft.id,
                timestamp = iso8601(draft.timestampSeconds),
                from = draft.from,
                to = draft.to,
                value = scaledDecimal(draft.value, DEFAULT_NATIVE_DECIMALS),
                tokenAddress = draft.to.takeIf { !draft.data.isNullOrEmpty() && draft.data != "0x" },
                rawValue = draft.value,
                decimals = DEFAULT_NATIVE_DECIMALS,
                category = RainTransactionCategory.External,
                chainId = draft.chainId
            )
        }
        return transactions
    }

    /**
     * Solana activity-log history, used when the indexed query is unavailable. Shows only
     * transactions this wallet sent through Turnkey (no receives). A send is recorded under
     * `ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2`, the type the SDK posts, or under
     * `ACTIVITY_TYPE_SOL_SEND_TRANSACTION`, the type earlier builds posted, so both are listed and
     * either intent and result shape is read. Turnkey's Solana activity carries only the hex
     * unsigned transaction (no recipient/amount) and no on-chain signature, so `to`/`value` are
     * decoded from that blob and the row's hash is the Turnkey status id (not an
     * explorer-resolvable signature).
     */
    internal suspend fun getSolanaTransactionsFromActivities(
        chainId: Int,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?
    ): List<RainTransaction> {
        val caip2 = SolanaChains.caip2(chainId)
        val requestedLimit = minOf(maxOf(((limit ?: 10) + (offset ?: 0)), 1), 100)
        val activities = sessions.executeRead { session, client ->
            client.getActivities(
                TGetActivitiesBody(
                    organizationId = session.organizationId,
                    filterByType = SOLANA_SEND_ACTIVITY_TYPES,
                    paginationOptions = V1Pagination(limit = requestedLimit.toString())
                )
            )
        }

        val drafts = activities.activities.mapNotNull { activity ->
            val intent = activity.intent.solanaSend() ?: return@mapNotNull null
            if (intent.caip2 != caip2) return@mapNotNull null

            val transfer = SolanaTransactionDecoder.decode(intent.unsignedTransaction)
            SolanaActivityDraft(
                id = activity.id,
                timestampSeconds = activity.createdAt.epochSeconds(),
                // The account the transfer moves funds from; the intent's first signer only when the
                // blob does not decode (a V2 activity may list a co-signer first).
                from = transfer?.from ?: intent.signer,
                transfer = transfer,
                sendTransactionStatusId = activity.result.solanaSendStatusId()
            )
        }

        val sorted = when (order ?: RainTransactionOrder.DESC) {
            RainTransactionOrder.ASC -> drafts.sortedBy { it.timestampSeconds }
            RainTransactionOrder.DESC -> drafts.sortedByDescending { it.timestampSeconds }
        }
        val sliced = sorted
            .drop(offset ?: 0)
            .let { if (limit != null) it.take(limit) else it }

        // An SPL row's recipient is a token account; resolving it to the owner's wallet needs a
        // read per row, so the page is resolved concurrently and only after slicing.
        val transactions = coroutineScope {
            sliced
                .map { draft -> async { solanaTransaction(chainId, draft) } }
                .awaitAll()
        }
        return transactions
    }

    /**
     * Renders one decoded Solana activity as a [RainTransaction]. SPL rows resolve their decimals
     * and recipient wallet from the node when the transaction itself didn't carry them; both
     * reads are best-effort, so a row always lists.
     */
    private suspend fun solanaTransaction(chainId: Int, draft: SolanaActivityDraft): RainTransaction {
        val hash = draft.sendTransactionStatusId ?: draft.id
        val timestamp = iso8601(draft.timestampSeconds)

        return when (val transfer = draft.transfer) {
            is SolanaTransactionDecoder.SplTransfer -> {
                // TransferChecked carries the decimals; the bare Transfer instruction does not.
                val decimals = transfer.decimals
                    ?: transfer.mint?.let { resolveMintDecimals(chainId, it) }
                RainTransaction(
                    hash = hash,
                    uniqueId = draft.id,
                    timestamp = timestamp,
                    from = draft.from,
                    // The recipient wallet comes from the transaction's own account-creation
                    // instruction when it has one, and from the node otherwise. Falls back to the
                    // token account when the owner cannot be read — a real address the user can
                    // look up, rather than nothing.
                    to = transfer.destinationOwner
                        ?: resolveTokenAccountOwner(chainId, transfer.destination)
                        ?: transfer.destination,
                    // Without decimals the base-unit amount cannot be scaled honestly; the raw
                    // amount stays available in the metadata.
                    value = decimals?.let {
                        BigDecimal(transfer.amount).movePointLeft(it).stripTrailingZeros()
                    },
                    // SPL symbols live in off-chain metadata the SDK does not read; the mint
                    // identifies the asset.
                    asset = null,
                    tokenAddress = transfer.mint,
                    rawValue = transfer.amount.toString(),
                    decimals = decimals,
                    category = RainTransactionCategory.Token,
                    chainId = chainId,
                    // The token accounts are not reconstructible from `to`, which holds the wallet
                    // when the owner read succeeded and the token account when it didn't.
                    metadata = RainTransaction.Metadata(
                        sourceTokenAccount = transfer.source,
                        destinationTokenAccount = transfer.destination
                    )
                )
            }

            is SolanaTransactionDecoder.NativeTransfer -> RainTransaction(
                hash = hash,
                uniqueId = draft.id,
                timestamp = timestamp,
                from = draft.from,
                to = transfer.to,
                value = if (transfer.lamports.signum() == 0) {
                    BigDecimal.ZERO
                } else {
                    SolanaConverter.lamportsToSol(transfer.lamports).stripTrailingZeros()
                },
                asset = SolanaChains.NATIVE_CURRENCY.symbol,
                rawValue = transfer.lamports.toString(),
                decimals = SolanaConverter.SOL_DECIMALS,
                category = RainTransactionCategory.External,
                chainId = chainId
            )

            // Undecodable payload: report the activity rather than dropping it from history.
            null -> RainTransaction(
                hash = hash,
                uniqueId = draft.id,
                timestamp = timestamp,
                from = draft.from,
                asset = SolanaChains.NATIVE_CURRENCY.symbol,
                category = RainTransactionCategory.External,
                chainId = chainId
            )
        }
    }

    /** The wallet owning [tokenAccount], or null when it cannot be read. Never fatal. */
    private suspend fun resolveTokenAccountOwner(chainId: Int, tokenAccount: String): String? {
        val rpcUrl = rpcEndpoints[chainId] ?: return null
        return runCatching {
            solanaRpcClient.getTokenAccount(rpcUrl, tokenAccount)?.owner?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** The mint's decimals, or null when they cannot be read. Never fatal. */
    private suspend fun resolveMintDecimals(chainId: Int, mint: String): Int? {
        val rpcUrl = rpcEndpoints[chainId] ?: return null
        return runCatching { solanaRpcClient.getMintInfo(rpcUrl, mint)?.decimals }.getOrNull()
    }

    private data class SolanaActivityDraft(
        val id: String,
        val timestampSeconds: Double,
        val from: String,
        /** What the activity's unsigned transaction turned out to be; null if undecodable. */
        val transfer: SolanaTransactionDecoder.Transfer?,
        val sendTransactionStatusId: String?
    )

    // ---------- helpers ----------

    private suspend fun fetchBalances(chainId: Int, walletAddress: String): List<V1AssetBalance> {
        val response = sessions.executeRead { session, client ->
            client.getWalletAddressBalances(
                TGetWalletAddressBalancesBody(
                    organizationId = session.organizationId,
                    address = walletAddress,
                    caip2 = caip2For(chainId)
                )
            )
        }
        return response.balances.orEmpty()
    }

    private suspend fun buildSendTransactionBody(
        session: com.turnkey.core.models.Session,
        client: TurnkeyClientProtocol,
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): TEthSendTransactionBody {
        if (sponsorGas) {
            // Sponsored sends are minimal payloads. Turnkey's Gas Station builds and fee-covers
            // the outer EIP-7702 transaction, so this wallet's account nonce and self-estimated
            // fees are the wrong values to pin (the outer tx is not this account's). The quote a
            // host asks for goes to the RPC separately, with no fee field in the call, so a
            // zero-balance wallet still gets one (see estimateTransactionFee). Null fields are
            // omitted from the wire payload and auto-filled by Turnkey.
            //
            // Replay protection is the gas-station nonce, and Turnkey's one-transaction-per-
            // request guarantee holds only when the request carries it. Turnkey's other SDKs
            // attach it by default; the Kotlin client forwards what it is given, so it is
            // fetched here with one Turnkey call (not a chain RPC, so zero-balance wallets are
            // unaffected) and refetched on every refresh-and-retry rebuild. A null from Turnkey
            // is omitted, which falls back to their server-side fetch.
            val gasStationNonce = client.getNonces(
                TGetNoncesBody(
                    organizationId = session.organizationId,
                    address = from,
                    caip2 = ChainIdFormat.EIP155.format(chainId),
                    gasStationNonce = true
                )
            ).gasStationNonce
            return TEthSendTransactionBody(
                organizationId = session.organizationId,
                caip2 = ChainIdFormat.EIP155.format(chainId),
                data = data.ifEmpty { "0x" },
                from = from,
                gasStationNonce = gasStationNonce,
                sponsor = true,
                to = to,
                value = decimalStringFromHex(value)
            )
        }
        val nonceHex = rpcCallForHex(
            chainId = chainId,
            method = "eth_getTransactionCount",
            params = listOf(from, "pending")
        )
        val estimateGasHex = rpcCallForHex(
            chainId = chainId,
            method = "eth_estimateGas",
            params = listOf(rpcTransactionObject(from, to, data, value))
        )
        val gasPriceHex = rpcCallForHex(
            chainId = chainId,
            method = "eth_gasPrice",
            params = emptyList()
        )

        val nonce = decimalStringFromHex(nonceHex)
        val estimatedGas = BigInteger(
            estimateGasHex.strippingHexPrefix().ifEmpty { "0" },
            16
        ).takeIf { it > BigInteger.ZERO } ?: BigInteger.valueOf(FALLBACK_GAS_LIMIT)
        val bufferedGas = estimatedGas.add(estimatedGas.divide(BigInteger.valueOf(5)))
        val gasLimit = (if (bufferedGas == BigInteger.ZERO) estimatedGas else bufferedGas).toString()
        val gasPrice = decimalStringFromHex(gasPriceHex)

        return TEthSendTransactionBody(
            organizationId = session.organizationId,
            caip2 = ChainIdFormat.EIP155.format(chainId),
            data = data.ifEmpty { "0x" },
            from = from,
            gasLimit = gasLimit,
            maxFeePerGas = gasPrice,
            maxPriorityFeePerGas = gasPrice,
            nonce = nonce,
            sponsor = false,
            to = to,
            value = decimalStringFromHex(value)
        )
    }

    private suspend fun resolveTransactionHash(sendTransactionStatusId: String?): String? {
        val statusId = sendTransactionStatusId ?: return null
        val status = sessions.executeRead { session, client ->
            client.getSendTransactionStatus(
                TGetSendTransactionStatusBody(
                    organizationId = session.organizationId,
                    sendTransactionStatusId = statusId
                )
            )
        }
        return status.eth?.txHash
    }

    @Suppress("TooGenericExceptionCaught") // a failed status read after acceptance is pending, not a failed send
    private suspend fun pollForTransactionHash(sendTransactionStatusId: String): String {
        for (attempt in 0 until DEFAULT_POLLING_ATTEMPTS) {
            // Session-guarded per poll: a session expiring mid-poll refreshes instead of
            // aborting a transaction that was already submitted. Turnkey has accepted it by now,
            // so a failed status read — session death, network, anything — is not a failed send:
            // the status id must survive, or the host's natural next move is a duplicate send.
            val status = try {
                sessions.executeRead { session, client ->
                    client.getSendTransactionStatus(
                        TGetSendTransactionStatusBody(
                            organizationId = session.organizationId,
                            sendTransactionStatusId = sendTransactionStatusId
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Rain SDK: wallet backend status read failed after submission; reporting pending")
                throw RainError.TransactionPending(sendTransactionStatusId)
            }

            // Classified before the hash is read: an included transaction that reverted on chain
            // carries both its hash and the decoded revert, and the revert wins.
            TurnkeySendFailures.sendFailure(status, "Wallet backend transaction submission failed")?.let { throw it }

            val txHash = status.eth?.txHash
            if (!txHash.isNullOrEmpty()) return txHash

            if (attempt + 1 < DEFAULT_POLLING_ATTEMPTS) {
                delay(pollingIntervalMs)
            }
        }

        // A poll timeout is not a failure: Turnkey accepted the submission and the transaction
        // may still confirm. Carrying the status id lets the host resume polling instead of
        // resending, which would risk a duplicate transfer.
        throw RainError.TransactionPending(sendTransactionStatusId)
    }

    // ---------- Solana send ----------

    /**
     * Signs and broadcasts a composed transfer through Turnkey, then resolves the signature:
     * from the send-status response (Turnkey SDK 2.0 populates it once Included), else recovered
     * from chain and verified as this wallet's own successful transaction. Anything short of that
     * is [RainError.TransactionPending] — never the status id posing as a signature.
     *
     * The send is a `sol_send_transaction` activity through the vendor's client. Once Turnkey has
     * accepted it, this call reports a failure only when the send has settled, with one residue named
     * below; an unknown fate is [RainError.TransactionPending], because a host that retries a reported
     * failure would send the money twice. The vendor's client works against that: it returns the activity only once it
     * completed within its poll and otherwise throws and drops the activity id (an activity still
     * pending, a poll read that failed, a lost answer, a body that did not decode), so
     * [solanaSendOutcome] keeps every failure that can follow acceptance inside the write, the
     * activity is read back by its unsigned transaction outside it and read again by id while
     * Turnkey is still executing it ([solanaSendActivity]), and [solanaSendStatusId] classifies it.
     * The failures this call does report after acceptance
     * are settled ones: an activity Turnkey itself failed or rejected before it broadcast anything,
     * or a send status Turnkey reports as failed; both leave the money where it was. One residue
     * stays: when the activity cannot be read back at all, the host sees [RainError.ProviderError]
     * with the fate unknown, built without the vendor's exception so nothing retries the send.
     */
    internal suspend fun submitSolanaTransaction(
        chainId: Int,
        from: String,
        unsigned: UnsignedSolanaTransfer
    ): String {
        // Every Solana broadcast funnels through here; gate it like the EVM funnel so no
        // future caller can reach solSendTransaction on an unsupported cluster.
        TurnkeyBroadcastChains.requireSendSupport(chainId)
        val rpcUrl = rpcEndpoints[chainId]
            ?: throw RainError.InvalidConfig("No RPC endpoint configured for chainId=$chainId")

        // Baseline for the signature recovery below: the wallet's newest signature before this send.
        // Kept as a Result: without a baseline, recovery cannot tell this send from older history.
        val baseline = runCatching { solanaRpcClient.getLatestSignature(rpcUrl, from) }

        // One timestamp per send, pinned on the body: should the coordinator re-run the block after
        // a refresh, the vendor posts the identical envelope, which Turnkey answers with the same
        // activity rather than a second one.
        val timestampMs = System.currentTimeMillis().toString()
        val outcome = sessions.executeWrite { session, client ->
            solanaSendOutcome(
                client,
                TSolSendTransactionBody(
                    timestampMs = timestampMs,
                    organizationId = session.organizationId,
                    unsignedTransaction = unsigned.transactionHex,
                    // The one customer signer, in transaction order as a sponsored send requires.
                    signWiths = listOf(from),
                    // Sponsored: Turnkey rebuilds the transaction with its own sponsor as the fee
                    // payer and signs it with this wallet and the sponsor, so signature recovery
                    // below matches on signers, not on the fee payer.
                    sponsor = sponsorGas,
                    caip2 = SolanaChains.caip2(chainId),
                    recentBlockhash = unsigned.recentBlockhash
                )
            )
        }
        val activity = when (outcome) {
            is SolanaSendOutcome.Settled -> outcome.activity
            is SolanaSendOutcome.Dropped -> solanaSendActivity(outcome)
        }
        val statusId = solanaSendStatusId(activity)
        pollForSolanaCompletion(statusId)?.let { return it }

        // No baseline: any signature found now could be older history or someone else's
        // deposit, so skip recovery. The send was accepted; the host resumes from the status id.
        val priorSignature = baseline.getOrElse { throw RainError.TransactionPending(statusId) }

        // getSignaturesForAddress lags broadcast slightly, so retry briefly. Only a signature
        // newer than the baseline can belong to this send, and it still has to be verified as
        // this wallet's own successful transaction: a deposit from elsewhere also lands here.
        for (attempt in 0 until SOLANA_SIGNATURE_LOOKUP_ATTEMPTS) {
            findOwnConfirmedSignature(rpcUrl, from, priorSignature)?.let { return it }
            if (attempt + 1 < SOLANA_SIGNATURE_LOOKUP_ATTEMPTS) delay(pollingIntervalMs)
        }
        // Same contract as the EVM path: a timeout is pending, not success and not failure.
        throw RainError.TransactionPending(statusId)
    }

    /** What the vendor's client made of a Solana send: the activity, or a failure that may follow acceptance. */
    private sealed interface SolanaSendOutcome {
        class Settled(val activity: V1Activity) : SolanaSendOutcome
        class Dropped(val body: TSolSendTransactionBody, val failure: Exception) : SolanaSendOutcome
    }

    /**
     * The vendor's `solSendTransaction`, with its failures sorted by what they say about the send. A
     * cancellation, a [RainError] and a refusal of the submit itself (an answer below 500, given before
     * anything was executed; a 401 there is the one the coordinator refreshes and retries, safely)
     * leave as themselves. Everything else arrived after Turnkey may have accepted the activity: the
     * vendor's bare throw on an activity not completed within its poll, a 5xx answer to the submit (a
     * gateway can fail after the activity was created), a poll read that failed (a 401 there must never
     * re-run this block, which would be a second send), a transport failure (the poll raises the same
     * types as the submit), a lost answer, a body that did not decode. Those come back as
     * [SolanaSendOutcome.Dropped], for the caller to read the activity back outside the write.
     */
    @Suppress("TooGenericExceptionCaught") // every failure the vendor's client can raise after acceptance is sorted here
    private suspend fun solanaSendOutcome(client: TurnkeyClientProtocol, body: TSolSendTransactionBody): SolanaSendOutcome = try {
        SolanaSendOutcome.Settled(client.solSendTransaction(body))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (TurnkeySendFailures.leavesTheSendAsItself(e)) throw e
        SolanaSendOutcome.Dropped(body, e)
    }

    /**
     * The activity behind a dropped send: read back from the activity log by its unsigned transaction,
     * then, while Turnkey is still executing it, read again by id at the SDK's own interval, since the
     * vendor's client gave it only about four seconds. Throws [TurnkeySendFailures.droppedActivity]
     * when the log does not have it.
     */
    private suspend fun solanaSendActivity(outcome: SolanaSendOutcome.Dropped): V1Activity {
        val found = readBackSolanaSendActivity(outcome.body, outcome.failure)
            ?: throw TurnkeySendFailures.droppedActivity(outcome.failure)
        return awaitSolanaSendActivity(found, outcome.body.organizationId)
    }

    /**
     * [activity] once Turnkey has finished executing it, or as last read when it is still being
     * executed after [SOLANA_ACTIVITY_POLL_ATTEMPTS] reads or a read failed; the caller classifies
     * whatever comes back. Only CREATED and PENDING are waited on: an activity that needs consensus or
     * an authenticator waits for a person, and a settled one is the caller's.
     */
    @Suppress("TooGenericExceptionCaught") // a failed read ends the wait, not the send; the last activity read is classified
    private suspend fun awaitSolanaSendActivity(activity: V1Activity, organizationId: String): V1Activity {
        var latest = activity
        var reads = 0
        while (latest.status.stillExecuting() && reads < SOLANA_ACTIVITY_POLL_ATTEMPTS) {
            delay(pollingIntervalMs)
            reads++
            val id = latest.id
            latest = try {
                sessions.executeRead { _, client ->
                    client.getActivity(TGetActivityBody(organizationId = organizationId, activityId = id)).activity
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Rain SDK: reading the Solana send activity again failed: %s", e.vendorMessage())
                break
            }
        }
        return latest
    }

    private fun V1ActivityStatus.stillExecuting(): Boolean =
        this == V1ActivityStatus.ACTIVITY_STATUS_CREATED || this == V1ActivityStatus.ACTIVITY_STATUS_PENDING

    /**
     * The newest Solana send activity whose unsigned transaction is [body]'s, read from the activity
     * log under the coordinator's read rules (a dead session is refreshed, a transient failure is
     * retried), or null when none matches or the read fails: the caller then surfaces the vendor's
     * failure with the fate unknown, never a lookup problem in its place. After the vendor's own
     * no-result throw one read is enough, since the vendor had the activity in hand; after any other
     * failure (a 5xx answer to the submit, a failed poll read, a lost or undecodable answer) the activity
     * may not list yet, so up to [SOLANA_SEND_LOOKUP_READS] reads run, one polling interval apart. The
     * unsigned transaction is unique to a send (it carries
     * the blockhash and the amount); the newest match by `createdAt` wins should one ever repeat, in
     * whatever order Turnkey returns the page.
     */
    @Suppress("TooGenericExceptionCaught") // a failed lookup falls back to the vendor's failure, never hides it
    private suspend fun readBackSolanaSendActivity(body: TSolSendTransactionBody, failure: Exception): V1Activity? = try {
        val reads = if (TurnkeySendFailures.isMissingResultFailure(failure)) 1 else SOLANA_SEND_LOOKUP_READS
        var match: V1Activity? = null
        var read = 0
        while (match == null && read < reads) {
            if (read > 0) delay(pollingIntervalMs)
            read++
            match = listSolanaSendActivities(body.organizationId)
                .filter { it.intent.solSendTransactionIntentV2?.unsignedTransaction.equals(body.unsignedTransaction, ignoreCase = true) }
                .maxByOrNull { it.createdAt.epochSeconds() }
        }
        if (match == null) Timber.w("Rain SDK: wallet backend dropped the Solana send activity and none matched the unsigned transaction")
        match
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(
            "Rain SDK: wallet backend dropped the Solana send activity and reading it back failed: %s: %s",
            e.javaClass.simpleName,
            e.vendorMessage()
        )
        null
    }

    /** One page of V2 Solana send activities, [SOLANA_SEND_LOOKUP_LIMIT] at most, in the order Turnkey lists them. */
    private suspend fun listSolanaSendActivities(organizationId: String): List<V1Activity> =
        sessions.executeRead { _, client ->
            client.getActivities(
                TGetActivitiesBody(
                    organizationId = organizationId,
                    filterByType = listOf(V1ActivityType.ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2),
                    paginationOptions = V1Pagination(limit = SOLANA_SEND_LOOKUP_LIMIT.toString())
                )
            ).activities
        }

    /** Seconds with the nanosecond fraction, the key the history rows sort by as well. */
    private fun Externaldatav1Timestamp.epochSeconds(): Double =
        (seconds.toDoubleOrNull() ?: 0.0) + (nanos.toDoubleOrNull() ?: 0.0) / NANOS_PER_SECOND

    /**
     * The send status id of a Solana send activity, the handle the broadcast is tracked by. A
     * completed activity carries it under the V2 result, or under the V1 result should Turnkey
     * record the activity that way; both are read. Without one, an activity Turnkey failed or
     * rejected never broadcast anything, so it is the [RainError.ProviderError] that
     * [TurnkeySendFailures.activityFailure] classifies and the host may retry. Anything else, an
     * activity still pending after the vendor's poll and the SDK's own wait, or one completed in a
     * shape this build cannot read, was accepted and may still broadcast, so it is
     * [RainError.TransactionPending] carrying the activity id: not a failure, and not to be resent.
     * The id matches `uniqueId` on the activity-log history row; no call takes it.
     */
    private fun solanaSendStatusId(activity: V1Activity): String {
        activity.result.solanaSendStatusId()?.let { return it }
        val verb = activity.status.name.removePrefix("ACTIVITY_STATUS_").lowercase()
        TurnkeySendFailures.activityFailure(activity, "Wallet backend $verb the Solana send")?.let { throw it }
        throw RainError.TransactionPending(activity.id)
    }

    /** The status id under either result shape, the V2 one first. */
    private fun V1Result.solanaSendStatusId(): String? =
        solSendTransactionResultV2?.sendTransactionStatusId ?: solSendTransactionResult?.sendTransactionStatusId

    /** A Solana send intent under either shape; the V2 shape's first signer is the sender. */
    private fun V1Intent.solanaSend(): SolanaSendIntent? =
        solSendTransactionIntent?.let { SolanaSendIntent(it.caip2, it.signWith, it.unsignedTransaction) }
            ?: solSendTransactionIntentV2?.let { v2 ->
                v2.signWiths.firstOrNull()?.let { signer -> SolanaSendIntent(v2.caip2, signer, v2.unsignedTransaction) }
            }

    /** The fields of a Solana send intent the history reads, common to the V1 and V2 shapes. */
    private data class SolanaSendIntent(val caip2: String, val signer: String, val unsignedTransaction: String)

    /**
     * Newest post-baseline signature that is a confirmed transaction signed by [from] with no
     * on-chain error, or null when none of them is. Matching on the signers rather than the fee
     * payer keeps sponsored sends recoverable: the sponsor may pay, but this wallet always signs
     * its own send, and a deposit from elsewhere never carries its signature.
     */
    private suspend fun findOwnConfirmedSignature(
        rpcUrl: String,
        from: String,
        priorSignature: String?
    ): String? {
        val candidates = solanaRpcClient.getSignaturesSince(rpcUrl, from, until = priorSignature)
        for (signature in candidates) {
            if (signature == priorSignature) continue
            val record = solanaRpcClient.getTransaction(rpcUrl, signature) ?: continue
            if (record.signedBy(from) && record.succeeded) return signature
        }
        return null
    }

    /**
     * Polls Turnkey for the terminal status of a Solana submission. Keeps
     * polling through `Broadcasted` until the signature appears or a terminal state
     * (Included/Confirmed/Finalized). Returns `solana.signature` (populated once Included on
     * Turnkey SDK 2.0), null at a terminal status without it or on timeout (caller then recovers
     * the signature from chain), and throws on explicit failure.
     */
    @Suppress("TooGenericExceptionCaught") // a failed status read ends the poll, not the send; recovered from chain
    private suspend fun pollForSolanaCompletion(sendTransactionStatusId: String): String? {
        for (attempt in 0 until DEFAULT_POLLING_ATTEMPTS) {
            // A failed status read (session death, network) stops the polling, not the submitted
            // transaction: returning null lets the caller recover the signature from chain.
            val status = try {
                sessions.executeRead { session, client ->
                    client.getSendTransactionStatus(
                        TGetSendTransactionStatusBody(
                            organizationId = session.organizationId,
                            sendTransactionStatusId = sendTransactionStatusId
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Rain SDK: wallet backend Solana status read failed after submission")
                return null
            }

            TurnkeySendFailures.sendFailure(status, "Wallet backend Solana transaction submission failed")?.let { throw it }
            val normalized = status.txStatus.uppercase()

            // Turnkey SDK 2.0 populates solana.signature once the tx is Included.
            status.solana?.signature?.takeIf { it.isNotEmpty() }?.let { return it }

            // Terminal status but no signature yet: stop; the caller recovers it from chain.
            val terminal = normalized.contains("INCLUDED") ||
                normalized.contains("CONFIRMED") ||
                normalized.contains("FINALIZED") ||
                normalized.contains("MINED")
            if (terminal) return null

            if (attempt + 1 < DEFAULT_POLLING_ATTEMPTS) {
                delay(pollingIntervalMs)
            }
        }
        return null
    }

    // ---------- RPC ----------

    /**
     * Issues a JSON-RPC call against the chain's configured RPC URL, returning the hex
     * `result` field. Promotes [RainError.InvalidRpcUrl] to [RainError.InvalidConfig] so
     * the caller gets the chain ID alongside the bad URL.
     */
    private suspend fun rpcCallForHex(
        chainId: Int,
        method: String,
        params: List<Any>
    ): String {
        val rpcUrl = rpcEndpoints[chainId]
            ?: throw RainError.InvalidConfig("No RPC endpoint configured for chainId=$chainId")
        return try {
            jsonRpcClient.callForHexResult(rpcUrl, method, params)
        } catch (e: RainError.InvalidRpcUrl) {
            throw RainError.InvalidConfig("Invalid RPC URL for chainId=$chainId: $rpcUrl")
        }
    }

    private fun rpcTransactionObject(
        from: String,
        to: String,
        data: String,
        value: String
    ): Map<String, String> {
        // Native transfers carry no calldata. Omit the "data" field entirely (rather than
        // sending "0x" or "") so eth_estimateGas matches the request shape RPC nodes expect
        // for a value-only transfer.
        val tx = mutableMapOf("from" to from, "to" to to, "value" to value)
        if (data.isNotEmpty() && data != "0x") tx["data"] = data
        return tx
    }

    // ---------- formatting ----------

    private fun isNativeAsset(balance: V1AssetBalance, caip2: String): Boolean {
        val caip19 = balance.caip19 ?: return false
        return caip19.startsWith("$caip2/slip44:")
    }

    private fun tokenAddressFromCaip19(caip19: String, caip2: String): String? {
        // EVM tokens use the `erc20` asset namespace; Solana SPL tokens use `token`.
        val prefixes = listOf("$caip2/erc20:", "$caip2/token:")
        if (prefixes.none { caip19.startsWith(it) }) return null
        return caip19.substringAfterLast(":", "").takeIf { it.isNotEmpty() }
    }

    private fun chainIdFromCaip2(caip2: String): Int {
        return caip2.substringAfterLast(":", "").toIntOrNull() ?: 0
    }

    /**
     * Scales a base-unit amount down without going through [Double] — a wei value above 2^53
     * would otherwise be inexact. Null when the amount is not a number.
     */
    private fun scaledDecimal(balance: String?, decimals: Int): BigDecimal? {
        if (balance.isNullOrEmpty()) return BigDecimal.ZERO
        return runCatching { BigDecimal(balance).movePointLeft(decimals).stripTrailingZeros() }
            .getOrNull()
    }

    private fun decimalStringFromHex(hex: String): String {
        val cleaned = hex.strippingHexPrefix().ifEmpty { "0" }
        return BigInteger(cleaned, 16).toString()
    }

    private fun ethereumSignatureHex(signature: V1SignRawPayloadResult): String {
        val r = normalizeHexComponent(signature.r, 64)
        val s = normalizeHexComponent(signature.s, 64)
        val v = String.format(Locale.ROOT, "%02x", normalizedRecoveryId(signature.v))
        return "0x$r$s$v"
    }

    private fun normalizeHexComponent(value: String, length: Int): String {
        // Strip every "0x" occurrence (not just a leading prefix) — Turnkey occasionally
        // returns components like "0x...0x..." that need full normalization before padding.
        val clean = value.lowercase().replace("0x", "")
        return if (clean.length >= length) {
            clean.takeLast(length)
        } else {
            clean.padStart(length, '0')
        }
    }

    private fun normalizedRecoveryId(value: String): Int {
        val clean = value.lowercase()
        val parsed = when {
            clean.startsWith("0x") -> clean.removePrefix("0x").toIntOrNull(16)
            else -> clean.toIntOrNull() ?: clean.toIntOrNull(16)
        } ?: return 27
        return if (parsed >= 27) parsed else parsed + 27
    }

    /** Second-precision UTC Zulu ISO-8601, e.g. `2026-07-26T12:34:56Z`. */
    private fun iso8601(seconds: Double): String =
        Instant.ofEpochMilli((seconds * 1000).toLong())
            .truncatedTo(ChronoUnit.SECONDS)
            .toString()
}
