package com.rain.sdk.internal.utils

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

/** The one decoder both withdrawal paths use for Rain's authorization salt. */
class RainSignatureSaltTest {

    @Test
    fun `a 32-byte base64 salt decodes to its bytes`() {
        val bytes = ByteArray(RainSignatureSalt.LENGTH) { 0x11.toByte() }
        assertThat(RainSignatureSalt.decode(Base64.getEncoder().encodeToString(bytes))).isEqualTo(bytes)
    }

    @Test
    fun `a salt that is not base64 is InvalidConfig`() {
        assertInvalidBase64("not base64!")
    }

    @Test
    fun `only the basic alphabet is accepted`() {
        // The URL-safe alphabet and embedded whitespace are rejected, as both withdrawal paths always did; a
        // later switch of decoder flavour would change EVM and Solana at once, so the rule is pinned here.
        val urlSafe = Base64.getUrlEncoder().encodeToString(ByteArray(RainSignatureSalt.LENGTH) { 0xFB.toByte() })
        assertThat(urlSafe).contains("-")
        assertInvalidBase64(urlSafe)
        assertInvalidBase64("AAAA AAAA")
        assertInvalidBase64("AAAA\nAAAA")
    }

    @Test
    fun `surrounding whitespace is not trimmed`() {
        // Unlike the expiry parser, the salt is taken as sent: a padded value is a malformed value.
        val valid = Base64.getEncoder().encodeToString(ByteArray(RainSignatureSalt.LENGTH))
        assertInvalidBase64(" $valid ")
    }

    @Test
    fun `a salt of another length is InvalidConfig and names both lengths`() {
        assertWrongLength(Base64.getEncoder().encodeToString(ByteArray(31)), got = 31)
        assertWrongLength(Base64.getEncoder().encodeToString(ByteArray(33)), got = 33)
        assertWrongLength("", got = 0)
    }

    private fun assertInvalidBase64(salt: String) {
        val error = assertThrows(RainError.InvalidConfig::class.java) { RainSignatureSalt.decode(salt) }
        assertThat(error.errorCode).isEqualTo(RainErrorCode.INVALID_CONFIG)
        assertThat(error).hasMessageThat().contains("RainAdminSignature.salt is not valid base64")
    }

    private fun assertWrongLength(salt: String, got: Int) {
        val error = assertThrows(RainError.InvalidConfig::class.java) { RainSignatureSalt.decode(salt) }
        assertThat(error.errorCode).isEqualTo(RainErrorCode.INVALID_CONFIG)
        assertThat(error).hasMessageThat().contains("RainAdminSignature.salt must be 32 bytes, got $got")
    }
}
