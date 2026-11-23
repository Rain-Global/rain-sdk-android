package com.rain.sdk.internal.transaction

import com.rain.sdk.error.RainError
import com.rain.sdk.interfaces.CollateralAdminCheck
import com.rain.sdk.interfaces.RainTransactionBuilder
import com.rain.sdk.models.RainTransactionParameters
import com.rain.sdk.provider.WalletProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/**
 * Orchestrates the complete transaction flow.
 *
 * Coordinates between validator, transaction builder, signer, and executor — all of which
 * route through the active [WalletProvider] (Portal or Turnkey) — to handle the entire
 * lifecycle of a transaction from validation to execution.
 */
internal class TransactionCoordinator(
    private val walletProvider: () -> WalletProvider?,
    private val transactionBuilder: RainTransactionBuilder,
    private val validator: TransactionValidator,
    private val signer: TransactionSigner,
    private val executor: TransactionExecutor
) {

    /**
     * Builds a complete, submittable withdrawal transaction: validate → build EIP-712 → sign via the
     * active provider → encode calldata. The one pipeline behind withdraw, prepare, and estimate.
     */
    private suspend fun buildWithdrawalParameters(
        request: WithdrawCollateralRequest
    ): RainTransactionParameters {
        validator.validateWithdrawRequest(request)
        requireCollateralAdmin(request)

        val eip712 = transactionBuilder.buildEIP712Message(
            chainId = request.chainId,
            walletAddress = request.walletAddress,
            addresses = request.addresses,
            amount = request.amount,
            decimals = request.decimals,
            nonce = request.nonce
        )

        val walletSignature = signer.signTypedData(
            chainId = request.chainId,
            walletAddress = request.walletAddress,
            typedDataJson = eip712.message
        )

        val transactionData = transactionBuilder.buildWithdrawTransactionData(
            addresses = request.addresses,
            amount = request.amount,
            decimals = request.decimals,
            executorSignature = request.adminSignature,
            walletSalt = eip712.salt,
            walletSignature = walletSignature
        )

        return RainTransactionParameters(
            from = request.walletAddress,
            to = request.addresses.controllerAddress,
            value = "0x0",
            data = transactionData
        )
    }

    /**
     * Runs [block] with the withdrawal error contract: a simulation revert on this path means the
     * network rejected the withdrawal itself, so it surfaces as [RainError.WithdrawalRevertedByNetwork].
     * Module-visible so the Solana withdrawal path shares the same contract.
     */
    suspend fun <T> withWithdrawalErrors(
        what: String,
        block: suspend () -> T
    ): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: RainError.TransactionSimulationFailed) {
            throw RainError.WithdrawalRevertedByNetwork(cause = e, transactionId = e.transactionId)
        } catch (e: RainError) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw RainError.InternalError("$what failed: ${e.message}", e)
        }
    }

    /**
     * Builds and broadcasts a collateral withdrawal.
     *
     * @return The transaction hash.
     * @throws RainError if any step fails.
     */
    suspend fun executeWithdrawCollateral(
        request: WithdrawCollateralRequest
    ): String = withWithdrawalErrors("Withdraw collateral") {
        val parameters = buildWithdrawalParameters(request)
        executor.sendTransaction(
            chainId = request.chainId,
            from = parameters.from,
            to = parameters.to,
            data = parameters.data,
            value = parameters.value
        )
    }

    /**
     * Builds a collateral withdrawal without broadcasting it.
     *
     * @return Complete transaction parameters — from/to/value/data, not bare calldata.
     * @throws RainError if any step fails.
     */
    suspend fun prepareWithdrawCollateral(
        request: WithdrawCollateralRequest
    ): RainTransactionParameters = withWithdrawalErrors("Prepare withdrawal") {
        buildWithdrawalParameters(request)
    }

    /**
     * Throws [RainError.WalletNotAuthorized] when the signing wallet is not a collateral admin.
     * Only a definitive `false` blocks — an unknown result proceeds, so a check that cannot run
     * never blocks a withdrawal.
     */
    private suspend fun requireCollateralAdmin(request: WithdrawCollateralRequest) {
        val check = transactionBuilder.isCollateralAdmin(
            chainId = request.chainId,
            proxyAddress = request.addresses.proxyAddress,
            walletAddress = request.walletAddress
        )
        when (check) {
            CollateralAdminCheck.NotAdmin -> throw RainError.WalletNotAuthorized(
                walletAddress = request.walletAddress,
                proxyAddress = request.addresses.proxyAddress
            )
            CollateralAdminCheck.Admin, is CollateralAdminCheck.Failed, CollateralAdminCheck.NotCollateral -> Unit
        }
    }

    /**
     * Estimates gas for any transaction using the active wallet provider.
     *
     * @param chainId The chain ID
     * @param from Sender address
     * @param to Target contract address
     * @param data Transaction data (hex-encoded)
     * @return Estimated gas fee in the chain's native token (e.g. ETH/AVAX)
     */
    suspend fun estimateGas(
        chainId: Int,
        from: String,
        to: String,
        data: String
    ): BigDecimal = withContext(Dispatchers.IO) {
        val provider = walletProvider() ?: throw RainError.SdkNotInitialized()
        try {
            provider.estimateTransactionFee(
                chainId = chainId,
                from = from,
                to = to,
                data = data,
                value = "0x0"
            )
        } catch (e: RainError) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw RainError.InternalError("Gas estimation failed: ${e.message}", e)
        }
    }

    /**
     * Estimates the total fee (in the chain's native token) required to execute a collateral
     * withdrawal. Builds the same transaction [executeWithdrawCollateral] would, then asks the
     * active provider to estimate gas for it instead of broadcasting.
     *
     * @return Estimated withdrawal fee in the chain's native token (e.g. ETH/AVAX).
     * @throws RainError if any step fails.
     */
    suspend fun estimateWithdrawalFee(
        request: WithdrawCollateralRequest
    ): BigDecimal = withWithdrawalErrors("Estimate withdrawal fee") {
        val parameters = buildWithdrawalParameters(request)
        val provider = walletProvider() ?: throw RainError.SdkNotInitialized()
        provider.estimateTransactionFee(
            chainId = request.chainId,
            from = parameters.from,
            to = parameters.to,
            data = parameters.data,
            value = parameters.value
        )
    }

    /**
     * Estimates the fee of an already-prepared EVM withdrawal: the provider quotes [parameters] as
     * they are, so nothing is rebuilt or signed. Same error contract as [estimateWithdrawalFee].
     */
    suspend fun estimatePreparedWithdrawalFee(
        chainId: Int,
        parameters: RainTransactionParameters
    ): BigDecimal = withWithdrawalErrors("Estimate prepared withdrawal fee") {
        val provider = walletProvider() ?: throw RainError.SdkNotInitialized()
        provider.estimateTransactionFee(
            chainId = chainId,
            from = parameters.from,
            to = parameters.to,
            data = parameters.data,
            value = parameters.value
        )
    }
}
