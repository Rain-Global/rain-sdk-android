package com.rain.sdk.internal.transaction

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import com.rain.sdk.internal.helpers.MockRpcServer
import com.rain.sdk.internal.network.Web3jProvider
import com.rain.sdk.models.RainWithdrawAddresses
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.EthCall
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.CompletableFuture

class RainTransactionBuilderImplTest {

    private companion object {
        const val CHAIN_ID = 1
        const val RPC_URL = "https://rpc.com"
        const val PROXY = "0x1111111111111111111111111111111111111111"

        // keccak256("adminNonce()"), first four bytes, computed outside the SDK.
        const val ADMIN_NONCE_SELECTOR = "0x4ab3be98"
    }

    private lateinit var mockWeb3j: Web3j

    /** Builder over the mocked Web3j, configured for [CHAIN_ID] only. */
    private lateinit var builder: RainTransactionBuilderImpl

    @Before
    fun setUp() {
        // Mock Android classes (URLUtil is static)
        io.mockk.mockkStatic(android.webkit.URLUtil::class)
        io.mockk.every { android.webkit.URLUtil.isValidUrl(any()) } returns true

        mockWeb3j = mockk(relaxed = true)
        builder = RainTransactionBuilderImpl(mapOf(CHAIN_ID to RPC_URL)) { mockWeb3j }

        Web3jProvider.resetForTest()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Web3jProvider.resetForTest()
    }

    @Test
    fun `getLatestNonce decodes the nonce from the eth_call result`() = runBlocking {
        val proxy = "0x1111111111111111111111111111111111111111"
        val expectedNonce = BigInteger.TEN

        // Mock Web3j ethCall
        val mockEthCall = mockk<Request<*, EthCall>>()
        val mockResponse = EthCall()
        // result for 10 in hex
        mockResponse.result = "0x000000000000000000000000000000000000000000000000000000000000000a"

        every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
        every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(mockResponse)

        val nonce = builder.getLatestNonce(CHAIN_ID, proxy)

        assertThat(nonce).isEqualTo(expectedNonce)
    }

    @Test
    fun `getLatestNonce throws instead of defaulting to zero on an undecodable response`() = runBlocking {
        val mockEthCall = mockk<Request<*, EthCall>>()
        val mockResponse = EthCall()
        mockResponse.result = "0x"

        every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
        every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(mockResponse)

        try {
            builder.getLatestNonce(CHAIN_ID, "0x1111111111111111111111111111111111111111")
            org.junit.Assert.fail("Expected RainError.InternalError")
        } catch (e: Exception) {
            assertThat(e).isInstanceOf(RainError.InternalError::class.java)
        }
    }

    @Test
    fun `getLatestNonce sends adminNonce() to the proxy through the default web3j client`() = runBlocking {
        // No web3j fake: the default factory builds the client through Web3jProvider, so the call is
        // encoded, sent over HTTP and decoded the way it is in an app.
        val rpc = MockRpcServer().also { it.start() }
        try {
            rpc.stub(method = "eth_call", result = "0x" + "a".padStart(64, '0'))
            val liveBuilder = RainTransactionBuilderImpl(mapOf(CHAIN_ID to rpc.urlFor(CHAIN_ID)))

            val nonce = liveBuilder.getLatestNonce(CHAIN_ID, PROXY)

            assertThat(nonce).isEqualTo(BigInteger.TEN)
            val params = JSONObject(rpc.recordedBodies.single()).getJSONArray("params")
            assertThat(params.getJSONObject(0).getString("to")).isEqualTo(PROXY)
            assertThat(params.getJSONObject(0).getString("data")).isEqualTo(ADMIN_NONCE_SELECTOR)
            assertThat(params.getString(1)).isEqualTo("latest")
        } finally {
            rpc.shutdown()
        }
    }

    @Test
    fun `getLatestNonce reports a JSON-RPC error whose data is an object`() = runBlocking {
        val rpc = MockRpcServer().also { it.start() }
        try {
            rpc.stubError(
                method = "eth_call",
                code = -32000,
                message = "execution reverted",
                data = JSONObject().put("reason", "paused"),
            )
            val liveBuilder = RainTransactionBuilderImpl(mapOf(CHAIN_ID to rpc.urlFor(CHAIN_ID)))

            try {
                liveBuilder.getLatestNonce(CHAIN_ID, PROXY)
                org.junit.Assert.fail("Expected RainError.InternalError")
            } catch (e: Exception) {
                // The node's own error, not a NetworkError: the error object parsed, data included.
                assertThat(e).isInstanceOf(RainError.InternalError::class.java)
                assertThat(e).hasMessageThat().contains("execution reverted")
            }
        } finally {
            rpc.shutdown()
        }
    }

    @Test
    fun `isCollateralAdmin returns true when the contract says so`() = runBlocking {
        val mockEthCall = mockk<Request<*, EthCall>>()
        val mockResponse = EthCall()
        mockResponse.result = "0x0000000000000000000000000000000000000000000000000000000000000001"

        every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
        every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(mockResponse)

        val result = builder.isCollateralAdmin(
            chainId = CHAIN_ID,
            proxyAddress = "0x1111111111111111111111111111111111111111",
            walletAddress = "0x2222222222222222222222222222222222222222"
        )

        assertThat(result).isTrue()
    }

    @Test
    fun `isCollateralAdmin returns false when the wallet is not an admin`() = runBlocking {
        val mockEthCall = mockk<Request<*, EthCall>>()
        val mockResponse = EthCall()
        mockResponse.result = "0x0000000000000000000000000000000000000000000000000000000000000000"

        every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
        every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(mockResponse)

        val result = builder.isCollateralAdmin(
            chainId = CHAIN_ID,
            proxyAddress = "0x1111111111111111111111111111111111111111",
            walletAddress = "0x2222222222222222222222222222222222222222"
        )

        assertThat(result).isFalse()
    }

    @Test
    fun `isCollateralAdmin returns null when the call reverts`() = runBlocking {
        val mockEthCall = mockk<Request<*, EthCall>>()
        val mockResponse = EthCall()
        mockResponse.error = org.web3j.protocol.core.Response.Error(3, "execution reverted")

        every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
        every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(mockResponse)

        val result = builder.isCollateralAdmin(
            chainId = CHAIN_ID,
            proxyAddress = "0x1111111111111111111111111111111111111111",
            walletAddress = "0x2222222222222222222222222222222222222222"
        )

        assertThat(result).isNull()
    }

    @Test
    fun `isCollateralAdmin returns null when the RPC fails`() = runBlocking {
        every { mockWeb3j.ethCall(any(), any()) } throws RuntimeException("connection reset")

        val result = builder.isCollateralAdmin(
            chainId = CHAIN_ID,
            proxyAddress = "0x1111111111111111111111111111111111111111",
            walletAddress = "0x2222222222222222222222222222222222222222"
        )

        assertThat(result).isNull()
    }

    @Test
    fun `buildEIP712Message resolves the configured RPC when nonce is omitted`() = runBlocking {
        val chainId = CHAIN_ID

        // Mock Web3j response for nonce call
        val mockEthCall = mockk<Request<*, EthCall>>()
        val mockResponse = EthCall()
        mockResponse.result = "0x0000000000000000000000000000000000000000000000000000000000000000" // 0

        every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
        every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(mockResponse)

        val addresses = RainWithdrawAddresses(
            proxyAddress = "0x1111111111111111111111111111111111111111",
            controllerAddress = "0x5555555555555555555555555555555555555555",
            tokenAddress = "0x3333333333333333333333333333333333333333",
            recipientAddress = "0x4444444444444444444444444444444444444444"
        )

        val result = builder.buildEIP712Message(
            chainId = chainId,
            walletAddress = "0x2222222222222222222222222222222222222222",
            addresses = addresses,
            amount = BigDecimal("1.0"),
            decimals = 18,
            nonce = null
        )

        assertThat(result).isNotNull()
    }

    @Test
    fun `buildEIP712Message throws InvalidConfig when RPC missing and nonce missing`() = runBlocking {
        // 999 is not in the builder's endpoint map.
        val chainId = 999

        try {
            val addresses = RainWithdrawAddresses(
                proxyAddress = "0x1111111111111111111111111111111111111111",
                controllerAddress = "0x5555555555555555555555555555555555555555",
                tokenAddress = "0x3333333333333333333333333333333333333333",
                recipientAddress = "0x4444444444444444444444444444444444444444"
            )

            builder.buildEIP712Message(
                chainId = chainId,
                addresses = addresses,
                walletAddress = "0x2222222222222222222222222222222222222222",
                amount = BigDecimal("1.0"),
                decimals = 18,
                nonce = null
            )
            org.junit.Assert.fail("Expected RainError.InvalidConfig")
        } catch (e: Exception) {
            assertThat(e).isInstanceOf(RainError.InvalidConfig::class.java)
        }
    }
}
