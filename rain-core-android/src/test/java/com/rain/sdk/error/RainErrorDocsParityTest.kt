package com.rain.sdk.error

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * `docs/METHODS.md` is the host's reference for the error contract: its Errors table names every code
 * and every error class, and the sponsorship prose in the three documents never promises the zero quote
 * the SDK does not return. A failure here means the document drifted from the code; fix the document.
 */
class RainErrorDocsParityTest {

    private val methods: String = doc("docs/METHODS.md")
    private val turnkeySupport: String = doc("docs/TURNKEY_SUPPORT.md")
    private val readme: String = doc("README.md")

    private fun doc(relative: String): String = sequenceOf("../$relative", relative)
        .map(::File)
        .firstOrNull { it.isFile }
        ?.readText()
        ?: error("$relative not found from ${File(".").absolutePath}")

    private val errorsSection: String =
        methods.substringAfter("\n## Errors").substringBefore("\n### Error handling example")

    init {
        // A renamed heading would widen the section to the whole document and every check below would pass.
        check(methods.contains("\n## Errors") && methods.contains("\n### Error handling example")) {
            "docs/METHODS.md lost the Errors section anchors this test scopes by"
        }
    }

    @Test
    fun `the Errors table names every code`() {
        RainErrorCode.entries.forEach { code ->
            assertWithMessage(code.name).that(errorsSection).contains("`${code.code}`")
        }
    }

    @Test
    fun `the Errors table names every error class`() {
        RainError::class.sealedSubclasses.forEach { subclass ->
            assertWithMessage(subclass.simpleName).that(errorsSection).contains("`RainError.${subclass.simpleName}`")
        }
    }

    @Test
    fun `sponsored fee estimates are never documented as zero or as skipping the signature`() {
        val stale = listOf(
            "estimates return 0",
            "estimates return zero",
            "result is `0`",
            "quotes `0`",
            "fee estimate `0`",
            "nothing is signed or estimated",
            "signing step of a withdrawal fee estimate",
        )
        mapOf("docs/METHODS.md" to methods, "docs/TURNKEY_SUPPORT.md" to turnkeySupport, "README.md" to readme)
            .forEach { (name, text) ->
                stale.forEach { phrase -> assertWithMessage("$name: $phrase").that(text).doesNotContain(phrase) }
            }
    }
}
