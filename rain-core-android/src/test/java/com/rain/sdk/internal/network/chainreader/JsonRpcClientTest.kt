package com.rain.sdk.internal.network.chainreader

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The shared JSON-RPC client's wire behaviour: response shapes, node errors, empty bodies and timeouts. */
class JsonRpcClientTest {

    private lateinit var server: MockWebServer
    private val client = JsonRpcClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = server.url("/").toString()

    @Test
    fun `returns the parsed response object and posts a JSON-RPC 2 envelope`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":"0x2a"}"""))

        val response = client.call(url(), "eth_getBalance", listOf("0xabc", "latest"))

        assertThat(response.getString("result")).isEqualTo("0x2a")
        val posted = org.json.JSONObject(server.takeRequest().body.readUtf8())
        assertThat(posted.getString("jsonrpc")).isEqualTo("2.0")
        assertThat(posted.getString("method")).isEqualTo("eth_getBalance")
        assertThat(posted.getJSONArray("params").getString(1)).isEqualTo("latest")
    }

    @Test
    fun `callForHexResult returns the string result and rejects any other shape`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":"0x2a"}"""))
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"unexpected":true}}"""))

        assertThat(client.callForHexResult(url(), "eth_blockNumber", emptyList())).isEqualTo("0x2a")
        assertThrows(RainError.InternalError::class.java) {
            runBlocking { client.callForHexResult(url(), "eth_blockNumber", emptyList()) }
        }
    }

    @Test
    fun `an RPC error object maps to InternalError carrying code and message`() {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"boom"}}"""))

        val error = assertThrows(RainError.InternalError::class.java) {
            runBlocking { client.call(url(), "eth_call", emptyList()) }
        }
        assertThat(error.message).contains("-32000")
        assertThat(error.message).contains("boom")
    }

    @Test
    fun `an RPC error that is not an object maps to InternalError, not a JSON failure`() {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":"rate limited"}"""))

        val error = assertThrows(RainError.InternalError::class.java) {
            runBlocking { client.call(url(), "eth_call", emptyList()) }
        }

        assertThat(error).hasMessageThat().contains("rate limited")
    }

    @Test
    @Suppress("InjectDispatcher") // a real thread is the point: takeRequest blocks the test thread while the call is in flight
    fun `cancelling the caller cancels the request instead of waiting for the answer`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":"0x2a"}""").setHeadersDelay(5, TimeUnit.SECONDS)
        )

        // On another thread: takeRequest below blocks this one, and the call has to be in flight first.
        val read = async(Dispatchers.IO) { client.call(url(), "eth_blockNumber", emptyList()) }
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()
        val started = System.nanoTime()
        read.cancelAndJoin()

        assertThat(read.isCancelled).isTrue()
        // Well under the 5 s the server would have taken, and the client's 10 s call timeout.
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000L)
    }

    @Test
    fun `a revert in the RPC error maps to TransactionSimulationFailed`() {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted"}}"""))

        assertThrows(RainError.TransactionSimulationFailed::class.java) {
            runBlocking { client.call(url(), "eth_call", emptyList()) }
        }
    }

    @Test
    fun `an unparseable RPC url is rejected with InvalidRpcUrl before any request`() {
        assertThrows(RainError.InvalidRpcUrl::class.java) {
            runBlocking { client.call("not a url", "eth_blockNumber", emptyList()) }
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a non-JSON body maps to NetworkError`() {
        server.enqueue(MockResponse().setBody("<html>gateway error</html>"))

        assertThrows(RainError.NetworkError::class.java) {
            runBlocking { client.call(url(), "eth_blockNumber", emptyList()) }
        }
    }

    @Test
    fun `an empty 200 body maps to NetworkError`() {
        server.enqueue(MockResponse())

        assertThrows(RainError.NetworkError::class.java) {
            runBlocking { client.call(url(), "eth_blockNumber", emptyList()) }
        }
    }

    @Test
    fun `an empty 502 body maps to NetworkError`() {
        server.enqueue(MockResponse().setResponseCode(502))

        assertThrows(RainError.NetworkError::class.java) {
            runBlocking { client.call(url(), "eth_blockNumber", emptyList()) }
        }
    }

    @Test
    fun `a server that never answers times out into NetworkError`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val impatient = JsonRpcClient(timeoutSeconds = 1)

        assertThrows(RainError.NetworkError::class.java) {
            runBlocking { impatient.call(url(), "eth_blockNumber", emptyList()) }
        }
    }

    @Test
    fun `a dropped connection maps to NetworkError`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertThrows(RainError.NetworkError::class.java) {
            runBlocking { client.call(url(), "eth_blockNumber", emptyList()) }
        }
    }
}
