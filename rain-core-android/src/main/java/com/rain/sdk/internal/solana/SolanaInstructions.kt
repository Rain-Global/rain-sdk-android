package com.rain.sdk.internal.solana

import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.constants.SolanaPrograms
import com.rain.sdk.internal.utils.RainSignatureSalt
import java.math.BigInteger

/**
 * An account referenced by an instruction, with the privileges that instruction needs.
 *
 * `isSigner` / `isWritable` are not properties of the account itself but of its use here:
 * [SolanaTransactionBuilder] merges the flags across every instruction in a transaction to build
 * the message's account table and header.
 *
 * [pubkey] is held by reference, not copied — treat it as immutable.
 */
@RainAdapterApi
class AccountMeta(
    val pubkey: ByteArray,
    val isSigner: Boolean = false,
    val isWritable: Boolean = false
) {
    companion object {
        fun readonly(pubkey: ByteArray) = AccountMeta(pubkey)
        fun writable(pubkey: ByteArray) = AccountMeta(pubkey, isWritable = true)
        fun signer(pubkey: ByteArray) = AccountMeta(pubkey, isSigner = true)
        fun signerAndWritable(pubkey: ByteArray) = AccountMeta(pubkey, isSigner = true, isWritable = true)
    }
}

/** A single program invocation: which program, which accounts, and the program's own payload. */
@RainAdapterApi
class Instruction(
    val programId: ByteArray,
    val accounts: List<AccountMeta>,
    val data: ByteArray
)

/**
 * Builders for the instructions the SDK sends on Solana.
 *
 * Account order within each instruction is fixed by the target program's interface and must not
 * be rearranged — programs read their accounts positionally.
 */
@RainAdapterApi
object SolanaInstructions {
    /** `SystemInstruction::Transfer`. Also matched by [SolanaTransactionDecoder]. */
    const val SYSTEM_TRANSFER_INDEX = 2

    /**
     * `TokenInstruction::TransferChecked`. Preferred over `Transfer` (3): the program verifies the
     * mint and decimals on chain, so a wrong-scale amount fails instead of moving the wrong value.
     * Also matched by [SolanaTransactionDecoder].
     */
    const val TOKEN_TRANSFER_CHECKED_INDEX = 12

    /**
     * `AssociatedTokenAccountInstruction::CreateIdempotent`. Preferred over `Create` (0), which
     * fails if the account already exists.
     */
    private const val ATA_CREATE_IDEMPOTENT_INDEX = 1

    // `BigInteger.TWO` needs API 33; minSdk is lower.
    private val U64_LIMIT = BigInteger.valueOf(2).pow(64)

    /** Moves native SOL between system accounts. */
    fun systemTransfer(from: ByteArray, to: ByteArray, lamports: Long): Instruction {
        require(lamports >= 0) { "lamports must be non-negative: $lamports" }
        val data = ByteArray(12)
        writeU32LE(data, 0, SYSTEM_TRANSFER_INDEX.toLong())
        writeU64LE(data, 4, BigInteger.valueOf(lamports))
        return Instruction(
            programId = SolanaPrograms.SYSTEM,
            accounts = listOf(AccountMeta.signerAndWritable(from), AccountMeta.writable(to)),
            data = data
        )
    }

    /**
     * Moves SPL tokens between two token accounts of the same mint.
     *
     * @param tokenProgramId program that owns [mint] — [SolanaPrograms.TOKEN] or
     *                       [SolanaPrograms.TOKEN_2022]
     * @param source      sender's token account (not their wallet)
     * @param destination recipient's token account (not their wallet)
     * @param owner       wallet authorising the transfer; signs the transaction
     * @param amount      amount in the mint's base units
     * @param decimals    the mint's decimals, verified on chain against [mint]
     */
    fun transferChecked(
        tokenProgramId: ByteArray,
        source: ByteArray,
        mint: ByteArray,
        destination: ByteArray,
        owner: ByteArray,
        amount: BigInteger,
        decimals: Int
    ): Instruction {
        require(amount.signum() >= 0 && amount < U64_LIMIT) {
            "SPL amount out of u64 range: $amount"
        }
        require(decimals in 0..255) { "Invalid mint decimals: $decimals" }

        val data = ByteArray(10)
        data[0] = TOKEN_TRANSFER_CHECKED_INDEX.toByte()
        writeU64LE(data, 1, amount)
        data[9] = decimals.toByte()

        return Instruction(
            programId = tokenProgramId,
            accounts = listOf(
                AccountMeta.writable(source),
                AccountMeta.readonly(mint),
                AccountMeta.writable(destination),
                AccountMeta.signer(owner)
            ),
            data = data
        )
    }

    /**
     * Creates [owner]'s associated token account for [mint], or does nothing if it already exists.
     *
     * [payer] funds the account's rent-exempt balance, so on a transfer this is the *sender*, not
     * the recipient who ends up owning the account.
     */
    fun createAssociatedTokenAccountIdempotent(
        tokenProgramId: ByteArray,
        payer: ByteArray,
        associatedAccount: ByteArray,
        owner: ByteArray,
        mint: ByteArray
    ): Instruction = Instruction(
        programId = SolanaPrograms.ASSOCIATED_TOKEN,
        accounts = listOf(
            AccountMeta.signerAndWritable(payer),
            AccountMeta.writable(associatedAccount),
            AccountMeta.readonly(owner),
            AccountMeta.readonly(mint),
            AccountMeta.readonly(SolanaPrograms.SYSTEM),
            AccountMeta.readonly(tokenProgramId)
        ),
        data = byteArrayOf(ATA_CREATE_IDEMPOTENT_INDEX.toByte())
    )

    /**
     * Native ed25519-program verification of a single [signature] by [signer] over [message].
     *
     * Layout matches Rain's `Ed25519ExtendedProgram`: count + padding, one 14-byte offsets
     * struct (with `u16::MAX` as the instruction index, meaning "this instruction"), then
     * pubkey ‖ signature ‖ message. The instruction has no accounts; programs read its result
     * back through the instructions sysvar.
     */
    fun ed25519Verify(signer: ByteArray, signature: ByteArray, message: ByteArray): Instruction {
        require(signer.size == SolanaAddresses.PUBLIC_KEY_LENGTH) {
            "ed25519 signer must be ${SolanaAddresses.PUBLIC_KEY_LENGTH} bytes, got ${signer.size}"
        }
        require(signature.size == 64) { "ed25519 signature must be 64 bytes, got ${signature.size}" }
        require(message.size == 32) { "ed25519 message must be 32 bytes, got ${message.size}" }

        val pubkeyOffset = 2 + 14 // count + padding + one offsets struct
        val signatureOffset = pubkeyOffset + SolanaAddresses.PUBLIC_KEY_LENGTH
        val messageOffset = signatureOffset + 64

        val data = ByteArray(messageOffset + 32)
        data[0] = 1 // signature count
        writeU16LE(data, 2, signatureOffset)
        writeU16LE(data, 4, ED25519_THIS_INSTRUCTION)
        writeU16LE(data, 6, pubkeyOffset)
        writeU16LE(data, 8, ED25519_THIS_INSTRUCTION)
        writeU16LE(data, 10, messageOffset)
        writeU16LE(data, 12, message.size)
        writeU16LE(data, 14, ED25519_THIS_INSTRUCTION)
        signer.copyInto(data, pubkeyOffset)
        signature.copyInto(data, signatureOffset)
        message.copyInto(data, messageOffset)

        return Instruction(
            programId = SolanaPrograms.ED25519_VERIFY,
            accounts = emptyList(),
            data = data
        )
    }

    /**
     * Rain collateral program (v2.02): `withdraw_single_signer_collateral_asset`.
     *
     * Account order and the borsh-encoded request follow the program's Anchor IDL. [owner] both
     * signs the transaction and pays the fee; the coordinator's authorization arrives via a
     * preceding [ed25519Verify] instruction, and [coordinatorSignatureSalt] ties this request to
     * that signature's domain.
     */
    fun withdrawSingleSignerCollateralAsset(
        programId: ByteArray,
        owner: ByteArray,
        coordinator: ByteArray,
        collateral: ByteArray,
        collateralAuthority: ByteArray,
        destination: ByteArray,
        asset: ByteArray,
        collateralTokenAccount: ByteArray,
        destinationTokenAccount: ByteArray,
        tokenProgramId: ByteArray,
        amountBaseUnits: BigInteger,
        expiresAtEpochSeconds: Long,
        coordinatorSignatureSalt: ByteArray
    ): Instruction {
        require(amountBaseUnits.signum() >= 0 && amountBaseUnits < U64_LIMIT) {
            "Withdrawal amount out of u64 range: $amountBaseUnits"
        }
        require(coordinatorSignatureSalt.size == RainSignatureSalt.LENGTH) {
            "Coordinator signature salt must be ${RainSignatureSalt.LENGTH} bytes, got ${coordinatorSignatureSalt.size}"
        }

        // Borsh: discriminator ‖ WithdrawSingleSignerCollateralAssetRequest
        //   { amount_in_asset: u64, signature_expiration_time: i64, coordinator_signature_salt: [u8; 32] }
        val data = ByteArray(8 + 8 + 8 + RainSignatureSalt.LENGTH)
        WITHDRAW_SINGLE_SIGNER_DISCRIMINATOR.copyInto(data, 0)
        writeU64LE(data, 8, amountBaseUnits)
        writeU64LE(data, 16, BigInteger.valueOf(expiresAtEpochSeconds))
        coordinatorSignatureSalt.copyInto(data, 24)

        return Instruction(
            programId = programId,
            accounts = listOf(
                AccountMeta.signerAndWritable(owner),
                AccountMeta.readonly(coordinator),
                AccountMeta.writable(collateral),
                AccountMeta.writable(collateralAuthority),
                AccountMeta.writable(destination),
                AccountMeta.readonly(asset),
                AccountMeta.writable(collateralTokenAccount),
                AccountMeta.writable(destinationTokenAccount),
                AccountMeta.readonly(tokenProgramId),
                AccountMeta.readonly(SolanaPrograms.SYSVAR_INSTRUCTIONS),
                AccountMeta.readonly(SolanaPrograms.SYSTEM)
            ),
            data = data
        )
    }

    /** Anchor discriminator `sha256("global:withdraw_single_signer_collateral_asset")[0..8]`. */
    private val WITHDRAW_SINGLE_SIGNER_DISCRIMINATOR = byteArrayOf(
        13,
        25,
        64,
        83,
        111,
        184.toByte(),
        70,
        241.toByte()
    )

    /** In an ed25519 offsets struct, `u16::MAX` points the runtime at this same instruction. */
    private const val ED25519_THIS_INSTRUCTION = 0xFFFF

    private fun writeU16LE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeU32LE(target: ByteArray, offset: Int, value: Long) {
        var v = value
        for (i in 0 until 4) {
            target[offset + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    private fun writeU64LE(target: ByteArray, offset: Int, value: BigInteger) {
        for (i in 0 until 8) {
            target[offset + i] = value.shiftRight(8 * i).and(BigInteger.valueOf(0xFF)).toByte()
        }
    }
}
