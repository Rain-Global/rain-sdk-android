package com.rain.sdk.turnkey

/**
 * Where a managed-mode login code is delivered, the identity the account is keyed on, and the
 * contact an attach adds; the sending methods canonicalize it. Internal API
 * ([InternalRainTurnkeyApi]). Hosts use `RainWalletContact` in `rain-wallet-android`, whose KDoc
 * has the rules. `toString()` hides the value, which is personal data.
 */
@InternalRainTurnkeyApi
sealed interface LoginContact {
    /** The contact as given, not yet canonicalized. */
    val value: String

    /** An email address; `RainWalletContact.Email` has the rules. */
    data class Email(override val value: String) : LoginContact {
        override fun toString(): String = "LoginContact.Email(…)"
    }

    /** A phone number in E.164 form; `RainWalletContact.Phone` has the rules. */
    data class Phone(override val value: String) : LoginContact {
        override fun toString(): String = "LoginContact.Phone(…)"
    }
}
