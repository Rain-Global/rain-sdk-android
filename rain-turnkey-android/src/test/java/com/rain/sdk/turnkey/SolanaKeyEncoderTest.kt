package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.solana.Base58
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Locale

/**
 * Pins [SolanaKeyEncoder] to published vectors: RFC 8032 section 7.1 for the ed25519 derivation,
 * and the `0102..1f20` seed for the keypair string, a cross-platform contract shared by Rain's
 * SDKs. No vendor type appears here, so the suite runs on any JDK.
 */
class SolanaKeyEncoderTest {

    @Test
    fun `RFC 8032 test 1 seed derives its public key`() {
        val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexBytes()

        assertThat(SolanaKeyEncoder.publicKey(seed).hex())
            .isEqualTo("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    }

    @Test
    fun `RFC 8032 test 2 seed derives its public key`() {
        val seed = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb".hexBytes()

        assertThat(SolanaKeyEncoder.publicKey(seed).hex())
            .isEqualTo("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
    }

    @Test
    fun `RFC 8032 test 3 seed derives its public key`() {
        val seed = "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7".hexBytes()

        assertThat(SolanaKeyEncoder.publicKey(seed).hex())
            .isEqualTo("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025")
    }

    @Test
    fun `the sequential seed encodes to the contract keypair string`() {
        val seed = VECTOR_SEED.hexBytes()
        val publicKey = SolanaKeyEncoder.publicKey(seed)

        assertThat(publicKey.hex()).isEqualTo(VECTOR_PUBLIC_KEY)
        assertThat(SolanaKeyEncoder.keypairBase58(seed, publicKey)).isEqualTo(VECTOR_KEYPAIR)
    }

    @Test
    fun `the keypair string decodes to seed then public key and is not the Base58Check form`() {
        val seed = VECTOR_SEED.hexBytes()
        val publicKey = SolanaKeyEncoder.publicKey(seed)

        val decoded = Base58.decode(SolanaKeyEncoder.keypairBase58(seed, publicKey))

        assertThat(decoded).hasLength(64)
        assertThat(decoded.copyOfRange(0, 32)).isEqualTo(seed)
        assertThat(decoded.copyOfRange(32, 64)).isEqualTo(publicKey)
        assertThat(SolanaKeyEncoder.keypairBase58(seed, publicKey)).isNotEqualTo(VECTOR_KEYPAIR_BASE58CHECK)
        assertThat(Base58.encode(publicKey)).isEqualTo(MockTurnkey.VECTOR_SOLANA_ADDRESS)
    }

    @Test
    fun `a seed of the wrong length is rejected without echoing its bytes`() {
        val short = ByteArray(31) { 0xab.toByte() }
        val long = ByteArray(33) { 0xcd.toByte() }
        val publicKey = SolanaKeyEncoder.publicKey(VECTOR_SEED.hexBytes())

        val shortError = assertThrows(IllegalArgumentException::class.java) { SolanaKeyEncoder.publicKey(short) }
        val longError = assertThrows(IllegalArgumentException::class.java) { SolanaKeyEncoder.publicKey(long) }
        val pairError = assertThrows(IllegalArgumentException::class.java) {
            SolanaKeyEncoder.keypairBase58(short, publicKey)
        }
        val keyError = assertThrows(IllegalArgumentException::class.java) {
            SolanaKeyEncoder.keypairBase58(VECTOR_SEED.hexBytes(), long)
        }

        assertThat(shortError).hasMessageThat().contains("31")
        assertThat(shortError).hasMessageThat().doesNotContain(short.hex())
        assertThat(longError).hasMessageThat().contains("33")
        assertThat(longError).hasMessageThat().doesNotContain(long.hex())
        assertThat(pairError).hasMessageThat().doesNotContain(short.hex())
        assertThat(keyError).hasMessageThat().doesNotContain(long.hex())
    }

    @Test
    fun `a seed with leading zero bytes round-trips through Base58 to 64 bytes`() {
        val seed = ByteArray(32) { index -> if (index < 3) 0 else 0x07 }
        val publicKey = SolanaKeyEncoder.publicKey(seed)

        val encoded = SolanaKeyEncoder.keypairBase58(seed, publicKey)
        val decoded = Base58.decode(encoded)

        assertThat(encoded).startsWith("111")
        assertThat(decoded).hasLength(64)
        assertThat(decoded).isEqualTo(seed + publicKey)
    }

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(Locale.ROOT, it) }

    private companion object {
        const val VECTOR_SEED = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
        const val VECTOR_PUBLIC_KEY = "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664"
        const val VECTOR_KEYPAIR =
            "2Ana1pUpv2ZbMVkwF5FXapYeBEjdxDatLn7nvJkhgTSdZd8hbDHTd21as7EAsg7ypityqfsw2pMQKJcVDVcAEsd"
        const val VECTOR_KEYPAIR_BASE58CHECK =
            "8eZoZozC16DxAWsfGzwNnJfZkCJ3Yw3VEogWoNy59BnUiq57huawYsvMjVzajpYn1PEbEPzNykppFaizG5VZjvMQ3BJX"
    }
}
