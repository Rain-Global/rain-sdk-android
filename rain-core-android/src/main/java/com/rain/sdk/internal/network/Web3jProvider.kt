package com.rain.sdk.internal.network

import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import java.util.concurrent.ConcurrentHashMap

/** One Web3j per RPC URL over [RainHttp.web3j], process-wide; production never shuts them down. */
internal object Web3jProvider {
    private val cache = ConcurrentHashMap<String, Web3j>()

    /**
     * Return instance Web3j.
     */
    fun getOrCreate(rpcUrl: String): Web3j {
        return cache.computeIfAbsent(rpcUrl) { url ->
            Web3j.build(HttpService(url, RainHttp.web3j, false))
        }
    }

    /**
     * Test teardown only. `Web3j.shutdown()` stops web3j's shared scheduler (`Async.defaultExecutorService`),
     * which every later instance in the process also runs on, so nothing in production calls this.
     */
    fun resetForTest() {
        cache.values.forEach {
            try {
                it.shutdown()
            } catch (e: Exception) {
                // Ignore shutdown errors, only use when mock test
            }
        }
        cache.clear()
    }
}
