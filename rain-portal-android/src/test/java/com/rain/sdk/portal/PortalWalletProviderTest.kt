package com.rain.sdk.portal

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.Balance
import com.rain.sdk.models.NativeCurrency
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.pow

class PortalWalletProviderTest {

    private lateinit var portalManager: PortalManager
    private lateinit var tokenStore: TokenMetadataStore
    private lateinit var portalWalletProvider: PortalWalletProvider

    @Before
    fun setUp() {
        portalManager = mockk()
        // sendNativeToken scales by the registry's native decimals; balance/history reads go
        // through PortalManager (mocked here), which receives the store as a pass-through.
        tokenStore = mockk(relaxed = true)
        every { tokenStore.nativeCurrency(any()) } returns NativeCurrency("AVAX", "Avalanche", 18)
        portalWalletProvider = PortalWalletProvider(portalManager, tokenStore)
    }

    @Test
    fun `sendNativeToken should call portalManager sendTransaction with correct params`() = runBlocking {
        // Given
        val chainId = 43114
        val fromAddress = "0x1234567890123456789012345678901234567890"
        val toAddress = "0x0987654321098765432109876543210987654321"
        val amountInEth = BigDecimal("1.5")
        // 1.5 AVAX = 1.5e18 wei
        val expectedValueWeiHex = "0x14d1120d7b160000"
        val expectedTxHash = "0xHash"

        coEvery { portalManager.getAddress() } returns fromAddress
        coEvery {
            portalManager.sendTransaction(
                chainId = chainId,
                from = fromAddress,
                to = toAddress,
                data = "0x",
                value = expectedValueWeiHex
            )
        } returns expectedTxHash

        // When
        val result = portalWalletProvider.sendNativeToken(chainId, toAddress, amountInEth)

        // Then
        assertEquals(expectedTxHash, result)
        coVerify {
            portalManager.sendTransaction(
                chainId = chainId,
                from = fromAddress,
                to = toAddress,
                data = "0x",
                value = expectedValueWeiHex
            )
        }
    }

    @Test
    fun `sendNativeToken scales the value by the registry's native decimals, not a fixed 18`() = runBlocking {
        val chainId = 43114
        every { tokenStore.nativeCurrency(chainId) } returns NativeCurrency("AVAX", "Avalanche", 9)
        coEvery { portalManager.getAddress() } returns "0x1234567890123456789012345678901234567890"
        coEvery { portalManager.sendTransaction(any(), any(), any(), any(), any()) } returns "0xHash"

        portalWalletProvider.sendNativeToken(chainId, "0x0987654321098765432109876543210987654321", BigDecimal.ONE)

        // 1 unit of a 9-decimal native currency = 1e9 base units = 0x3b9aca00
        coVerify { portalManager.sendTransaction(chainId, any(), any(), "0x", "0x3b9aca00") }
    }

    @Test
    fun `sendToken should call portalManager sendTransaction with ABI encoded data`() = runBlocking {
        // Given
        val chainId = 43114
        val fromAddress = "0x1234567890123456789012345678901234567890"
        val toAddress = "0x0987654321098765432109876543210987654321"
        val contractAddress = "0x1111111111111111111111111111111111111111"
        val amount = BigDecimal("100.0")
        val decimals = 6
        val expectedTxHash = "0xERC20Hash"

        // Calculate expected data
        val tokenAmount = amount
            .multiply(java.math.BigDecimal.TEN.pow(decimals))
            .toBigInteger()
        val function = Function(
            "transfer",
            listOf(Address(toAddress), Uint256(tokenAmount)),
            emptyList<TypeReference<*>>()
        )
        val expectedData = FunctionEncoder.encode(function)

        coEvery { portalManager.getAddress() } returns fromAddress
        coEvery {
            portalManager.sendTransaction(
                chainId = chainId,
                from = fromAddress,
                to = contractAddress,
                data = expectedData,
                value = "0x0"
            )
        } returns expectedTxHash

        // When
        val result = portalWalletProvider.sendToken(chainId, contractAddress, toAddress, amount, decimals)

        // Then
        assertEquals(expectedTxHash, result)
        coVerify {
            portalManager.sendTransaction(
                chainId = chainId,
                from = fromAddress,
                to = contractAddress,
                data = expectedData,
                value = "0x0"
            )
        }
    }

    // ---- Solana guard -----------------------------------------------------------

    @Test
    fun `send paths reject Solana chain ids instead of building an EVM transaction`() {
        for (chainId in intArrayOf(900, 901, 902)) {
            assertThrows(RainError.InvalidConfig::class.java) {
                runBlocking {
                    portalWalletProvider.sendNativeToken(chainId, "recipient", BigDecimal.ONE)
                }
            }
            assertThrows(RainError.InvalidConfig::class.java) {
                runBlocking {
                    portalWalletProvider.sendToken(chainId, "mint", "recipient", BigDecimal.ONE, 6)
                }
            }
            assertThrows(RainError.InvalidConfig::class.java) {
                runBlocking {
                    portalWalletProvider.sendTransaction(chainId, "from", "to", "0x", "0x0")
                }
            }
            assertThrows(RainError.InvalidConfig::class.java) {
                runBlocking {
                    portalWalletProvider.estimateTransactionFee(chainId, "from", "to", "0x", "0x0")
                }
            }
            assertThrows(RainError.InvalidConfig::class.java) {
                runBlocking {
                    portalWalletProvider.signTypedData(chainId, "wallet", "{}")
                }
            }
        }
        // The manager must never have been reached.
        coVerify(exactly = 0) { portalManager.sendTransaction(any(), any(), any(), any(), any()) }
    }

    // ---- Balance delegations ----------------------------------------------------

    @Test
    fun `getAddress delegates to PortalManager`() = runBlocking {
        coEvery { portalManager.getAddress() } returns TestFixtures.WALLET_ADDRESS

        val address = portalWalletProvider.getWalletAddress()

        assertThat(address).isEqualTo(TestFixtures.WALLET_ADDRESS)
        coVerify { portalManager.getAddress() }
    }

    @Test
    fun `getBalance delegates to PortalManager and returns its result`() = runBlocking {
        val expected = Balance(
            token = Token.Native,
            chainId = 43114,
            rawAmount = BigInteger("1500000000000000000"),
            decimals = 18,
            symbol = "AVAX",
            name = "Avalanche"
        )
        coEvery { portalManager.getBalance(eq(43114), eq(Token.Native), any()) } returns expected

        val balance = portalWalletProvider.getBalance(chainId = 43114, token = Token.Native)

        assertThat(balance).isEqualTo(expected)
        coVerify { portalManager.getBalance(43114, Token.Native, any()) }
    }

    @Test
    fun `getBalance propagates PortalManager errors`() {
        coEvery { portalManager.getBalance(any(), any(), any()) } throws RainError.ProviderError(
            RuntimeException("portal indexer failed")
        )

        assertThrows(RainError.ProviderError::class.java) {
            runBlocking { portalWalletProvider.getBalance(chainId = 43114, token = Token.Native) }
        }
    }

    @Test
    fun `getBalances delegates to PortalManager and returns its result`() = runBlocking {
        val expected = listOf(
            Balance(Token.Native, 1, BigInteger.ZERO, 18, "ETH", "Ether"),
            Balance(Token.Contract(TestFixtures.USDC_ADDRESS), 1, BigInteger("25000000"), 6, "USDC", "USDC")
        )
        coEvery { portalManager.getBalances(eq(1), any()) } returns expected

        val result = portalWalletProvider.getBalances(chainId = 1)

        assertThat(result).isEqualTo(expected)
        coVerify { portalManager.getBalances(1, any()) }
    }

    @Test
    fun `getBalances propagates PortalManager errors`() {
        coEvery { portalManager.getBalances(any(), any()) } throws RainError.ProviderError(
            RuntimeException("getAssets failed")
        )

        assertThrows(RainError.ProviderError::class.java) {
            runBlocking { portalWalletProvider.getBalances(chainId = 1) }
        }
    }

    // ---- Transaction history delegation -----------------------------------------

    @Test
    fun `getTransactions forwards pagination + order to PortalManager`() = runBlocking {
        val expected = listOf(
            RainTransaction(
                hash = "0xabc",
                blockNumber = "100",
                from = "0xfrom",
                to = "0xto",
                value = BigDecimal("1.0"),
                chainId = 43114
            )
        )
        coEvery {
            portalManager.getTransactions(43114, any(), 5, 2, RainTransactionOrder.DESC)
        } returns expected

        val result = portalWalletProvider.getTransactions(
            chainId = 43114,
            limit = 5,
            offset = 2,
            order = RainTransactionOrder.DESC
        )

        assertThat(result).isSameInstanceAs(expected)
        coVerify { portalManager.getTransactions(43114, any(), 5, 2, RainTransactionOrder.DESC) }
    }

    // ---- Signing + low-level sendTransaction + fee estimation -------------------

    @Test
    fun `signTypedData delegates to PortalManager with chain + wallet + JSON`() = runBlocking {
        val expectedSig = "0x" + "1".repeat(130)
        coEvery {
            portalManager.signTypedData(1, TestFixtures.WALLET_ADDRESS, """{"foo":"bar"}""")
        } returns expectedSig

        val signature = portalWalletProvider.signTypedData(
            chainId = 1,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            typedDataJson = """{"foo":"bar"}"""
        )

        assertThat(signature).isEqualTo(expectedSig)
    }

    @Test
    fun `sendTransaction (low-level) delegates to PortalManager`() = runBlocking {
        val expectedHash = "0x" + "f".repeat(64)
        coEvery {
            portalManager.sendTransaction(
                chainId = 1,
                from = TestFixtures.WALLET_ADDRESS,
                to = TestFixtures.CONTRACT_ADDRESS,
                data = "0xdeadbeef",
                value = "0x0"
            )
        } returns expectedHash

        val txHash = portalWalletProvider.sendTransaction(
            chainId = 1,
            from = TestFixtures.WALLET_ADDRESS,
            to = TestFixtures.CONTRACT_ADDRESS,
            data = "0xdeadbeef",
            value = "0x0"
        )

        assertThat(txHash).isEqualTo(expectedHash)
    }

    @Test
    fun `ERC-20 approve calldata reaches Portal unchanged through the generic send path`() =
        runBlocking {
            // approve(0x5a6E...8f29, uint256 max) — the Auth Pull approval. Portal broadcasts it
            // through the same path a withdrawal uses; nothing here may rewrite the calldata.
            val approveCalldata = "0x095ea7b3" +
                "0000000000000000000000005a6e6b0d5ea051cfff9b3dcc2aa8dac226458f29" +
                "f".repeat(64)
            val expectedHash = "0x" + "c".repeat(64)
            coEvery {
                portalManager.sendTransaction(
                    chainId = 1,
                    from = TestFixtures.WALLET_ADDRESS,
                    to = TestFixtures.CONTRACT_ADDRESS,
                    data = approveCalldata,
                    value = "0x0"
                )
            } returns expectedHash

            val txHash = portalWalletProvider.sendTransaction(
                chainId = 1,
                from = TestFixtures.WALLET_ADDRESS,
                to = TestFixtures.CONTRACT_ADDRESS,
                data = approveCalldata,
                value = "0x0"
            )

            assertThat(txHash).isEqualTo(expectedHash)
        }

    @Test
    fun `sendTransaction propagates TransactionSimulationFailed from PortalManager`() {
        coEvery {
            portalManager.sendTransaction(any(), any(), any(), any(), any())
        } throws RainError.TransactionSimulationFailed(RuntimeException("revert"))

        assertThrows(RainError.TransactionSimulationFailed::class.java) {
            runBlocking {
                portalWalletProvider.sendTransaction(
                    chainId = 1,
                    from = TestFixtures.WALLET_ADDRESS,
                    to = TestFixtures.CONTRACT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }
    }

    @Test
    fun `estimateTransactionFee delegates to PortalManager`() = runBlocking {
        val expectedFee = BigDecimal("0.00042")
        coEvery {
            portalManager.estimateTransactionFee(1, TestFixtures.WALLET_ADDRESS, TestFixtures.CONTRACT_ADDRESS, "0x", "0x0")
        } returns expectedFee

        val fee = portalWalletProvider.estimateTransactionFee(
            chainId = 1,
            from = TestFixtures.WALLET_ADDRESS,
            to = TestFixtures.CONTRACT_ADDRESS,
            data = "0x",
            value = "0x0"
        )

        assertThat(fee).isEqualTo(expectedFee)
    }
}
