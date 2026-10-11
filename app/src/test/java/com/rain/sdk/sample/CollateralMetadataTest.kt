package com.rain.sdk.sample

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.models.TokenInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/** The metadata fan-out behind `RainSession.fetchCollateralContract`: fill what resolves, guess nothing. */
class CollateralMetadataTest {

    private val usdc = CollateralToken(address = USDC, balance = "12.5", exchangeRate = 1.0, advanceRate = 0.9)
    private val other = CollateralToken(address = OTHER, balance = "1", exchangeRate = 1.0, advanceRate = 0.9)
    private val contract = CollateralContract(
        id = null,
        chainId = 84532,
        proxyAddress = PROXY,
        controllerAddress = CONTROLLER,
        depositAddress = null,
        adminAddresses = listOf(ADMIN),
        contractVersion = null,
        tokens = listOf(usdc, other),
    )

    @Test
    fun `resolved metadata lands on its token and the order is kept`(): Unit = runBlocking {
        val rows = tokensWithMetadata(contract, lookup = { _, address ->
            if (address == USDC) TokenInfo(84532, USDC, "USDC", 6, "USD Coin") else null
        })

        assertThat(rows.map { it.address }).containsExactly(USDC, OTHER).inOrder()
        assertThat(rows[0].symbol).isEqualTo("USDC")
        assertThat(rows[0].decimals).isEqualTo(6)
        assertThat(rows[0].balance).isEqualTo("12.5")
        assertThat(rows[1].decimals).isNull()
        assertThat(rows[1].name).isNull()
    }

    @Test
    fun `a lookup that throws RainError leaves the token unnamed and reports it`(): Unit = runBlocking {
        val reported = mutableListOf<Pair<String, String>>()

        val rows = tokensWithMetadata(
            contract,
            lookup = { chainId, _ -> throw RainError.InvalidConfig("No RPC endpoint configured for chainId=$chainId") },
            onUnavailable = { token, error -> reported += token.address to error.code },
        )

        assertThat(rows.map { it.decimals }).containsExactly(null, null)
        assertThat(rows.map { it.symbol }).containsExactly(null, null)
        assertThat(reported).containsExactly(USDC to "RAIN_102", OTHER to "RAIN_102")
    }

    @Test
    fun `a cancellation is not swallowed`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                tokensWithMetadata(contract, lookup = { _, _ -> throw CancellationException("screen gone") })
            }
        }
    }

    @Test
    fun `only tokens with resolved decimals become registrations`() {
        val resolved = usdc.copy(symbol = "USDC", name = "USD Coin", decimals = 6)

        val infos = registrableTokenInfos(84532, listOf(resolved, other))

        assertThat(infos).containsExactly(TokenInfo(84532, USDC, "USDC", 6, "USD Coin"))
    }

    private companion object {
        const val USDC = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
        const val OTHER = "0x4444444444444444444444444444444444444444"
        const val PROXY = "0x1111111111111111111111111111111111111111"
        const val CONTROLLER = "0x2222222222222222222222222222222222222222"
        const val ADMIN = "0x3333333333333333333333333333333333333333"
    }
}
