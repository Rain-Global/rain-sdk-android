package com.rain.sdk.internal.network

import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import java.util.concurrent.ConcurrentHashMap

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

    fun shutDownAll() {
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
