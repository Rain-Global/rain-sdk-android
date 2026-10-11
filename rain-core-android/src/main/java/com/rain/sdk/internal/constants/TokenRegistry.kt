package com.rain.sdk.internal.constants

import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.models.NativeCurrency
import com.rain.sdk.models.TokenInfo

/**
 * Static registry of ERC-20 tokens the SDK knows how to read balances for.
 *
 * Used by `ChainReader` to batch-fetch balances: alone on chains no wallet provider's balance
 * service covers, and in the Turnkey adapter for the tokens its balance service leaves out.
 *
 * Scope:
 * - EVM chains only. Solana and Stellar entries are intentionally omitted: Solana balances come
 *   from the adapter's own vendor API or core's Solana chain reader, and the SDK has no Horizon
 *   client.
 *
 * Maintenance:
 * This list lives in-tree, so the SDK owns updates. When tokens are added, removed,
 * or migrated, edit this file and ship a new SDK release. Decimals are best-effort
 * based on the canonical issuer's documentation at time of authoring.
 *
 * Multicall3 status:
 * Chains where Multicall3 is deployed at the canonical address are listed in
 * `MULTICALL3_DEPLOYMENTS` — those use the batched `aggregate3` path.
 * Any chain not in that set uses the parallel `eth_call` fallback.
 */
@RainAdapterApi
object TokenRegistry {

    val tokensByChainId: Map<Int, List<TokenInfo>> = mapOf(
        // Ethereum
        1 to listOf(
            TokenInfo(1, "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", 6, "USDC"),
            TokenInfo(1, "0x6c3ea9036406852006290770BEdFcAbA0e23A0e8", "PYUSD", 6, "PayPal USD"),
            TokenInfo(1, "0xdAC17F958D2ee523a2206206994597C13D831ec7", "USDT", 6, "Tether USD"),
            TokenInfo(1, "0xfdcC3dd6671eaB0709A4C0f3F53De9a333d80798", "SBC", 18, "SBC"),
            TokenInfo(1, "0x4F604735c1cF31399C6E711D5962b2B3E0225AD3", "USDGLO", 18, "Glo Dollar"),
            TokenInfo(1, "0xC9E3df3D230980B45adC623C81C3DF4A73a5350f", "USD+", 6, "USD+"),
            TokenInfo(1, "0xe343167631d89B6Ffc58B88d6b7fB0228795491D", "USDG", 6, "Global Dollar")
        ),
        // Optimism
        10 to listOf(
            TokenInfo(10, "0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85", "USDC", 6, "USDC"),
            TokenInfo(10, "0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1", "DAI", 18, "Dai Stablecoin"),
            TokenInfo(10, "0x94b008aA00579c1307B0EF2c499aD98a8ce58e58", "USDT", 6, "Tether USD"),
            TokenInfo(10, "0xf9FB20B8E097904f0aB7d12e9DbeE88f2dcd0F16", "SBC", 18, "SBC"),
            TokenInfo(10, "0x4F604735c1cF31399C6E711D5962b2B3E0225AD3", "USDGLO", 18, "Glo Dollar")
        ),
        // BNB Chain
        56 to listOf(
            TokenInfo(56, "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", "USDC", 18, "USDC"),
            TokenInfo(56, "0x55d398326f99059fF775485246999027B3197955", "USDT", 18, "Tether USD")
        ),
        // Polygon
        137 to listOf(
            TokenInfo(137, "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", "USDC", 6, "USDC"),
            TokenInfo(137, "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174", "USDC.e", 6, "Bridged USDC"),
            TokenInfo(137, "0xc2132D05D31c914a87C6611C10748AEb04B58e8F", "USDT", 6, "Tether USD"),
            TokenInfo(137, "0xfdcC3dd6671eaB0709A4C0f3F53De9a333d80798", "SBC", 18, "SBC"),
            TokenInfo(137, "0x4F604735c1cF31399C6E711D5962b2B3E0225AD3", "USDGLO", 18, "Glo Dollar"),
            TokenInfo(137, "0x949E7b96C3946A0A035d33094FcB58418d50c505", "rUSD", 6, "Rain USD")
        ),
        // Monad
        143 to listOf(
            TokenInfo(143, "0x754704Bc059F8C67012fEd69BC8A327a5aafb603", "USDC", 6, "USDC")
        ),
        // zkSync Era
        324 to listOf(
            TokenInfo(324, "0x1d17CBcF0D6D143135aE902365D2E5e2A16538D4", "USDC", 6, "USDC")
        ),
        // Base
        8453 to listOf(
            TokenInfo(8453, "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "USDC", 6, "USDC"),
            TokenInfo(8453, "0xFa2ACD0861Bd3219D5764d349D3a970AE8321620", "DKUSD", 18, "DKUSD"),
            TokenInfo(8453, "0x50c5725949A6F0c72E6C4a641F24049A917DB0Cb", "DAI", 18, "Dai Stablecoin"),
            TokenInfo(8453, "0xfde4C96c8593536E31F229EA8f37b2ADa2699bb2", "USDT", 6, "Tether USD"),
            TokenInfo(8453, "0xfdcC3dd6671eaB0709A4C0f3F53De9a333d80798", "SBC", 18, "SBC"),
            TokenInfo(8453, "0xd899C2254C1F4B11FfF038571D6cb02aB8860eC8", "rUSD", 6, "Rain USD"),
            TokenInfo(8453, "0x4F604735c1cF31399C6E711D5962b2B3E0225AD3", "USDGLO", 18, "Glo Dollar")
        ),
        // Plasma
        9745 to listOf(
            TokenInfo(9745, "0xB8CE59FC3717ada4C02eaDF9682A9e934F625ebb", "USDT0", 6, "USDT0")
        ),
        // Arbitrum
        42161 to listOf(
            TokenInfo(42161, "0xaf88d065e77c8cC2239327C5EDb3A432268e5831", "USDC", 6, "USDC"),
            TokenInfo(42161, "0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1", "DAI", 18, "Dai Stablecoin"),
            TokenInfo(42161, "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", "USDT", 6, "Tether USD"),
            TokenInfo(42161, "0xfdcC3dd6671eaB0709A4C0f3F53De9a333d80798", "SBC", 18, "SBC"),
            TokenInfo(42161, "0x4F604735c1cF31399C6E711D5962b2B3E0225AD3", "USDGLO", 18, "Glo Dollar"),
            TokenInfo(42161, "0xC9E3df3D230980B45adC623C81C3DF4A73a5350f", "USD+", 6, "USD+")
        ),
        // Celo
        42220 to listOf(
            TokenInfo(42220, "0xceba9300f2b948710d2653dd7b07f33a8b32118c", "USDC", 6, "USDC"),
            TokenInfo(42220, "0x48065fbBE25f71C9282ddf5e1cD6D6A887483D5e", "USDT", 6, "Tether USD")
        ),
        // Avalanche
        43114 to listOf(
            TokenInfo(43114, "0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E", "USDC", 6, "USDC"),
            TokenInfo(43114, "0xd586E7F844cEa2F87f50152665BCbc2C279D8d70", "DAI", 18, "Dai Stablecoin"),
            TokenInfo(43114, "0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7", "USDT", 6, "Tether USD"),
            TokenInfo(43114, "0xf9FB20B8E097904f0aB7d12e9DbeE88f2dcd0F16", "SBC", 18, "SBC"),
            TokenInfo(43114, "0xB31f66AA3C1e785363F0875A1B74E27b85FD66c7", "WAVAX", 18, "Wrapped AVAX"),
            TokenInfo(43114, "0x5E817F2AbCCB9095585D26c2a3ce234a440574Fc", "FRNT", 18, "FRNT"),
            TokenInfo(43114, "0xFd56187DCe1A7c5Ad5aaE9cA3A8827267e69E58a", "TenantToken", 18, "TenantToken (Raindrop)")
        ),
        // Base Sepolia — Auth Pull sandbox, plus the two collateral tokens Rain's sandbox mints:
        // "Rain USD" through /v1/simulate/collateral/fund, "rUSD" through the hand-mintable contract
        // Rain's docs name. Names and decimals read from the chain on 2026-10-06.
        84532 to listOf(
            TokenInfo(84532, "0x036CbD53842c5426634e7929541eC2318f3dCF7e", "USDC", 6, "USDC"),
            TokenInfo(84532, "0x6122C5964D39416C3F34475d00Ceb37f50700314", "rUSD", 6, "Rain USD"),
            TokenInfo(84532, "0x10b5Be494C2962A7B318aFB63f0Ee30b959D000b", "rUSD", 6, "rUSD")
        ),
        // Arbitrum Sepolia — Auth Pull sandbox
        421614 to listOf(
            TokenInfo(421614, "0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d", "USDC", 6, "USDC")
        ),
        // Ink
        57073 to listOf(
            TokenInfo(57073, "0x2D270e6886d130D724215A266106e6832161EAEd", "USDC", 6, "USDC"),
            TokenInfo(57073, "0xe343167631d89B6Ffc58B88d6b7fB0228795491D", "USDG", 6, "Global Dollar")
        )
    )

    /** Returns the configured tokens for a chain, or empty if none are known. */
    fun tokensFor(chainId: Int): List<TokenInfo> = tokensByChainId[chainId] ?: emptyList()

    /**
     * Native currency metadata per EIP-155 chain ID.
     *
     * Static reference data (the chain's gas token), best-effort like the token list above.
     * Every chain here is EVM, so decimals default to 18.
     */
    val nativeCurrencyByChainId: Map<Int, NativeCurrency> = mapOf(
        1 to NativeCurrency(symbol = "ETH", name = "Ether"),
        10 to NativeCurrency(symbol = "ETH", name = "Ether"),
        56 to NativeCurrency(symbol = "BNB", name = "BNB"),
        137 to NativeCurrency(symbol = "POL", name = "Polygon Ecosystem Token"),
        143 to NativeCurrency(symbol = "MON", name = "Monad"),
        324 to NativeCurrency(symbol = "ETH", name = "Ether"),
        8453 to NativeCurrency(symbol = "ETH", name = "Ether"),
        9745 to NativeCurrency(symbol = "XPL", name = "Plasma"),
        42161 to NativeCurrency(symbol = "ETH", name = "Ether"),
        42220 to NativeCurrency(symbol = "CELO", name = "Celo"),
        43114 to NativeCurrency(symbol = "AVAX", name = "Avalanche"),
        57073 to NativeCurrency(symbol = "ETH", name = "Ether"),
        84532 to NativeCurrency(symbol = "ETH", name = "Ether"),
        421614 to NativeCurrency(symbol = "ETH", name = "Ether")
    )

    /**
     * Native currency for a chain. Falls back to an 18-decimal ETH-like default for chains
     * not explicitly listed — every chain the SDK targets today is EVM.
     */
    fun nativeCurrency(chainId: Int): NativeCurrency =
        nativeCurrencyByChainId[chainId] ?: NativeCurrency(symbol = "ETH", name = "Ether")
}
