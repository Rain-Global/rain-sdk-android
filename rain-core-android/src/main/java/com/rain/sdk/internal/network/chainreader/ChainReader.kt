package com.rain.sdk.internal.network.chainreader

import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Provider-agnostic, read-only on-chain query surface.
 *
 * One place for reading state from any chain the SDK consumer has configured. An adapter whose
 * vendor serves balances for only some chains uses this to fill in the rest, and it is available
 * to any wallet-provider adapter that needs the same fallback.
 *
 * V1 surface is balances. Future reads (allowances, generic `eth_call` wrappers) belong on
 * this interface so call sites don't fragment.
 *
 * Implementations exist per chain family. [EvmChainReader] covers all EIP-155 chains via
 * JSON-RPC and [SolanaChainReader] covers the Solana clusters; a Stellar reader would sit
 * alongside them. `chainId: Int` matches the rest of the SDK's typing, with Rain's own sentinel
 * ids standing in for chains that have no EIP-155 number.
 */
@RainAdapterApi
interface ChainReader {
    /**
     * Native balance (e.g. ETH on Ethereum, AVAX on Avalanche). Result is in
     * human-readable form (e.g. `1.5` for 1.5 ETH).
     */
    suspend fun getNativeBalance(chainId: Int, walletAddress: String): BigDecimal

    /**
     * Single ERC-20 balance via `balanceOf(address)`. [decimals] defaults to 18 when null, the
     * SDK's display-path default.
     */
    suspend fun getERC20Balance(
        chainId: Int,
        tokenAddress: String,
        walletAddress: String,
        decimals: Int?
    ): BigDecimal

    /**
     * Batched balances for many tokens on one chain, in a single round-trip when possible.
     *
     * @param tokens ERC-20 tokens to query. The native balance is always included.
     * @return One [Balance] per successfully-read token plus the native balance
     *   ([Token.Native]). Tokens whose `balanceOf` reverts are omitted; zero balances are
     *   retained (zero-filtering is the caller's responsibility).
     */
    suspend fun getBalances(
        chainId: Int,
        walletAddress: String,
        tokens: List<TokenInfo>
    ): List<Balance>

    /**
     * Reads a single balance (native or a contract token) as a rich [Balance].
     *
     * @param tokenInfo Pre-resolved metadata for a [Token.Contract] (decimals / symbol /
     *   name); ignored for [Token.Native]. When `null` for a contract token, defaults are used.
     */
    suspend fun getBalance(
        chainId: Int,
        walletAddress: String,
        token: Token,
        tokenInfo: TokenInfo?
    ): Balance

    /** Reads an ERC-20 token's `decimals()`. Used to enrich tokens not in the registry. */
    suspend fun getDecimals(chainId: Int, tokenAddress: String): Int

    /**
     * Reads an ERC-20 token's `symbol()`. Returns `null` if the call reverts or returns an
     * undecodable payload. Used to enrich tokens not in the registry.
     */
    suspend fun getSymbol(chainId: Int, tokenAddress: String): String?

    /**
     * Reads an ERC-20 token's `name()`. Returns `null` if the call reverts or returns an
     * undecodable payload. Used to enrich tokens not in the registry.
     */
    suspend fun getName(chainId: Int, tokenAddress: String): String?

    /**
     * Reads `allowance(owner, spender)` — the base units of [owner]'s token balance that
     * [spender] may still move. Returned raw so the caller decides the scale; an unlimited
     * approval is `uint256` max.
     *
     * @param atBlock Block tag to read at; pass a [MinedReceipt.blockNumber] to read state that
     *   provably includes that transaction.
     */
    suspend fun getErc20Allowance(
        chainId: Int,
        tokenAddress: String,
        owner: String,
        spender: String,
        atBlock: String = LATEST_BLOCK
    ): BigInteger

    /** `null` while the transaction is still pending; otherwise its outcome and block. */
    suspend fun getTransactionReceipt(chainId: Int, transactionHash: String): MinedReceipt?
}

/** Whatever head the answering node is at — fine standalone, stale for verifying a transaction. */
internal const val LATEST_BLOCK = "latest"

/** A mined transaction's outcome and the block it landed in, so a read can be pinned to that block. */
@RainAdapterApi
data class MinedReceipt(
    /** `true` when the transaction mined successfully, `false` when it mined but reverted. */
    val succeeded: Boolean,

    /** Hex quantity (e.g. `"0x10"`), carried verbatim so it can be reused as a block tag. */
    val blockNumber: String
)
