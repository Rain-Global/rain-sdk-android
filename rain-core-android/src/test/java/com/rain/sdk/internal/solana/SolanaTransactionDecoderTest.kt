package com.rain.sdk.internal.solana

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.constants.SolanaPrograms
import org.junit.Test
import java.math.BigInteger
import java.util.Base64 as JavaBase64

/**
 * Round-trips what [SolanaTransactionBuilder] emits, since transaction history reconstructs a
 * transfer's recipient, amount and asset purely from the stored unsigned transaction. Also covers
 * blobs this SDK never produces but the same Turnkey wallet can accrue from other tooling: the
 * bare SPL `Transfer` instruction and base64 encoding.
 */
class SolanaTransactionDecoderTest {

    private val fromBytes = ByteArray(32) { (it + 1).toByte() }
    private val toBytes = ByteArray(32) { (it + 33).toByte() }
    private val mintBytes = ByteArray(32) { (it + 97).toByte() }

    private val from = Base58.encode(fromBytes)
    private val to = Base58.encode(toBytes)
    private val mint = Base58.encode(mintBytes)
    private val blockhash = Base58.encode(ByteArray(32) { (it + 65).toByte() })

    // ---------- native SOL ----------

    @Test
    fun `decodes the transfer the builder produced`() {
        val lamports = 1_234_500_000L
        val hex = transferHex(from, to, lamports, blockhash)

        val decoded = SolanaTransactionDecoder.decode(hex)

        assertThat(decoded).isInstanceOf(SolanaTransactionDecoder.NativeTransfer::class.java)
        decoded as SolanaTransactionDecoder.NativeTransfer
        assertThat(decoded.from).isEqualTo(from)
        assertThat(decoded.to).isEqualTo(to)
        assertThat(decoded.lamports).isEqualTo(BigInteger.valueOf(lamports))
    }

    @Test
    fun `tolerates an optional 0x prefix`() {
        val hex = transferHex(from, to, 1L, blockhash)
        val decoded = SolanaTransactionDecoder.decode("0x$hex")
        assertThat((decoded as SolanaTransactionDecoder.NativeTransfer).lamports)
            .isEqualTo(BigInteger.ONE)
    }

    @Test
    fun `decodes a base64-encoded transaction`() {
        // Base64 is the canonical Solana transaction encoding; blobs stored by other tooling
        // against the same Turnkey wallet plausibly arrive that way.
        val hex = transferHex(from, to, 7L, blockhash)
        val base64 = JavaBase64.getEncoder().encodeToString(hexToBytes(hex))

        val decoded = SolanaTransactionDecoder.decode(base64)

        assertThat((decoded as SolanaTransactionDecoder.NativeTransfer).lamports)
            .isEqualTo(BigInteger.valueOf(7))
        assertThat(decoded.to).isEqualTo(to)
    }

    @Test
    fun `returns null for non-hex or undecodable input`() {
        assertThat(SolanaTransactionDecoder.decode("not-hex!!")).isNull()
        assertThat(SolanaTransactionDecoder.decode("abcd")).isNull()
    }

    // ---------- SPL ----------

    @Test
    fun `decodes an spl transfer including the mint and scale`() {
        val decoded = SolanaTransactionDecoder.decode(splTransferHex())

        assertThat(decoded).isInstanceOf(SolanaTransactionDecoder.SplTransfer::class.java)
        decoded as SolanaTransactionDecoder.SplTransfer
        assertThat(decoded.from).isEqualTo(from)
        assertThat(decoded.mint).isEqualTo(mint)
        assertThat(decoded.amount).isEqualTo(BigInteger.valueOf(1_500_000L))
        // Carried by the instruction, so history needs no mint lookup to scale the amount.
        assertThat(decoded.decimals).isEqualTo(6)
        assertThat(decoded.source).isEqualTo(Base58.encode(sourceAta()))
        assertThat(decoded.destination).isEqualTo(Base58.encode(destinationAta()))
        // Nothing in this transaction names the wallet behind the destination token account.
        assertThat(decoded.destinationOwner).isNull()
    }

    @Test
    fun `recovers the recipient wallet from an account-creation instruction`() {
        // A transfer to a first-time recipient is preceded by a create-token-account instruction,
        // whose account list is the one place the recipient's wallet appears on the wire.
        val decoded = SolanaTransactionDecoder.decode(splTransferHex(createDestination = true))

        assertThat(decoded).isInstanceOf(SolanaTransactionDecoder.SplTransfer::class.java)
        decoded as SolanaTransactionDecoder.SplTransfer
        assertThat(decoded.destinationOwner).isEqualTo(to)
        assertThat(decoded.destination).isEqualTo(Base58.encode(destinationAta()))
        assertThat(decoded.amount).isEqualTo(BigInteger.valueOf(1_500_000L))
    }

    @Test
    fun `decodes a token-2022 transfer`() {
        val decoded = SolanaTransactionDecoder.decode(
            splTransferHex(tokenProgram = SolanaPrograms.TOKEN_2022)
        )

        assertThat(decoded).isInstanceOf(SolanaTransactionDecoder.SplTransfer::class.java)
        assertThat((decoded as SolanaTransactionDecoder.SplTransfer).mint).isEqualTo(mint)
    }

    @Test
    fun `decodes the bare transfer instruction other tooling emits`() {
        // TokenInstruction::Transfer (tag 3) — @solana/spl-token's default. Accounts are
        // [source, destination, owner] and the data carries neither mint nor decimals.
        val data = ByteArray(9)
        data[0] = 3
        var amount = 250_000L
        for (i in 0 until 8) {
            data[1 + i] = (amount and 0xFF).toByte()
            amount = amount ushr 8
        }
        val hex = unsignedHex(
            feePayer = fromBytes,
            recentBlockhash = blockhash,
            instructions = listOf(
                Instruction(
                    programId = SolanaPrograms.TOKEN,
                    accounts = listOf(
                        AccountMeta.writable(sourceAta()),
                        AccountMeta.writable(destinationAta()),
                        AccountMeta.signer(fromBytes)
                    ),
                    data = data
                )
            )
        )

        val decoded = SolanaTransactionDecoder.decode(hex)

        assertThat(decoded).isInstanceOf(SolanaTransactionDecoder.SplTransfer::class.java)
        decoded as SolanaTransactionDecoder.SplTransfer
        assertThat(decoded.from).isEqualTo(from)
        assertThat(decoded.source).isEqualTo(Base58.encode(sourceAta()))
        assertThat(decoded.destination).isEqualTo(Base58.encode(destinationAta()))
        assertThat(decoded.amount).isEqualTo(BigInteger.valueOf(250_000L))
        // The bare instruction carries neither, so history resolves them elsewhere.
        assertThat(decoded.mint).isNull()
        assertThat(decoded.decimals).isNull()
        assertThat(decoded.destinationOwner).isNull()
    }

    @Test
    fun `reads token amounts above Long MAX_VALUE`() {
        val huge = BigInteger("18446744073709551615") // u64 max
        val decoded = SolanaTransactionDecoder.decode(splTransferHex(amount = huge))

        assertThat((decoded as SolanaTransactionDecoder.SplTransfer).amount).isEqualTo(huge)
    }

    // ---------- helpers ----------

    private fun sourceAta(tokenProgram: ByteArray = SolanaPrograms.TOKEN) =
        SolanaAddresses.associatedTokenAddress(fromBytes, mintBytes, tokenProgram)

    private fun destinationAta(tokenProgram: ByteArray = SolanaPrograms.TOKEN) =
        SolanaAddresses.associatedTokenAddress(toBytes, mintBytes, tokenProgram)

    private fun splTransferHex(
        createDestination: Boolean = false,
        tokenProgram: ByteArray = SolanaPrograms.TOKEN,
        amount: BigInteger = BigInteger.valueOf(1_500_000L)
    ): String {
        val instructions = buildList {
            if (createDestination) {
                add(
                    SolanaInstructions.createAssociatedTokenAccountIdempotent(
                        tokenProgramId = tokenProgram,
                        payer = fromBytes,
                        associatedAccount = destinationAta(tokenProgram),
                        owner = toBytes,
                        mint = mintBytes
                    )
                )
            }
            add(
                SolanaInstructions.transferChecked(
                    tokenProgramId = tokenProgram,
                    source = sourceAta(tokenProgram),
                    mint = mintBytes,
                    destination = destinationAta(tokenProgram),
                    owner = fromBytes,
                    amount = amount,
                    decimals = 6
                )
            )
        }
        return unsignedHex(fromBytes, blockhash, instructions)
    }

    private fun transferHex(from: String, to: String, lamports: Long, recentBlockhash: String): String =
        SolanaTransactionBuilder.hexEncode(SolanaTransactionBuilder.buildTransferBytes(from, to, lamports, recentBlockhash))

    private fun unsignedHex(feePayer: ByteArray, recentBlockhash: String, instructions: List<Instruction>): String =
        SolanaTransactionBuilder.hexEncode(
            SolanaTransactionBuilder.buildUnsignedTransaction(feePayer, recentBlockhash, instructions)
        )

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
}
