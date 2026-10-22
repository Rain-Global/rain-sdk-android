package com.rain.sdk.wallet

import com.rain.sdk.turnkey.LoginContact

/**
 * Where a login code is delivered, the identity the account is keyed on, and the contact
 * [RainProvider.sendContactVerificationCode] attaches to the signed-in account as a further login
 * method. A first login signs the user up under this contact; a later login finds the account by
 * email for [Email] and by phone number for [Phone]. The same person arriving through the other
 * channel is a new account. [RainProvider.sendLoginCode] and [RainProvider.sendContactVerificationCode]
 * canonicalize the value once (trim and lowercase for an email, since the backend matches it
 * case-sensitively; trim, separator removal and an E.164 check for a phone number), and that
 * canonical string names the account: the instance [RainWalletConfig.authTokenProvider] receives
 * is rebuilt from it, so it is not `equals` to the one the host passed. `toString()` hides the
 * value, which is personal data.
 */
sealed interface RainWalletContact {
    /**
     * The contact as given; canonicalization happens in [RainProvider.sendLoginCode] and
     * [RainProvider.sendContactVerificationCode], not here.
     */
    val value: String

    /**
     * An email address. The sending methods lowercase it, so two spellings of one address name the
     * same account; `equals` still compares the text as given. A blank one makes either sending
     * method throw `RainError.InvalidConfig`.
     */
    class Email(override val value: String) : RainWalletContact {
        override fun equals(other: Any?): Boolean = other is Email && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "RainWalletContact.Email(…)"
    }

    /**
     * A phone number in E.164 form: `+`, the country code and the number, digits only, at most 15
     * of them, for example `+13214567890`. Spaces, dots, hyphens and parentheses are removed
     * before the check; anything else outside E.164 makes either sending method throw
     * `RainError.InvalidConfig`, and so does a parenthesised trunk zero (`+44 (0) 20 ...`), which
     * stripping would fold into a different number. A national number without its country code
     * is not converted.
     */
    class Phone(override val value: String) : RainWalletContact {
        override fun equals(other: Any?): Boolean = other is Phone && other.value == value

        override fun hashCode(): Int = value.hashCode() + PHONE_HASH_OFFSET

        override fun toString(): String = "RainWalletContact.Phone(…)"
    }
}

// Keeps an email and a phone number with the same text from hashing alike. File-private rather than
// a companion constant, which Kotlin would hoist onto the published interface as a public field.
private const val PHONE_HASH_OFFSET = 31

@JvmSynthetic
internal fun RainWalletContact.toBacking(): LoginContact = when (this) {
    is RainWalletContact.Email -> LoginContact.Email(value)
    is RainWalletContact.Phone -> LoginContact.Phone(value)
}

/** The backing's contact in the wallet's own terms, for the host's token supplier; the value as the backing holds it. */
@JvmSynthetic
internal fun LoginContact.toRainWallet(): RainWalletContact = when (this) {
    is LoginContact.Email -> RainWalletContact.Email(value)
    is LoginContact.Phone -> RainWalletContact.Phone(value)
}
