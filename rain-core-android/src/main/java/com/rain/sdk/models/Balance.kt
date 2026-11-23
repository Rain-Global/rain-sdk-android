package com.rain.sdk.models

import java.math.BigDecimal
import java.math.BigInteger

/**
 * A token balance with exact base-unit precision plus resolved display metadata.
 *
 * [rawAmount] is the exact on-chain value in the token's smallest unit (wei-equivalent);
 * it is never lossy. [decimalAmount] and [formatted] are derived for display.
 */
data class Balance(
    /** Which token this balance is for ([Token.Native] or a [Token.Contract]). */
    val token: Token,

    /** EIP-155 chain ID the balance was read on. Keeps merged, cross-chain lists self-describing. */
    val chainId: Int,

    /** Exact balance in the token's smallest unit (e.g. wei for an 18-decimal token). */
    val rawAmount: BigInteger,

    /** The token's decimal places, the scale of [rawAmount] (6 for USDC, 18 for ETH). */
    val decimals: Int,

    /** Token symbol (e.g. "USDC"), when known. */
    val symbol: String? = null,

    /** Human-readable token name (e.g. "USD Coin"), when known. */
    val name: String? = null
) {
    /** The balance as a precise [BigDecimal] (`rawAmount / 10^decimals`). */
    val decimalAmount: BigDecimal
        get() = rawAmount.toBigDecimal().movePointLeft(decimals)

    /** Human-readable balance string with trailing zeros trimmed (e.g. `"1.5"`). */
    val formatted: String
        get() = decimalAmount.stripTrailingZeros().toPlainString()
}
