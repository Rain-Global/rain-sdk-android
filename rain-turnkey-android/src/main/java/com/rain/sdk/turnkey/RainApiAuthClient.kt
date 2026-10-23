package com.rain.sdk.turnkey

import com.rain.sdk.error.RainError
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

// The device half of Rain's embedded-wallet auth, `/v1/issuing/embedded-wallets` on rain-api: the
// code send, code verify, sign-up and login legs that used to run through Turnkey's auth proxy.
// Every call carries the wallet auth token the host's backend minted, as a bearer. Transport,
// decoding and classification only; the managed-auth controller owns the flow.

// ---------- Wire types ----------

/** `POST /otp/init`'s answer: the code's id and the bundle the device encrypts the typed code to. */
internal data class RainApiOtpChallenge(val otpId: String, val otpEncryptionTargetBundle: String)

/**
 * `POST /otp/verify`'s answer: Turnkey's verification token, a JWT good for ten minutes that
 * carries the public key the code was encrypted with and names the contact, so `toString()`
 * redacts it.
 */
internal data class RainApiVerification(val verificationToken: String) {
    override fun toString(): String = "RainApiVerification(verificationToken=<redacted>)"
}

/** `POST /signup` and `POST /signup/passkey`'s answer. */
internal data class RainApiSignup(val subOrganizationId: String)

/**
 * `POST /login`'s answer, the two fields the SDK reads. The session JWT names the wallet and its
 * user, so `toString()` redacts it.
 */
internal data class RainApiLogin(
    /** The Turnkey session JWT to store under the device key. */
    val sessionToken: String,
    val providerUserId: String,
) {
    override fun toString(): String = "RainApiLogin(sessionToken=<redacted>, providerUserId=$providerUserId)"
}

/**
 * The vendor's `v1ClientSignature`, encoded here so the wire shape is pinned in this module:
 * `{publicKey, scheme, message, signature}` with [CLIENT_SIGNATURE_SCHEME_API_P256] as the scheme.
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
 * on. The kind is read off the status and, for 400, 401, 404 and 409, rain-api's own
 * `{statusCode, error, message}` body; the body is then dropped before this is thrown, so the
 * message names the path, the status and, on a 500, the `correlationId` that finds Rain's server
 * log, and nothing else Rain said. Not a vendor failure for `carriesVendorData`: it carries nothing
 * a host may not see, so the mapper's exit passes it through.
 */
internal class RainApiAuthFailure(
    val kind: Kind,
    val status: Int,
    val path: String,
    /** A 500's `correlationId`, kept only when it is a UUID; null on every other answer. */
    val correlationId: String? = null,
) : RuntimeException("Rain API $path: HTTP $status" + correlationId?.let { " (correlationId $it)" }.orEmpty()) {

    enum class Kind {
        /** 400 "Invalid or expired code": wrong, expired or locked. Keep the attempt; the user retypes. */
        InvalidCode,

        /** 401 for a missing, malformed, unknown, expired or revoked token: ask the host for a new one. */
        TokenRejected,

        /**
         * 403, or 401 "Invalid tenant": the tenant cannot use embedded wallets (flag off, no API
         * access) or the token is of the wrong kind for the route. A new token does not help.
         */
        Forbidden,

        /** rain-api's own 404: no wallet for this contact in this tenant. On a code login the controller signs up. */
        NoWallet,

        /**
         * 409 "A signup is already in progress": rain-api holds a create lock for two minutes after a
         * create, because the create can finish after the device gave up on the call. On a passkey
         * sign-up the controller keeps the passkey and the user retries; elsewhere a refusal like any.
         */
        SignupInProgress,

        /**
         * 409 "This passkey already has a wallet": the account exists and Rain has it on file, so the
         * user signs in with it. The one 409 the mapper names, as `RainError.InvalidConfig`.
         */
        PasskeyTaken,

        /**
         * Every other refusal, told apart by [status] where it matters: another 400, another 409, a
         * 429, a 5xx, a 3xx (redirects are off), or a 404 whose body is not rain-api's.
         */
        Refused,
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
        if (!baseUrl.isHttps && !baseUrl.isLoopback()) throw RainError.InvalidConfig(notHttpsMessage(baseUrl.toString()))
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
                sessionToken = json.requiredString("sessionToken"),
                providerUserId = json.requiredString("providerUserId"),
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
     * A 2xx body through [decode]. No cause on the error, because org.json echoes the whole input in a
     * syntax error's message and the input here is a session or a verification token.
     */
    @Suppress("SwallowedException") // the cause is dropped on purpose, see above
    private fun <T> decodeAnswer(body: String, path: String, decode: (JSONObject) -> T): T = try {
        decode(JSONObject(body))
    } catch (e: JSONException) {
        throw RainError.InternalError("Rain API $path: unreadable answer (${e.javaClass.simpleName})")
    }

    /**
     * The kind for a refusal, from the status and rain-api's `{statusCode, error, message}` body.
     * "Invalid or expired code" is matched whole: `/otp/init`'s "This contact can't receive a code" is
     * also a 400 and must not read as a wrong code before any code was sent. "Invalid tenant" is a
     * 401 too, but a new token fails the same way, so it joins the tenant refusals and the controller
     * never loops on the host's token provider. A 404 is [RainApiAuthFailure.Kind.NoWallet] only when
     * the body is rain-api's own, because a 404 from a proxy in front of it says nothing about the
     * wallet, and the controller must neither sign up nor attach on it. Two 409 phrases are read, both from
     * `/signup/passkey`: "already has a wallet" is [RainApiAuthFailure.Kind.PasskeyTaken] and "already
     * in progress" is [RainApiAuthFailure.Kind.SignupInProgress]; a 409 for a token already used stays
     * [RainApiAuthFailure.Kind.Refused]. Everything else is the status alone.
     */
    private fun classify(status: Int, body: String, path: String): RainApiAuthFailure {
        val rainBody = rainApiBody(body)
        val message = (rainBody?.optString("message")?.takeIf { it.isNotEmpty() } ?: body).lowercase()
        val kind = when (status) {
            HttpURLConnection.HTTP_BAD_REQUEST ->
                if (INVALID_CODE_PHRASE in message) RainApiAuthFailure.Kind.InvalidCode else RainApiAuthFailure.Kind.Refused
            HttpURLConnection.HTTP_UNAUTHORIZED ->
                if (INVALID_TENANT_PHRASE in message) RainApiAuthFailure.Kind.Forbidden else RainApiAuthFailure.Kind.TokenRejected
            HttpURLConnection.HTTP_FORBIDDEN -> RainApiAuthFailure.Kind.Forbidden
            HttpURLConnection.HTTP_NOT_FOUND ->
                if (rainBody?.optInt("statusCode") == status) RainApiAuthFailure.Kind.NoWallet else RainApiAuthFailure.Kind.Refused
            HttpURLConnection.HTTP_CONFLICT -> conflictKind(message)
            else -> RainApiAuthFailure.Kind.Refused
        }
        // A 500's `correlationId` is the one value that finds Rain's server log; kept only when it is a UUID.
        val correlationId = rainBody?.optString("correlationId")
            ?.takeIf { status == HttpURLConnection.HTTP_INTERNAL_ERROR && UUID_PATTERN.matches(it) }
        return RainApiAuthFailure(kind, status, path, correlationId)
    }

    /** rain-api's error body is Fastify's `{statusCode, error, message}`; null when the body is not a JSON object. */
    private fun rainApiBody(body: String): JSONObject? = runCatching { JSONObject(body) }.getOrNull()

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
        private const val SIGNUP_IN_PROGRESS_PHRASE = "already in progress"
        private const val PASSKEY_TAKEN_PHRASE = "already has a wallet"

        /** The two 409 phrases `/signup/passkey` answers with; a 409 for a token already used stays Refused. */
        private fun conflictKind(message: String): RainApiAuthFailure.Kind = when {
            PASSKEY_TAKEN_PHRASE in message -> RainApiAuthFailure.Kind.PasskeyTaken
            SIGNUP_IN_PROGRESS_PHRASE in message -> RainApiAuthFailure.Kind.SignupInProgress
            else -> RainApiAuthFailure.Kind.Refused
        }
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** Whole call and each read: above rain-api's slowest leg, the sub-organization create, so its own answer reaches the device. */
        private const val TIMEOUT_SECONDS = 45L

        /** Redirects and connection-failure retries off: either would re-send the bearer, and every leg has a side effect. */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}

/**
 * [raw] as the Rain API base URL a managed configuration carries: trimmed, parsed by the client's own
 * parser, https, and nothing but a host and a port (a path other than `/`, a query, a fragment or
 * userinfo is refused, because the client appends its routes to whatever it is given), or
 * [RainError.InvalidConfig] (`RAIN_102`) naming the rule. Validated at construction so a typo fails
 * there and not as a network error on the first login. The loopback exemption the client itself
 * keeps for its mock-server tests does not apply here: a configuration names a real host.
 */
internal fun rainApiBaseUrlOrThrow(raw: String): HttpUrl {
    val trimmed = raw.trim()
    val url = trimmed.toHttpUrlOrNull()
    val refusal = when {
        url == null -> "rainApiBaseUrl must be an absolute https URL such as https://api-dev.rain.xyz; got '$trimmed'"
        !url.isHttps -> notHttpsMessage(trimmed)
        !url.namesHostOnly() ->
            "rainApiBaseUrl must name a host and nothing more, no path, query, fragment or userinfo, " +
                "because the SDK appends its routes to it; got '$trimmed'"
        else -> null
    }
    if (refusal != null) throw RainError.InvalidConfig(refusal)
    return checkNotNull(url)
}

/** A path of `/`, no query, no fragment and no userinfo: the client appends its routes to the URL as given. */
private fun HttpUrl.namesHostOnly(): Boolean {
    val bare = encodedPath == "/" && query == null && fragment == null
    return bare && username.isEmpty() && password.isEmpty()
}

/**
 * The host of [rainApiBaseUrl] as [rainApiBaseUrlOrThrow] parses it, lowercased with a trailing dot
 * stripped: the one spelling a deployment check compares, so no second parser can read
 * `https://api%2Erain.xyz` or `https://api.rain.xyz.` differently from the one the configuration
 * is validated with. Internal API for the Rain wallet; throws as [rainApiBaseUrlOrThrow] does.
 */
@InternalRainTurnkeyApi
fun rainApiBaseUrlHost(rainApiBaseUrl: String): String =
    rainApiBaseUrlOrThrow(rainApiBaseUrl).host.removeSuffix(".").lowercase()

private fun notHttpsMessage(url: String): String =
    "rainApiBaseUrl must be https, the wallet auth token travels in a header; got '$url'"

/** The local test server, the one host a plain-http base URL may name. */
private fun HttpUrl.isLoopback(): Boolean = host == "localhost" || host == "127.0.0.1" || host == "::1"

/**
 * A required string field: present, not a JSON null, and a string; a missing field, a JSON null or
 * a non-string is unreadable, a cross-platform contract shared by Rain's SDKs. Read through `opt`
 * because the two org.json implementations disagree on `getString`:
 * the device's coerces a number, the library's throws. A missing field is a [JSONException], which
 * [RainApiAuthClient] turns into the unreadable-answer error.
 */
private fun JSONObject.requiredString(key: String): String =
    opt(key) as? String ?: throw JSONException("$key is missing")
