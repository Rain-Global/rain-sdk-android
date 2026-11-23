package com.rain.sdk

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderDescriptor
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.WalletProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `provider(id)` is the first call a host makes, and the one most likely to hit wrong credentials.
 * Whatever an adapter's `create` throws must reach the host as a [RainError].
 */
class RainSdkProviderResolutionTest {

    /** A descriptor whose `create` always throws [failure]; counts attempts. */
    private class FailingProvider(private val failure: Throwable) : ProviderDescriptor {
        override val id = ProviderId("failing")
        override val capabilities: Set<Capability> = emptySet()
        var attempts = 0
        override suspend fun create(context: ProviderContext): WalletProvider {
            attempts++
            throw failure
        }
    }

    private fun sdkWith(provider: ProviderDescriptor): RainSdk = RainSdk.builder()
        .rpcEndpoints(mapOf(RainChain.AVALANCHE_MAINNET to "https://rpc.example/avalanche"))
        .register(provider)
        .build()

    @Test
    fun `a raw vendor failure at creation is wrapped as a RainError`() {
        val vendor = IllegalStateException("401 Unauthorized")
        val sdk = sdkWith(FailingProvider(vendor))

        val error = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { sdk.provider(ProviderId("failing")) }
        }
        assertThat(error.cause).isSameInstanceAs(vendor)
    }

    /** An adapter that already classified its failure must not be re-wrapped into something vaguer. */
    @Test
    fun `an adapter's own RainError passes through untouched`() {
        val typed = RainError.TokenExpired()
        val sdk = sdkWith(FailingProvider(typed))

        val error = assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { sdk.provider(ProviderId("failing")) }
        }
        assertThat(error).isSameInstanceAs(typed)
    }

    @Test
    fun `cancellation is not a provider failure`() {
        val sdk = sdkWith(FailingProvider(CancellationException("caller left")))

        assertThrows(CancellationException::class.java) {
            runBlocking { sdk.provider(ProviderId("failing")) }
        }
    }

    /** A failed resolution caches nothing: fixing the credentials and calling again must retry. */
    @Test
    fun `a failed resolution is retried on the next call`() {
        val provider = FailingProvider(IllegalStateException("boom"))
        val sdk = sdkWith(provider)

        repeat(2) {
            assertThrows(RainError.ProviderError::class.java) {
                runBlocking { sdk.provider(ProviderId("failing")) }
            }
        }
        assertThat(provider.attempts).isEqualTo(2)
    }
}
