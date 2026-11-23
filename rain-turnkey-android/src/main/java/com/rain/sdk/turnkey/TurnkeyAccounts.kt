package com.rain.sdk.turnkey

import com.turnkey.core.models.Wallet
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1WalletAccount

/**
 * Picks the wallet accounts this module acts on out of a Turnkey organization's wallet list.
 * [TurnkeyManager] signs and reads with the accounts these functions return, and the key exporter
 * exports the same ones, so signing and export can never disagree about which account is in use.
 * The rule is the first account of a chain family across every wallet, in the order the vendor
 * lists them.
 */
internal object TurnkeyAccounts {

    /** The first Ethereum-format account across [wallets] in list order, or null when there is none. */
    fun ethereumAccount(wallets: List<Wallet>): V1WalletAccount? =
        firstOfFormat(wallets, V1AddressFormat.ADDRESS_FORMAT_ETHEREUM)

    /** [ethereumAccount]'s Solana twin: the first Solana-format account across [wallets], or null. */
    fun solanaAccount(wallets: List<Wallet>): V1WalletAccount? =
        firstOfFormat(wallets, V1AddressFormat.ADDRESS_FORMAT_SOLANA)

    /**
     * The account of [format] at [address], or null. The comparison is [sameAddress], so a
     * checksummed and a lowercase spelling of an Ethereum address name the same account while a
     * Base58 address has to match exactly. An account at that address in another format does not
     * match.
     */
    fun accountAt(wallets: List<Wallet>, address: String, format: V1AddressFormat): V1WalletAccount? =
        wallets.flatMap { it.accounts }.firstOrNull { account ->
            account.addressFormat == format && sameAddress(account.address, address)
        }

    /**
     * True when [a] and [b] name the same account: a hex address compares ignoring case, every other
     * form exactly. The module's one address rule: [accountAt] and the export adapter's check that
     * the enclave's bundle is for the requested account both use it.
     */
    fun sameAddress(a: String, b: String): Boolean =
        a == b || (a.startsWith("0x", ignoreCase = true) && a.equals(b, ignoreCase = true))

    private fun firstOfFormat(wallets: List<Wallet>, format: V1AddressFormat): V1WalletAccount? =
        wallets.flatMap { it.accounts }.firstOrNull { it.addressFormat == format }
}
