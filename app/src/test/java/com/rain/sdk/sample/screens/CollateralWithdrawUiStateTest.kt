package com.rain.sdk.sample.screens

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.sample.CollateralContract
import com.rain.sdk.sample.CollateralToken
import org.junit.Test
import java.math.BigDecimal

/**
 * The withdraw screen's fail-closed rule: a token whose decimals the SDK could not establish never
 * reaches a money action, from the rows the screen builds through the checks every money action
 * runs, the signature cache key tells chains and amounts apart, and the fee note says where an
 * estimate came from and who pays it.
 */
class CollateralWithdrawUiStateTest {

    private val usdc = WithdrawTokenOption(
        name = "USD Coin",
        symbol = "USDC",
        address = USDC,
        decimals = 6,
        balance = BigDecimal("12.5"),
    )

    private fun state(token: WithdrawTokenOption, amount: String, admin: String = "") = CollateralWithdrawUiState(
        chainId = 84532,
        availableTokens = listOf(token),
        selectedTokenIndex = 0,
        amount = amount,
        adminAddress = admin,
    )

    private fun contract(vararg tokens: CollateralToken) = CollateralContract(
        id = "c-1",
        chainId = 84532,
        proxyAddress = PROXY,
        controllerAddress = CONTROLLER,
        depositAddress = null,
        adminAddresses = listOf(ADMIN),
        contractVersion = 2,
        tokens = tokens.toList(),
    )

    private fun refusal(state: CollateralWithdrawUiState): String =
        (withdrawInput(state, amountOverride = null) as WithdrawInput.Refused).errorText

    @Test
    fun `known decimals enable a positive amount within the balance`() {
        val state = state(usdc, "1")

        assertThat(state.selectedTokenDecimalsKnown).isTrue()
        assertThat(state.decimalsUnavailableText).isNull()
        assertThat(state.isAmountValid).isTrue()
    }

    @Test
    fun `unknown decimals disable every money action and explain why`() {
        val state = state(usdc.copy(decimals = null), "1")

        assertThat(state.selectedTokenDecimalsKnown).isFalse()
        assertThat(state.isAmountValid).isFalse()
        assertThat(state.decimalsUnavailableText).contains("USDC")
    }

    @Test
    fun `the explanation names the address when the symbol is blank`() {
        val state = state(usdc.copy(decimals = null, symbol = ""), "")

        assertThat(state.decimalsUnavailableText).contains(USDC)
    }

    @Test
    fun `a valid amount is still positive and within the balance`() {
        assertThat(state(usdc, "0").isAmountValid).isFalse()
        assertThat(state(usdc, "abc").isAmountValid).isFalse()
        assertThat(state(usdc, "12.6").isAmountValid).isFalse()
        assertThat(state(usdc, "12.6").isAmountOverBalance).isTrue()
        assertThat(state(usdc, "12.5").isAmountValid).isTrue()
    }

    @Test
    fun `signature keys differ across chains and amounts`() {
        val key = SignatureKey(chainId = 84532, tokenAddress = USDC.lowercase(), amountBaseUnits = "12500000", recipientAddress = RECIPIENT)

        assertThat(key).isEqualTo(key.copy())
        assertThat(key.copy(chainId = 421614)).isNotEqualTo(key)
        assertThat(key.copy(amountBaseUnits = "12500001")).isNotEqualTo(key)
        assertThat(key.copy(recipientAddress = RECIPIENT.uppercase())).isNotEqualTo(key)
    }

    @Test
    fun `the fee note names the source and the payer, or is absent`() {
        assertThat(feeNote(sponsored = false, fromPrepared = false)).isNull()
        assertThat(feeNote(sponsored = true, fromPrepared = false))
            .isEqualTo("On a chain the provider sponsors, a sponsor pays this instead of the wallet.")
        assertThat(feeNote(sponsored = false, fromPrepared = true)).isEqualTo("From the prepared withdrawal, no new signature.")
        assertThat(feeNote(sponsored = true, fromPrepared = true)).isEqualTo(
            "From the prepared withdrawal, no new signature. On a chain the provider sponsors, a sponsor pays this instead of the wallet."
        )
    }

    @Test
    fun `the screen opens on the first token with known decimals, or on none`() {
        val unresolved = usdc.copy(address = RECIPIENT, symbol = "T", decimals = null)

        assertThat(defaultWithdrawSelection(listOf(unresolved, usdc))).isEqualTo(1)
        assertThat(defaultWithdrawSelection(listOf(usdc, unresolved))).isEqualTo(0)
        assertThat(defaultWithdrawSelection(listOf(unresolved))).isEqualTo(-1)
        assertThat(defaultWithdrawSelection(emptyList())).isEqualTo(-1)
    }

    // ---- the rows the screen builds --------------------------------------------------------

    @Test
    fun `withdrawTokens keeps unresolved decimals null and reads a missing balance as zero`() {
        val resolved = CollateralToken(
            address = USDC,
            balance = "12.5",
            exchangeRate = 1.0,
            advanceRate = 0.9,
            name = "USD Coin",
            symbol = "USDC",
            decimals = 6,
        )
        val unresolved = CollateralToken(address = RECIPIENT, balance = "", exchangeRate = 1.0, advanceRate = 0.9)

        val rows = withdrawTokens(contract(resolved, unresolved))

        assertThat(rows.map { it.decimals }).containsExactly(6, null).inOrder()
        assertThat(rows[0].displayName).isEqualTo("USD Coin (USDC)")
        assertThat(rows[0].balance).isEqualTo(BigDecimal("12.5"))
        assertThat(rows[1].name).isEqualTo("Token")
        assertThat(rows[1].symbol).isEmpty()
        assertThat(rows[1].balance).isEqualTo(BigDecimal.ZERO)
    }

    // ---- the checks before every money action ----------------------------------------------

    @Test
    fun `withdrawInput refuses a token without decimals before anything else`() {
        val input = withdrawInput(state(usdc.copy(decimals = null), "1", admin = ADMIN), amountOverride = null)

        assertThat(input).isInstanceOf(WithdrawInput.Refused::class.java)
        assertThat((input as WithdrawInput.Refused).errorText).contains("could not be resolved")
    }

    @Test
    fun `withdrawInput checks the amount, the balance and the admin, in that order`() {
        assertThat(refusal(state(usdc, "", admin = ADMIN))).isEqualTo("Enter a valid amount")
        assertThat(refusal(state(usdc, "-1", admin = ADMIN))).isEqualTo("Enter a valid amount")
        assertThat(refusal(state(usdc, "0.0000001", admin = ADMIN))).isEqualTo("Amount is below the token's minimum unit")
        assertThat(refusal(state(usdc, "12.6", admin = ADMIN))).startsWith("Amount exceeds available balance")
        assertThat(refusal(state(usdc, "1", admin = ""))).isEqualTo("Contract has no admin address")
    }

    @Test
    fun `withdrawInput rounds the amount down to the token's precision and hands back the pieces`() {
        val input = withdrawInput(state(usdc, "1.2345678", admin = ADMIN), amountOverride = null) as WithdrawInput.Ready

        assertThat(input.token).isEqualTo(usdc)
        assertThat(input.decimals).isEqualTo(6)
        assertThat(input.amount).isEqualTo(BigDecimal("1.234567"))
    }

    @Test
    fun `withdrawInput prefers the override amount and is null without a selected token`() {
        val max = withdrawInput(state(usdc, "1", admin = ADMIN), amountOverride = BigDecimal("12.5")) as WithdrawInput.Ready
        assertThat(max.amount).isEqualTo(BigDecimal("12.500000"))

        val none = state(usdc, "1", admin = ADMIN).copy(selectedTokenIndex = -1)
        assertThat(withdrawInput(none, amountOverride = null)).isNull()
    }

    private companion object {
        const val USDC = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
        const val RECIPIENT = "0x4444444444444444444444444444444444444444"
        const val PROXY = "0x1111111111111111111111111111111111111111"
        const val CONTROLLER = "0x2222222222222222222222222222222222222222"
        const val ADMIN = "0x3333333333333333333333333333333333333333"
    }
}
