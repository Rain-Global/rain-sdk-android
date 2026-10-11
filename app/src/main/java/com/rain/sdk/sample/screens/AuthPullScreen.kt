package com.rain.sdk.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.SampleEnvironment
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.ui.RainAmount
import com.rain.sdk.sample.ui.RainBackHeader
import com.rain.sdk.sample.ui.RainBadgeTone
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainButtonStyle
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainDivider
import com.rain.sdk.sample.ui.RainErrorPanel
import com.rain.sdk.sample.ui.RainField
import com.rain.sdk.sample.ui.RainKeyValue
import com.rain.sdk.sample.ui.RainMuted
import com.rain.sdk.sample.ui.RainNote
import com.rain.sdk.sample.ui.RainRow
import com.rain.sdk.sample.ui.RainScreen
import com.rain.sdk.sample.ui.RainSpinner
import com.rain.sdk.sample.ui.RainStrong
import com.rain.sdk.sample.ui.RainTextAction
import com.rain.sdk.sample.ui.RainTitleBlock
import com.rain.sdk.sample.ui.RainToggle
import com.rain.sdk.sample.ui.theme.RainColors
import com.rain.sdk.sample.ui.theme.RainRadius
import com.rain.sdk.sample.ui.theme.RainTheme
import com.rain.sdk.sample.ui.theme.RainType

/**
 * Approves Rain's operator to spend USDC from this wallet, the wallet-side prerequisite for
 * Auth pull, and shows the resulting allowance.
 */
@Composable
fun AuthPullScreen(
    innerPadding: PaddingValues,
    rainClient: RainClient,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    viewModel: AuthPullViewModel = viewModel(factory = AuthPullViewModelFactory(rainClient))
) {
    val state by viewModel.state.collectAsState()

    // Operator and token are per-environment, so re-seed on a chain switch; the ViewModel
    // no-ops when the chain is unchanged.
    LaunchedEffect(selectedChain) { viewModel.onChainChanged(selectedChain) }

    AuthPullContent(
        innerPadding = innerPadding,
        state = state,
        selectedChain = selectedChain,
        isSupported = viewModel.supportsAuthPull(selectedChain),
        onBack = onBack,
        actions = AuthPullActions(
            onRefreshAllowance = { viewModel.refreshAllowance(selectedChain) },
            onUnlimitedChanged = viewModel::onUnlimitedChanged,
            onAmountChanged = viewModel::onAmountChanged,
            onEstimateFee = { viewModel.estimateFee(selectedChain) },
            onApprove = { viewModel.approve(selectedChain) },
            onRevoke = { viewModel.revoke(selectedChain) },
        ),
    )
}

/** Callbacks the Auth pull screen raises, so the stateless body can be previewed without a view model. */
private class AuthPullActions(
    val onRefreshAllowance: () -> Unit,
    val onUnlimitedChanged: (Boolean) -> Unit,
    val onAmountChanged: (String) -> Unit,
    val onEstimateFee: () -> Unit,
    val onApprove: () -> Unit,
    val onRevoke: () -> Unit,
) {
    companion object {
        /** Inert callbacks for previews. */
        val None = AuthPullActions(
            onRefreshAllowance = {},
            onUnlimitedChanged = {},
            onAmountChanged = {},
            onEstimateFee = {},
            onApprove = {},
            onRevoke = {},
        )
    }
}

/**
 * Stateless body of [AuthPullScreen]. [isSupported] is whether the client enforces Auth pull on
 * [selectedChain]; the confirmation dialog's open/closed state lives here since it is view-only.
 */
@Suppress("LongParameterList") // Slot-style Compose API: state, a support flag, and the callbacks.
@Composable
private fun AuthPullContent(
    innerPadding: PaddingValues,
    state: AuthPullUiState,
    selectedChain: WalletChain,
    isSupported: Boolean,
    onBack: () -> Unit,
    actions: AuthPullActions,
) {
    var pendingAction by remember { mutableStateOf<AuthPullAction?>(null) }

    val isProduction = SampleEnvironment.isProduction

    RainScreen(innerPadding) {
        RainBackHeader(onBack = onBack)
        RainTitleBlock(
            title = "Auth pull",
            subtitle = if (isProduction) {
                "Production. Approvals use real USDC and real gas."
            } else {
                "Sandbox. Approvals use testnet USDC and testnet gas."
            },
        )

        if (!isSupported) {
            RainNote(
                title = "Auth pull is not available on ${selectedChain.displayName}",
                body = if (isProduction) {
                    "Production Auth pull runs on Base and Arbitrum. Switch chains on the home screen."
                } else {
                    "Sandbox Auth pull runs on Base Sepolia and Arbitrum Sepolia. Switch chains on the home screen."
                },
            )
            return@RainScreen
        }

        // Current allowance
        RainCard {
            RainRow {
                RainStrong("Current allowance", Modifier.weight(1f))
                RainTextAction(
                    text = "Refresh",
                    onClick = actions.onRefreshAllowance,
                    enabled = !state.isLoadingAllowance && !state.isApproving,
                )
            }
            if (state.isLoadingAllowance) {
                RainSpinner(size = 24.dp)
            } else {
                val allowance = state.allowanceText
                if (allowance == null) {
                    Text("—", style = RainType.Title)
                } else {
                    RainAmount(value = allowance, unit = "USDC")
                }
            }
            RainMuted(
                when {
                    state.allowanceText == null -> "What Rain's operator may pull from this wallet."
                    state.isRevokedAllowance -> "Revoked. Rain's operator cannot pull from this wallet."
                    state.isUnlimitedAllowance -> "Rain's operator may pull any amount from this wallet."
                    else -> "What Rain's operator may still pull from this wallet."
                },
            )
        }

        // Approval form
        RainCard {
            RainStrong("Approve Rain operator")
            RainKeyValue(label = "USDC contract", value = state.tokenAddress)
            RainKeyValue(label = "Trusted Rain operator", value = state.operatorAddress)
            RainDivider()
            RainRow {
                Text("Unlimited approval", style = RainType.Body, modifier = Modifier.weight(1f))
                RainToggle(
                    checked = state.isUnlimited,
                    onCheckedChange = actions.onUnlimitedChanged,
                    enabled = !state.isApproving,
                )
            }
            if (!state.isUnlimited) {
                RainField(
                    label = "Amount (USDC)",
                    value = state.amount,
                    onValueChange = actions.onAmountChanged,
                    enabled = !state.isApproving,
                    helper = "Zero revokes the approval.",
                    keyboardType = KeyboardType.Decimal,
                )
            }
            state.estimatedFee?.let { fee ->
                RainMuted("Estimated network fee: $fee")
            }
        }

        state.errorText?.let { RainErrorPanel(it) }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RainButton(
                text = if (state.isApproving) "Approving" else "Approve operator",
                onClick = { pendingAction = AuthPullAction.Approve },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isApproving,
                loading = state.isApproving,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RainButton(
                    text = "Estimate fee",
                    onClick = actions.onEstimateFee,
                    modifier = Modifier.weight(1f),
                    style = RainButtonStyle.Secondary,
                    enabled = !state.isApproving,
                )
                RainButton(
                    text = "Revoke",
                    onClick = { pendingAction = AuthPullAction.Revoke },
                    modifier = Modifier.weight(1f),
                    style = RainButtonStyle.Secondary,
                    enabled = !state.isApproving,
                )
            }
        }

        state.txHash?.let { txHash ->
            TransactionResultCard(
                title = state.approvalStatus ?: "Approval submitted",
                hash = txHash,
                // A pending approval's statusId can be a Turnkey status or activity id, which no explorer resolves.
                explorerUrl = txHash.takeIf(WalletChain::isEvmTransactionHash)?.let(selectedChain::explorerTxUrl),
                explorerName = selectedChain.explorerName,
                badge = if (state.isApproving) "Pending" else "Submitted",
                badgeTone = if (state.isApproving) RainBadgeTone.Neutral else RainBadgeTone.Success,
                note = if (state.isApproving) {
                    "Waiting for a successful receipt and the exact onchain allowance."
                } else {
                    "The receipt and resulting allowance were verified onchain."
                },
            )
        }

        val fundingAsset = if (isProduction) "USDC" else "testnet USDC"
        RainNote(
            title = "How Auth pull works",
            body = "Fund this wallet with $fundingAsset and a little ${selectedChain.nativeSymbol} for gas, " +
                "then approve the operator. When a card authorization arrives, Rain pulls the full amount " +
                "into the user's collateral contract. The app does nothing further.",
        )
    }

    pendingAction?.let { action ->
        val isRevoke = action == AuthPullAction.Revoke
        val amountLabel = when {
            isRevoke -> "zero (revoke)"
            state.isUnlimited -> "unlimited, all current and future USDC until revoked"
            else -> "${state.amount} USDC"
        }
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            containerColor = RainColors.Surface,
            shape = RoundedCornerShape(RainRadius.Card),
            titleContentColor = RainColors.Ink,
            textContentColor = RainColors.TextMuted,
            title = {
                Text(
                    if (isRevoke) "Confirm revocation" else "Confirm Auth pull approval",
                    style = RainType.Strong,
                )
            },
            text = {
                Text(
                    "Environment: ${SampleEnvironment.displayName}\n" +
                        "Chain: ${selectedChain.displayName} (${selectedChain.chainId})\n" +
                        "Token: ${state.tokenAddress}\n" +
                        "Operator: ${state.operatorAddress}\n" +
                        "Allowance: $amountLabel",
                    style = RainType.BodyMuted,
                )
            },
            confirmButton = {
                RainButton(
                    text = if (isRevoke) "Revoke" else "Approve",
                    onClick = {
                        pendingAction = null
                        if (isRevoke) actions.onRevoke() else actions.onApprove()
                    },
                    height = 44.dp,
                )
            },
            dismissButton = {
                RainButton(
                    text = "Cancel",
                    onClick = { pendingAction = null },
                    style = RainButtonStyle.Ghost,
                )
            },
        )
    }
}

private enum class AuthPullAction { Approve, Revoke }

// region Previews

private const val PREVIEW_TX_HASH = "0x7d2f9b1c4e8a6d3f5b9c2e1a7f4d8b6c3e9a1f5d7b2c4e6a8f1d3b5c7e9a2f4c"

@Composable
private fun AuthPullPreview(
    state: AuthPullUiState,
    selectedChain: WalletChain = WalletChain.BASE_SEPOLIA,
    isSupported: Boolean = true,
) {
    RainTheme {
        AuthPullContent(
            innerPadding = PaddingValues(),
            state = state,
            selectedChain = selectedChain,
            isSupported = isSupported,
            onBack = {},
            actions = AuthPullActions.None,
        )
    }
}

@Preview(name = "Unsupported chain", showBackground = true)
@Composable
private fun AuthPullUnsupportedPreview() {
    AuthPullPreview(AuthPullUiState(), selectedChain = WalletChain.SOLANA, isSupported = false)
}

@Preview(name = "Allowance unknown · defaults", showBackground = true, heightDp = 1000)
@Composable
private fun AuthPullDefaultPreview() {
    AuthPullPreview(AuthPullUiState())
}

@Preview(name = "Loading allowance", showBackground = true, heightDp = 1000)
@Composable
private fun AuthPullLoadingAllowancePreview() {
    AuthPullPreview(AuthPullUiState(isLoadingAllowance = true))
}

@Preview(name = "Allowance set · fee estimated", showBackground = true, heightDp = 1000)
@Composable
private fun AuthPullAllowancePreview() {
    AuthPullPreview(AuthPullUiState(allowanceText = "250.00", estimatedFee = "0.000021 ETH"))
}

@Preview(name = "Unlimited · approving", showBackground = true, heightDp = 1200)
@Composable
private fun AuthPullApprovingPreview() {
    AuthPullPreview(
        AuthPullUiState(
            isUnlimited = true,
            allowanceText = "250.00",
            isApproving = true,
            txHash = PREVIEW_TX_HASH,
            approvalStatus = "Approval pending",
        ),
    )
}

@Preview(name = "Unlimited · approved", showBackground = true, heightDp = 1200)
@Composable
private fun AuthPullApprovedPreview() {
    AuthPullPreview(
        AuthPullUiState(
            isUnlimited = true,
            allowanceText = "Unlimited",
            isUnlimitedAllowance = true,
            txHash = PREVIEW_TX_HASH,
            approvalStatus = "Approval confirmed",
        ),
    )
}

@Preview(name = "Revoked", showBackground = true, heightDp = 1000)
@Composable
private fun AuthPullRevokedPreview() {
    AuthPullPreview(AuthPullUiState(allowanceText = "0.00", isRevokedAllowance = true))
}

@Preview(name = "Error", showBackground = true, heightDp = 1000)
@Composable
private fun AuthPullErrorPreview() {
    AuthPullPreview(AuthPullUiState(errorText = "User rejected the signature request"))
}

// endregion
