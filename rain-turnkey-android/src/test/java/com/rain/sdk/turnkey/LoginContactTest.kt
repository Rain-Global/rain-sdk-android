package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [LoginContact] is a plain value type; canonicalization is the controller's job and is tested there. */
class LoginContactTest {

    @Test
    fun `contacts with the same value are equal within a case and distinct across cases`() {
        assertThat(LoginContact.Email("a@example.com")).isEqualTo(LoginContact.Email("a@example.com"))
        assertThat(LoginContact.Phone("+19999999999")).isEqualTo(LoginContact.Phone("+19999999999"))
        assertThat(LoginContact.Email("+19999999999")).isNotEqualTo(LoginContact.Phone("+19999999999"))
    }

    @Test
    fun `a contact returns the value it was given unchanged`() {
        // A host can still show what the user typed; sendLoginCode canonicalizes its own copy.
        assertThat(LoginContact.Email(" a@example.com ").value).isEqualTo(" a@example.com ")
        assertThat(LoginContact.Phone("+1 999-999-9999").value).isEqualTo("+1 999-999-9999")
    }

    @Test
    fun `toString hides the contact`() {
        assertThat(LoginContact.Email("a@example.com").toString()).doesNotContain("example")
        assertThat(LoginContact.Phone("+19999999999").toString()).doesNotContain("9999")
        assertThat(LoginContact.Phone("+19999999999").toString()).contains("Phone")
    }
}
