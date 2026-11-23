package com.rain.sdk.sample.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.ui.RainBackHeader
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainErrorPanel
import com.rain.sdk.sample.ui.RainField
import com.rain.sdk.sample.ui.RainScreen
import com.rain.sdk.sample.ui.RainSegmentedControl
import com.rain.sdk.sample.ui.RainTitleBlock
import com.rain.sdk.sample.ui.theme.RainTheme

@Composable
fun SendTokensScreen(
    innerPadding: PaddingValues,
    rainClient: RainClient,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    viewModel: SendTokensViewModel = viewModel(factory = SendTokensViewModelFactory(rainClient))
) {
    val state by viewModel.state.collectAsState()

    // Address defaults differ per chain (contract vs mint), so re-seed the form on a switch;
    // the ViewModel no-ops when the chain is unchanged.
    LaunchedEffect(selectedChain) { viewModel.onChainChanged(selectedChain) }

    SendTokensContent(
        innerPadding = innerPadding,
        state = state,
        selectedChain = selectedChain,
        onBack = onBack,
        actions = SendTokensActions(
            onSendModeChanged = viewModel::onSendModeChanged,
            onContractAddressChanged = viewModel::onContractAddressChanged,
            onRecipientChanged = viewModel::onRecipientChanged,
            onAmountChanged = viewModel::onAmountChanged,
            onSend = { isTokenSend ->
                if (isTokenSend) viewModel.sendTokenTransfer(selectedChain) else viewModel.sendNative(selectedChain)
            },
        ),
    )
}

/** Callbacks the send form raises, so the stateless body can be previewed without a view model. */
private class SendTokensActions(
    val onSendModeChanged: (isTokenSend: Boolean) -> Unit,
    val onContractAddressChanged: (String) -> Unit,
    val onRecipientChanged: (String) -> Unit,
    val onAmountChanged: (String) -> Unit,
    val onSend: (isTokenSend: Boolean) -> Unit,
) {
    companion object {
        /** Inert callbacks for previews. */
        val None = SendTokensActions(
            onSendModeChanged = {},
            onContractAddressChanged = {},
            onRecipientChanged = {},
            onAmountChanged = {},
            onSend = {},
        )
    }
}

/** Stateless body of [SendTokensScreen]. */
@Composable
private fun SendTokensContent(
    innerPadding: PaddingValues,
    state: SendTokensUiState,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    actions: SendTokensActions,
) {
    val isTokenSend = state.isTokenMode

    RainScreen(innerPadding) {
        RainBackHeader(onBack = onBack)
        RainTitleBlock(title = "Send tokens", subtitle = selectedChain.displayName)

        // Every chain supports both a native and a token transfer.
        RainSegmentedControl(
            options = listOf("Native (${selectedChain.nativeSymbol})", "${selectedChain.tokenStandard} token"),
            selectedIndex = if (isTokenSend) 1 else 0,
            onSelected = { actions.onSendModeChanged(it == 1) },
            enabled = !state.isSending,
        )

        RainCard {
            if (isTokenSend) {
                RainField(
                    label = selectedChain.tokenAddressLabel,
                    value = state.contractAddress,
                    onValueChange = actions.onContractAddressChanged,
                    enabled = !state.isSending,
                    helper = if (selectedChain.isSolana) {
                        "Decimals come from the mint. If the recipient has no account for this token, one is created " +
                            "in the send; its rent is the organization's when rent sponsorship is on, the sender's otherwise."
                    } else {
                        "Decimals are resolved automatically by the SDK."
                    },
                )
            }
            RainField(
                label = "Recipient address",
                value = state.recipientAddress,
                onValueChange = actions.onRecipientChanged,
                enabled = !state.isSending,
            )
            RainField(
                label = if (isTokenSend) "Amount (token units)" else "Amount (${selectedChain.nativeSymbol})",
                value = state.amount,
                onValueChange = actions.onAmountChanged,
                enabled = !state.isSending,
                keyboardType = KeyboardType.Decimal,
            )
        }

        state.errorText?.let { RainErrorPanel(it) }

        RainButton(
            text = when {
                state.isSending -> "Sending"
                isTokenSend -> "Send ${selectedChain.tokenStandard}"
                else -> "Send ${selectedChain.nativeSymbol}"
            },
            onClick = { actions.onSend(isTokenSend) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isSending,
            loading = state.isSending,
        )

        state.txHash?.let { txHash ->
            TransactionResultCard(
                title = "Transaction sent",
                hash = txHash,
                explorerUrl = selectedChain.explorerTxUrl(txHash),
                explorerName = selectedChain.explorerName,
            )
        }
    }
}

// region Previews

private const val PREVIEW_TX_HASH = "0x7d2f9b1c4e8a6d3f5b9c2e1a7f4d8b6c3e9a1f5d7b2c4e6a8f1d3b5c7e9a2f4c"

@Composable
private fun SendTokensPreview(state: SendTokensUiState, selectedChain: WalletChain = WalletChain.BASE_SEPOLIA) {
    RainTheme {
        SendTokensContent(
            innerPadding = PaddingValues(),
            state = state,
            selectedChain = selectedChain,
            onBack = {},
            actions = SendTokensActions.None,
        )
    }
}

@Preview(name = "Native · defaults", showBackground = true)
@Composable
private fun SendTokensNativePreview() {
    SendTokensPreview(SendTokensUiState())
}

@Preview(name = "Token · EVM", showBackground = true)
@Composable
private fun SendTokensTokenEvmPreview() {
    SendTokensPreview(SendTokensUiState(isTokenMode = true, amount = "10"))
}

@Preview(name = "Token · Solana mint", showBackground = true)
@Composable
private fun SendTokensTokenSolanaPreview() {
    SendTokensPreview(
        SendTokensUiState(
            isTokenMode = true,
            recipientAddress = "7EcDhSYGxXyscszYEp35KHN8vvw3svAuLKTzXwCFLtV",
            amount = "1.5",
            contractAddress = WalletChain.SOLANA.defaultTokenAddress,
        ),
        selectedChain = WalletChain.SOLANA,
    )
}

@Preview(name = "Sending", showBackground = true)
@Composable
private fun SendTokensSendingPreview() {
    SendTokensPreview(SendTokensUiState(isSending = true))
}

@Preview(name = "Error", showBackground = true)
@Composable
private fun SendTokensErrorPreview() {
    SendTokensPreview(SendTokensUiState(errorText = "Insufficient funds for gas * price + value"))
}

@Preview(name = "Sent", showBackground = true)
@Composable
private fun SendTokensSentPreview() {
    SendTokensPreview(SendTokensUiState(txHash = PREVIEW_TX_HASH))
}

// endregion
