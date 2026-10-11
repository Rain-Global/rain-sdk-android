package com.rain.sdk.privy

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.error.VendorErrorClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * What a JSON-RPC call is for; drives how node error messages are classified.
 *
 * - [READ] is a plain state read (balances, gas price): a "revert" in an error message never
 *   means a failed simulation there.
 * - [SIMULATION] executes the transaction against the node (`eth_call` pre-flight,
 *   `eth_estimateGas`): a "revert" means the transaction cannot succeed.
 */
internal enum class RpcCallPurpose { READ, SIMULATION }

/**
 * Minimal JSON-RPC 2.0 client for Privy's read path (balances, gas, fee estimates).
 *
 * Core's `JsonRpcClient` is a `@RainAdapterApi` seam this module does not opt in to yet, so it
 * carries its own client. Privy's
 * EIP-1193 provider is reserved for custody (sign/send); everything read-only goes through here
 * against the RPC endpoints Rain was configured with. Wire format / error mapping mirror core.
 */
internal class PrivyRpcClient(
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

    /** Sends a single JSON-RPC 2.0 request and returns the `result` field as a hex string. */
    suspend fun callForHexResult(
        rpcUrl: String,
        method: String,
        params: List<Any>,
        purpose: RpcCallPurpose = RpcCallPurpose.READ
    ): String {
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
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { it.body.string() }
            }
        } catch (e: IOException) {
            Timber.e(e, "Rain SDK: Privy JSON-RPC transport failure for $method")
            throw RainError.NetworkError(message = "RPC request failed for $method", cause = e)
        }

        val response = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            Timber.e(e, "Rain SDK: Privy JSON-RPC returned non-JSON body for $method")
            throw RainError.NetworkError(message = "RPC request failed for $method", cause = e)
        }

        if (response.has("error") && !response.isNull("error")) {
            val err = response.getJSONObject("error")
            val code = err.optInt("code", -1)
            val message = err.optString("message", "Unknown RPC error")
            throw classifyNodeError(code, message, purpose)
        }

        val result = response.opt("result")
        if (result !is String) {
            throw RainError.InternalError("Unexpected RPC result for method $method")
        }
        return result
    }

    /**
     * Classifies a node JSON-RPC error by message, aware of what the call was [purpose]d for:
     * - [RpcCallPurpose.SIMULATION]: "revert" maps to [RainError.TransactionSimulationFailed]
     *   (checked before the funds phrases so "execution reverted: insufficient allowance"
     *   classifies as a simulation failure), then a funds shortfall maps to
     *   [RainError.InsufficientFunds].
     * - [RpcCallPurpose.READ]: only a funds shortfall maps to [RainError.InsufficientFunds]; a
     *   read can never fail simulation.
     *
     * The funds shortfall is the shared [VendorErrorClassifier]'s funds verdict, the one
     * [PrivyErrorMapping] asks of a wallet-API answer, so every wording it knows ("insufficient
     * funds", the "EVM error: OutOfFunds" Base Sepolia's node answers a native send over the
     * balance with) classifies the same on both Privy paths. Only that verdict is asked for: nodes
     * and gateways say "denied" for auth and rate-limit failures, never for a user rejection (those
     * come from the wallet layer, not JSON-RPC). [RainError.InsufficientFunds] carries no cause, so
     * the node's wording is logged before the verdict is returned. Anything else falls back to
     * [RainError.InternalError] with the code and message preserved.
     */
    private fun classifyNodeError(code: Int, message: String, purpose: RpcCallPurpose): RainError {
        val details = "RPC error [$code]: $message"
        val shortfall = VendorErrorClassifier.insufficientFundsOrNull(message)
        return when {
            purpose == RpcCallPurpose.SIMULATION && message.contains("revert", ignoreCase = true) ->
                RainError.TransactionSimulationFailed(RainError.InternalError(details))
            shortfall != null -> {
                Timber.w("Rain SDK: Privy JSON-RPC refused for a funds shortfall: $details")
                shortfall
            }
            else -> RainError.InternalError(details)
        }
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
