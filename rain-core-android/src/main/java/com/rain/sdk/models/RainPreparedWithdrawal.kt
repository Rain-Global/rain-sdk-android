package com.rain.sdk.models

/**
 * A collateral withdrawal built but not broadcast, in the shape the target chain requires.
 *
 * Not offline: building this still prompts the wallet to sign EIP-712 (EVM) and reads the
 * collateral's admin set on chain. A Solana blockhash lasts ~150 slots (60 to 90 s) and is fetched
 * at finalized commitment, so submit promptly or re-prepare.
 */
sealed interface RainPreparedWithdrawal {

    /** A complete, submittable EVM transaction — from/to/value/data, not bare calldata. */
    data class Evm(val parameters: RainTransactionParameters) : RainPreparedWithdrawal

    /** The serialized unsigned Solana transaction, fee-checked and simulated as the host's own self-paid submission, with its blockhash. */
    data class Solana(val transfer: UnsignedSolanaTransfer) : RainPreparedWithdrawal

    /** The EVM parameters, or null on a Solana withdrawal. */
    val evmParameters: RainTransactionParameters?
        get() = (this as? Evm)?.parameters

    /** The unsigned Solana transfer, or null on an EVM withdrawal. */
    val solanaTransfer: UnsignedSolanaTransfer?
        get() = (this as? Solana)?.transfer
}
