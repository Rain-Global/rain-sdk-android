package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.turnkey.core.models.errors.TurnkeyKotlinError
import org.junit.Test
import kotlin.reflect.full.primaryConstructor

/**
 * The mapper's one exit for the vendor's HTTP response body: whichever wrapper carries an HTTP
 * failure and whichever branch maps it, the host gets the call and the status, never the body.
 */
class TurnkeyResponseBodyTest {
    private val mapping = TurnkeyErrorMapping

    @Test
    fun `every vendor wrapper over an HTTP failure hides the response body`() {
        // The vendor joins a wrapper's text to its cause's, so the body travels up through every wrapper.
        // Whatever branch of the mapper a wrapper takes, the one exit must remove it.
        val marker = "body-the-host-must-not-see"
        val http = RuntimeException("HTTP error calling ACTIVITY_TYPE_STAMP_LOGIN request\nError: {\"message\":\"$marker\"}\nCode: 500")
        val checked = TurnkeyKotlinError::class.sealedSubclasses.mapNotNull { wrapper ->
            val constructor = wrapper.primaryConstructor ?: return@mapNotNull null
            if (constructor.parameters.none { it.type.classifier == Throwable::class }) return@mapNotNull null
            val arguments = constructor.parameters.associateWith { parameter ->
                when (parameter.type.classifier) {
                    Throwable::class -> http
                    String::class -> "x"
                    else -> error("unexpected parameter ${parameter.name} on ${wrapper.simpleName}")
                }
            }
            val mapped = mapping.map(constructor.callBy(arguments))
            val texts = mapped.causeChain().mapNotNull { it.message }.toList()
            assertWithMessage("${wrapper.simpleName} -> $texts").that(texts.none { it.contains(marker) }).isTrue()
            wrapper.simpleName
        }
        assertThat(checked.size).isAtLeast(40)
    }

    @Test
    fun `one HTTP failure maps to one message whichever wrapper carried it`() {
        val http = RuntimeException("HTTP error calling ACTIVITY_TYPE_STAMP_LOGIN request\nError: {\"code\":13}\nCode: 500")
        val routes = listOf(
            mapping.map(http),
            mapping.map(TurnkeyKotlinError.FailedToLoginWithOtp(http)),
            mapping.map(TurnkeyKotlinError.FailedToCreateSession(http)),
        )
        routes.forEach { mapped ->
            assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
            assertThat(mapped.message).endsWith("Provider Error: HTTP error from ACTIVITY_TYPE_STAMP_LOGIN: 500")
        }
    }

    @Test
    fun `an HTTP failure two causes down loses its body too`() {
        val http = RuntimeException("HTTP error calling ACTIVITY_TYPE_GET_WALLETS request\nError: {\"secret\":1}\nCode: 503")
        val mapped = mapping.map(TurnkeyKotlinError.FailedToRefreshWallets(RuntimeException("retry helper", http)))
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapped.message).endsWith("Provider Error: HTTP error from ACTIVITY_TYPE_GET_WALLETS: 503")
        assertThat(mapped.cause?.message).isEqualTo("HTTP error from ACTIVITY_TYPE_GET_WALLETS: 503")
    }

    @Test
    fun `an internal error over an HTTP failure keeps the vendor's own text and drops the body`() {
        val http = RuntimeException("HTTP error calling ACTIVITY_TYPE_STAMP_LOGIN request\nError: {\"secret\":1}\nCode: 500")
        val mapped = mapping.map(TurnkeyKotlinError.KeyAlreadyExists("rain-turnkey-abc", http))
        assertThat(mapped).isInstanceOf(RainError.InternalError::class.java)
        assertThat(mapped.message).contains("Wallet backend:")
        assertThat(mapped.message).doesNotContain("secret")
        assertThat(mapped.message).doesNotContain(" - error: ")
        assertThat(mapped.cause?.message).isEqualTo("HTTP error from ACTIVITY_TYPE_STAMP_LOGIN: 500")
    }
}
