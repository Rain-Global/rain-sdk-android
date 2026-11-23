package com.rain.sdk.internal.utils

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.RainAdapterApi
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

internal object RainHexUtils {
    private const val ADDRESS_LENGTH_IN_HEX = 40

    /**
     * Validates an EVM address and returns it in EIP-55 checksum form. A mixed-case input must
     * already carry the right checksum: its letter case is EIP-55's typo check, a mistyped
     * character almost always breaks it, and funds sent to a mistyped address can't be recovered.
     * All-lowercase and all-uppercase inputs carry no checksum and are normalized.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`) when [address] is not 40 hex characters with an
     *   optional lowercase `0x` prefix, or when its mixed-case checksum does not match.
     */
    fun validateAndChecksum(address: String, paramName: String): String {
        if (!isValidAddress(address)) {
            throw RainError.InvalidConfig("Invalid $paramName format: $address")
        }
        if (!hasValidChecksum(address)) {
            throw RainError.InvalidConfig("Invalid $paramName checksum: $address")
        }
        return toChecksumAddress(address)
    }

    /**
     * True when [address], already checked for shape, is all one letter case or matches its EIP-55
     * checksum. A `0x` or `0X` prefix is optional.
     */
    fun hasValidChecksum(address: String): Boolean {
        val body = address.strippingHexPrefix()
        val mixedCase = body.any { it.isUpperCase() } && body.any { it.isLowerCase() }
        return !mixedCase || body == toChecksumAddress(body).strippingHexPrefix()
    }

    /**
     * Converts a hex string (optional "0x" prefix) to bytes, rejecting odd lengths and non-hex
     * characters. Pass [expectedByteCount] to also enforce an exact size (e.g. 65 for signatures).
     */
    fun hexToBytes(s: String, expectedByteCount: Int? = null): ByteArray {
        val cleanS = Numeric.cleanHexPrefix(s)
        val len = cleanS.length
        if (len % 2 != 0) {
            throw RainError.InvalidConfig("Invalid hex string: odd length ($len)")
        }
        if (expectedByteCount != null && len / 2 != expectedByteCount) {
            throw RainError.InvalidConfig(
                "Invalid hex string: expected $expectedByteCount bytes, got ${len / 2}"
            )
        }
        val data = ByteArray(len / 2)
        for (i in cleanS.indices step 2) {
            val hi = Character.digit(cleanS[i], 16)
            val lo = Character.digit(cleanS[i + 1], 16)
            if (hi < 0 || lo < 0) {
                throw RainError.InvalidConfig("Invalid hex string: non-hex character at index $i")
            }
            data[i / 2] = ((hi shl 4) + lo).toByte()
        }
        return data
    }

    /**
     * Validates if the string is a valid Ethereum address format.
     */
    fun isValidAddress(address: String): Boolean {
        return try {
            val cleanAddress = Numeric.cleanHexPrefix(address)
            cleanAddress.length == ADDRESS_LENGTH_IN_HEX && cleanAddress.matches(Regex("^[0-9a-fA-F]+$"))
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Converts an address to its checksummed format (EIP-55).
     */
    fun toChecksumAddress(address: String): String {
        return Keys.toChecksumAddress(address)
    }
}

/**
 * Validates an EVM address a host hands an adapter and returns it in EIP-55 checksum form. Adapters
 * call it on a `walletAddress` override at construction, so a typo fails there rather than as a
 * foreign address on every read. It applies [RainHexUtils.validateAndChecksum]'s rules and also
 * accepts an uppercase `0X` prefix.
 *
 * @throws RainError.InvalidConfig (`RAIN_102`) when [address] is not 40 hex characters with an
 *   optional `0x` or `0X` prefix, or when its mixed-case checksum does not match.
 */
@RainAdapterApi
fun validateAndChecksumAddress(address: String, paramName: String): String =
    // The shape check recognises only a lowercase prefix; normalise first so `0X` is accepted.
    RainHexUtils.validateAndChecksum("0x${address.strippingHexPrefix()}", paramName)
