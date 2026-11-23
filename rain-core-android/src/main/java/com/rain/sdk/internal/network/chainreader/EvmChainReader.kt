package com.rain.sdk.internal.network.chainreader

import com.rain.sdk.error.RainError
import com.rain.sdk.error.noRpcEndpointConfigured
import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.constants.RainConstants
import com.rain.sdk.internal.constants.TokenRegistry
import com.rain.sdk.internal.utils.isValidEthereumAddress
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.utils.EthereumConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger

/**
 * EVM implementation of [ChainReader].
 *
 * Primary path — Multicall3 (`aggregate3`) for batched balance reads, so a wallet holding
 * N tokens on a chain costs one RPC round-trip regardless of N. Used when
 * [multicall3Address] knows a deployment for the target chain (zkSync Era is at a
 * non-canonical address).
 *
 * Fallback — parallel `eth_call` (`balanceOf`) and `eth_getBalance`, used on chains outside
 * the deployment map.
 *
 * Native balance failures are fatal — they indicate a chain-wide problem (bad RPC, wrong
 * chain ID). Per-token failures (a single `balanceOf` reverts) are logged via Timber and
 * the token is omitted from the result, so one bad [TokenRegistry] entry doesn't break
 * balance reads for the whole chain.
 */
@RainAdapterApi
class EvmChainReader(
    private val jsonRpcClient: JsonRpcClient = JsonRpcClient(),
    private val rpcUrlResolver: (Int) -> String?
) : ChainReader {

    private companion object {
        /** Decimals used for native balances. Every chain the SDK targets today uses 18. */
        const val DEFAULT_NATIVE_DECIMALS = 18

        /** An EVM transaction hash: 32 bytes of hex behind a `0x`. */
        val TRANSACTION_HASH = Regex("^0x[0-9a-fA-F]{64}$")

        /** A JSON-RPC quantity — validated before a node-supplied value is sent back as a block tag. */
        val HEX_QUANTITY = Regex("^0[xX][0-9a-fA-F]+$")
    }

    /** Convenience constructor backed by a static `chainId → rpcUrl` map. */
    constructor(
        rpcEndpoints: Map<Int, String>,
        jsonRpcClient: JsonRpcClient = JsonRpcClient()
    ) : this(jsonRpcClient, { rpcEndpoints[it] })

    override suspend fun getNativeBalance(chainId: Int, walletAddress: String): BigDecimal {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(walletAddress, "wallet address")
        val hex = jsonRpcClient.callForHexResult(
            rpcUrl = rpcUrl,
            method = "eth_getBalance",
            params = listOf(walletAddress, "latest")
        )
        return EthereumConverter.convertHexToDecimal(hex, DEFAULT_NATIVE_DECIMALS)
    }

    override suspend fun getERC20Balance(
        chainId: Int,
        tokenAddress: String,
        walletAddress: String,
        decimals: Int?
    ): BigDecimal {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(walletAddress, "wallet address")
        validateAddress(tokenAddress, "token address")
        val callData = Multicall3.encodeBalanceOf(walletAddress)
        val callParams = mapOf("to" to tokenAddress, "data" to callData)
        val hex = jsonRpcClient.callForHexResult(
            rpcUrl = rpcUrl,
            method = "eth_call",
            params = listOf(callParams, "latest")
        )
        return EthereumConverter.convertHexToDecimal(
            hex,
            decimals ?: RainConstants.DEFAULT_ERC20_DECIMALS
        )
    }

    override suspend fun getBalances(
        chainId: Int,
        walletAddress: String,
        tokens: List<TokenInfo>
    ): List<Balance> {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(walletAddress, "wallet address")
        // A malformed token address must not enter a batch: Multicall3's address padding only
        // left-pads, so an over-long entry would shift the whole aggregate encoding and take
        // every balance on the chain down with it. Drop it and keep the rest readable.
        val (valid, malformed) = tokens.partition { it.address.isValidEthereumAddress }
        malformed.forEach {
            Timber.w("Rain SDK: skipping token with malformed address ${it.address} on chainId=$chainId")
        }
        val multicallAddress = multicall3Address(chainId)
        return if (multicallAddress != null) {
            fetchViaMulticall3(rpcUrl, chainId, multicallAddress, walletAddress, valid)
        } else {
            fetchViaParallelCalls(rpcUrl, chainId, walletAddress, valid)
        }
    }

    override suspend fun getBalance(
        chainId: Int,
        walletAddress: String,
        token: Token,
        tokenInfo: TokenInfo?
    ): Balance {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(walletAddress, "wallet address")

        return when (token) {
            is Token.Native -> {
                val hex = jsonRpcClient.callForHexResult(
                    rpcUrl = rpcUrl,
                    method = "eth_getBalance",
                    params = listOf(walletAddress, "latest")
                )
                nativeBalance(chainId, hex)
            }
            is Token.Contract -> {
                validateAddress(token.address, "token address")
                val callData = Multicall3.encodeBalanceOf(walletAddress)
                val hex = ethCall(rpcUrl, token.address, callData)
                val info = tokenInfo ?: TokenInfo(
                    chainId = chainId,
                    address = token.address,
                    symbol = null,
                    decimals = RainConstants.DEFAULT_ERC20_DECIMALS
                )
                tokenBalance(chainId, info, hex)
            }
        }
    }

    override suspend fun getDecimals(chainId: Int, tokenAddress: String): Int {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(tokenAddress, "token address")
        val hex = ethCall(rpcUrl, tokenAddress, "0x" + ERC20Selectors.DECIMALS)
        return EthereumConverter.parseHexToIntStrict(hex)
    }

    override suspend fun getSymbol(chainId: Int, tokenAddress: String): String? {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(tokenAddress, "token address")
        val hex = ethCall(rpcUrl, tokenAddress, "0x" + ERC20Selectors.SYMBOL)
        return EthereumConverter.parseHexToString(hex)
    }

    override suspend fun getName(chainId: Int, tokenAddress: String): String? {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(tokenAddress, "token address")
        val hex = ethCall(rpcUrl, tokenAddress, "0x" + ERC20Selectors.NAME)
        return EthereumConverter.parseHexToString(hex)
    }

    override suspend fun getErc20Allowance(
        chainId: Int,
        tokenAddress: String,
        owner: String,
        spender: String,
        atBlock: String
    ): BigInteger {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(tokenAddress, "token address")
        validateAddress(owner, "owner address")
        validateAddress(spender, "spender address")
        val hex = ethCall(rpcUrl, tokenAddress, Erc20Calldata.allowance(owner, spender), atBlock)
        return EthereumConverter.parseHexToBigIntegerStrict(hex)
    }

    @Suppress("ThrowsCount") // one throw per malformed receipt field, each naming the field
    override suspend fun getTransactionReceipt(
        chainId: Int,
        transactionHash: String
    ): MinedReceipt? {
        if (!TRANSACTION_HASH.matches(transactionHash)) {
            throw RainError.InvalidConfig("Invalid transaction hash: $transactionHash")
        }
        val rpcUrl = resolveRpcUrl(chainId)
        val response = jsonRpcClient.call(
            rpcUrl = rpcUrl,
            method = "eth_getTransactionReceipt",
            params = listOf(transactionHash)
        )
        if (response.isNull("result")) return null
        val receipt = response.optJSONObject("result")
            ?: throw RainError.InternalError("Malformed transaction receipt for $transactionHash")
        val status = receipt.opt("status") as? String
            ?: throw RainError.InternalError(
                "Transaction receipt for $transactionHash carries no status field"
            )
        // Kept, not discarded: without it a caller can only read at whatever head answers next.
        val blockNumber = receipt.opt("blockNumber") as? String
            ?: throw RainError.InternalError(
                "Transaction receipt for $transactionHash carries no blockNumber field"
            )
        if (!HEX_QUANTITY.matches(blockNumber)) {
            throw RainError.InternalError(
                "Malformed receipt blockNumber for $transactionHash: $blockNumber"
            )
        }
        // Decoded as a quantity rather than matched against literals: nodes are inconsistent about
        // minimal hex encoding, and a node answering "0x01" would otherwise make a perfectly good
        // approval receipt read as malformed.
        val succeeded = when (EthereumConverter.parseHexToBigIntegerStrict(status)) {
            BigInteger.ONE -> true
            BigInteger.ZERO -> false
            else -> throw RainError.InternalError(
                "Malformed transaction receipt status for $transactionHash: $status"
            )
        }
        return MinedReceipt(succeeded = succeeded, blockNumber = blockNumber)
    }

    /**
     * Issues a raw `eth_call` at [block] and returns the hex result. For read functions with
     * pre-encoded [data] (no-arg selectors like `decimals()` / `symbol()`, or `balanceOf`).
     *
     * A node that has not reached [block] errors instead of answering from older state — a retryable
     * failure, where a stale success would be undetectable.
     */
    private suspend fun ethCall(
        rpcUrl: String,
        to: String,
        data: String,
        block: String = LATEST_BLOCK
    ): String {
        val callParams = mapOf("to" to to, "data" to data)
        return jsonRpcClient.callForHexResult(
            rpcUrl = rpcUrl,
            method = "eth_call",
            params = listOf(callParams, block)
        )
    }

    // ---------- Multicall3 path ----------

    private suspend fun fetchViaMulticall3(
        rpcUrl: String,
        chainId: Int,
        multicallAddress: String,
        walletAddress: String,
        tokens: List<TokenInfo>
    ): List<Balance> {
        // `allowFailure = true` so we get back per-call status. Native failure is fatal;
        // per-token failures are logged and omitted from the result (see decode loop below).
        val calls = buildList {
            add(
                Multicall3.Call3(
                    target = multicallAddress,
                    allowFailure = true,
                    callData = Multicall3.encodeGetEthBalance(walletAddress)
                )
            )
            tokens.forEach { token ->
                add(
                    Multicall3.Call3(
                        target = token.address,
                        allowFailure = true,
                        callData = Multicall3.encodeBalanceOf(walletAddress)
                    )
                )
            }
        }

        val aggregateCallData = Multicall3.encodeAggregate3(calls)
        val callParams = mapOf("to" to multicallAddress, "data" to aggregateCallData)
        val hex = jsonRpcClient.callForHexResult(
            rpcUrl = rpcUrl,
            method = "eth_call",
            params = listOf(callParams, "latest")
        )
        val results = Multicall3.decodeAggregate3Result(hex)

        // Expect native + one entry per token.
        val expectedCount = tokens.size + 1
        if (results.size != expectedCount) {
            throw RainError.InternalError(
                "Multicall3 returned ${results.size} results, expected $expectedCount on chain $chainId"
            )
        }

        // Index 0 is the native balance.
        val nativeResult = results[0]
        if (!nativeResult.success) {
            throw RainError.InternalError(
                "Multicall3 native balance call reverted on chain $chainId"
            )
        }

        val output = mutableListOf(nativeBalance(chainId, nativeResult.returnData))
        tokens.forEachIndexed { i, token ->
            val result = results[i + 1]
            if (!result.success) {
                Timber.w(
                    "Rain SDK: balanceOf reverted for token ${token.symbol ?: token.address} (${token.address}) on chain $chainId — omitting from result"
                )
                return@forEachIndexed
            }
            output += try {
                tokenBalance(chainId, token, result.returnData)
            } catch (e: RainError.InternalError) {
                // Per-token failures stay non-fatal on the batched path too: a malformed
                // balanceOf payload omits the token, mirroring the parallel fallback.
                Timber.w(
                    e,
                    "Rain SDK: malformed balanceOf payload for token ${token.symbol ?: token.address} (${token.address}) on chain $chainId — omitting from result"
                )
                return@forEachIndexed
            }
        }
        return output
    }

    // ---------- Parallel fallback path ----------

    /**
     * Fans out `eth_getBalance` (native) and per-token `eth_call balanceOf` requests
     * concurrently via `async`. Native failure is fatal; per-token failures are logged and
     * the token is omitted from the result.
     */
    private suspend fun fetchViaParallelCalls(
        rpcUrl: String,
        chainId: Int,
        walletAddress: String,
        tokens: List<TokenInfo>
    ): List<Balance> = coroutineScope {
        // Native first, on its own — its failure is fatal and shouldn't be swallowed by a
        // group that's also tolerating per-token errors.
        val nativeHex = jsonRpcClient.callForHexResult(
            rpcUrl = rpcUrl,
            method = "eth_getBalance",
            params = listOf(walletAddress, "latest")
        )
        val native = nativeBalance(chainId, nativeHex)

        // `balanceOf(walletAddress)` calldata is identical across every token — encode once.
        val balanceOfCallData = Multicall3.encodeBalanceOf(walletAddress)

        val tokenJobs: List<Pair<TokenInfo, Deferred<Balance?>>> = tokens.map { token ->
            token to async {
                try {
                    val hex = ethCall(rpcUrl, token.address, balanceOfCallData)
                    tokenBalance(chainId, token, hex)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(
                        e,
                        "Rain SDK: balanceOf failed for token ${token.symbol ?: token.address} (${token.address}) — omitting from result"
                    )
                    null
                }
            }
        }

        val output = mutableListOf(native)
        tokenJobs.forEach { (_, deferred) ->
            deferred.await()?.let { output += it }
        }
        output
    }

    // ---------- Balance builders ----------

    /**
     * Builds a native-currency [Balance] from a raw hex wei value, pulling symbol / name /
     * decimals from the static native-currency table.
     */
    private fun nativeBalance(chainId: Int, hex: String): Balance {
        val native = TokenRegistry.nativeCurrency(chainId)
        return Balance(
            token = Token.Native,
            chainId = chainId,
            rawAmount = EthereumConverter.parseHexToBigIntegerStrict(hex),
            decimals = native.decimals,
            symbol = native.symbol,
            name = native.name
        )
    }

    /** Builds a contract-token [Balance] from a raw hex base-unit value and the token's metadata. */
    private fun tokenBalance(chainId: Int, token: TokenInfo, hex: String): Balance =
        Balance(
            token = Token.Contract(token.address),
            chainId = chainId,
            rawAmount = EthereumConverter.parseHexToBigIntegerStrict(hex),
            decimals = token.decimals,
            symbol = token.symbol,
            name = token.name
        )

    // ---------- Helpers ----------

    /**
     * Resolves and validates the RPC URL for [chainId]. Throws [RainError.InvalidConfig] —
     * with the correct chain ID — if the chain isn't configured or its URL is unparseable,
     * so parse failures don't surface from [JsonRpcClient] without chain context.
     */
    private fun resolveRpcUrl(chainId: Int): String {
        val rpcUrl = rpcUrlResolver(chainId)
            ?: throw noRpcEndpointConfigured(chainId)
        if (rpcUrl.toHttpUrlOrNull() == null) {
            throw RainError.InvalidConfig("Invalid RPC URL for chainId=$chainId: $rpcUrl")
        }
        return rpcUrl
    }

    private fun validateAddress(address: String, label: String) {
        if (!address.isValidEthereumAddress) {
            throw RainError.InternalError("Invalid Ethereum $label: $address")
        }
    }
}
