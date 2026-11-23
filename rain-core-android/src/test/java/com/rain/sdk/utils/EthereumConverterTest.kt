package com.rain.sdk.utils

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Covers the precise BigInteger converters added for the rich balance API — exact hex
 * parsing, ERC-20 `decimals()` / `symbol()` decoding, and non-18-decimal raw-amount
 * reconstruction (the inverse used when a provider only exposes a formatted decimal).
 */
class EthereumConverterTest {

    @Test
    fun `convertWeiHexToDecimal converts wei hex to exact ETH units`() {
        // 1 ETH in wei
        assertThat(EthereumConverter.convertWeiHexToDecimal("0x0de0b6b3a7640000"))
            .isEqualToIgnoringScale(java.math.BigDecimal.ONE)
        // A value Double cannot represent exactly: 1.000000000000000001 ETH
        assertThat(EthereumConverter.convertWeiHexToDecimal("0x0de0b6b3a7640001"))
            .isEqualToIgnoringScale(java.math.BigDecimal("1.000000000000000001"))
        assertThat(EthereumConverter.convertWeiHexToDecimal("0x"))
            .isEqualToIgnoringScale(java.math.BigDecimal.ZERO)
    }

    @Test
    fun `convertEthToWeiHex scales exactly and hex-encodes`() {
        assertThat(EthereumConverter.convertEthToWeiHex(BigDecimal.ONE, 18))
            .isEqualTo("0xde0b6b3a7640000")
        // 1 wei, the finest amount the chain can represent.
        assertThat(EthereumConverter.convertEthToWeiHex(BigDecimal("0.000000000000000001"), 18))
            .isEqualTo("0x1")
        assertThat(EthereumConverter.convertEthToWeiHex(BigDecimal.ZERO, 18)).isEqualTo("0x0")
    }

    @Test
    fun `convertEthToWeiHex scales by the supplied decimals rather than a fixed 18`() {
        // A 9-decimal native currency: 1.5 units = 1_500_000_000 base units.
        assertThat(EthereumConverter.convertEthToWeiHex(BigDecimal("1.5"), 9))
            .isEqualTo("0x${BigInteger("1500000000").toString(16)}")
        // Precision that is fine at 18 decimals is over-precise at 9.
        assertThrows(RainError.InvalidAmount::class.java) {
            EthereumConverter.convertEthToWeiHex(BigDecimal("0.0000000001"), 9)
        }
    }

    @Test
    fun `convertEthToWeiHex rejects a negative amount instead of emitting malformed hex`() {
        // "-1" would otherwise render as "0x-de0b6b3a7640000" and ship into eth_estimateGas.
        val ex = assertThrows(RainError.InvalidAmount::class.java) {
            EthereumConverter.convertEthToWeiHex(BigDecimal("-1"), 18)
        }
        assertThat(ex.amount).isEqualTo("-1")
    }

    @Test
    fun `convertEthToWeiHex rejects sub-wei precision instead of truncating`() {
        assertThrows(RainError.InvalidAmount::class.java) {
            EthereumConverter.convertEthToWeiHex(BigDecimal("0.0000000000000000015"), 18)
        }
    }

    @Test
    fun `convertWeiToEthDecimal converts a wei BigInteger to exact ETH units`() {
        assertThat(EthereumConverter.convertWeiToEthDecimal(BigInteger("420000000000000")))
            .isEqualToIgnoringScale(java.math.BigDecimal("0.00042"))
        assertThat(EthereumConverter.convertWeiToEthDecimal(BigInteger("1000000000000000001")))
            .isEqualToIgnoringScale(java.math.BigDecimal("1.000000000000000001"))
    }

    @Test
    fun `parseHexToBigIntegerStrict parses well-formed payloads exactly`() {
        assertThat(EthereumConverter.parseHexToBigIntegerStrict("0x0")).isEqualTo(BigInteger.ZERO)
        assertThat(EthereumConverter.parseHexToBigIntegerStrict("0x0de0b6b3a7640000"))
            .isEqualTo(BigInteger("1000000000000000000"))
    }

    @Test
    fun `parseHexToBigIntegerStrict throws InternalError on malformed payloads instead of a silent zero`() {
        listOf("not-hex", "0xZZ", "", "0x").forEach { bad ->
            val error = runCatching { EthereumConverter.parseHexToBigIntegerStrict(bad) }.exceptionOrNull()
            assertThat(error).isInstanceOf(RainError.InternalError::class.java)
            assertThat(error).hasMessageThat().contains("Malformed hex payload")
        }
    }

    @Test
    fun `parseHexToIntStrict parses decimals responses`() {
        assertThat(EthereumConverter.parseHexToIntStrict("0x" + "6".padStart(64, '0'))).isEqualTo(6)
        assertThat(EthereumConverter.parseHexToIntStrict("0x12")).isEqualTo(18)
    }

    @Test
    fun `parseHexToIntStrict throws InternalError instead of narrowing to a negative Int`() {
        // A hostile or malformed decimals() must never produce a negative decimals, which would
        // flip Balance.decimalAmount from a divide into a multiply. The strict parser throws.
        listOf("0xffffffff", "0x" + "f".repeat(64)).forEach { bad ->
            val error = runCatching { EthereumConverter.parseHexToIntStrict(bad) }.exceptionOrNull()
            assertThat(error).isInstanceOf(RainError.InternalError::class.java)
            assertThat(error).hasMessageThat().contains("does not fit in a non-negative Int")
        }
    }

    @Test
    fun `decimalStringToBigInteger reconstructs non-18-decimal raw amounts`() {
        // 1.5 USDC at 6 decimals -> 1_500_000
        assertThat(EthereumConverter.decimalStringToBigInteger("1.5", 6))
            .isEqualTo(BigInteger("1500000"))
        // 2 DAI at 18 decimals
        assertThat(EthereumConverter.decimalStringToBigInteger("2", 18))
            .isEqualTo(BigInteger("2000000000000000000"))
        // Truncates extra precision (round-down).
        assertThat(EthereumConverter.decimalStringToBigInteger("1.2345678", 6))
            .isEqualTo(BigInteger("1234567"))
    }

    @Test
    fun `decimalStringToBigInteger returns zero for empty, invalid, or non-positive input`() {
        assertThat(EthereumConverter.decimalStringToBigInteger(null, 6)).isEqualTo(BigInteger.ZERO)
        assertThat(EthereumConverter.decimalStringToBigInteger("", 6)).isEqualTo(BigInteger.ZERO)
        assertThat(EthereumConverter.decimalStringToBigInteger("not-a-number", 6)).isEqualTo(BigInteger.ZERO)
        assertThat(EthereumConverter.decimalStringToBigInteger("-5", 6)).isEqualTo(BigInteger.ZERO)
    }

    @Test
    fun `parseHexToString decodes an ABI-encoded symbol`() {
        // [offset=0x20][length=4]["USDC" right-padded to 32 bytes]
        val symbolHex = "0x" +
            "20".padStart(64, '0') +
            "4".padStart(64, '0') +
            "55534443".padEnd(64, '0')
        assertThat(EthereumConverter.parseHexToString(symbolHex)).isEqualTo("USDC")
        // Too short / malformed → null.
        assertThat(EthereumConverter.parseHexToString("0x1234")).isNull()
    }

    @Test
    fun `parseHexToString returns null when the length word does not fit the payload`() {
        val offset = "20".padStart(64, '0')
        // 0x40000000 bytes: doubling that length overflows an Int, which used to throw.
        assertThat(EthereumConverter.parseHexToString("0x$offset" + "40000000".padStart(64, '0'))).isNull()
        // 2^32 + 5 bytes: narrowing to Int would read it as 5 and decode the first five of 32 bytes.
        val wrapped = "0x$offset" + "100000005".padStart(64, '0') + "55".repeat(32)
        assertThat(EthereumConverter.parseHexToString(wrapped)).isNull()
    }

    @Test
    fun `the hex converters throw InternalError on non-hex input and read an empty payload as zero`() {
        assertThrows(RainError.InternalError::class.java) { EthereumConverter.convertWeiHexToDecimal("0xzz") }
        assertThrows(RainError.InternalError::class.java) { EthereumConverter.convertHexToDecimal("0xzz", 6) }
        assertThat(EthereumConverter.convertHexToDecimal("0x", 6)).isEqualToIgnoringScale(BigDecimal.ZERO)
        assertThat(EthereumConverter.convertHexToDecimal("0x0f4240", 6)).isEqualToIgnoringScale(BigDecimal.ONE)
    }

    @Test
    fun `normalizedHexString defaults only null, unprefixed and empty values`() {
        assertThat(EthereumConverter.normalizedHexString(null)).isEqualTo("0x0")
        assertThat(EthereumConverter.normalizedHexString("0x")).isEqualTo("0x0")
        assertThat(EthereumConverter.normalizedHexString("1a")).isEqualTo("0x0")
        assertThat(EthereumConverter.normalizedHexString("0x1a")).isEqualTo("0x1a")
        // The digits are not checked here; the parser that reads the value rejects them.
        assertThat(EthereumConverter.normalizedHexString("0xzz")).isEqualTo("0xzz")
    }
}
