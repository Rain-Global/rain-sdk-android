package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Pins the account-resolution rules in [TurnkeyAccounts]: the first account of a chain family
 * across every wallet in list order, and the address lookup that matches Ethereum hex ignoring
 * case and every other format exactly. [TurnkeyManager] resolves its signing addresses through
 * these functions, so a change here changes which account the SDK signs with.
 *
 * Gated on JDK 24+ because Turnkey's published AAR is compiled to major class version 68. Every
 * vendor type lives inside a method body. JUnit resolves method signatures during discovery, which
 * would load the vendor classes before `assumeTrue` can skip, as [TurnkeyErrorMappingTest] explains.
 */
class TurnkeyAccountsTest {

    @Before
    fun requireJdk24() = assumeJdk24()

    @Test
    fun `the Ethereum account is the first Ethereum-format account across wallets in list order`() {
        val first = MockTurnkey.defaultWallet().accounts.single().copy(address = ETH_A, walletId = "wallet-a")
        val second = MockTurnkey.defaultWallet().accounts.single().copy(address = ETH_B, walletId = "wallet-b")
        val wallets = listOf(
            com.turnkey.core.models.Wallet(id = "wallet-a", name = "a", accounts = listOf(first)),
            com.turnkey.core.models.Wallet(id = "wallet-b", name = "b", accounts = listOf(second))
        )

        val resolved = TurnkeyAccounts.ethereumAccount(wallets)

        assertThat(resolved?.address).isEqualTo(ETH_A)
        assertThat(resolved?.walletId).isEqualTo("wallet-a")
    }

    @Test
    fun `the Solana account is the first Solana-format account across wallets`() {
        val wallets = listOf(
            MockTurnkey.defaultWallet(),
            com.turnkey.core.models.Wallet(
                id = "sol-1",
                name = "sol-1",
                accounts = listOf(MockTurnkey.solanaAccount(MockTurnkey.DEFAULT_SOLANA_ADDRESS))
            ),
            com.turnkey.core.models.Wallet(
                id = "sol-2",
                name = "sol-2",
                accounts = listOf(MockTurnkey.solanaAccount(MockTurnkey.DEFAULT_SOLANA_RECIPIENT))
            )
        )

        val resolved = TurnkeyAccounts.solanaAccount(wallets)

        assertThat(resolved?.address).isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        assertThat(resolved?.addressFormat).isEqualTo(com.turnkey.types.V1AddressFormat.ADDRESS_FORMAT_SOLANA)
    }

    @Test
    fun `accountAt matches an Ethereum address ignoring case and a Solana address exactly`() {
        val ethereum = com.turnkey.types.V1AddressFormat.ADDRESS_FORMAT_ETHEREUM
        val solana = com.turnkey.types.V1AddressFormat.ADDRESS_FORMAT_SOLANA
        val wallet = MockTurnkey.walletWithEthAndSolana()
        val readdressed = wallet.copy(
            accounts = wallet.accounts.map { account ->
                if (account.addressFormat == ethereum) account.copy(address = ETH_A) else account
            }
        )
        val wallets = listOf(readdressed)

        assertThat(TurnkeyAccounts.accountAt(wallets, ETH_A.uppercase(), ethereum)?.address).isEqualTo(ETH_A)
        assertThat(TurnkeyAccounts.accountAt(wallets, ETH_A, ethereum)?.address).isEqualTo(ETH_A)
        assertThat(TurnkeyAccounts.accountAt(wallets, MockTurnkey.DEFAULT_SOLANA_ADDRESS, solana)?.address)
            .isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        assertThat(TurnkeyAccounts.accountAt(wallets, MockTurnkey.DEFAULT_SOLANA_ADDRESS.lowercase(), solana))
            .isNull()
    }

    @Test
    fun `accountAt ignores an address whose account has another format`() {
        val ethereum = com.turnkey.types.V1AddressFormat.ADDRESS_FORMAT_ETHEREUM
        val solana = com.turnkey.types.V1AddressFormat.ADDRESS_FORMAT_SOLANA
        val wallets = listOf(MockTurnkey.walletWithEthAndSolana())

        assertThat(TurnkeyAccounts.accountAt(wallets, MockTurnkey.DEFAULT_SOLANA_ADDRESS, ethereum)).isNull()
        assertThat(TurnkeyAccounts.accountAt(wallets, MockTurnkey.DEFAULT_WALLET_ADDRESS, solana)).isNull()
        assertThat(TurnkeyAccounts.accountAt(wallets, ETH_B, ethereum)).isNull()
    }

    @Test
    fun `an empty wallet list resolves nothing`() {
        val ethereum = com.turnkey.types.V1AddressFormat.ADDRESS_FORMAT_ETHEREUM
        val empty = emptyList<com.turnkey.core.models.Wallet>()
        val accountless = listOf(com.turnkey.core.models.Wallet(id = "bare", name = "bare", accounts = emptyList()))

        assertThat(TurnkeyAccounts.ethereumAccount(empty)).isNull()
        assertThat(TurnkeyAccounts.solanaAccount(empty)).isNull()
        assertThat(TurnkeyAccounts.accountAt(empty, MockTurnkey.DEFAULT_WALLET_ADDRESS, ethereum)).isNull()
        assertThat(TurnkeyAccounts.ethereumAccount(accountless)).isNull()
        assertThat(TurnkeyAccounts.solanaAccount(accountless)).isNull()
    }

    @Test
    fun `a Solana-first legacy list still resolves the Ethereum account from the second wallet`() {
        val solanaWallet = com.turnkey.core.models.Wallet(
            id = "sol-wallet",
            name = "sol",
            accounts = listOf(MockTurnkey.solanaAccount().copy(walletId = "sol-wallet"))
        )
        val ethereumWallet = com.turnkey.core.models.Wallet(
            id = "eth-wallet",
            name = "eth",
            accounts = listOf(MockTurnkey.defaultWallet().accounts.single().copy(walletId = "eth-wallet"))
        )
        val wallets = listOf(solanaWallet, ethereumWallet)

        val ethereum = TurnkeyAccounts.ethereumAccount(wallets)
        val solana = TurnkeyAccounts.solanaAccount(wallets)

        assertThat(ethereum?.address).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        assertThat(ethereum?.walletId).isEqualTo("eth-wallet")
        assertThat(solana?.address).isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        assertThat(solana?.walletId).isEqualTo("sol-wallet")
    }

    @Test
    fun `sameAddress compares hex ignoring case and every other form exactly`() {
        assertThat(TurnkeyAccounts.sameAddress(ETH_A, ETH_A)).isTrue()
        assertThat(TurnkeyAccounts.sameAddress(ETH_A, ETH_A.uppercase())).isTrue()
        assertThat(TurnkeyAccounts.sameAddress(ETH_A.uppercase(), ETH_A)).isTrue()
        assertThat(TurnkeyAccounts.sameAddress(ETH_A, ETH_B)).isFalse()
        val solana = MockTurnkey.DEFAULT_SOLANA_ADDRESS
        assertThat(TurnkeyAccounts.sameAddress(solana, solana)).isTrue()
        assertThat(TurnkeyAccounts.sameAddress(solana, solana.lowercase())).isFalse()
    }

    private companion object {
        const val ETH_A = "0xabcdef0123456789abcdef0123456789abcdef01"
        const val ETH_B = "0x0123456789abcdef0123456789abcdef01234567"
    }
}
