package com.rain.sdk.models

/**
 * Result returned after performing a token transfer.
 */
data class RainTokenTransferResult(
    /**
     * The transaction hash on the blockchain.
     */
    val transactionHash: String
)
