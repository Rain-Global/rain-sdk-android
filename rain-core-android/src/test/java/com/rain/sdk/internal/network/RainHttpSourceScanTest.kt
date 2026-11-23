package com.rain.sdk.internal.network

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * No library module builds an HTTP client of its own, so the SDK's calls stay on [RainHttp]'s pool:
 * `OkHttpClient()` and `OkHttpClient.Builder()` outside `RainHttp.kt`, a web3j `HttpService(...)`
 * outside `Web3jProvider.kt` (the one place that hands it the shared client), and a vendor
 * `TurnkeyClient(...)` whose arguments carry no `http =` (the vendor then builds its own client), in
 * Kotlin or Java main sources, comments and strings blanked first. An import alias for `OkHttpClient`
 * is not seen. The core build file declares the other modules' sources as inputs of this test.
 */
class RainHttpSourceScanTest {

    @Test
    fun `no library main source builds an HTTP client outside RainHttp`() {
        val repoRoot = listOf(File(".."), File(".")).map { it.canonicalFile }
            .first { File(it, "${LIBRARY_MODULES.first()}/src/main").isDirectory }
        val sources = LIBRARY_MODULES.flatMap { module ->
            val mainSources = File(repoRoot, "$module/src/main")
            assertWithMessage("$module/src/main").that(mainSources.isDirectory).isTrue()
            mainSources.walkTopDown().filter { it.isFile && it.extension in SOURCE_EXTENSIONS }.toList()
        }
        assertWithMessage("library sources scanned").that(sources).isNotEmpty()

        val hits = sources.flatMap { file ->
            val code = COMMENTS_AND_STRINGS.replace(file.readText()) { it.value.replace(NOT_A_NEWLINE, " ") }
            CLIENT_BUILDS.filter { it.allowedIn != file.name }.flatMap { build ->
                build.pattern.findAll(code).filter(build.isHit).map { match ->
                    val line = code.substring(0, match.range.first).count { it == '\n' } + 1
                    "${file.relativeTo(repoRoot)}:$line: ${match.value.lineSequence().first()}"
                }
            }
        }
        assertWithMessage("HTTP clients built outside RainHttp").that(hits).isEmpty()
    }

    /** A way of building a client, the one file allowed to use it, and a check on each match. */
    private class ClientBuild(
        val pattern: Regex,
        val allowedIn: String? = null,
        val isHit: (MatchResult) -> Boolean = { true },
    )

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
        val HTTP_ARGUMENT = Regex("\\bhttp\\s*=")
        val CLIENT_BUILDS = listOf(
            ClientBuild(Regex("\\bOkHttpClient\\s*\\(\\s*\\)"), allowedIn = "RainHttp.kt"),
            ClientBuild(Regex("\\bOkHttpClient\\s*\\.\\s*Builder\\s*\\(\\s*\\)"), allowedIn = "RainHttp.kt"),
            ClientBuild(Regex("\\bHttpService\\s*\\("), allowedIn = "Web3jProvider.kt"),
            ClientBuild(Regex("\\bTurnkeyClient\\s*\\(((?:[^()]|\\([^()]*\\))*)\\)")) { match ->
                !HTTP_ARGUMENT.containsMatchIn(match.groupValues[1])
            },
        )
    }
}
