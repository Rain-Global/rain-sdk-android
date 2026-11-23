package com.rain.sdk

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Chain configuration belongs to the [RainSdk] instance built with it, never to shared state.
 * Two SDKs configured for different chains must not see each other's endpoints — otherwise the
 * second `build()` silently redirects the first instance's RPC calls.
 */
class RainSdkInstanceIsolationTest {

    private companion object {
        const val PROXY = "0x1111111111111111111111111111111111111111"
    }

    @Test
    fun `an SDK only resolves the chains it was built with`() {
        val ethereum = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.example/ethereum"))
            .build()

        // Building a second SDK for a different chain must not widen the first one's view.
        RainSdk.builder()
            .rpcEndpoints(mapOf(137 to "https://rpc.example/polygon"))
            .build()

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { ethereum.getLatestNonce(chainId = 137, proxyAddress = PROXY) }
        }
        assertThat(error).hasMessageThat().contains("137")
    }

    @Test
    fun `neither SDK inherits the other's endpoints`() {
        val polygon = RainSdk.builder()
            .rpcEndpoints(mapOf(137 to "https://rpc.example/polygon"))
            .build()
        val avalanche = RainSdk.builder()
            .rpcEndpoints(mapOf(RainChain.AVALANCHE_MAINNET to "https://rpc.example/avalanche"))
            .build()

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                polygon.getLatestNonce(RainChain.AVALANCHE_MAINNET, PROXY)
            }
        }
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { avalanche.getLatestNonce(137, PROXY) }
        }
    }
}
