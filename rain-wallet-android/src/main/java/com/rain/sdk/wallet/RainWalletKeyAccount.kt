package com.rain.sdk.wallet

import com.rain.sdk.turnkey.TurnkeyKeyFamily

/**
 * Which of the wallet's keys to export. Every Rain wallet has one account per chain family, both
 * derived from the single wallet seed, so the phrase from [RainProvider.exportRecoveryPhrase]
 * restores both. Accounts may be added as chain families are added, so use an `else` branch rather
 * than an exhaustive `when`; for an enum the compiler cannot require it, so this is the contract in
 * prose. The output formats are documented on [RainProvider.exportPrivateKey].
 */
enum class RainWalletKeyAccount {
    /** The secp256k1 key behind the Ethereum account, returned as `0x` plus 64 lowercase hex characters. */
    ETHEREUM,

    /** The ed25519 key behind the Solana account, returned as the Base58 keypair string Solana wallets import. */
    SOLANA,
}

@JvmSynthetic
internal fun RainWalletKeyAccount.toBacking(): TurnkeyKeyFamily = when (this) {
    RainWalletKeyAccount.ETHEREUM -> TurnkeyKeyFamily.ETHEREUM
    RainWalletKeyAccount.SOLANA -> TurnkeyKeyFamily.SOLANA
}
