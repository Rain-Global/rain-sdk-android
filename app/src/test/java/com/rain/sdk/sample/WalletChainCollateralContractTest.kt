package com.rain.sdk.sample

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import org.junit.Test

/**
 * Which of the user's collateral contracts a screen gets for the selected chain. The Wallet & QR
 * deposit card offers its address for a deposit on the chain on screen, so it takes the exact
 * chain only; withdraw carries the contract's own chain id through every withdrawal call, so a
 * sibling chain's contract will do there.
 */
class WalletChainCollateralContractTest {

    private val ethereumSepolia = contract(chainId = ETHEREUM_SEPOLIA, proxy = SEPOLIA_PROXY)
    private val arbitrumSepolia = contract(chainId = RainChain.ARBITRUM_SEPOLIA, proxy = ARBITRUM_PROXY)
    private val solanaDevnet = contract(chainId = RainChain.SOLANA_DEVNET, proxy = SOLANA_PROXY)

    @Test
    fun `exact match finds nothing on a chain without a contract of its own`() {
        // WALL-116: a contract on Ethereum Sepolia, which the picker does not offer, and none on the selected chain.
        val contracts = listOf(ethereumSepolia)

        assertThat(WalletChain.ARBITRUM_SEPOLIA.collateralContract(contracts, CollateralContractMatch.EXACT_CHAIN)).isNull()
        assertThat(WalletChain.EVM.collateralContract(contracts, CollateralContractMatch.EXACT_CHAIN)).isNull()
    }

    @Test
    fun `family match on a chain without its own contract takes the sibling EVM contract`() {
        val contracts = listOf(ethereumSepolia)

        assertThat(WalletChain.ARBITRUM_SEPOLIA.collateralContract(contracts, CollateralContractMatch.CHAIN_FAMILY))
            .isEqualTo(ethereumSepolia)
        assertThat(WalletChain.EVM.collateralContract(contracts, CollateralContractMatch.CHAIN_FAMILY))
            .isEqualTo(ethereumSepolia)
    }

    @Test
    fun `both matches return the selected chain's own contract whatever the API's order`() {
        for (match in CollateralContractMatch.entries) {
            assertThat(WalletChain.ARBITRUM_SEPOLIA.collateralContract(listOf(ethereumSepolia, arbitrumSepolia), match))
                .isEqualTo(arbitrumSepolia)
            assertThat(WalletChain.ARBITRUM_SEPOLIA.collateralContract(listOf(arbitrumSepolia, ethereumSepolia), match))
                .isEqualTo(arbitrumSepolia)
        }
    }

    @Test
    fun `a Solana contract never serves an EVM chain, nor an EVM contract a Solana chain`() {
        for (match in CollateralContractMatch.entries) {
            assertThat(WalletChain.ARBITRUM_SEPOLIA.collateralContract(listOf(solanaDevnet), match)).isNull()
            assertThat(WalletChain.SOLANA.collateralContract(listOf(ethereumSepolia), match)).isNull()
        }
        assertThat(WalletChain.SOLANA.collateralContract(listOf(ethereumSepolia, solanaDevnet), CollateralContractMatch.CHAIN_FAMILY))
            .isEqualTo(solanaDevnet)
    }

    @Test
    fun `Solana matches its exact cluster under both policies`() {
        val mainnet = contract(chainId = RainChain.SOLANA_MAINNET, proxy = SOLANA_PROXY)

        for (match in CollateralContractMatch.entries) {
            assertThat(WalletChain.SOLANA.collateralContract(listOf(mainnet), match)).isNull()
            assertThat(WalletChain.SOLANA.collateralContract(listOf(solanaDevnet), match)).isEqualTo(solanaDevnet)
        }
    }

    @Test
    fun `an empty list finds nothing`() {
        for (match in CollateralContractMatch.entries) {
            assertThat(WalletChain.EVM.collateralContract(emptyList(), match)).isNull()
            assertThat(WalletChain.SOLANA.collateralContract(emptyList(), match)).isNull()
        }
    }

    private fun contract(chainId: Int, proxy: String) = CollateralContract(
        id = null,
        chainId = chainId,
        proxyAddress = proxy,
        controllerAddress = CONTROLLER,
        depositAddress = null,
        adminAddresses = emptyList(),
        contractVersion = null,
        tokens = emptyList(),
    )

    private companion object {
        /** A chain the picker does not offer; Rain hosts the QA user's collateral there. */
        const val ETHEREUM_SEPOLIA = 11155111
        const val SEPOLIA_PROXY = "0x1111111111111111111111111111111111111111"
        const val ARBITRUM_PROXY = "0x2222222222222222222222222222222222222222"
        const val CONTROLLER = "0x3333333333333333333333333333333333333333"
        const val SOLANA_PROXY = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"
    }
}
