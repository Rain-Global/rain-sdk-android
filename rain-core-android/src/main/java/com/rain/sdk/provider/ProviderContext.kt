package com.rain.sdk.provider

import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.network.chainreader.SolanaChainReader
import com.rain.sdk.internal.solana.SolanaSupport
import com.rain.sdk.internal.tokenstore.TokenMetadataStore

/**
 * Vendor-free infrastructure that core builds once from the configured RPC endpoints and hands
 * to each [ProviderDescriptor] when it materializes its [com.rain.sdk.provider.WalletProvider].
 *
 * Adapters use these shared pieces instead of constructing their own, so every provider sees one
 * token store and one RPC view. Everything except [rpcEndpoints] carries
 * [com.rain.sdk.internal.RainAdapterApi]: Rain's adapter modules need it, and the marker keeps it
 * out of the contract with host apps. A host's own descriptor needs only [rpcEndpoints].
 */
class ProviderContext @RainAdapterApi constructor(
    val rpcEndpoints: Map<Int, String>,
    @property:RainAdapterApi val tokenStore: TokenMetadataStore,
    @property:RainAdapterApi val evmChainReader: EvmChainReader,
    @property:RainAdapterApi val solanaSupport: SolanaSupport,
) {
    @RainAdapterApi
    val solanaChainReader: SolanaChainReader get() = solanaSupport.chainReader
}
