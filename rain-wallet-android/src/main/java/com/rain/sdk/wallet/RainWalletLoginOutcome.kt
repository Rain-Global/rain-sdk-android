package com.rain.sdk.wallet

import com.rain.sdk.turnkey.LoginContactType
import com.rain.sdk.turnkey.TurnkeyLoginOutcome
import com.rain.sdk.turnkey.TurnkeyMaskedContact

/**
 * What [RainProvider.confirmLoginCode] led to. [SignedIn]: the session is live and selected.
 * [SecondCodeRequired]: Rain protects this account with two codes and has sent nothing yet; call
 * [RainProvider.sendSecondLoginCode] to have the code sent to the account's other contact, shown
 * to the user as [SecondCodeRequired.contact] says, then [RainProvider.confirmSecondLoginCode].
 * A `when` over this hierarchy is exhaustive; adding a case is a breaking change that ships in a
 * major version.
 */
sealed class RainWalletLoginOutcome {
    data object SignedIn : RainWalletLoginOutcome()

    /** The contact the second code goes to, masked by Rain; nothing else about it reaches the device. */
    class SecondCodeRequired(val contact: RainWalletMaskedContact) : RainWalletLoginOutcome() {
        override fun equals(other: Any?): Boolean = other is SecondCodeRequired && other.contact == contact

        override fun hashCode(): Int = contact.hashCode()

        override fun toString(): String = "SecondCodeRequired(contact=$contact)"
    }
}

/**
 * The account's other contact as Rain masks it for display: its kind and the masked form (for
 * example `j•••@example.com` or `+•••• 4567`); show it as it is.
 */
class RainWalletMaskedContact(val type: RainWalletContactType, val masked: String) {
    override fun equals(other: Any?): Boolean = other is RainWalletMaskedContact && other.type == type && other.masked == masked

    override fun hashCode(): Int = type.hashCode() * HASH_MULTIPLIER + masked.hashCode()

    override fun toString(): String = "RainWalletMaskedContact(type=$type, masked=$masked)"
}

/** The kind of contact a second login code goes to. */
enum class RainWalletContactType { EMAIL, PHONE }

// File-private rather than a companion constant, which Kotlin would hoist onto the published class as a public field.
private const val HASH_MULTIPLIER = 31

@JvmSynthetic
internal fun TurnkeyLoginOutcome.toRainWallet(): RainWalletLoginOutcome = when (this) {
    is TurnkeyLoginOutcome.SignedIn -> RainWalletLoginOutcome.SignedIn
    is TurnkeyLoginOutcome.SecondCodeRequired -> RainWalletLoginOutcome.SecondCodeRequired(contact.toRainWallet())
}

@JvmSynthetic
internal fun TurnkeyMaskedContact.toRainWallet(): RainWalletMaskedContact = RainWalletMaskedContact(type.toRainWallet(), masked)

@JvmSynthetic
internal fun LoginContactType.toRainWallet(): RainWalletContactType = when (this) {
    LoginContactType.EMAIL -> RainWalletContactType.EMAIL
    LoginContactType.PHONE -> RainWalletContactType.PHONE
}
