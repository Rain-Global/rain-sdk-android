package com.rain.sdk.internal.core

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.RainAuthPullChains
import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.interfaces.CollateralAdminCheck
import com.rain.sdk.internal.helpers.MockChainReader
import com.rain.sdk.internal.helpers.StubTransactionBuilder
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.models.RainTokenAllowance
import com.rain.sdk.models.TokenInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Manager-level contract for the Auth Pull approval surface: what gets encoded, what gets
 * broadcast, what is read back, and which failures surface as which typed error.
 */
class RainSdkManagerApprovalTest {

    // A sandbox Auth Pull chain: the manager defaults to the sandbox chain set, and approvals off
    // that set are rejected before anything is encoded.
    private val chainId = RainChain.BASE_SEPOLIA
    private val usdc = TestFixtures.AUTH_PULL_USDC_ADDRESS
    private val spender = TestFixtures.PROXY_ADDRESS
    private val otherSpender = "0x1111111111111111111111111111111111111111"
    private val unknownToken = "0x000000000000000000000000000000000000dEaD"

    private fun usdcInfo() = TokenInfo(chainId, usdc, "USDC", 6, "USDC")

    private fun rpcFailure() = RainError.NetworkError("rpc down")

    // ---- approveTokenAllowance ----------------------------------------------------

    @Test
    fun `an omitted amount approves uint256 max`(): Unit = runBlocking {
        val admin = StubTransactionBuilder()
        val (manager, stub, _) = TestManagers.approvalManager(transactionBuilder = admin)
        stub.sendTransactionHashToReturn = "0xapprovalhash"

        val result = manager.approveTokenAllowance(chainId, usdc, spender)

        assertThat(result.transactionHash).isEqualTo("0xapprovalhash")
        val sent = stub.sendTransactionCalls.single()
        assertThat(sent.chainId).isEqualTo(chainId)
        assertThat(sent.to).isEqualTo(usdc)
        assertThat(sent.from).isEqualTo(TestFixtures.WALLET_ADDRESS)
        assertThat(sent.value).isEqualTo("0x0")
        assertThat(sent.data).isEqualTo(
            "0x095ea7b3" +
                "000000000000000000000000abcdefabcdefabcdefabcdefabcdefabcdefabcd" +
                "f".repeat(64)
        )
        assertThat(admin.adminCalls.single())
            .isEqualTo(StubTransactionBuilder.AdminCall(chainId, spender, TestFixtures.WALLET_ADDRESS))
    }

    @Test
    fun `an unlimited approval needs no decimals lookup`(): Unit = runBlocking {
        val (manager, _, reader) = TestManagers.approvalManager()

        manager.approveTokenAllowance(chainId, usdc, spender)

        assertThat(reader.decimalsCalls).isEmpty()
    }

    @Test
    fun `a decimal amount is scaled by the token's decimals`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager(seedTokens = listOf(usdcInfo()))

        manager.approveTokenAllowance(chainId, usdc, spender, BigDecimal("250.5"))

        // 250.5 USDC at 6 decimals = 250_500_000 = 0xEEE53A0.
        assertThat(stub.sendTransactionCalls.single().data).endsWith("0eee53a0")
    }

    /**
     * There is no `decimals` parameter to get this wrong with: the scale comes from trusted
     * metadata, so a token the registry knows is never re-read and never re-scaled.
     */
    @Test
    fun `the scale always comes from trusted metadata`(): Unit = runBlocking {
        val reader = MockChainReader(decimals = 18)
        val (manager, stub, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        manager.approveTokenAllowance(chainId, usdc, spender, BigDecimal.ONE)

        // 1 USDC at the registry's 6 decimals = 1_000_000 = 0xF4240, not 10^18.
        assertThat(stub.sendTransactionCalls.single().data).endsWith("00f4240")
        assertThat(reader.decimalsCalls).isEmpty()
    }

    @Test
    fun `a zero amount is a revoke, not an invalid amount`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager(seedTokens = listOf(usdcInfo()))

        val result = manager.approveTokenAllowance(chainId, usdc, spender, BigDecimal.ZERO)

        assertThat(result.transactionHash).isNotEmpty()
        assertThat(stub.sendTransactionCalls.single().data).endsWith("0".repeat(64))
    }

    // ---- validation ---------------------------------------------------------------

    @Test
    fun `a negative amount is rejected before any provider call`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager(seedTokens = listOf(usdcInfo()))

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking { manager.approveTokenAllowance(chainId, usdc, spender, BigDecimal("-1")) }
        }
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `an amount finer than the token's scale is rejected, not truncated`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager(seedTokens = listOf(usdcInfo()))

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                manager.approveTokenAllowance(chainId, usdc, spender, BigDecimal("1.2345678"))
            }
        }
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `a malformed spender is rejected before any provider call`(): Unit = runBlocking {
        val admin = StubTransactionBuilder()
        val (manager, stub, reader) = TestManagers.approvalManager(transactionBuilder = admin)
        val hash = "0x" + "a".repeat(64)

        val wrongChecksum = "0x036cbD53842c5426634e7929541eC2318f3dCF7e"
        for (malformed in listOf("not-an-address", wrongChecksum, "0x" + "0".repeat(40), "0X" + spender.drop(2))) {
            val calls = listOf<suspend () -> Any>(
                { manager.approveTokenAllowance(chainId, usdc, malformed) },
                { manager.approveTokenAllowance(chainId, usdc, malformed, BigDecimal.ZERO) },
                { manager.estimateApprovalFee(chainId, usdc, malformed) },
                { manager.getTokenAllowance(chainId, usdc, malformed) },
                { manager.confirmTokenAllowance(hash, chainId, usdc, malformed) },
            )
            for (call in calls) {
                assertThrows(malformed, RainError.InvalidConfig::class.java) { runBlocking { call() } }
            }
        }
        assertThat(admin.adminCalls).isEmpty()
        assertThat(stub.sendTransactionCalls).isEmpty()
        assertThat(stub.estimateTransactionFeeCalls).isEmpty()
        assertThat(reader.allowanceCalls).isEmpty()
    }

    @Test
    fun `a malformed token contract is rejected before any provider call`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager()

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.approveTokenAllowance(chainId, "0x1234", spender) }
        }
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `an approval is refused unless the spender answers that the wallet is its admin`(): Unit = runBlocking {
        data class Row(val name: String, val check: CollateralAdminCheck, val code: String)

        val rows = listOf(
            Row("the wallet is not an admin", CollateralAdminCheck.NotAdmin, "RAIN_407"),
            Row("the admin read failed", CollateralAdminCheck.Failed(RainError.NetworkError()), "RAIN_301"),
            Row("the spender has no isAdmin", CollateralAdminCheck.NotCollateral, "RAIN_102"),
        )
        for (row in rows) {
            for (amount in listOf(null, BigDecimal("250"))) {
                val (manager, stub, _) = TestManagers.approvalManager(
                    transactionBuilder = StubTransactionBuilder(row.check),
                    seedTokens = listOf(usdcInfo())
                )
                val case = "${row.name}, amount ${amount ?: "omitted"}"

                val approval = runCatching { manager.approveTokenAllowance(chainId, usdc, spender, amount) }.exceptionOrNull()
                val estimate = runCatching { manager.estimateApprovalFee(chainId, usdc, spender, amount) }.exceptionOrNull()

                assertWithMessage(case).that((approval as? RainError)?.code).isEqualTo(row.code)
                assertWithMessage(case).that((estimate as? RainError)?.code).isEqualTo(row.code)
                assertWithMessage(case).that(stub.sendTransactionCalls).isEmpty()
                assertWithMessage(case).that(stub.estimateTransactionFeeCalls).isEmpty()
            }
        }
    }

    @Test
    fun `a zero amount is sent to any spender without an admin read`(): Unit = runBlocking {
        val admin = StubTransactionBuilder(CollateralAdminCheck.NotCollateral)
        val (manager, stub, _) = TestManagers.approvalManager(
            transactionBuilder = admin,
            seedTokens = listOf(usdcInfo())
        )

        manager.approveTokenAllowance(chainId, usdc, otherSpender, BigDecimal.ZERO)
        manager.estimateApprovalFee(chainId, usdc, otherSpender, BigDecimal.ZERO)

        val revoke = "0x095ea7b3" + "0".repeat(24) + otherSpender.removePrefix("0x") + "0".repeat(64)
        assertThat(stub.sendTransactionCalls.single().data).isEqualTo(revoke)
        assertThat(stub.estimateTransactionFeeCalls.single().data).isEqualTo(revoke)
        assertThat(admin.adminCalls).isEmpty()
    }

    @Test
    fun `an allowance read and a confirmation accept any spender without an admin read`(): Unit = runBlocking {
        val admin = StubTransactionBuilder(CollateralAdminCheck.NotCollateral)
        val reader = MockChainReader(receiptStatus = true, allowance = BigInteger.ZERO)
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            transactionBuilder = admin,
            seedTokens = listOf(usdcInfo())
        )

        manager.getTokenAllowance(chainId, usdc, otherSpender)
        manager.confirmTokenAllowance("0x" + "a".repeat(64), chainId, usdc, otherSpender, BigDecimal.ZERO)

        assertThat(reader.allowanceCalls.map { it.spender }).containsExactly(otherSpender, otherSpender)
        assertThat(admin.adminCalls).isEmpty()
    }

    @Test
    fun `a valid but untrusted token is rejected before any provider call`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager()

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.approveTokenAllowance(chainId, unknownToken, spender) }
        }
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `a non-positive chain id is rejected`(): Unit = runBlocking {
        val (manager, _, _) = TestManagers.approvalManager()

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.approveTokenAllowance(0, usdc, spender) }
        }
    }

    @Test
    fun `a Solana chain id is rejected instead of taking the EVM path`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager()

        assertThrows(RainError.InternalError::class.java) {
            runBlocking { manager.approveTokenAllowance(RainChain.SOLANA_DEVNET, usdc, spender) }
        }
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    // ---- environment / chain pairing ----------------------------------------------

    @Test
    fun `a production chain is rejected on a sandbox-configured client`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager()

        // Base mainnet is an Auth Pull chain, but not this client's: approving here would mine a
        // real mainnet allowance no sandbox authorization draws on.
        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.approveTokenAllowance(RainChain.BASE_MAINNET, usdc, spender) }
        }
        // The guard must send hosts to the configuration, not to a removed environment setting.
        assertThat(error.message).contains("RainAuthPullConfig")
        assertThat(error.message).doesNotContain("rainApiEnvironment")
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `a sandbox chain is rejected on a production-configured client`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager(
            authPullChainIds = RainAuthPullChains.PRODUCTION
        )

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.approveTokenAllowance(RainChain.BASE_SEPOLIA, usdc, spender) }
        }
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `a non-Auth-Pull chain is rejected even though it is a valid EVM chain`(): Unit =
        runBlocking {
            val (manager, stub, _) = TestManagers.approvalManager()

            assertThrows(RainError.InvalidConfig::class.java) {
                runBlocking { manager.approveTokenAllowance(1, usdc, spender) }
            }
            assertThat(stub.sendTransactionCalls).isEmpty()
        }

    @Test
    fun `the allowance read applies the same environment gate`(): Unit = runBlocking {
        val (manager, _, reader) = TestManagers.approvalManager()

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.getTokenAllowance(RainChain.BASE_MAINNET, usdc, spender) }
        }
        assertThat(reader.allowanceCalls).isEmpty()
    }

    @Test
    fun `every production Auth Pull chain is accepted on a production client`(): Unit =
        runBlocking {
            for (chain in RainAuthPullChains.PRODUCTION) {
                val token = when (chain) {
                    RainChain.BASE_MAINNET -> "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
                    else -> "0xaf88d065e77c8cC2239327C5EDb3A432268e5831"
                }
                val (manager, stub, _) = TestManagers.approvalManager(
                    authPullChainIds = RainAuthPullChains.PRODUCTION
                )
                stub.sendTransactionHashToReturn = "0xok"

                val result = manager.approveTokenAllowance(chain, token, spender)

                assertThat(result.transactionHash).isEqualTo("0xok")
            }
        }

    // ---- the advertised chain set -------------------------------------------------

    /**
     * The set a host gates its UI on has to be the same set the guard enforces. Asserted as one
     * fact rather than two: every advertised chain approves, and no unadvertised one does.
     */
    @Test
    fun `the advertised chain set is exactly what the guard accepts`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager(
            authPullChainIds = setOf(RainChain.BASE_SEPOLIA)
        )
        stub.sendTransactionHashToReturn = "0xok"

        assertThat(manager.authPullChainIds).containsExactly(RainChain.BASE_SEPOLIA)

        manager.approveTokenAllowance(RainChain.BASE_SEPOLIA, usdc, spender)
        assertThat(stub.sendTransactionCalls).hasSize(1)

        // Arbitrum Sepolia is an Auth Pull chain for this environment, but not for this client.
        assertThat(RainAuthPullChains.SANDBOX).contains(RainChain.ARBITRUM_SEPOLIA)
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                manager.approveTokenAllowance(RainChain.ARBITRUM_SEPOLIA, usdc, spender)
            }
        }
        assertThat(stub.sendTransactionCalls).hasSize(1)
    }

    @Test
    fun `a client with Auth Pull disabled advertises no chains`(): Unit = runBlocking {
        val manager = TestManagers.authPullDisabledManager()

        assertThat(manager.authPullChainIds).isEmpty()
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.approveTokenAllowance(chainId, usdc, spender) }
        }
    }

    // ---- decimals must never be guessed -------------------------------------------

    @Test
    fun `an unresolvable decimals refuses to guess rather than over-approving`(): Unit =
        runBlocking {
            // Not in the registry, and `decimals()` reverts. Guessing 18 against a 6-decimal token
            // would approve 10^12 times the requested amount, and `approve` has no balance to fail
            // against.
            val reader = MockChainReader(metadataError = rpcFailure())
            val (manager, stub, _) = TestManagers.approvalManager(
                reader = reader,
                authPullTokenAddresses = mapOf(chainId to unknownToken)
            )

            assertThrows(RainError.TokenNotFound::class.java) {
                runBlocking {
                    manager.approveTokenAllowance(chainId, unknownToken, spender, BigDecimal("250"))
                }
            }
            assertThat(stub.sendTransactionCalls).isEmpty()
        }

    @Test
    fun `an unlimited approval still needs no decimals, even for an unknown token`(): Unit =
        runBlocking {
            val reader = MockChainReader(metadataError = rpcFailure())
            val (manager, stub, _) = TestManagers.approvalManager(
                reader = reader,
                authPullTokenAddresses = mapOf(chainId to unknownToken)
            )

            manager.approveTokenAllowance(chainId, unknownToken, spender)

            assertThat(stub.sendTransactionCalls.single().data).endsWith("f".repeat(64))
        }

    @Test
    fun `an allowance read refuses to report a scale it had to guess`(): Unit = runBlocking {
        val reader = MockChainReader(
            metadataError = rpcFailure(),
            allowance = BigInteger.valueOf(250_000_000)
        )
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            authPullTokenAddresses = mapOf(chainId to unknownToken)
        )

        assertThrows(RainError.TokenNotFound::class.java) {
            runBlocking { manager.getTokenAllowance(chainId, unknownToken, spender) }
        }
    }

    @Test
    fun `absurd decimals are rejected instead of blowing up the scaling math`(): Unit =
        runBlocking {
            val reader = MockChainReader(decimals = 40_000)
            val (manager, stub, _) = TestManagers.approvalManager(
                reader = reader,
                authPullTokenAddresses = mapOf(chainId to unknownToken)
            )

            assertThrows(RainError.InvalidConfig::class.java) {
                runBlocking {
                    manager.approveTokenAllowance(chainId, unknownToken, spender, BigDecimal.ONE)
                }
            }
            assertThat(stub.sendTransactionCalls).isEmpty()
        }

    @Test
    fun `a token reporting absurd decimals cannot break the allowance read either`(): Unit =
        runBlocking {
            val reader = MockChainReader(decimals = 40_000, allowance = BigInteger.ONE)
            val (manager, _, _) = TestManagers.approvalManager(
                reader = reader,
                authPullTokenAddresses = mapOf(chainId to unknownToken)
            )

            assertThrows(RainError.InvalidConfig::class.java) {
                runBlocking { manager.getTokenAllowance(chainId, unknownToken, spender) }
            }
        }

    // ---- provider failures ---------------------------------------------------------

    @Test
    fun `a user rejection in the wallet surfaces as userRejected`(): Unit = runBlocking {
        val stub = StubWalletProvider().apply { sendTransactionError = RainError.UserRejected() }
        val (manager, _, _) = TestManagers.approvalManager(stub = stub)

        assertThrows(RainError.UserRejected::class.java) {
            runBlocking { manager.approveTokenAllowance(chainId, usdc, spender) }
        }
    }

    @Test
    fun `a simulation revert keeps its own code rather than the withdrawal one`(): Unit =
        runBlocking {
            val stub = StubWalletProvider().apply {
                sendTransactionError = RainError.TransactionSimulationFailed(null)
            }
            val (manager, _, _) = TestManagers.approvalManager(stub = stub)

            val ex = assertThrows(RainError.TransactionSimulationFailed::class.java) {
                runBlocking { manager.approveTokenAllowance(chainId, usdc, spender) }
            }
            assertThat(ex.errorCode.code).isEqualTo("RAIN_403")
        }

    // ---- getTokenAllowance ----------------------------------------------------------

    @Test
    fun `the allowance is read for the wallet's own address by default`(): Unit = runBlocking {
        val reader = MockChainReader(allowance = BigInteger.valueOf(250_000_000))
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        val allowance = manager.getTokenAllowance(chainId, usdc, spender)

        // A standalone read has no transaction to pin to, so `latest` is the right block here —
        // unlike a confirmation, which reads at the block its transaction mined in.
        assertThat(reader.allowanceCalls.single()).isEqualTo(
            MockChainReader.AllowanceCall(
                chainId,
                usdc,
                TestFixtures.WALLET_ADDRESS,
                spender,
                atBlock = "latest"
            )
        )
        assertThat(allowance.rawAmount).isEqualTo(BigInteger.valueOf(250_000_000))
        assertThat(allowance.decimals).isEqualTo(6)
        assertThat(allowance.decimalAmount).isEqualTo(BigDecimal("250.000000"))
        assertThat(allowance.formatted).isEqualTo("250")
        assertThat(allowance.isUnlimited).isFalse()
        assertThat(allowance.isZero).isFalse()
    }

    @Test
    fun `an explicit owner is read instead of the wallet's own address`(): Unit = runBlocking {
        val other = "0x3cA8ac240F6ebeA8684b3E629A8e8C1f0E3bC0Ff"
        val (manager, _, reader) = TestManagers.approvalManager(seedTokens = listOf(usdcInfo()))

        manager.getTokenAllowance(chainId, usdc, spender, owner = other)

        assertThat(reader.allowanceCalls.single().owner).isEqualTo(other)
    }

    @Test
    fun `a malformed explicit owner is rejected before the chain read`(): Unit = runBlocking {
        val (manager, _, reader) = TestManagers.approvalManager(seedTokens = listOf(usdcInfo()))

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.getTokenAllowance(chainId, usdc, spender, owner = "0xnope") }
        }
        assertThat(reader.allowanceCalls).isEmpty()
    }

    @Test
    fun `an unlimited allowance is exact in rawAmount`(): Unit = runBlocking {
        val reader = MockChainReader(allowance = RainTokenAllowance.UNLIMITED_RAW_AMOUNT)
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        val allowance = manager.getTokenAllowance(chainId, usdc, spender)

        assertThat(allowance.isUnlimited).isTrue()
        assertThat(allowance.rawAmount).isEqualTo(RainTokenAllowance.UNLIMITED_RAW_AMOUNT)
        assertThat(allowance.covers(BigDecimal("1000000"))).isTrue()
    }

    @Test
    fun `a zero allowance is reported as revoked and covers nothing`(): Unit = runBlocking {
        val (manager, _, _) = TestManagers.approvalManager(seedTokens = listOf(usdcInfo()))

        val allowance = manager.getTokenAllowance(chainId, usdc, spender)

        assertThat(allowance.isZero).isTrue()
        assertThat(allowance.covers(BigDecimal("0.000001"))).isFalse()
        assertThat(allowance.covers(BigDecimal.ZERO)).isTrue()
    }

    @Test
    fun `covers compares against the exact base units the amount needs`() {
        val allowance = RainTokenAllowance(
            chainId = chainId,
            tokenAddress = usdc,
            owner = TestFixtures.WALLET_ADDRESS,
            spender = spender,
            rawAmount = BigInteger.valueOf(250_000_000),
            decimals = 6
        )

        assertThat(allowance.covers(BigDecimal("250"))).isTrue()
        assertThat(allowance.covers(BigDecimal("249.999999"))).isTrue()
        assertThat(allowance.covers(BigDecimal("250.000001"))).isFalse()
        // Finer than the token's scale can't be represented, so it isn't covered.
        assertThat(allowance.covers(BigDecimal("0.0000001"))).isFalse()
    }

    @Test
    fun `a failed allowance read surfaces as a typed SDK error`(): Unit = runBlocking {
        val reader = MockChainReader(allowanceError = rpcFailure())
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        assertThrows(RainError.NetworkError::class.java) {
            runBlocking { manager.getTokenAllowance(chainId, usdc, spender) }
        }
    }

    @Test
    fun `a Solana chain id is rejected on the allowance read too`(): Unit = runBlocking {
        val (manager, _, reader) = TestManagers.approvalManager()

        assertThrows(RainError.InternalError::class.java) {
            runBlocking { manager.getTokenAllowance(RainChain.SOLANA_DEVNET, usdc, spender) }
        }
        assertThat(reader.allowanceCalls).isEmpty()
    }

    // ---- estimateApprovalFee ---------------------------------------------------------

    @Test
    fun `fee estimation prices the same calldata the approval would send`(): Unit = runBlocking {
        val stub = StubWalletProvider().apply {
            estimateTransactionFeeToReturn = BigDecimal("0.00042")
        }
        val admin = StubTransactionBuilder()
        val (manager, _, _) = TestManagers.approvalManager(stub = stub, transactionBuilder = admin)

        val fee = manager.estimateApprovalFee(chainId, usdc, spender)

        assertThat(fee).isEqualTo(BigDecimal("0.00042"))
        assertThat(admin.adminCalls.single())
            .isEqualTo(StubTransactionBuilder.AdminCall(chainId, spender, TestFixtures.WALLET_ADDRESS))
        val priced = stub.estimateTransactionFeeCalls.single()
        assertThat(priced.to).isEqualTo(usdc)
        assertThat(priced.value).isEqualTo("0x0")
        assertThat(priced.data).endsWith("f".repeat(64))
        // Nothing was broadcast on the way there.
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `fee estimation applies the same validation as the approval`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager()

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.estimateApprovalFee(chainId, usdc, "0xnope") }
        }
        assertThat(stub.estimateTransactionFeeCalls).isEmpty()
    }

    // ---- confirmTokenAllowance -------------------------------------------------------

    @Test
    fun `confirmation requires a successful receipt and returns the resulting allowance`(): Unit =
        runBlocking {
            val reader = MockChainReader(
                receiptStatus = true,
                allowance = BigInteger.valueOf(250_000_000)
            )
            val (manager, _, _) = TestManagers.approvalManager(
                reader = reader,
                seedTokens = listOf(usdcInfo())
            )

            val allowance = manager.confirmTokenAllowance(
                transactionHash = "0x" + "a".repeat(64),
                chainId = chainId,
                contractAddress = usdc,
                spender = spender,
                amount = BigDecimal("250")
            )

            assertThat(allowance.rawAmount).isEqualTo(BigInteger.valueOf(250_000_000))
        }

    /**
     * The read is pinned to the block the approval mined in, so nothing later can lower it: a
     * value below the requested one means the approval itself fell short — the wallet already had
     * 250, the host raised it to 500, and the `approve` reverted inside a bundle whose transaction
     * succeeded. Reading back 250 and calling that confirmed would be a lie.
     */
    @Test
    fun `an allowance below the requested amount fails`(): Unit = runBlocking {
        val reader = MockChainReader(
            receiptStatus = true,
            allowance = BigInteger.valueOf(250_000_000)
        )
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        val error = assertThrows(RainError.InternalError::class.java) {
            runBlocking {
                manager.confirmTokenAllowance(
                    transactionHash = "0x" + "c".repeat(64),
                    chainId = chainId,
                    contractAddress = usdc,
                    spender = spender,
                    amount = BigDecimal("500")
                )
            }
        }
        assertThat(error.message).contains("below the requested")
    }

    /**
     * The mirror image: the wallet had 1000, the host lowered it to 500, and the `approve` reverted
     * inside a bundle whose transaction succeeded. The pinned read still says 1000 — more than
     * requested is just as wrong as less, since `approve` sets the value outright.
     */
    @Test
    fun `an approval that lowered the allowance but left the old value fails`(): Unit = runBlocking {
        val reader = MockChainReader(
            receiptStatus = true,
            allowance = BigInteger.valueOf(1_000_000_000)
        )
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        val error = assertThrows(RainError.InternalError::class.java) {
            runBlocking {
                manager.confirmTokenAllowance(
                    transactionHash = "0x" + "1".repeat(64),
                    chainId = chainId,
                    contractAddress = usdc,
                    spender = spender,
                    amount = BigDecimal("500")
                )
            }
        }
        assertThat(error.message).contains("above the requested")
    }

    /** The unlimited approval is held to the same standard: anything short of `uint256` max fails. */
    @Test
    fun `an unlimited approval that left less than uint256 max fails`(): Unit = runBlocking {
        val short = RainTokenAllowance.UNLIMITED_RAW_AMOUNT.subtract(BigInteger.ONE)
        val reader = MockChainReader(receiptStatus = true, allowance = short)
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        assertThrows(RainError.InternalError::class.java) {
            runBlocking {
                manager.confirmTokenAllowance(
                    transactionHash = "0x" + "d".repeat(64),
                    chainId = chainId,
                    contractAddress = usdc,
                    spender = spender
                )
            }
        }
    }

    @Test
    fun `a revoke that left a spendable allowance fails`(): Unit = runBlocking {
        val reader = MockChainReader(receiptStatus = true, allowance = BigInteger.valueOf(500))
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        assertThrows(RainError.InternalError::class.java) {
            runBlocking {
                manager.confirmTokenAllowance(
                    transactionHash = "0x" + "e".repeat(64),
                    chainId = chainId,
                    contractAddress = usdc,
                    spender = spender,
                    amount = BigDecimal.ZERO
                )
            }
        }
    }

    @Test
    fun `an approval that mined against a still-zero allowance fails`(): Unit = runBlocking {
        val reader = MockChainReader(receiptStatus = true, allowance = BigInteger.ZERO)
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        assertThrows(RainError.InternalError::class.java) {
            runBlocking {
                manager.confirmTokenAllowance(
                    transactionHash = "0x" + "f".repeat(64),
                    chainId = chainId,
                    contractAddress = usdc,
                    spender = spender,
                    amount = BigDecimal("250")
                )
            }
        }
    }

    @Test
    fun `confirmation polls until the receipt appears`(): Unit = runBlocking {
        val reader = MockChainReader(
            allowance = BigInteger.valueOf(250_000_000),
            receiptStatuses = mutableListOf(null, true)
        )
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        manager.confirmTokenAllowance(
            transactionHash = "0x" + "1".repeat(64),
            chainId = chainId,
            contractAddress = usdc,
            spender = spender,
            amount = BigDecimal("250")
        )

        assertThat(reader.receiptCalls).hasSize(2)
        assertThat(reader.allowanceCalls).hasSize(1)
    }

    @Test
    fun `a raw vendor failure resolving the owner surfaces as a typed SDK error`(): Unit =
        runBlocking {
            // The owner lookup runs before the confirm loop; a raw provider exception there must
            // still surface mapped, never escape a @Throws(RainError) API.
            val failing = object : StubWalletProvider() {
                override suspend fun getWalletAddress(): String {
                    throw RuntimeException("session store unavailable")
                }
            }
            val (manager, _, _) = TestManagers.approvalManager(
                stub = failing,
                reader = MockChainReader(receiptStatus = true),
                seedTokens = listOf(usdcInfo())
            )

            val ex = runCatching {
                manager.confirmTokenAllowance(
                    transactionHash = "0x" + "2".repeat(64),
                    chainId = chainId,
                    contractAddress = usdc,
                    spender = spender,
                    amount = BigDecimal("250")
                )
            }.exceptionOrNull()

            assertThat(ex).isInstanceOf(RainError.ProviderError::class.java)
        }

    // ---- confirmation survives node blips and reports the right thing on timeout -------

    /** The approval is already out: one failed receipt read is a retry, not a hard error. */
    @Test
    fun `a receipt read that fails mid-poll is retried`(): Unit = runBlocking {
        val reader = MockChainReader(
            receiptStatus = true,
            allowance = BigInteger.valueOf(250_000_000),
            receiptFailures = mutableListOf(RainError.NetworkError("connection reset"))
        )
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        val allowance = manager.confirmTokenAllowance(
            transactionHash = "0x" + "6".repeat(64),
            chainId = chainId,
            contractAddress = usdc,
            spender = spender,
            amount = BigDecimal("250")
        )

        assertThat(allowance.rawAmount).isEqualTo(BigInteger.valueOf(250_000_000))
        assertThat(reader.receiptCalls).hasSize(2)
    }

    /** A malformed hash is a verdict on the input, not a node blip: it must not burn the window. */
    @Test
    fun `an invalid hash is rejected at once rather than retried for the whole window`(): Unit =
        runBlocking {
            val reader = MockChainReader(
                receiptFailures = mutableListOf(RainError.InvalidConfig("Invalid transaction hash"))
            )
            val (manager, _, _) = TestManagers.approvalManager(
                reader = reader,
                seedTokens = listOf(usdcInfo())
            )

            assertThrows(RainError.InvalidConfig::class.java) {
                runBlocking {
                    manager.confirmTokenAllowance(
                        transactionHash = "0x" + "7".repeat(64),
                        chainId = chainId,
                        contractAddress = usdc,
                        spender = spender,
                        amount = BigDecimal("250")
                    )
                }
            }
            assertThat(reader.receiptCalls).hasSize(1)
        }

    /**
     * Not mined by the end of the window is pending, not failure: the host resumes from the hash
     * (re-read or confirm again) instead of re-approving.
     */
    @Test
    fun `an approval that never mines surfaces as TransactionPending carrying the hash`(): Unit =
        runBlocking {
            val hash = "0x" + "8".repeat(64)
            val reader = MockChainReader(receiptStatus = null)
            val (manager, _, _) = TestManagers.approvalManager(
                reader = reader,
                seedTokens = listOf(usdcInfo())
            )

            val error = assertThrows(RainError.TransactionPending::class.java) {
                runBlocking {
                    manager.confirmTokenAllowance(
                        transactionHash = hash,
                        chainId = chainId,
                        contractAddress = usdc,
                        spender = spender,
                        amount = BigDecimal("250")
                    )
                }
            }
            assertThat(error.statusId).isEqualTo(hash)
            assertThat(reader.receiptCalls).hasSize(RainSdkManager.APPROVAL_CONFIRMATION_ATTEMPTS)
        }

    /**
     * Every catch in the poll loop, and the wrapper around it, rethrows CancellationException
     * before classifying anything. A host that cancels a confirm mid-poll must see the
     * cancellation, never a TransactionPending or NetworkError dressed up from it.
     */
    @Test
    fun `cancelling a confirm mid-poll propagates the cancellation, not a timeout`(): Unit =
        runBlocking {
            val reader = MockChainReader(receiptStatus = null)
            val (manager, _, _) = TestManagers.approvalManager(
                reader = reader,
                seedTokens = listOf(usdcInfo()),
                approvalConfirmationIntervalMs = 50
            )

            var outcome: Throwable? = null
            val job = launch {
                outcome = runCatching {
                    manager.confirmTokenAllowance(
                        transactionHash = "0x" + "c".repeat(64),
                        chainId = chainId,
                        contractAddress = usdc,
                        spender = spender,
                        amount = BigDecimal("250")
                    )
                }.exceptionOrNull()
            }
            // Past the first read and parked in the interval delay.
            while (reader.receiptCalls.isEmpty()) delay(1)
            job.cancelAndJoin()

            assertThat(outcome).isInstanceOf(CancellationException::class.java)
            assertThat(reader.receiptCalls.size)
                .isLessThan(RainSdkManager.APPROVAL_CONFIRMATION_ATTEMPTS)
        }

    /** Mined, but every allowance read failed: the node's own error is the useful one, not a timeout. */
    @Test
    fun `a mined approval whose allowance never reads back surfaces the node's own failure`(): Unit =
        runBlocking {
            val reader = MockChainReader(
                receiptStatus = true,
                allowanceError = RainError.InternalError("RPC error [-32000]: header not found")
            )
            val (manager, _, _) = TestManagers.approvalManager(
                reader = reader,
                seedTokens = listOf(usdcInfo())
            )

            val error = assertThrows(RainError.InternalError::class.java) {
                runBlocking {
                    manager.confirmTokenAllowance(
                        transactionHash = "0x" + "9".repeat(64),
                        chainId = chainId,
                        contractAddress = usdc,
                        spender = spender,
                        amount = BigDecimal("250")
                    )
                }
            }
            assertThat(error.message).contains("header not found")
            assertThat(reader.allowanceCalls).hasSize(RainSdkManager.APPROVAL_CONFIRMATION_ATTEMPTS)
        }

    /**
     * The reviewer's scenario: the node is unreachable for the whole window. That is still
     * "not confirmed yet" — the approval may well have mined — so it must resume from the hash,
     * not fail as a network error.
     */
    @Test
    fun `receipt reads that fail for the whole window still surface as TransactionPending`(): Unit =
        runBlocking {
            val hash = "0x" + "a".repeat(64)
            val reader = MockChainReader(
                receiptStatus = true,
                receiptFailures = MutableList(RainSdkManager.APPROVAL_CONFIRMATION_ATTEMPTS) {
                    IOException("connection reset")
                }
            )
            val (manager, _, _) = TestManagers.approvalManager(
                reader = reader,
                seedTokens = listOf(usdcInfo())
            )

            val error = assertThrows(RainError.TransactionPending::class.java) {
                runBlocking {
                    manager.confirmTokenAllowance(
                        transactionHash = hash,
                        chainId = chainId,
                        contractAddress = usdc,
                        spender = spender,
                        amount = BigDecimal("250")
                    )
                }
            }
            assertThat(error.statusId).isEqualTo(hash)
            assertThat(reader.receiptCalls).hasSize(RainSdkManager.APPROVAL_CONFIRMATION_ATTEMPTS)
            assertThat(reader.allowanceCalls).isEmpty()
        }

    /** Transport failures arrive untyped (OkHttp/JSON), not as RainError — they retry all the same. */
    @Test
    fun `an untyped transport failure on the receipt read is retried`(): Unit = runBlocking {
        val reader = MockChainReader(
            receiptStatus = true,
            allowance = BigInteger.valueOf(250_000_000),
            receiptFailures = mutableListOf(IOException("unexpected end of stream"))
        )
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        val allowance = manager.confirmTokenAllowance(
            transactionHash = "0x" + "b".repeat(64),
            chainId = chainId,
            contractAddress = usdc,
            spender = spender,
            amount = BigDecimal("250")
        )

        assertThat(allowance.rawAmount).isEqualTo(BigInteger.valueOf(250_000_000))
        assertThat(reader.receiptCalls).hasSize(2)
    }

    /** Mined, but every read died on the wire: that is a network error, and the wire error is the cause. */
    @Test
    fun `a mined approval whose allowance reads all fail untyped surfaces NetworkError with the cause`(): Unit = runBlocking {
        val wire = IOException("socket closed")
        val reader = MockChainReader(receiptStatus = true, receiptBlockNumber = "0x2a", allowanceError = wire)
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        val error = assertThrows(RainError.NetworkError::class.java) {
            runBlocking {
                manager.confirmTokenAllowance(
                    transactionHash = "0x" + "c".repeat(64),
                    chainId = chainId,
                    contractAddress = usdc,
                    spender = spender,
                    amount = BigDecimal("250")
                )
            }
        }
        assertThat(error.cause).isSameInstanceAs(wire)
        assertThat(error.message).contains("0x2a")
        assertThat(reader.receiptCalls).hasSize(1)
        assertThat(reader.allowanceCalls).hasSize(RainSdkManager.APPROVAL_CONFIRMATION_ATTEMPTS)
    }

    // ---- confirmation reads the transaction's own block ------------------------------
    // `allowanceByBlock` is the state at the receipt's block, `allowance` what a lagging node
    // answers — so reading at `latest` fails these with the exact production error, both ways.

    @Test
    fun `an approval is confirmed against the block it mined in, not against a lagging head`(): Unit = runBlocking {
        val reader = MockChainReader(
            receiptStatus = true,
            receiptBlockNumber = "0x2a",
            allowance = BigInteger.ZERO, // what a node that is still behind would answer
            allowanceByBlock = mapOf("0x2a" to BigInteger.valueOf(10_000_000))
        )
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        val allowance = manager.confirmTokenAllowance(
            transactionHash = "0x" + "2".repeat(64),
            chainId = chainId,
            contractAddress = usdc,
            spender = spender,
            amount = BigDecimal("10")
        )

        assertThat(allowance.rawAmount).isEqualTo(BigInteger.valueOf(10_000_000))
        assertThat(reader.allowanceCalls.single().atBlock).isEqualTo("0x2a")
    }

    /** The dangerous direction: a stale zero looks like a successful revoke that never landed. */
    @Test
    fun `a revoke is confirmed against its own block rather than a stale zero`(): Unit =
        runBlocking {
            val reader = MockChainReader(
                receiptStatus = true,
                receiptBlockNumber = "0x2a",
                // A node one block behind still shows the pre-revoke allowance.
                allowance = BigInteger.valueOf(10_000_000),
                allowanceByBlock = mapOf("0x2a" to BigInteger.ZERO)
            )
            val (manager, _, _) = TestManagers.approvalManager(
                reader = reader,
                seedTokens = listOf(usdcInfo())
            )

            val allowance = manager.confirmTokenAllowance(
                transactionHash = "0x" + "3".repeat(64),
                chainId = chainId,
                contractAddress = usdc,
                spender = spender,
                amount = BigDecimal.ZERO
            )

            assertThat(allowance.isZero).isTrue()
            assertThat(reader.allowanceCalls.single().atBlock).isEqualTo("0x2a")
        }

    /** A node without the receipt's block errors; that is a retry, not a verdict. */
    @Test
    fun `a node that has not reached the receipt block yet is retried`(): Unit = runBlocking {
        val reader = MockChainReader(
            receiptStatus = true,
            receiptBlockNumber = "0x2a",
            allowanceByBlock = mapOf("0x2a" to BigInteger.valueOf(10_000_000)),
            allowanceFailures = mutableListOf(RainError.InternalError("RPC error [-32000]: header not found"))
        )
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        val allowance = manager.confirmTokenAllowance(
            transactionHash = "0x" + "4".repeat(64),
            chainId = chainId,
            contractAddress = usdc,
            spender = spender,
            amount = BigDecimal("10")
        )

        assertThat(allowance.rawAmount).isEqualTo(BigInteger.valueOf(10_000_000))
        assertThat(reader.allowanceCalls).hasSize(2)
        // The receipt is not re-fetched once it is in hand — only the read is retried.
        assertThat(reader.receiptCalls).hasSize(1)
    }

    /** A mismatch at the transaction's own block is final; retrying only delays the same answer. */
    @Test
    fun `a verdict from the pinned block is not retried`(): Unit = runBlocking {
        val reader = MockChainReader(
            receiptStatus = true,
            receiptBlockNumber = "0x2a",
            allowanceByBlock = mapOf("0x2a" to BigInteger.ZERO)
        )
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader,
            seedTokens = listOf(usdcInfo())
        )

        assertThrows(RainError.InternalError::class.java) {
            runBlocking {
                manager.confirmTokenAllowance(
                    transactionHash = "0x" + "5".repeat(64),
                    chainId = chainId,
                    contractAddress = usdc,
                    spender = spender,
                    amount = BigDecimal("10")
                )
            }
        }
        assertThat(reader.allowanceCalls).hasSize(1)
    }

    @Test
    fun `confirmation rejects a reverted receipt`(): Unit = runBlocking {
        val reader = MockChainReader(receiptStatus = false)
        val (manager, _, _) = TestManagers.approvalManager(reader = reader)

        assertThrows(RainError.TransactionSimulationFailed::class.java) {
            runBlocking {
                manager.confirmTokenAllowance(
                    transactionHash = "0x" + "b".repeat(64),
                    chainId = chainId,
                    contractAddress = usdc,
                    spender = spender
                )
            }
        }
        assertThat(reader.allowanceCalls).isEmpty()
    }
}
