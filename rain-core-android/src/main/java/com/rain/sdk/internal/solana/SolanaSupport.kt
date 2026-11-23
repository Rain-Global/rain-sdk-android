package com.rain.sdk.internal.solana

import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.network.chainreader.SolanaChainReader
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.models.UnsignedSolanaTransfer
import java.math.BigDecimal

/**
 * Cross-module surface of the core Solana stack for out-of-module wallet adapters (Privy,
 * Portal). Public for the same reason as [com.rain.sdk.provider.WalletProvider]:
 * adapters live in separate Gradle modules, so Kotlin `internal` would wall them off. Not API
 * for host apps.
 *
 * Bundles transfer composition (with all preflights and simulation — see
 * [SolanaTransferComposer]) and balance reads. Signing and broadcasting stay with the adapter,
 * which is the only vendor-specific step.
 */
@RainAdapterApi
class SolanaSupport internal constructor(
    rpcEndpoints: Map<Int, String>,
    @property:RainAdapterApi val rpc: SolanaRpcClient,
) {

    constructor(rpcEndpoints: Map<Int, String>) : this(rpcEndpoints, SolanaRpcClient())

    @RainAdapterApi
    val chainReader =
        SolanaChainReader(rpcEndpoints = rpcEndpoints, solanaRpcClient = rpc)

    @RainAdapterApi
    val composer = SolanaTransferComposer(rpc, rpcEndpoints::get)

    fun isSolanaChain(chainId: Int): Boolean = SolanaChains.isSolanaChain(chainId)

    /** See [SolanaTransferComposer.composeNative]. */
    suspend fun composeNativeTransfer(
        chainId: Int,
        fromAddress: String,
        toAddress: String,
        amountInSol: BigDecimal
    ): UnsignedSolanaTransfer =
        composer.composeNative(chainId, fromAddress, toAddress, amountInSol)

    /** See [SolanaTransferComposer.composeSplToken]. */
    suspend fun composeSplTransfer(
        chainId: Int,
        fromAddress: String,
        mintAddress: String,
        toAddress: String,
        amount: BigDecimal
    ): UnsignedSolanaTransfer =
        composer.composeSplToken(chainId, fromAddress, mintAddress, toAddress, amount)

    /**
     * Native SOL or a single SPL mint; see [SolanaChainReader.getBalance]. [tokenInfo] supplies
     * symbol/name for a mint (SPL mints carry none on chain) — pass the registered entry, if any.
     */
    suspend fun getBalance(
        chainId: Int,
        walletAddress: String,
        token: Token,
        tokenInfo: TokenInfo? = null
    ): Balance = chainReader.getBalance(chainId, walletAddress, token, tokenInfo)

    /** Native SOL plus every held SPL token; see [SolanaChainReader.getBalances]. */
    suspend fun getBalances(
        chainId: Int,
        walletAddress: String,
        tokens: List<TokenInfo>
    ): List<Balance> = chainReader.getBalances(chainId, walletAddress, tokens)
}
