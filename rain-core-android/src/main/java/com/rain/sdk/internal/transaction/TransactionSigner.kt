package com.rain.sdk.internal.transaction

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.error.ErrorMapper
import com.rain.sdk.provider.WalletProvider
import kotlinx.coroutines.CancellationException

/**
 * Handles transaction signing operations: the signing twin of [TransactionExecutor], delegating
 * to the active [WalletProvider] the same way and mapping signing errors to [RainError] types.
 */
internal class TransactionSigner(
    private val walletProvider: () -> WalletProvider?,
    private val errorMapper: ErrorMapper
) {

    /**
     * Signs the EIP-712 [typedDataJson] with [walletAddress] on [chainId] through the active
     * [WalletProvider] and returns the hex signature. No provider is [RainError.SdkNotInitialized];
     * what the provider throws is mapped by [ErrorMapper.mapSigningError] unless it is already a
     * [RainError].
     */
    suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String
    ): String {
        val provider = walletProvider() ?: throw RainError.SdkNotInitialized()
        return try {
            provider.signTypedData(chainId, walletAddress, typedDataJson)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is RainError) throw e
            throw errorMapper.mapSigningError(e)
        }
    }
}
