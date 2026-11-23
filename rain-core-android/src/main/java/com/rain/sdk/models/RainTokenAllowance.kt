package com.rain.sdk.models

import com.rain.sdk.internal.utils.RainAmountUtils
import java.math.BigDecimal
import java.math.BigInteger

/**
 * An ERC-20 allowance: how much of [owner]'s token balance [spender] may still move.
 *
 * [rawAmount] is the exact on-chain value in the token's smallest unit and is never lossy — it is
 * the value to compare against. [decimalAmount] and [formatted] are derived for display; gate on
 * [isUnlimited] before rendering a number, since an unlimited approval scales to ~1.16e71.
 */
data class RainTokenAllowance(
    /** EIP-155 chain ID the allowance was read on. */
    val chainId: Int,

    /** The ERC-20 token contract the allowance is against. */
    val tokenAddress: String,

    /** The wallet whose balance may be moved. */
    val owner: String,

    /** The address allowed to move it — the user's collateral contract, for Auth Pull. */
    val spender: String,

    /** Exact allowance in the token's smallest unit. */
    val rawAmount: BigInteger,

    /** The token's decimal places, the scale of [rawAmount]. */
    val decimals: Int
) {
    /**
     * Whether this is the unlimited (`uint256` max) allowance the SDK sets when an approval
     * amount is omitted.
     *
     * Exact by design. Some tokens decrement even a max allowance on every `transferFrom`, so a
     * wallet approved as unlimited can later report `false` here while still holding a very large
     * allowance — compare [rawAmount] against the amount you need rather than treating `false` as
     * "must re-approve".
     */
    val isUnlimited: Boolean get() = rawAmount == UNLIMITED_RAW_AMOUNT

    /** Nothing is approved — the state after a revoke, and before any approval. */
    val isZero: Boolean get() = rawAmount.signum() == 0

    /** The allowance as a precise [BigDecimal] (`rawAmount / 10^decimals`), for display. */
    val decimalAmount: BigDecimal
        get() = rawAmount.toBigDecimal().movePointLeft(decimals)

    /** Human-readable allowance string with trailing zeros trimmed (e.g. `"1.5"`). */
    val formatted: String
        get() = decimalAmount.stripTrailingZeros().toPlainString()

    /**
     * Whether [amount] (human-readable, at this token's [decimals]) is still covered by the
     * allowance — the check to run before an Auth Pull authorization.
     *
     * An amount that cannot be represented at all — negative, or finer than the token's scale —
     * is `false` too, so `false` means "do not rely on this allowance", not "approve more".
     */
    fun covers(amount: BigDecimal): Boolean =
        runCatching { RainAmountUtils.toBaseUnits(amount, decimals) }
            .fold(onSuccess = { rawAmount >= it }, onFailure = { false })

    companion object {
        /**
         * The maximum `uint256`, the value an unlimited approval sets.
         *
         * Built from `valueOf(2)` rather than `BigInteger.TWO`, which is Java 9+ and so not
         * available at this module's source level.
         */
        val UNLIMITED_RAW_AMOUNT: BigInteger =
            BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE)
    }
}
