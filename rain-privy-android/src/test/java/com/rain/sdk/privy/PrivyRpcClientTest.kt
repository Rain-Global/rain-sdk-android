package com.rain.sdk.privy

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test

class PrivyRpcClientTest {

    private lateinit var server: MockWebServer
    private val client = PrivyRpcClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        // Some tests shut the server down mid-body; ignore a redundant shutdown.
        runCatching { server.shutdown() }
    }

    private fun url() = server.url("/").toString()

    @Test
    fun `returns the hex result on a well-formed response`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":"0x2a"}"""))
        val result = client.callForHexResult(url(), "eth_getBalance", listOf("0xabc", "latest"))
        assertThat(result).isEqualTo("0x2a")
    }

    @Test
    fun `maps an unclassified JSON-RPC error object to InternalError with code and message`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"boom"}}""")
        )
        val error = runCatching {
            client.callForHexResult(url(), "eth_call", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.InternalError::class.java)
        assertThat(error!!.message).contains("-32000")
        assertThat(error.message).contains("boom")
    }

    @Test
    fun `classifies an insufficient-funds node error on a read as InsufficientFunds`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"insufficient funds for gas * price + value"}}"""
            )
        )
        val error = runCatching {
            client.callForHexResult(url(), "eth_getBalance", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `classifies the node's OutOfFunds wording as InsufficientFunds on a simulation and on a read`() = runBlocking {
        // Base Sepolia's public node answered a native send over the balance with this on
        // 2026-10-02 (beta QA PV-SEND-01); the host must hear a funds shortfall, not a node failure.
        for (purpose in RpcCallPurpose.entries) {
            server.enqueue(
                MockResponse().setBody(
                    """{"jsonrpc":"2.0","id":1,"error":{"code":-32003,"message":"EVM error: OutOfFunds"}}"""
                )
            )
            val error = runCatching {
                client.callForHexResult(url(), "eth_call", emptyList(), purpose = purpose)
            }.exceptionOrNull()
            assertThat(error).isInstanceOf(RainError.InsufficientFunds::class.java)
        }
    }

    @Test
    fun `a funds shortfall next to a rejection marker is still a funds shortfall at a node`() = runBlocking {
        // No user sits behind a JSON-RPC node, so a "(4001)" in its text cannot be a rejection.
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"insufficient funds for transfer (4001)"}}"""
            )
        )
        val error = runCatching {
            client.callForHexResult(url(), "eth_call", emptyList(), purpose = RpcCallPurpose.SIMULATION)
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `a revert that also names a funds shortfall stays a simulation failure`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted: insufficient funds"}}"""
            )
        )
        val error = runCatching {
            client.callForHexResult(url(), "eth_call", emptyList(), purpose = RpcCallPurpose.SIMULATION)
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
    }

    @Test
    fun `classifies an execution-reverted simulation error as TransactionSimulationFailed`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted"}}"""
            )
        )
        val error = runCatching {
            client.callForHexResult(url(), "eth_call", emptyList(), purpose = RpcCallPurpose.SIMULATION)
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
        assertThat(error!!.message).contains("execution reverted")
    }

    @Test
    fun `a denied node error on a read maps to InternalError, never UserRejected`() = runBlocking {
        // Nodes and gateways say "denied" for auth / rate-limit failures; there is no user
        // in the loop on a JSON-RPC read, so this must never classify as UserRejected.
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"access denied"}}"""
            )
        )
        val error = runCatching {
            client.callForHexResult(url(), "eth_getBalance", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.InternalError::class.java)
        assertThat(error!!.message).contains("access denied")
    }

    @Test
    fun `an execution-reverted error on a read maps to InternalError, not a simulation failure`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted"}}"""
            )
        )
        val error = runCatching {
            client.callForHexResult(url(), "eth_getBalance", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `maps a non-string result to InternalError`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"unexpected":true}}"""))
        val error = runCatching {
            client.callForHexResult(url(), "eth_getBalance", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `maps a non-JSON body to NetworkError`() = runBlocking {
        server.enqueue(MockResponse().setBody("not json at all"))
        val error = runCatching {
            client.callForHexResult(url(), "eth_getBalance", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.NetworkError::class.java)
    }

    @Test
    fun `maps an empty 200 body to NetworkError`() = runBlocking {
        server.enqueue(MockResponse())
        val error = runCatching {
            client.callForHexResult(url(), "eth_getBalance", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.NetworkError::class.java)
    }

    @Test
    fun `maps an empty 502 body to NetworkError`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502))
        val error = runCatching {
            client.callForHexResult(url(), "eth_getBalance", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.NetworkError::class.java)
    }

    @Test
    fun `a server that never answers times out into NetworkError`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val impatient = PrivyRpcClient(timeoutSeconds = 1)
        val error = runCatching {
            impatient.callForHexResult(url(), "eth_getBalance", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.NetworkError::class.java)
    }

    @Test
    fun `rejects an unparseable RPC url with InvalidRpcUrl`() = runBlocking {
        val error = runCatching {
            client.callForHexResult("not a url", "eth_getBalance", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.InvalidRpcUrl::class.java)
    }

    @Test
    fun `maps transport failure to NetworkError`() = runBlocking {
        val dead = url()
        server.shutdown() // nothing listening -> connection refused
        val error = runCatching {
            client.callForHexResult(dead, "eth_getBalance", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.NetworkError::class.java)
    }
}
