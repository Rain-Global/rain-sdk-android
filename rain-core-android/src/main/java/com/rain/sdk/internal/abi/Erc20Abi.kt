package com.rain.sdk.internal.abi

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.utils.RainAmountUtils
import com.rain.sdk.internal.utils.RainHexUtils
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.generated.Uint256
import java.math.BigDecimal
import java.math.BigInteger
import org.web3j.abi.datatypes.Function as Web3jFunction

/**
 * Canonical ABI encoding for the ERC-20 write calls the SDK sends. Calldata is
 * provider-independent, so it is encoded once here; each wallet adapter broadcasts
 * the bytes through its own `sendTransaction` path.
 *
 * Public (not Kotlin-`internal`) because the out-of-core adapter modules
 * (`rain-portal-android`, `rain-privy-android`) encode calldata too, mirroring [com.rain.sdk.utils.EthereumConverter].
 */
@RainAdapterApi
object Erc20Abi {

    /**
     * Encodes `transfer(address,uint256)` calldata for [tokenAmount] base units.
     *
     * @throws RainError.InvalidRecipient if [toAddress] is not a well-formed EVM address —
     *         web3j's [Address] would otherwise left-zero-pad a short one into a wrong-but-legal
     *         recipient inside opaque calldata.
     */
    fun encodeTransfer(toAddress: String, tokenAmount: BigInteger): String {
        if (!RainHexUtils.isValidAddress(toAddress)) {
            throw RainError.InvalidRecipient(toAddress, "not a valid EVM address")
        }
        val function = Web3jFunction(
            "transfer",
            listOf(Address(toAddress), Uint256(tokenAmount)),
            emptyList<TypeReference<*>>()
        )
        return FunctionEncoder.encode(function)
    }

    /**
     * Encodes `transfer(address,uint256)` calldata for a decimal [amount] of a token with
     * [decimals]. An amount finer than the token can represent is rejected rather than truncated.
     *
     * @throws RainError.InvalidAmount if [amount] carries more decimal places than [decimals].
     */
    @Throws(RainError::class)
    fun encodeTransfer(toAddress: String, amount: BigDecimal, decimals: Int): String =
        encodeTransfer(toAddress, RainAmountUtils.toBaseUnits(amount, decimals))

    /**
     * Encodes `approve(address,uint256)` calldata — the wallet-side prerequisite for Rain's
     * Auth Pull, where [spender] is the user's collateral contract.
     *
     * [allowanceBaseUnits] is in the token's base units; `uint256` max encodes an unlimited
     * allowance and `0` revokes.
     *
     * @throws RainError.InvalidRecipient if [spender] is not a well-formed EVM address — web3j's
     *         [Address] would otherwise left-zero-pad a short one into a wrong-but-legal spender
     *         inside opaque calldata.
     */
    fun encodeApprove(spender: String, allowanceBaseUnits: BigInteger): String {
        if (!RainHexUtils.isValidAddress(spender)) {
            throw RainError.InvalidRecipient(spender, "not a valid EVM address")
        }
        if (allowanceBaseUnits.signum() < 0 || allowanceBaseUnits > MAX_UINT256) {
            throw RainError.InvalidAmount(
                amount = allowanceBaseUnits.toString(),
                reason = "approval amount must fit in uint256"
            )
        }
        val function = Web3jFunction(
            "approve",
            listOf(Address(spender), Uint256(allowanceBaseUnits)),
            emptyList<TypeReference<*>>()
        )
        return FunctionEncoder.encode(function)
    }

    /**
     * Encodes `approve(address,uint256)` calldata for a decimal [amount] of a token with
     * [decimals]; the amount rule is [encodeTransfer]'s.
     *
     * @throws RainError.InvalidAmount as for [encodeTransfer]
     */
    @Throws(RainError::class)
    fun encodeApprove(spender: String, amount: BigDecimal, decimals: Int): String =
        encodeApprove(spender, RainAmountUtils.toBaseUnits(amount, decimals))

    private val MAX_UINT256: BigInteger =
        BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE)
}
