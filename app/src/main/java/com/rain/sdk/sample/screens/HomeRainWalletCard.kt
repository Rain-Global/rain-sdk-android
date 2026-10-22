package com.rain.sdk.sample.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.rain.sdk.sample.ContactChannel
import com.rain.sdk.sample.SessionStore
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainButtonStyle
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainDivider
import com.rain.sdk.sample.ui.RainField
import com.rain.sdk.sample.ui.RainLabel
import com.rain.sdk.sample.ui.RainMuted
import com.rain.sdk.sample.ui.theme.RainTheme

/*
 * The Rain Wallet card: a one-time code by email or SMS, or a passkey, while signed out; the
 * Initialize button, another passkey and the attach-contact step while signed in. The previews at
 * the end cover the card's states and each step on its own.
 */

/** The notices above the contact fields: a backend another tab configured, or a missing Api-Key. */
@Composable
private fun RainWalletNotices(state: HomeUiState) {
    // The Turnkey tab configured the shared backend this launch: a login here fails until a relaunch.
    state.sharedBackendNotice?.let { RainMuted(it) }
    // The code login mints its wallet auth token with the Api-Key on Home; without it the token minting fails.
    state.rainWalletAuthNotice?.let { RainMuted(it) }
}

@Composable
internal fun RainWalletCard(state: HomeUiState, actions: ProviderCardActions) {
    // The channel and the contact are frozen once a code is out, but the button stays live as
    // "Resend code": codes expire after 5 minutes and lock after 3 wrong attempts, and only a new
    // code gets the user past either.
    // The channel switch also locks while a send is in flight and while a session is live, so the
    // channel the code went out on, and the one the header names, cannot change underneath.
    // A passkey sheet in flight locks the code flow too, because the SDK serializes its
    // authentication calls and a second one would only queue behind the sheet.
    val codeSent = state.rainWalletOtpSent
    val passkeyBusy = state.rainWalletPasskeyInFlight != null
    RainCard {
        CardTitle("Rain Wallet", "One-time code by email or SMS, or a passkey")
        RainWalletNotices(state)
        ContactFields(
            state.rainWalletContactInput,
            actions.rainWalletContactActions,
            switchEnabled = !codeSent && !state.isLoading && !passkeyBusy && !state.rainWalletSessionActive,
            fieldEnabled = !codeSent,
        )
        RainButton(
            text = if (codeSent) "Resend code" else "Send code",
            onClick = actions.onSendRainWalletCode,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.rainWalletContact.isNotBlank() &&
                state.rainWalletAuthAvailable &&
                !state.isLoading &&
                !passkeyBusy &&
                !state.rainWalletSessionActive,
            loading = state.isLoading && !codeSent && !state.rainWalletSessionActive,
        )
        if (codeSent) {
            OneTimeCodeStep(
                code = state.rainWalletOtpCode,
                onCodeChanged = actions.onRainWalletOtpCodeChanged,
                sessionActive = state.rainWalletSessionActive,
                isLoading = state.isLoading,
                onVerify = actions.onVerifyRainWalletOtp,
                placeholder = state.rainWalletChannel.codePlaceholder,
            )
        }
        if (state.rainWalletSessionActive) {
            InitializeRainButton(
                isInitialized = state.isInitialized,
                isLoading = state.isLoading,
                onClick = actions.onInitializeRainWithRainWallet,
            )
            RainWalletAddPasskeyStep(state, actions)
            RainWalletAttachStep(state, actions)
        } else {
            RainWalletPasskeyButtons(state, actions)
        }
    }
}

/**
 * The two passkey entries while signed out. Both disable while anything is loading, while a passkey
 * call is in flight and where no Activity can present the sheet, which is a preview's case. The
 * sign-up also needs the Api-Key on Home, since it mints a wallet auth token; the sign-in does not.
 */
@Composable
private fun RainWalletPasskeyButtons(state: HomeUiState, actions: ProviderCardActions) {
    RainDivider()
    PasskeyButton("Sign in with passkey", RainWalletPasskeyAction.Login, state, actions.onLoginWithRainWalletPasskey)
    PasskeyButton(
        "Create wallet with passkey",
        RainWalletPasskeyAction.SignUp,
        state,
        actions.onSignUpWithRainWalletPasskey,
        needsAuthToken = true,
    )
    RainMuted("Creates a new account. Already have one? Sign in instead.")
}

/** Registers another passkey on the signed-in account; the next sign-in can use it. */
@Composable
private fun RainWalletAddPasskeyStep(state: HomeUiState, actions: ProviderCardActions) {
    RainDivider()
    PasskeyButton("Add a passkey to this account", RainWalletPasskeyAction.AddPasskey, state, actions.onAddRainWalletPasskey)
    RainMuted("One passkey per device is enough; each tap registers another.")
}

/**
 * One passkey entry: a secondary button that spins while its own [action] runs and disables while
 * anything loads, while any passkey call runs, where no Activity can present the sheet, and, when
 * the action mints a wallet auth token ([needsAuthToken]), while the Api-Key is missing on Home.
 */
@Composable
private fun PasskeyButton(
    text: String,
    action: RainWalletPasskeyAction,
    state: HomeUiState,
    onClick: () -> Unit,
    needsAuthToken: Boolean = false,
) {
    val inFlight = state.rainWalletPasskeyInFlight
    RainButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        style = RainButtonStyle.Secondary,
        enabled = !state.isLoading && inFlight == null && state.rainWalletActivityAvailable &&
            (!needsAuthToken || state.rainWalletAuthAvailable),
        loading = inFlight == action,
    )
}

/**
 * Attaches an email or phone to the signed-in account, so it can sign in by code too. The channel
 * and the contact freeze once a verification code is out, as on the login step; the button stays
 * live as a resend.
 */
@Composable
private fun RainWalletAttachStep(state: HomeUiState, actions: ProviderCardActions) {
    val codeSent = state.rainWalletAttachCodeSent
    val inFlight = state.rainWalletAttachInFlight
    RainDivider()
    RainLabel("Add a login contact")
    ContactFields(
        state.rainWalletAttachInput,
        actions.rainWalletAttachActions,
        switchEnabled = !codeSent && !inFlight,
        fieldEnabled = !codeSent,
    )
    RainButton(
        text = if (codeSent) "Resend verification code" else "Send verification code",
        onClick = actions.onSendRainWalletAttachCode,
        modifier = Modifier.fillMaxWidth(),
        style = RainButtonStyle.Secondary,
        enabled = state.rainWalletAttachContact.isNotBlank() && !inFlight && !state.isLoading,
        loading = inFlight && !codeSent,
    )
    if (codeSent) {
        RainField(
            label = "Verification code",
            value = state.rainWalletAttachCode,
            onValueChange = actions.onRainWalletAttachCodeChanged,
            placeholder = state.rainWalletAttachChannel.codePlaceholder,
            enabled = !inFlight,
            keyboardType = KeyboardType.Ascii,
        )
        RainButton(
            text = "Verify and attach",
            onClick = actions.onConfirmRainWalletAttach,
            modifier = Modifier.fillMaxWidth(),
            style = RainButtonStyle.Secondary,
            enabled = state.rainWalletAttachCode.isNotBlank() && !inFlight && !state.isLoading,
            loading = inFlight,
        )
    }
}

// region Previews

private const val PREVIEW_EMAIL = "dev@rain.xyz"
private const val PREVIEW_PHONE = "+15551234567"

/**
 * The Rain wallet with both contacts filled in and an Activity present, as on a device; each
 * preview copies the flags it needs.
 */
private val previewRainWalletState = HomeUiState(
    mode = WalletMode.RainWallet,
    rainApiKey = "sandbox-api-key",
    userId = "user-1",
    rainWalletEmail = PREVIEW_EMAIL,
    rainWalletPhone = PREVIEW_PHONE,
    rainWalletActivityAvailable = true,
)

/** A live passkey session: no code step on screen, the add-passkey and attach steps below Initialize. */
private val previewRainWalletPasskeyState = HomeUiState(
    mode = WalletMode.RainWallet,
    rainApiKey = "sandbox-api-key",
    userId = "user-1",
    rainWalletSessionActive = true,
    rainWalletPasskeySession = true,
    rainWalletActivityAvailable = true,
)

@Composable
private fun RainWalletCardPreview(state: HomeUiState) {
    RainTheme {
        RainWalletCard(state = state, actions = ProviderCardActions.None)
    }
}

/** One step on its own, inside a card, so each step composable has a preview of its own. */
@Composable
private fun RainWalletStepPreview(content: @Composable () -> Unit) {
    RainTheme {
        RainCard { content() }
    }
}

@Preview(name = "Rain Wallet · email, ready to send", showBackground = true)
@Composable
private fun RainWalletCardEmailPreview() {
    RainWalletCardPreview(previewRainWalletState)
}

@Preview(name = "Rain Wallet · phone, ready to send", showBackground = true)
@Composable
private fun RainWalletCardPhonePreview() {
    RainWalletCardPreview(previewRainWalletState.copy(rainWalletChannel = ContactChannel.Phone))
}

@Preview(name = "Rain Wallet · sending", showBackground = true)
@Composable
private fun RainWalletCardSendingPreview() {
    RainWalletCardPreview(previewRainWalletState.copy(isLoading = true))
}

@Preview(name = "Rain Wallet · other tab owns the backend", showBackground = true)
@Composable
private fun RainWalletCardSharedBackendPreview() {
    RainWalletCardPreview(previewRainWalletState.copy(backendOwner = SessionStore.Provider.Turnkey))
}

@Preview(name = "Rain Wallet · no Api-Key on Home, code and sign-up off", showBackground = true)
@Composable
private fun RainWalletCardNoApiKeyPreview() {
    RainWalletCardPreview(previewRainWalletState.copy(rainApiKey = "", userId = ""))
}

@Preview(name = "Rain Wallet · code sent by email", showBackground = true, heightDp = 700)
@Composable
private fun RainWalletCardEmailCodeSentPreview() {
    RainWalletCardPreview(previewRainWalletState.copy(rainWalletOtpSent = true, rainWalletOtpCode = "481902"))
}

@Preview(name = "Rain Wallet · code sent by SMS", showBackground = true, heightDp = 700)
@Composable
private fun RainWalletCardSmsCodeSentPreview() {
    RainWalletCardPreview(
        previewRainWalletState.copy(rainWalletChannel = ContactChannel.Phone, rainWalletOtpSent = true),
    )
}

@Preview(name = "Rain Wallet · session active", showBackground = true, heightDp = 780)
@Composable
private fun RainWalletCardSessionActivePreview() {
    RainWalletCardPreview(
        previewRainWalletState.copy(
            rainWalletOtpSent = true,
            rainWalletOtpCode = "481902",
            rainWalletSessionActive = true,
        ),
    )
}

@Preview(name = "Rain Wallet · Rain initialized", showBackground = true, heightDp = 780)
@Composable
private fun RainWalletCardInitializedPreview() {
    RainWalletCardPreview(
        previewRainWalletState.copy(
            rainWalletOtpSent = true,
            rainWalletOtpCode = "481902",
            rainWalletSessionActive = true,
            isInitialized = true,
        ),
    )
}

@Preview(name = "Rain Wallet · no Activity, passkey buttons disabled", showBackground = true, heightDp = 760)
@Composable
private fun RainWalletCardNoActivityPreview() {
    RainWalletCardPreview(previewRainWalletState.copy(rainWalletActivityAvailable = false))
}

@Preview(name = "Rain Wallet · passkey sign-in in flight", showBackground = true, heightDp = 760)
@Composable
private fun RainWalletCardPasskeyLoginInFlightPreview() {
    RainWalletCardPreview(
        previewRainWalletState.copy(rainWalletPasskeyInFlight = RainWalletPasskeyAction.Login),
    )
}

@Preview(name = "Rain Wallet · passkey session, no code", showBackground = true, heightDp = 1000)
@Composable
private fun RainWalletCardPasskeySessionPreview() {
    RainWalletCardPreview(previewRainWalletPasskeyState)
}

@Preview(name = "Rain Wallet · add passkey in flight", showBackground = true, heightDp = 1000)
@Composable
private fun RainWalletCardAddPasskeyInFlightPreview() {
    RainWalletCardPreview(previewRainWalletPasskeyState.copy(rainWalletPasskeyInFlight = RainWalletPasskeyAction.AddPasskey))
}

@Preview(name = "Rain Wallet · attach code sent by email", showBackground = true, heightDp = 1100)
@Composable
private fun RainWalletCardAttachCodeSentPreview() {
    RainWalletCardPreview(
        previewRainWalletPasskeyState.copy(
            rainWalletAttachEmail = PREVIEW_EMAIL,
            rainWalletAttachCodeSent = true,
            rainWalletAttachCode = "481902",
        ),
    )
}

@Preview(name = "Rain Wallet · attach in flight", showBackground = true, heightDp = 1100)
@Composable
private fun RainWalletCardAttachInFlightPreview() {
    RainWalletCardPreview(
        previewRainWalletPasskeyState.copy(
            rainWalletAttachEmail = PREVIEW_EMAIL,
            rainWalletAttachCodeSent = true,
            rainWalletAttachCode = "481902",
            rainWalletAttachInFlight = true,
        ),
    )
}

@Preview(name = "Rain Wallet · verifying the code", showBackground = true, heightDp = 700)
@Composable
private fun RainWalletCardVerifyingPreview() {
    RainWalletCardPreview(
        previewRainWalletState.copy(rainWalletOtpSent = true, rainWalletOtpCode = "481902", isLoading = true),
    )
}

@Preview(name = "Rain Wallet step · passkey buttons", showBackground = true)
@Composable
private fun RainWalletPasskeyButtonsPreview() {
    RainWalletStepPreview { RainWalletPasskeyButtons(previewRainWalletState, ProviderCardActions.None) }
}

@Preview(name = "Rain Wallet step · add a passkey", showBackground = true)
@Composable
private fun RainWalletAddPasskeyStepPreview() {
    RainWalletStepPreview { RainWalletAddPasskeyStep(previewRainWalletPasskeyState, ProviderCardActions.None) }
}

@Preview(name = "Rain Wallet step · attach a login contact, code sent", showBackground = true, heightDp = 640)
@Composable
private fun RainWalletAttachStepPreview() {
    RainWalletStepPreview {
        RainWalletAttachStep(
            previewRainWalletPasskeyState.copy(
                rainWalletAttachEmail = PREVIEW_EMAIL,
                rainWalletAttachCodeSent = true,
                rainWalletAttachCode = "481902",
            ),
            ProviderCardActions.None,
        )
    }
}

// endregion
