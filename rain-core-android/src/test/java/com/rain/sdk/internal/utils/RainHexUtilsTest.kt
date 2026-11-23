package com.rain.sdk.internal.utils

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import org.junit.Assert.assertThrows
import org.junit.Test

class RainHexUtilsTest {

    @Test
    fun `hexToBytes decodes with and without 0x prefix`() {
        assertThat(RainHexUtils.hexToBytes("0xdeadbeef"))
            .isEqualTo(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()))
        assertThat(RainHexUtils.hexToBytes("00ff"))
            .isEqualTo(byteArrayOf(0x00, 0xff.toByte()))
    }

    @Test
    fun `hexToBytes rejects odd-length input`() {
        val ex = assertThrows(RainError.InvalidConfig::class.java) {
            RainHexUtils.hexToBytes("0xabc")
        }
        assertThat(ex.message).contains("odd length")
    }

    @Test
    fun `hexToBytes rejects non-hex characters instead of corrupting bytes`() {
        assertThrows(RainError.InvalidConfig::class.java) {
            RainHexUtils.hexToBytes("0xzz11")
        }
    }

    @Test
    fun `hexToBytes enforces an expected byte count when given`() {
        // 64 bytes offered where a 65-byte signature is required.
        val ex = assertThrows(RainError.InvalidConfig::class.java) {
            RainHexUtils.hexToBytes("ab".repeat(64), expectedByteCount = 65)
        }
        assertThat(ex.message).contains("expected 65 bytes")

        assertThat(RainHexUtils.hexToBytes("ab".repeat(65), expectedByteCount = 65)).hasLength(65)
    }

    // ---- EIP-55 checksum ------------------------------------------------------------------------

    @Test
    fun `validateAndChecksum passes a correctly checksummed address through unchanged`() {
        EIP55_VECTORS.forEach { vector ->
            assertThat(RainHexUtils.validateAndChecksum(vector, "recipientAddress")).isEqualTo(vector)
        }
    }

    @Test
    fun `validateAndChecksum normalizes an address written in a single letter case`() {
        EIP55_VECTORS.forEach { vector ->
            val body = vector.removePrefix("0x")
            assertThat(RainHexUtils.validateAndChecksum("0x" + body.lowercase(), "recipientAddress")).isEqualTo(vector)
            assertThat(RainHexUtils.validateAndChecksum("0x" + body.uppercase(), "recipientAddress")).isEqualTo(vector)
        }
    }

    @Test
    fun `validateAndChecksum refuses a mixed-case address whose checksum doesn't match`() {
        val error = assertThrows(RainError.InvalidConfig::class.java) {
            RainHexUtils.validateAndChecksum(WRONG_CHECKSUM, "recipientAddress")
        }

        assertThat(error.errorCode).isEqualTo(RainErrorCode.INVALID_CONFIG)
        assertThat(error).hasMessageThat().contains("recipientAddress checksum")
    }

    @Test
    fun `validateAndChecksum still reports a malformed address as a format error`() {
        val error = assertThrows(RainError.InvalidConfig::class.java) {
            RainHexUtils.validateAndChecksum("0x1234", "recipientAddress")
        }

        assertThat(error).hasMessageThat().contains("recipientAddress format")
    }

    @Test
    fun `hasValidChecksum accepts a single letter case or the right checksum, with or without a prefix`() {
        val vector = EIP55_VECTORS.first()
        val body = vector.removePrefix("0x")

        assertThat(RainHexUtils.hasValidChecksum(vector)).isTrue()
        assertThat(RainHexUtils.hasValidChecksum(body)).isTrue()
        assertThat(RainHexUtils.hasValidChecksum("0X$body")).isTrue()
        assertThat(RainHexUtils.hasValidChecksum("0x" + body.lowercase())).isTrue()
        assertThat(RainHexUtils.hasValidChecksum("0x" + body.uppercase())).isTrue()
        assertThat(RainHexUtils.hasValidChecksum(WRONG_CHECKSUM)).isFalse()
        assertThat(RainHexUtils.hasValidChecksum(WRONG_CHECKSUM.removePrefix("0x"))).isFalse()
    }

    private companion object {
        /** The mixed-case reference vectors from EIP-55. */
        val EIP55_VECTORS = listOf(
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
            "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
            "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
        )

        /** The first vector with its first letter's case flipped: the same bytes, a wrong checksum. */
        const val WRONG_CHECKSUM = "0x5AAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    }
}
