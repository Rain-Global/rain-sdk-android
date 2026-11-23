package com.rain.sdk.internal.solana

import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.constants.SolanaPrograms
import java.math.BigInteger
import java.util.Base64

/**
 * Minimal decoder for unsigned legacy Solana transactions.
 *
 * A managed-broadcast send records only the unsigned transaction — no recipient, amount or asset
 * — so transaction history recovers those by parsing the blob back.
 * The blob is usually the hex this SDK's [SolanaTransactionBuilder] produced, but the same wallet
 * can be driven by other tooling (web3.js, other SDK versions), so the
 * decoder also accepts base64 input and the bare SPL `Transfer` instruction that
 * `@solana/spl-token` emits by default.
 *
 * Reverses the builder's wire format: compact-u16 signature count + zero-filled signatures +
 * message (header, account keys, blockhash, instructions), then looks for the instruction that
 * moves value. A transaction may carry more than one instruction (an SPL transfer to a first-time
 * recipient is preceded by a create-token-account instruction), so every instruction is examined
 * and the non-transfer ones are skipped. Returns null if the bytes are not a decodable
 * transaction containing a transfer this SDK knows how to describe.
 */
@RainAdapterApi
object SolanaTransactionDecoder {
    private const val SIGNATURE_LENGTH = 64

    /**
     * `TokenInstruction::Transfer` — the bare transfer. The SDK's own builder always emits
     * `TransferChecked`, but transactions built elsewhere against the same wallet commonly use
     * this, and history must render them too.
     */
    private const val TOKEN_TRANSFER_INDEX = 3

    /** Associated Token Account program tags that create an account (`Create` / `CreateIdempotent`). */
    private val ATA_CREATE_TAGS = setOf(0, 1)

    /** A value transfer recovered from an unsigned transaction. */
    sealed interface Transfer {
        /** The account authorising the transfer — the wallet, in every transaction the SDK builds. */
        val from: String
    }

    /** A System Program transfer of native SOL. Lamports are u64, read unsigned. */
    data class NativeTransfer(
        override val from: String,
        val to: String,
        val lamports: BigInteger
    ) : Transfer

    /**
     * An SPL `TransferChecked` or bare `Transfer`. [source] and [destination] are *token
     * accounts*, not wallets. [destinationOwner] is the recipient's wallet when the transaction
     * itself creates the destination token account (the one place it appears on the wire) and
     * null otherwise — the caller then resolves it, or displays the token account.
     * [mint] and [decimals] are null for the bare `Transfer` instruction, which carries neither.
     */
    data class SplTransfer(
        override val from: String,
        val source: String,
        val destination: String,
        val destinationOwner: String?,
        val mint: String?,
        val amount: BigInteger,
        val decimals: Int?
    ) : Transfer

    /**
     * Decodes the first value transfer in [unsignedTransaction] (hex, or base64 as a fallback),
     * or null if there is none.
     */
    fun decode(unsignedTransaction: String): Transfer? {
        val bytes = hexToBytes(unsignedTransaction) ?: base64ToBytes(unsignedTransaction) ?: return null
        val message = runCatching { parse(bytes) }.getOrNull() ?: return null
        return decodeTransfer(message)
    }

    // ---------- instruction matching ----------

    private fun decodeTransfer(message: DecodedMessage): Transfer? {
        for (instruction in message.instructions) {
            val programId = message.programId(instruction) ?: continue
            decodeInstruction(programId, instruction, message)?.let { return it }
        }
        return null
    }

    private fun decodeInstruction(
        programId: ByteArray,
        instruction: DecodedInstruction,
        message: DecodedMessage
    ): Transfer? {
        val data = instruction.data
        fun account(position: Int): String? = message.account(instruction, position)

        val isSystem = programId.contentEquals(SolanaPrograms.SYSTEM)
        if (isSystem &&
            data.size >= 12 &&
            readU32LE(data, 0) == SolanaInstructions.SYSTEM_TRANSFER_INDEX.toLong()
        ) {
            return NativeTransfer(
                from = account(0) ?: return null,
                to = account(1) ?: return null,
                lamports = readU64Unsigned(data, 4)
            )
        }

        val isTokenProgram = programId.contentEquals(SolanaPrograms.TOKEN) ||
            programId.contentEquals(SolanaPrograms.TOKEN_2022)
        if (!isTokenProgram || data.isEmpty()) return null

        // TransferChecked: [source, mint, destination, owner], data = tag + u64 amount + u8 decimals.
        // Transfer:        [source, destination, owner],       data = tag + u64 amount.
        val tag = data[0].toInt() and 0xFF
        val checked = tag == SolanaInstructions.TOKEN_TRANSFER_CHECKED_INDEX
        if (!checked && tag != TOKEN_TRANSFER_INDEX) return null
        if (data.size < if (checked) 10 else 9) return null

        val destination = account(if (checked) 2 else 1) ?: return null
        return SplTransfer(
            from = account(if (checked) 3 else 2) ?: return null,
            source = account(0) ?: return null,
            destination = destination,
            destinationOwner = ataOwner(destination, message),
            mint = if (checked) account(1) ?: return null else null,
            amount = readU64Unsigned(data, 1),
            decimals = if (checked) data[9].toInt() and 0xFF else null
        )
    }

    /**
     * The wallet an Associated Token Account program instruction in this transaction creates
     * [tokenAccount] for — the one place a transfer's recipient wallet appears on the wire.
     */
    private fun ataOwner(tokenAccount: String, message: DecodedMessage): String? {
        for (instruction in message.instructions) {
            val programId = message.programId(instruction) ?: continue
            if (!programId.contentEquals(SolanaPrograms.ASSOCIATED_TOKEN)) continue
            val tag = instruction.data.firstOrNull()?.let { it.toInt() and 0xFF } ?: continue
            if (tag !in ATA_CREATE_TAGS) continue
            // Accounts: [payer, ata, owner, mint, systemProgram, tokenProgram].
            if (message.account(instruction, 1) != tokenAccount) continue
            return message.account(instruction, 2)
        }
        return null
    }

    // ---------- message decoding ----------

    private class DecodedInstruction(
        val programIdIndex: Int,
        val accountIndices: IntArray,
        val data: ByteArray
    )

    private class DecodedMessage(
        val accounts: List<ByteArray>,
        val instructions: List<DecodedInstruction>
    ) {
        fun programId(instruction: DecodedInstruction): ByteArray? =
            accounts.getOrNull(instruction.programIdIndex)

        /** The base58 key an instruction references at [position] in its own account list. */
        fun account(instruction: DecodedInstruction, position: Int): String? =
            accounts.getOrNull(instruction.accountIndices.getOrElse(position) { -1 })
                ?.let { Base58.encode(it) }
    }

    private fun parse(bytes: ByteArray): DecodedMessage {
        val reader = Reader(bytes)

        // Signature section: count + count * 64-byte placeholders.
        val signatureCount = reader.readCompactU16()
        reader.skip(signatureCount * SIGNATURE_LENGTH)

        // Message header (3 bytes), unused here.
        reader.readByte()
        reader.readByte()
        reader.readByte()

        // Account keys.
        val accountCount = reader.readCompactU16()
        val accounts = ArrayList<ByteArray>(accountCount)
        repeat(accountCount) { accounts += reader.readBytes(SolanaAddresses.PUBLIC_KEY_LENGTH) }

        // Recent blockhash.
        reader.skip(SolanaTransactionBuilder.BLOCKHASH_LENGTH)

        val instructionCount = reader.readCompactU16()
        val instructions = ArrayList<DecodedInstruction>(instructionCount)
        repeat(instructionCount) {
            val programIdIndex = reader.readByte()
            val accountIndexCount = reader.readCompactU16()
            val accountIndices = IntArray(accountIndexCount) { reader.readByte() }
            val dataLen = reader.readCompactU16()
            instructions += DecodedInstruction(programIdIndex, accountIndices, reader.readBytes(dataLen))
        }

        return DecodedMessage(accounts, instructions)
    }

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun readByte(): Int {
            require(pos < bytes.size) { "Unexpected end of transaction" }
            return bytes[pos++].toInt() and 0xFF
        }

        fun readBytes(length: Int): ByteArray {
            require(pos + length <= bytes.size) { "Unexpected end of transaction" }
            return bytes.copyOfRange(pos, pos + length).also { pos += length }
        }

        fun skip(length: Int) {
            require(pos + length <= bytes.size) { "Unexpected end of transaction" }
            pos += length
        }

        /** Solana short-vec (compact-u16): 7 bits per byte, MSB = continuation. */
        fun readCompactU16(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = readByte()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }
    }

    private fun readU32LE(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    /** Lamport and token amounts are u64; read unsigned so values above `Long.MAX_VALUE` stay accurate. */
    private fun readU64Unsigned(data: ByteArray, offset: Int): BigInteger {
        var value = BigInteger.ZERO
        for (i in 7 downTo 0) {
            value = value.shiftLeft(8).or(BigInteger.valueOf(data[offset + i].toLong() and 0xFF))
        }
        return value
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val clean = if (hex.startsWith("0x", ignoreCase = true)) hex.substring(2) else hex
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            val hi = Character.digit(clean[i], 16)
            val lo = Character.digit(clean[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    private fun base64ToBytes(value: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(value) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
}
