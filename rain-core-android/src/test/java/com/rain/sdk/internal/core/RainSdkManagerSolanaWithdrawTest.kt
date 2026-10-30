package com.rain.sdk.internal.core

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.helpers.MockRpcServer
import com.rain.sdk.internal.helpers.SolanaWithdrawFixtures
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.models.RainPreparedWithdrawal
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.provider.Capability
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * A Solana withdrawal composes in the manager rather than in `TransactionCoordinator`, so it has
 * to run the same parameter validation the EVM path gets. These pin that: unusable parameters are
 * rejected before any RPC or signing, on both chain families. The first two drive a real
 * composition (devnet fixtures over a mock node) to pin how the provider's
 * [Capability.GAS_SPONSORSHIP] reaches the composer's self-paid dry run.
 */
class RainSdkManagerSolanaWithdrawTest {

    private lateinit var rpc: MockRpcServer

    @Before
    fun setUp() {
        rpc = MockRpcServer().also { it.start() }
    }

    @After
    fun tearDown() = rpc.shutdown()

    private val addresses = RainWithdrawAddresses(
        proxyAddress = TestFixtures.PROXY_ADDRESS,
        controllerAddress = TestFixtures.CONTROLLER_ADDRESS,
        tokenAddress = TestFixtures.TOKEN_ADDRESS,
        recipientAddress = TestFixtures.RECIPIENT_ADDRESS
    )

    private fun manager() = TestManagers.stubProviderManager(
        rpcEndpoints = mapOf(RainChain.SOLANA_DEVNET to "https://api.devnet.solana.com")
    )

    // ---------- sponsorship reaches the composer ----------

    /** The devnet fixture withdrawal: collateral, mint, and the owner as recipient. */
    private val fixtureAddresses = RainWithdrawAddresses(
        proxyAddress = SolanaWithdrawFixtures.COLLATERAL,
        controllerAddress = SolanaWithdrawFixtures.COLLATERAL, // not used on Solana
        tokenAddress = SolanaWithdrawFixtures.MINT,
        recipientAddress = SolanaWithdrawFixtures.OWNER
    )

    /** A manager over the mock node whose stub provider owns the fixture collateral. */
    private fun fixtureManager(sponsored: Boolean, rentSponsored: Boolean = false): Pair<RainSdkManager, StubWalletProvider> {
        val stub = StubWalletProvider().apply {
            addressToReturn = SolanaWithdrawFixtures.OWNER
            if (sponsored) capabilitiesToReturn = setOf(Capability.GAS_SPONSORSHIP)
            if (rentSponsored) sponsorsRentToReturn = true
        }
        return TestManagers.stubProviderManager(
            stub = stub,
            rpcEndpoints = mapOf(RainChain.SOLANA_DEVNET to rpc.urlFor(RainChain.SOLANA_DEVNET))
        )
    }

    @Test
    fun `withdrawCollateral skips the Solana dry run when the provider advertises gas sponsorship`(): Unit =
        runBlocking {
            // The dry run charges the fee to the owner, so a zero-SOL sponsored user would fail
            // before the sponsor ever saw the send. Composition is unchanged: golden bytes.
            SolanaWithdrawFixtures.stubHappyPath(rpc)
            val (manager, stub) = fixtureManager(sponsored = true)

            manager.withdrawCollateral(
                chainId = RainChain.SOLANA_DEVNET,
                addresses = fixtureAddresses,
                amount = BigDecimal("0.000001"), // 1 base unit at 6 decimals, as in the golden tx
                decimals = 6,
                adminSignature = SolanaWithdrawFixtures.adminSignature
            )

            assertThat(rpc.recordedMethods).doesNotContain("simulateTransaction")
            assertThat(stub.sendSolanaTransactionUnsigned.single().transactionHex)
                .isEqualTo(SolanaWithdrawFixtures.GOLDEN_WITHDRAW_TX_HEX)
        }

    @Test
    fun `withdrawCollateral keeps the Solana dry run for a self-paid provider`(): Unit = runBlocking {
        // No capability advertised (Privy, or Turnkey with sponsorGas off): the owner pays the
        // fee, so the self-paid preflight must still run before anything is signed.
        SolanaWithdrawFixtures.stubHappyPath(rpc)
        val (manager, stub) = fixtureManager(sponsored = false)

        manager.withdrawCollateral(
            chainId = RainChain.SOLANA_DEVNET,
            addresses = fixtureAddresses,
            amount = BigDecimal("0.000001"),
            decimals = 6,
            adminSignature = SolanaWithdrawFixtures.adminSignature
        )

        assertThat(rpc.recordedMethods).contains("simulateTransaction")
        assertThat(stub.sendSolanaTransactionCalls).containsExactly(RainChain.SOLANA_DEVNET)
    }

    @Test
    fun `prepareWithdrawal keeps the Solana fee check and dry run when the provider sponsors fees`(): Unit = runBlocking {
        // A prepared transaction is the host's own self-paid submission, so the sponsor never pays
        // its fee: the owner's balance and the dry run are checked as for a self-paid provider.
        SolanaWithdrawFixtures.stubHappyPath(rpc)
        val (manager, stub) = fixtureManager(sponsored = true)

        val prepared = manager.prepareWithdrawal(
            chainId = RainChain.SOLANA_DEVNET,
            addresses = fixtureAddresses,
            amount = BigDecimal("0.000001"),
            decimals = 6,
            adminSignature = SolanaWithdrawFixtures.adminSignature
        )

        assertThat(rpc.recordedMethods).containsAtLeast("getBalance", "simulateTransaction")
        assertThat((prepared as RainPreparedWithdrawal.Solana).transfer.transactionHex)
            .isEqualTo(SolanaWithdrawFixtures.GOLDEN_WITHDRAW_TX_HEX)
        assertThat(stub.sendSolanaTransactionCalls).isEmpty()
    }

    /** The fixture node with the recipient's token account missing and the owner holding no SOL. */
    private fun stubRecipientWithoutAccountAndOwnerWithoutSol() {
        SolanaWithdrawFixtures.stubHappyPath(rpc)
        rpc.stubObjectFor(
            "getAccountInfo",
            SolanaWithdrawFixtures.destinationAta(),
            SolanaWithdrawFixtures.contextual(JSONObject.NULL)
        )
        rpc.stubObject("getBalance", SolanaWithdrawFixtures.contextual(0L))
    }

    @Test
    fun `withdrawCollateral leaves the rent to the sponsor when the provider says it sponsors rent`(): Unit =
        runBlocking {
            // The port hook reaches the composer: nothing is required of a zero-SOL owner, so the
            // balance is not read, and the sponsored send still skips the dry run.
            stubRecipientWithoutAccountAndOwnerWithoutSol()
            val (manager, stub) = fixtureManager(sponsored = true, rentSponsored = true)

            manager.withdrawCollateral(
                chainId = RainChain.SOLANA_DEVNET,
                addresses = fixtureAddresses,
                amount = BigDecimal("0.000001"),
                decimals = 6,
                adminSignature = SolanaWithdrawFixtures.adminSignature
            )

            assertThat(rpc.recordedMethods).containsNoneOf("getBalance", "getMinimumBalanceForRentExemption", "simulateTransaction")
            assertThat(stub.sendSolanaTransactionUnsigned.single().createsRecipientAccount).isTrue()
        }

    @Test
    fun `prepareWithdrawal still demands the rent when the provider sponsors it`(): Unit = runBlocking {
        // The prepared transaction is the host's own submission, which no sponsor pre-funds: the
        // owner must hold the fee and the rent, as for a self-paid provider.
        stubRecipientWithoutAccountAndOwnerWithoutSol()
        val (manager, stub) = fixtureManager(sponsored = true, rentSponsored = true)

        val error = assertThrows(RainError.InsufficientFunds::class.java) {
            runBlocking {
                manager.prepareWithdrawal(
                    chainId = RainChain.SOLANA_DEVNET,
                    addresses = fixtureAddresses,
                    amount = BigDecimal("0.000001"),
                    decimals = 6,
                    adminSignature = SolanaWithdrawFixtures.adminSignature
                )
            }
        }

        // The fee plus the token-account rent the cluster answered, in SOL.
        assertThat(error.required?.compareTo(BigDecimal("0.00149344"))).isEqualTo(0)
        assertThat(stub.sendSolanaTransactionCalls).isEmpty()
    }

    @Test
    fun `prepareWithdrawal on Solana ignores the provider's broadcast gate`(): Unit = runBlocking {
        SolanaWithdrawFixtures.stubHappyPath(rpc)
        val (manager, stub) = fixtureManager(sponsored = false)
        stub.requireSendSupportError = RainError.ChainNotSupported(RainChain.SOLANA_DEVNET, "read-only here")

        val prepared = manager.prepareWithdrawal(
            chainId = RainChain.SOLANA_DEVNET,
            addresses = fixtureAddresses,
            amount = BigDecimal("0.000001"),
            decimals = 6,
            adminSignature = SolanaWithdrawFixtures.adminSignature
        )

        assertThat(prepared).isInstanceOf(RainPreparedWithdrawal.Solana::class.java)
        assertThat(stub.sendSolanaTransactionCalls).isEmpty()
    }

    @Test
    fun `withdrawCollateral rejects a non-positive amount on Solana`() {
        val (manager, stub) = manager()

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                manager.withdrawCollateral(
                    chainId = RainChain.SOLANA_DEVNET,
                    addresses = addresses,
                    amount = BigDecimal.ZERO,
                    decimals = 6,
                    adminSignature = TestFixtures.adminSignature()
                )
            }
        }

        // Rejected before composition, so nothing was handed to the provider to sign.
        assert(stub.sendSolanaTransactionCalls.isEmpty())
    }

    @Test
    fun `withdrawCollateral rejects a negative amount on Solana`() {
        val (manager, _) = manager()

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                manager.withdrawCollateral(
                    chainId = RainChain.SOLANA_DEVNET,
                    addresses = addresses,
                    amount = BigDecimal("-1.0"),
                    decimals = 6,
                    adminSignature = TestFixtures.adminSignature()
                )
            }
        }
    }

    @Test
    fun `withdrawCollateral rejects negative decimals on Solana`() {
        val (manager, _) = manager()

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                manager.withdrawCollateral(
                    chainId = RainChain.SOLANA_DEVNET,
                    addresses = addresses,
                    amount = BigDecimal("1.0"),
                    decimals = -1,
                    adminSignature = TestFixtures.adminSignature()
                )
            }
        }
    }

    @Test
    fun `prepareWithdrawal rejects a non-positive amount on Solana`() {
        val (manager, _) = manager()

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                manager.prepareWithdrawal(
                    chainId = RainChain.SOLANA_DEVNET,
                    addresses = addresses,
                    amount = BigDecimal.ZERO,
                    decimals = 6,
                    adminSignature = TestFixtures.adminSignature()
                )
            }
        }
    }
}
