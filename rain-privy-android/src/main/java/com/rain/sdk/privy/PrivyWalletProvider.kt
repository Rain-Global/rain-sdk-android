package com.rain.sdk.privy

import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.abi.Erc20Abi
import com.rain.sdk.internal.solana.SolanaSupport
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.internal.utils.suspendRunCatching
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionCategory
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import com.rain.sdk.models.UnsignedSolanaTransfer
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.WalletProvider
import com.rain.sdk.utils.EthereumConverter
import io.privy.wallet.solana.SolanaCluster
import io.privy.wallet.transactions.GetTransactionsParams
import io.privy.wallet.transactions.TransactionChain
import io.privy.wallet.transactions.TransactionStatus
import io.privy.wallet.transactions.TransactionType
import io.privy.wallet.transactions.TransactionsPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.generated.Uint256
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import io.privy.wallet.transactions.Transaction as PrivyTransaction
import org.web3j.abi.datatypes.Function as Web3jFunction

/**
 * Privy-backed [WalletProvider].
 *
 * Splits responsibilities the way the SDK's other adapters do: custody (signing, broadcasting) goes
 * through Privy's EIP-1193 embedded wallet via [PrivyManager]; balance and fee reads run through
 * [PrivyRpcClient] against Rain's configured RPC, with metadata resolved by [tokenStore].
 *
 * Transaction history comes from Privy's indexer via the wallet's `getTransactions` (TEE wallets
 * only, and only on chains Privy indexes — see [privyChain]); unsupported chains return an empty
 * result, matching the pre-indexer behavior.
 */
internal class PrivyWalletProvider(
    private val manager: PrivyManager,
    private val rpcEndpoints: Map<Int, String>,
    private val tokenStore: TokenMetadataStore,
    private val walletAddressOverride: String? = null,
    private val rpcClient: PrivyRpcClient = PrivyRpcClient(),
    private val solanaSupport: SolanaSupport = SolanaSupport(rpcEndpoints),
    sessionCoordinator: PrivySessionCoordinator? = null,
) : WalletProvider {

    override val id: ProviderId get() = ProviderId.PRIVY

    /** The same set as the descriptor's `PrivyProvider.CAPABILITIES`, whose KDoc says why. */
    override val capabilities: Set<Capability>
        get() = PrivyProvider.CAPABILITIES

    @Volatile
    private var cachedAddress: String? = null

    @Volatile
    private var cachedSolanaAddress: String? = null
    private val cachedAddressLock = Mutex()

    // Bumped on every eviction so a resolution that was already in flight when the session
    // died cannot write the dead session's address back into the cache.
    private val evictionEpoch = AtomicInteger(0)

    init {
        // Cached accounts must not survive the session that resolved them: a later login as a
        // different user would otherwise keep signing with the previous user's addresses.
        sessionCoordinator?.onSessionDeath {
            evictionEpoch.incrementAndGet()
            cachedAddress = null
            cachedSolanaAddress = null
        }
    }

    // ---------- address ----------

    override suspend fun getWalletAddress(): String {
        walletAddressOverride?.takeIf { it.isNotEmpty() }?.let { return it }
        cachedAddress?.let { return it }
        // Lock so concurrent first-access callers resolve the address once (parity with Turnkey),
        // rather than each firing a redundant Privy lookup.
        return cachedAddressLock.withLock {
            cachedAddress?.let { return@withLock it }
            val epoch = evictionEpoch.get()
            manager.getAddress(walletAddressOverride).also {
                // Cache only while no eviction happened mid-flight — this result may belong
                // to a session that just died.
                if (evictionEpoch.get() == epoch) cachedAddress = it
            }
        }
    }

    override suspend fun getWalletAddress(chainId: Int): String {
        if (!solanaSupport.isSolanaChain(chainId)) return getWalletAddress()
        cachedSolanaAddress?.let { return it }
        return cachedAddressLock.withLock {
            cachedSolanaAddress?.let { return@withLock it }
            val epoch = evictionEpoch.get()
            manager.getSolanaAddress().also {
                if (evictionEpoch.get() == epoch) cachedSolanaAddress = it
            }
        }
    }

    // ---------- high-level send ----------

    override suspend fun sendNativeToken(
        chainId: Int,
        toAddress: String,
        amountInEth: BigDecimal,
    ): String {
        if (solanaSupport.isSolanaChain(chainId)) {
            val from = getWalletAddress(chainId)
            val unsigned =
                solanaSupport.composeNativeTransfer(chainId, from, toAddress, amountInEth)
            return manager.signAndSendSolanaTransaction(
                unsigned.transaction,
                solanaCluster(chainId),
                rpcUrlFor(chainId)
            )
        }
        val from = getWalletAddress()
        val decimals = tokenStore.nativeCurrency(chainId).decimals
        val valueHex = EthereumConverter.convertEthToWeiHex(amountInEth, decimals)
        return sendTransaction(chainId = chainId, from = from, to = toAddress, data = "0x", value = valueHex)
    }

    /**
     * On Solana [contractAddress] is the mint and [decimals] is deliberately unread — the composer
     * reads the mint's own scale from the chain, which `TransferChecked` then enforces.
     */
    override suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        toAddress: String,
        amount: BigDecimal,
        decimals: Int,
    ): String {
        if (solanaSupport.isSolanaChain(chainId)) {
            val from = getWalletAddress(chainId)
            val unsigned =
                solanaSupport.composeSplTransfer(chainId, from, contractAddress, toAddress, amount)
            return manager.signAndSendSolanaTransaction(
                unsigned.transaction,
                solanaCluster(chainId),
                rpcUrlFor(chainId)
            )
        }
        val from = getWalletAddress()
        val data = Erc20Abi.encodeTransfer(toAddress, amount, decimals)
        return sendTransaction(chainId = chainId, from = from, to = contractAddress, data = data, value = "0x0")
    }

    // ---------- low-level send / sign / fee ----------

    /**
     * The embedded Solana wallet's leg of `WalletProvider.sendSolanaTransaction` (the Turnkey
     * adapter has the other). Core has already composed the transaction, a collateral withdrawal
     * for example, and run every preflight and simulation. Privy only signs with the wallet's
     * ed25519 key and broadcasts; the fee payer is always this wallet, as *Capabilities and
     * chains* in rain-privy-android/README.md says.
     */
    override suspend fun sendSolanaTransaction(
        chainId: Int,
        unsigned: UnsignedSolanaTransfer
    ): String = manager.signAndSendSolanaTransaction(
        unsigned.transaction,
        solanaCluster(chainId),
        rpcUrlFor(chainId)
    )

    @Suppress("ThrowsCount") // the pass-through-or-map catch around the simulation
    override suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String,
    ): String {
        requireEvmChain(chainId, "sendTransaction")
        val rpcUrl = rpcUrlFor(chainId)

        // Simulate the transaction first via eth_call to catch failures
        // (e.g. insufficient funds, contract reverts) — no balance fetch needed,
        // the node validates it for free. Mirrors the Portal adapter.
        try {
            rpcClient.callForHexResult(
                rpcUrl,
                "eth_call",
                listOf(rpcTransactionObject(from, to, data, value), "latest"),
                purpose = RpcCallPurpose.SIMULATION
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "Rain SDK: Transaction simulation failed (eth_call)")
            // Errors the RPC client already classified surface as themselves: a NetworkError
            // here is retryable, and wrapping it as a simulation failure would tell the host
            // (via WithdrawalRevertedByNetwork) not to retry. Anything else is a simulation failure.
            if (e is RainError) throw e
            throw RainError.TransactionSimulationFailed(e)
        }

        val transactionJson = JSONObject().apply {
            put("from", from)
            put("to", to)
            put("value", value.ifEmpty { "0x0" })
            if (data.isNotEmpty() && data != "0x") put("data", data)
            put("chainId", "0x${chainId.toString(16)}")
        }.toString()
        return manager.sendTransaction(walletAddress = from, rpcUrl = rpcUrl, transactionJson = transactionJson)
    }

    override suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String,
    ): String {
        requireEvmChain(chainId, "signTypedData")
        return manager.signTypedData(walletAddress = walletAddress, typedDataJson = typedDataJson)
    }

    override suspend fun estimateTransactionFee(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String,
    ): BigDecimal {
        requireEvmChain(chainId, "estimateTransactionFee")
        val rpcUrl = rpcUrlFor(chainId)
        val gasLimitHex = rpcClient.callForHexResult(
            rpcUrl,
            "eth_estimateGas",
            listOf(rpcTransactionObject(from, to, data, value)),
            purpose = RpcCallPurpose.SIMULATION
        )
        val gasPriceHex = rpcClient.callForHexResult(rpcUrl, "eth_gasPrice", emptyList())
        val gasLimit = EthereumConverter.parseHexToBigIntegerStrict(gasLimitHex)
        val gasPrice = EthereumConverter.parseHexToBigIntegerStrict(gasPriceHex)
        return EthereumConverter.convertWeiToEthDecimal(gasLimit.multiply(gasPrice))
    }

    // ---------- balances ----------

    override suspend fun getBalance(chainId: Int, token: Token): Balance {
        if (solanaSupport.isSolanaChain(chainId)) {
            // Registry-only naming, as on Turnkey: an SPL mint has no on-chain symbol, and the
            // token store's enrichment path is EVM-only.
            val registered = (token as? Token.Contract)?.let { contract ->
                tokenStore.registeredTokens(chainId)
                    .firstOrNull { it.address.equals(contract.address, ignoreCase = true) }
            }
            return solanaSupport.getBalance(chainId, getWalletAddress(chainId), token, registered)
        }
        val walletAddress = getWalletAddress()
        return when (token) {
            is Token.Native -> fetchNativeBalance(chainId, walletAddress)
            is Token.Contract -> fetchContractBalance(chainId, walletAddress, token.address)
        }
    }

    override suspend fun getBalances(chainId: Int): List<Balance> {
        if (solanaSupport.isSolanaChain(chainId)) {
            return solanaSupport.getBalances(
                chainId,
                getWalletAddress(chainId),
                tokenStore.registeredTokens(chainId)
            )
        }
        return evmBalances(chainId)
    }

    private suspend fun evmBalances(chainId: Int): List<Balance> = supervisorScope {
        val walletAddress = getWalletAddress()
        // Native is essential: its failure propagates (it is awaited directly below). Per-token
        // reads are best-effort — a single bad/failing contract must not drop the whole list, so
        // each is wrapped and skipped on failure. A semaphore caps concurrent RPC calls; without
        // it a large token registry would fan out one connection per token at once.
        val nativeDeferred = async { fetchNativeBalance(chainId, walletAddress) }
        val gate = Semaphore(MAX_CONCURRENT_BALANCE_READS)
        val contractDeferred = tokenStore.registeredTokens(chainId).map { info ->
            async {
                suspendRunCatching { gate.withPermit { fetchContractBalance(chainId, walletAddress, info.address) } }
                    .onFailure { e ->
                        Timber.w(
                            e,
                            "Rain SDK: Privy balance read failed for token=${info.address} chainId=$chainId; skipping"
                        )
                    }
                    .getOrNull()
            }
        }
        val output = mutableListOf(nativeDeferred.await())
        output += contractDeferred.awaitAll().filterNotNull().filter { it.rawAmount > BigInteger.ZERO }
        output
    }

    private suspend fun fetchNativeBalance(chainId: Int, walletAddress: String): Balance {
        val rpcUrl = rpcUrlFor(chainId)
        val hex = rpcClient.callForHexResult(rpcUrl, "eth_getBalance", listOf(walletAddress, "latest"))
        val native = tokenStore.nativeCurrency(chainId)
        return Balance(
            token = Token.Native,
            chainId = chainId,
            rawAmount = EthereumConverter.parseHexToBigIntegerStrict(hex),
            decimals = native.decimals,
            symbol = native.symbol,
            name = native.name
        )
    }

    private suspend fun fetchContractBalance(
        chainId: Int,
        walletAddress: String,
        address: String,
    ): Balance {
        val rpcUrl = rpcUrlFor(chainId)
        val info = tokenStore.tokenInfo(chainId, address)
        val function = Web3jFunction(
            "balanceOf",
            listOf(Address(walletAddress)),
            listOf(object : TypeReference<Uint256>() {})
        )
        val callObject = mapOf("to" to address, "data" to FunctionEncoder.encode(function))
        val hex = rpcClient.callForHexResult(rpcUrl, "eth_call", listOf(callObject, "latest"))
        return Balance(
            token = Token.Contract(address),
            chainId = chainId,
            rawAmount = EthereumConverter.parseHexToBigIntegerStrict(hex),
            decimals = info.decimals,
            symbol = info.symbol,
            name = info.name
        )
    }

    // ---------- transactions ----------

    /**
     * Wallet history from Privy's transaction indexer.
     *
     * Privy's endpoint requires exactly one of an `asset` or `token` filter per request, so full
     * history is assembled from one native-asset query plus token-address queries over the
     * registered tokens (chunked to the server's 10-filter cap). Every query is essential: a
     * failure in the native query or any token chunk fails the whole call rather than silently
     * returning partial history (Privy vendor errors classify to RainError in
     * [PrivyManager.getTransactions]). Privy paginates by cursor while the SDK contract is
     * limit/offset, so each query collects pages until `offset + limit` rows are gathered (or
     * history is exhausted); the merged rows are deduped, sorted by `createdAt` per [order]
     * (newest first by default, matching Turnkey) and sliced. An EVM chain [privyChain] has no
     * case for (Arbitrum Sepolia, for example) returns an empty result rather than failing.
     */
    override suspend fun getTransactions(
        chainId: Int,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?,
    ): List<RainTransaction> {
        val needed = maxOf((limit ?: DEFAULT_TRANSACTION_LIMIT) + (offset ?: 0), 1)
        val collected = (
            if (solanaSupport.isSolanaChain(chainId)) {
                collectSolanaHistory(chainId, needed)
            } else {
                collectEvmHistory(chainId, needed)
            }
            ) ?: return emptyList()

        val deduped = collected.distinctBy { it.privyTransactionId ?: it.transactionHash ?: it }
        val sorted = when (order ?: RainTransactionOrder.DESC) {
            RainTransactionOrder.ASC -> deduped.sortedBy { it.createdAt }
            RainTransactionOrder.DESC -> deduped.sortedByDescending { it.createdAt }
        }
        val sliced = sorted
            .drop(offset ?: 0)
            .let { if (limit != null) it.take(limit) else it }

        return sliced.map { toRainTransaction(chainId, it) }
    }

    /** EVM rows across the native asset and registered tokens, or null when Privy has no slug. */
    private suspend fun collectEvmHistory(chainId: Int, needed: Int): List<PrivyTransaction>? {
        val chain = privyChain(chainId) ?: return null
        val walletAddress = getWalletAddress()

        val collected = mutableListOf<PrivyTransaction>()
        collected += collectPages(needed) { pageLimit, cursor ->
            manager.getTransactions(
                walletAddress,
                GetTransactionsParams(
                    chain = chain.chain,
                    assets = listOf(chain.nativeAsset),
                    tokens = null,
                    limit = pageLimit,
                    cursor = cursor,
                ),
            )
        }
        tokenStore.registeredTokens(chainId)
            .map { it.address }
            .chunked(MAX_TRANSACTION_FILTERS_PER_REQUEST)
            .forEach { chunk ->
                collected += collectPages(needed) { pageLimit, cursor ->
                    manager.getTransactions(
                        walletAddress,
                        GetTransactionsParams(
                            chain = chain.chain,
                            assets = null,
                            tokens = chunk,
                            limit = pageLimit,
                            cursor = cursor,
                        ),
                    )
                }
            }
        return collected
    }

    /**
     * Solana rows across native SOL and registered mints, or null when the cluster is not
     * indexed — Privy's server-side chain enum covers only mainnet, so devnet/testnet return
     * empty results like unindexed EVM chains.
     */
    private suspend fun collectSolanaHistory(chainId: Int, needed: Int): List<PrivyTransaction>? {
        if (chainId != RainChain.SOLANA_MAINNET) return null

        val collected = mutableListOf<PrivyTransaction>()
        collected += collectPages(needed) { pageLimit, cursor ->
            manager.getSolanaTransactions(
                GetTransactionsParams(
                    chain = TransactionChain.Solana.Mainnet,
                    assets = listOf(SOLANA_NATIVE_ASSET),
                    tokens = null,
                    limit = pageLimit,
                    cursor = cursor,
                ),
            )
        }
        tokenStore.registeredTokens(chainId)
            .map { it.address }
            .chunked(MAX_TRANSACTION_FILTERS_PER_REQUEST)
            .forEach { chunk ->
                collected += collectPages(needed) { pageLimit, cursor ->
                    manager.getSolanaTransactions(
                        GetTransactionsParams(
                            chain = TransactionChain.Solana.Mainnet,
                            assets = null,
                            tokens = chunk,
                            limit = pageLimit,
                            cursor = cursor,
                        ),
                    )
                }
            }
        return collected
    }

    /** Collects up to [needed] rows for one asset-or-token filter, following Privy's cursor. */
    private suspend fun collectPages(
        needed: Int,
        fetch: suspend (pageLimit: Int, cursor: String?) -> TransactionsPage,
    ): List<PrivyTransaction> {
        val collected = mutableListOf<PrivyTransaction>()
        var cursor: String? = null
        do {
            val page = fetch(minOf(needed - collected.size, MAX_TRANSACTIONS_PAGE_SIZE), cursor)
            collected += page.transactions
            cursor = page.nextCursor
        } while (cursor != null && collected.size < needed)
        return collected
    }

    private fun toRainTransaction(chainId: Int, transaction: PrivyTransaction): RainTransaction {
        val details = transaction.details
        // `asset` is either a named asset ("eth", "sol", "usdc") or a token address; route it to
        // the field that matches its shape. Solana token addresses are base58, not 0x-prefixed —
        // a base58 mint is at least 32 chars, so length separates it from any named asset.
        val assetIsAddress = details?.asset?.let { asset ->
            if (solanaSupport.isSolanaChain(chainId)) {
                asset.length >= SOLANA_MIN_ADDRESS_LENGTH
            } else {
                asset.startsWith("0x")
            }
        } == true
        return RainTransaction(
            hash = transaction.transactionHash
                ?: transaction.userOperationHash
                ?: transaction.privyTransactionId
                ?: "",
            uniqueId = transaction.privyTransactionId ?: transaction.transactionHash,
            timestamp = Instant.ofEpochMilli(transaction.createdAt)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString(),
            from = details?.sender ?: "",
            to = details?.recipient,
            value = details?.let {
                runCatching {
                    BigDecimal(it.rawValue).movePointLeft(it.rawValueDecimals).stripTrailingZeros()
                }.getOrNull()
            },
            asset = details?.asset?.takeUnless { assetIsAddress },
            tokenAddress = details?.asset?.takeIf { assetIsAddress },
            rawValue = details?.rawValue?.takeIf { assetIsAddress },
            decimals = details?.rawValueDecimals?.takeIf { assetIsAddress },
            category = when {
                !assetIsAddress -> RainTransactionCategory.External
                solanaSupport.isSolanaChain(chainId) -> RainTransactionCategory.Token
                else -> RainTransactionCategory.Erc20
            },
            chainId = chainId,
            metadata = RainTransaction.Metadata(
                caip2 = transaction.caip2,
                status = transaction.status.toRainValue(),
                sponsored = transaction.sponsored,
                privyTransactionId = transaction.privyTransactionId,
                userOperationHash = transaction.userOperationHash,
                type = details?.type?.toRainValue(),
                displayValues = details?.displayValues?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    private fun TransactionStatus.toRainValue(): String = when (this) {
        TransactionStatus.Broadcasted -> "broadcasted"
        TransactionStatus.Confirmed -> "confirmed"
        TransactionStatus.ExecutionReverted -> "executionReverted"
        TransactionStatus.Failed -> "failed"
        TransactionStatus.Replaced -> "replaced"
        TransactionStatus.Finalized -> "finalized"
        TransactionStatus.ProviderError -> "providerError"
        TransactionStatus.Pending -> "pending"
        is TransactionStatus.Unknown -> rawValue
    }

    private fun TransactionType.toRainValue(): String = when (this) {
        TransactionType.TransferSent -> "transferSent"
        TransactionType.TransferReceived -> "transferReceived"
        is TransactionType.Unknown -> rawValue
    }

    /**
     * The [TransactionChain] and native-asset filter name Privy's indexer accepts for [chainId],
     * or null when the chain is not indexed (verified against the endpoint's server-side enum:
     * ethereum, arbitrum, avalanche, base, bsc, tempo, linea, optimism, polygon, solana, sepolia).
     * Privy identifies chains by slug, not chain id; when Privy adds a slug with no SDK case yet
     * (e.g. a testnet), map it here via [TransactionChain.Evm.Custom].
     */
    private fun privyChain(chainId: Int): PrivyIndexedChain? = when (chainId) {
        1 -> PrivyIndexedChain(TransactionChain.Evm.Ethereum, "eth")
        10 -> PrivyIndexedChain(TransactionChain.Evm.Optimism, "eth")
        56 -> PrivyIndexedChain(TransactionChain.Evm.Bsc, "bnb")
        137 -> PrivyIndexedChain(TransactionChain.Evm.Polygon, "pol")
        8453 -> PrivyIndexedChain(TransactionChain.Evm.Base, "eth")
        42161 -> PrivyIndexedChain(TransactionChain.Evm.Arbitrum, "eth")
        43114 -> PrivyIndexedChain(TransactionChain.Evm.Avalanche, "avax")
        59144 -> PrivyIndexedChain(TransactionChain.Evm.Linea, "eth")
        11155111 -> PrivyIndexedChain(TransactionChain.Evm.Sepolia, "eth")
        84532 -> PrivyIndexedChain(TransactionChain.Evm.BaseSepolia, "eth")
        else -> null
    }

    private data class PrivyIndexedChain(val chain: TransactionChain.Evm, val nativeAsset: String)

    // ---------- helpers ----------

    private fun rpcUrlFor(chainId: Int): String =
        rpcEndpoints[chainId]
            ?: throw RainError.InvalidConfig("No RPC endpoint configured for chainId=$chainId")

    /** Refuses a Solana chain id on an EVM-only entry point, as the Turnkey adapter's `requireEvmChain` does. */
    private fun requireEvmChain(chainId: Int, operation: String) {
        if (solanaSupport.isSolanaChain(chainId)) {
            throw RainError.InvalidConfig(
                "$operation is EVM-only; use sendNative/sendToken on Solana chainId=$chainId"
            )
        }
    }

    private fun solanaCluster(chainId: Int): SolanaCluster = when (chainId) {
        RainChain.SOLANA_MAINNET -> SolanaCluster.MainNet
        RainChain.SOLANA_DEVNET -> SolanaCluster.DevNet
        RainChain.SOLANA_TESTNET -> SolanaCluster.TestNet
        else -> throw RainError.InvalidConfig("Not a known Solana chainId: $chainId")
    }

    private fun rpcTransactionObject(
        from: String,
        to: String,
        data: String,
        value: String,
    ): Map<String, String> {
        val tx = mutableMapOf("from" to from, "to" to to, "value" to value.ifEmpty { "0x0" })
        if (data.isNotEmpty() && data != "0x") tx["data"] = data
        return tx
    }

    private companion object {
        /** Upper bound on simultaneous per-token balance RPC calls in [getBalances]. */
        const val MAX_CONCURRENT_BALANCE_READS = 8

        /** Rows returned by [getTransactions] when the caller passes no limit (parity with Turnkey). */
        const val DEFAULT_TRANSACTION_LIMIT = 10

        /** Privy's server-side maximum page size for its transactions endpoint. */
        const val MAX_TRANSACTIONS_PAGE_SIZE = 100

        /** Privy's server-side maximum number of asset/token filters per request. */
        const val MAX_TRANSACTION_FILTERS_PER_REQUEST = 10

        /** Privy's indexer name for native SOL, the analogue of "eth"/"pol" on EVM chains. */
        const val SOLANA_NATIVE_ASSET = "sol"

        /** Shortest base58 encoding of a 32-byte key — anything shorter is a named asset. */
        const val SOLANA_MIN_ADDRESS_LENGTH = 32
    }
}
