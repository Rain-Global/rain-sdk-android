package com.rain.sdk.internal.transaction

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.error.ErrorMapper
import com.rain.sdk.provider.WalletProvider
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Handles transaction execution operations.
 *
 * Delegates submission to the active [WalletProvider] (Portal or Turnkey) so the same
 * withdraw flow works regardless of which wallet provider is registered.
 * Maps execution errors to appropriate [RainError] types.
 */
internal class TransactionExecutor(
    private val walletProvider: () -> WalletProvider?,
    private val errorMapper: ErrorMapper
) {

    /**
     * Submits [data] from [from] to [to] on [chainId], with [value] in hex wei, through the active
     * [WalletProvider] and returns the transaction hash. Throws [RainError.SdkNotInitialized]
     * without a provider; a vendor failure goes through [ErrorMapper.mapTransactionError], a
     * [RainError] passes untouched.
     */
    suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String = "0x0"
    ): String {
        val provider = walletProvider() ?: throw RainError.SdkNotInitialized()
        return try {
            val txHash = provider.sendTransaction(chainId, from, to, data, value)
            Timber.d("Rain SDK: Transaction submitted successfully. Hash: $txHash")
            txHash
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is RainError) throw e
            Timber.e(e, "Rain SDK: Failed to send transaction")
            throw errorMapper.mapTransactionError(e)
        }
    }
}
