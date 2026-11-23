package com.rain.sdk.internal.utils

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import org.junit.Assert.assertThrows
import org.junit.Test

/** The adapter-facing address gate: shape and, for mixed-case input, the EIP-55 checksum. */
class AdapterAddressValidationTest {

    @Test
    fun `a lowercase address is accepted and returned in checksum form`() {
        assertThat(validateAndChecksumAddress(VECTOR.lowercase(), "walletAddress")).isEqualTo(VECTOR)
    }

    @Test
    fun `an uppercase address is accepted and returned in checksum form`() {
        assertThat(validateAndChecksumAddress("0x" + VECTOR.removePrefix("0x").uppercase(), "walletAddress"))
            .isEqualTo(VECTOR)
    }

    @Test
    fun `an uppercase 0X prefix is accepted`() {
        assertThat(validateAndChecksumAddress("0X" + VECTOR.removePrefix("0x"), "walletAddress")).isEqualTo(VECTOR)
    }

    @Test
    fun `a correctly checksummed address passes through unchanged`() {
        assertThat(validateAndChecksumAddress(VECTOR, "walletAddress")).isEqualTo(VECTOR)
    }

    @Test
    fun `a mixed-case address with a wrong checksum is refused`() {
        val wrong = "0x5AAeb6053F3E94C9b9A09f33669435E7Ef1BeAed" // first letter flipped

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            validateAndChecksumAddress(wrong, "walletAddress")
        }

        assertThat(error).hasMessageThat().contains("walletAddress checksum")
    }

    @Test
    fun `a malformed address is refused`() {
        assertThrows(RainError.InvalidConfig::class.java) {
            validateAndChecksumAddress("0x1234", "walletAddress")
        }
        assertThrows(RainError.InvalidConfig::class.java) {
            validateAndChecksumAddress("not-an-address", "walletAddress")
        }
    }

    private companion object {
        /** The EIP-55 reference vector. */
        const val VECTOR = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    }
}
