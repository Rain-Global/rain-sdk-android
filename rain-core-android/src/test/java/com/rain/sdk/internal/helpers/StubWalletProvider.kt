package com.rain.sdk.internal.helpers

import com.rain.sdk.error.RainError
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import com.rain.sdk.models.UnsignedSolanaTransfer
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.WalletProvider
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Provider-agnostic stub for manager-contract tests. Records calls and returns
 * configured values so tests can verify `RainSdkManager` routes to the active
 * `WalletProvider` and surfaces its result, independent of Portal- or
 * Turnkey-specific behavior.
 */
internal open class StubWalletProvider : WalletProvider {

    data class SendTokenCall(
        val chainId: Int,
        val contractAddress: String,
        val toAddress: String,
        val amount: Double,
        val decimals: Int
    )

    data class GetBalanceCall(
        val chainId: Int,
        val token: Token
    )

    data class GetTransactionsCall(
        val chainId: Int,
        val limit: Int?,
        val offset: Int?,
        val order: RainTransactionOrder?
    )

    data class SignTypedDataCall(
        val chainId: Int,
        val walletAddress: String,
        val typedDataJson: String
    )

    data class SendTransactionCall(
        val chainId: Int,
        val from: String,
        val to: String,
        val data: String,
        val value: String
    )

    data class EstimateTransactionFeeCall(
        val chainId: Int,
        val from: String,
        val to: String,
        val data: String,
        val value: String
    )

    var addressToReturn: String = TestFixtures.WALLET_ADDRESS
    var balanceToReturn: Balance = Balance(
        token = Token.Native,
        chainId = 1,
        rawAmount = BigInteger.ZERO,
        decimals = 18,
        symbol = "ETH",
        name = "Ether"
    )
    var balancesToReturn: List<Balance> = emptyList()
    var transactionsToReturn: List<RainTransaction> = emptyList()
    var sendNativeTokenHashToReturn: String = "0x" + "0".repeat(64)
    var sendTokenHashToReturn: String = "0x" + "0".repeat(64)
    var sendTransactionHashToReturn: String = "0x" + "0".repeat(64)

    /** When set, [sendTransaction] throws it — drives user-rejection and revert paths. */
    var sendTransactionError: Throwable? = null
    var signTypedDataToReturn: String = "0x" + "0".repeat(130)
    var estimateTransactionFeeToReturn: BigDecimal = BigDecimal.ZERO
    var sendSolanaTransactionSignatureToReturn: String = "5" + "1".repeat(87)

    /** What [capabilities] advertises; empty by default, like a minimal provider. */
    var capabilitiesToReturn: Set<Capability> = emptySet()
    override val capabilities: Set<Capability> get() = capabilitiesToReturn

    /** When set, [requireSendSupport] throws it, like a provider that cannot broadcast on the chain. */
    var requireSendSupportError: RainError? = null
    override fun requireSendSupport(chainId: Int) {
        requireSendSupportError?.let { throw it }
    }

    /** Overrides [sponsorsFees]; null keeps the port default, which reads [capabilities]. */
    var sponsorsFeesToReturn: Boolean? = null
    override fun sponsorsFees(chainId: Int): Boolean = sponsorsFeesToReturn ?: super.sponsorsFees(chainId)

    /** Overrides [sponsorsRent]; null keeps the port default, false. */
    var sponsorsRentToReturn: Boolean? = null
    override fun sponsorsRent(chainId: Int): Boolean = sponsorsRentToReturn ?: super.sponsorsRent(chainId)

    val sendNativeTokenCalls = mutableListOf<SendTokenCall>()
    val sendTokenCalls = mutableListOf<SendTokenCall>()
    val getBalanceCalls = mutableListOf<GetBalanceCall>()
    val getBalancesCalls = mutableListOf<Int>()
    val getTransactionsCalls = mutableListOf<GetTransactionsCall>()
    val signTypedDataCalls = mutableListOf<SignTypedDataCall>()
    val sendTransactionCalls = mutableListOf<SendTransactionCall>()
    val estimateTransactionFeeCalls = mutableListOf<EstimateTransactionFeeCall>()
    val sendSolanaTransactionCalls = mutableListOf<Int>()
    val sendSolanaTransactionUnsigned = mutableListOf<UnsignedSolanaTransfer>()

    override suspend fun getWalletAddress(): String = addressToReturn

    override suspend fun sendNativeToken(
        chainId: Int,
        toAddress: String,
        amountInEth: BigDecimal
    ): String {
        sendNativeTokenCalls += SendTokenCall(
            chainId = chainId,
            contractAddress = "",
            toAddress = toAddress,
            amount = amountInEth.toDouble(),
            decimals = 18
        )
        return sendNativeTokenHashToReturn
    }

    override suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        toAddress: String,
        amount: BigDecimal,
        decimals: Int
    ): String {
        sendTokenCalls += SendTokenCall(chainId, contractAddress, toAddress, amount.toDouble(), decimals)
        return sendTokenHashToReturn
    }

    override suspend fun getBalance(chainId: Int, token: Token): Balance {
        getBalanceCalls += GetBalanceCall(chainId, token)
        return balanceToReturn
    }

    override suspend fun getBalances(chainId: Int): List<Balance> {
        getBalancesCalls += chainId
        return balancesToReturn
    }

    override suspend fun getTransactions(
        chainId: Int,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?
    ): List<RainTransaction> {
        getTransactionsCalls += GetTransactionsCall(chainId, limit, offset, order)
        return transactionsToReturn
    }

    override suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String
    ): String {
        signTypedDataCalls += SignTypedDataCall(chainId, walletAddress, typedDataJson)
        return signTypedDataToReturn
    }

    override suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): String {
        sendTransactionCalls += SendTransactionCall(chainId, from, to, data, value)
        sendTransactionError?.let { throw it }
        return sendTransactionHashToReturn
    }

    override suspend fun estimateTransactionFee(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): BigDecimal {
        estimateTransactionFeeCalls += EstimateTransactionFeeCall(chainId, from, to, data, value)
        return estimateTransactionFeeToReturn
    }

    override suspend fun sendSolanaTransaction(
        chainId: Int,
        unsigned: UnsignedSolanaTransfer
    ): String {
        sendSolanaTransactionCalls += chainId
        sendSolanaTransactionUnsigned += unsigned
        return sendSolanaTransactionSignatureToReturn
    }
}
