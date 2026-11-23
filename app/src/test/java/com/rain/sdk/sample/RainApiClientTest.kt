package com.rain.sdk.sample

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * The demo's Rain API client against a local server: wire parsing, the ready rule, status mapping
 * and the request shape. The client is host reference code, so these pin what a host would copy.
 */
class RainApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RainApiClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = RainApiClient(baseUrl = server.url("/").toString(), apiKey = API_KEY, userId = "user-abc", httpClient = OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun clientWith(httpClient: OkHttpClient) =
        RainApiClient(baseUrl = server.url("/").toString(), apiKey = API_KEY, userId = "user-abc", httpClient = httpClient)

    private fun contracts() = runBlocking { client.fetchCollateralContracts() }

    private fun signature() = runBlocking { client.fetchAdminSignature(signatureRequest()) }

    // ---- contracts ----------------------------------------------------------------------

    @Test
    fun `fetchCollateralContracts parses a full contract and sends the headers`() {
        enqueue(200, "[$FULL_CONTRACT]")

        assertThat(contracts()).containsExactly(
            CollateralContract(
                id = "c-1",
                chainId = 84532,
                proxyAddress = PROXY,
                controllerAddress = CONTROLLER,
                depositAddress = null,
                adminAddresses = listOf(ADMIN),
                contractVersion = 2,
                tokens = listOf(CollateralToken(address = USDC, balance = "12.5", exchangeRate = 1.0, advanceRate = 0.9)),
            )
        )
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/v1/issuing/users/user-abc/contracts")
        assertThat(request.getHeader("Api-Key")).isEqualTo(API_KEY)
        assertThat(request.getHeader("Accept")).isEqualTo("application/json")
    }

    @Test
    fun `fetchCollateralContracts reads explicit nulls and missing lists as empty`() {
        enqueue(
            200,
            """[{"chainId":901,"proxyAddress":"$SOL_PROXY","controllerAddress":"$SOL_CONTROLLER","id":null,""" +
                """"depositAddress":null,"contractVersion":null,"adminAddresses":["$ADMIN",null],"tokens":null}]""",
        )

        val contract = contracts().single()

        assertThat(contract.id).isNull()
        assertThat(contract.depositAddress).isNull()
        assertThat(contract.contractVersion).isNull()
        assertThat(contract.adminAddresses).containsExactly(ADMIN)
        assertThat(contract.tokens).isEmpty()
    }

    @Test
    fun `fetchCollateralContracts reads a blank deposit address and id as absent`() {
        // The deposit screen falls back to the proxy when there is no dedicated address; an empty
        // string must not become the deposit target.
        enqueue(200, """[{"id":"","chainId":84532,"proxyAddress":"$PROXY","controllerAddress":"$CONTROLLER","depositAddress":" "}]""")

        val contract = contracts().single()

        assertThat(contract.id).isNull()
        assertThat(contract.depositAddress).isNull()
    }

    @Test
    fun `fetchCollateralContracts parses a token that has only an address`() {
        enqueue(200, """[{"chainId":84532,"proxyAddress":"$PROXY","controllerAddress":"$CONTROLLER","tokens":[{"address":"$USDC"}]}]""")

        val token = contracts().single().tokens.single()

        assertThat(token).isEqualTo(CollateralToken(address = USDC, balance = "", exchangeRate = 0.0, advanceRate = 0.0))
        assertThat(token.balanceAmount).isNull()
    }

    @Test
    fun `fetchCollateralContracts rejects a balance that is not a decimal`() {
        // A money figure that does not parse fails the load instead of reading as zero.
        enqueue(
            200,
            """[{"chainId":84532,"proxyAddress":"$PROXY","controllerAddress":"$CONTROLLER",""" +
                """"tokens":[{"address":"$USDC","balance":"12,5"}]}]""",
        )

        val error = assertThrows(RainApiError.Decoding::class.java) { contracts() }
        assertThat(error.detail).isEqualTo("token.balance is not a decimal: 12,5")
    }

    @Test
    fun `fetchCollateralContracts rejects a contract without a chain id before returning anything`() {
        // The first contract is fine; the second lacks its chain id, so the whole fetch fails.
        enqueue(200, """[$FULL_CONTRACT,{"proxyAddress":"$PROXY","controllerAddress":"$CONTROLLER"}]""")

        val error = assertThrows(RainApiError.Decoding::class.java) { contracts() }
        assertThat(error.detail).isEqualTo("contract.chainId is missing")
    }

    @Test
    fun `fetchCollateralContracts rejects a blank proxy address and a token without an address`() {
        enqueue(200, """[{"chainId":84532,"proxyAddress":"","controllerAddress":"$CONTROLLER"}]""")
        assertThrows(RainApiError.Decoding::class.java) { contracts() }

        enqueue(200, """[{"chainId":84532,"proxyAddress":"$PROXY","controllerAddress":"$CONTROLLER","tokens":[{"balance":"1"}]}]""")
        val error = assertThrows(RainApiError.Decoding::class.java) { contracts() }
        assertThat(error.detail).isEqualTo("token.address is missing")
    }

    @Test
    fun `fetchCollateralContracts rejects a body that is not a JSON array`() {
        enqueue(200, "<html>maintenance</html>")
        assertThrows(RainApiError.Decoding::class.java) { contracts() }

        enqueue(200, """{"contracts":[]}""")
        assertThrows(RainApiError.Decoding::class.java) { contracts() }

        // An empty 2xx body is how a gateway fails; it must not read as "no contracts".
        enqueue(200, "")
        assertThrows(RainApiError.Decoding::class.java) { contracts() }
    }

    @Test
    fun `fetchAdminSignature rejects an empty 200 body`() {
        enqueue(200, "")
        assertThrows(RainApiError.Decoding::class.java) { signature() }
    }

    @Test
    fun `the user id travels as one encoded path segment`() {
        val spaced = RainApiClient(baseUrl = server.url("/").toString(), apiKey = API_KEY, userId = "user abc/x", httpClient = OkHttpClient())
        enqueue(200, "[]")

        assertThat(runBlocking { spaced.fetchCollateralContracts() }).isEmpty()
        assertThat(server.takeRequest().path).isEqualTo("/v1/issuing/users/user%20abc%2Fx/contracts")
    }

    // ---- withdrawal signature -------------------------------------------------------------

    @Test
    fun `fetchAdminSignature returns a ready signature and encodes the query`() {
        enqueue(200, """{"status":"ready","retryAfter":null,"signature":{"data":"0xabc","salt":"c2FsdA=="},"expiresAt":"2030-01-01T00:00:00Z"}""")

        val signature = runBlocking {
            client.fetchAdminSignature(signatureRequest().copy(recipientAddress = "$RECIPIENT&x=y"))
        }

        assertThat(signature.salt).isEqualTo("c2FsdA==")
        assertThat(signature.signature).isEqualTo("0xabc")
        assertThat(signature.expiresAt).isEqualTo("2030-01-01T00:00:00Z")
        val request = server.takeRequest()
        assertThat(request.getHeader("Api-Key")).isEqualTo(API_KEY)
        assertThat(request.getHeader("Accept")).isEqualTo("application/json")
        val url = requireNotNull(request.requestUrl)
        assertThat(url.encodedPath).isEqualTo("/v1/issuing/users/user-abc/signatures/withdrawals")
        assertThat(url.queryParameter("chainId")).isEqualTo("84532")
        assertThat(url.queryParameter("token")).isEqualTo(USDC)
        assertThat(url.queryParameter("amount")).isEqualTo("100000000")
        assertThat(url.queryParameter("adminAddress")).isEqualTo(ADMIN)
        assertThat(url.queryParameter("recipientAddress")).isEqualTo("$RECIPIENT&x=y")
        assertThat(url.queryParameter("x")).isNull()
        assertThat(url.queryParameter("isAmountNative")).isEqualTo("true")
    }

    @Test
    fun `fetchAdminSignature accepts the ready status in any case`() {
        enqueue(200, """{"status":"Ready","signature":{"data":"0xabc","salt":"c2FsdA=="},"expiresAt":"1893456000"}""")

        assertThat(signature().expiresAt).isEqualTo("1893456000")
    }

    @Test
    fun `fetchAdminSignature reports a pending signature with its retry hint`() {
        enqueue(200, """{"status":"pending","retryAfter":30}""")

        val error = assertThrows(RainApiError.SignatureNotReady::class.java) { signature() }
        assertThat(error.status).isEqualTo("pending")
        assertThat(error.retryAfter).isEqualTo(30)
    }

    @Test
    fun `fetchAdminSignature treats ready without signature bytes as not ready`() {
        enqueue(200, """{"status":"ready","signature":{"data":"","salt":"c2FsdA=="},"expiresAt":"2030-01-01T00:00:00Z"}""")

        val error = assertThrows(RainApiError.SignatureNotReady::class.java) { signature() }
        assertThat(error.status).isEqualTo("ready")
        assertThat(error.retryAfter).isNull()

        // Whitespace is not signature bytes either.
        enqueue(200, """{"status":"ready","signature":{"data":"  ","salt":"c2FsdA=="},"expiresAt":"2030-01-01T00:00:00Z"}""")
        assertThrows(RainApiError.SignatureNotReady::class.java) { signature() }
    }

    @Test
    fun `isAmountNative is forwarded as given`() {
        enqueue(200, """{"status":"ready","signature":{"data":"0xabc","salt":"c2FsdA=="},"expiresAt":"1893456000"}""")

        runBlocking { client.fetchAdminSignature(signatureRequest().copy(isAmountNative = false)) }

        assertThat(requireNotNull(server.takeRequest().requestUrl).queryParameter("isAmountNative")).isEqualTo("false")
    }

    @Test
    fun `fetchAdminSignature reads a missing or null status as unknown`() {
        enqueue(200, "{}")
        assertThat(assertThrows(RainApiError.SignatureNotReady::class.java) { signature() }.status).isEqualTo("unknown")

        // A JSON null must read as absent. The guard is the opt() path in the client; the org.json build
        // whose optString returns the string "null" for it is not the one these JVM tests run on, so this
        // case documents the intended result rather than proving the quirk.
        enqueue(200, """{"status":null,"retryAfter":null}""")
        assertThat(assertThrows(RainApiError.SignatureNotReady::class.java) { signature() }.status).isEqualTo("unknown")
    }

    @Test
    fun `fetchAdminSignature rejects a ready signature without a salt or expiry`() {
        enqueue(200, """{"status":"ready","signature":{"data":"0xabc"},"expiresAt":"2030-01-01T00:00:00Z"}""")
        assertThrows(RainApiError.Decoding::class.java) { signature() }

        enqueue(200, """{"status":"ready","signature":{"data":"0xabc","salt":"c2FsdA=="}}""")
        assertThrows(RainApiError.Decoding::class.java) { signature() }
    }

    // ---- status mapping ---------------------------------------------------------------------

    @Test
    fun `401 and 403 are Unauthorized and never echo the key`() {
        enqueue(401, """{"message":"missing api-key"}""")
        val first = assertThrows(RainApiError.Unauthorized::class.java) { contracts() }
        assertThat(first.statusCode).isEqualTo(401)
        assertThat(first).hasMessageThat().doesNotContain(API_KEY)

        enqueue(403, "")
        val second = assertThrows(RainApiError.Unauthorized::class.java) { contracts() }
        assertThat(second.statusCode).isEqualTo(403)
    }

    @Test
    fun `other statuses are Http with the body cut at 300 characters`() {
        val body = "x".repeat(1000)
        enqueue(500, body)

        val error = assertThrows(RainApiError.Http::class.java) { contracts() }

        assertThat(error.statusCode).isEqualTo(500)
        assertThat(error.body).isEqualTo(body.take(300))
    }

    @Test
    fun `an active signature message survives in the Http error and an empty body reads as null`() {
        enqueue(409, """{"message":"active signature already exists"}""")
        val conflict = assertThrows(RainApiError.Http::class.java) { signature() }
        assertThat(conflict.body).contains("active signature already exists")

        enqueue(502, "")
        val gateway = assertThrows(RainApiError.Http::class.java) { signature() }
        assertThat(gateway.body).isNull()
    }

    @Test
    fun `a dropped connection is Transport carrying the IOException`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val error = assertThrows(RainApiError.Transport::class.java) { contracts() }
        assertThat(error.cause).isInstanceOf(IOException::class.java)
    }

    // ---- timeouts and cancellation ---------------------------------------------------------

    @Test
    fun `a server that never answers is Transport with the socket timeout as cause`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val impatient = clientWith(OkHttpClient.Builder().readTimeout(250, TimeUnit.MILLISECONDS).build())

        val error = assertThrows(RainApiError.Transport::class.java) { runBlocking { impatient.fetchCollateralContracts() } }
        assertThat(error.cause).isInstanceOf(SocketTimeoutException::class.java)
    }

    @Test
    fun `the default client has connect, read and call timeouts and follows no redirect`() {
        val http = RainApiClient.defaultHttpClient()

        assertThat(http.connectTimeoutMillis).isEqualTo(30_000)
        assertThat(http.readTimeoutMillis).isEqualTo(30_000)
        assertThat(http.callTimeoutMillis).isEqualTo(60_000)
        assertThat(http.followRedirects).isFalse()
        assertThat(http.followSslRedirects).isFalse()
    }

    @Test
    fun `a redirect is refused so the key never follows it to another host`() {
        val elsewhere = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/v1/issuing/users/user-abc/contracts").toString())
            )
            val strict = RainApiClient(baseUrl = server.url("/").toString(), apiKey = API_KEY, userId = "user-abc")

            val error = assertThrows(RainApiError.Http::class.java) { runBlocking { strict.fetchCollateralContracts() } }

            assertThat(error.statusCode).isEqualTo(302)
            assertThat(elsewhere.requestCount).isEqualTo(0)
        } finally {
            elsewhere.shutdown()
        }
    }

    @Test
    fun `a plain http base URL is refused unless it names the loopback host`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RainApiClient(baseUrl = "http://example.test", apiKey = API_KEY, userId = "user-abc")
        }
        assertThat(error).hasMessageThat().contains("https")

        // The local test server is plain http on the loopback host; every test here builds against it.
        RainApiClient(baseUrl = server.url("/").toString(), apiKey = API_KEY, userId = "user-abc")
    }

    @Test
    fun `cancelling the caller cancels the call instead of waiting for a timeout`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val patient = clientWith(OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build())
        var seen: Throwable? = null
        val startedAt = System.nanoTime()

        runBlocking {
            val job = launch {
                try {
                    patient.fetchCollateralContracts()
                } catch (e: CancellationException) {
                    seen = e
                } catch (e: RainApiError) {
                    seen = e
                }
            }
            // Let the call reach the wire, then cancel while the server is still holding the response.
            yield()
            assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
            job.cancelAndJoin()
        }

        assertThat(seen).isInstanceOf(CancellationException::class.java)
        // Cancellation is immediate: nowhere near the 10 s read timeout.
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt)).isLessThan(5)
    }

    private fun signatureRequest() = WithdrawalSignatureRequest(
        chainId = 84532,
        tokenAddress = USDC,
        amountBaseUnits = BigInteger("100000000"),
        adminAddress = ADMIN,
        recipientAddress = RECIPIENT,
    )

    private companion object {
        const val API_KEY = "key-123"
        const val PROXY = "0x1111111111111111111111111111111111111111"
        const val CONTROLLER = "0x2222222222222222222222222222222222222222"
        const val ADMIN = "0x3333333333333333333333333333333333333333"
        const val RECIPIENT = "0x4444444444444444444444444444444444444444"
        const val USDC = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
        const val SOL_PROXY = "8YLKoCu7NwqHNS8GzuvA2ibsvLrsg22YMfMDafxh1B15"
        const val SOL_CONTROLLER = "7ZfwS4EeFhNLbC9zL2Z3mYr1QeDeHHsGZ7sV8JNtHqEo"
        const val FULL_CONTRACT = """{"id":"c-1","chainId":84532,"controllerAddress":"$CONTROLLER","proxyAddress":"$PROXY",""" +
            """"depositAddress":null,"adminAddresses":["$ADMIN"],"contractVersion":2,""" +
            """"tokens":[{"address":"$USDC","balance":"12.5","exchangeRate":1.0,"advanceRate":0.9}]}"""
    }
}
