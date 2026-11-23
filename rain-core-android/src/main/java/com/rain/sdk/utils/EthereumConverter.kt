package com.rain.sdk.utils

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.utils.RainAmountUtils
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Utility for converting between different Ethereum units and formats.
 *
 * The unit-conversion functions are pure (no Portal/Turnkey/web3j dependencies) and safe to
 * call from any layer. Provider-result parsing lives in the adapter modules (e.g. `rain-portal-android`'s
 * `PortalProviderResult` extensions), so this converter carries no vendor dependency.
 */
object EthereumConverter {

    /**
     * Returns [hex] when it starts with `0x` and has anything after it, else `"0x0"`. The digits
     * are not checked, so a malformed value still fails in the parser that reads it. Used by the
     * Portal adapter before parsing.
     */
    fun normalizedHexString(hex: String?): String =
        hex?.takeIf { it.startsWith("0x") && it.length > 2 } ?: "0x0"

    /**
     * Converts a Wei hex string to an exact ETH-unit [BigDecimal] (18 decimals). An empty payload
     * (`""` or `"0x"`) reads as zero.
     *
     * @throws RainError.InternalError on non-hex input
     */
    fun convertWeiHexToDecimal(weiHexValue: String): BigDecimal =
        lenientHexToBigInteger(weiHexValue).toBigDecimal().movePointLeft(18)

    /** [convertWeiHexToDecimal] for a wei value already parsed into a [BigInteger]. */
    fun convertWeiToEthDecimal(wei: BigInteger): BigDecimal =
        wei.toBigDecimal().movePointLeft(18)

    /**
     * Converts a native-currency amount to a base-unit (wei) hex string (exact base-10 scaling).
     *
     * [decimals] comes from the chain's registry entry (`tokenStore.nativeCurrency(chainId)`),
     * never a hardcoded 18, so a non-18-decimal native currency scales correctly.
     *
     * This value ships straight into `eth_estimateGas` and the provider send body, so it takes
     * the same guards as every other money path: a negative amount would produce malformed hex
     * ("0x-..."), and sub-base-unit precision would be truncated silently.
     *
     * @throws RainError.InvalidAmount if [ethBalance] is negative, carries more than [decimals]
     *         decimal places or exceeds uint256, or if [decimals] is outside 0..77
     */
    fun convertEthToWeiHex(ethBalance: BigDecimal, decimals: Int): String {
        val wei = RainAmountUtils.toBaseUnits(ethBalance, decimals)
        return "0x${wei.toString(16)}"
    }

    /**
     * Converts a hex string to an exact [BigDecimal] with the specified number of decimals; an
     * empty payload reads as [convertWeiHexToDecimal] reads it.
     *
     * @throws RainError.InternalError as for [convertWeiHexToDecimal]
     */
    fun convertHexToDecimal(hex: String, decimals: Int): BigDecimal =
        lenientHexToBigInteger(hex).toBigDecimal().movePointLeft(decimals)

    /** Reads [hex] for the two converters above: an empty payload is zero, non-hex input throws. */
    private fun lenientHexToBigInteger(hex: String): BigInteger {
        val cleaned = hex.removePrefix("0x").ifEmpty { "0" }
        return try {
            BigInteger(cleaned, 16)
        } catch (e: NumberFormatException) {
            throw RainError.InternalError("Malformed hex payload: \"$hex\"", e)
        }
    }

    /**
     * Converts a hex-encoded uint256 string to an exact [BigInteger], throwing on malformed input.
     *
     * The parser for money paths (`eth_getBalance`,
     * `eth_call balanceOf`, gas reads), where a garbage RPC response must surface as an error
     * rather than a silent zero balance or fee. Accepts an optional `0x`/`0X` prefix; `"0x0"`
     * parses to [BigInteger.ZERO].
     *
     * @throws RainError.InternalError on non-hex input or an empty/blank payload (`""` or `"0x"`)
     */
    fun parseHexToBigIntegerStrict(hex: String): BigInteger {
        val cleaned = hex.removePrefix("0x").removePrefix("0X")
        if (cleaned.isBlank()) {
            throw RainError.InternalError("Malformed hex payload: \"$hex\"")
        }
        return try {
            BigInteger(cleaned, 16)
        } catch (e: NumberFormatException) {
            throw RainError.InternalError("Malformed hex payload: \"$hex\"", e)
        }
    }

    /**
     * Strict decoder for ABI uint values that must fit in a non-negative [Int].
     *
     * @throws RainError.InternalError on malformed hex or a value outside 0..Int.MAX_VALUE
     */
    fun parseHexToIntStrict(hex: String): Int {
        val value = parseHexToBigIntegerStrict(hex)
        if (value.signum() < 0 || value.bitLength() > 31) {
            throw RainError.InternalError("Hex value does not fit in a non-negative Int: $hex")
        }
        return value.toInt()
    }

    /**
     * Decodes an ABI-encoded string returned by `eth_call` (e.g. from ERC-20 `symbol()`).
     *
     * ABI string layout (each slot = 32 bytes = 64 hex chars):
     *   slot 0 — offset to data (usually 0x20)
     *   slot 1 — byte length of string
     *   slot 2+ — UTF-8 string bytes, right-padded to a 32-byte boundary
     *
     * Returns `null` if the payload is too short or malformed.
     */
    fun parseHexToString(hex: String): String? {
        val cleaned = hex.removePrefix("0x").removePrefix("0X")
        if (cleaned.length < 128) return null

        val lengthHex = cleaned.substring(64, 128)
        val length = try {
            BigInteger(lengthHex, 16)
        } catch (e: NumberFormatException) {
            return null
        }
        // Compared as a BigInteger before narrowing: toInt() would wrap a length word past Int range,
        // and doubling a length of 0x40000000 or more overflows an Int.
        val availableBytes = (cleaned.length - 128) / 2
        if (length.signum() <= 0 || length > BigInteger.valueOf(availableBytes.toLong())) return null
        val byteLength = length.toInt()

        val dataHex = cleaned.substring(128, 128 + byteLength * 2)

        val bytes = ByteArray(byteLength)
        var i = 0
        while (i < byteLength) {
            val byteHex = dataHex.substring(i * 2, i * 2 + 2)
            bytes[i] = try {
                byteHex.toInt(16).toByte()
            } catch (e: NumberFormatException) {
                return null
            }
            i += 1
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Reconstructs an exact base-unit [BigInteger] from a human-readable decimal string.
     *
     * The inverse of [convertHexToDecimal]: multiplies by `10^decimals` and truncates any
     * remaining fractional part (round-down). Used where a provider only exposes a formatted
     * decimal balance (e.g. Portal's `getAssets`, Turnkey's supported-chain API) rather than
     * raw hex. Returns [BigInteger.ZERO] on parse failure or a non-positive result.
     */
    fun decimalStringToBigInteger(decimalString: String?, decimals: Int): BigInteger {
        if (decimalString.isNullOrEmpty()) return BigInteger.ZERO
        return try {
            val baseUnits = BigDecimal(decimalString)
                .movePointRight(decimals)
                .setScale(0, RoundingMode.DOWN)
                .toBigInteger()
            if (baseUnits > BigInteger.ZERO) baseUnits else BigInteger.ZERO
        } catch (e: NumberFormatException) {
            BigInteger.ZERO
        }
    }
}
