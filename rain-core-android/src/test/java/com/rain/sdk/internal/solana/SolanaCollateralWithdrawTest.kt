package com.rain.sdk.internal.solana

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigInteger
import java.util.Base64

/**
 * The message-encoding golden test uses values captured from a LIVE Rain dev-API withdrawal
 * signature (2026-07-24, devnet collateral `2h5mCX…`): the expected message is the exact
 * 32-byte payload that the coordinator executor's real ed25519 signature verified against.
 * If the encoding drifts in any byte, on-chain verification would fail — this pins it.
 */
class SolanaWithdrawMessagesTest {

    @Test
    fun `coordinator withdraw message matches live-verified vector`() {
        val message = SolanaWithdrawMessages.coordinatorWithdrawMessage(
            coordinator = Base58.decode("FF3tTZ91aRu7XdGc4XK6V7MkDyStJ5ZY2fG4ZKLqFgL5"),
            collateral = Base58.decode("2h5mCXyirbPJZbjGcWf4PTpcA6M7qZ5UCRaWysHjnx31"),
            sender = Base58.decode("3mhBCgFYhFCAV7LFARCcLuLsQLsyErTRc2axkGJrf8UG"),
            receiver = Base58.decode("3mhBCgFYhFCAV7LFARCcLuLsQLsyErTRc2axkGJrf8UG"),
            asset = Base58.decode("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"),
            amountBaseUnits = BigInteger.ONE,
            nonce = 0L,
            expiresAtEpochSeconds = 1_784_912_091L,
            salt = Base64.getDecoder().decode("TQQ0CmdE6fdJzi6aFl6X68AKTETgYWKmPuoKvWpBb5w=")
        )
        assertThat(SolanaTransactionBuilder.hexEncode(message))
            .isEqualTo("77229dff3a1aca1bf472323ba0cdf247669970593ab8f64b31d7e1e74d28e8f4")
    }
}

class SolanaEd25519InstructionTest {

    @Test
    fun `ed25519 verify instruction lays out offsets, pubkey, signature and message`() {
        val signer = ByteArray(32) { 0x11 }
        val signature = ByteArray(64) { 0x22 }
        val message = ByteArray(32) { 0x33 }

        val instruction = SolanaInstructions.ed25519Verify(signer, signature, message)

        assertThat(instruction.programId).isEqualTo(Base58.decode("Ed25519SigVerify111111111111111111111111111"))
        assertThat(instruction.accounts).isEmpty()

        val data = instruction.data
        assertThat(data.size).isEqualTo(144)
        assertThat(data[0].toInt()).isEqualTo(1) // one signature
        assertThat(data[1].toInt()).isEqualTo(0) // padding
        assertThat(readU16LE(data, 2)).isEqualTo(48) // signature offset
        assertThat(readU16LE(data, 4)).isEqualTo(0xFFFF)
        assertThat(readU16LE(data, 6)).isEqualTo(16) // pubkey offset
        assertThat(readU16LE(data, 8)).isEqualTo(0xFFFF)
        assertThat(readU16LE(data, 10)).isEqualTo(112) // message offset
        assertThat(readU16LE(data, 12)).isEqualTo(32) // message size
        assertThat(readU16LE(data, 14)).isEqualTo(0xFFFF)
        assertThat(data.copyOfRange(16, 48)).isEqualTo(signer)
        assertThat(data.copyOfRange(48, 112)).isEqualTo(signature)
        assertThat(data.copyOfRange(112, 144)).isEqualTo(message)
    }

    @Test
    fun `withdraw instruction encodes discriminator and borsh request`() {
        val programId = ByteArray(32) { 0x01 }
        val salt = ByteArray(32) { 0x44 }
        val instruction = SolanaInstructions.withdrawSingleSignerCollateralAsset(
            programId = programId,
            owner = ByteArray(32) { 0x02 },
            coordinator = ByteArray(32) { 0x03 },
            collateral = ByteArray(32) { 0x04 },
            collateralAuthority = ByteArray(32) { 0x05 },
            destination = ByteArray(32) { 0x06 },
            asset = ByteArray(32) { 0x07 },
            collateralTokenAccount = ByteArray(32) { 0x08 },
            destinationTokenAccount = ByteArray(32) { 0x09 },
            tokenProgramId = Base58.decode("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"),
            amountBaseUnits = BigInteger.valueOf(10_000),
            expiresAtEpochSeconds = 1_784_912_091L,
            coordinatorSignatureSalt = salt
        )

        assertThat(instruction.programId).isEqualTo(programId)

        val data = instruction.data
        assertThat(data.size).isEqualTo(56)
        // Anchor discriminator for withdraw_single_signer_collateral_asset (from the IDL).
        assertThat(data.copyOfRange(0, 8))
            .isEqualTo(byteArrayOf(13, 25, 64, 83, 111, 184.toByte(), 70, 241.toByte()))
        assertThat(readU64LE(data, 8)).isEqualTo(BigInteger.valueOf(10_000))
        assertThat(readU64LE(data, 16)).isEqualTo(BigInteger.valueOf(1_784_912_091L))
        assertThat(data.copyOfRange(24, 56)).isEqualTo(salt)

        // Account order is the program's ABI — owner signs and pays, sysvars close the list.
        assertThat(instruction.accounts).hasSize(11)
        assertThat(instruction.accounts[0].isSigner).isTrue()
        assertThat(instruction.accounts[0].isWritable).isTrue()
        assertThat(instruction.accounts[9].pubkey)
            .isEqualTo(Base58.decode("Sysvar1nstructions1111111111111111111111111"))
        assertThat(instruction.accounts[10].pubkey)
            .isEqualTo(Base58.decode("11111111111111111111111111111111"))
    }

    private fun readU16LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU64LE(data: ByteArray, offset: Int): BigInteger {
        var value = BigInteger.ZERO
        for (i in 0 until 8) {
            value = value.or(
                BigInteger.valueOf(data[offset + i].toLong() and 0xFF).shiftLeft(8 * i)
            )
        }
        return value
    }
}

class SolanaCollateralAccountsTest {

    @Test
    fun `parses a single-signer collateral account`() {
        // Field values from the real devnet account at 2h5mCX… (name "CollateralSolana").
        val coordinator = Base58.decode("FF3tTZ91aRu7XdGc4XK6V7MkDyStJ5ZY2fG4ZKLqFgL5")
        val owner = Base58.decode("3mhBCgFYhFCAV7LFARCcLuLsQLsyErTRc2axkGJrf8UG")
        val name = "CollateralSolana".toByteArray()
        val data = byteArrayOf(19, 45, 99, 29, 196.toByte(), 50, 228.toByte(), 117) + // discriminator
            ByteArray(32) { 0x0A } + // id
            coordinator +
            byteArrayOf(255.toByte()) + // authority bump
            byteArrayOf(name.size.toByte(), 0, 0, 0) + name + // borsh string
            byteArrayOf(7, 0, 0, 0) + // nonce = 7, u32 LE
            owner

        val parsed = SolanaCollateralAccounts.parseSingleSigner(data)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.coordinator).isEqualTo(coordinator)
        assertThat(parsed.nonce).isEqualTo(7L)
        assertThat(parsed.owner).isEqualTo(owner)
    }

    @Test
    fun `rejects an account with a different discriminator`() {
        val data = ByteArray(120) { 0x01 }
        assertThat(SolanaCollateralAccounts.parseSingleSigner(data)).isNull()
    }

    @Test
    fun `parses coordinator executors after variable-length publishers`() {
        val publisher = ByteArray(32) { 0x0B }
        val executor1 = ByteArray(32) { 0x0C }
        val executor2 = ByteArray(32) { 0x0D }
        val data = ByteArray(8) + // discriminator (not checked by the executors parser)
            ByteArray(32) + // id
            byteArrayOf(254.toByte()) + // bump
            ByteArray(32) + // owner
            ByteArray(32) + // collateral_creator
            ByteArray(32) + // treasury
            byteArrayOf(1, 0, 0, 0) + publisher +
            byteArrayOf(2, 0, 0, 0) + executor1 + executor2

        val executors = SolanaCollateralAccounts.parseCoordinatorExecutors(data)

        assertThat(executors).hasSize(2)
        assertThat(executors[0]).isEqualTo(executor1)
        assertThat(executors[1]).isEqualTo(executor2)
    }
}
