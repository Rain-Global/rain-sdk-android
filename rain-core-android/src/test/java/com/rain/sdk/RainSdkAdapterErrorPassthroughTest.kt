package com.rain.sdk

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderDescriptor
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.WalletProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The error contract between an adapter and core, driven through the public builder.
 *
 * Every adapter converts its vendor's exceptions to a [RainError] before they leave the adapter,
 * in its session coordinator. Core's side of the bargain is pinned here: a [RainError] thrown by a
 * wallet provider reaches the host with the same code, and anything else is wrapped as a
 * [RainError.ProviderError] after the shared prose heuristics. Without the first half, a vendor's
 * expired session would arrive as a generic provider error and no re-authentication hook would
 * fire.
 */
class RainSdkAdapterErrorPassthroughTest {

    /** A wallet whose every address read fails the way an adapter reports a dead session. */
    private class ExpiredSessionWallet : StubWalletProvider() {
        override suspend fun getWalletAddress(): String = throw RainError.TokenExpired()
    }

    /** A wallet that lets a non-Rain exception escape, as a host-supplied provider might. */
    private class LeakyWallet(private val failure: Throwable) : StubWalletProvider() {
        override suspend fun getWalletAddress(): String = throw failure
    }

    private class StubProvider(private val wallet: WalletProvider) : ProviderDescriptor {
        override val id = ProviderId("stub-vendor")
        override val capabilities: Set<Capability> = emptySet()
        override suspend fun create(context: ProviderContext): WalletProvider = wallet
    }

    private fun sdkWith(wallet: WalletProvider): RainSdk = RainSdk.builder()
        .rpcEndpoints(mapOf(RainChain.AVALANCHE_MAINNET to "https://rpc.example/avalanche"))
        .register(StubProvider(wallet))
        .build()

    @Test
    fun `a RainError thrown by the adapter reaches the host with its code intact`() {
        val sdk = sdkWith(ExpiredSessionWallet())

        val error = assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { sdk.provider(ProviderId("stub-vendor")).getWalletAddress() }
        }

        assertThat(error.errorCode).isEqualTo(RainErrorCode.TOKEN_EXPIRED)
    }

    @Test
    fun `anything else the adapter lets escape is wrapped as ProviderError carrying it`() {
        val leaked = IllegalStateException("vendor said no")
        val sdk = sdkWith(LeakyWallet(leaked))

        val error = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { sdk.provider(ProviderId("stub-vendor")).getWalletAddress() }
        }

        assertThat(error.cause).isSameInstanceAs(leaked)
    }

    @Test
    fun `core's prose heuristics still read a leaked exception before flooring it`() {
        val sdk = sdkWith(LeakyWallet(RuntimeException("user rejected the request")))

        assertThrows(RainError.UserRejected::class.java) {
            runBlocking { sdk.provider(ProviderId("stub-vendor")).getWalletAddress() }
        }
    }
}
