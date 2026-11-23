package com.rain.sdk

object RainChain {
    const val AVALANCHE_MAINNET = 43114
    const val AVALANCHE_TESTNET = 43113

    // Auth Pull chains — Rain pulls authorization amounts from the user's wallet on these.
    // Base / Arbitrum in production, their Sepolia testnets in sandbox.
    const val BASE_MAINNET = 8453
    const val BASE_SEPOLIA = 84532
    const val ARBITRUM_MAINNET = 42161
    const val ARBITRUM_SEPOLIA = 421614

    // Solana clusters. Solana has no EIP-155 numeric chain ID, so the SDK uses Rain's own
    // chain IDs (900 = mainnet-beta, 901 = devnet — the values the Rain issuing API returns in
    // collateral contracts and expects on withdrawal-signature requests) to keep Solana
    // addressable through the same `chainId: Int` surface as EVM chains. Rain assigns no ID to
    // the testnet cluster; 902 extends the scheme for SDK-internal use only.
    // CAIP-2 (genesis-hash based) and routing live in `internal.constants.SolanaChains`.
    const val SOLANA_MAINNET = 900
    const val SOLANA_TESTNET = 902
    const val SOLANA_DEVNET = 901

    /** True when [chainId] is one of Rain's Solana sentinel chain IDs. */
    fun isSolana(chainId: Int): Boolean =
        chainId == SOLANA_MAINNET || chainId == SOLANA_TESTNET || chainId == SOLANA_DEVNET
}
