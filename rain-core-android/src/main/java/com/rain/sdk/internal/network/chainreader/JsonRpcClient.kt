package com.rain.sdk.internal.network.chainreader

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.RainAdapterApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Minimal JSON-RPC 2.0 client used by the SDK's chain-read layer.
 *
 * Owned by `EvmChainReader` and reused by adapter modules for their own `eth_*` calls
 * (gas, nonce, balance helpers) so the SDK has one HTTP+JSON-RPC implementation rather
 * than per-adapter copies.
 *
 * Stays small on purpose: wire format and error mapping match what the adapters' own
 * request helpers did before this was shared.
 */
@RainAdapterApi
class JsonRpcClient(
    httpClient: OkHttpClient? = null,
    timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
) {
    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 10L
        const val JSON_MEDIA_TYPE = "application/json"
    }

    /**
     * Sends a single JSON-RPC 2.0 request and returns the parsed response object.
     *
     * - Throws [RainError.InvalidRpcUrl] when [rpcUrl] doesn't parse as an HTTP/S URL.
     * - Throws [RainError.NetworkError] for transport failures and non-JSON bodies.
     * - Wraps RPC `error` objects as [RainError.TransactionSimulationFailed] for reverts
     *   (withdrawal flows translate that to [RainError.WithdrawalRevertedByNetwork]),
     *   otherwise [RainError.InternalError] with the RPC code/message.
     */
    suspend fun call(
        rpcUrl: String,
        method: String,
        params: List<Any>
    ): JSONObject {
        val parsedUrl = rpcUrl.toHttpUrlOrNull()
            ?: throw RainError.InvalidRpcUrl(rpcUrl)

        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", paramsToJsonArray(params))
        }

        val request = Request.Builder()
            .url(parsedUrl)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaTypeOrNull()))
            .addHeader("Content-Type", JSON_MEDIA_TYPE)
            .build()

        val raw = try {
            // Enqueued, not executed: the coroutine suspends until OkHttp answers, and cancelling it
            // cancels the call, so a caller that gives up, or a scope whose sibling failed, does not
            // wait for the answer or the call timeout. The body is read on OkHttp's thread.
            client.newCall(request).awaitBody()
        } catch (e: IOException) {
            Timber.e(e, "Rain SDK: JSON-RPC transport failure for $method")
            throw RainError.NetworkError(message = "RPC request failed for $method", cause = e)
        }

        val response = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            Timber.e(e, "Rain SDK: JSON-RPC returned non-JSON body for $method")
            throw RainError.NetworkError(message = "RPC request failed for $method", cause = e)
        }

        if (response.has("error") && !response.isNull("error")) {
            // JSON-RPC 2.0 makes `error` an object. An endpoint that sends a string or a number
            // still fails as a RainError, never as a JSONException out of the chain-read layer.
            val err = response.optJSONObject("error")
                ?: throw RainError.InternalError("RPC error: ${response.optString("error")}")
            val code = err.optInt("code", -1)
            val message = err.optString("message", "Unknown RPC error")
            if (message.contains("revert", ignoreCase = true)) {
                // Withdrawal flows translate this to WithdrawalRevertedByNetwork in
                // TransactionCoordinator; plain sends and reads must not surface RAIN_405.
                throw RainError.TransactionSimulationFailed(
                    IllegalStateException("RPC error [$code]: $message")
                )
            }
            throw RainError.InternalError("RPC error [$code]: $message")
        }
        return response
    }

    /**
     * Convenience wrapper that returns the `result` field as a String. Throws
     * [RainError.InternalError] if the field is missing or not a string.
     */
    suspend fun callForHexResult(
        rpcUrl: String,
        method: String,
        params: List<Any>
    ): String {
        val response = call(rpcUrl, method, params)
        val result = response.opt("result")
        if (result !is String) {
            throw RainError.InternalError("Unexpected RPC result for method $method")
        }
        return result
    }

    private fun paramsToJsonArray(params: List<Any>): JSONArray {
        val array = JSONArray()
        params.forEach { value ->
            when (value) {
                is Map<*, *> -> {
                    val obj = JSONObject()
                    value.forEach { (k, v) -> obj.put(k.toString(), v) }
                    array.put(obj)
                }
                else -> array.put(value)
            }
        }
        return array
    }
}

/**
 * Runs the call on OkHttp's dispatcher and suspends until it answers, resuming at most once. A
 * cancelled coroutine cancels the call; the "Canceled" failure OkHttp then reports is ignored.
 */
private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = try {
                    response.use { it.body.string() }
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                    return
                }
                if (continuation.isActive) continuation.resume(body)
            }
        }
    )
    continuation.invokeOnCancellation { cancel() }
}
