package com.rain.sdk.internal.helpers

import com.rain.sdk.internal.network.chainreader.ChainReader
import com.rain.sdk.internal.network.chainreader.MinedReceipt
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import java.math.BigDecimal
import java.math.BigInteger

/**
 * In-memory [ChainReader] stub for routing tests in `TurnkeyWalletProvider*Test` and
 * `RainSdkManager*Test`. Records every call so tests can assert that the adapter routed the
 * request through here rather than through Turnkey's balance indexer.
 */
internal class MockChainReader(
    var nativeBalance: BigDecimal = BigDecimal.ZERO,
    var erc20Balance: BigDecimal = BigDecimal.ZERO,
    var balances: List<Balance> = emptyList(),
    var balance: Balance? = null,
    var decimals: Int = 18,
    var symbol: String? = null,
    var name: String? = null,
    /** When set, every metadata read (decimals/symbol/name) throws it — drives failed-read paths. */
    var metadataError: Throwable? = null,
    /** When set, only `symbol()` throws it — a token whose decimals resolve but whose symbol read fails. */
    var symbolError: Throwable? = null,
    /** When set, only `name()` throws it. */
    var nameError: Throwable? = null,
    var allowance: BigInteger = BigInteger.ZERO,
    /** When set, the allowance read throws it. */
    var allowanceError: Throwable? = null,
    /**
     * Allowance per block tag, for tests that need the chain to hold different state at different
     * blocks. A read whose `atBlock` is absent here falls back to [allowance] — so a test can seed
     * the post-transaction value under the receipt's block and leave [allowance] at the
     * pre-transaction value, which is exactly the production lag this models.
     */
    var allowanceByBlock: Map<String, BigInteger> = emptyMap(),
    /**
     * Consumed one entry per allowance read; a non-null entry is thrown. Models a node that does
     * not yet have the requested block and so errors instead of answering.
     */
    val allowanceFailures: MutableList<Throwable?> = mutableListOf(),
    /** Consumed one entry per receipt read; a non-null entry is thrown. Models a node blip mid-poll. */
    val receiptFailures: MutableList<Throwable?> = mutableListOf(),
    var receiptStatus: Boolean? = true,
    /**
     * Consumed one entry per receipt read before falling back to [receiptStatus], so a test can
     * drive a pending-then-mined poll.
     */
    val receiptStatuses: MutableList<Boolean?> = mutableListOf(),
    /** Block the mined receipt reports — the tag a confirmation read is expected to pin to. */
    var receiptBlockNumber: String = "0x10"
) : ChainReader {

    data class NativeCall(val chainId: Int, val walletAddress: String)
    data class Erc20Call(
        val chainId: Int,
        val tokenAddress: String,
        val walletAddress: String,
        val decimals: Int?
    )
    data class BalancesCall(
        val chainId: Int,
        val walletAddress: String,
        val tokens: List<TokenInfo>
    )
    data class BalanceCall(
        val chainId: Int,
        val walletAddress: String,
        val token: Token,
        val tokenInfo: TokenInfo?
    )
    data class DecimalsCall(val chainId: Int, val tokenAddress: String)
    data class SymbolCall(val chainId: Int, val tokenAddress: String)
    data class NameCall(val chainId: Int, val tokenAddress: String)
    data class AllowanceCall(
        val chainId: Int,
        val tokenAddress: String,
        val owner: String,
        val spender: String,
        val atBlock: String
    )
    data class ReceiptCall(val chainId: Int, val transactionHash: String)

    val nativeCalls = mutableListOf<NativeCall>()
    val erc20Calls = mutableListOf<Erc20Call>()
    val balancesCalls = mutableListOf<BalancesCall>()
    val balanceCalls = mutableListOf<BalanceCall>()
    val decimalsCalls = mutableListOf<DecimalsCall>()
    val symbolCalls = mutableListOf<SymbolCall>()
    val nameCalls = mutableListOf<NameCall>()
    val allowanceCalls = mutableListOf<AllowanceCall>()
    val receiptCalls = mutableListOf<ReceiptCall>()

    override suspend fun getNativeBalance(chainId: Int, walletAddress: String): BigDecimal {
        nativeCalls += NativeCall(chainId, walletAddress)
        return nativeBalance
    }

    override suspend fun getERC20Balance(
        chainId: Int,
        tokenAddress: String,
        walletAddress: String,
        decimals: Int?
    ): BigDecimal {
        erc20Calls += Erc20Call(chainId, tokenAddress, walletAddress, decimals)
        return erc20Balance
    }

    override suspend fun getBalances(
        chainId: Int,
        walletAddress: String,
        tokens: List<TokenInfo>
    ): List<Balance> {
        balancesCalls += BalancesCall(chainId, walletAddress, tokens)
        return balances
    }

    override suspend fun getBalance(
        chainId: Int,
        walletAddress: String,
        token: Token,
        tokenInfo: TokenInfo?
    ): Balance {
        balanceCalls += BalanceCall(chainId, walletAddress, token, tokenInfo)
        return balance ?: Balance(
            token = token,
            chainId = chainId,
            rawAmount = BigInteger.ZERO,
            decimals = tokenInfo?.decimals ?: 18,
            symbol = tokenInfo?.symbol,
            name = tokenInfo?.name
        )
    }

    override suspend fun getDecimals(chainId: Int, tokenAddress: String): Int {
        decimalsCalls += DecimalsCall(chainId, tokenAddress)
        metadataError?.let { throw it }
        return decimals
    }

    override suspend fun getSymbol(chainId: Int, tokenAddress: String): String? {
        symbolCalls += SymbolCall(chainId, tokenAddress)
        metadataError?.let { throw it }
        symbolError?.let { throw it }
        return symbol
    }

    override suspend fun getName(chainId: Int, tokenAddress: String): String? {
        nameCalls += NameCall(chainId, tokenAddress)
        metadataError?.let { throw it }
        nameError?.let { throw it }
        return name
    }

    override suspend fun getErc20Allowance(
        chainId: Int,
        tokenAddress: String,
        owner: String,
        spender: String,
        atBlock: String
    ): BigInteger {
        allowanceCalls += AllowanceCall(chainId, tokenAddress, owner, spender, atBlock)
        if (allowanceFailures.isNotEmpty()) allowanceFailures.removeAt(0)?.let { throw it }
        allowanceError?.let { throw it }
        return allowanceByBlock[atBlock] ?: allowance
    }

    override suspend fun getTransactionReceipt(
        chainId: Int,
        transactionHash: String
    ): MinedReceipt? {
        receiptCalls += ReceiptCall(chainId, transactionHash)
        if (receiptFailures.isNotEmpty()) receiptFailures.removeAt(0)?.let { throw it }
        val status =
            if (receiptStatuses.isNotEmpty()) receiptStatuses.removeAt(0) else receiptStatus
        return status?.let { MinedReceipt(succeeded = it, blockNumber = receiptBlockNumber) }
    }
}
