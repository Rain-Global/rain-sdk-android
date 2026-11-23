package com.rain.sdk.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.sample.R
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.ui.RainBackHeader
import com.rain.sdk.sample.ui.RainBadge
import com.rain.sdk.sample.ui.RainBadgeTone
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainButtonStyle
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainDivider
import com.rain.sdk.sample.ui.RainErrorPanel
import com.rain.sdk.sample.ui.RainIconButton
import com.rain.sdk.sample.ui.RainLink
import com.rain.sdk.sample.ui.RainMuted
import com.rain.sdk.sample.ui.RainNote
import com.rain.sdk.sample.ui.RainPanel
import com.rain.sdk.sample.ui.RainRow
import com.rain.sdk.sample.ui.RainScreen
import com.rain.sdk.sample.ui.RainSpinner
import com.rain.sdk.sample.ui.RainStrong
import com.rain.sdk.sample.ui.RainTextAction
import com.rain.sdk.sample.ui.RainTitleBlock
import com.rain.sdk.sample.ui.theme.RainColors
import com.rain.sdk.sample.ui.theme.RainTheme
import com.rain.sdk.sample.ui.theme.RainType
import java.math.BigDecimal

@Composable
fun TransactionHistoryScreen(
    innerPadding: PaddingValues,
    rainClient: RainClient,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    viewModel: TransactionHistoryViewModel = viewModel(factory = TransactionHistoryViewModelFactory(rainClient)),
) {
    val state by viewModel.state.collectAsState()

    // Re-fetch whenever the active chain changes.
    LaunchedEffect(selectedChain) {
        viewModel.fetchTransactions(selectedChain)
    }

    TransactionHistoryContent(
        innerPadding = innerPadding,
        state = state,
        selectedChain = selectedChain,
        onBack = onBack,
        onRefresh = { viewModel.fetchTransactions(selectedChain) },
    )
}

/** Stateless body of [TransactionHistoryScreen], so previews can render every state without a view model. */
@Composable
private fun TransactionHistoryContent(
    innerPadding: PaddingValues,
    state: TransactionHistoryUiState,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current

    RainScreen(innerPadding) {
        RainBackHeader(onBack = onBack)
        RainTitleBlock(title = "History", subtitle = "Sent from this wallet on ${selectedChain.displayName}")

        state.errorText?.let { RainErrorPanel(it) }

        if (state.isLoading && state.transactions.isEmpty()) {
            RainPanel {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RainSpinner()
                    RainMuted("Loading transactions…")
                }
            }
        }

        // Empty state. The history is sourced from this wallet's send activities filtered to the
        // selected chain, so it shows only transactions sent through this wallet on this network
        // (no receives, nothing sent outside the provider). Empty is expected for a fresh wallet.
        if (!state.isLoading && state.transactions.isEmpty() && state.errorText == null) {
            RainNote(
                title = "No transactions on ${selectedChain.displayName}",
                body = "History lists transactions sent from this wallet on the selected network. Sends on " +
                    "other chains, or transfers received from someone else, won't appear here.",
            )
        }

        if (state.transactions.isNotEmpty()) {
            RainCard(gap = 0.dp, contentPadding = PaddingValues(horizontal = 24.dp)) {
                state.transactions.forEach { tx ->
                    TransactionRow(tx = tx, walletAddress = state.walletAddress, chain = selectedChain)
                    RainDivider()
                }
                val walletAddress = state.walletAddress
                if (walletAddress != null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        RainTextAction(
                            text = "View all on ${selectedChain.explorerName}",
                            onClick = { openUrl(context, selectedChain.explorerAddressUrl(walletAddress)) },
                            icon = R.drawable.ic_arrow_up_right,
                        )
                    }
                }
            }
        }

        RainButton(
            text = "Refresh",
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
            style = RainButtonStyle.Secondary,
            enabled = !state.isLoading,
            loading = state.isLoading,
        )
    }
}

/** Sent / Received / Self relative to the connected wallet, or null when the wallet is unknown. */
private fun transactionKind(tx: RainTransaction, walletAddress: String?): Pair<String, RainBadgeTone>? {
    val isSend = walletAddress?.let { tx.from.equals(it, ignoreCase = true) } ?: false
    val isReceive = walletAddress?.let { tx.to?.equals(it, ignoreCase = true) == true } ?: false
    return when {
        isSend && isReceive -> "Self" to RainBadgeTone.Outline
        isSend -> "Sent" to RainBadgeTone.Neutral
        isReceive -> "Received" to RainBadgeTone.Success
        else -> null
    }
}

/**
 * Value formatted like the Balances screen (clean decimal, no trailing zeros), or null for zero.
 * Only a transfer with no token address is denominated in the native symbol; a token transfer whose
 * symbol is unknown shows the bare amount, identified by the mint shown on the row.
 */
private fun formattedValue(tx: RainTransaction, chain: WalletChain): String? {
    val amount = tx.value?.let { formatPlain(it) }?.takeIf { it != "0" } ?: return null
    val unit = tx.asset ?: chain.nativeSymbol.takeIf { tx.tokenAddress == null }
    return listOfNotNull(amount, unit).joinToString(" ")
}

@Composable
private fun TransactionRow(tx: RainTransaction, walletAddress: String?, chain: WalletChain) {
    val context = LocalContext.current
    val kind = transactionKind(tx, walletAddress)
    val value = formattedValue(tx, chain)
    // Solana history rows carry the provider's status id, not an explorer-resolvable signature,
    // so the hash is shown plainly (no link, no explorer action) on Solana.
    val explorerLinkable = !chain.isSolana

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RainRow {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (kind != null) RainBadge(kind.first, tone = kind.second)
                if (explorerLinkable) {
                    RainLink(
                        text = shortHash(tx.hash),
                        onClick = { openUrl(context, chain.explorerTxUrl(tx.hash)) },
                        maxLines = 1,
                    )
                } else {
                    Text(shortHash(tx.hash), style = RainType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (value != null) RainStrong(value)
        }

        TransactionAddresses(tx)

        if (explorerLinkable) {
            RainTextAction(
                text = "View on ${chain.explorerName}",
                onClick = { openUrl(context, chain.explorerTxUrl(tx.hash)) },
                icon = R.drawable.ic_arrow_up_right,
            )
        }
    }
}

/** From → to, plus the token contract (copyable) for token transfers. */
@Composable
private fun TransactionAddresses(tx: RainTransaction) {
    val context = LocalContext.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RainMuted(shortAddress(tx.from))
        Icon(
            painter = painterResource(R.drawable.ic_arrow_right),
            contentDescription = "to",
            tint = RainColors.TextMuted,
            modifier = Modifier.size(16.dp),
        )
        RainMuted(shortAddress(tx.to ?: "—"))
    }

    tx.tokenAddress?.let { tokenAddress ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RainMuted("Token ${shortAddress(tokenAddress)}")
            RainIconButton(
                icon = R.drawable.ic_copy,
                contentDescription = "Copy token address",
                onClick = { copyToClipboard(context, "Token address", tokenAddress, "Token address copied") },
                iconSize = 16.dp,
            )
        }
    }
}

// region Previews

private const val PREVIEW_WALLET = "0x1234567890abcdef1234567890abcdef12345678"
private const val PREVIEW_COUNTERPARTY = "0x3cA8ac240F6ebeA8684b3E629A8e8C1f0E3bC0Ff"
private const val PREVIEW_SOLANA_WALLET = "7EcDhSYGxXyscszYEp35KHN8vvw3svAuLKTzXwCFLtV"

private val previewEvmTransactions = listOf(
    RainTransaction(
        hash = "0x7d2f9b1c4e8a6d3f5b9c2e1a7f4d8b6c3e9a1f5d7b2c4e6a8f1d3b5c7e9a2f4c",
        from = PREVIEW_WALLET,
        to = PREVIEW_COUNTERPARTY,
        value = BigDecimal("0.001"),
        asset = "ETH",
        chainId = WalletChain.BASE_SEPOLIA.chainId,
    ),
    RainTransaction(
        hash = "0xa41c3e9f2d7b5c8e1a4f6d3b9c2e7a5f8d1b4c6e3a9f2d5b7c1e4a8f6d3b9c2e",
        from = PREVIEW_WALLET,
        to = PREVIEW_COUNTERPARTY,
        value = BigDecimal("25"),
        asset = "USDC",
        tokenAddress = WalletChain.BASE_SEPOLIA.defaultTokenAddress,
        chainId = WalletChain.BASE_SEPOLIA.chainId,
    ),
    RainTransaction(
        hash = "0x5b9c2e1a7f4d8b6c3e9a1f5d7b2c4e6a8f1d3b5c7e9a2f4c7d2f9b1c4e8a6d3f",
        from = PREVIEW_COUNTERPARTY,
        to = PREVIEW_WALLET,
        value = BigDecimal("0.05"),
        asset = "ETH",
        chainId = WalletChain.BASE_SEPOLIA.chainId,
    ),
    // Self-transfer with a zero value: no amount is shown on the row.
    RainTransaction(
        hash = "0x1f5d7b2c4e6a8f1d3b5c7e9a2f4c7d2f9b1c4e8a6d3f5b9c2e1a7f4d8b6c3e9a",
        from = PREVIEW_WALLET,
        to = PREVIEW_WALLET,
        value = BigDecimal.ZERO,
        chainId = WalletChain.BASE_SEPOLIA.chainId,
    ),
)

private val previewSolanaTransactions = listOf(
    RainTransaction(
        hash = "5UfDuX7WXY2rjwKk6yZK9GAaKhWzJrV4qGZx3xwTeVWABcdefGhijkLmnoPqrsTuvwXyz1234567890abcdefgh",
        from = PREVIEW_SOLANA_WALLET,
        to = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin",
        value = BigDecimal("0.25"),
        asset = "SOL",
        chainId = WalletChain.SOLANA.chainId,
    ),
    // An unregistered SPL mint: no symbol, so the amount is bare and the mint identifies it.
    RainTransaction(
        hash = "3nRt8yGhJkLmNpQrStUvWxYz1234567890AbCdEfGhIjKlMnOpQrStUvWxYz1234567890AbCdEfGhIjKlMn",
        from = PREVIEW_SOLANA_WALLET,
        to = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin",
        value = BigDecimal("1000"),
        tokenAddress = "So11111111111111111111111111111111111111112",
        chainId = WalletChain.SOLANA.chainId,
    ),
)

@Composable
private fun TransactionHistoryPreview(
    state: TransactionHistoryUiState,
    selectedChain: WalletChain = WalletChain.BASE_SEPOLIA,
) {
    RainTheme {
        TransactionHistoryContent(
            innerPadding = PaddingValues(),
            state = state,
            selectedChain = selectedChain,
            onBack = {},
            onRefresh = {},
        )
    }
}

@Preview(name = "Loading", showBackground = true)
@Composable
private fun TransactionHistoryLoadingPreview() {
    TransactionHistoryPreview(TransactionHistoryUiState(isLoading = true))
}

@Preview(name = "Empty", showBackground = true)
@Composable
private fun TransactionHistoryEmptyPreview() {
    TransactionHistoryPreview(TransactionHistoryUiState(walletAddress = PREVIEW_WALLET))
}

@Preview(name = "Error", showBackground = true)
@Composable
private fun TransactionHistoryErrorPreview() {
    TransactionHistoryPreview(TransactionHistoryUiState(errorText = "429 Too Many Requests from the indexer"))
}

@Preview(name = "Loaded · EVM", showBackground = true, heightDp = 1100)
@Composable
private fun TransactionHistoryLoadedEvmPreview() {
    TransactionHistoryPreview(
        TransactionHistoryUiState(transactions = previewEvmTransactions, walletAddress = PREVIEW_WALLET),
    )
}

@Preview(name = "Loaded · Solana, plain hashes", showBackground = true)
@Composable
private fun TransactionHistoryLoadedSolanaPreview() {
    TransactionHistoryPreview(
        TransactionHistoryUiState(transactions = previewSolanaTransactions, walletAddress = PREVIEW_SOLANA_WALLET),
        selectedChain = WalletChain.SOLANA,
    )
}

@Preview(name = "Refreshing · rows kept", showBackground = true, heightDp = 1100)
@Composable
private fun TransactionHistoryRefreshingPreview() {
    TransactionHistoryPreview(
        TransactionHistoryUiState(
            transactions = previewEvmTransactions,
            walletAddress = PREVIEW_WALLET,
            isLoading = true,
        ),
    )
}

// endregion
