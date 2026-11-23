package com.rain.sdk.models

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.constants.RainConstants
import com.rain.sdk.internal.utils.ChainIdFormat

/**
 * A chain the SDK talks to: its id, RPC endpoint, and an optional display name.
 *
 * An alternative to the plain `chainId → RPC URL` map accepted by
 * [com.rain.sdk.RainSdk.Builder.rpcEndpoints], for hosts that carry a network name alongside the
 * endpoint or that hold chain ids in CAIP-2 form.
 *
 * @property chainId EIP-155 chain id (e.g. 1 Ethereum, 137 Polygon), or a `RainChain.SOLANA_*`
 *   sentinel.
 * @property rpcUrl RPC endpoint for the chain.
 * @property networkName Optional display name; the SDK never reads it.
 */
data class NetworkConfig(
    val chainId: Int,
    val rpcUrl: String,
    val networkName: String? = null,
) {
    /** CAIP-2 identifier: `eip155:<id>` for EVM chains, `solana:<genesis>` for Solana sentinels. */
    val eip155ChainId: String get() = ChainIdFormat.namespaceFor(chainId).format(chainId)

    companion object {
        /**
         * Builds a config from a CAIP-2 EVM chain id such as `"eip155:137"`.
         *
         * @throws RainError.InvalidConfig if [eip155ChainId] is not `eip155:<chainId>`.
         */
        @Throws(RainError::class)
        fun fromEip155(
            eip155ChainId: String,
            rpcUrl: String,
            networkName: String? = null,
        ): NetworkConfig {
            val prefix = "${RainConstants.EIP155_NAMESPACE}:"
            val chainId = eip155ChainId
                .takeIf { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.toIntOrNull()
                ?: throw RainError.InvalidConfig(
                    "Invalid EIP-155 chain ID format: $eip155ChainId. Expected 'eip155:<chainId>'"
                )
            return NetworkConfig(chainId, rpcUrl, networkName)
        }
    }
}
