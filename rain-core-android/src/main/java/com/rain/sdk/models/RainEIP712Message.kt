package com.rain.sdk.models

import java.util.Locale

/**
 * The EIP-712 message a wallet signs to authorize a withdrawal, plus the salt bound into it.
 *
 * The salt is carried as raw bytes — [saltHex] renders the form the EIP-712 domain and the
 * `withdrawAsset` calldata use. Feed the same value straight back into
 * [com.rain.sdk.RainSdk.buildWithdrawTransactionData] as `walletSalt`; a re-generated salt would
 * not match the signature.
 */
class RainEIP712Message internal constructor(
    /** The serialized EIP-712 typed-data JSON to hand to the wallet for signing. */
    val message: String,
    salt: ByteArray
) {
    private val saltBytes = salt

    /** The 32-byte salt bound into [message]. Defensive copy — callers must not mutate it. */
    val salt: ByteArray get() = saltBytes.copyOf()

    /** [salt] as a `0x`-prefixed lowercase hex string. */
    val saltHex: String get() = saltBytes.joinToString(prefix = "0x", separator = "") {
        "%02x".format(Locale.ROOT, it)
    }
}
