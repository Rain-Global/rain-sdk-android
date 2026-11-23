package com.rain.sdk.internal.helpers

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin wrapper around [MockWebServer] that dispatches JSON-RPC requests by `method` name.
 *
 * Tests register per-method stubs (a result value or a forced network failure) and the
 * server replies accordingly. Records every RPC method that has been called so tests can
 * assert which calls were made and in what order.
 *
 * Usage:
 * ```
 * val rpc = MockRpcServer().also { it.start() }
 * rpc.stub(method = "eth_estimateGas", result = "0x5208")
 * rpc.stubNetworkFailure(method = "eth_call")
 * val provider = TurnkeyWalletProvider(rpcEndpoints = mapOf(1 to rpc.urlFor(1)), ...)
 * ...
 * rpc.shutdown()
 * ```
 *
 * Because [MockWebServer] is HTTP and the provider needs a URL keyed by chainId, callers
 * pass [rpc.urlFor(chainId)] into their `rpcEndpoints` map.
 */
internal class MockRpcServer {
    private val server = MockWebServer()

    private data class Stub(val result: Any? = null, val networkFailure: Boolean = false, val error: JSONObject? = null)

    private val stubs = ConcurrentHashMap<String, Stub>()

    /** Stubs keyed by method *and* first parameter, for methods called once per address. */
    private val paramStubs = ConcurrentHashMap<Pair<String, String>, Stub>()

    /** Stubs keyed by method and a substring of the request body, for deeper params. */
    private val bodyStubs = ConcurrentHashMap<Pair<String, String>, Stub>()

    /** Per-method result sequences; each dispatch consumes the next result, the last is sticky. */
    private val sequenceStubs = ConcurrentHashMap<String, MutableList<Any>>()
    private val recorded = mutableListOf<String>()
    private val recordedBodyList = mutableListOf<String>()

    fun start() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val requestJson = runCatching { JSONObject(body) }.getOrNull()
                val method = requestJson?.optString("method", "").orEmpty()
                synchronized(recorded) {
                    recorded += method
                    recordedBodyList += body
                }

                // A parameter-specific stub wins over the method-wide one, so a test can give
                // different answers for e.g. getAccountInfo on a mint vs on a token account.
                val firstParam = requestJson?.optJSONArray("params")?.optString(0, "").orEmpty()
                val stub = sequenceStubs[method]?.let { sequence ->
                    synchronized(sequence) {
                        Stub(result = if (sequence.size > 1) sequence.removeAt(0) else sequence.first())
                    }
                }
                    ?: paramStubs[method to firstParam]
                    ?: bodyStubs.entries
                        .firstOrNull { (key, _) -> key.first == method && body.contains(key.second) }
                        ?.value
                    ?: stubs[method]
                    ?: return MockResponse().setResponseCode(404).setBody(
                        """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"unstubbed method $method"}}"""
                    )

                if (stub.networkFailure) {
                    // Force a network-level failure: drop the socket so OkHttp surfaces an IOException.
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                }

                val payload = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    if (stub.error != null) {
                        put("error", stub.error)
                    } else if (stub.result is Map<*, *> || stub.result is JSONObject) {
                        put("result", stub.result)
                    } else {
                        put("result", stub.result ?: JSONObject.NULL)
                    }
                }
                return MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(payload.toString())
            }
        }
    }

    fun shutdown() {
        server.shutdown()
    }

    /**
     * Returns the server URL to use as the RPC endpoint for [chainId]. The wallet provider
     * just sends POSTs to this URL; the dispatcher routes them by JSON-RPC method.
     */
    fun urlFor(chainId: Int): String = server.url("/v1/chain/$chainId").toString()

    /** Stub a successful response for [method]. [result] is placed under the `result` key. */
    fun stub(method: String, result: String) {
        stubs[method] = Stub(result = result)
    }

    /**
     * Stub a JSON-RPC error for [method]: an HTTP 200 whose `error` object carries [code], [message]
     * and, when given, [data], which may be a string or a structured value such as a [JSONObject].
     */
    fun stubError(method: String, code: Int, message: String, data: Any? = null) {
        val error = JSONObject().put("code", code).put("message", message)
        if (data != null) error.put("data", data)
        stubs[method] = Stub(error = error)
    }

    /**
     * Stub a successful response whose `result` is an arbitrary JSON value — a [JSONObject],
     * [org.json.JSONArray], or a primitive. Used for Solana RPC, whose results are objects /
     * arrays / numbers rather than the hex strings EVM returns.
     */
    fun stubObject(method: String, result: Any) {
        stubs[method] = Stub(result = result)
    }

    /**
     * Stub [method] with a sequence of results: each request consumes the next one and the
     * last repeats — for reads whose answer changes across a flow, such as
     * `getSignaturesForAddress` before vs after a send.
     */
    fun stubObjectSequence(method: String, vararg results: Any) {
        require(results.isNotEmpty()) { "stubObjectSequence needs at least one result" }
        sequenceStubs[method] = results.toMutableList()
    }

    /**
     * Stub [method] for a specific first parameter — the address, for the account-scoped Solana
     * reads (`getAccountInfo`), which one flow calls several times for different accounts.
     */
    fun stubObjectFor(method: String, firstParam: String, result: Any) {
        paramStubs[method to firstParam] = Stub(result = result)
    }

    /**
     * Stub [method] for requests whose body contains [bodyContains] — for parameters nested
     * beyond the first, such as the `programId` that distinguishes the two SPL token programs
     * in `getTokenAccountsByOwner`.
     */
    fun stubObjectWhenBodyContains(method: String, bodyContains: String, result: Any) {
        bodyStubs[method to bodyContains] = Stub(result = result)
    }

    /**
     * Stub a network failure for [method]. The server disconnects the socket so OkHttp
     * surfaces an `IOException` to the caller — used to drive the `RainError.NetworkError`
     * code path in [com.rain.sdk.internal.provider.TurnkeyWalletProvider].
     */
    fun stubNetworkFailure(method: String) {
        stubs[method] = Stub(networkFailure = true)
    }

    /** Stub a network failure for [method] only when the request body contains [bodyContains]. */
    fun stubNetworkFailureWhenBodyContains(method: String, bodyContains: String) {
        bodyStubs[method to bodyContains] = Stub(networkFailure = true)
    }

    /** Methods recorded in dispatch order. Reset by [resetRecordings]. */
    val recordedMethods: List<String>
        get() = synchronized(recorded) { recorded.toList() }

    /** Raw request bodies in dispatch order, parallel to [recordedMethods] — for asserting on params. */
    val recordedBodies: List<String>
        get() = synchronized(recorded) { recordedBodyList.toList() }

    fun resetRecordings() {
        synchronized(recorded) {
            recorded.clear()
            recordedBodyList.clear()
        }
    }
}
