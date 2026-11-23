package com.rain.sdk.sample

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object WalletInfo : Screen("wallet_info")
    data object Balances : Screen("balances")
    data object SendTokens : Screen("send_tokens")
    data object CollateralWithdraw : Screen("collateral_withdraw")
    data object AuthPull : Screen("auth_pull")
    data object TransactionHistory : Screen("transaction_history")
}
