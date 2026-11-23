package com.rain.sdk.internal.transaction

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.rain.sdk.interfaces.CollateralAdminCheck
import com.rain.sdk.internal.helpers.StubTransactionBuilder
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.provider.WalletProvider
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class TransactionCoordinatorTest {

    private lateinit var walletProvider: WalletProvider
    private lateinit var validator: TransactionValidator
    private lateinit var signer: TransactionSigner
    private lateinit var executor: TransactionExecutor
    private lateinit var coordinator: TransactionCoordinator

    @Before
    fun setUp() {
        walletProvider = mockk()
        validator = mockk()
        signer = mockk()
        executor = mockk()

        coordinator = TransactionCoordinator(
            walletProvider = { walletProvider },
            transactionBuilder = mockk(),
            validator = validator,
            signer = signer,
            executor = executor
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `estimateGas delegates to walletProvider estimateTransactionFee`() = runBlocking {
        val chainId = 1
        val from = "0x123"
        val to = "0x456"
        val data = "0x789"
        val expectedFee = java.math.BigDecimal("0.00042")

        coEvery {
            walletProvider.estimateTransactionFee(chainId, from, to, data, "0x0")
        } returns expectedFee

        val fee = coordinator.estimateGas(chainId, from, to, data)

        assertThat(fee).isEqualTo(expectedFee)
    }

    @Test
    fun `a withdrawal is refused only when the collateral answers that the wallet is not an admin`() = runBlocking {
        val request = WithdrawCollateralRequest(
            chainId = 1,
            addresses = RainWithdrawAddresses(
                proxyAddress = TestFixtures.PROXY_ADDRESS,
                controllerAddress = TestFixtures.CONTROLLER_ADDRESS,
                tokenAddress = TestFixtures.TOKEN_ADDRESS,
                recipientAddress = TestFixtures.RECIPIENT_ADDRESS
            ),
            amount = BigDecimal.ONE,
            decimals = 6,
            adminSignature = TestFixtures.adminSignature(),
            walletAddress = TestFixtures.WALLET_ADDRESS,
            nonce = BigInteger.ONE
        )
        every { validator.validateWithdrawRequest(request) } just Runs
        coEvery { signer.signTypedData(any(), any(), any()) } returns TestFixtures.validSignatureHex

        val rows = mapOf(
            CollateralAdminCheck.Admin to null,
            CollateralAdminCheck.NotAdmin to "RAIN_407",
            CollateralAdminCheck.Failed(RainError.NetworkError()) to null,
            CollateralAdminCheck.NotCollateral to null,
        )
        for ((check, code) in rows) {
            clearMocks(signer, answers = false)
            val gated = TransactionCoordinator({ walletProvider }, StubTransactionBuilder(check), validator, signer, executor)

            val error = runCatching { gated.prepareWithdrawCollateral(request) }.exceptionOrNull()

            assertWithMessage("$check").that((error as? RainError)?.code).isEqualTo(code)
            coVerify(exactly = if (code == null) 1 else 0) { signer.signTypedData(any(), any(), any()) }
        }
    }

    // The Solana withdrawal path in RainSdkManager shares this wrapper, so its contract is pinned.

    @Test
    fun `withWithdrawalErrors maps a simulation failure to WithdrawalRevertedByNetwork`() {
        assertThrows(RainError.WithdrawalRevertedByNetwork::class.java) {
            runBlocking {
                coordinator.withWithdrawalErrors<Unit>("Withdraw collateral") {
                    throw RainError.TransactionSimulationFailed(RuntimeException("revert"))
                }
            }
        }
    }

    @Test
    fun `withWithdrawalErrors wraps raw exceptions as InternalError`() {
        val ex = assertThrows(RainError.InternalError::class.java) {
            runBlocking {
                coordinator.withWithdrawalErrors<Unit>("Withdraw collateral") {
                    throw IllegalArgumentException("truncated account data")
                }
            }
        }
        assertThat(ex.message).contains("Withdraw collateral")
    }

    @Test
    fun `withWithdrawalErrors passes RainError through unchanged`() {
        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                coordinator.withWithdrawalErrors<Unit>("Withdraw collateral") {
                    throw RainError.InvalidAmount("0", "amount must be positive")
                }
            }
        }
    }
}
