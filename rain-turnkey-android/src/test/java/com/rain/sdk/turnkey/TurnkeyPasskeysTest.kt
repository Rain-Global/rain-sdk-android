package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The pure passkey pieces at their own boundaries. The domain rule is the only guard against the
 * vendor interpolating `passkeyDomain` unescaped into its WebAuthn request JSON, so the accepted and
 * refused shapes are pinned here rather than through the provider.
 */
class TurnkeyPasskeysTest {

    @Test
    fun `accepted domains are trimmed and otherwise returned as given`() {
        val longLabel = "a".repeat(63)
        listOf(
            "passkeys.example.com" to "passkeys.example.com",
            "  passkeys.example.com  " to "passkeys.example.com",
            "a.b" to "a.b",
            "xn--bcher-kva.example" to "xn--bcher-kva.example",
            "my-app.passkeys.example.co.uk" to "my-app.passkeys.example.co.uk",
            "$longLabel.example" to "$longLabel.example",
        ).forEach { (raw, expected) ->
            assertThat(TurnkeyPasskeys.normalizedDomainOrNull(raw)).isEqualTo(expected)
        }
    }

    @Test
    fun `null and blank turn passkeys off`() {
        assertThat(TurnkeyPasskeys.normalizedDomainOrNull(null)).isNull()
        assertThat(TurnkeyPasskeys.normalizedDomainOrNull("")).isNull()
        assertThat(TurnkeyPasskeys.normalizedDomainOrNull("   ")).isNull()
    }

    @Test
    fun `everything that is not a two-label domain of letters digits and hyphens is refused`() {
        val tooLong = "a".repeat(64)
        listOf(
            "localhost",
            "-a.example",
            "a-.example",
            "a..example",
            "a.example.",
            ".a.example",
            "$tooLong.example",
            "a_b.example",
            "a.example:443",
            "https://a.example",
            "a.example/path",
            "a example.com",
            "a.ex\u00e4mple",
            "a.\"example",
            "a.example\\",
        ).forEach { bad ->
            val refused = assertThrows(RainError.InvalidConfig::class.java) { TurnkeyPasskeys.normalizedDomainOrNull(bad) }
            assertThat(refused).hasMessageThat().contains("two labels")
        }
    }

    @Test
    fun `the authenticator name is the epoch in whole seconds`() {
        assertThat(TurnkeyPasskeys.authenticatorName(1_700_000_000.999)).isEqualTo("passkey-1700000000")
        assertThat(TurnkeyPasskeys.authenticatorName(1_700_000_000.0)).isEqualTo("passkey-1700000000")
        assertThat(TurnkeyPasskeys.authenticatorName(0.0)).isEqualTo("passkey-0")
    }
}
