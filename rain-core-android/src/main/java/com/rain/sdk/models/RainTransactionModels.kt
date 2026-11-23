package com.rain.sdk.models

import java.math.BigDecimal

/** Sort order for transaction history. */
enum class RainTransactionOrder { ASC, DESC }

/**
 * Transaction category. An extensible constant rather than a closed enum: Portal passes its
 * indexer's vocabulary straight through, and an unrecognised value must not be dropped.
 */
data class RainTransactionCategory(val value: String) {
    companion object {
        /** A plain value transfer between externally-owned accounts. */
        val External = RainTransactionCategory("external")

        /** A fungible-token transfer whose standard the provider does not name (Solana SPL). */
        val Token = RainTransactionCategory("token")
        val Erc20 = RainTransactionCategory("erc20")
        val Erc721 = RainTransactionCategory("erc721")
        val Erc1155 = RainTransactionCategory("erc1155")

        /** A transfer made by a contract while executing another transaction. */
        val ContractInternal = RainTransactionCategory("internal")
    }
}

/**
 * A wallet transaction returned by `getTransactions`. Vendor-agnostic; each provider adapter maps
 * its own history source onto this in its own module.
 */
data class RainTransaction(
    /**
     * Transaction hash (EVM) or signature (Solana). May be a provider-internal activity id when
     * the real hash is not known yet, and may be empty for a row carrying neither.
     */
    val hash: String,
    /**
     * Provider-stable row identifier, when the history source has one. May be absent or blank —
     * do not use it as a list key.
     */
    val uniqueId: String? = null,
    /** Block the transaction was included in. Only providers with an indexed source fill this. */
    val blockNumber: String? = null,
    /** ISO-8601 timestamp of the block, or of the provider activity when there is no block yet. */
    val timestamp: String? = null,
    /** Sender address. */
    val from: String,
    /** Recipient address; for SPL rows the recipient's *wallet* where it could be resolved. */
    val to: String? = null,
    /**
     * Amount in human-readable units. Null when decimals could not be resolved — [rawValue] is
     * still populated in that case. Producers normalise with `stripTrailingZeros()`, so the
     * generated `equals` is scale-sensitive only for values that bypass them.
     */
    val value: BigDecimal? = null,
    /** Asset symbol (e.g. ETH, SOL, USDC), when the provider can resolve one. */
    val asset: String? = null,
    /** Contract address (EVM) or mint (Solana). Null for a native transfer. */
    val tokenAddress: String? = null,
    /** Amount in the token's smallest unit, as an unscaled decimal string. */
    val rawValue: String? = null,
    /** Decimals used to scale [rawValue] into [value]. */
    val decimals: Int? = null,
    /** Transaction category. See [RainTransactionCategory]. */
    val category: RainTransactionCategory? = null,
    /** Chain the transaction executed on. */
    val chainId: Int,
    /** Indexer / Solana detail; see [Metadata]. */
    val metadata: Metadata? = null
) {
    /**
     * Indexer-supplied detail. Populated only by providers whose history source carries it, plus
     * the two Solana token-account fields. Null for every other row.
     */
    data class Metadata(
        /** CAIP-2 identifier of the chain the transaction executed on (e.g. `eip155:1`). */
        val caip2: String? = null,
        /** Indexer-reported status (e.g. `broadcasted`, `confirmed`, `finalized`, `failed`). */
        val status: String? = null,
        /** Whether the transaction's gas was sponsored. */
        val sponsored: Boolean? = null,
        /** Privy's internal id, when the row came from Privy's indexer. */
        val privyTransactionId: String? = null,
        /** ERC-4337 user-operation hash, when submitted as a user operation. */
        val userOperationHash: String? = null,
        /** Transfer direction (e.g. `transferSent`, `transferReceived`). */
        val type: String? = null,
        /** Human-readable value renderings keyed by unit, as supplied by the indexer. */
        val displayValues: Map<String, String>? = null,
        /** SPL only: the sender's token account (not the wallet). */
        val sourceTokenAccount: String? = null,
        /** SPL only: the recipient's token account — [to] holds the wallet where resolvable. */
        val destinationTokenAccount: String? = null
    )
}
