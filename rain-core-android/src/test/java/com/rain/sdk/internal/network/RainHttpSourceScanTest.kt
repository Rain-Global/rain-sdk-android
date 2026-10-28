package com.rain.sdk.internal.network

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * No library module builds an HTTP client of its own, so the SDK's calls stay on [RainHttp]'s pool:
 * `OkHttpClient()`, `OkHttpClient.Builder()` and a one-argument web3j `HttpService(url)` in Kotlin or
 * Java main sources, comments and strings blanked first. An import alias for `OkHttpClient` is not seen.
 */
class RainHttpSourceScanTest {

    @Test
    fun `no library main source builds an HTTP client outside RainHttp`() {
        val repoRoot = listOf(File(".."), File(".")).map { it.canonicalFile }
            .first { File(it, "${LIBRARY_MODULES.first()}/src/main").isDirectory }
        val sources = LIBRARY_MODULES.flatMap { module ->
            val mainSources = File(repoRoot, "$module/src/main")
            assertWithMessage("$module/src/main").that(mainSources.isDirectory).isTrue()
            mainSources.walkTopDown()
                .filter { it.isFile && it.extension in SOURCE_EXTENSIONS && it.name != "RainHttp.kt" }
                .toList()
        }
        assertWithMessage("library sources scanned").that(sources).isNotEmpty()

        val hits = sources.flatMap { file ->
            val code = COMMENTS_AND_STRINGS.replace(file.readText()) { it.value.replace(NOT_A_NEWLINE, " ") }
            CLIENT_BUILDERS.flatMap { pattern ->
                pattern.findAll(code).map { match ->
                    val line = code.substring(0, match.range.first).count { it == '\n' } + 1
                    "${file.relativeTo(repoRoot)}:$line: ${match.value}"
                }
            }
        }
        assertWithMessage("HTTP clients built outside RainHttp").that(hits).isEmpty()
    }

    private companion object {
        val LIBRARY_MODULES = listOf(
            "rain-core-android",
            "rain-turnkey-android",
            "rain-portal-android",
            "rain-privy-android",
            "rain-wallet-android",
        )
        val SOURCE_EXTENSIONS = setOf("kt", "java")
        val COMMENTS_AND_STRINGS = Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|/\\*[\\s\\S]*?\\*/|//[^\\n]*")
        val NOT_A_NEWLINE = Regex("[^\\n]")
        val CLIENT_BUILDERS = listOf(
            Regex("\\bOkHttpClient\\s*\\(\\s*\\)"),
            Regex("\\bOkHttpClient\\s*\\.\\s*Builder\\s*\\(\\s*\\)"),
            Regex("\\bHttpService\\s*\\([^,()]*\\)"),
        )
    }
}
