package com.rain.sdk.turnkey

import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import java.math.BigInteger
import java.util.Locale

/**
 * Derives the Ethereum address an exported secp256k1 private key controls, so the exporter can
 * refuse a key that does not belong to the account it was requested for. Bouncy Castle's curve
 * arithmetic and Keccak, the library the module already uses for the Solana derivation. The
 * address is the last 20 bytes of keccak256 over the uncompressed public point without its
 * prefix byte, as `0x` plus 40 lowercase hex characters.
 */
internal object EthereumKeyEncoder {
    private const val ADDRESS_BYTES = 20
    private const val KECCAK_BITS = 256
    private val curve = CustomNamedCurves.getByName("secp256k1")

    /** The address for a 32-byte [privateKey] inside the curve order. Other input is rejected without being echoed. */
    fun address(privateKey: ByteArray): String {
        require(privateKey.size == KEY_LENGTH) { "secp256k1 key must be $KEY_LENGTH bytes, found ${privateKey.size}" }
        val d = BigInteger(1, privateKey)
        require(d.signum() > 0 && d < curve.n) { "secp256k1 key is outside the curve order" }
        val point = FixedPointCombMultiplier().multiply(curve.g, d).normalize()
        val encoded = point.getEncoded(false) // 0x04 || X || Y
        val digest = KeccakDigest(KECCAK_BITS)
        digest.update(encoded, 1, encoded.size - 1)
        val hash = ByteArray(digest.digestSize)
        digest.doFinal(hash, 0)
        return "0x" + hash.copyOfRange(hash.size - ADDRESS_BYTES, hash.size).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
