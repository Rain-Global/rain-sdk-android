package com.rain.sdk.internal.solana

import com.google.common.truth.Truth.assertThat
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

    private suspend fun require(includeAccountRent: Boolean, feesSponsored: Boolean, rentSponsored: Boolean) =
        SolanaLamportPreflight.require(
            SolanaRpcClient(),
            rpc.urlFor(RainChain.SOLANA_DEVNET),
            SolanaWithdrawFixtures.OWNER,
            includeAccountRent = includeAccountRent,
            feesSponsored = feesSponsored,
            rentSponsored = rentSponsored
        )
}
