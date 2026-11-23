package com.rain.sdk.internal.utils

import com.rain.sdk.error.RainError
import java.math.BigDecimal
import java.math.BigInteger

internal object RainAmountUtils {
    /**
     * The token decimals any money path accepts. Scaling raises 10 to `decimals`; uint256 max is
     * about 1.16e77, so one whole unit of a finer token is unrepresentable.
     */
    val DECIMALS_RANGE: IntRange = 0..77

    /**
     * Converts a decimal amount to BigInteger (Wei/Base Units) with precision safety.
     * Throws an error if the amount has more decimal places than the token allows.
     */
    fun toBaseUnits(amount: BigDecimal, decimals: Int): BigInteger {
        // A negative amount must never reach ABI encoding: uint256 two's complement would turn
        // it into an astronomically large transfer.
        if (amount.signum() < 0) {
            throw RainError.InvalidAmount(
                amount = amount.toPlainString(),
                reason = "amount must not be negative"
            )
        }
        // Scaling raises 10 to this power, and `decimals` can come from an on-chain `decimals()`
        // read — a contract the SDK does not control. An absurd value would either blow up with a
        // raw ArithmeticException or allocate a monstrous BigDecimal; reject it as a typed error.
        // 77 is the ceiling that means anything: uint256 max is ~1.16e77, so one whole unit of a
        // finer token is unrepresentable.
        if (decimals !in DECIMALS_RANGE) {
            throw RainError.InvalidAmount(
                amount = amount.toPlainString(),
                reason = "token decimals must be within $DECIMALS_RANGE, got $decimals"
            )
        }
        val baseUnits = try {
            amount.movePointRight(decimals).toBigIntegerExact()
        } catch (_: ArithmeticException) {
            throw RainError.InvalidAmount(
                amount = amount.toPlainString(),
                reason = "amount has fractional base units for a $decimals-decimal token"
            )
        }
        if (baseUnits > MAX_UINT256) {
            throw RainError.InvalidAmount(
                amount = amount.toPlainString(),
                reason = "amount exceeds the maximum ERC-20 uint256 value"
            )
        }
        return baseUnits
    }

    private val MAX_UINT256: BigInteger =
        BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE)
}
