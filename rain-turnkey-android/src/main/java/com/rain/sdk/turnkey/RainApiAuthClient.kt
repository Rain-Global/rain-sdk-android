package com.rain.sdk.turnkey

import com.rain.sdk.error.RainError
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// The device half of Rain's embedded-wallet auth, `/v1/issuing/embedded-wallets` on rain-api. It
// stands in for Turnkey's auth proxy on the code send, code verify, sign-up and login legs;
// everything after a session exists stays device to Turnkey. The host's backend mints the wallet
// auth token (`wat_` plus 64 hex characters, ten minutes, bound to one tenant and at most one
// contact) with its Api-Key and hands it to the app. This client only ever carries that token as a
// bearer and never sends a contact, because the token is bound to one.
//
// Transport, decoding and classification only. No flow logic lives here: the managed-auth
// controller decides what a 404 on login means (sign up), when a refused token is fetched again
// and which step spends the code. Nothing calls this client yet; the cutover wires it in.

// ---------- Wire types ----------

/** `POST /otp/init`'s answer: the code's id and the bundle the device encrypts the typed code to. */
internal data class RainApiOtpChallenge(val otpId: String, val otpEncryptionTargetBundle: String)

/**
 * `POST /otp/verify`'s answer: Turnkey's verification token, a JWT good for ten minutes that
 * carries the public key the code was encrypted with.
 */
internal data class RainApiVerification(val verificationToken: String)

/** `POST /signup` and `POST /signup/passkey`'s answer. */
internal data class RainApiSignup(val subOrganizationId: String)

/** `POST /login`'s answer, every field rain-api's `RainWalletContextSchema` requires. */
internal data class RainApiLogin(
    /** `rain`, the one value the schema allows. */
    val provider: String,
    /** The Turnkey session JWT to store under the device key. */
    val sessionToken: String,
    val expiresAt: String,
    val providerUserId: String,
    /** Lowercased by the backend; checksum on display, not here. */
    val walletAddress: String,
    val solanaAddress: String,
)

/**
 * The vendor's `v1ClientSignature`, encoded here so the wire shape is pinned in this module:
 * `{publicKey, scheme, message, signature}` with [CLIENT_SIGNATURE_SCHEME_API_P256] as the
 * scheme, the one value rain-api's `ClientSignatureSchema` allows.
 */
internal data class RainApiClientSignature(
    val publicKey: String,
    val message: String,
    val signature: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("publicKey", publicKey)
        .put("scheme", CLIENT_SIGNATURE_SCHEME_API_P256)
        .put("message", message)
        .put("signature", signature)

    companion object {
        const val CLIENT_SIGNATURE_SCHEME_API_P256 = "CLIENT_SIGNATURE_SCHEME_API_P256"
    }
}

/**
 * What the passkey ceremony produced, for `POST /signup/passkey`: exactly the four fields rain-api
 * validates (`additionalProperties: false` on the attestation object), the vendor's
 * `v1Attestation` field for field, so the ceremony result maps one to one.
 */
internal data class RainApiPasskeyAttestation(
    val credentialId: String,
    val clientDataJson: String,
    val attestationObject: String,
    /** Vendor `v1AuthenticatorTransport` names, `AUTHENTICATOR_TRANSPORT_INTERNAL` and the like. */
    val transports: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("credentialId", credentialId)
        .put("clientDataJson", clientDataJson)
        .put("attestationObject", attestationObject)
        .put("transports", JSONArray(transports))
}

// ---------- Failure ----------

/**
 * A device endpoint's refusal: the status, the request path and a [Kind] the controller branches
 * on. The kind is read off the status and, for 400 and 401, two phrases of rain-api's
 * `{statusCode, error, message}` body; the body is then dropped before this is thrown, so the
 * message names the path and the status and nothing Rain said. Not a vendor failure for
 * `carriesVendorData`: it carries nothing a host may not see, so the mapper's exit passes it through.
 */
internal class RainApiAuthFailure(val kind: Kind, val status: Int, val path: String) :
    RuntimeException("Rain API $path: HTTP $status") {

    enum class Kind {
        /** 400 "Invalid or expired code": wrong, expired or locked. Keep the attempt; the user retypes. */
        InvalidCode,

        /**
         * Any other 400: "Verification failed", a verification for a different contact, a contact
         * that cannot receive a code. Restart the attempt.
         */
        VerificationFailed,

        /** 401 for a missing, malformed, unknown, expired or revoked token: ask the host for a new one. */
        TokenRejected,

        /**
         * The tenant cannot use embedded wallets: 403 (flag off, or a token of the wrong kind for the
         * route) or 401 "Invalid tenant" (no API access, not compliance-approved). A new token does
         * not help.
         */
        Forbidden,

        /** 404: no wallet for this contact in this tenant. On the login leg the controller signs up. */
        NoWallet,

        /** 409: the token was already used, a sign-up is in progress, or the passkey already has a wallet. */
        Conflict,

        /** 429: too many codes requested or too many attempts. */
        RateLimited,

        /** 5xx: no Turnkey configuration for this environment, codes cannot be sent, or Rain's own failure. */
        ServerError,

        /** A status the table has no name for, a 3xx included: redirects are off. */
        Other,
    }
}

// ---------- Client ----------

/** The device calls, each under the host-supplied wallet auth token; the seam the controller's tests fake. */
internal interface RainApiAuthClientProtocol {
    suspend fun initOtp(token: String): RainApiOtpChallenge

    suspend fun verifyOtp(token: String, otpId: String, encryptedOtpBundle: String): RainApiVerification

    suspend fun signup(token: String, verificationToken: String, clientSignature: RainApiClientSignature): RainApiSignup

    suspend fun login(
        token: String,
        verificationToken: String,
        clientSignature: RainApiClientSignature,
        subOrganizationId: String?,
    ): RainApiLogin

    suspend fun signupPasskey(token: String, challenge: String, attestation: RainApiPasskeyAttestation): RainApiSignup
}

/**
 * [RainApiAuthClientProtocol] over OkHttp, already a dependency of this module, and the device's
 * `org.json`. [baseUrl] must be https, because the wallet auth token travels in a header; the
 * loopback host is allowed for tests, as the sample's client allows it. [RainError.InvalidConfig]
 * otherwise.
 *
 * Answers: an `IOException` from the transport is [RainError.NetworkError]; a 2xx body that does
 * not decode is [RainError.InternalError] naming the path; any other status is a
 * [RainApiAuthFailure]. A caller that is cancelled sees its own `CancellationException`, never a
 * [RainError], and the call in flight is cancelled with it.
 */
internal class RainApiAuthClient(
    private val baseUrl: HttpUrl,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : RainApiAuthClientProtocol {

    init {
        if (!baseUrl.isHttps && !baseUrl.isLoopback()) {
            throw RainError.InvalidConfig("Rain API base URL must be https, the wallet auth token travels in a header: $baseUrl")
        }
    }

    /** `POST /otp/init`: a code to the token's contact. No body; the token says where. */
    override suspend fun initOtp(token: String): RainApiOtpChallenge =
        post(OTP_INIT, token, body = null) { json ->
            RainApiOtpChallenge(
                otpId = json.requiredString("otpId"),
                otpEncryptionTargetBundle = json.requiredString("otpEncryptionTargetBundle"),
            )
        }

    /** `POST /otp/verify`: the typed code, encrypted to the challenge's bundle with the device key. */
    override suspend fun verifyOtp(token: String, otpId: String, encryptedOtpBundle: String): RainApiVerification =
        post(OTP_VERIFY, token, JSONObject().put("otpId", otpId).put("encryptedOtpBundle", encryptedOtpBundle)) { json ->
            RainApiVerification(verificationToken = json.requiredString("verificationToken"))
        }

    /** `POST /signup`: a wallet for the token's contact in the token's tenant. */
    override suspend fun signup(token: String, verificationToken: String, clientSignature: RainApiClientSignature): RainApiSignup =
        post(SIGNUP, token, JSONObject().put("verificationToken", verificationToken).put("clientSignature", clientSignature.toJson())) { json ->
            RainApiSignup(subOrganizationId = json.requiredString("subOrganizationId"))
        }

    /**
     * `POST /login`: a Turnkey session on the device key. [subOrganizationId] lets the backend skip
     * its Turnkey lookup after a sign-up; a null is omitted from the body, never sent as `null`,
     * because the schema declares the field a non-empty string and allows no other property.
     */
    override suspend fun login(
        token: String,
        verificationToken: String,
        clientSignature: RainApiClientSignature,
        subOrganizationId: String?,
    ): RainApiLogin {
        val body = JSONObject()
            .put("verificationToken", verificationToken)
            .put("clientSignature", clientSignature.toJson())
        if (subOrganizationId != null) body.put("subOrganizationId", subOrganizationId)
        return post(LOGIN, token, body) { json ->
            RainApiLogin(
                provider = json.requiredString("provider"),
                sessionToken = json.requiredString("sessionToken"),
                expiresAt = json.requiredString("expiresAt"),
                providerUserId = json.requiredString("providerUserId"),
                walletAddress = json.requiredString("walletAddress"),
                solanaAddress = json.requiredString("solanaAddress"),
            )
        }
    }

    /** `POST /signup/passkey`: a wallet whose root user has the passkey as its only authenticator. */
    override suspend fun signupPasskey(token: String, challenge: String, attestation: RainApiPasskeyAttestation): RainApiSignup =
        post(SIGNUP_PASSKEY, token, JSONObject().put("challenge", challenge).put("attestation", attestation.toJson())) { json ->
            RainApiSignup(subOrganizationId = json.requiredString("subOrganizationId"))
        }

    // ---------- HTTP ----------

    /**
     * One exchange. Headers: the bearer, `Accept: application/json`, and `Content-Type` only when
     * there is a body, since Fastify refuses an empty body sent as JSON.
     */
    private suspend fun <T> post(path: String, token: String, body: JSONObject?, decode: (JSONObject) -> T): T {
        val url = baseUrl.newBuilder().addPathSegments(BASE_PATH).addPathSegments(path).build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", JSON_MEDIA_TYPE)
            .post(body?.toString()?.toRequestBody(JSON_MEDIA_TYPE.toMediaType()) ?: EMPTY_BODY)
            .build()
        val reply = try {
            httpClient.newCall(request).await()
        } catch (e: IOException) {
            throw RainError.NetworkError("Rain API ${url.encodedPath}: ${e.javaClass.simpleName}", e)
        }
        if (!reply.successful) throw classify(reply.code, reply.body, url.encodedPath)
        return decodeAnswer(reply.body, url.encodedPath, decode)
    }

    /**
     * A 2xx body through [decode]. No cause on the error, on purpose: the device's org.json echoes
     * the whole input in a syntax error's message (`JSONTokener.syntaxError`), and a 2xx body here
     * is a session or a verification token. The class name says whether the body was not JSON or
     * lacked a field.
     */
    @Suppress("SwallowedException") // the cause is dropped on purpose, see above
    private fun <T> decodeAnswer(body: String, path: String, decode: (JSONObject) -> T): T = try {
        decode(JSONObject(body))
    } catch (e: JSONException) {
        throw RainError.InternalError("Rain API $path: unreadable answer (${e.javaClass.simpleName})")
    }

    /**
     * The kind for a refusal, from the status and, on 400 and 401, the message. "Invalid or expired
     * code" is matched whole: `/otp/init`'s "This contact can't receive a code" is also a 400 and must
     * not read as a wrong code before any code was sent. "Invalid tenant" is a 401 too, but a new token
     * fails the same way, so it joins the tenant refusals and the controller never loops on the host's
     * token provider. Everything else is the status alone.
     */
    private fun classify(status: Int, body: String, path: String): RainApiAuthFailure {
        val message = errorMessage(body).lowercase()
        val kind = when (status) {
            HttpURLConnection.HTTP_BAD_REQUEST ->
                if (INVALID_CODE_PHRASE in message) RainApiAuthFailure.Kind.InvalidCode else RainApiAuthFailure.Kind.VerificationFailed
            HttpURLConnection.HTTP_UNAUTHORIZED ->
                if (INVALID_TENANT_PHRASE in message) RainApiAuthFailure.Kind.Forbidden else RainApiAuthFailure.Kind.TokenRejected
            HttpURLConnection.HTTP_FORBIDDEN -> RainApiAuthFailure.Kind.Forbidden
            HttpURLConnection.HTTP_NOT_FOUND -> RainApiAuthFailure.Kind.NoWallet
            HttpURLConnection.HTTP_CONFLICT -> RainApiAuthFailure.Kind.Conflict
            HTTP_TOO_MANY_REQUESTS -> RainApiAuthFailure.Kind.RateLimited
            in SERVER_ERRORS -> RainApiAuthFailure.Kind.ServerError
            else -> RainApiAuthFailure.Kind.Other
        }
        return RainApiAuthFailure(kind, status, path)
    }

    /** rain-api's error body is Fastify's `{statusCode, error, message}`; any other body reads as itself. */
    private fun errorMessage(body: String): String =
        runCatching { JSONObject(body).optString("message") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: body

    /** What a completed call left behind: the status and the body, read on OkHttp's thread. */
    private class Reply(val code: Int, val successful: Boolean, val body: String)

    /**
     * Runs the call on OkHttp's own threads and reads the body there, so the caller's dispatcher never
     * blocks. Cancelling the caller cancels the call at once instead of waiting for the call timeout,
     * and the "Canceled" failure OkHttp then reports finds the continuation no longer active.
     */
    private suspend fun Call.await(): Reply = suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val reply = try {
                        response.use { Reply(code = it.code, successful = it.isSuccessful, body = it.readBody()) }
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                        return
                    }
                    if (continuation.isActive) continuation.resume(reply)
                }
            }
        )
        continuation.invokeOnCancellation { cancel() }
    }

    /** A 2xx body whole; any other only as far as [ERROR_BODY_LIMIT_BYTES], enough for Fastify's `message`. */
    private fun Response.readBody(): String =
        if (isSuccessful) body.string() else peekBody(ERROR_BODY_LIMIT_BYTES).string()

    companion object {
        /** Every device route sits under this path on the Rain API host. */
        const val BASE_PATH = "v1/issuing/embedded-wallets"
        private const val OTP_INIT = "otp/init"
        private const val OTP_VERIFY = "otp/verify"
        private const val SIGNUP = "signup"
        private const val SIGNUP_PASSKEY = "signup/passkey"
        private const val LOGIN = "login"

        private const val JSON_MEDIA_TYPE = "application/json"
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
        private const val ERROR_BODY_LIMIT_BYTES = 4_096L

        private const val INVALID_CODE_PHRASE = "invalid or expired code"
        private const val INVALID_TENANT_PHRASE = "invalid tenant"
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val SERVER_ERRORS = HttpURLConnection.HTTP_INTERNAL_ERROR..HTTP_LAST_SERVER_ERROR
        private const val HTTP_LAST_SERVER_ERROR = 599

        /**
         * Whole call, and the wait for each read, above rain-api's 30-second deadline on a
         * sub-organization create (`CREATE_SUB_ORGANIZATION_TIMEOUT_MS` in `lib/turnkey/activities.ts`),
         * so the backend's own answer to a slow create reaches the device instead of the client's clock
         * winning first. The read timeout has to move with it: OkHttp's 10-second default applies to each
         * read, the first byte of the answer included (`OkHttpClient.Builder.readTimeout`), and would cut
         * a 30-second create before the call timeout ever applied.
         */
        private const val TIMEOUT_SECONDS = 45L

        /**
         * The client used when none is injected. Redirects off: a 3xx from these routes is not an answer
         * the client can read, and following one would re-send the request, bearer included, wherever the
         * answer pointed (OkHttp drops `Authorization` only when the redirect changes the scheme, host or
         * port, `RetryAndFollowUpInterceptor.buildRedirectRequest`). It surfaces as a [RainApiAuthFailure]
         * of kind [RainApiAuthFailure.Kind.Other] instead.
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/** The local test server, the one host a plain-http base URL may name. */
private fun HttpUrl.isLoopback(): Boolean = host == "localhost" || host == "127.0.0.1" || host == "::1"

/**
 * A required string field: present, not a JSON null, and a string, as the sibling SDK's decoder
 * reads it. Read through `opt` because the two org.json implementations disagree on `getString`:
 * the device's coerces a number, the library's throws. A missing field is a [JSONException], which
 * [RainApiAuthClient] turns into the unreadable-answer error.
 */
private fun JSONObject.requiredString(key: String): String =
    opt(key) as? String ?: throw JSONException("$key is missing")
