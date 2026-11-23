package com.rain.sdk.internal.utils

import com.rain.sdk.internal.RainAdapterApi

/** Returns the string with a leading `"0x"` or `"0X"` prefix removed. */
@RainAdapterApi
fun String.strippingHexPrefix(): String =
    if (startsWith("0x") || startsWith("0X")) substring(2) else this

/**
 * Lightweight syntactic check for a 20-byte Ethereum address: `0x`-optional,
 * exactly 40 hex characters. Does not validate the checksum.
 */
internal val String.isValidEthereumAddress: Boolean
    get() {
        val cleaned = strippingHexPrefix()
        if (cleaned.length != 40) return false
        return cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    }

/** True for the zero address in any casing, with or without a `0x` prefix. */
internal val String.isZeroAddress: Boolean
    get() = strippingHexPrefix().let { it.isNotEmpty() && it.all { c -> c == '0' } }
