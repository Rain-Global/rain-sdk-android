package com.rain.sdk.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.RainSession
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.ui.RainAmount
import com.rain.sdk.sample.ui.RainBackHeader
import com.rain.sdk.sample.ui.RainBadge
import com.rain.sdk.sample.ui.RainButton
import com.rain.sdk.sample.ui.RainButtonStyle
import com.rain.sdk.sample.ui.RainCard
import com.rain.sdk.sample.ui.RainDivider
import com.rain.sdk.sample.ui.RainErrorPanel
import com.rain.sdk.sample.ui.RainMuted
import com.rain.sdk.sample.ui.RainRow
import com.rain.sdk.sample.ui.RainScreen
import com.rain.sdk.sample.ui.RainStrong
import com.rain.sdk.sample.ui.RainTitleBlock
import com.rain.sdk.sample.ui.theme.RainTheme
import java.math.BigDecimal

@Suppress("LongParameterList") // Navigation entry point: the NavHost injects the session, chain, and callbacks.
@Composable
fun BalancesScreen(
    innerPadding: PaddingValues,
    session: RainSession,
    rainClient: RainClient,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    viewModel: BalancesViewModel = viewModel(factory = BalancesViewModelFactory(session, rainClient)),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(selectedChain) {
        viewModel.loadWalletAddresses(selectedChain)
    }

    BalancesContent(
        innerPadding = innerPadding,
        state = state,
        selectedChain = selectedChain,
        onBack = onBack,
        onFetchCollateral = { viewModel.fetchCollateralBalances(selectedChain) },
        onFetchWallet = { viewModel.fetchBalances(selectedChain) },
    )
}

/** Stateless body of [BalancesScreen], so previews can render every state without a view model. */
@Suppress("LongParameterList") // Slot-style Compose API: state plus one callback per user action.
@Composable
private fun BalancesContent(
    innerPadding: PaddingValues,
    state: BalancesUiState,
    selectedChain: WalletChain,
    onBack: () -> Unit,
    onFetchCollateral: () -> Unit,
    onFetchWallet: () -> Unit,
) {
    RainScreen(innerPadding) {
        RainBackHeader(onBack = onBack)
        RainTitleBlock(title = "Balances", subtitle = selectedChain.displayName)
        CollateralCard(state = state, onFetch = onFetchCollateral)
        WalletCard(state = state, chain = selectedChain, onFetch = onFetchWallet)
    }
}

/**
 * Collateral balances come from the Rain API, not on-chain: tokens are deposited into the user's
 * collateral contract, so the wallet itself won't hold them.
 */
@Composable
private fun CollateralCard(state: BalancesUiState, onFetch: () -> Unit) {
    val collateral = state.collateralBalances
    RainCard {
        RainRow {
            Column(modifier = Modifier.weight(1f)) {
                RainStrong("Collateral")
                RainMuted(
                    if (state.collateralWalletAddress.isNotEmpty()) {
                        shortAddress(state.collateralWalletAddress)
                    } else {
                        "Rain collateral contract"
                    },
                )
            }
            RainBadge("Rain API")
        }

        if (collateral.isNotEmpty()) {
            val primary = collateral.first()
            RainAmount(value = formatMoney(primary.balance), unit = primary.symbol)
            RainDivider()
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                collateral.forEach { token ->
                    val name = token.name.takeIf { it.isNotBlank() && it != token.symbol }
                    BalanceRow(
                        title = token.symbol,
                        subtitle = listOfNotNull(name, shortAddress(token.address)).joinToString(" · "),
                        value = formatMoney(token.balance),
                    )
                }
            }
        }
        RainMuted("Every token held in the collateral contract, read from the Rain API.")

        state.collateralError?.let { RainErrorPanel(it) }

        RainButton(
            text = if (collateral.isEmpty()) "Fetch" else "Refresh",
            onClick = onFetch,
            modifier = Modifier.fillMaxWidth(),
            style = RainButtonStyle.Secondary,
            enabled = !state.isCollateralLoading,
            loading = state.isCollateralLoading,
        )
    }
}

/** The wallet's own holdings, read on-chain: the native token plus every discovered token. */
@Composable
private fun WalletCard(state: BalancesUiState, chain: WalletChain, onFetch: () -> Unit) {
    val native = state.nativeBalance
    RainCard {
        RainRow {
            Column(modifier = Modifier.weight(1f)) {
                RainStrong("Wallet")
                RainMuted(
                    if (state.internalWalletAddress.isNotEmpty()) {
                        shortAddress(state.internalWalletAddress)
                    } else {
                        "Connected wallet"
                    },
                )
            }
            RainBadge("Onchain")
        }

        if (native != null) {
            // Stored as "0.4821 ETH": split the figure from its unit for the headline.
            val nativeValue = native.substringBeforeLast(' ')
            val nativeUnit = native.substringAfterLast(' ', missingDelimiterValue = chain.nativeSymbol)
            RainAmount(value = nativeValue, unit = nativeUnit)
            RainDivider()
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BalanceRow(title = nativeUnit, subtitle = "Native", value = nativeValue)
                state.walletTokenBalances.forEach { token ->
                    // The unit is always stated: an SPL mint has no on-chain symbol, so an
                    // unregistered token is named by its mint rather than a bare number.
                    val name = token.name?.takeIf { it.isNotBlank() && it != token.symbol }
                    BalanceRow(
                        title = token.displayUnit,
                        subtitle = listOfNotNull(name, shortAddress(token.address)).joinToString(" · "),
                        value = token.formattedBalance,
                    )
                }
            }
            if (state.walletTokenBalances.isEmpty()) {
                RainMuted("No ${chain.tokenStandard} tokens with a balance above zero.")
            }
        }

        RainMuted(
            "Native ${chain.nativeSymbol} plus every ${chain.tokenStandard} token with a balance above zero, " +
                "discovered automatically.",
        )

        state.errorMessage?.let { RainErrorPanel(it) }

        RainButton(
            text = if (native == null) "Fetch" else "Refresh",
            onClick = onFetch,
            modifier = Modifier.fillMaxWidth(),
            style = RainButtonStyle.Secondary,
            enabled = !state.isLoading,
            loading = state.isLoading,
        )
    }
}

@Composable
private fun BalanceRow(title: String, subtitle: String?, value: String) {
    RainRow {
        Column(modifier = Modifier.weight(1f)) {
            RainStrong(title)
            if (subtitle != null) RainMuted(subtitle)
        }
        RainStrong(value)
    }
}

// region Previews

private const val PREVIEW_EVM_WALLET = "0x1234567890abcdef1234567890abcdef12345678"
private const val PREVIEW_EVM_COLLATERAL = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
private const val PREVIEW_SOLANA_WALLET = "7EcDhSYGxXyscszYEp35KHN8vvw3svAuLKTzXwCFLtV"
private const val PREVIEW_SOLANA_COLLATERAL = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"

private val previewEvmCollateral = listOf(
    CollateralTokenBalance(
        symbol = "USDC",
        name = "USD Coin",
        address = "0x036CbD53842c5426634e7929541eC2318f3dCF7e",
        decimals = 6,
        balance = BigDecimal("1250.50"),
        exchangeRate = 1.0,
    ),
    CollateralTokenBalance(
        symbol = "WETH",
        name = "Wrapped Ether",
        address = "0x4200000000000000000000000000000000000006",
        decimals = 18,
        balance = BigDecimal("0.25"),
        exchangeRate = 3200.0,
    ),
)

private val previewEvmWalletTokens = listOf(
    WalletTokenBalance(
        address = "0x036CbD53842c5426634e7929541eC2318f3dCF7e",
        symbol = "USDC",
        name = "USD Coin",
        decimals = 6,
        balance = BigDecimal("84.20"),
    ),
)

private val previewSolanaWalletTokens = listOf(
    WalletTokenBalance(
        address = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU",
        symbol = "USDC",
        name = "USD Coin",
        decimals = 6,
        balance = BigDecimal("42"),
    ),
    // An unregistered SPL mint: no symbol, so the row falls back to the truncated mint address.
    WalletTokenBalance(
        address = "So11111111111111111111111111111111111111112",
        decimals = 9,
        balance = BigDecimal("1000000"),
    ),
)

@Composable
private fun BalancesPreview(state: BalancesUiState, selectedChain: WalletChain = WalletChain.BASE_SEPOLIA) {
    RainTheme {
        BalancesContent(
            innerPadding = PaddingValues(),
            state = state,
            selectedChain = selectedChain,
            onBack = {},
            onFetchCollateral = {},
            onFetchWallet = {},
        )
    }
}

@Preview(name = "Empty · nothing fetched yet", showBackground = true)
@Composable
private fun BalancesEmptyPreview() {
    BalancesPreview(
        BalancesUiState(
            internalWalletAddress = PREVIEW_EVM_WALLET,
            collateralWalletAddress = PREVIEW_EVM_COLLATERAL,
        ),
    )
}

@Preview(name = "Loading · both cards", showBackground = true)
@Composable
private fun BalancesLoadingPreview() {
    BalancesPreview(
        BalancesUiState(
            internalWalletAddress = PREVIEW_EVM_WALLET,
            collateralWalletAddress = PREVIEW_EVM_COLLATERAL,
            isLoading = true,
            isCollateralLoading = true,
        ),
    )
}

@Preview(name = "Loaded · EVM", showBackground = true, heightDp = 1000)
@Composable
private fun BalancesLoadedEvmPreview() {
    BalancesPreview(
        BalancesUiState(
            internalWalletAddress = PREVIEW_EVM_WALLET,
            nativeBalance = "0.4821 ETH",
            walletTokenBalances = previewEvmWalletTokens,
            collateralWalletAddress = PREVIEW_EVM_COLLATERAL,
            collateralBalances = previewEvmCollateral,
        ),
    )
}

@Preview(name = "Loaded · Solana, unregistered mint", showBackground = true, heightDp = 1000)
@Composable
private fun BalancesLoadedSolanaPreview() {
    BalancesPreview(
        BalancesUiState(
            internalWalletAddress = PREVIEW_SOLANA_WALLET,
            nativeBalance = "2.5 SOL",
            walletTokenBalances = previewSolanaWalletTokens,
            collateralWalletAddress = PREVIEW_SOLANA_COLLATERAL,
            collateralBalances = previewEvmCollateral.take(1),
        ),
        selectedChain = WalletChain.SOLANA,
    )
}

@Preview(name = "Loaded · wallet has no tokens", showBackground = true)
@Composable
private fun BalancesNoTokensPreview() {
    BalancesPreview(
        BalancesUiState(
            internalWalletAddress = PREVIEW_EVM_WALLET,
            nativeBalance = "0.0000 ETH",
            collateralWalletAddress = PREVIEW_EVM_COLLATERAL,
        ),
    )
}

@Preview(name = "Errors · both cards", showBackground = true)
@Composable
private fun BalancesErrorPreview() {
    BalancesPreview(
        BalancesUiState(
            internalWalletAddress = PREVIEW_EVM_WALLET,
            errorMessage = "RPC request timed out after 30s",
            collateralWalletAddress = PREVIEW_EVM_COLLATERAL,
            collateralError = "401 Unauthorized: session token expired",
        ),
    )
}

// endregion
