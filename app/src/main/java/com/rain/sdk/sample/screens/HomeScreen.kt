package com.rain.sdk.sample.screens

import android.app.Application
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.sample.ContactChannel
import com.rain.sdk.sample.R
import com.rain.sdk.sample.RainSampleApp
import com.rain.sdk.sample.SampleEnvironment
import com.rain.sdk.sample.Screen
import com.rain.sdk.sample.SessionHealth
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.WalletSessionStatus
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainButtonStyle
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainDropdownField
import com.rain.sdk.sample.ui.RainFeatureTile
import com.rain.sdk.sample.ui.RainField
import com.rain.sdk.sample.ui.RainHomeHeader
import com.rain.sdk.sample.ui.RainLabel
import com.rain.sdk.sample.ui.RainMuted
import com.rain.sdk.sample.ui.RainPanel
import com.rain.sdk.sample.ui.RainRow
import com.rain.sdk.sample.ui.RainScreen
import com.rain.sdk.sample.ui.RainSegmentedControl
import com.rain.sdk.sample.ui.RainStatusDot
import com.rain.sdk.sample.ui.RainStrong
import com.rain.sdk.sample.ui.RainTitleBlock
import com.rain.sdk.sample.ui.theme.RainColors
import com.rain.sdk.sample.ui.theme.RainRadius
import com.rain.sdk.sample.ui.theme.RainTheme
import com.rain.sdk.sample.ui.theme.RainType

data class FeatureAction(
    @DrawableRes val icon: Int,
    val label: String,
    val screen: Screen,
)

private val featureActions = listOf(
    FeatureAction(R.drawable.ic_tile_wallet, "Wallet & QR", Screen.WalletInfo),
    FeatureAction(R.drawable.ic_tile_coin, "Balances", Screen.Balances),
    FeatureAction(R.drawable.ic_tile_transaction, "Send tokens", Screen.SendTokens),
    FeatureAction(R.drawable.ic_tile_bank, "Withdraw", Screen.CollateralWithdraw),
    FeatureAction(R.drawable.ic_tile_secure, "Auth pull", Screen.AuthPull),
    FeatureAction(R.drawable.ic_tile_time, "History", Screen.TransactionHistory),
)

/** Session details up to this length sit beside the headline; longer ones wrap below it. */
private const val INLINE_DETAIL_MAX_CHARS = 24

internal val WalletMode.displayName: String
    get() = when (this) {
        WalletMode.Portal -> "Portal MPC"
        WalletMode.RainWallet -> "Rain Wallet"
        WalletMode.Turnkey -> "Turnkey"
        WalletMode.Privy -> "Privy"
    }

/**
 * "Rain Wallet · dev@rain.xyz" once connected, "Rain Wallet · passkey" after a passkey login; the bare
 * provider name where there is no account.
 */
private fun HomeUiState.connectedSubtitle(): String {
    val account = when (mode) {
        WalletMode.RainWallet -> if (rainWalletPasskeySession) "passkey" else rainWalletContactInput.headline()
        WalletMode.Turnkey -> turnkeyContactInput.headline()
        WalletMode.Privy -> privyEmail
        WalletMode.Portal -> ""
    }.trim()
    return if (account.isBlank()) mode.displayName else "${mode.displayName} · $account"
}

@Composable
fun HomeScreen(
    innerPadding: PaddingValues,
    selectedChain: WalletChain,
    onChainSelected: (WalletChain) -> Unit,
    onNavigate: (Screen) -> Unit,
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(LocalContext.current.applicationContext as RainSampleApp),
    ),
) {
    val state by viewModel.state.collectAsState()
    // Resolved here, never inside HomeContent: a preview's LocalContext is not an Application.
    val application = LocalContext.current.applicationContext as Application

    // Built once per view model: these carriers have identity equality, so rebuilding them on
    // every recomposition would stop the cards below from ever skipping.
    val actions = remember(viewModel) {
        HomeActions(
            onRainApiKeyChanged = viewModel::onRainApiKeyChanged,
            onUserIdChanged = viewModel::onUserIdChanged,
            onModeChanged = viewModel::onModeChanged,
            onRefreshSession = viewModel::refreshSession,
            onReplacementPortalTokenChanged = viewModel::onReplacementPortalTokenChanged,
            onUpdatePortalSessionToken = viewModel::updatePortalSessionToken,
            onClearSession = viewModel::clearSession,
        )
    }
    // The passkey sheet needs a foreground Activity. A preview has none, so the card reads the
    // view model's flag and disables the passkey buttons while it is false.
    val activity = LocalActivity.current
    LaunchedEffect(viewModel, activity) { viewModel.onRainWalletActivityAvailable(activity != null) }
    val providerActions = remember(viewModel, application, activity) {
        ProviderCardActions.bound(viewModel, application, activity)
    }

    val exportActions = remember(viewModel, application) {
        ExportKeysActions(
            onReveal = viewModel::revealRainWalletSecret,
            onCopy = { viewModel.copyRainWalletSecret(application) },
            onHide = { viewModel.hideRainWalletSecret(clearClipboard = true) },
            onLeave = { viewModel.hideRainWalletSecret() },
        )
    }

    HomeContent(
        innerPadding = innerPadding,
        state = state,
        selectedChain = selectedChain,
        onChainSelected = onChainSelected,
        onNavigate = onNavigate,
        actions = actions,
        providerActions = providerActions,
        exportActions = exportActions,
    )
}

/** Callbacks Home's own chrome raises, so [HomeContent] can be previewed without a view model. */
@Suppress("LongParameterList") // One callback per user action on the screen chrome.
internal class HomeActions(
    val onRainApiKeyChanged: (String) -> Unit,
    val onUserIdChanged: (String) -> Unit,
    val onModeChanged: (WalletMode) -> Unit,
    val onRefreshSession: () -> Unit,
    val onReplacementPortalTokenChanged: (String) -> Unit,
    val onUpdatePortalSessionToken: () -> Unit,
    val onClearSession: () -> Unit,
) {
    companion object {
        /** Inert callbacks for previews. */
        val None = HomeActions(
            onRainApiKeyChanged = {},
            onUserIdChanged = {},
            onModeChanged = {},
            onRefreshSession = {},
            onReplacementPortalTokenChanged = {},
            onUpdatePortalSessionToken = {},
            onClearSession = {},
        )
    }
}

/** Stateless body of [HomeScreen], so previews can render every state without a view model. */
@Suppress("LongParameterList") // Slot-style Compose API: state plus the screen's callbacks.
@Composable
private fun HomeContent(
    innerPadding: PaddingValues,
    state: HomeUiState,
    selectedChain: WalletChain,
    onChainSelected: (WalletChain) -> Unit,
    onNavigate: (Screen) -> Unit,
    actions: HomeActions,
    providerActions: ProviderCardActions,
    exportActions: ExportKeysActions,
) {
    val connected = state.isRecovered
    val sessionUsable = state.sessionStatus?.health != SessionHealth.Dead

    RainScreen(innerPadding) {
        RainHomeHeader(environmentLabel = SampleEnvironment.displayName)

        RainTitleBlock(
            title = "SDK sample",
            subtitle = if (connected) state.connectedSubtitle() else "Connect a wallet provider to start.",
        )

        if (!connected) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RainLabel("Wallet provider")
                // Locked while a provider is resolving or resolved, so the session card always
                // describes `mode`. Not gated on sessionStatus: a failed Initialize leaves that set
                // and would lock the other providers until process death.
                RainSegmentedControl(
                    options = WalletMode.entries.map { it.displayName },
                    selectedIndex = state.mode.ordinal,
                    onSelected = { actions.onModeChanged(WalletMode.entries[it]) },
                    enabled = state.providerPickerEnabled,
                )
            }

            // Rain API credentials are independent of the wallet provider: they feed the demo's own
            // Rain API client (contracts and withdrawal signatures; a shipped app makes those calls
            // from its backend), so they live in their own card shown for every provider.
            RainApiCard(state, actions)
            ProviderCard(state = state, actions = providerActions)
            // Available as soon as a session is live: a fresh login initializes Rain on its own; a
            // restored or reused session shows it before "Initialize Rain" as well as after.
            if (state.mode == WalletMode.RainWallet && state.rainWalletSessionActive) {
                ExportKeysCard(state = state, actions = exportActions)
            }
        }

        // Stays visible when the session is dead so the hidden feature grid is explained.
        state.sessionStatus?.let { status ->
            SessionCard(status = status, state = state, actions = actions)
        }

        if (connected && sessionUsable) {
            // Rain Wallet, Turnkey and Privy hold a Solana account; Portal is EVM-only. Force the selection
            // back to an EVM chain so Portal never reads/signs on Solana.
            LaunchedEffect(state.mode, selectedChain) {
                if (state.mode == WalletMode.Portal && selectedChain.isSolana) {
                    onChainSelected(WalletChain.EVM)
                }
            }
            ChainSection(
                mode = state.mode,
                selectedChain = selectedChain,
                onChainSelected = onChainSelected,
            )
            FeatureGrid(actions = featureActions, onActionClick = onNavigate)
        }

        if (connected) {
            // Connection details stay on the screen after connecting, as before the redesign. The
            // Rain API card is still live (it rebuilds the demo's client), so a wallet can be connected
            // first and the program key pasted afterwards; the provider card shows what was used and,
            // after a resume, still offers a fresh sign-in.
            RainLabel("Connection")
            RainApiCard(state, actions)
            ProviderCard(state = state, actions = providerActions)
            if (state.mode == WalletMode.RainWallet && state.rainWalletSessionActive) {
                ExportKeysCard(state = state, actions = exportActions)
            }
            // Disabled while a passkey sheet or an attach is in flight: the logout would queue behind
            // the ceremony on the SDK's mutex and then sign out the account the sheet just signed in.
            RainButton(
                text = "Clear session",
                onClick = actions.onClearSession,
                modifier = Modifier.fillMaxWidth(),
                style = RainButtonStyle.Ghost,
                enabled = state.rainWalletPasskeyInFlight == null && !state.rainWalletAttachInFlight,
            )
        }

        RainPanel { RainMuted("Status: ${state.statusText}") }
    }
}

@Composable
private fun RainApiCard(state: HomeUiState, actions: HomeActions) {
    RainCard {
        RainStrong("Rain API credentials")
        RainField(
            label = "Api-Key",
            value = state.rainApiKey,
            onValueChange = actions.onRainApiKeyChanged,
            placeholder = "Paste your program Api-Key",
        )
        RainField(
            label = "User ID",
            value = state.userId,
            onValueChange = actions.onUserIdChanged,
            placeholder = "Rain user ID",
        )
    }
}

/** `sessionState`, `refreshSession()` and, for Portal, `updateSessionToken()`. */
@Composable
private fun SessionCard(status: WalletSessionStatus, state: HomeUiState, actions: HomeActions) {
    // Short details sit beside the headline, as in the design; longer ones drop below it.
    val detail = status.detail
    val detailInline = detail != null && detail.length <= INLINE_DETAIL_MAX_CHARS
    RainCard {
        RainRow {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RainStatusDot(status.health.indicatorColor())
                RainStrong(status.label)
            }
            if (detail != null && detailInline) RainMuted(detail)
        }
        if (detail != null && !detailInline) RainMuted(detail)

        // Portal's refresh goes through onSessionTokenNeeded, which needs a replacement token.
        val canRefresh = !state.isLoading &&
            (state.mode != WalletMode.Portal || state.replacementPortalToken.isNotBlank())
        RainButton(
            text = "Refresh session",
            onClick = actions.onRefreshSession,
            modifier = Modifier.fillMaxWidth(),
            style = RainButtonStyle.Secondary,
            enabled = canRefresh,
        )

        if (state.mode == WalletMode.Portal) {
            RainMuted(
                "Update token installs the replacement now (updateSessionToken). Refresh and any " +
                    "rejected call take it through onSessionTokenNeeded.",
            )
            RainField(
                label = "Replacement session token",
                value = state.replacementPortalToken,
                onValueChange = actions.onReplacementPortalTokenChanged,
                placeholder = "Paste a new Portal session token",
            )
            RainButton(
                text = "Update token",
                onClick = actions.onUpdatePortalSessionToken,
                modifier = Modifier.fillMaxWidth(),
                style = RainButtonStyle.Secondary,
                enabled = state.replacementPortalToken.isNotBlank() && !state.isLoading,
            )
        }
    }
}

private fun SessionHealth.indicatorColor(): Color = when (this) {
    SessionHealth.Healthy -> RainColors.Success
    SessionHealth.Transitional -> RainColors.Warn
    SessionHealth.Dead -> RainColors.Danger
    SessionHealth.Unknown -> RainColors.TextSubtle
}

@Composable
private fun ChainSection(
    mode: WalletMode,
    selectedChain: WalletChain,
    onChainSelected: (WalletChain) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Solana for Rain Wallet, Turnkey and Privy; Portal is EVM-only.
    val chains = WalletChain.selectable.filter { mode != WalletMode.Portal || !it.isSolana }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RainLabel("Active wallet")
        Box(modifier = Modifier.fillMaxWidth()) {
            RainDropdownField(text = selectedChain.displayName, onClick = { expanded = true })
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(RainRadius.Small),
                containerColor = RainColors.Surface,
                border = BorderStroke(1.dp, RainColors.Border),
            ) {
                chains.forEach { chain ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                chain.displayName,
                                style = if (chain == selectedChain) RainType.Strong else RainType.Body,
                            )
                        },
                        onClick = {
                            onChainSelected(chain)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureGrid(
    actions: List<FeatureAction>,
    onActionClick: (Screen) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RainLabel("Features")
        actions.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { action ->
                    RainFeatureTile(
                        icon = action.icon,
                        label = action.label,
                        onClick = { onActionClick(action.screen) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// region Previews

private const val PREVIEW_HOME_TURNKEY_ORG = "a1b2c3d4-5e6f-7890-abcd-ef1234567890"
private const val PREVIEW_HOME_TURNKEY_PROXY = "9f8e7d6c-5b4a-3210-fedc-ba0987654321"
private const val PREVIEW_HOME_EMAIL = "dev@rain.xyz"
private const val PREVIEW_HOME_PHONE = "+15551234567"
private const val PREVIEW_HOME_API_KEY = "review_api_key_9f2c4b7a1d8e35604c287888"
private const val PREVIEW_HOME_USER_ID = "usr_7HqL2mNp9RtVx4Kd"
private const val PREVIEW_HOME_PORTAL_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.portal.session.token"

/** A connected Rain Wallet session: the state Home is in once a provider has logged in. */
private fun connectedRainWalletState(
    status: WalletSessionStatus = WalletSessionStatus(
        label = "Session healthy",
        health = SessionHealth.Healthy,
        detail = "Expires at 18:42:07, refreshed by the SDK",
    ),
) = HomeUiState(
    mode = WalletMode.RainWallet,
    rainApiKey = PREVIEW_HOME_API_KEY,
    userId = PREVIEW_HOME_USER_ID,
    rainWalletEmail = PREVIEW_HOME_EMAIL,
    rainWalletOtpSent = true,
    rainWalletSessionActive = true,
    isInitialized = true,
    isRecovered = true,
    statusText = "Session resumed",
    sessionStatus = status,
)

@Composable
private fun HomePreview(state: HomeUiState, selectedChain: WalletChain = WalletChain.BASE_SEPOLIA) {
    RainTheme {
        HomeContent(
            innerPadding = PaddingValues(),
            state = state,
            selectedChain = selectedChain,
            onChainSelected = {},
            onNavigate = {},
            actions = HomeActions.None,
            providerActions = ProviderCardActions.None,
            exportActions = ExportKeysActions.None,
        )
    }
}

@Preview(name = "Connect · Rain Wallet", showBackground = true, heightDp = 1250)
@Composable
private fun HomeConnectRainWalletPreview() {
    HomePreview(
        HomeUiState(
            mode = WalletMode.RainWallet,
            rainWalletEmail = PREVIEW_HOME_EMAIL,
        ),
    )
}

@Preview(name = "Connect · Rain Wallet by SMS", showBackground = true, heightDp = 1250)
@Composable
private fun HomeConnectRainWalletSmsPreview() {
    HomePreview(
        HomeUiState(
            mode = WalletMode.RainWallet,
            rainWalletChannel = ContactChannel.Phone,
            rainWalletPhone = PREVIEW_HOME_PHONE,
            rainWalletOtpSent = true,
        ),
    )
}

@Preview(name = "Connect · Turnkey, bring your own", showBackground = true, heightDp = 1250)
@Composable
private fun HomeConnectTurnkeyPreview() {
    HomePreview(
        HomeUiState(
            mode = WalletMode.Turnkey,
            turnkeyOrgId = PREVIEW_HOME_TURNKEY_ORG,
            turnkeyAuthProxyConfigId = PREVIEW_HOME_TURNKEY_PROXY,
            turnkeyEmail = PREVIEW_HOME_EMAIL,
        ),
    )
}

@Preview(name = "Connect · Portal", showBackground = true, heightDp = 1100)
@Composable
private fun HomeConnectPortalPreview() {
    HomePreview(HomeUiState(mode = WalletMode.Portal, sessionToken = PREVIEW_HOME_PORTAL_TOKEN))
}

@Preview(name = "Connect · Privy", showBackground = true, heightDp = 1250)
@Composable
private fun HomeConnectPrivyPreview() {
    HomePreview(
        HomeUiState(
            mode = WalletMode.Privy,
            privyAppId = "clz1a2b3c4d5e6f7g8h9i0jk",
            privyAppClientId = "client-WY1a2B3c4D5e6F7g8H9i0J",
            privyEmail = PREVIEW_HOME_EMAIL,
        ),
    )
}

@Preview(name = "Connect · sending a code", showBackground = true, heightDp = 1250)
@Composable
private fun HomeConnectLoadingPreview() {
    HomePreview(
        HomeUiState(
            mode = WalletMode.RainWallet,
            rainWalletEmail = PREVIEW_HOME_EMAIL,
            isLoading = true,
            statusText = "Sending a one-time code...",
        ),
    )
}

@Preview(name = "Connected · healthy", showBackground = true, heightDp = 1700)
@Composable
private fun HomeConnectedPreview() {
    HomePreview(connectedRainWalletState())
}

@Preview(name = "Connected · session expired", showBackground = true, heightDp = 1400)
@Composable
private fun HomeConnectedDeadSessionPreview() {
    // Health Dead hides the chain picker and the feature grid, leaving the session card to explain why.
    HomePreview(
        connectedRainWalletState(
            status = WalletSessionStatus("Session expired", SessionHealth.Dead, "Log in again"),
        ),
    )
}

@Preview(name = "Connected · restoring", showBackground = true, heightDp = 1400)
@Composable
private fun HomeConnectedRestoringPreview() {
    HomePreview(
        connectedRainWalletState(
            status = WalletSessionStatus("Restoring session", SessionHealth.Transitional),
        ),
    )
}

@Preview(name = "Connected · Portal, replacement token", showBackground = true, heightDp = 1700)
@Composable
private fun HomeConnectedPortalPreview() {
    // Portal is the only provider whose session card offers "Update token".
    HomePreview(
        HomeUiState(
            mode = WalletMode.Portal,
            sessionToken = PREVIEW_HOME_PORTAL_TOKEN,
            rainApiKey = PREVIEW_HOME_API_KEY,
            userId = PREVIEW_HOME_USER_ID,
            replacementPortalToken = "eyJhbGciOiJIUzI1NiJ9.replacement.token",
            isInitialized = true,
            isRecovered = true,
            statusText = "Session resumed",
            sessionStatus = WalletSessionStatus(
                label = "Session healthy",
                health = SessionHealth.Healthy,
                detail = "Last Portal call succeeded",
            ),
        ),
        selectedChain = WalletChain.EVM,
    )
}

@Preview(name = "Connected · Portal, refresh blocked", showBackground = true, heightDp = 1700)
@Composable
private fun HomeConnectedPortalNoReplacementPreview() {
    // Portal refreshes through onSessionTokenNeeded, so a blank replacement disables "Refresh session".
    HomePreview(
        HomeUiState(
            mode = WalletMode.Portal,
            sessionToken = PREVIEW_HOME_PORTAL_TOKEN,
            rainApiKey = PREVIEW_HOME_API_KEY,
            userId = PREVIEW_HOME_USER_ID,
            isInitialized = true,
            isRecovered = true,
            statusText = "Session resumed",
            sessionStatus = WalletSessionStatus(
                label = "Session healthy",
                health = SessionHealth.Healthy,
                detail = "Last Portal call succeeded",
            ),
        ),
        selectedChain = WalletChain.EVM,
    )
}

@Preview(name = "Connected · Portal, status unknown", showBackground = true, heightDp = 1700)
@Composable
private fun HomeConnectedPortalUnknownPreview() {
    // Unknown is the fourth status-dot colour; Portal reports it before any call has completed.
    HomePreview(
        HomeUiState(
            mode = WalletMode.Portal,
            sessionToken = PREVIEW_HOME_PORTAL_TOKEN,
            isInitialized = true,
            isRecovered = true,
            sessionStatus = WalletSessionStatus(
                label = "Session unknown",
                health = SessionHealth.Unknown,
                detail = "No Portal call has completed yet",
            ),
        ),
        selectedChain = WalletChain.EVM,
    )
}

@Preview(name = "Connected · Rain Wallet by SMS, masked number", showBackground = true, heightDp = 1700)
@Composable
private fun HomeConnectedRainWalletPhonePreview() {
    // The subtitle masks the number, because this header shows up in screenshots.
    HomePreview(
        connectedRainWalletState().copy(
            rainWalletChannel = ContactChannel.Phone,
            rainWalletPhone = PREVIEW_HOME_PHONE,
        ),
    )
}

@Preview(name = "Connected · Privy", showBackground = true, heightDp = 1700)
@Composable
private fun HomeConnectedPrivyPreview() {
    HomePreview(
        HomeUiState(
            mode = WalletMode.Privy,
            privyAppId = "clz1a2b3c4d5e6f7g8h9i0jk",
            privyAppClientId = "client-WY1a2B3c4D5e6F7g8H9i0J",
            privyEmail = PREVIEW_HOME_EMAIL,
            privyOtpSent = true,
            privySessionActive = true,
            isInitialized = true,
            isRecovered = true,
            statusText = "Session resumed",
            sessionStatus = WalletSessionStatus(
                label = "Session healthy",
                health = SessionHealth.Healthy,
                detail = "Privy refreshes the session itself",
            ),
        ),
    )
}

// endregion
