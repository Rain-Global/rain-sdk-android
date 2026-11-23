package com.rain.sdk.turnkey

/**
 * Internal API ([InternalRainTurnkeyApi]): what `confirmLoginCode` led to, surfaced to hosts as
 * `RainWalletLoginOutcome`, whose KDoc has the cases; *Two-code login* in
 * rain-wallet-android/README.md has the rest.
 */
@InternalRainTurnkeyApi
sealed class TurnkeyLoginOutcome {
    data object SignedIn : TurnkeyLoginOutcome()

    /** The contact the second code goes to, masked by Rain. */
    data class SecondCodeRequired(val contact: TurnkeyMaskedContact) : TurnkeyLoginOutcome()
}

/**
 * The account's other contact, masked by Rain for display: its kind and the masked form, as Rain
 * sent it. Internal API ([InternalRainTurnkeyApi]).
 */
@InternalRainTurnkeyApi
data class TurnkeyMaskedContact(val type: LoginContactType, val masked: String)

/** The kind of contact a second login code goes to. Internal API ([InternalRainTurnkeyApi]). */
@InternalRainTurnkeyApi
enum class LoginContactType { EMAIL, PHONE }
