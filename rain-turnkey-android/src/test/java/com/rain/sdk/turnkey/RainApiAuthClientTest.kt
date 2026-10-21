package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The wire contract of the device half of Rain's embedded-wallet auth, against a mock server: paths,
 * headers, bodies, decoding, and the classification the controller keys on. No flow logic here. The
 * cases mirror the sibling SDK's `RainApiAuthClientTests` one for one, plus the ones this client adds
 * on its own rules: no redirect, the timeouts, no body text in a failure, https only. No vendor type
 * is involved, so the suite needs no JDK gate.
 */
class RainApiAuthClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RainApiAuthClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = RainApiAuthClient(server.url("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val signature = RainApiClientSignature(publicKey = "pk", message = "m", signature = "sig")

    private val attestation = RainApiPasskeyAttestation(
        credentialId = "cred-1",
        clientDataJson = "eyJ0eXBlIjoi",
        attestationObject = "o2NmbXQ",
        transports = listOf("AUTHENTICATOR_TRANSPORT_INTERNAL", "AUTHENTICATOR_TRANSPORT_HYBRID"),
    )

    private val loginBody =
        """{"provider":"rain","sessionToken":"jwt","expiresAt":"2026-10-20T00:00:00Z","providerUserId":"u",""" +
            """"walletAddress":"0xabc","solanaAddress":"So1"}"""

    private fun answer(status: Int, body: String): MockResponse =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    /** rain-api's error body, Fastify's `{statusCode, error, message}`. */
    private fun refusal(status: Int, message: String): MockResponse =
        answer(status, """{"statusCode":$status,"error":"E","message":"$message"}""")

    private fun RecordedRequest.json(): JSONObject = JSONObject(body.readUtf8())

    private fun JSONObject.keyList(): List<String> = keys().asSequence().toList()

    private fun takeRequest(): RecordedRequest =
        checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "no request reached the server" }

    private fun enqueueOneOfEach() {
        server.enqueue(answer(200, """{"otpId":"otp-1","otpEncryptionTargetBundle":"bundle"}"""))
        server.enqueue(answer(200, """{"verificationToken":"vt"}"""))
        server.enqueue(answer(200, """{"subOrganizationId":"sub-1"}"""))
        server.enqueue(answer(200, loginBody))
        server.enqueue(answer(200, """{"subOrganizationId":"sub-2"}"""))
    }

    // ---------- requests ----------

    @Test
    fun `every call POSTs under the embedded-wallets base path with the token as a bearer, and init has no body`() {
        enqueueOneOfEach()
        runBlocking {
            assertThat(client.initOtp("wat_1")).isEqualTo(RainApiOtpChallenge("otp-1", "bundle"))
            assertThat(client.verifyOtp("wat_1", "otp-1", "enc")).isEqualTo(RainApiVerification("vt"))
            assertThat(client.signup("wat_1", "vt", signature)).isEqualTo(RainApiSignup("sub-1"))
            client.login("wat_1", "vt", signature, subOrganizationId = null)
            assertThat(client.signupPasskey("wat_2", "Y2hhbGxlbmdl", attestation)).isEqualTo(RainApiSignup("sub-2"))
        }

        val requests = List(5) { takeRequest() }
        assertThat(requests.map { it.path }).containsExactly(
            "/v1/issuing/embedded-wallets/otp/init",
            "/v1/issuing/embedded-wallets/otp/verify",
            "/v1/issuing/embedded-wallets/signup",
            "/v1/issuing/embedded-wallets/login",
            "/v1/issuing/embedded-wallets/signup/passkey",
        ).inOrder()
        assertThat(requests.map { it.method }).containsExactly("POST", "POST", "POST", "POST", "POST")
        assertThat(requests[0].getHeader("Authorization")).isEqualTo("Bearer wat_1")
        assertThat(requests[3].getHeader("Authorization")).isEqualTo("Bearer wat_1")
        assertThat(requests[4].getHeader("Authorization")).isEqualTo("Bearer wat_2")
        requests.forEach { assertThat(it.getHeader("Accept")).isEqualTo("application/json") }
        // init has no body and no Content-Type: Fastify refuses an empty body declared as JSON.
        assertThat(requests[0].bodySize).isEqualTo(0)
        assertThat(requests[0].getHeader("Content-Type")).isNull()
        requests.drop(1).forEach { assertThat(it.getHeader("Content-Type")).startsWith("application/json") }
    }

    @Test
    fun `no request body ever carries a contact`() {
        // The token is bound to the contact; the device never names it, so no body can leak it.
        enqueueOneOfEach()
        runBlocking {
            client.initOtp("wat")
            client.verifyOtp("wat", "otp-1", "enc")
            client.signup("wat", "vt", signature)
            client.login("wat", "vt", signature, subOrganizationId = "sub-1")
            client.signupPasskey("wat", "Y2hhbGxlbmdl", attestation)
        }
        List(5) { takeRequest().body.readUtf8().lowercase() }.forEach { body ->
            listOf("contact", "email", "phone", "@").forEach { word ->
                assertWithMessage(body).that(body).doesNotContain(word)
            }
        }
    }

    @Test
    fun `the client signature is encoded in the vendor's v1ClientSignature shape`() {
        server.enqueue(answer(200, loginBody))
        runBlocking {
            client.login(
                token = "wat",
                verificationToken = "vt",
                clientSignature = RainApiClientSignature(publicKey = "04pk", message = "{\"tokenId\":1}", signature = "ab"),
                subOrganizationId = "sub-9",
            )
        }
        val body = takeRequest().json()
        assertThat(body.keyList()).containsExactly("verificationToken", "clientSignature", "subOrganizationId")
        val signature = body.getJSONObject("clientSignature")
        assertThat(signature.keyList()).containsExactly("publicKey", "scheme", "message", "signature")
        assertThat(signature.getString("publicKey")).isEqualTo("04pk")
        assertThat(signature.getString("scheme")).isEqualTo(RainApiClientSignature.CLIENT_SIGNATURE_SCHEME_API_P256)
        assertThat(signature.getString("scheme")).isEqualTo("CLIENT_SIGNATURE_SCHEME_API_P256")
        assertThat(signature.getString("message")).isEqualTo("{\"tokenId\":1}")
        assertThat(signature.getString("signature")).isEqualTo("ab")
        assertThat(body.getString("verificationToken")).isEqualTo("vt")
        assertThat(body.getString("subOrganizationId")).isEqualTo("sub-9")
    }

    @Test
    fun `the passkey attestation is an object with exactly the four fields rain-api validates`() {
        server.enqueue(answer(200, """{"subOrganizationId":"sub-2"}"""))
        runBlocking { client.signupPasskey("wat", "Y2hhbGxlbmdl", attestation) }

        val body = takeRequest().json()
        // rain-api rejects unknown keys at both levels, so nothing else may ride along.
        assertThat(body.keyList()).containsExactly("challenge", "attestation")
        assertThat(body.getString("challenge")).isEqualTo("Y2hhbGxlbmdl")
        val sent = body.getJSONObject("attestation")
        assertThat(sent.keyList()).containsExactly("credentialId", "clientDataJson", "attestationObject", "transports")
        assertThat(sent.getString("credentialId")).isEqualTo("cred-1")
        assertThat(sent.getString("clientDataJson")).isEqualTo("eyJ0eXBlIjoi")
        assertThat(sent.getString("attestationObject")).isEqualTo("o2NmbXQ")
        val transports = sent.getJSONArray("transports")
        assertThat(List(transports.length()) { transports.getString(it) })
            .containsExactly("AUTHENTICATOR_TRANSPORT_INTERNAL", "AUTHENTICATOR_TRANSPORT_HYBRID")
            .inOrder()
    }

    @Test
    fun `a login without a sub-organization id omits the key rather than sending null`() {
        server.enqueue(answer(200, loginBody))
        runBlocking { client.login("wat", "vt", signature, subOrganizationId = null) }

        val raw = takeRequest().body.readUtf8()
        assertThat(raw).doesNotContain("subOrganizationId")
        assertThat(JSONObject(raw).keyList()).containsExactly("verificationToken", "clientSignature")
    }

    // ---------- answers ----------

    @Test
    fun `a login answer decodes every field`() {
        server.enqueue(
            answer(
                200,
                """{"provider":"rain","sessionToken":"jwt-1","expiresAt":"2026-10-20T10:15:00Z","providerUserId":"user-1",""" +
                    """"walletAddress":"0xeddf67f4b6ba6da613cc1c24433073d6ab4c885d",""" +
                    """"solanaAddress":"7YP827Y77YQTU7MS8R9JGMVJKAkvyiBg6sEVJNeRibge"}""",
            )
        )
        val login = runBlocking { client.login("wat", "vt", signature, subOrganizationId = null) }
        assertThat(login).isEqualTo(
            RainApiLogin(
                provider = "rain",
                sessionToken = "jwt-1",
                expiresAt = "2026-10-20T10:15:00Z",
                providerUserId = "user-1",
                walletAddress = "0xeddf67f4b6ba6da613cc1c24433073d6ab4c885d",
                solanaAddress = "7YP827Y77YQTU7MS8R9JGMVJKAkvyiBg6sEVJNeRibge",
            )
        )
    }

    @Test
    fun `an unreadable 200 is an internal error naming the path, not a provider one, and carries nothing from the body`() {
        val marker = "session-jwt-the-host-must-not-see"
        val bodies = listOf(
            "<html>",
            """{"otpId":"otp-1"}""",
            """{"otpId":7,"otpEncryptionTargetBundle":"bundle"}""",
            """{"otpId":null,"otpEncryptionTargetBundle":"bundle"}""",
            """{"otpId":"$marker",""",
        )
        bodies.forEach { body ->
            server.enqueue(answer(200, body))
            val error = assertThrows(RainError.InternalError::class.java) { runBlocking { client.initOtp("wat") } }
            assertWithMessage(body).that(error.errorCode).isEqualTo(RainErrorCode.INTERNAL_ERROR)
            assertWithMessage(body).that(error).hasMessageThat().contains("/v1/issuing/embedded-wallets/otp/init")
            assertWithMessage(body).that(error.causeChain().mapNotNull { it.message }.none { marker in it }).isTrue()
        }
    }

    // ---------- refusals ----------

    @Test
    fun `status and message classify into the kinds the controller branches on`() {
        val rows = listOf(
            Triple(400, "Invalid or expired code", RainApiAuthFailure.Kind.InvalidCode),
            Triple(400, "Verification failed", RainApiAuthFailure.Kind.VerificationFailed),
            Triple(400, "This contact can't receive a code", RainApiAuthFailure.Kind.VerificationFailed),
            Triple(400, "Token was issued for a different email or phone", RainApiAuthFailure.Kind.VerificationFailed),
            Triple(401, "Token expired", RainApiAuthFailure.Kind.TokenRejected),
            Triple(401, "Bad token", RainApiAuthFailure.Kind.TokenRejected),
            Triple(401, "Invalid tenant", RainApiAuthFailure.Kind.Forbidden),
            Triple(403, "Feature not enabled for tenant", RainApiAuthFailure.Kind.Forbidden),
            Triple(404, "No wallet exists for this contact", RainApiAuthFailure.Kind.NoWallet),
            Triple(409, "Token already used", RainApiAuthFailure.Kind.Conflict),
            Triple(409, "Signup in progress", RainApiAuthFailure.Kind.Conflict),
            Triple(429, "Too many attempts", RainApiAuthFailure.Kind.RateLimited),
            Triple(503, "Turnkey is not configured", RainApiAuthFailure.Kind.ServerError),
            Triple(418, "teapot", RainApiAuthFailure.Kind.Other),
        )
        rows.forEach { (status, message, kind) ->
            server.enqueue(refusal(status, message))
            val failure = assertThrows(RainApiAuthFailure::class.java) { runBlocking { client.initOtp("wat") } }
            assertWithMessage("$status $message").that(failure.kind).isEqualTo(kind)
            assertWithMessage("$status $message").that(failure.status).isEqualTo(status)
            assertWithMessage("$status $message").that(failure.path).isEqualTo("/v1/issuing/embedded-wallets/otp/init")
        }
    }

    @Test
    fun `a non-JSON error body still classifies, read as the message itself`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        val gateway = assertThrows(RainApiAuthFailure::class.java) { runBlocking { client.initOtp("wat") } }
        assertThat(gateway.kind).isEqualTo(RainApiAuthFailure.Kind.ServerError)
        assertThat(gateway.status).isEqualTo(502)

        // A plain-text 400 still reads its phrase, as the sibling SDK's body-prefix fallback does.
        server.enqueue(MockResponse().setResponseCode(400).setBody("Invalid or expired code"))
        val plain = assertThrows(RainApiAuthFailure::class.java) { runBlocking { client.verifyOtp("wat", "otp-1", "enc") } }
        assertThat(plain.kind).isEqualTo(RainApiAuthFailure.Kind.InvalidCode)

        // An empty body classifies by status alone.
        server.enqueue(MockResponse().setResponseCode(401))
        val empty = assertThrows(RainApiAuthFailure::class.java) { runBlocking { client.initOtp("wat") } }
        assertThat(empty.kind).isEqualTo(RainApiAuthFailure.Kind.TokenRejected)
    }

    @Test
    fun `through the mapper a wrong code is RAIN_203, a refused token or tenant RAIN_202, the rest RAIN_501`() {
        fun mapped(status: Int, message: String): RainError {
            server.enqueue(refusal(status, message))
            val failure = assertThrows(RainApiAuthFailure::class.java) {
                runBlocking { client.login("wat", "vt", signature, subOrganizationId = null) }
            }
            return TurnkeyErrorMapping.map(failure)
        }

        val wrongCode = mapped(400, "Invalid or expired code")
        assertThat(wrongCode).isInstanceOf(RainError.InvalidLoginCode::class.java)
        assertThat(wrongCode.errorCode).isEqualTo(RainErrorCode.INVALID_LOGIN_CODE)
        listOf(401 to "Token expired", 401 to "Invalid tenant", 403 to "Feature not enabled for tenant").forEach { (status, message) ->
            val refused = mapped(status, message)
            assertWithMessage("$status $message").that(refused).isInstanceOf(RainError.Unauthorized::class.java)
            assertWithMessage("$status $message").that(refused.errorCode).isEqualTo(RainErrorCode.UNAUTHORIZED)
        }
        listOf(
            400 to "Verification failed",
            404 to "No wallet exists for this contact",
            409 to "Signup in progress",
            429 to "Too many attempts",
            503 to "Turnkey is not configured",
            418 to "teapot",
        ).forEach { (status, message) ->
            val provider = mapped(status, message)
            assertWithMessage("$status $message").that(provider).isInstanceOf(RainError.ProviderError::class.java)
            assertWithMessage("$status $message").that(provider.errorCode).isEqualTo(RainErrorCode.PROVIDER_ERROR)
            assertWithMessage("$status $message").that(provider.cause).isInstanceOf(RainApiAuthFailure::class.java)
        }
    }

    @Test
    fun `a failure names the path and the status and nothing from the body, through the mapper too`() {
        val marker = "body-the-host-must-not-see"
        server.enqueue(refusal(409, "Signup in progress for $marker"))
        val failure = assertThrows(RainApiAuthFailure::class.java) { runBlocking { client.signup("wat", "vt", signature) } }
        assertThat(failure).hasMessageThat().isEqualTo("Rain API /v1/issuing/embedded-wallets/signup: HTTP 409")
        // Nothing for the mapper's exit to remove, so it passes the failure through as the cause.
        assertThat(failure.carriesVendorData()).isFalse()

        val mapped = TurnkeyErrorMapping.map(failure)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapped).hasMessageThat().endsWith("Provider Error: Rain API /v1/issuing/embedded-wallets/signup: HTTP 409")
        assertThat(mapped.cause).isSameInstanceAs(failure)
        assertThat(mapped.causeChain().mapNotNull { it.message }.none { marker in it }).isTrue()
    }

    @Test
    fun `a redirect is not followed and surfaces as Other`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere").toString()))
        // What following the redirect would have found: it must never be requested.
        server.enqueue(answer(200, """{"otpId":"otp-1","otpEncryptionTargetBundle":"bundle"}"""))

        val failure = assertThrows(RainApiAuthFailure::class.java) { runBlocking { client.initOtp("wat") } }
        assertThat(failure.kind).isEqualTo(RainApiAuthFailure.Kind.Other)
        assertThat(failure.status).isEqualTo(302)
        assertThat(server.requestCount).isEqualTo(1)
    }

    // ---------- transport ----------

    @Test
    fun `a transport failure is RAIN_301 with the IOException as its cause`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val error = assertThrows(RainError.NetworkError::class.java) { runBlocking { client.initOtp("wat") } }
        assertThat(error.errorCode).isEqualTo(RainErrorCode.NETWORK_ERROR)
        assertThat(error.cause).isInstanceOf(IOException::class.java)
        assertThat(error).hasMessageThat().contains("/v1/issuing/embedded-wallets/otp/init")
    }

    @Test
    fun `a cancelled caller sees its own cancellation, not a RainError, and the call in flight is cancelled with it`() {
        val callCancelled = AtomicBoolean(false)
        val watched = RainApiAuthClient(
            server.url("/"),
            RainApiAuthClient.defaultHttpClient().newBuilder()
                .eventListener(object : EventListener() {
                    override fun canceled(call: Call) {
                        callCancelled.set(true)
                    }
                })
                .build(),
        )
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        var seen: Throwable? = null
        runBlocking {
            val job = launch {
                try {
                    watched.initOtp("wat")
                } catch (e: CancellationException) {
                    seen = e
                } catch (e: RainError) {
                    seen = e
                }
            }
            // The call runs on OkHttp's threads; once the server has the request the call is in flight and waiting.
            withTimeout(5_000) {
                while (server.requestCount == 0) delay(10)
            }
            job.cancelAndJoin()
        }

        assertThat(seen).isInstanceOf(CancellationException::class.java)
        assertThat(seen).isNotInstanceOf(RainError::class.java)
        assertThat(callCancelled.get()).isTrue()
    }

    @Test
    fun `the default client gives a whole call 45 seconds, waits as long for each read, and follows no redirect`() {
        val http = RainApiAuthClient.defaultHttpClient()
        assertThat(http.callTimeoutMillis).isEqualTo(45_000)
        assertThat(http.readTimeoutMillis).isEqualTo(45_000)
        assertThat(http.followRedirects).isFalse()
        assertThat(http.followSslRedirects).isFalse()
    }

    @Test
    fun `a plain-http base URL off the loopback is refused with RAIN_102 while https and the loopback are accepted`() {
        val refused = assertThrows(RainError.InvalidConfig::class.java) { RainApiAuthClient("http://api-dev.rain.xyz".toHttpUrl()) }
        assertThat(refused.errorCode).isEqualTo(RainErrorCode.INVALID_CONFIG)
        assertThat(refused).hasMessageThat().contains("https")

        RainApiAuthClient("https://api-dev.rain.xyz".toHttpUrl())
        RainApiAuthClient("http://localhost:8080".toHttpUrl())
        RainApiAuthClient("http://127.0.0.1:8080".toHttpUrl())
    }
}
