package com.rain.sdk.sample

import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.wallet.RainWalletContact
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// The Rain issuing API is the host's responsibility, not the SDK's. The SDK builds and sends
// transactions; the data those transactions need, the user's collateral contract and Rain's admin
// withdrawal signature, comes from Rain's REST API, authenticated with a program Api-Key that
// belongs on the partner's backend. The same key mints the wallet auth token the Rain wallet's
// authentication runs under. This file is the demo's own minimal client and a reference for what a
// host implements, normally server-side so the key never ships in an app. The demo types the key on
// the device only because it has no backend of its own.

/** Which Rain deployment the demo talks to. */
enum class SampleRainEnvironment(val baseUrl: String) {
    /** Rain sandbox. */
    SANDBOX("https://api-dev.rain.xyz"),

    /** Rain production. Mainnet: real USDC, real gas. */
    PRODUCTION("https://api.rain.xyz"),
}

/** A user's collateral contract, from `GET /v1/issuing/users/{userId}/contracts`. */
data class CollateralContract(
    val id: String?,
    /** Rain's numeric chain id: EIP-155 for EVM chains, 900 to 902 for the Solana clusters. */
    val chainId: Int,
    /** The collateral proxy holding the assets; `RainWithdrawAddresses.proxyAddress`. */
    val proxyAddress: String,
    /** The controller a withdrawal executes against; `RainWithdrawAddresses.controllerAddress`. */
    val controllerAddress: String,
    /** Where deposits go when Rain sends a dedicated address, seen on EVM and Solana contracts; null, never blank, otherwise. */
    val depositAddress: String?,
    /** Admin signers; one of them is the `adminAddress` of a signature request. */
    val adminAddresses: List<String>,
    val contractVersion: Int?,
    val tokens: List<CollateralToken>,
)

/**
 * A token held in a collateral contract. Name, symbol and decimals are not on the wire; the SDK
 * resolves them from the address through `RainSdk.tokenMetadata` (see `RainSession.fetchCollateralContract`).
 */
data class CollateralToken(
    val address: String,
    /** Balance as a decimal string in whole tokens, exactly as the API sent it; empty when it sent none. */
    val balance: String,
    val exchangeRate: Double,
    val advanceRate: Double,
    val name: String? = null,
    val symbol: String? = null,
    val decimals: Int? = null,
) {
    /** [balance] as a decimal, or null when the API sent none. */
    val balanceAmount: BigDecimal? get() = balance.toBigDecimalOrNull()
}

/**
 * A wallet auth token from `POST /v1/issuing/embedded-wallets/auth-tokens`: what a host's backend
 * hands the app for `RainWalletConfig.authTokenProvider`. Lives ten minutes; bound to the tenant
 * and, when minted for one, the contact. The token is a bearer credential, so `toString()` redacts it.
 */
data class WalletAuthToken(val token: String, val expiresAt: String) {
    override fun toString(): String = "WalletAuthToken(token=<redacted>, expiresAt=$expiresAt)"
}

/** The inputs of `GET /v1/issuing/users/{userId}/signatures/withdrawals`. */
data class WithdrawalSignatureRequest(
    val chainId: Int,
    val tokenAddress: String,
    /** Withdrawal amount in the token's base units. */
    val amountBaseUnits: BigInteger,
    /** One of the contract's `adminAddresses`. */
    val adminAddress: String,
    val recipientAddress: String,
    /**
     * Forwarded as Rain's `isAmountNative` query parameter. `true` is what the Rain SDK's own client
     * always sent for an amount in the token's base units; consult Rain's API reference before changing it.
     */
    val isAmountNative: Boolean = true,
)

/** What the demo's Rain API client can fail with. No message carries the Api-Key. */
sealed class RainApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 401 or 403: the Api-Key was rejected or lacks the permission. */
    class Unauthorized(val statusCode: Int) : RainApiError("Rain API rejected the Api-Key ($statusCode)")

    /** Any other non-2xx status, with the start of the body when there was one. */
    class Http(val statusCode: Int, val body: String?) :
        RainApiError("Rain API error $statusCode" + body?.let { ": $it" }.orEmpty())

    /** Rain has not produced the withdrawal signature yet; retry after [retryAfter] seconds when given. */
    class SignatureNotReady(val status: String, val retryAfter: Int?) :
        RainApiError("Withdrawal signature not ready: status=$status" + retryAfter?.let { " (retry after ${it}s)" }.orEmpty())

    /** The body was not the shape the demo expects; [detail] names the field or the parse failure. */
    class Decoding(val detail: String, cause: Throwable? = null) :
        RainApiError("Rain API response could not be decoded: $detail", cause)

    /** The request never got an HTTP response. */
    class Transport(cause: IOException) : RainApiError("Rain API request failed: ${cause.message}", cause)
}

/**
 * Minimal OkHttp client for the two Rain API calls the wallet flows need. Every request carries the
 * program key in the `Api-Key` header, so the base URL must be https; the loopback host is allowed
 * for tests. Plain Kotlin, no Android types, so it also runs under JVM tests and reads as host
 * reference code; callers do the logging.
 */
@Suppress("TooManyFunctions") // one function per Rain endpoint the demo calls, plus the HTTP plumbing under them
class RainApiClient(
    baseUrl: String,
    private val apiKey: String,
    private val userId: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private val baseUrl: HttpUrl = baseUrl.toHttpUrl()

    init {
        require(this.baseUrl.isHttps || this.baseUrl.isLoopback()) {
            "Rain API base URL must be https, the Api-Key travels in a header: $baseUrl"
        }
    }

    /** `GET /v1/issuing/users/{userId}/contracts`. Tokens come back without name, symbol or decimals. */
    suspend fun fetchCollateralContracts(): List<CollateralContract> {
        val url = userUrl().addPathSegment("contracts").build()
        val body = get(url)
        return decode("contracts") {
            val array = JSONArray(body)
            List(array.length()) { index -> parseContract(array.getJSONObject(index)) }
        }
    }

    /**
     * `GET /v1/issuing/users/{userId}/signatures/withdrawals`: Rain's authorization for one withdrawal,
     * passed whole to `RainClient.withdrawCollateral` or `prepareWithdrawal`.
     *
     * @throws RainApiError.SignatureNotReady until Rain has produced the signature.
     */
    suspend fun fetchAdminSignature(request: WithdrawalSignatureRequest): RainAdminSignature {
        val url = userUrl()
            .addPathSegments("signatures/withdrawals")
            .addQueryParameter("chainId", request.chainId.toString())
            .addQueryParameter("token", request.tokenAddress)
            .addQueryParameter("amount", request.amountBaseUnits.toString())
            .addQueryParameter("adminAddress", request.adminAddress)
            .addQueryParameter("recipientAddress", request.recipientAddress)
            .addQueryParameter("isAmountNative", request.isAmountNative.toString())
            .build()
        val body = get(url)
        return parseSignature(decode("withdrawal signature") { JSONObject(body) })
    }

    /**
     * `POST /v1/issuing/embedded-wallets/auth-tokens`: a wallet auth token for [contact], the
     * canonical contact the SDK names, or for no contact on a passkey sign-up, minted with the
     * Api-Key, which needs the `embeddedWallets:write` scope. This is what a host's backend does
     * behind `RainWalletConfig.authTokenProvider`; the demo does it from the device for want of a
     * backend. The token is never logged here: it is the bearer of every call the SDK makes to Rain's endpoints.
     */
    suspend fun mintWalletAuthToken(contact: RainWalletContact?): WalletAuthToken {
        val url = baseUrl.newBuilder().addPathSegments("v1/issuing/embedded-wallets/auth-tokens").build()
        val body = JSONObject()
        if (contact != null) {
            val type = when (contact) {
                is RainWalletContact.Email -> "email"
                is RainWalletContact.Phone -> "phone"
            }
            body.put("contact", JSONObject().put("type", type).put("value", contact.value))
        }
        val answer = post(url, body)
        return decode("wallet auth token") {
            val json = JSONObject(answer)
            WalletAuthToken(
                token = json.requiredString("token", "wallet auth token"),
                expiresAt = json.requiredString("expiresAt", "wallet auth token"),
            )
        }
    }

    // ---------- HTTP ----------

    /** `.../v1/issuing/users/{userId}` with the id as one encoded segment. */
    private fun userUrl(): HttpUrl.Builder =
        baseUrl.newBuilder().addPathSegments("v1/issuing/users").addPathSegment(userId)

    /** The body on 2xx; 401 and 403 are [RainApiError.Unauthorized], other statuses [RainApiError.Http]. */
    private suspend fun get(url: HttpUrl): String = exchange(
        Request.Builder()
            .url(url)
            .header("Api-Key", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()
    )

    /** [get]'s twin for a JSON body. */
    private suspend fun post(url: HttpUrl, body: JSONObject): String = exchange(
        Request.Builder()
            .url(url)
            .header("Api-Key", apiKey)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()
    )

    private suspend fun exchange(request: Request): String {
        val reply = httpClient.newCall(request).await()
        val error = statusError(reply.code, reply.successful, reply.body)
        if (error != null) throw error
        return reply.body
    }

    private fun statusError(code: Int, successful: Boolean, body: String): RainApiError? = when {
        code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN ->
            RainApiError.Unauthorized(code)
        !successful -> RainApiError.Http(code, body.take(ERROR_BODY_PREVIEW_CHARS).ifBlank { null })
        else -> null
    }

    /** What a completed call left behind: the status and the body, read on OkHttp's thread. */
    private class Reply(val code: Int, val successful: Boolean, val body: String)

    /**
     * Runs the call on OkHttp's own threads and reads the body there, so the caller's dispatcher never
     * blocks. Cancelling the caller cancels the call at once instead of waiting for a socket timeout.
     * The success body is read whole, an error body only up to the preview the error carries.
     */
    private suspend fun Call.await(): Reply = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val reply = try {
                    response.use { it.toReply() }
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(RainApiError.Transport(e))
                    return
                }
                if (continuation.isActive) continuation.resume(reply)
            }

            override fun onFailure(call: Call, e: IOException) {
                // After our own cancel() the continuation is already cancelled; nothing to report.
                if (continuation.isActive) continuation.resumeWithException(RainApiError.Transport(e))
            }
        })
    }

    private fun Response.toReply(): Reply {
        val text = if (isSuccessful) body.string() else peekBody(ERROR_BODY_PREVIEW_CHARS.toLong()).string()
        return Reply(code = code, successful = isSuccessful, body = text)
    }

    // ---------- Wire format ----------

    private inline fun <T> decode(what: String, parse: () -> T): T = try {
        parse()
    } catch (e: JSONException) {
        throw RainApiError.Decoding("$what: ${e.message}", e)
    }

    private fun parseContract(json: JSONObject): CollateralContract = CollateralContract(
        id = json.nonBlankString("id"),
        chainId = json.requiredInt("chainId", "contract"),
        proxyAddress = json.requiredString("proxyAddress", "contract"),
        controllerAddress = json.requiredString("controllerAddress", "contract"),
        depositAddress = json.nonBlankString("depositAddress"),
        adminAddresses = json.optJSONArray("adminAddresses").stringsOrEmpty(),
        contractVersion = json.optionalInt("contractVersion"),
        tokens = json.optJSONArray("tokens")?.let { array -> List(array.length()) { parseToken(array.getJSONObject(it)) } }.orEmpty(),
    )

    private fun parseToken(json: JSONObject): CollateralToken {
        // A balance that does not parse fails the load; a money figure never silently reads as zero.
        val balance = json.optionalString("balance").orEmpty()
        if (balance.isNotBlank() && balance.toBigDecimalOrNull() == null) {
            throw RainApiError.Decoding("token.balance is not a decimal: $balance")
        }
        return CollateralToken(
            address = json.requiredString("address", "token"),
            balance = balance,
            exchangeRate = json.optDouble("exchangeRate", 0.0),
            advanceRate = json.optDouble("advanceRate", 0.0),
        )
    }

    private fun parseSignature(json: JSONObject): RainAdminSignature {
        val status = json.optionalString("status").orEmpty()
        val signature = json.optJSONObject("signature") ?: JSONObject()
        val data = signature.optionalString("data")
        // "ready" without signature bytes is still not ready: an empty signature would only surface
        // later as a confusing on-chain revert.
        if (!status.equals("ready", ignoreCase = true) || data.isNullOrBlank()) {
            throw RainApiError.SignatureNotReady(
                status = status.ifBlank { "unknown" },
                retryAfter = json.optionalInt("retryAfter"),
            )
        }
        return RainAdminSignature(
            salt = signature.requiredString("salt", "withdrawal signature"),
            signature = data,
            expiresAt = json.requiredString("expiresAt", "withdrawal signature"),
        )
    }

    companion object {
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val ERROR_BODY_PREVIEW_CHARS = 300
        private const val TIMEOUT_SECONDS = 30L
        private const val CALL_TIMEOUT_SECONDS = 60L

        /**
         * The one OkHttp client the demo shares between every [RainApiClient] it builds: 30 s to connect
         * and to read, 60 s for a whole call, and no redirects. OkHttp keeps custom headers such as
         * `Api-Key` across a redirect to another host, so a 3xx surfaces as [RainApiError.Http] instead.
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/** The local test server; the only host a plain-http Rain API base URL may name. */
private fun HttpUrl.isLoopback(): Boolean = host == "localhost" || host == "127.0.0.1" || host == "::1"

// The two org.json implementations disagree on the edges: one stringifies a JSON null as "null"
// and coerces numbers to strings, the other returns the default and throws on a number. Reading
// through `opt` and converting by hand behaves the same on both.

private fun JSONObject.optionalString(key: String): String? =
    opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()

/** A non-blank string, or null when the key is absent, a JSON null or blank. */
private fun JSONObject.nonBlankString(key: String): String? = optionalString(key)?.takeIf { it.isNotBlank() }

private fun JSONObject.requiredString(key: String, context: String): String =
    nonBlankString(key) ?: throw RainApiError.Decoding("$context.$key is missing")

private fun JSONObject.optionalInt(key: String): Int? = (opt(key) as? Number)?.toInt()

private fun JSONObject.requiredInt(key: String, context: String): Int =
    optionalInt(key) ?: throw RainApiError.Decoding("$context.$key is missing")

/** The array's non-null entries as strings; an absent array reads as empty. */
private fun JSONArray?.stringsOrEmpty(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).filterNot { isNull(it) }.map { get(it).toString() }
}
