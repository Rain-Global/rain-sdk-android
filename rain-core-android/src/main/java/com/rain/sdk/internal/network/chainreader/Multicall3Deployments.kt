package com.rain.sdk.internal.network.chainreader

/**
 * Multicall3 address per Rain chain ID. Used to decide between the batched `aggregate3` path
 * and the parallel `eth_call` fallback, and to target the batch.
 *
 * Source: https://www.multicall3.com/deployments
 */
internal val MULTICALL3_DEPLOYMENTS: Map<Int, String> = mapOf(
    1 to Multicall3.CANONICAL_ADDRESS, // Ethereum
    10 to Multicall3.CANONICAL_ADDRESS, // Optimism
    56 to Multicall3.CANONICAL_ADDRESS, // BNB Chain
    137 to Multicall3.CANONICAL_ADDRESS, // Polygon
    143 to Multicall3.CANONICAL_ADDRESS, // Monad
    324 to Multicall3.ZKSYNC_ERA_ADDRESS, // zkSync Era
    8453 to Multicall3.CANONICAL_ADDRESS, // Base
    9745 to Multicall3.CANONICAL_ADDRESS, // Plasma
    42161 to Multicall3.CANONICAL_ADDRESS, // Arbitrum
    42220 to Multicall3.CANONICAL_ADDRESS, // Celo
    43114 to Multicall3.CANONICAL_ADDRESS, // Avalanche
    57073 to Multicall3.CANONICAL_ADDRESS, // Ink
    84532 to Multicall3.CANONICAL_ADDRESS, // Base Sepolia
    421614 to Multicall3.CANONICAL_ADDRESS, // Arbitrum Sepolia
)

/** The Multicall3 address on [chainId], or null where no deployment is known. */
internal fun multicall3Address(chainId: Int): String? = MULTICALL3_DEPLOYMENTS[chainId]
