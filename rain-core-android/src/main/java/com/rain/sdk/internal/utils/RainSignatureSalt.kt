package com.rain.sdk.internal.utils

import com.rain.sdk.error.RainError
import java.util.Base64

/**
 * The one decoder for the salt in `RainAdminSignature.salt`, shared by the EVM and Solana withdrawal paths so
 * both accept the same shape and fail the same way. Rain signs every withdrawal authorization over a 32-byte
 * salt and returns it as base64. On EVM chains it becomes the `_executorPublisherSalt` argument of
 * `withdrawAsset`; the EIP-712 domain's `bytes32 salt` is a different value, the wallet's own salt from
 * `buildEIP712Message`. On Solana it is the coordinator signature salt: part of the signed domain preimage
 * and a field of the withdraw instruction.
 */
internal object RainSignatureSalt {

    /** Byte length of Rain's authorization salt on every chain. The wallet's EIP-712 domain salt is not this. */
    const val LENGTH = 32

    /**
     * [salt] decoded to its [LENGTH] bytes. A host builds [com.rain.sdk.models.RainAdminSignature] from its
     * own JSON, so a malformed value is its configuration error, not an SDK failure. Basic base64 only: the
     * URL-safe alphabet, embedded whitespace and MIME line breaks are rejected, as both paths always did.
     *
     * @throws RainError.InvalidConfig (RAIN_102) when [salt] is not base64 or decodes to another length.
     */
    fun decode(salt: String): ByteArray {
        val bytes = try {
            Base64.getDecoder().decode(salt)
        } catch (_: IllegalArgumentException) {
            throw RainError.InvalidConfig("RainAdminSignature.salt is not valid base64")
        }
        if (bytes.size != LENGTH) {
            throw RainError.InvalidConfig("RainAdminSignature.salt must be $LENGTH bytes, got ${bytes.size}")
        }
        return bytes
    }
}
