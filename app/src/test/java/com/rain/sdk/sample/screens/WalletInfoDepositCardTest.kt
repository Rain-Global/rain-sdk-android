package com.rain.sdk.sample.screens

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.sample.CollateralContract
import com.rain.sdk.sample.WalletChain
import org.junit.Test

/**
 * The Wallet & QR deposit card offers an address for a deposit on the chain on screen, so it takes
 * the selected chain's contract only, and with none it says which chains hold the collateral.
 */
class WalletInfoDepositCardTest {

    private val ethereumSepolia = contract(chainId = ETHEREUM_SEPOLIA, proxy = SEPOLIA_PROXY)
    private val baseSepolia = contract(chainId = RainChain.BASE_SEPOLIA, proxy = BASE_PROXY)
    private val arbitrumSepolia = contract(chainId = RainChain.ARBITRUM_SEPOLIA, proxy = ARBITRUM_PROXY)
    private val solanaDevnet = contract(
        chainId = RainChain.SOLANA_DEVNET,
        proxy = SOLANA_COLLATERAL,
        depositAddress = SOLANA_DEPOSIT,
    )

    /** What a sandbox build's picker offers; the production list drops the two sandbox Auth Pull chains. */
    private val sandboxPicker = listOf(WalletChain.EVM, WalletChain.BASE_SEPOLIA, WalletChain.ARBITRUM_SEPOLIA, WalletChain.SOLANA)
    private val productionPicker = listOf(WalletChain.EVM, WalletChain.BASE_MAINNET, WalletChain.ARBITRUM_MAINNET, WalletChain.SOLANA)

    @Test
    fun `a sibling chain's contract is never the deposit target`() {
        // WALL-116: a contract on Ethereum Sepolia, which the picker does not offer, and none on the selected chain.
        val card = depositCard(WalletChain.ARBITRUM_SEPOLIA, listOf(ethereumSepolia))

        assertThat(card).isEqualTo(DepositCard.NoContract(listOf(ETHEREUM_SEPOLIA)))
        assertThat(noContractMessage(WalletChain.ARBITRUM_SEPOLIA, listOf(ETHEREUM_SEPOLIA), sandboxPicker)).isEqualTo(
            "No collateral contract on EVM · Arbitrum Sepolia. This account's collateral is on " +
                "EVM · Ethereum Sepolia, which this app does not offer."
        )
    }

    @Test
    fun `every sibling is named, picker chains first, whatever the API's order`() {
        // The WALL-116 account: contracts on Base Sepolia (in the picker) and Ethereum Sepolia (not), Fuji selected.
        val expected = DepositCard.NoContract(listOf(RainChain.BASE_SEPOLIA, ETHEREUM_SEPOLIA))
        val message = "No collateral contract on EVM · Avalanche Fuji. This account's collateral is on " +
            "EVM · Base Sepolia and EVM · Ethereum Sepolia; switch to EVM · Base Sepolia to deposit."

        for (contracts in listOf(listOf(ethereumSepolia, baseSepolia), listOf(baseSepolia, ethereumSepolia))) {
            val card = depositCard(WalletChain.EVM, contracts) as DepositCard.NoContract
            assertThat(card.collateralChainIds).containsExactlyElementsIn(expected.collateralChainIds)
            assertThat(noContractMessage(WalletChain.EVM, card.collateralChainIds, sandboxPicker)).isEqualTo(message)
        }
    }

    @Test
    fun `the switch hint depends on what the picker offers`() {
        val chains = listOf(RainChain.BASE_SEPOLIA)

        assertThat(noContractMessage(WalletChain.EVM, chains, sandboxPicker)).isEqualTo(
            "No collateral contract on EVM · Avalanche Fuji. This account's collateral is on " +
                "EVM · Base Sepolia; switch to it to deposit."
        )
        assertThat(noContractMessage(WalletChain.EVM, chains, productionPicker)).isEqualTo(
            "No collateral contract on EVM · Avalanche Fuji. This account's collateral is on " +
                "EVM · Base Sepolia, which this app does not offer."
        )
    }

    @Test
    fun `two offered chains are both named in the hint`() {
        val chains = listOf(RainChain.ARBITRUM_SEPOLIA, RainChain.BASE_SEPOLIA)

        assertThat(noContractMessage(WalletChain.EVM, chains, sandboxPicker)).isEqualTo(
            "No collateral contract on EVM · Avalanche Fuji. This account's collateral is on " +
                "EVM · Base Sepolia and EVM · Arbitrum Sepolia; switch to EVM · Base Sepolia and EVM · Arbitrum Sepolia to deposit."
        )
    }

    @Test
    fun `no contract in the family gives the plain message`() {
        val card = depositCard(WalletChain.SOLANA, listOf(ethereumSepolia, baseSepolia))

        assertThat(card).isEqualTo(DepositCard.NoContract(emptyList()))
        assertThat(noContractMessage(WalletChain.SOLANA, emptyList(), sandboxPicker)).isEqualTo("No collateral contract on Solana · Devnet")
        assertThat(depositCard(WalletChain.EVM, emptyList())).isEqualTo(DepositCard.NoContract(emptyList()))
    }

    @Test
    fun `the selected chain's own contract wins whatever the API's order`() {
        assertThat(depositCard(WalletChain.ARBITRUM_SEPOLIA, listOf(ethereumSepolia, arbitrumSepolia)))
            .isEqualTo(DepositCard.Deposit(ARBITRUM_PROXY))
        assertThat(depositCard(WalletChain.ARBITRUM_SEPOLIA, listOf(arbitrumSepolia, ethereumSepolia)))
            .isEqualTo(DepositCard.Deposit(ARBITRUM_PROXY))
    }

    @Test
    fun `a deposit address wins over the proxy on EVM as on Solana`() {
        val baseWithDeposit = contract(chainId = RainChain.BASE_SEPOLIA, proxy = BASE_PROXY, depositAddress = EVM_DEPOSIT)

        assertThat(depositCard(WalletChain.BASE_SEPOLIA, listOf(baseWithDeposit))).isEqualTo(DepositCard.Deposit(EVM_DEPOSIT))
        assertThat(depositCard(WalletChain.SOLANA, listOf(ethereumSepolia, solanaDevnet)))
            .isEqualTo(DepositCard.Deposit(SOLANA_DEPOSIT))
    }

    private fun contract(chainId: Int, proxy: String, depositAddress: String? = null) = CollateralContract(
        id = null,
        chainId = chainId,
        proxyAddress = proxy,
        controllerAddress = CONTROLLER,
        depositAddress = depositAddress,
        adminAddresses = emptyList(),
        contractVersion = null,
        tokens = emptyList(),
    )

    private companion object {
        /** A chain the picker does not offer; Rain hosts collateral there. */
        const val ETHEREUM_SEPOLIA = 11155111
        const val SEPOLIA_PROXY = "0x1111111111111111111111111111111111111111"
        const val BASE_PROXY = "0x4444444444444444444444444444444444444444"
        const val ARBITRUM_PROXY = "0x2222222222222222222222222222222222222222"
        const val CONTROLLER = "0x3333333333333333333333333333333333333333"
        const val EVM_DEPOSIT = "0x5555555555555555555555555555555555555555"
        const val SOLANA_COLLATERAL = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"
        const val SOLANA_DEPOSIT = "7EcDhSYGxXyscszYEp35KHN8vvw3svAuLKTzXwCFLtV"
    }
}
