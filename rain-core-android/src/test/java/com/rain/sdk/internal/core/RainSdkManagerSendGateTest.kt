package com.rain.sdk.internal.core

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.internal.network.Web3jProvider
import com.rain.sdk.models.RainPreparedWithdrawal
import com.rain.sdk.models.RainWithdrawAddresses
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.EthCall
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.CompletableFuture

/**
 * Core asks the provider whether it can broadcast on the chain before a withdrawal or approval
 * does any work. The provider's own funnel gate would catch the same chain later, but only after
 * the contract reads and a biometric signing prompt the user then sees refused. Preparing is the
 * exception: it signs but never broadcasts, so it is not gated.
 */
class RainSdkManagerSendGateTest {

    /** Builder over a mocked Web3j, so a prepare can run end to end against a refusing provider. */
    private lateinit var builder: RainTransactionBuilderImpl

    @Before
    fun setUp() {
        mockkStatic(URLUtil::class)
        every { URLUtil.isValidUrl(any()) } returns true

        // Every eth_call answers uint256(1): nonce = 1, and the isAdmin check decodes to true.
        val mockWeb3j = mockk<Web3j>(relaxed = true)
        val mockEthCall = mockk<Request<*, EthCall>>()
        val response = EthCall().apply { result = "0x" + "0".repeat(63) + "1" }
        every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
        every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(response)
        builder = RainTransactionBuilderImpl(mapOf(1 to "https://rpc.example/test")) { mockWeb3j }

        Web3jProvider.resetForTest()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Web3jProvider.resetForTest()
    }

    private val addresses = RainWithdrawAddresses(
        proxyAddress = TestFixtures.PROXY_ADDRESS,
        controllerAddress = TestFixtures.CONTROLLER_ADDRESS,
        tokenAddress = TestFixtures.TOKEN_ADDRESS,
        recipientAddress = TestFixtures.RECIPIENT_ADDRESS
    )

    private fun refusingStub() = StubWalletProvider().apply {
        requireSendSupportError = RainError.ChainNotSupported(1, "this provider cannot broadcast here")
    }

    @Test
    fun `withdrawCollateral asks the provider before any signing or network work`() {
        val (manager, stub) = TestManagers.stubProviderManager(stub = refusingStub())

        val error = assertThrows(RainError.ChainNotSupported::class.java) {
            runBlocking {
                manager.withdrawCollateral(1, addresses, BigDecimal("1"), 6, TestFixtures.adminSignature())
            }
        }

        assertThat(error.chainId).isEqualTo(1)
        assertThat(stub.signTypedDataCalls).isEmpty()
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `prepareWithdrawal is not gated because it never broadcasts`(): Unit = runBlocking {
        // The prepared transaction is the host's own-RPC path on a chain the provider cannot
        // broadcast on, so a refusing provider is still asked to sign, and nothing is sent.
        val (manager, stub) = TestManagers.stubProviderManager(stub = refusingStub(), transactionBuilder = builder)
        stub.signTypedDataToReturn = TestFixtures.validSignatureHex

        val prepared = manager.prepareWithdrawal(
            chainId = 1,
            addresses = addresses,
            amount = BigDecimal("1"),
            decimals = 6,
            adminSignature = TestFixtures.adminSignature(),
            nonce = BigInteger.valueOf(7)
        )

        assertThat(prepared).isInstanceOf(RainPreparedWithdrawal.Evm::class.java)
        assertThat(stub.signTypedDataCalls).hasSize(1)
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `a Solana withdrawal is refused before the composer reads anything`() {
        val (manager, stub) = TestManagers.stubProviderManager(
            stub = refusingStub(),
            rpcEndpoints = mapOf(RainChain.SOLANA_DEVNET to "http://127.0.0.1:1/unreachable")
        )

        assertThrows(RainError.ChainNotSupported::class.java) {
            runBlocking {
                manager.withdrawCollateral(
                    RainChain.SOLANA_DEVNET,
                    addresses,
                    BigDecimal("1"),
                    6,
                    TestFixtures.adminSignature()
                )
            }
        }

        assertThat(stub.sendSolanaTransactionCalls).isEmpty()
    }

    @Test
    fun `approveTokenAllowance is refused before the wallet is touched`() {
        val (manager, stub, _) = TestManagers.approvalManager(stub = refusingStub())

        assertThrows(RainError.ChainNotSupported::class.java) {
            runBlocking {
                manager.approveTokenAllowance(
                    RainChain.BASE_SEPOLIA,
                    TestFixtures.AUTH_PULL_USDC_ADDRESS,
                    "0x5a6E6b0d5Ea051CfFF9b3dcC2Aa8Dac226458f29"
                )
            }
        }

        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `estimateApprovalFee is a read and is not gated`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager(stub = refusingStub())
        stub.estimateTransactionFeeToReturn = BigDecimal("0.001")

        val fee = manager.estimateApprovalFee(
            RainChain.BASE_SEPOLIA,
            TestFixtures.AUTH_PULL_USDC_ADDRESS,
            "0x5a6E6b0d5Ea051CfFF9b3dcC2Aa8Dac226458f29"
        )

        assertThat(fee).isEqualTo(BigDecimal("0.001"))
    }
}
