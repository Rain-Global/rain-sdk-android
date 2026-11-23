package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins [EthereumKeyEncoder] to addresses computed outside the SDK: the key `1` address every
 * secp256k1 reference lists, and the `0102..1f20` seed's address, computed with an independent
 * pure-Python curve implementation and Keccak and cross-checked with web3j outside this build.
 * No vendor type appears here.
 */
class EthereumKeyEncoderTest {

    @Test
    fun `private key one derives the well-known address`() {
        val key = ByteArray(32).also { it[31] = 1 }

        assertThat(EthereumKeyEncoder.address(key)).isEqualTo("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf")
    }

    @Test
    fun `the sequential seed derives the vector address`() {
        val key = ByteArray(32) { (it + 1).toByte() }

        assertThat(EthereumKeyEncoder.address(key)).isEqualTo(MockTurnkey.VECTOR_ETHEREUM_ADDRESS)
    }

    @Test
    fun `the address is 0x plus 40 lowercase hex characters`() {
        val address = EthereumKeyEncoder.address(ByteArray(32) { 0x5a })

        assertThat(address).matches("0x[0-9a-f]{40}")
    }

    @Test
    fun `zero and the curve order are rejected and the order minus one is accepted`() {
        val zero = ByteArray(32)
        val order = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141".hexBytes()
        val orderMinusOne = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364140".hexBytes()

        assertThrows(IllegalArgumentException::class.java) { EthereumKeyEncoder.address(zero) }
        assertThrows(IllegalArgumentException::class.java) { EthereumKeyEncoder.address(order) }
        assertThat(EthereumKeyEncoder.address(orderMinusOne)).matches("0x[0-9a-f]{40}")
    }

    @Test
    fun `a key of the wrong length is rejected without echoing its bytes`() {
        val short = ByteArray(31) { 0xab.toByte() }
        val long = ByteArray(33) { 0xcd.toByte() }

        val shortError = assertThrows(IllegalArgumentException::class.java) { EthereumKeyEncoder.address(short) }
        val longError = assertThrows(IllegalArgumentException::class.java) { EthereumKeyEncoder.address(long) }

        assertThat(shortError).hasMessageThat().contains("31")
        assertThat(shortError).hasMessageThat().doesNotContain("abab")
        assertThat(longError).hasMessageThat().contains("33")
        assertThat(longError).hasMessageThat().doesNotContain("cdcd")
    }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
