package com.rain.sdk.internal.solana

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.helpers.MockRpcServer
import com.rain.sdk.internal.helpers.SolanaWithdrawFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The one table of what a wallet must hold: the fee unless sponsored, plus the rent of an account
 * the transaction creates unless sponsored, the rent being whatever the cluster answers.
 */
class SolanaLamportPreflightTest {

    private lateinit var rpc: MockRpcServer

    @Before
    fun setUp() {
        rpc = MockRpcServer().also { it.start() }
        rpc.stubObject("getBalance", SolanaWithdrawFixtures.contextual(0L))
        rpc.stubObject("getMinimumBalanceForRentExemption", SolanaWithdrawFixtures.TOKEN_ACCOUNT_RENT_LAMPORTS)
    }

    @After
    fun tearDown() = rpc.shutdown()

    @Test
    fun `requires the fee and the cluster's rent each unless sponsored`(): Unit = runBlocking {
        val cases = listOf(
            // includeAccountRent, feesSponsored, rentSponsored: the SOL a wallet holding none is short of,
            // the rent being whatever the fake cluster answers
            Triple(false, false, false) to "0.000005",
            Triple(true, false, false) to "0.00149344",
            Triple(true, true, false) to "0.00148844",
            Triple(true, false, true) to "0.000005",
        )
        cases.forEach { (flags, requiredSol) ->
            val (includeAccountRent, feesSponsored, rentSponsored) = flags
            val error = assertThrows(RainError.InsufficientFunds::class.java) {
                runBlocking { require(includeAccountRent, feesSponsored, rentSponsored) }
            }
            assertThat(error.required?.compareTo(BigDecimal(requiredSol))).isEqualTo(0)
            assertThat(error.currency).isEqualTo("SOL")
        }
    }

    @Test
    fun `bills one fee per signature`(): Unit = runBlocking {
        val error = assertThrows(RainError.InsufficientFunds::class.java) {
            runBlocking { require(includeAccountRent = false, feesSponsored = false, rentSponsored = false, signatures = 2) }
        }
        assertThat(error.required?.compareTo(BigDecimal("0.00001"))).isEqualTo(0)
    }

    @Test
    fun `a transfer must leave the wallet empty or rent exempt`(): Unit = runBlocking {
        // the fake cluster answers one rent for every account size
        val rent = SolanaWithdrawFixtures.TOKEN_ACCOUNT_RENT_LAMPORTS
        val rows = listOf(
            Leftover("sends everything", held = 1_000_000L, sent = 1_000_000L),
            Leftover("leaves less than the rent", held = 1_000_001L, sent = 1_000_000L, needsSol = "0.00248844"),
            Leftover("leaves the rent", held = 1_000_000L + rent, sent = 1_000_000L),
            Leftover("pays a fee and moves nothing", held = 5_001L, sent = 0L, feesSponsored = false),
        )
        rows.forEach { row ->
            rpc.stubObject("getBalance", SolanaWithdrawFixtures.contextual(row.held))
            val needsSol = try {
                require(includeAccountRent = false, feesSponsored = row.feesSponsored, rentSponsored = false, transferLamports = row.sent)
                null
            } catch (e: RainError.InsufficientFunds) {
                e.required?.stripTrailingZeros()?.toPlainString()
            }
            assertWithMessage(row.name).that(needsSol).isEqualTo(row.needsSol)
        }
    }

    private class Leftover(
        val name: String,
        val held: Long,
        val sent: Long,
        val feesSponsored: Boolean = true,
        val needsSol: String? = null
    )

    private suspend fun require(
        includeAccountRent: Boolean,
        feesSponsored: Boolean,
        rentSponsored: Boolean,
        signatures: Int = 1,
        transferLamports: Long = 0L
    ) = SolanaLamportPreflight.require(
        SolanaRpcClient(),
        rpc.urlFor(RainChain.SOLANA_DEVNET),
        SolanaWithdrawFixtures.OWNER,
        includeAccountRent = includeAccountRent,
        feesSponsored = feesSponsored,
        rentSponsored = rentSponsored,
        signatures = signatures,
        transferLamports = BigInteger.valueOf(transferLamports)
    )
}
