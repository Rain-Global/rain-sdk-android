package com.rain.sdk.internal.solana

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.constants.SolanaPrograms
import org.junit.Assert.assertThrows
import org.junit.Test
import org.sol4k.Constants
import org.sol4k.PublicKey
import org.sol4k.tweetnacl.TweetNaclFast
import java.util.Random

/**
 * Pins associated-token-account derivation, the address an SPL transfer sends to.
 *
 * Getting this wrong is silent and expensive — a mis-derived address is still a well-formed
 * account, so tokens would be sent to an account nobody controls. Every case is therefore
 * cross-checked against sol4k (a test-only dependency, see build.gradle.kts), which is the
 * reference implementation the Solana POC ran against devnet.
 */
class SolanaAddressesTest {

    /** Devnet USDC — a real mint under the original SPL Token program. */
    private val usdcDevnetMint = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    private val wallet = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"

    @Test
    fun `derives the associated token account for a real mint`() {
        val derived = associatedTokenAddress(wallet, usdcDevnetMint, SolanaPrograms.TOKEN)

        val expected = PublicKey
            .findProgramDerivedAddress(PublicKey(wallet), PublicKey(usdcDevnetMint))
            .publicKey
            .toBase58()
        assertThat(derived).isEqualTo(expected)
    }

    @Test
    fun `token-2022 mints derive a different address than the original token program`() {
        val classic = associatedTokenAddress(wallet, usdcDevnetMint, SolanaPrograms.TOKEN)
        val token2022 = associatedTokenAddress(wallet, usdcDevnetMint, SolanaPrograms.TOKEN_2022)

        // The owning program is a seed, so the two programs never share an account address.
        assertThat(token2022).isNotEqualTo(classic)
        assertThat(token2022).isEqualTo(
            PublicKey
                .findProgramDerivedAddress(
                    PublicKey(wallet),
                    PublicKey(usdcDevnetMint),
                    Constants.TOKEN_2022_PROGRAM_ID
                )
                .publicKey
                .toBase58()
        )
    }

    @Test
    fun `matches the reference derivation across random owner and mint pairs`() {
        // Fixed seed: the bump search takes a different number of iterations per input, so this
        // sweeps the branch that rejects on-curve candidates without being flaky.
        val random = Random(20260723L)
        repeat(250) {
            val owner = randomKey(random)
            val mint = randomKey(random)
            val tokenProgram =
                if (it % 2 == 0) SolanaPrograms.TOKEN else SolanaPrograms.TOKEN_2022
            val sol4kProgram =
                if (it % 2 == 0) Constants.TOKEN_PROGRAM_ID else Constants.TOKEN_2022_PROGRAM_ID

            val derived = SolanaAddresses.associatedTokenAddress(owner, mint, tokenProgram)
            val expected = PublicKey
                .findProgramDerivedAddress(PublicKey(owner), PublicKey(mint), sol4kProgram)
                .publicKey
                .bytes()

            assertThat(derived).isEqualTo(expected)
        }
    }

    @Test
    fun `reports the bump seed the canonical address was found with`() {
        val result = SolanaAddresses.findProgramAddress(
            seeds = listOf(
                Base58.decode(wallet),
                SolanaPrograms.TOKEN,
                Base58.decode(usdcDevnetMint)
            ),
            programId = SolanaPrograms.ASSOCIATED_TOKEN
        )

        val reference = PublicKey
            .findProgramDerivedAddress(PublicKey(wallet), PublicKey(usdcDevnetMint))
        assertThat(result.bump).isEqualTo(reference.nonce)
        assertThat(result.address).isEqualTo(reference.publicKey.bytes())
    }

    @Test
    fun `wallet addresses are on the curve and derived addresses are not`() {
        // A real wallet is an ed25519 public key by construction.
        assertThat(SolanaAddresses.isOnCurve(Base58.decode(wallet))).isTrue()
        assertThat(SolanaAddresses.isOnCurve(Base58.decode(usdcDevnetMint))).isTrue()

        // A PDA is only valid because it has no matching private key, i.e. is off the curve.
        val ata = SolanaAddresses.associatedTokenAddress(
            Base58.decode(wallet),
            Base58.decode(usdcDevnetMint),
            SolanaPrograms.TOKEN
        )
        assertThat(SolanaAddresses.isOnCurve(ata)).isFalse()
    }

    @Test
    fun `curve membership agrees with the reference implementation on random points`() {
        val random = Random(4711L)
        var onCurveSeen = 0
        repeat(500) {
            val point = ByteArray(32).also(random::nextBytes)
            val expected = TweetNaclFast.isOnCurve(point)
            if (expected) onCurveSeen++
            assertThat(SolanaAddresses.isOnCurve(point)).isEqualTo(expected)
        }
        // Roughly half of random 32-byte strings decode to a curve point; assert both branches
        // were actually exercised rather than trivially agreeing on "false".
        assertThat(onCurveSeen).isGreaterThan(50)
    }

    @Test
    fun `rejects malformed seeds and program ids`() {
        assertThrows(IllegalArgumentException::class.java) {
            SolanaAddresses.findProgramAddress(
                seeds = listOf(ByteArray(33)),
                programId = SolanaPrograms.ASSOCIATED_TOKEN
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SolanaAddresses.findProgramAddress(
                seeds = List(16) { ByteArray(1) },
                programId = SolanaPrograms.ASSOCIATED_TOKEN
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SolanaAddresses.findProgramAddress(seeds = emptyList(), programId = ByteArray(31))
        }
    }

    @Test
    fun `rejects points that are not 32 bytes`() {
        assertThat(SolanaAddresses.isOnCurve(ByteArray(31))).isFalse()
        assertThat(SolanaAddresses.isOnCurve(ByteArray(33))).isFalse()
    }

    // ---------- helpers ----------

    private fun associatedTokenAddress(
        owner: String,
        mint: String,
        tokenProgramId: ByteArray
    ): String = Base58.encode(
        SolanaAddresses.associatedTokenAddress(
            Base58.decode(owner),
            Base58.decode(mint),
            tokenProgramId
        )
    )

    private fun randomKey(random: Random): ByteArray = ByteArray(32).also(random::nextBytes)
}
