package com.rain.sdk.internal.transaction

import com.rain.sdk.error.RainError
import java.math.BigDecimal

/**
 * Validates transaction parameters.
 *
 * Centralizes all validation logic to ensure consistent error handling
 * and clear validation rules across the SDK.
 */
internal class TransactionValidator {

    /**
     * Validates a withdraw collateral request. An unusable chain is a config error; an unusable
     * amount or scale is an amount error.
     *
     * @throws RainError.InvalidConfig for a non-positive chainId
     * @throws RainError.InvalidAmount for a non-positive amount or negative decimals
     */
    fun validateWithdrawRequest(request: WithdrawCollateralRequest) =
        validateWithdrawRequest(request.chainId, request.amount, request.decimals)

    /**
     * Chain-family-agnostic form, callable before a wallet address has been resolved. Both the EVM
     * and Solana withdrawal paths run this, so neither can compose a transaction from parameters
     * that cannot produce a valid one.
     */
    fun validateWithdrawRequest(chainId: Int, amount: BigDecimal, decimals: Int) {
        if (chainId <= 0) {
            throw RainError.InvalidConfig("Invalid chainId: $chainId. Must be a positive integer.")
        }

        if (amount <= BigDecimal.ZERO) {
            throw RainError.InvalidAmount(
                amount.toPlainString(),
                "amount must be greater than zero"
            )
        }

        if (decimals < 0) {
            throw RainError.InvalidAmount(
                amount.toPlainString(),
                "decimals must be non-negative, got $decimals"
            )
        }
    }
}
