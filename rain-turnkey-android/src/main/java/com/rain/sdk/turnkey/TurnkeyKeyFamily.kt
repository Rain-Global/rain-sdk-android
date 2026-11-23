package com.rain.sdk.turnkey

/**
 * Which of a Turnkey wallet's keys [TurnkeyProvider.exportPrivateKey] returns. *Which wallet and
 * account* and *Formats* under *Key export* in `docs/TURNKEY_SUPPORT.md` say which account each
 * family's key comes from and the string it comes back as. Families may be added, so prefer an
 * `else` branch.
 */
enum class TurnkeyKeyFamily {
    /** The secp256k1 key behind the Ethereum account. */
    ETHEREUM,

    /** The ed25519 key behind the Solana account. */
    SOLANA,
}
