package com.rain.sdk.internal.solana

import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.constants.SolanaPrograms
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Program-derived address (PDA) math, and the associated-token-account derivation built on it.
 *
 * An SPL transfer moves tokens between *token accounts*, not wallets: each (wallet, mint) pair
 * has one canonical associated token account, derived from the seeds `[owner, tokenProgram,
 * mint]`. Deriving it locally is what lets the SDK address an account that does not exist yet.
 *
 * A PDA must have no private key, so derivation hashes the seeds with a candidate bump byte and
 * keeps the first result that does *not* land on the ed25519 curve, matching Solana's
 * `find_program_address`.
 */
@RainAdapterApi
object SolanaAddresses {
    /** Byte length of a Solana public key, the size every address and mint decodes to. */
    internal const val PUBLIC_KEY_LENGTH = 32
    private const val MAX_SEEDS = 16
    private const val MAX_SEED_LENGTH = 32

    /** Domain separator appended to the hash input, per Solana's PDA construction. */
    private val PDA_MARKER = "ProgramDerivedAddress".toByteArray(Charsets.US_ASCII)

    // ---------- Associated token accounts ----------

    /**
     * The associated token account holding [mint] for [owner] under [tokenProgramId].
     *
     * @param owner  wallet (system account) that owns the tokens, 32 bytes
     * @param mint   SPL mint, 32 bytes
     * @param tokenProgramId the program that owns the mint — [SolanaPrograms.TOKEN] or
     *                       [SolanaPrograms.TOKEN_2022]. It is a *seed*, so passing the wrong
     *                       one silently derives a different (unfunded) address.
     */
    fun associatedTokenAddress(
        owner: ByteArray,
        mint: ByteArray,
        tokenProgramId: ByteArray
    ): ByteArray {
        // A short seed still derives a valid-looking address, so sizes are checked explicitly.
        require(owner.size == PUBLIC_KEY_LENGTH) {
            "Invalid token account owner (expected 32 bytes, got ${owner.size})"
        }
        require(mint.size == PUBLIC_KEY_LENGTH) {
            "Invalid mint (expected 32 bytes, got ${mint.size})"
        }
        return findProgramAddress(
            seeds = listOf(owner, tokenProgramId, mint),
            programId = SolanaPrograms.ASSOCIATED_TOKEN
        ).address
    }

    // ---------- PDA derivation ----------

    /** A derived address together with the bump seed that produced it. */
    data class ProgramAddress(val address: ByteArray, val bump: Int)

    /**
     * Finds the canonical PDA for [seeds] under [programId]: the highest bump seed (255 down to 1)
     * whose hash is off the ed25519 curve.
     *
     * @throws IllegalArgumentException if no bump yields an off-curve address (cryptographically
     *         implausible), or if the seeds violate Solana's limits.
     */
    fun findProgramAddress(seeds: List<ByteArray>, programId: ByteArray): ProgramAddress {
        require(seeds.size < MAX_SEEDS) { "Too many PDA seeds: ${seeds.size}" }
        seeds.forEach {
            require(it.size <= MAX_SEED_LENGTH) { "PDA seed exceeds $MAX_SEED_LENGTH bytes: ${it.size}" }
        }
        require(programId.size == PUBLIC_KEY_LENGTH) {
            "Invalid program id (expected 32 bytes, got ${programId.size})"
        }

        for (bump in 255 downTo 1) {
            val candidate = createProgramAddress(seeds, byteArrayOf(bump.toByte()), programId)
            if (candidate != null) return ProgramAddress(candidate, bump)
        }
        throw IllegalArgumentException("Unable to find a program address for the given seeds")
    }

    /** `SHA-256(seeds || bump || programId || "ProgramDerivedAddress")`, or null if on-curve. */
    private fun createProgramAddress(
        seeds: List<ByteArray>,
        bump: ByteArray,
        programId: ByteArray
    ): ByteArray? {
        val digest = MessageDigest.getInstance("SHA-256")
        seeds.forEach { digest.update(it) }
        digest.update(bump)
        digest.update(programId)
        digest.update(PDA_MARKER)
        val hash = digest.digest()
        return if (isOnCurve(hash)) null else hash
    }

    // ---------- ed25519 curve membership ----------

    /** Field prime for Curve25519: 2^255 - 19. (`BigInteger.TWO` needs API 33; minSdk is lower.) */
    private val P = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))

    /** Edwards curve constant d = -121665/121666 (mod P). */
    private val D = BigInteger(
        "37095705934669439343138083508754565189542113879843219016388785533085940283555"
    )

    /** (P-1)/2 — Euler's criterion exponent: `w^((P-1)/2) == 1` iff w is a non-zero square. */
    private val LEGENDRE_EXPONENT = P.subtract(BigInteger.ONE).shiftRight(1)

    /**
     * Whether [point] decodes to a point on the ed25519 curve — i.e. whether it could be a real
     * public key (and so must *not* be used as a PDA).
     *
     * A compressed point encodes y in the low 255 bits; the point is on the curve exactly when
     * `x^2 = (y^2 - 1) / (d*y^2 + 1)` has a solution, tested with Euler's criterion since only
     * existence matters.
     */
    internal fun isOnCurve(point: ByteArray): Boolean {
        if (point.size != PUBLIC_KEY_LENGTH) return false

        val y = decodeY(point)
        val ySquared = y.multiply(y).mod(P)
        val numerator = ySquared.subtract(BigInteger.ONE).mod(P)
        val denominator = D.multiply(ySquared).add(BigInteger.ONE).mod(P)
        // No inverse exists, so no x satisfies the curve equation for this y.
        if (denominator.signum() == 0) return false

        val xSquared = numerator.multiply(denominator.modInverse(P)).mod(P)
        // x = 0 is a valid solution (the identity point), and 0 is not covered by the test below.
        if (xSquared.signum() == 0) return true
        return xSquared.modPow(LEGENDRE_EXPONENT, P) == BigInteger.ONE
    }

    /** Reads the little-endian y coordinate, dropping the top bit (which carries x's sign). */
    private fun decodeY(point: ByteArray): BigInteger {
        val bigEndian = ByteArray(PUBLIC_KEY_LENGTH)
        for (i in 0 until PUBLIC_KEY_LENGTH) {
            bigEndian[PUBLIC_KEY_LENGTH - 1 - i] = point[i]
        }
        bigEndian[0] = (bigEndian[0].toInt() and 0x7F).toByte()
        return BigInteger(1, bigEndian).mod(P)
    }
}
