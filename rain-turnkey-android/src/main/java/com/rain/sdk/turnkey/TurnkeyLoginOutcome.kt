package com.rain.sdk.turnkey

/**
 * What `confirmLoginCode` led to: [SignedIn], the session live and selected, or
 * [SecondCodeRequired], Rain's answer for an account it protects with two codes, which has sent
 * nothing yet: `sendSecondLoginCode` sends the second code to the account's other contact and
 * `confirmSecondLoginCode` completes the login. Internal API ([InternalRainTurnkeyApi]): hosts see
 * it through the Rain wallet provider, `RainProvider` in `rain-wallet-android`.
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
