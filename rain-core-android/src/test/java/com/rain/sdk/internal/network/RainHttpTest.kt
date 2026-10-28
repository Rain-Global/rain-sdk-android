package com.rain.sdk.internal.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RainHttpTest {

    @Test
    fun `the variants share one dispatcher and one pool`() {
        val custom = RainHttp.rpcWithCallTimeout(1)
        for (client in listOf(RainHttp.rainApi, RainHttp.web3j, custom)) {
            assertThat(client.dispatcher).isSameInstanceAs(RainHttp.rpc.dispatcher)
            assertThat(client.connectionPool).isSameInstanceAs(RainHttp.rpc.connectionPool)
        }
        assertThat(custom.callTimeoutMillis).isEqualTo(1_000)
        assertThat(RainHttp.rpcWithCallTimeout(RainHttp.RPC_CALL_TIMEOUT_SECONDS)).isSameInstanceAs(RainHttp.rpc)
    }

    @Test
    fun `each variant carries its policy`() {
        assertThat(RainHttp.rpc.callTimeoutMillis).isEqualTo(10_000)
        assertThat(RainHttp.rpc.retryOnConnectionFailure).isTrue()

        assertThat(RainHttp.rainApi.readTimeoutMillis).isEqualTo(45_000)
        assertThat(RainHttp.rainApi.callTimeoutMillis).isEqualTo(45_000)
        assertThat(RainHttp.rainApi.followRedirects).isFalse()
        assertThat(RainHttp.rainApi.followSslRedirects).isFalse()
        assertThat(RainHttp.rainApi.retryOnConnectionFailure).isFalse()

        assertThat(RainHttp.web3j.connectTimeoutMillis).isEqualTo(30_000)
        assertThat(RainHttp.web3j.readTimeoutMillis).isEqualTo(30_000)
        assertThat(RainHttp.web3j.writeTimeoutMillis).isEqualTo(30_000)
        assertThat(RainHttp.web3j.callTimeoutMillis).isEqualTo(60_000)
        assertThat(RainHttp.web3j.retryOnConnectionFailure).isTrue()
    }
}
