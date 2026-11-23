package com.rain.sdk.internal.error

import com.rain.sdk.error.RainError
import timber.log.Timber

/**
 * Centralized error mapping for Rain SDK.
 *
 * Maps the failures core itself can raise on a wallet call — web3j, network I/O, unexpected
 * shapes — to standardized [RainError] types. Vendor exceptions never reach it: each adapter
 * module converts its own vendor's failures to a [RainError] before they leave the adapter, and
 * every call site here rethrows a [RainError] untouched before mapping anything. Core imports no
 * vendor type.
 */
internal class ErrorMapper {

    /**
     * Maps signing-related errors to appropriate RainError types.
     *
     * @param e The exception thrown during signing operation
     * @return Mapped RainError
     */
    fun mapSigningError(e: Exception): RainError {
        Timber.e(e, "Rain SDK: Signing error")
        return classify(e)
    }

    /** As [mapSigningError], for a failure raised while a transaction was being submitted. */
    fun mapTransactionError(e: Exception): RainError {
        Timber.e(e, "Rain SDK: Transaction execution error")
        return classify(e)
    }

    /**
     * Maps a failure raised while a provider materializes its wallet, so a problem at init time
     * surfaces through the same error contract as every later call.
     */
    fun mapProviderInitError(e: Exception): RainError {
        Timber.e(e, "Rain SDK: Provider initialization error")
        return classify(e)
    }

    /**
     * The shared prose heuristics decide, and [RainError.ProviderError] is the floor. Typed
     * classification happens in the adapters, where the vendor types are known.
     */
    private fun classify(e: Exception): RainError =
        VendorErrorClassifier.fromVendorError(e) ?: RainError.ProviderError(e)
}
