package com.rain.sdk.turnkey

import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.constants.SolanaChains

/**
 * The chains Turnkey's managed transaction path can broadcast (and gas-sponsor) on.
 *
 * The Rain SDK's only send path on Turnkey is `ethSendTransaction` / `solSendTransaction`,
 * which exist solely on Turnkey's managed-broadcast networks — there is no self-broadcast
 * fallback (v0 decision). A send on any other chain would fail opaquely deep in the vendor
 * call, so [requireSendSupport] refuses it up front with [RainError.ChainNotSupported].
 *
 * Reads (balances, history, fee estimation) are deliberately NOT gated here; they need an RPC
 * endpoint registered for the chain (some balances also come from Turnkey's own indexer). Nor is
 * `prepareWithdrawal`, which signs (EVM) or composes (Solana) but never broadcasts: its result is
 * what a host submits itself.
 * Avalanche, for example, is read-only but fully readable.
 *
 * Source of truth: Turnkey's transaction-management broadcasting documentation
 * (https://docs.turnkey.com/features/transaction-management). When Turnkey adds a network,
 * extend this list; the chain set is a cross-platform contract shared by Rain's SDKs.
 *
 * This list is the VENDOR's capability, not Rain's product offering — those are different
 * questions. Rain's supported chains and tokens live at
 * https://docs.rain.xyz (Supported Chains and Tokens); a send actually works only for chains
 * in BOTH lists that also have an RPC endpoint registered. *Which chains can send* in
 * `docs/TURNKEY_SUPPORT.md` names the Rain chains this leaves read-only; Stellar is outside the
 * SDK entirely.
 */
internal object TurnkeyBroadcastChains {

    /**
     * Chains for which the Turnkey `get-balances` API returns data.
     * On any other chain, balance reads fall through to `ChainReader`.
     * Source: https://docs.turnkey.com/api-reference/queries/get-balances
     */
    @Suppress("MagicNumber") // the chain ids are the registry's data; each line names its network
    val BALANCE_API_CHAIN_IDS: Set<Int> = setOf(
        1, // Ethereum Mainnet
        11155111, // Sepolia
        8453, // Base Mainnet
        84532, // Base Sepolia
        137, // Polygon Mainnet
        80002 // Polygon Amoy
    )

    /** EVM chains with Turnkey-managed broadcast, mainnets and their test networks. */
    @Suppress("MagicNumber") // the chain ids are the registry's data; each line names its network
    private val EVM_CHAIN_IDS = setOf(
        1, // Ethereum
        11155111, // Ethereum Sepolia
        10, // Optimism
        11155420, // Optimism Sepolia
        56, // BNB Smart Chain
        97, // BNB testnet
        137, // Polygon
        80002, // Polygon Amoy
        143, // Monad (sponsored sends that leave a delegated account under 10 MON revert; see docs)
        10143, // Monad testnet
        4217, // Tempo
        42431, // Tempo Moderato (testnet)
        4663, // Robinhood Chain
        46630, // Robinhood Chain testnet
        8453, // Base
        84532, // Base Sepolia
        42161, // Arbitrum One
        421614, // Arbitrum Sepolia
    )

    /** Turnkey broadcasts Solana on mainnet and devnet only — not the testnet cluster. */
    private val SOLANA_CHAIN_IDS = setOf(
        RainChain.SOLANA_MAINNET,
        RainChain.SOLANA_DEVNET,
    )

    fun supportsSend(chainId: Int): Boolean =
        if (SolanaChains.isSolanaChain(chainId)) {
            chainId in SOLANA_CHAIN_IDS
        } else {
            chainId in EVM_CHAIN_IDS
        }

    /**
     * Throws [RainError.ChainNotSupported] when [chainId] has no Turnkey-managed broadcast.
     * Call at the top of every send entry point, before any wallet or network work.
     */
    fun requireSendSupport(chainId: Int) {
        if (!supportsSend(chainId)) {
            throw RainError.ChainNotSupported(
                chainId = chainId,
                details = "Managed broadcast does not cover this chain; " +
                    "this wallet can read balances and history on it, but cannot send. " +
                    "See the SDK documentation for the chains that support sending."
            )
        }
    }
}
