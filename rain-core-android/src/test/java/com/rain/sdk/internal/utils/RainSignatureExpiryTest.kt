package com.rain.sdk.internal.utils

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import org.junit.Assert.assertThrows
import org.junit.Test

/** The one parser both withdrawal paths use for Rain's `expiresAt`. */
class RainSignatureExpiryTest {

    @Test
    fun `unix seconds pass through`() {
        assertThat(RainSignatureExpiry.parseEpochSeconds("1784912091")).isEqualTo(1784912091L)
    }

    @Test
    fun `an ISO-8601 instant converts to unix seconds`() {
        assertThat(RainSignatureExpiry.parseEpochSeconds("2026-07-24T16:54:51Z")).isEqualTo(1784912091L)
    }

    @Test
    fun `fractional seconds are accepted and truncated`() {
        assertThat(RainSignatureExpiry.parseEpochSeconds("2026-07-24T16:54:51.000Z")).isEqualTo(1784912091L)
        assertThat(RainSignatureExpiry.parseEpochSeconds("2026-07-24T16:54:51.999Z")).isEqualTo(1784912091L)
    }

    @Test
    fun `a numeric offset converts to the same instant`() {
        assertThat(RainSignatureExpiry.parseEpochSeconds("2026-07-24T18:54:51+02:00")).isEqualTo(1784912091L)
        assertThat(RainSignatureExpiry.parseEpochSeconds("2026-07-24T11:54:51-05:00")).isEqualTo(1784912091L)
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertThat(RainSignatureExpiry.parseEpochSeconds(" 1784912091 ")).isEqualTo(1784912091L)
        assertThat(RainSignatureExpiry.parseEpochSeconds("\t2026-07-24T16:54:51Z\n")).isEqualTo(1784912091L)
    }

    @Test
    fun `anything else is InvalidConfig and names the accepted shapes`() {
        for (bad in listOf("", "   ", "not-a-timestamp", "2026-07-24", "2026-07-24T16:54:51", "1784912091.5")) {
            val error = assertThrows(RainError.InvalidConfig::class.java) { RainSignatureExpiry.parseEpochSeconds(bad) }
            assertWithMessage(bad).that(error.errorCode).isEqualTo(RainErrorCode.INVALID_CONFIG)
            assertWithMessage(bad).that(error).hasMessageThat().contains("Invalid expiresAt format")
            assertWithMessage(bad).that(error).hasMessageThat().contains("unix seconds or an ISO-8601 instant")
        }
    }

    @Test
    fun `a value before the unix epoch is InvalidConfig`() {
        for (bad in listOf("-5", "1969-12-31T23:59:59Z")) {
            val error = assertThrows(RainError.InvalidConfig::class.java) { RainSignatureExpiry.parseEpochSeconds(bad) }
            assertWithMessage(bad).that(error.errorCode).isEqualTo(RainErrorCode.INVALID_CONFIG)
            assertWithMessage(bad).that(error).hasMessageThat().contains("before the unix epoch")
        }
    }

    @Test
    fun `an integer is always seconds, so a millisecond value reads as a far future`() {
        // No unit heuristic: the signed expiry then no longer matches, and the contract rejects the authorization.
        assertThat(RainSignatureExpiry.parseEpochSeconds("1784912091000")).isEqualTo(1784912091000L)
    }
}
