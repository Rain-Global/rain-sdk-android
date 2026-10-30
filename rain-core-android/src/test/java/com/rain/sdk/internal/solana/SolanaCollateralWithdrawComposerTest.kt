package com.rain.sdk.internal.solana

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import com.rain.sdk.internal.constants.SolanaPrograms
import com.rain.sdk.internal.helpers.MockRpcServer
import com.rain.sdk.internal.helpers.SolanaWithdrawFixtures
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64

/**
 * End-to-end composition test against real devnet fixtures: the account data, addresses,
 * signature, and salt are the ones from the first successful withdrawal on devnet
 * (tx `3ByKrsXW…`, 2026-07-24), so the asserted bytes are known-good on chain.
 */
class SolanaCollateralWithdrawComposerTest {

    private val devnet = SolanaWithdrawFixtures.DEVNET
    private val owner = SolanaWithdrawFixtures.OWNER
    private val collateral = SolanaWithdrawFixtures.COLLATERAL
    private val programId = SolanaWithdrawFixtures.PROGRAM_ID
    private val mint = SolanaWithdrawFixtures.MINT
    private val executor = SolanaWithdrawFixtures.EXECUTOR
    private val coordinatorData = SolanaWithdrawFixtures.COORDINATOR_DATA
    private val adminSignature = SolanaWithdrawFixtures.adminSignature

    private lateinit var rpc: MockRpcServer

    @Before
    fun setUp() {
        rpc = MockRpcServer().also { it.start() }
    }

    @After
    fun tearDown() = rpc.shutdown()

    private fun composer() = SolanaCollateralWithdrawComposer(
        solanaRpcClient = SolanaRpcClient(),
        rpcUrlResolver = { rpc.urlFor(it) }
    )

    private fun stubHappyPath() = SolanaWithdrawFixtures.stubHappyPath(rpc)

    @Test
    fun `composes the two-instruction withdrawal the program accepted on devnet`(): Unit =
        runBlocking {
            stubHappyPath()

            val unsigned = composer().composeWithdraw(
                chainId = devnet,
                ownerAddress = owner,
                collateralAddress = collateral,
                mintAddress = mint,
                recipientAddress = owner,
                amountBaseUnits = BigInteger.ONE,
                adminSignature = adminSignature
            )

            val hex = unsigned.transactionHex
            // Golden bytes: pins the full serialization so composition drift is caught here
            // rather than on chain. Deterministic because the stubbed blockhash is fixed.
            assertThat(hex).isEqualTo(SolanaWithdrawFixtures.GOLDEN_WITHDRAW_TX_HEX)
            // The ed25519 instruction embeds the executor key, Rain's signature, and the exact
            // 32-byte message the executor signed — verified against the live signature.
            assertThat(hex).contains(SolanaTransactionBuilder.hexEncode(Base58.decode(executor)))
            assertThat(hex)
                .contains("77229dff3a1aca1bf472323ba0cdf247669970593ab8f64b31d7e1e74d28e8f4")
            // The withdraw instruction: discriminator, then amount=1 and expiry 1784912091
            // (0x6A6398DB) as LE u64/i64.
            assertThat(hex).contains("0d1940536fb846f1" + "0100000000000000" + "db98636a00000000")
            // Both programs are in the account table; the owner is the fee payer.
            assertThat(hex).contains(SolanaTransactionBuilder.hexEncode(Base58.decode(programId)))
            assertThat(hex).contains(
                SolanaTransactionBuilder.hexEncode(SolanaPrograms.ED25519_VERIFY)
            )
            assertThat(unsigned.createsRecipientAccount).isFalse()
        }

    @Test
    fun `skips the self-paid dry run when the fee is sponsored`(): Unit = runBlocking {
        // The dry run charges the fee to the owner, so it would false-fail a zero-SOL wallet whose
        // sponsor pays the real send. Composition itself is unchanged: same golden bytes.
        stubHappyPath()

        val unsigned = composer().composeWithdraw(
            chainId = devnet,
            ownerAddress = owner,
            collateralAddress = collateral,
            mintAddress = mint,
            recipientAddress = owner,
            amountBaseUnits = BigInteger.ONE,
            adminSignature = adminSignature,
            sponsoredFees = true
        )

        assertThat(unsigned.transactionHex).isEqualTo(SolanaWithdrawFixtures.GOLDEN_WITHDRAW_TX_HEX)
        assertThat(rpc.recordedMethods).doesNotContain("simulateTransaction")
    }

    @Test
    fun `demands rent up front when the recipient token account must be created`(): Unit = runBlocking {
        // A withdrawal to a recipient without a token account creates it with the owner as payer.
        // Fee sponsorship does not cover that rent, and without the dry run nothing else would
        // catch a wallet that cannot pay it before Turnkey does, so it is checked up front.
        stubHappyPath()
        rpc.stubObjectFor("getAccountInfo", SolanaWithdrawFixtures.destinationAta(), contextual(JSONObject.NULL))
        rpc.stubObject("getBalance", contextual(0L))

        val error = assertThrows(RainError.InsufficientFunds::class.java) {
            runBlocking {
                composer().composeWithdraw(
                    chainId = devnet,
                    ownerAddress = owner,
                    collateralAddress = collateral,
                    mintAddress = mint,
                    recipientAddress = owner,
                    amountBaseUnits = BigInteger.ONE,
                    adminSignature = adminSignature,
                    sponsoredFees = true
                )
            }
        }
        assertThat(rpc.recordedMethods).doesNotContain("simulateTransaction")
        // The fee is sponsored, so the shortfall is the token-account rent alone, in SOL; the wallet holds nothing.
        assertThat(error.required?.compareTo(BigDecimal("0.00148844"))).isEqualTo(0)
        assertThat(error.available?.compareTo(BigDecimal.ZERO)).isEqualTo(0)
        assertThat(error.message).contains("required 0.00148844")
    }

    @Test
    fun `a dry run the runtime refuses for a rent shortfall is an InsufficientFunds, not a revert`(): Unit = runBlocking {
        stubHappyPath()
        rpc.stubObject(
            "simulateTransaction",
            contextual(
                JSONObject()
                    .put("err", JSONObject().put("InsufficientFundsForRent", JSONObject().put("account_index", 0)))
                    .put("logs", JSONArray())
            )
        )

        assertThrows(RainError.InsufficientFunds::class.java) {
            runBlocking {
                composer().composeWithdraw(
                    chainId = devnet,
                    ownerAddress = owner,
                    collateralAddress = collateral,
                    mintAddress = mint,
                    recipientAddress = owner,
                    amountBaseUnits = BigInteger.ONE,
                    adminSignature = adminSignature
                )
            }
        }
        Unit
    }

    @Test
    fun `a dry run the System Program refuses for the created account's rent carries the lamports it named`(): Unit =
        runBlocking {
            stubHappyPath()
            rpc.stubObject(
                "simulateTransaction",
                contextual(
                    JSONObject()
                        .put("err", JSONObject().put("InstructionError", JSONArray().put(0).put(JSONObject().put("Custom", 1))))
                        .put(
                            "logs",
                            JSONArray()
                                .put("Program 11111111111111111111111111111111 invoke [2]")
                                .put("Transfer: insufficient lamports 0, need 1488440")
                                .put("Program 11111111111111111111111111111111 failed: custom program error: 0x1")
                        )
                )
            )

            val error = assertThrows(RainError.InsufficientFunds::class.java) {
                runBlocking {
                    composer().composeWithdraw(
                        chainId = devnet,
                        ownerAddress = owner,
                        collateralAddress = collateral,
                        mintAddress = mint,
                        recipientAddress = owner,
                        amountBaseUnits = BigInteger.ONE,
                        adminSignature = adminSignature
                    )
                }
            }
            assertThat(error.required?.compareTo(BigDecimal("0.00148844"))).isEqualTo(0)
            assertThat(error.available?.compareTo(BigDecimal.ZERO)).isEqualTo(0)
            assertThat(error.currency).isEqualTo("SOL")
        }

    @Test
    fun `rejects a wallet that does not own the collateral`(): Unit = runBlocking {
        stubHappyPath()

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                composer().composeWithdraw(
                    chainId = devnet,
                    ownerAddress = executor, // any wallet that isn't the collateral's owner
                    collateralAddress = collateral,
                    mintAddress = mint,
                    recipientAddress = owner,
                    amountBaseUnits = BigInteger.ONE,
                    adminSignature = adminSignature
                )
            }
        }
        assertThat(error.message).contains("not the owner")
    }

    @Test
    fun `rejects a collateral account that is not single-signer`(): Unit = runBlocking {
        stubHappyPath()
        // The coordinator account has a different Anchor discriminator — a realistic wrong type.
        rpc.stubObjectFor("getAccountInfo", collateral, contextual(rawAccount(programId, coordinatorData)))

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                composer().composeWithdraw(
                    chainId = devnet,
                    ownerAddress = owner,
                    collateralAddress = collateral,
                    mintAddress = mint,
                    recipientAddress = owner,
                    amountBaseUnits = BigInteger.ONE,
                    adminSignature = adminSignature
                )
            }
        }
        assertThat(error.message).contains("single-signer")
    }

    @Test
    fun `surfaces a simulation failure instead of handing the transaction out`(): Unit =
        runBlocking {
            stubHappyPath()
            rpc.stubObject(
                "simulateTransaction",
                contextual(
                    JSONObject()
                        .put("err", JSONObject().put("InstructionError", JSONArray()))
                        .put("logs", JSONArray().put("Program log: signature expired"))
                )
            )

            assertThrows(RainError.TransactionSimulationFailed::class.java) {
                runBlocking {
                    composer().composeWithdraw(
                        chainId = devnet,
                        ownerAddress = owner,
                        collateralAddress = collateral,
                        mintAddress = mint,
                        recipientAddress = owner,
                        amountBaseUnits = BigInteger.ONE,
                        adminSignature = adminSignature
                    )
                }
            }
        }

    // ---------- fixtures ----------

    /** Rain's `expiresAt` arrives in more than one shape; every shape must encode the expiry the executor signed. */
    @Test
    fun `accepts every expiresAt shape and encodes the same expiry`(): Unit = runBlocking {
        stubHappyPath()
        for (shape in listOf("1784912091", " 1784912091 ", "2026-07-24T18:54:51+02:00", "2026-07-24T16:54:51.250Z")) {
            val unsigned = composer().composeWithdraw(
                chainId = devnet,
                ownerAddress = owner,
                collateralAddress = collateral,
                mintAddress = mint,
                recipientAddress = owner,
                amountBaseUnits = BigInteger.ONE,
                adminSignature = adminSignature.copy(expiresAt = shape)
            )
            assertWithMessage(shape).that(unsigned.transactionHex).isEqualTo(SolanaWithdrawFixtures.GOLDEN_WITHDRAW_TX_HEX)
        }
    }

    @Test
    fun `rejects a blank expiresAt as InvalidConfig`(): Unit = runBlocking {
        stubHappyPath()
        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                composer().composeWithdraw(
                    chainId = devnet,
                    ownerAddress = owner,
                    collateralAddress = collateral,
                    mintAddress = mint,
                    recipientAddress = owner,
                    amountBaseUnits = BigInteger.ONE,
                    adminSignature = adminSignature.copy(expiresAt = " ")
                )
            }
        }
        assertThat(error).hasMessageThat().contains("Invalid expiresAt format")
    }

    @Test
    fun `rejects a 31-byte salt as InvalidConfig through the shared decoder`(): Unit = runBlocking {
        stubHappyPath()
        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                composer().composeWithdraw(
                    chainId = devnet,
                    ownerAddress = owner,
                    collateralAddress = collateral,
                    mintAddress = mint,
                    recipientAddress = owner,
                    amountBaseUnits = BigInteger.ONE,
                    adminSignature = adminSignature.copy(salt = Base64.getEncoder().encodeToString(ByteArray(31)))
                )
            }
        }
        assertThat(error.errorCode).isEqualTo(RainErrorCode.INVALID_CONFIG)
        assertThat(error).hasMessageThat().contains("RainAdminSignature.salt must be 32 bytes, got 31")
    }

    private fun contextual(value: Any): JSONObject = SolanaWithdrawFixtures.contextual(value)

    private fun rawAccount(ownerProgram: String, base64Data: String): JSONObject =
        SolanaWithdrawFixtures.rawAccount(ownerProgram, base64Data)
}
