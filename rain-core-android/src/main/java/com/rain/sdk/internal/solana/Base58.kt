package com.rain.sdk.internal.solana

import com.rain.sdk.internal.RainAdapterApi
import java.math.BigInteger

/**
 * Base58 codec using the Bitcoin / Solana alphabet.
 *
 * Solana account addresses, blockhashes and transaction signatures are Base58 strings of
 * 32- or 64-byte values. EVM uses hex, so the SDK had no Base58 until Solana support — and
 * neither web3j nor the JDK ships one, hence this small, dependency-free implementation.
 *
 * Leading zero bytes map to leading `'1'` characters (and back), matching the reference
 * implementations.
 */
@RainAdapterApi
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58L)

    private val INDEXES = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c -> table[c.code] = i }
    }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""

        var leadingZeros = 0
        while (leadingZeros < input.size && input[leadingZeros].toInt() == 0) leadingZeros++

        val sb = StringBuilder()
        var value = BigInteger(1, input)
        while (value.signum() > 0) {
            val divmod = value.divideAndRemainder(BASE)
            value = divmod[0]
            sb.append(ALPHABET[divmod[1].toInt()])
        }
        repeat(leadingZeros) { sb.append(ALPHABET[0]) }
        return sb.reverse().toString()
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)

        var value = BigInteger.ZERO
        for (c in input) {
            val digit = if (c.code < 128) INDEXES[c.code] else -1
            require(digit >= 0) { "Invalid Base58 character '$c'" }
            value = value.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }

        // BigInteger.toByteArray() may prepend a 0x00 sign byte; strip it. When the value is
        // zero the numeric part is empty — any leading zeros are carried by the '1' count below.
        val numericBytes = if (value.signum() == 0) {
            ByteArray(0)
        } else {
            val raw = value.toByteArray()
            if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        }

        var leadingOnes = 0
        for (c in input) {
            if (c == ALPHABET[0]) leadingOnes++ else break
        }

        val result = ByteArray(leadingOnes + numericBytes.size)
        System.arraycopy(numericBytes, 0, result, leadingOnes, numericBytes.size)
        return result
    }
}
