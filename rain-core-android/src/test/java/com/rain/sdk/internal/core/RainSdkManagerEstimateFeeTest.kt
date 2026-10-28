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
import com.rain.sdk.models.UnsignedSolanaTransfer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
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
 * Manager-contract tests for `estimateWithdrawalFee`: the primary BigDecimal overload
 * (the caller-supplied withdrawal authorization) and the Solana guard, plus the error contract on
 * both estimate paths: an adapter-mapped RainError passes through untouched, a raw exception floors
 * at InternalError.
 */
class RainSdkManagerEstimateFeeTest {

    private val addresses = RainWithdrawAddresses(
        proxyAddress = TestFixtures.PROXY_ADDRESS,
        controllerAddress = TestFixtures.CONTROLLER_ADDRESS,
        tokenAddress = TestFixtures.TOKEN_ADDRESS,
        recipientAddress = TestFixtures.RECIPIENT_ADDRESS
    )

    /** Builder over a mocked Web3j; handed to every manager these tests build. */
    private lateinit var builder: RainTransactionBuilderImpl

    @Before
    fun setUp() {
        mockkStatic(URLUtil::class)
        every { URLUtil.isValidUrl(any()) } returns true

        // Stub every eth_call (admin-nonce read + isAdmin preflight) with uint256(1): nonce = 1,
        // and Bool decode = true so the collateral-admin check passes.
        val mockWeb3j = mockk<Web3j>(relaxed = true)
        val mockEthCall = mockk<Request<*, EthCall>>()
        val response = EthCall().apply {
            result = "0x" + "0".repeat(63) + "1"
        }
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

    @Test
    fun `estimateWithdrawalFee signs once and returns the provider's BigDecimal fee`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager(transactionBuilder = builder)
        stub.signTypedDataToReturn = TestFixtures.validSignatureHex
        stub.estimateTransactionFeeToReturn = BigDecimal("0.00042")
        // Caller-supplied authorization, distinct from the wallet signature so the calldata
        // assertion below proves the caller's values (not the wallet's) were embedded.
        val callerSignatureHex = "0x" + "02".repeat(65)

        val fee = manager.estimateWithdrawalFee(
            chainId = 1,
            addresses = addresses,
            amount = BigDecimal("100.0"),
            decimals = 6,
            adminSignature = TestFixtures.adminSignature(signature = callerSignatureHex)
        )

        assertThat(fee).isEqualTo(BigDecimal("0.00042"))
        // One wallet signature (the EIP-712 payload), and one fee estimate against the controller.
        assertThat(stub.signTypedDataCalls).hasSize(1)
        val estimate = stub.estimateTransactionFeeCalls.single()
        assertThat(estimate.chainId).isEqualTo(1)
        assertThat(estimate.to).isEqualTo(TestFixtures.CONTROLLER_ADDRESS)
        assertThat(estimate.value).isEqualTo("0x0")
        // The whole point of this overload: the estimated calldata embeds the caller-supplied
        // withdrawal authorization (salt bytes and signature bytes), not placeholders.
        val calldata = estimate.data.lowercase()
        assertThat(calldata).contains("aa".repeat(32)) // validSaltBase64 decodes to 32 x 0xAA
        assertThat(calldata).contains("02".repeat(65)) // caller's admin signature bytes
        // Nothing was broadcast.
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    // ---- the error contract on the estimate paths ------------------------------------------
    //
    // An adapter converts its vendor's failure to a RainError before it leaves the adapter, and core
    // passes that RainError through with its code intact. Core's own InternalError wrap on these two
    // paths is the floor for a failure nothing mapped, not a relabelling of the adapter's verdict.

    @Test
    fun `estimateGas passes an adapter-mapped ProviderError through with its code intact`(): Unit = runBlocking {
        val mapped = RainError.ProviderError(RuntimeException("node refused the estimate"))
        val stub = object : StubWalletProvider() {
            override suspend fun estimateTransactionFee(
                chainId: Int,
                from: String,
                to: String,
                data: String,
                value: String
            ): BigDecimal = throw mapped
        }
        val (manager, _) = TestManagers.stubProviderManager(stub, transactionBuilder = builder)

        val error = assertThrows(RainError.ProviderError::class.java) {
            runBlocking {
                manager.estimateGas(
                    chainId = 1,
                    from = TestFixtures.WALLET_ADDRESS,
                    to = TestFixtures.CONTROLLER_ADDRESS,
                    data = "0x"
                )
            }
        }
        assertThat(error).isSameInstanceAs(mapped)
    }

    @Test
    fun `estimateWithdrawalFee passes an adapter-mapped ProviderError through with its code intact`(): Unit =
        runBlocking {
            val mapped = RainError.ProviderError(RuntimeException("node refused the estimate"))
            val stub = object : StubWalletProvider() {
                override suspend fun estimateTransactionFee(
                    chainId: Int,
                    from: String,
                    to: String,
                    data: String,
                    value: String
                ): BigDecimal = throw mapped
            }
            val (manager, _) = TestManagers.stubProviderManager(stub, transactionBuilder = builder)
            stub.signTypedDataToReturn = TestFixtures.validSignatureHex

            val error = assertThrows(RainError.ProviderError::class.java) {
                runBlocking {
                    manager.estimateWithdrawalFee(
                        chainId = 1,
                        addresses = addresses,
                        amount = BigDecimal("100.0"),
                        decimals = 6,
                        adminSignature = TestFixtures.adminSignature()
                    )
                }
            }
            assertThat(error).isSameInstanceAs(mapped)
        }

    @Test
    fun `estimateWithdrawalFee still floors a failure nothing mapped at InternalError`(): Unit = runBlocking {
        val raw = IllegalStateException("truncated response")
        val stub = object : StubWalletProvider() {
            override suspend fun estimateTransactionFee(
                chainId: Int,
                from: String,
                to: String,
                data: String,
                value: String
            ): BigDecimal = throw raw
        }
        val (manager, _) = TestManagers.stubProviderManager(stub, transactionBuilder = builder)
        stub.signTypedDataToReturn = TestFixtures.validSignatureHex

        val error = assertThrows(RainError.InternalError::class.java) {
            runBlocking {
                manager.estimateWithdrawalFee(
                    chainId = 1,
                    addresses = addresses,
                    amount = BigDecimal("100.0"),
                    decimals = 6,
                    adminSignature = TestFixtures.adminSignature()
                )
            }
        }
        assertThat(error.cause).isSameInstanceAs(raw)
    }

    @Test
    fun `estimateGas still floors a failure nothing mapped at InternalError`(): Unit = runBlocking {
        // A host-supplied provider that lets a raw exception escape, or a failure raised inside core.
        val raw = IllegalStateException("truncated response")
        val stub = object : StubWalletProvider() {
            override suspend fun estimateTransactionFee(
                chainId: Int,
                from: String,
                to: String,
                data: String,
                value: String
            ): BigDecimal = throw raw
        }
        val (manager, _) = TestManagers.stubProviderManager(stub, transactionBuilder = builder)

        val error = assertThrows(RainError.InternalError::class.java) {
            runBlocking {
                manager.estimateGas(
                    chainId = 1,
                    from = TestFixtures.WALLET_ADDRESS,
                    to = TestFixtures.CONTROLLER_ADDRESS,
                    data = "0x"
                )
            }
        }
        assertThat(error.cause).isSameInstanceAs(raw)
    }

    @Test
    fun `estimateWithdrawalFee throws InvalidAmount for over-precision amounts`(): Unit = runBlocking {
        val (manager, stub) = TestManagers.stubProviderManager(transactionBuilder = builder)
        stub.signTypedDataToReturn = TestFixtures.validSignatureHex

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                manager.estimateWithdrawalFee(
                    chainId = 1,
                    addresses = addresses,
                    amount = BigDecimal("1.2345678"), // 7 decimal places on a 6-decimal token
                    decimals = 6,
                    adminSignature = TestFixtures.adminSignature()
                )
            }
        }
    }

    @Test
    fun `estimateWithdrawalFee translates a provider simulation failure to WithdrawalRevertedByNetwork`(): Unit =
        runBlocking {
            val simulationFailure = RainError.TransactionSimulationFailed(RuntimeException("execution reverted"))
            val stub = object : StubWalletProvider() {
                override suspend fun estimateTransactionFee(
                    chainId: Int,
                    from: String,
                    to: String,
                    data: String,
                    value: String
                ): BigDecimal = throw simulationFailure
            }
            val (manager, _) = TestManagers.stubProviderManager(stub, transactionBuilder = builder)
            stub.signTypedDataToReturn = TestFixtures.validSignatureHex

            val error = assertThrows(RainError.WithdrawalRevertedByNetwork::class.java) {
                runBlocking {
                    manager.estimateWithdrawalFee(
                        chainId = 1,
                        addresses = addresses,
                        amount = BigDecimal("100.0"),
                        decimals = 6,
                        adminSignature = TestFixtures.adminSignature()
                    )
                }
            }
            assertThat(error.cause).isSameInstanceAs(simulationFailure)
        }

    @Test
    fun `withdrawCollateral translates a provider simulation failure to WithdrawalRevertedByNetwork`(): Unit =
        runBlocking {
            val simulationFailure = RainError.TransactionSimulationFailed(RuntimeException("execution reverted"))
            val stub = object : StubWalletProvider() {
                override suspend fun sendTransaction(
                    chainId: Int,
                    from: String,
                    to: String,
                    data: String,
                    value: String
                ): String = throw simulationFailure
            }
            val (manager, _) = TestManagers.stubProviderManager(stub, transactionBuilder = builder)
            stub.signTypedDataToReturn = TestFixtures.validSignatureHex

            val error = assertThrows(RainError.WithdrawalRevertedByNetwork::class.java) {
                runBlocking {
                    manager.withdrawCollateral(
                        chainId = 1,
                        addresses = addresses,
                        amount = BigDecimal("100.0"),
                        decimals = 6,
                        adminSignature = TestFixtures.adminSignature()
                    )
                }
            }
            assertThat(error.cause).isSameInstanceAs(simulationFailure)
        }

    @Test
    fun `prepareWithdrawal returns the exact transaction withdrawCollateral broadcasts`(): Unit =
        runBlocking {
            val (manager, stub) = TestManagers.stubProviderManager(transactionBuilder = builder)
            stub.signTypedDataToReturn = TestFixtures.validSignatureHex

            // A pinned nonce is what makes the two runs comparable: the salt is random per call,
            // but the caller-supplied signature bytes and the encoded arguments are not.
            val prepared = manager.prepareWithdrawal(
                chainId = 1,
                addresses = addresses,
                amount = BigDecimal("100.0"),
                decimals = 6,
                adminSignature = TestFixtures.adminSignature(),
                nonce = BigInteger.valueOf(7)
            )
            val parameters = (prepared as RainPreparedWithdrawal.Evm).parameters

            // Preparing broadcasts nothing.
            assertThat(stub.sendTransactionCalls).isEmpty()

            manager.withdrawCollateral(
                chainId = 1,
                addresses = addresses,
                amount = BigDecimal("100.0"),
                decimals = 6,
                adminSignature = TestFixtures.adminSignature(),
                nonce = BigInteger.valueOf(7)
            )
            val broadcast = stub.sendTransactionCalls.single()

            // The prepared transaction is complete, not bare calldata — from/to/value are the
            // three fields the old `transactionData: String?` could not carry.
            assertThat(parameters.from).isEqualTo(broadcast.from)
            assertThat(parameters.to).isEqualTo(broadcast.to)
            assertThat(parameters.value).isEqualTo(broadcast.value)
            assertThat(parameters.from).isNotEmpty()
            // Same selector and same encoded length; only the random salt differs.
            assertThat(parameters.data.take(10)).isEqualTo(broadcast.data.take(10))
            assertThat(parameters.data).hasLength(broadcast.data.length)
        }

    @Test
    fun `estimateWithdrawalFee quotes the network cost and signs once when the provider sponsors the fee`(): Unit =
        runBlocking {
            // The quote is what the network charges, which the sponsor pays: the withdrawal is built
            // and signed once to price it, exactly as on a self-paid chain.
            val (manager, stub) = TestManagers.stubProviderManager(transactionBuilder = builder)
            stub.sponsorsFeesToReturn = true
            stub.signTypedDataToReturn = TestFixtures.validSignatureHex
            stub.estimateTransactionFeeToReturn = BigDecimal("0.001")

            val fee = manager.estimateWithdrawalFee(
                chainId = 1,
                addresses = addresses,
                amount = BigDecimal("100.0"),
                decimals = 6,
                adminSignature = TestFixtures.adminSignature()
            )

            assertThat(fee).isEqualToIgnoringScale(BigDecimal("0.001"))
            assertThat(stub.signTypedDataCalls).hasSize(1)
            assertThat(stub.estimateTransactionFeeCalls).hasSize(1)
        }

    @Test
    fun `estimateWithdrawalFee rejects a Solana chain id instead of taking the EVM path`(): Unit =
        runBlocking {
            val (manager, stub) = TestManagers.stubProviderManager(transactionBuilder = builder)

            assertThrows(RainError.InternalError::class.java) {
                runBlocking {
                    manager.estimateWithdrawalFee(
                        chainId = RainChain.SOLANA_DEVNET,
                        addresses = addresses,
                        amount = BigDecimal("100.0"),
                        decimals = 6,
                        adminSignature = TestFixtures.adminSignature()
                    )
                }
            }
            // The guard fires before any signing or estimation.
            assertThat(stub.signTypedDataCalls).isEmpty()
            assertThat(stub.estimateTransactionFeeCalls).isEmpty()
        }

    @Test
    fun `estimateWithdrawalFee on a prepared withdrawal quotes the prepared parameters without signing again`(): Unit =
        runBlocking {
            val (manager, stub) = TestManagers.stubProviderManager(transactionBuilder = builder)
            stub.signTypedDataToReturn = TestFixtures.validSignatureHex
            stub.estimateTransactionFeeToReturn = BigDecimal("0.002")
            val prepared = manager.prepareWithdrawal(
                chainId = 1,
                addresses = addresses,
                amount = BigDecimal("100.0"),
                decimals = 6,
                adminSignature = TestFixtures.adminSignature(),
                nonce = BigInteger.valueOf(7)
            )
            val parameters = (prepared as RainPreparedWithdrawal.Evm).parameters

            val fee = manager.estimateWithdrawalFee(chainId = 1, prepared = prepared)

            assertThat(fee).isEqualToIgnoringScale(BigDecimal("0.002"))
            // The prepare signed once; the estimate on its result did not sign again.
            assertThat(stub.signTypedDataCalls).hasSize(1)
            val call = stub.estimateTransactionFeeCalls.single()
            assertThat(call.chainId).isEqualTo(1)
            assertThat(call.from).isEqualTo(parameters.from)
            assertThat(call.to).isEqualTo(parameters.to)
            assertThat(call.data).isEqualTo(parameters.data)
            assertThat(call.value).isEqualTo(parameters.value)
        }

    @Test
    fun `estimateWithdrawalFee on a Solana preparation throws InternalError before asking the provider`(): Unit =
        runBlocking {
            val (manager, stub) = TestManagers.stubProviderManager(transactionBuilder = builder)
            val prepared = RainPreparedWithdrawal.Solana(UnsignedSolanaTransfer(ByteArray(1), recentBlockhash = "hash"))

            assertThrows(RainError.InternalError::class.java) {
                runBlocking { manager.estimateWithdrawalFee(chainId = RainChain.SOLANA_DEVNET, prepared = prepared) }
            }

            assertThat(stub.estimateTransactionFeeCalls).isEmpty()
        }

    @Test
    fun `estimateWithdrawalFee on a prepared withdrawal rejects a Solana chain id before asking the provider`(): Unit =
        runBlocking {
            val (manager, stub) = TestManagers.stubProviderManager(transactionBuilder = builder)
            stub.signTypedDataToReturn = TestFixtures.validSignatureHex
            val prepared = manager.prepareWithdrawal(
                chainId = 1,
                addresses = addresses,
                amount = BigDecimal("100.0"),
                decimals = 6,
                adminSignature = TestFixtures.adminSignature(),
                nonce = BigInteger.valueOf(7)
            )

            assertThrows(RainError.InternalError::class.java) {
                runBlocking { manager.estimateWithdrawalFee(chainId = RainChain.SOLANA_DEVNET, prepared = prepared) }
            }

            assertThat(stub.estimateTransactionFeeCalls).isEmpty()
        }

    @Test
    fun `estimateWithdrawalFee on a prepared withdrawal translates a simulation failure to WithdrawalRevertedByNetwork`(): Unit =
        runBlocking {
            val simulationFailure = RainError.TransactionSimulationFailed(RuntimeException("execution reverted"))
            val stub = object : StubWalletProvider() {
                override suspend fun estimateTransactionFee(
                    chainId: Int,
                    from: String,
                    to: String,
                    data: String,
                    value: String
                ): BigDecimal = throw simulationFailure
            }
            val (manager, _) = TestManagers.stubProviderManager(stub, transactionBuilder = builder)
            stub.signTypedDataToReturn = TestFixtures.validSignatureHex
            val prepared = manager.prepareWithdrawal(
                chainId = 1,
                addresses = addresses,
                amount = BigDecimal("100.0"),
                decimals = 6,
                adminSignature = TestFixtures.adminSignature(),
                nonce = BigInteger.valueOf(7)
            )

            val error = assertThrows(RainError.WithdrawalRevertedByNetwork::class.java) {
                runBlocking { manager.estimateWithdrawalFee(chainId = 1, prepared = prepared) }
            }

            assertThat(error.cause).isSameInstanceAs(simulationFailure)
        }

    @Test
    fun `estimateWithdrawalFee on a Solana preparation rejects an EVM chain id before asking the provider`(): Unit =
        runBlocking {
            // The chain id alone would take the EVM path; the preparation's shape refuses it first.
            val (manager, stub) = TestManagers.stubProviderManager(transactionBuilder = builder)
            val prepared = RainPreparedWithdrawal.Solana(UnsignedSolanaTransfer(ByteArray(1), recentBlockhash = "hash"))

            assertThrows(RainError.InternalError::class.java) {
                runBlocking { manager.estimateWithdrawalFee(chainId = 1, prepared = prepared) }
            }

            assertThat(stub.estimateTransactionFeeCalls).isEmpty()
        }

    @Test
    fun `estimateWithdrawalFee on a prepared withdrawal passes a cancellation through unwrapped`(): Unit =
        runBlocking {
            val stub = object : StubWalletProvider() {
                override suspend fun estimateTransactionFee(
                    chainId: Int,
                    from: String,
                    to: String,
                    data: String,
                    value: String
                ): BigDecimal = throw CancellationException("caller cancelled")
            }
            val (manager, _) = TestManagers.stubProviderManager(stub, transactionBuilder = builder)
            stub.signTypedDataToReturn = TestFixtures.validSignatureHex
            val prepared = manager.prepareWithdrawal(
                chainId = 1,
                addresses = addresses,
                amount = BigDecimal("100.0"),
                decimals = 6,
                adminSignature = TestFixtures.adminSignature(),
                nonce = BigInteger.valueOf(7)
            )

            val thrown = assertThrows(CancellationException::class.java) {
                runBlocking { manager.estimateWithdrawalFee(chainId = 1, prepared = prepared) }
            }

            // Not wrapped into a RainError: the host sees its own cancellation.
            assertThat(thrown).isNotInstanceOf(RainError::class.java)
            assertThat(thrown).hasMessageThat().isEqualTo("caller cancelled")
        }

    @Test
    fun `estimateWithdrawalFee on a prepared withdrawal still floors a failure nothing mapped at InternalError`(): Unit =
        runBlocking {
            val raw = IllegalStateException("truncated response")
            val stub = object : StubWalletProvider() {
                override suspend fun estimateTransactionFee(
                    chainId: Int,
                    from: String,
                    to: String,
                    data: String,
                    value: String
                ): BigDecimal = throw raw
            }
            val (manager, _) = TestManagers.stubProviderManager(stub, transactionBuilder = builder)
            stub.signTypedDataToReturn = TestFixtures.validSignatureHex
            val prepared = manager.prepareWithdrawal(
                chainId = 1,
                addresses = addresses,
                amount = BigDecimal("100.0"),
                decimals = 6,
                adminSignature = TestFixtures.adminSignature(),
                nonce = BigInteger.valueOf(7)
            )

            val error = assertThrows(RainError.InternalError::class.java) {
                runBlocking { manager.estimateWithdrawalFee(chainId = 1, prepared = prepared) }
            }

            assertThat(error.cause).isSameInstanceAs(raw)
        }
}
