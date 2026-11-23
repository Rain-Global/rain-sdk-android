package com.rain.sdk.internal.utils

import com.rain.sdk.error.RainError
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * The one parser for a withdrawal authorization's `expiresAt`, shared by the EVM and Solana paths so
 * both accept the same shapes and fail the same way.
 */
internal object RainSignatureExpiry {

    /**
     * [value] as unix seconds. Accepts the shapes Rain's API has returned: unix seconds
     * ("1784912091"), an ISO-8601 instant ("2026-07-24T16:54:51Z", fractional seconds allowed) and an
     * ISO-8601 date-time with a numeric offset ("2026-07-24T18:54:51+02:00"). Surrounding whitespace
     * is ignored. The offset fallback matters on Android API 28 to 33, whose `java.time` predates
     * OpenJDK 12 and rejects a numeric offset in `Instant.parse`; newer runtimes, including the JVM the
     * unit tests run on, accept it there, so only those devices reach the fallback. An integer is
     * always seconds: there is no unit heuristic, so a millisecond value reads as a date far in the
     * future, and the contract then rejects the authorization, whose signed expiry it no longer
     * matches. A value before the unix epoch is rejected here.
     *
     * @throws RainError.InvalidConfig (RAIN_102) for a negative value and for any other input,
     *   including a blank one.
     */
    fun parseEpochSeconds(value: String): Long {
        val trimmed = value.trim()
        val seconds = trimmed.toLongOrNull()
            ?: instantOrNull(trimmed)
            ?: offsetDateTimeOrNull(trimmed)
            ?: throw RainError.InvalidConfig(
                "Invalid expiresAt format: '$value'. Expected unix seconds or an ISO-8601 instant, with Z or a numeric offset."
            )
        if (seconds < 0) {
            throw RainError.InvalidConfig("Invalid expiresAt: '$value' lies before the unix epoch")
        }
        return seconds
    }

    private fun instantOrNull(value: String): Long? = try {
        Instant.parse(value).epochSecond
    } catch (_: DateTimeParseException) {
        null
    }

    private fun offsetDateTimeOrNull(value: String): Long? = try {
        OffsetDateTime.parse(value).toEpochSecond()
    } catch (_: DateTimeParseException) {
        null
    }
}
