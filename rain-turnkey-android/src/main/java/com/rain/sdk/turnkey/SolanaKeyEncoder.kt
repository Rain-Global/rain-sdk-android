package com.rain.sdk.turnkey

import com.rain.sdk.internal.solana.Base58
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

/**
 * Builds the keypair string Solana wallets import from an exported ed25519 seed.
 *
 * The layout is the 64-byte `seed || publicKey` keypair in plain Base58, the form Phantom and
 * web3.js `Keypair.fromSecretKey` read. It is not Base58Check. Solana wallets reject the four
 * checksum bytes, and the vendor's checksum helper links a bitcoinj class that the project's
 * bitcoinj floor no longer ships, so calling it throws `NoClassDefFoundError` at runtime. The
 * public key comes from Bouncy Castle's ed25519, the derivation the vendor uses for its own
 * Solana export.
 */
internal object SolanaKeyEncoder {

    /** The ed25519 public key for a 32-byte [seed]. Any other length is rejected without echoing the input. */
    fun publicKey(seed: ByteArray): ByteArray {
        require(seed.size == KEY_LENGTH) { "ed25519 seed must be $KEY_LENGTH bytes, found ${seed.size}" }
        return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    }

    /** Plain Base58 of `seed || publicKey`, each 32 bytes. */
    fun keypairBase58(seed: ByteArray, publicKey: ByteArray): String {
        require(seed.size == KEY_LENGTH) { "ed25519 seed must be $KEY_LENGTH bytes, found ${seed.size}" }
        require(publicKey.size == KEY_LENGTH) {
            "ed25519 public key must be $KEY_LENGTH bytes, found ${publicKey.size}"
        }
        return Base58.encode(seed + publicKey)
    }
}
