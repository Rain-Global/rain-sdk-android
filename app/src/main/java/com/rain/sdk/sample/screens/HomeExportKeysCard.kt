package com.rain.sdk.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainButtonStyle
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainLabel
import com.rain.sdk.sample.ui.RainNote
import com.rain.sdk.sample.ui.RainPanel
import com.rain.sdk.sample.ui.RainRow
import com.rain.sdk.sample.ui.RainStrong
import com.rain.sdk.sample.ui.theme.RainTheme
import com.rain.sdk.sample.ui.theme.RainType

/*
 * Home's "Export keys" card, shown for a signed-in Rain Wallet session: reveal the recovery phrase or
 * one private key at a time, copy it with the sensitive clipboard flag, hide it. While a value is
 * revealed the window carries FLAG_SECURE, and the value is hidden again when the card leaves
 * composition or the activity stops.
 */

private val REVEALED_GAP = 8.dp

/**
 * Callbacks the export card raises, so it can be previewed without a view model. [onHide] is the
 * button, which also clears a clipboard the sample loaded; [onLeave] fires when the card leaves
 * composition or the activity stops, and leaves the clipboard alone so a copied value can be
 * pasted into another wallet app.
 */
internal class ExportKeysActions(
    val onReveal: (RainWalletExportKind) -> Unit,
    val onCopy: () -> Unit,
    val onHide: () -> Unit,
    val onLeave: () -> Unit,
) {
    companion object {
        /** Inert callbacks for previews. */
        val None = ExportKeysActions(onReveal = {}, onCopy = {}, onHide = {}, onLeave = {})
    }
}

@Composable
internal fun ExportKeysCard(state: HomeUiState, actions: ExportKeysActions) {
    val revealed = state.rainWalletRevealedSecret
    val inFlight = state.rainWalletExportInFlight

    SecureWindowWhile(active = revealed != null)
    HideOnLeave(actions.onLeave)

    RainCard {
        CardTitle("Export keys", "Decrypted on this device")
        RainNote(title = "Handle with care", body = "Anyone holding these controls the wallet.")
        RainWalletExportKind.entries.forEach { kind ->
            RainRow {
                RainStrong(kind.label, Modifier.weight(1f))
                RainButton(
                    text = "Reveal",
                    onClick = { actions.onReveal(kind) },
                    style = RainButtonStyle.Secondary,
                    enabled = inFlight == null,
                    loading = inFlight == kind,
                )
            }
        }
        if (revealed != null) {
            Column(verticalArrangement = Arrangement.spacedBy(REVEALED_GAP)) {
                RainLabel(revealed.kind.label)
                // No SelectionContainer: the flagged Copy button is the only copy path. Password
                // semantics make a screen reader mask the characters unless the user opted in.
                RainPanel { Text(revealed.value, style = RainType.Body, modifier = Modifier.semantics { password() }) }
                RainRow {
                    RainButton(
                        text = "Copy",
                        onClick = actions.onCopy,
                        modifier = Modifier.weight(1f),
                        style = RainButtonStyle.Secondary,
                    )
                    RainButton(
                        text = "Hide",
                        onClick = actions.onHide,
                        modifier = Modifier.weight(1f),
                        style = RainButtonStyle.Ghost,
                    )
                }
            }
        }
    }
}

/** Hides the value when the card leaves composition or the activity stops: Home, recents, the lock screen. */
@Composable
private fun HideOnLeave(onHide: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, onHide) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) onHide() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onHide()
        }
    }
}

// ---------- previews ----------

private val previewExportState = HomeUiState(mode = WalletMode.RainWallet, rainWalletSessionActive = true)

@Composable
private fun ExportKeysCardPreview(state: HomeUiState) {
    RainTheme {
        ExportKeysCard(state = state, actions = ExportKeysActions.None)
    }
}

@Preview(name = "Rain Wallet · export, idle", showBackground = true)
@Composable
private fun ExportKeysIdlePreview() {
    ExportKeysCardPreview(previewExportState)
}

@Preview(name = "Rain Wallet · export, revealing", showBackground = true)
@Composable
private fun ExportKeysRevealingPreview() {
    ExportKeysCardPreview(previewExportState.copy(rainWalletExportInFlight = RainWalletExportKind.SolanaKey))
}

@Preview(name = "Rain Wallet · export, revealed", showBackground = true)
@Composable
private fun ExportKeysRevealedPreview() {
    // Placeholder tokens, never BIP-39 words, so a capture of this preview cannot read as a real phrase.
    ExportKeysCardPreview(
        previewExportState.copy(
            rainWalletRevealedSecret = RevealedSecret(
                RainWalletExportKind.RecoveryPhrase,
                "word1 word2 word3 word4 word5 word6 word7 word8 word9 word10 word11 word12",
            ),
        ),
    )
}
