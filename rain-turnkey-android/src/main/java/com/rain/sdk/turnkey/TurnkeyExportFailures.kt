package com.rain.sdk.turnkey

import com.turnkey.core.models.errors.TurnkeyKotlinError
import com.turnkey.crypto.utils.TurnkeyCryptoError

/**
 * Turns whatever an export call threw into what the adapter rethrows. One place, reachable by unit
 * tests without the vendor singleton, because this is the only thing between the vendor's crypto
 * errors and the session coordinator's log line, which records the throwable it receives.
 */
internal object TurnkeyExportFailures {

    /**
     * The caller's own cancellation, unwrapped, when the vendor caught and wrapped it, so the
     * coordinator sees a bare cancellation and logs nothing. Else a cause-free
     * `FailedToExportWallet` when a crypto error is anywhere in the chain, because the vendor's
     * `InvalidHexString` carries the ephemeral decryption key in its message and every wrapper's
     * message repeats its cause's. Else the vendor's own `FailedToExportWallet` unchanged. Else [e]
     * wrapped in one. Both walks are [causeChain], so a cyclic cause cannot spin them.
     */
    fun rethrowable(e: Exception): Throwable {
        val cancellation = e.cancellationInChain()
        val rejected = e.causeChain().firstOrNull { it is TurnkeyCryptoError }
        return when {
            cancellation != null -> cancellation
            rejected != null -> TurnkeyKotlinError.FailedToExportWallet(
                IllegalStateException("export bundle rejected: ${rejected.javaClass.simpleName}")
            )
            e is TurnkeyKotlinError.FailedToExportWallet -> e
            else -> TurnkeyKotlinError.FailedToExportWallet(e)
        }
    }
}
