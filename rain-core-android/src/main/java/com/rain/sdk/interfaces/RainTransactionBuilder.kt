package com.rain.sdk.interfaces

import com.rain.sdk.error.RainError
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainEIP712Message
import com.rain.sdk.models.RainWithdrawAddresses
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Wallet-agnostic withdrawal-building primitives. These need no resolved provider, only the
 * configured RPC endpoints; hosts reach them as methods on [com.rain.sdk.RainSdk], which is why the
 * interface itself is internal.
 */
internal interface RainTransactionBuilder {

    /** Surfaced to hosts as [com.rain.sdk.RainSdk.getLatestNonce]; that KDoc has the contract. */
    suspend fun getLatestNonce(
        chainId: Int,
        proxyAddress: String
    ): BigInteger

    /**
     * Reads `isAdmin(walletAddress)` on the contract at [proxyAddress]; [CollateralAdminCheck] has
     * the outcomes. Surfaced to hosts as [com.rain.sdk.RainSdk.isCollateralAdmin].
     */
    suspend fun isCollateralAdmin(
        chainId: Int,
        proxyAddress: String,
        walletAddress: String
    ): CollateralAdminCheck

    /** Surfaced to hosts as [com.rain.sdk.RainSdk.buildEIP712Message]; that KDoc has the contract. */
    suspend fun buildEIP712Message(
        chainId: Int,
        walletAddress: String,
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        nonce: BigInteger? = null,
    ): RainEIP712Message

    /**
     * Surfaced to hosts as [com.rain.sdk.RainSdk.buildWithdrawTransactionData]; that KDoc has the
     * contract and the meaning of each parameter.
     */
    fun buildWithdrawTransactionData(
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        executorSignature: RainAdminSignature,
        walletSalt: ByteArray,
        walletSignature: String
    ): String
}

/** What an `isAdmin(wallet)` read on a collateral contract found. */
internal sealed interface CollateralAdminCheck {
    data object Admin : CollateralAdminCheck

    data object NotAdmin : CollateralAdminCheck

    /**
     * The read gave no answer: it was refused before it was sent, the request failed, or the node
     * answered with an error that is not a revert or with a result that does not decode. [error] is
     * what a caller that needs an answer throws.
     */
    data class Failed(val error: RainError) : CollateralAdminCheck

    /** The address is not a collateral contract: the call reverted, or returned no data or a word other than 0 or 1. */
    data object NotCollateral : CollateralAdminCheck
}
