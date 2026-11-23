package com.rain.sdk.models

import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.solana.SolanaTransactionBuilder

/**
 * An unsigned Solana transfer ready for a wallet provider to sign. A public model: hosts read it
 * through [RainPreparedWithdrawal.Solana.transfer], and adapters receive it from the SDK's Solana
 * support; the constructor is adapter API ([RainAdapterApi]).
 */
class UnsignedSolanaTransfer @RainAdapterApi constructor(
    transaction: ByteArray,
    /**
     * The blockhash the transaction was built against, in base58. It is fetched at `finalized`
     * commitment, so of the 60 to 90 seconds a blockhash lives, about 13 have passed on return.
     */
    val recentBlockhash: String,
    /**
     * True when the transfer creates the recipient's token account, whose rent the SDK demands from
     * the sender before signing unless the signing provider's organization funds it; the Rain wallet
     * section of `docs/METHODS.md` has the contract.
     */
    val createsRecipientAccount: Boolean = false
) {
    private val transactionBytes = transaction

    /**
     * Defensive copy: the bytes are what the provider signs and the host may submit, so callers must
     * not mutate them.
     */
    val transaction: ByteArray get() = transactionBytes.copyOf()

    /** [transaction] as hex without a `0x` prefix. */
    val transactionHex: String get() = SolanaTransactionBuilder.hexEncode(transactionBytes)
}
