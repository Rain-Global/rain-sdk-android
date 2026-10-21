package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import org.junit.Test

/**
 * The Rain half of the mapping tests: [TurnkeyErrorMapping] on a [RainApiAuthFailure], the Rain
 * client's refusal on the managed-auth legs that run against Rain's embedded-wallet endpoints. One
 * row per kind, then the claim in `classify` and the log line. Its own class beside
 * [TurnkeyErrorMappingTest] because that one is at detekt's size limit, and because no vendor type
 * is involved here, so these rows run on any JDK and need no gate.
 */
class RainApiAuthFailureMappingTest {

    private val mapping = TurnkeyErrorMapping

    private fun rainFailure(kind: RainApiAuthFailure.Kind, status: Int): RainApiAuthFailure =
        RainApiAuthFailure(kind, status, "/v1/issuing/embedded-wallets/login")

    private fun assertRainProviderError(kind: RainApiAuthFailure.Kind, status: Int) {
        val failure = rainFailure(kind, status)
        val mapped = mapping.map(failure)
        assertWithMessage(kind.name).that(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertWithMessage(kind.name).that(mapped.errorCode).isEqualTo(RainErrorCode.PROVIDER_ERROR)
        assertWithMessage(kind.name).that(mapped.cause).isSameInstanceAs(failure)
    }

    @Test
    fun `a Rain InvalidCode failure maps to InvalidLoginCode`() {
        val mapped = mapping.map(rainFailure(RainApiAuthFailure.Kind.InvalidCode, 400))
        assertThat(mapped).isInstanceOf(RainError.InvalidLoginCode::class.java)
        assertThat(mapped.errorCode).isEqualTo(RainErrorCode.INVALID_LOGIN_CODE)
    }

    @Test
    fun `a Rain TokenRejected failure maps to Unauthorized naming the path and the status`() {
        val mapped = mapping.map(rainFailure(RainApiAuthFailure.Kind.TokenRejected, 401))
        assertThat(mapped).isInstanceOf(RainError.Unauthorized::class.java)
        assertThat(mapped.errorCode).isEqualTo(RainErrorCode.UNAUTHORIZED)
        assertThat(mapped).hasMessageThat().contains("wallet auth token")
        assertThat(mapped).hasMessageThat().contains("/v1/issuing/embedded-wallets/login")
        assertThat(mapped).hasMessageThat().contains("401")
    }

    @Test
    fun `a Rain Forbidden failure maps to Unauthorized whether it arrived as a 403 or as a 401 for the tenant`() {
        listOf(403, 401).forEach { status ->
            val mapped = mapping.map(rainFailure(RainApiAuthFailure.Kind.Forbidden, status))
            assertWithMessage("$status").that(mapped).isInstanceOf(RainError.Unauthorized::class.java)
            assertWithMessage("$status").that(mapped.errorCode).isEqualTo(RainErrorCode.UNAUTHORIZED)
            assertWithMessage("$status").that(mapped).hasMessageThat().contains("tenant")
            assertWithMessage("$status").that(mapped).hasMessageThat().contains(status.toString())
        }
    }

    @Test
    fun `a Rain VerificationFailed failure maps to ProviderError, not InvalidLoginCode`() {
        // A 400 that is not "invalid or expired code" has spent the code; the attempt restarts.
        assertRainProviderError(RainApiAuthFailure.Kind.VerificationFailed, 400)
        assertThat(mapping.map(rainFailure(RainApiAuthFailure.Kind.VerificationFailed, 400)))
            .isNotInstanceOf(RainError.InvalidLoginCode::class.java)
    }

    @Test
    fun `a Rain NoWallet failure off the login leg maps to ProviderError`() {
        // On the login leg the controller consumes a 404 as "sign up" before anything is mapped.
        assertRainProviderError(RainApiAuthFailure.Kind.NoWallet, 404)
    }

    @Test
    fun `a Rain Conflict failure maps to ProviderError`() {
        assertRainProviderError(RainApiAuthFailure.Kind.Conflict, 409)
    }

    @Test
    fun `a Rain RateLimited failure maps to ProviderError, not a code of its own`() {
        assertRainProviderError(RainApiAuthFailure.Kind.RateLimited, 429)
    }

    @Test
    fun `a Rain ServerError failure maps to ProviderError`() {
        assertRainProviderError(RainApiAuthFailure.Kind.ServerError, 503)
    }

    @Test
    fun `a Rain Other failure maps to ProviderError`() {
        assertRainProviderError(RainApiAuthFailure.Kind.Other, 302)
    }

    @Test
    fun `classify claims a Rain failure, and map leaves it as the cause with its message whole`() {
        val failure = rainFailure(RainApiAuthFailure.Kind.Conflict, 409)
        assertThat(mapping.classify(failure)).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(failure.carriesVendorData()).isFalse()

        val mapped = mapping.map(failure)
        assertThat(mapped.cause).isSameInstanceAs(failure)
        assertThat(mapped).hasMessageThat().endsWith("Provider Error: Rain API /v1/issuing/embedded-wallets/login: HTTP 409")
    }

    @Test
    fun `mapAuthError on a Rain failure logs the code and the class name and never the throwable`() {
        val seen = StringBuilder()
        val throwables = mutableListOf<Throwable>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                seen.append(message).append('\n')
                if (t != null) throwables += t
            }
        }
        timber.log.Timber.plant(tree)
        try {
            mapping.mapAuthError(rainFailure(RainApiAuthFailure.Kind.TokenRejected, 401))
        } finally {
            timber.log.Timber.uproot(tree)
        }
        assertThat(seen.toString()).contains("Authentication error")
        assertThat(seen.toString()).contains(RainErrorCode.UNAUTHORIZED.code)
        assertThat(seen.toString()).contains("RainApiAuthFailure")
        assertThat(throwables).isEmpty()
    }
}
