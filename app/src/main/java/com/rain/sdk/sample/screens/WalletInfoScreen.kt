package com.rain.sdk.sample.screens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.RainChain
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.R
import com.rain.sdk.sample.RainSession
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.ui.RainBackHeader
import com.rain.sdk.sample.ui.RainBadge
import com.rain.sdk.sample.ui.RainBadgeTone
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainButtonStyle
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainErrorPanel
import com.rain.sdk.sample.ui.RainMuted
import com.rain.sdk.sample.ui.RainPanel
import com.rain.sdk.sample.ui.RainRow
import com.rain.sdk.sample.ui.RainScreen
import com.rain.sdk.sample.ui.RainStrong
import com.rain.sdk.sample.ui.RainTitleBlock
import com.rain.sdk.sample.ui.theme.RainTheme

@Composable
fun WalletInfoScreen(
    innerPadding: PaddingValues,
    session: RainSession,
    rainClient: RainClient,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    viewModel: WalletInfoViewModel = viewModel(factory = WalletInfoViewModelFactory(session, rainClient))
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Re-fetch whenever the active chain changes so the screen shows that wallet's address.
    LaunchedEffect(selectedChain) {
        viewModel.fetchWalletInfo(selectedChain)
    }

    WalletInfoContent(
        innerPadding = innerPadding,
        state = state,
        selectedChain = selectedChain,
        onBack = onBack,
        onRetry = { viewModel.fetchWalletInfo(selectedChain) },
        onCopy = { label, address -> copyToClipboard(context, label, address, "Address copied") },
    )
}

/** Stateless body of [WalletInfoScreen], so previews can render every state without a view model. */
@Suppress("LongParameterList") // Slot-style Compose API: state plus one callback per user action.
@Composable
private fun WalletInfoContent(
    innerPadding: PaddingValues,
    state: WalletInfoUiState,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (label: String, address: String) -> Unit,
) {
    RainScreen(innerPadding) {
        RainBackHeader(onBack = onBack)
        RainTitleBlock(title = "Wallet & QR", subtitle = selectedChain.displayName)

        if (state.isLoading) {
            RainPanel { RainMuted("Loading wallet info…") }
        }

        state.errorText?.let { error ->
            RainErrorPanel(error)
            RainButton(
                text = "Retry",
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                style = RainButtonStyle.Secondary,
            )
        }

        // User wallet address (provider-agnostic: Portal, Rain Wallet or Privy).
        if (state.portalAddress.isNotEmpty()) {
            AddressCard(
                title = "Wallet address",
                subtitle = null,
                address = state.portalAddress,
                isValid = selectedChain.isValidAddress(state.portalAddress),
                qrBitmap = state.portalQrBitmap,
                onCopy = { onCopy("Wallet address", state.portalAddress) },
            )
        }

        // Where deposits go: the collateral contract (or its dedicated deposit address).
        if (state.collateralAddress.isNotEmpty()) {
            AddressCard(
                title = "Deposit address",
                subtitle = "Collateral contract",
                address = state.collateralAddress,
                isValid = selectedChain.isValidAddress(state.collateralAddress),
                qrBitmap = state.collateralQrBitmap,
                onCopy = { onCopy("Deposit address", state.collateralAddress) },
            )
        }
    }
}

@Suppress("LongParameterList") // Slot-style Compose API: every extra parameter is an optional knob.
@Composable
private fun AddressCard(
    title: String,
    subtitle: String?,
    address: String,
    isValid: Boolean,
    qrBitmap: Bitmap?,
    onCopy: () -> Unit,
) {
    RainCard {
        RainRow {
            Column(modifier = Modifier.weight(1f)) {
                RainStrong(title)
                if (subtitle != null) RainMuted(subtitle)
            }
            RainBadge(
                text = if (isValid) "Valid" else "Invalid",
                tone = if (isValid) RainBadgeTone.Success else RainBadgeTone.Danger,
            )
        }

        qrBitmap?.let { bitmap ->
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "$title QR code",
                    modifier = Modifier.size(200.dp),
                )
            }
        }

        RainMuted(
            text = address,
            modifier = Modifier.fillMaxWidth(),
            textAlign = if (qrBitmap != null) TextAlign.Center else TextAlign.Start,
        )

        RainButton(
            text = "Copy address",
            onClick = onCopy,
            modifier = Modifier.fillMaxWidth(),
            style = RainButtonStyle.Secondary,
            icon = R.drawable.ic_copy,
        )
    }
}

// region Previews

private const val PREVIEW_EVM_WALLET = "0x1234567890abcdef1234567890abcdef12345678"
private const val PREVIEW_EVM_COLLATERAL = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
private const val PREVIEW_SOLANA_WALLET = "7EcDhSYGxXyscszYEp35KHN8vvw3svAuLKTzXwCFLtV"
private const val PREVIEW_SOLANA_COLLATERAL = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"
private const val PREVIEW_ETHEREUM_SEPOLIA = 11155111
private const val PREVIEW_QR_CELLS = 21
private const val PREVIEW_QR_SCALE = 8
private const val PREVIEW_QR_DENSITY = 3

/**
 * Deterministic QR-looking bitmap so previews show the card's real layout. The pattern is
 * derived from [seed] so the two cards on a screen don't render identical squares.
 */
private fun previewQrBitmap(seed: String): Bitmap {
    val size = PREVIEW_QR_CELLS * PREVIEW_QR_SCALE
    val pixels = IntArray(size * size) { index ->
        val cellX = (index % size) / PREVIEW_QR_SCALE
        val cellY = (index / size) / PREVIEW_QR_SCALE
        val cellHash = (cellX + 1) * (cellY + 1) + seed.hashCode()
        if (cellHash % PREVIEW_QR_DENSITY == 0) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
private fun WalletInfoPreview(state: WalletInfoUiState, selectedChain: WalletChain = WalletChain.BASE_SEPOLIA) {
    RainTheme {
        WalletInfoContent(
            innerPadding = PaddingValues(),
            state = state,
            selectedChain = selectedChain,
            onBack = {},
            onRetry = {},
            onCopy = { _, _ -> },
        )
    }
}

@Preview(name = "Loading", showBackground = true)
@Composable
private fun WalletInfoLoadingPreview() {
    WalletInfoPreview(WalletInfoUiState(isLoading = true))
}

@Preview(name = "Error · no collateral contract", showBackground = true)
@Composable
private fun WalletInfoErrorPreview() {
    WalletInfoPreview(
        WalletInfoUiState(errorText = "No collateral contract on ${WalletChain.SOLANA.displayName}"),
        selectedChain = WalletChain.SOLANA,
    )
}

@Preview(name = "Error · collateral on other chains", showBackground = true)
@Composable
private fun WalletInfoCollateralElsewherePreview() {
    // The message the card builds when the account's contracts sit on other chains of the family:
    // one the picker offers (Base Sepolia) and one it does not (Ethereum Sepolia).
    WalletInfoPreview(
        WalletInfoUiState(
            errorText = noContractMessage(
                chain = WalletChain.EVM,
                collateralChainIds = listOf(RainChain.BASE_SEPOLIA, PREVIEW_ETHEREUM_SEPOLIA),
                offered = listOf(WalletChain.EVM, WalletChain.BASE_SEPOLIA, WalletChain.ARBITRUM_SEPOLIA, WalletChain.SOLANA),
            ),
        ),
        selectedChain = WalletChain.EVM,
    )
}

@Preview(name = "Loaded · EVM with QR codes", showBackground = true, heightDp = 1100)
@Composable
private fun WalletInfoLoadedEvmPreview() {
    val walletQr = remember { previewQrBitmap(PREVIEW_EVM_WALLET) }
    val collateralQr = remember { previewQrBitmap(PREVIEW_EVM_COLLATERAL) }
    WalletInfoPreview(
        WalletInfoUiState(
            portalAddress = PREVIEW_EVM_WALLET,
            portalQrBitmap = walletQr,
            collateralAddress = PREVIEW_EVM_COLLATERAL,
            collateralQrBitmap = collateralQr,
        ),
    )
}

@Preview(name = "Loaded · Solana, no QR yet", showBackground = true)
@Composable
private fun WalletInfoLoadedSolanaPreview() {
    WalletInfoPreview(
        WalletInfoUiState(
            portalAddress = PREVIEW_SOLANA_WALLET,
            collateralAddress = PREVIEW_SOLANA_COLLATERAL,
        ),
        selectedChain = WalletChain.SOLANA,
    )
}

@Preview(name = "Loaded · invalid address badge", showBackground = true)
@Composable
private fun WalletInfoInvalidAddressPreview() {
    WalletInfoPreview(
        // An EVM address shown while Solana is selected trips the client-side validity check.
        WalletInfoUiState(portalAddress = PREVIEW_EVM_WALLET),
        selectedChain = WalletChain.SOLANA,
    )
}

// endregion
