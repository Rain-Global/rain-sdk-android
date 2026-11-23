package com.rain.sdk.internal.solana

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class Base58Test {

    @Test
    fun `empty round-trips to empty`() {
        assertThat(Base58.encode(ByteArray(0))).isEmpty()
        assertThat(Base58.decode("")).isEqualTo(ByteArray(0))
    }

    @Test
    fun `single-byte vectors`() {
        assertThat(Base58.encode(byteArrayOf(0))).isEqualTo("1")
        assertThat(Base58.encode(byteArrayOf(1))).isEqualTo("2")
        assertThat(Base58.encode(byteArrayOf(57))).isEqualTo("z")
        // 58 = 1*58 + 0 -> "21"
        assertThat(Base58.encode(byteArrayOf(58))).isEqualTo("21")

        assertThat(Base58.decode("1")).isEqualTo(byteArrayOf(0))
        assertThat(Base58.decode("2")).isEqualTo(byteArrayOf(1))
        assertThat(Base58.decode("z")).isEqualTo(byteArrayOf(57))
        assertThat(Base58.decode("21")).isEqualTo(byteArrayOf(58))
    }

    @Test
    fun `leading zero bytes map to leading ones`() {
        assertThat(Base58.encode(byteArrayOf(0, 1))).isEqualTo("12")
        assertThat(Base58.decode("12")).isEqualTo(byteArrayOf(0, 1))
        // Two leading zero bytes -> two leading '1's, even when the value is zero.
        assertThat(Base58.decode("11")).isEqualTo(byteArrayOf(0, 0))
        assertThat(Base58.encode(byteArrayOf(0, 0))).isEqualTo("11")
    }

    @Test
    fun `system program id is 32 all-zero bytes`() {
        val systemProgram = "1".repeat(32)
        assertThat(Base58.decode(systemProgram)).isEqualTo(ByteArray(32))
        assertThat(Base58.encode(ByteArray(32))).isEqualTo(systemProgram)
    }

    @Test
    fun `round-trips 32 and 64 byte values including high bytes`() {
        val pubkey = ByteArray(32) { ((it * 7 + 3) and 0xFF).toByte() }
        val signature = ByteArray(64) { ((255 - it) and 0xFF).toByte() }
        val leadingZero = byteArrayOf(0, 0, 0) + ByteArray(29) { (0x80 or it).toByte() }

        assertThat(Base58.decode(Base58.encode(pubkey))).isEqualTo(pubkey)
        assertThat(Base58.decode(Base58.encode(signature))).isEqualTo(signature)
        assertThat(Base58.decode(Base58.encode(leadingZero))).isEqualTo(leadingZero)
    }

    @Test
    fun `decode rejects characters outside the alphabet`() {
        // '0', 'O', 'I', 'l' are intentionally excluded from the Base58 alphabet.
        assertThrows(IllegalArgumentException::class.java) { Base58.decode("0OIl") }
    }
}
