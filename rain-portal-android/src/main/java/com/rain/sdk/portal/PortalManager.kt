package com.rain.sdk.portal

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionCategory
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import com.rain.sdk.utils.EthereumConverter
import io.portalhq.android.Portal
import io.portalhq.android.api.data.GetTransactionsOrder
import io.portalhq.android.api.data.Transaction
import io.portalhq.android.api.data.ntfassetsbychain.TokenBalance
import io.portalhq.android.mpc.data.FeatureFlags
import io.portalhq.android.provider.data.EthTransactionParam
import io.portalhq.android.provider.data.PortalProviderResult
import io.portalhq.android.provider.data.PortalProviderRpcResponse
import io.portalhq.android.provider.data.PortalRequestMethod
import io.portalhq.android.storage.mobile.PortalNamespace
import io.portalhq.android.utils.events.PortalEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Wrapper around Portal SDK to encapsulate all Portal interactions.
 *
 * Provides a clean API for signing and sending transactions through Portal,
 * and manages the Portal instance lifecycle.
 */
internal class PortalManager(
    /** Pause between post-submit retries (UserOperation scan, block-number read); injectable for tests. */
    private val retryIntervalMs: Long = UserOperationLookup.INTERVAL_MS
) {

    @Volatile
    private var _portal: Portal? = null

    // Retained so a token refresh can rebuild an identical client.
    private var legacyEthChainId: Int = 1
    private var rpcConfig: Map<String, String> = emptyMap()
    private var featureFlags: FeatureFlags? = null
    private var autoApprove: Boolean = true

    /**
     * Checks if Portal has been initialized.
     */
    val isInitialized: Boolean
        get() = _portal != null

    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Initializes the Portal instance with provided configuration.
     *
     * @param apiKey Portal API key (session token)
     * @param legacyEthChainId The default chain ID for legacy operations
     * @param rpcConfig Map of chain identifiers to RPC URLs
     * @param featureFlags Portal feature flags
     * @param autoApprove Whether to auto-approve transactions
     */
    fun initialize(
        apiKey: String,
        legacyEthChainId: Int,
        rpcConfig: Map<String, String>,
        featureFlags: FeatureFlags,
        autoApprove: Boolean
    ) {
        this.legacyEthChainId = legacyEthChainId
        this.rpcConfig = rpcConfig
        this.featureFlags = featureFlags
        this.autoApprove = autoApprove
        swapPortal(apiKey)
    }

    /**
     * Round-trips the session token against Portal's API. Construction never touches the network,
     * so this is where a rejected token first fails — as [RainError.TokenExpired], not a raw 401.
     */
    suspend fun verifySession() {
        val portal = getPortalInstance()
        try {
            portal.api.getClient()
        } catch (e: Exception) {
            if (e is CancellationException || e is RainError) throw e
            throw PortalErrorMapping.mapRead(e)
        }
    }

    /** Rebuilds the client around a new token with the config from [initialize]; MPC shares survive. */
    fun reinitialize(apiKey: String) {
        if (_portal == null) throw RainError.SdkNotInitialized()
        swapPortal(apiKey)
    }

    private fun swapPortal(apiKey: String) {
        val previousScope = scope
        val nextScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        val portal = createPortal(
            apiKey = apiKey,
            legacyEthChainId = legacyEthChainId,
            rpcConfig = rpcConfig,
            featureFlags = featureFlags ?: FeatureFlags(),
            autoApprove = autoApprove
        )

        // Portal raises no approval UI of its own: an unanswered PortalSigningRequested just hangs the
        // signature. The handler is therefore registered unless the host opted out to gate signing
        // itself, in which case answering the event is the host's job.
        if (autoApprove) {
            portal.on(PortalEvents.PortalSigningRequested) { data ->
                Timber.d("Rain SDK: Auto-approving signing request")
                if (nextScope.isActive) {
                    nextScope.launch {
                        portal.emit(PortalEvents.PortalSigningApproved, data)
                    }
                }
            }
        } else {
            Timber.w(
                "Rain SDK: Portal autoApprove is disabled — the host must answer " +
                    "PortalSigningRequested or every signature will hang"
            )
        }

        // Publish before retiring the old one so concurrent callers never see a gap.
        scope = nextScope
        _portal = portal
        previousScope.cancel()
        Timber.d("Rain SDK: Portal initialized (autoApprove=$autoApprove)")
    }

    /**
     * Gets the wallet address for the specified namespace.
     *
     * @return The wallet address
     * @throws RainError.ProviderError if Portal is not initialized or fails to get address
     */
    suspend fun getAddress(): String {
        val portal = getPortalInstance()

        return try {
            val address = portal.getAddress(PortalNamespace.EIP155)
            if (address.isNullOrEmpty()) {
                throw RainError.WalletUnavailable("Portal returned no EIP-155 wallet address")
            }
            address
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is RainError) throw e
            throw PortalErrorMapping.mapRead(e)
        }
    }

    /**
     * Fetches a single balance (native or a contract token) as a rich [Balance], preserving
     * exact base-unit precision.
     */
    suspend fun getBalance(
        chainId: Int,
        token: Token,
        tokenStore: TokenMetadataStore
    ): Balance = when (token) {
        is Token.Native -> fetchNativeBalance(chainId, tokenStore)
        is Token.Contract -> fetchContractBalance(chainId, token.address, tokenStore)
    }

    /**
     * Fetches all non-zero balances (native always included) for the current wallet on the
     * given network. Native via `eth_getBalance`; ERC-20s via Portal's `getAssets`, with raw
     * amounts reconstructed exactly and zero-balance contract tokens omitted.
     */
    suspend fun getBalances(
        chainId: Int,
        tokenStore: TokenMetadataStore
    ): List<Balance> {
        val native = fetchNativeBalance(chainId, tokenStore)
        val portal = getPortalInstance()
        val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

        val tokenBalances = try {
            portal.api.getAssets(eip155ChainId).getOrThrow().tokenBalances
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is RainError) throw e
            Timber.e(e, "Rain SDK: Failed to get balances for chainId=$chainId")
            throw PortalErrorMapping.mapRead(e)
        }

        val output = mutableListOf(native)
        for (entry in tokenBalances) {
            // Portal's TokenBalance exposes the contract address inside the untyped `metadata`
            // map under "tokenAddress".
            val address = entry.metadata["tokenAddress"] as? String
            if (address.isNullOrEmpty()) continue
            val info = tokenStore.tokenInfo(chainId, address)
            val raw = reconstructRawAmount(entry, info.decimals)
            if (raw <= BigInteger.ZERO) continue
            output += Balance(
                token = Token.Contract(address),
                chainId = chainId,
                rawAmount = raw,
                decimals = info.decimals,
                symbol = info.symbol ?: entry.symbol,
                name = info.name ?: entry.name
            )
        }
        return output
    }

    /** Fetches the native balance via `eth_getBalance`, preserving exact wei precision. */
    private suspend fun fetchNativeBalance(chainId: Int, tokenStore: TokenMetadataStore): Balance {
        val portal = getPortalInstance()
        val walletAddress = getAddress()
        val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"
        val result = try {
            portal.request(
                chainId = eip155ChainId,
                method = PortalRequestMethod.eth_getBalance,
                params = listOf(walletAddress, "latest")
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is RainError) throw e
            Timber.e(e, "Rain SDK: Failed to get native balance for chainId=$chainId")
            throw PortalErrorMapping.mapRead(e)
        }
        val raw = EthereumConverter.parseHexToBigIntegerStrict(result.toHexString())
        val native = tokenStore.nativeCurrency(chainId)
        return Balance(
            token = Token.Native,
            chainId = chainId,
            rawAmount = raw,
            decimals = native.decimals,
            symbol = native.symbol,
            name = native.name
        )
    }

    /** Fetches a single ERC-20 balance via direct RPC `eth_call` (balanceOf), preserving exact precision. */
    private suspend fun fetchContractBalance(
        chainId: Int,
        address: String,
        tokenStore: TokenMetadataStore
    ): Balance {
        val portal = getPortalInstance()
        val walletAddress = getAddress()
        val info = tokenStore.tokenInfo(chainId, address)
        val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

        val function = Function(
            "balanceOf",
            listOf(Address(walletAddress)),
            listOf(object : TypeReference<Uint256>() {})
        )
        val callParams = mapOf("to" to address, "data" to FunctionEncoder.encode(function))

        return try {
            val result = portal.request(
                chainId = eip155ChainId,
                method = PortalRequestMethod.eth_call,
                params = listOf(callParams, "latest")
            )
            val raw = EthereumConverter.parseHexToBigIntegerStrict(result.toHexString())
            Balance(
                token = Token.Contract(address),
                chainId = chainId,
                rawAmount = raw,
                decimals = info.decimals,
                symbol = info.symbol,
                name = info.name
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is RainError) throw e
            Timber.e(e, "Rain SDK: Failed to get ERC20 balance via RPC for token=$address chainId=$chainId")
            throw PortalErrorMapping.mapRead(e)
        }
    }

    /**
     * Reconstructs the exact base-unit amount for a Portal asset entry: prefer the raw integer
     * string when present, else reconstruct from the formatted decimal balance.
     */
    private fun reconstructRawAmount(entry: TokenBalance, decimals: Int): BigInteger {
        val rawBalance = entry.rawBalance
        if (!rawBalance.isNullOrEmpty()) {
            runCatching { BigInteger(rawBalance) }.getOrNull()?.let { return it }
        }
        return EthereumConverter.decimalStringToBigInteger(entry.balance, decimals)
    }

    private fun TokenBalance.tokenAddress(): String? = metadata["tokenAddress"] as? String

    /**
     * Gets the transaction history for the specified chain.
     *
     * @param chainId Numerical chain ID (e.g. 43114)
     * @param tokenStore Metadata store used to resolve the chain's native currency symbol
     * @param limit Optional maximum number of transactions to return
     * @param offset Optional number of transactions to skip for pagination
     * @param order Optional sort order (ASC or DESC)
     * @return the mapped transactions
     */
    suspend fun getTransactions(
        chainId: Int,
        tokenStore: TokenMetadataStore,
        limit: Int? = null,
        offset: Int? = null,
        order: RainTransactionOrder? = null
    ): List<RainTransaction> {
        val portal = getPortalInstance()
        val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

        return try {
            val portalOrder = when (order) {
                RainTransactionOrder.ASC -> GetTransactionsOrder.ASC
                RainTransactionOrder.DESC -> GetTransactionsOrder.DESC
                null -> null
            }

            val portalTransactions = portal.api.getTransactions(
                chainId = eip155ChainId,
                limit = limit,
                offset = offset,
                order = portalOrder
            ).getOrThrow()

            // Resolve ERC-20 metadata once per unique contract, not once per row: a page of transfers in
            // the same token would otherwise repeat the identical pair of eth_calls for every row.
            val metadata = fetchErc20Metadata(portalTransactions, portal, eip155ChainId)
            val nativeSymbol = tokenStore.nativeCurrencyOrNull(chainId)?.symbol

            portalTransactions.map { tx ->
                val contractAddress = tx.rawContract?.address
                val entry = contractAddress?.let { metadata[it.lowercase()] }
                RainTransaction(
                    hash = tx.hash,
                    uniqueId = tx.uniqueId,
                    blockNumber = tx.blockNum,
                    timestamp = tx.metadata?.blockTimestamp,
                    from = tx.from,
                    to = tx.to,
                    value = resolveTransactionValue(tx, entry?.decimals),
                    asset = if (contractAddress == null) nativeSymbol else entry?.symbol,
                    tokenAddress = contractAddress,
                    rawValue = tx.rawContract?.value,
                    decimals = tx.rawContract?.decimal?.toIntOrNull() ?: entry?.decimals,
                    category = tx.category?.let { RainTransactionCategory(it) },
                    chainId = tx.chainId
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is RainError) throw e
            Timber.e(e, "Rain SDK: Failed to get transactions for chainId=$chainId")
            throw PortalErrorMapping.mapRead(e)
        }
    }

    /**
     * Signs typed data (EIP-712) using Portal.
     *
     * @param chainId The chain ID
     * @param walletAddress The wallet address to sign with
     * @param typedDataJson The EIP-712 typed data as JSON string
     * @return The signature as a hex string
     * @throws RainError.ProviderError if signing fails
     */
    suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String
    ): String {
        val portal = getPortalInstance()

        return try {
            val response = portal.request(
                chainId = "${PortalNamespace.EIP155.value}:$chainId",
                method = PortalRequestMethod.eth_signTypedData_v4,
                params = listOf(walletAddress, typedDataJson)
            )
            response.result.toString()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "Rain SDK: Failed to sign typed data")
            // Map Portal-specific failures (401 / invalid API key) here; anything else bubbles raw
            // so the session coordinator can classify it (user rejection, insufficient funds, ...)
            // before it leaves the adapter.
            PortalErrorMapping.mapAuthOrNull(e)?.let { throw it }
            throw e
        }
    }

    /**
     * Estimates the total fee (gas limit * gas price) for a transaction, in the chain's native token.
     *
     * @param chainId The chain ID
     * @param from The sender address
     * @param to The target contract address
     * @param data Hex-encoded calldata (or "0x" / empty for plain transfers)
     * @param value Hex-encoded wei value
     * @return Estimated fee in the chain's native token (e.g. ETH/AVAX), as an exact [BigDecimal]
     */
    suspend fun estimateTransactionFee(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String = "0x0"
    ): BigDecimal {
        val portal = getPortalInstance()
        val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

        val ethParams = io.portalhq.android.provider.data.EthTransactionParam(
            from = from,
            to = to,
            gas = null,
            gasPrice = null,
            maxFeePerGas = null,
            maxPriorityFeePerGas = null,
            data = data,
            value = value,
            nonce = null
        )

        val (gasHex, gasPriceHex) = try {
            coroutineScope {
                val gasLimitDeferred = async {
                    portal.request(
                        chainId = eip155ChainId,
                        method = PortalRequestMethod.eth_estimateGas,
                        params = listOf(ethParams)
                    )
                }
                val gasPriceDeferred = async {
                    portal.request(
                        chainId = eip155ChainId,
                        method = PortalRequestMethod.eth_gasPrice,
                        params = listOf()
                    )
                }

                val gasHex = gasLimitDeferred.await().toHexString()
                val gasPriceHex = gasPriceDeferred.await().toHexString()
                Pair(gasHex, gasPriceHex)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is RainError) throw e
            Timber.e(e, "Rain SDK: Failed to estimate transaction fee")
            // Map Portal-specific failures first: `eth_estimateGas` simulates the call, so JSON-RPC
            // error code 3 means the node reverted execution (e.g. an invalid signature in the
            // calldata); 401 / invalid API key means the session token expired. Anything else bubbles
            // raw; the session coordinator maps it before it leaves the adapter.
            PortalErrorMapping.mapSimulationOrNull(e)?.let { throw it }
            if (PortalErrorMapping.isTransient(e)) throw RainError.NetworkError(cause = e)
            throw e
        }

        val gasLimit = java.math.BigInteger(gasHex.removePrefix("0x"), 16)
        val gasPrice = java.math.BigInteger(gasPriceHex.removePrefix("0x"), 16)
        return EthereumConverter.convertWeiToEthDecimal(gasLimit.multiply(gasPrice))
    }

    /**
     * Sends a transaction using Portal.
     *
     * @param chainId The chain ID
     * @param from The sender address
     * @param to The recipient address
     * @param data The transaction data (encoded function call)
     * @param value The value to send (default "0x0")
     * @return The transaction hash
     * @throws Exception if transaction fails
     */
    suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String = "0x0"
    ): String {
        val portal = getPortalInstance()
        val eip155ChainId = "${PortalNamespace.EIP155.value}:$chainId"

        val ethParam = mapOf(
            "from" to from,
            "to" to to,
            "data" to data,
            "value" to value
        )

        // Simulate the transaction first via eth_call to catch failures
        // (e.g. insufficient funds, contract reverts) — no balance fetch needed,
        // the node validates it for free.
        try {
            portal.request(
                chainId = eip155ChainId,
                method = PortalRequestMethod.eth_call,
                params = listOf(ethParam, "latest")
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is RainError) throw e
            Timber.e(e, "Rain SDK: Transaction simulation failed (eth_call)")
            // A rejected token is a session problem and a network failure is retryable: neither is a
            // verdict on the transaction, and calling them a failed simulation would tell the
            // withdrawal flow the send reverted when it never left the device.
            PortalErrorMapping.mapAuthOrNull(e)?.let { throw it }
            if (PortalErrorMapping.isTransient(e)) throw RainError.NetworkError(e.message, e)
            throw RainError.TransactionSimulationFailed(e)
        }

        // Read before the submit, so the UserOperation scan below has a lower bound to search from.
        val submittedFrom = currentBlockNumber(portal, eip155ChainId)

        val params = EthTransactionParam(
            from = from,
            to = to,
            gas = null,
            gasPrice = null,
            maxFeePerGas = null,
            maxPriorityFeePerGas = null,
            value = value,
            data = data,
            nonce = null
        )

        val result = try {
            portal.request(
                chainId = eip155ChainId,
                method = PortalRequestMethod.eth_sendTransaction,
                params = listOf(params)
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "Rain SDK: Failed to send transaction")
            // Map Portal-specific failures first: JSON-RPC error code 3 on a send means the node
            // reverted execution, i.e. the transaction cannot succeed (core translates that to
            // WithdrawalRevertedByNetwork on the withdrawal flows only); 401 / invalid API key means
            // the session token expired. Anything else bubbles raw so the session coordinator can
            // classify it (user rejection, insufficient funds, ...) before it leaves the adapter.
            PortalErrorMapping.mapSimulationOrNull(e)?.let { throw it }
            throw e
        }
        return minedTransactionHash(
            portal = portal,
            chainId = eip155ChainId,
            hash = result.toTransactionHash(),
            fromBlock = submittedFrom
        )
    }

    /**
     * Where the Portal environment has Account Abstraction enabled on a chain, `eth_sendTransaction`
     * returns a UserOperation hash rather than a transaction hash. No node has heard of that hash,
     * so a receipt poll on it never terminates. Resolve it through the EntryPoint's
     * `UserOperationEvent` and return the hash the operation was actually mined under.
     *
     * A plain transaction is in the mempool the moment it is submitted, so it returns on the first
     * pass and never reaches the scan.
     */
    private suspend fun minedTransactionHash(
        portal: Portal,
        chainId: String,
        hash: String,
        fromBlock: BigInteger?
    ): String {
        // A lookup that keeps failing looks the same as "not mined yet" from the outside; keep the
        // last failure so the terminal log says which it was.
        var lastLookupFailure: Exception? = null
        val recordFailure = { e: Exception -> lastLookupFailure = e }

        // Without a lower bound there is nothing to scan; only hand the hash on if a node knows it.
        if (fromBlock == null) {
            if (isKnownTransaction(portal, chainId, hash, recordFailure)) return hash
            Timber.w(
                lastLookupFailure,
                "Rain SDK: %s could not be verified as a transaction hash (block read failed)",
                hash
            )
            throw RainError.TransactionPending(hash)
        }

        repeat(UserOperationLookup.ATTEMPTS) { attempt ->
            if (isKnownTransaction(portal, chainId, hash, recordFailure)) return hash

            val event = userOperationEvent(portal, chainId, hash, fromBlock, recordFailure)
            if (event != null) {
                val (transactionHash, succeeded) = event
                if (!succeeded) {
                    throw RainError.TransactionSimulationFailed(
                        IllegalStateException(
                            "UserOperation $hash reverted on-chain in transaction $transactionHash"
                        )
                    )
                }
                Timber.i("Rain SDK: UserOperation $hash mined in transaction $transactionHash")
                return transactionHash
            }

            if (attempt < UserOperationLookup.ATTEMPTS - 1) delay(retryIntervalMs)
        }

        // Neither shape resolved within the window. The operation is out and may still mine, so this
        // is pending, not failure — and the UserOperation hash is what the host resumes from. Handing
        // it on as a transaction hash would send the caller's receipt poll after something no node
        // can ever answer for.
        Timber.w(
            lastLookupFailure,
            "Rain SDK: %s did not resolve to a mined transaction within the scan window",
            hash
        )
        throw RainError.TransactionPending(hash)
    }

    /** Whether the chain knows this hash as a transaction, mined or pending. */
    private suspend fun isKnownTransaction(
        portal: Portal,
        chainId: String,
        hash: String,
        onFailure: (Exception) -> Unit
    ): Boolean {
        val response = try {
            portal.request(
                chainId = chainId,
                method = PortalRequestMethod.eth_getTransactionByHash,
                params = listOf(hash)
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w(e, "Rain SDK: eth_getTransactionByHash failed during UserOperation resolution")
            onFailure(e)
            return false
        }
        return (response.result as? PortalProviderRpcResponse)?.result != null
    }

    /** Finds the `UserOperationEvent` this hash was emitted under, if it has been included yet. */
    private suspend fun userOperationEvent(
        portal: Portal,
        chainId: String,
        hash: String,
        fromBlock: BigInteger,
        onFailure: (Exception) -> Unit
    ): Pair<String, Boolean>? {
        val filter = mapOf(
            "fromBlock" to "0x" + fromBlock.toString(16),
            "toBlock" to "latest",
            // Public RPCs reject an address-less log filter, so name every canonical EntryPoint.
            "address" to UserOperationLookup.ENTRY_POINTS,
            "topics" to listOf(UserOperationLookup.EVENT_TOPIC, hash)
        )
        val response = try {
            portal.request(
                chainId = chainId,
                method = PortalRequestMethod.eth_getLogs,
                params = listOf(filter)
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w(e, "Rain SDK: eth_getLogs failed during UserOperation resolution")
            onFailure(e)
            return null
        }
        val logs = (response.result as? PortalProviderRpcResponse)?.result as? List<*> ?: return null
        val log = logs.firstOrNull() as? Map<*, *> ?: return null
        val transactionHash = log["transactionHash"] as? String ?: return null
        return transactionHash to userOperationSucceeded(log["data"] as? String)
    }

    /**
     * Retried on failure: the read has no side effects, and without it the UserOperation scan is
     * off for this send.
     */
    private suspend fun currentBlockNumber(portal: Portal, chainId: String): BigInteger? {
        var response: PortalProviderResult? = null
        for (attempt in 0 until UserOperationLookup.BLOCK_NUMBER_ATTEMPTS) {
            response = try {
                portal.request(
                    chainId = chainId,
                    method = PortalRequestMethod.eth_blockNumber,
                    params = emptyList()
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w(e, "Rain SDK: eth_blockNumber read failed (attempt %d)", attempt + 1)
                if (attempt < UserOperationLookup.BLOCK_NUMBER_ATTEMPTS - 1) delay(retryIntervalMs)
                null
            }
            if (response != null) break
        }
        val hex = (response?.result as? PortalProviderRpcResponse)?.result as? String ?: return null
        val block = hex.removePrefix("0x").toBigIntegerOrNull(16) ?: return null
        return if (block > BigInteger.ZERO) block else null
    }

    /**
     * `UserOperationEvent` data is `(nonce, success, actualGasCost, actualGasUsed)`. An unreadable
     * payload counts as success: the operation was mined, and inventing a failure here would mask
     * whatever the caller's own confirmation reads back.
     */
    private fun userOperationSucceeded(data: String?): Boolean {
        if (data == null || !data.startsWith("0x")) return true
        val words = data.removePrefix("0x")
        if (words.length < 128) return true
        return words.substring(64, 128).any { it != '0' }
    }

    /** On-chain ERC-20 metadata for one contract, resolved at most once per `getTransactions` call. */
    private data class Erc20Metadata(val decimals: Int?, val symbol: String?)

    /**
     * Resolves `decimals` and `symbol` for every distinct contract in the page, concurrently.
     * `decimals` is only read for contracts where at least one row needs it — rows that already
     * carry a parsed value or a `rawContract.decimal` never trigger the call.
     */
    private suspend fun fetchErc20Metadata(
        transactions: List<Transaction>,
        portal: Portal,
        eip155ChainId: String
    ): Map<String, Erc20Metadata> {
        val symbolAddresses = transactions.mapNotNull { it.rawContract?.address }.distinctBy { it.lowercase() }
        if (symbolAddresses.isEmpty()) return emptyMap()

        val decimalsNeeded = transactions
            .filter { tx ->
                tx.value == null &&
                    tx.rawContract?.value != null &&
                    tx.rawContract?.decimal?.toIntOrNull() == null
            }
            .mapNotNull { it.rawContract?.address?.lowercase() }
            .toSet()

        return coroutineScope {
            symbolAddresses.map { address ->
                async {
                    val key = address.lowercase()
                    val symbol = async { fetchErc20Symbol(portal, eip155ChainId, address) }
                    val decimals = if (key in decimalsNeeded) {
                        fetchErc20Decimals(portal, eip155ChainId, address)
                    } else {
                        null
                    }
                    key to Erc20Metadata(decimals = decimals, symbol = symbol.await())
                }
            }.awaitAll().toMap()
        }
    }

    /**
     * Resolves a human-readable value for a transaction.
     * Prefers tx.value, falls back to rawContract hex value / decimal, then to the pre-resolved
     * on-chain `decimals` for the contract.
     */
    private fun resolveTransactionValue(tx: Transaction, resolvedDecimals: Int?): BigDecimal? {
        // If Portal already provides a parsed value, use it. Portal types it as a Double, so
        // round-trip through its printed form rather than the binary float.
        tx.value?.let { return runCatching { BigDecimal(it.toString()) }.getOrNull() }

        // Fallback: parse rawContract hex value with its decimal
        val rawContract = tx.rawContract ?: return null
        val hexValue = rawContract.value ?: return null

        val decimal = rawContract.decimal?.toIntOrNull() ?: resolvedDecimals ?: return null

        return try {
            EthereumConverter.convertHexToDecimal(hexValue, decimal)
        } catch (e: Exception) {
            Timber.w(e, "Rain SDK: Failed to parse rawContract value=$hexValue decimal=$decimal")
            null
        }
    }

    /**
     * Fetches ERC20 decimals from contract via eth_call.
     */
    private suspend fun fetchErc20Decimals(
        portal: Portal,
        eip155ChainId: String,
        contractAddress: String
    ): Int? {
        return try {
            val function = Function("decimals", emptyList(), listOf(object : TypeReference<Uint256>() {}))
            val encodedFunction = FunctionEncoder.encode(function)
            val callParams = mapOf("to" to contractAddress, "data" to encodedFunction)
            val result = portal.request(
                chainId = eip155ChainId,
                method = PortalRequestMethod.eth_call,
                params = listOf(callParams, "latest")
            )
            val hex = result.toHexString()
            hex.removePrefix("0x").toBigInteger(16).toInt()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            PortalErrorMapping.mapAuthOrNull(e)?.let { throw it }
            Timber.w(e, "Rain SDK: Failed to fetch decimals for contract=$contractAddress")
            null
        }
    }

    /**
     * Fetches ERC20 symbol from contract via eth_call.
     */
    private suspend fun fetchErc20Symbol(
        portal: Portal,
        eip155ChainId: String,
        contractAddress: String
    ): String? {
        return try {
            val function =
                Function("symbol", emptyList(), listOf(object : TypeReference<org.web3j.abi.datatypes.Utf8String>() {}))
            val encodedFunction = FunctionEncoder.encode(function)
            val callParams = mapOf("to" to contractAddress, "data" to encodedFunction)
            val result = portal.request(
                chainId = eip155ChainId,
                method = PortalRequestMethod.eth_call,
                params = listOf(callParams, "latest")
            )
            val hex = result.toHexString()
            if (hex.length > 2) {
                val decoded = org.web3j.abi.FunctionReturnDecoder.decode(hex, function.outputParameters)
                if (decoded.isNotEmpty()) {
                    (decoded[0] as org.web3j.abi.datatypes.Utf8String).value
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            PortalErrorMapping.mapAuthOrNull(e)?.let { throw it }
            Timber.w(e, "Rain SDK: Failed to fetch symbol for contract=$contractAddress")
            null
        }
    }
    fun getPortalInstance(): Portal {
        return _portal ?: throw RainError.SdkNotInitialized()
    }

    /**
     * Factory method to create Portal instance.
     * Separated for testability (can be mocked).
     */
    internal fun createPortal(
        apiKey: String,
        legacyEthChainId: Int,
        rpcConfig: Map<String, String>,
        featureFlags: FeatureFlags,
        autoApprove: Boolean
    ): Portal {
        // No storage backends here by design: portal-android registers backup storage at
        // backup-call time, not at construction.
        return Portal(
            apiKey = apiKey,
            legacyEthChainId = legacyEthChainId,
            rpcConfig = rpcConfig,
            featureFlags = featureFlags,
            autoApprove = autoApprove
        )
    }

    /** Tears the client down for good; idempotent. A destroyed manager cannot be reinitialized. */
    fun destroy() {
        scope.cancel()
        _portal = null
        Timber.d("Rain SDK: PortalManager destroyed and coroutines cancelled")
    }
}

private object UserOperationLookup {
    /**
     * `UserOperationEvent(bytes32,address,address,uint256,bool,uint256,uint256)`, identical across
     * EntryPoint versions. Topic 1 is the UserOperation hash.
     */
    const val EVENT_TOPIC = "0x49628fd1471006c1482da88028e9ce4dbb080b815c9b0344d39e5a8e6ec1419f"

    /** Canonical EntryPoint deployments, v0.6 through v0.8. */
    val ENTRY_POINTS = listOf(
        "0x5FF137D4b0FDCD49DcA30c7CF57E578a026d2789",
        "0x0000000071727De22E5E9d8BAf0edAc6f37da032",
        "0x4337084D9E255Ff0702461CF8895CE9E3b5Ff108"
    )

    const val ATTEMPTS = 20
    const val INTERVAL_MS = 1_000L

    /** The pre-submit block read is side-effect free, so a failure is worth a couple of retries. */
    const val BLOCK_NUMBER_ATTEMPTS = 3
}
