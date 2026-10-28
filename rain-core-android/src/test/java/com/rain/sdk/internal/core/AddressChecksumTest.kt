package com.rain.sdk.internal.core

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.internal.network.Web3jProvider
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
 * A mixed-case EVM address whose EIP-55 checksum doesn't match is how a mistyped character shows
 * up, so sends and withdrawals refuse it with RAIN_102 before the wallet signs or sends anything.
 * Addresses written in a single letter case carry no checksum and still go through.
 */
class AddressChecksumTest {

    /** Builder over a mocked Web3j, so a withdrawal can run up to the point it signs. */
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

    // ---- sends ------------------------------------------------------------------------------------

    @Test
    fun `sendNative refuses a recipient whose checksum doesn't match`() {
        val (manager, stub) = TestManagers.stubProviderManager()

        val error = assertThrows(RainError.InvalidRecipient::class.java) {
            runBlocking { manager.sendNative(chainId = 1, to = TYPO, amount = BigDecimal("0.0001")) }
        }

        assertThat(error.errorCode).isEqualTo(RainErrorCode.INVALID_CONFIG)
        assertThat(error.address).isEqualTo(TYPO)
        assertThat(error.reason).contains("checksum")
        assertThat(stub.sendNativeTokenCalls).isEmpty()
    }

    @Test
    fun `sendNative sends to the correctly checksummed spelling of the same address`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()

        manager.sendNative(chainId = 1, to = CHECKSUMMED, amount = BigDecimal("0.0001"))

        assertThat(stub.sendNativeTokenCalls.single().toAddress).isEqualTo(CHECKSUMMED)
    }

    @Test
    fun `sendNative accepts an all-uppercase recipient and forwards its checksummed form`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager()
        val uppercase = "0x" + CHECKSUMMED.removePrefix("0x").uppercase()

        manager.sendNative(chainId = 1, to = uppercase, amount = BigDecimal("0.0001"))

        assertThat(stub.sendNativeTokenCalls.single().toAddress).isEqualTo(CHECKSUMMED)
    }

    @Test
    fun `sendToken refuses a recipient whose checksum doesn't match`() {
        val (manager, stub) = TestManagers.stubProviderManager()

        val error = assertThrows(RainError.InvalidRecipient::class.java) {
            runBlocking {
                manager.sendToken(
                    chainId = 1,
                    contractAddress = TestFixtures.TOKEN_ADDRESS,
                    to = TYPO,
                    amount = BigDecimal("1"),
                    decimals = 6
                )
            }
        }

        assertThat(error.reason).contains("checksum")
        assertThat(stub.sendTokenCalls).isEmpty()
    }

    @Test
    fun `sendToken refuses a token contract whose checksum doesn't match`() {
        val (manager, stub) = TestManagers.stubProviderManager()

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                manager.sendToken(
                    chainId = 1,
                    contractAddress = TYPO,
                    to = TestFixtures.RECIPIENT_ADDRESS,
                    amount = BigDecimal("1"),
                    decimals = 6
                )
            }
        }

        assertThat(error).hasMessageThat().contains("contractAddress checksum")
        assertThat(stub.sendTokenCalls).isEmpty()
    }

    // ---- withdrawals ------------------------------------------------------------------------------

    @Test
    fun `withdrawCollateral refuses a recipient whose checksum doesn't match before anything is signed`() {
        val (manager, stub) = TestManagers.stubProviderManager(transactionBuilder = builder)
        stub.signTypedDataToReturn = TestFixtures.validSignatureHex

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                manager.withdrawCollateral(
                    1,
                    addresses.copy(recipientAddress = TYPO),
                    BigDecimal("1"),
                    6,
                    TestFixtures.adminSignature()
                )
            }
        }

        assertThat(error).hasMessageThat().contains("recipientAddress checksum")
        assertThat(stub.signTypedDataCalls).isEmpty()
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `prepareWithdrawal refuses a recipient whose checksum doesn't match before anything is signed`() {
        val (manager, stub) = TestManagers.stubProviderManager(transactionBuilder = builder)
        stub.signTypedDataToReturn = TestFixtures.validSignatureHex

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                manager.prepareWithdrawal(
                    chainId = 1,
                    addresses = addresses.copy(recipientAddress = TYPO),
                    amount = BigDecimal("1"),
                    decimals = 6,
                    adminSignature = TestFixtures.adminSignature(),
                    nonce = BigInteger.valueOf(7)
                )
            }
        }

        assertThat(error).hasMessageThat().contains("recipientAddress checksum")
        assertThat(stub.signTypedDataCalls).isEmpty()
    }

    private companion object {
        /** A test wallet's address as EIP-55 writes it, from the WALL-113 report. */
        const val CHECKSUMMED = "0xC8C946aC6A9b6bA09889050Fc74a5AF3303c5286"

        /** [CHECKSUMMED] with its first letter's case flipped: the same bytes and a wrong checksum. */
        const val TYPO = "0xc8C946aC6A9b6bA09889050Fc74a5AF3303c5286"
    }
}
