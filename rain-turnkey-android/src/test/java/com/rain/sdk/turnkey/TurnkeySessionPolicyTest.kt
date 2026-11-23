package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TurnkeySessionPolicyTest {

    @Test
    fun `refreshExpirationSeconds must be positive`() {
        for (seconds in listOf(0L, -1L)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                TurnkeySessionPolicy(refreshExpirationSeconds = seconds)
            }
            assertThat(error).hasMessageThat().contains("was $seconds")
        }
        assertThat(TurnkeySessionPolicy(refreshExpirationSeconds = 1L).refreshExpirationSeconds).isEqualTo(1L)
        assertThat(TurnkeySessionPolicy().refreshExpirationSeconds).isNull()
    }
}
