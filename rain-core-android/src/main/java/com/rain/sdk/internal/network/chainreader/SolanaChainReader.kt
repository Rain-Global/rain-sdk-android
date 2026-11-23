package com.rain.sdk.internal.network.chainreader

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.constants.SolanaPrograms
import com.rain.sdk.internal.solana.Base58
import com.rain.sdk.internal.solana.SolanaAddresses
import com.rain.sdk.internal.solana.SolanaRpcClient
import com.rain.sdk.internal.solana.SolanaTokenAccount
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Solana implementation of [ChainReader], reading over Solana JSON-RPC.
 *
 * Covers native SOL (`getBalance`) and SPL tokens. SPL balances work differently from ERC-20: a
 * holding lives in a per-mint token account owned by the wallet, so a single mint is read by
 * deriving its associated token account, and *discovery* uses `getTokenAccountsByOwner`, which
 * enumerates holdings directly — no registry of known mints required, unlike EVM.
 */
@RainAdapterApi
class SolanaChainReader(
    private val solanaRpcClient: SolanaRpcClient = SolanaRpcClient(),
    private val rpcUrlResolver: (Int) -> String?
) : ChainReader {

    /** Convenience constructor backed by a static `chainId → rpcUrl` map. */
    constructor(
        rpcEndpoints: Map<Int, String>,
        solanaRpcClient: SolanaRpcClient = SolanaRpcClient()
    ) : this(solanaRpcClient, { rpcEndpoints[it] })

    override suspend fun getNativeBalance(chainId: Int, walletAddress: String): BigDecimal {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(walletAddress)
        val lamports = solanaRpcClient.getBalanceLamports(rpcUrl, walletAddress)
        return com.rain.sdk.internal.solana.SolanaConverter.lamportsToSol(lamports)
    }

    override suspend fun getBalance(
        chainId: Int,
        walletAddress: String,
        token: Token,
        tokenInfo: TokenInfo?
    ): Balance {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(walletAddress)
        return when (token) {
            is Token.Native -> nativeBalance(
                chainId = chainId,
                lamports = solanaRpcClient.getBalanceLamports(rpcUrl, walletAddress)
            )
            is Token.Contract -> splBalance(chainId, rpcUrl, walletAddress, token.address, tokenInfo)
        }
    }

    /**
     * Balance of [mint] for [walletAddress], read from the wallet's associated token account.
     *
     * A wallet that has never held the mint has no such account, which is a zero balance rather
     * than an error. Decimals come from the mint itself; [tokenInfo] only supplies naming, since
     * SPL symbols live in off-chain metadata the SDK does not read.
     */
    private suspend fun splBalance(
        chainId: Int,
        rpcUrl: String,
        walletAddress: String,
        mint: String,
        tokenInfo: TokenInfo?
    ): Balance {
        val mintKey = decodeAddress(mint, "mint")
        val mintInfo = solanaRpcClient.getMintInfo(rpcUrl, mint)
            ?: throw RainError.TokenNotFound(mint, chainId)

        val tokenAccount = SolanaAddresses.associatedTokenAddress(
            owner = decodeAddress(walletAddress, "wallet"),
            mint = mintKey,
            tokenProgramId = Base58.decode(mintInfo.tokenProgram)
        )
        val account = solanaRpcClient.getTokenAccount(rpcUrl, Base58.encode(tokenAccount))

        return Balance(
            token = Token.Contract(mint),
            chainId = chainId,
            rawAmount = account?.amount ?: BigInteger.ZERO,
            decimals = mintInfo.decimals,
            symbol = tokenInfo?.symbol,
            name = tokenInfo?.name
        )
    }

    /**
     * Native SOL plus every SPL token the wallet actually holds.
     *
     * [tokens] is accepted for interface parity but not required: holdings are enumerated from
     * the chain across both token programs, so tokens outside any registry still appear. Entries
     * in [tokens] supply naming for the mints they match.
     *
     * As on EVM, the native read is fatal but token enumeration is not: a failed program
     * enumeration is logged and its holdings omitted.
     */
    override suspend fun getBalances(
        chainId: Int,
        walletAddress: String,
        tokens: List<TokenInfo>
    ): List<Balance> {
        val rpcUrl = resolveRpcUrl(chainId)
        validateAddress(walletAddress)

        val namesByMint = tokens.associateBy { it.address }
        val lamports = solanaRpcClient.getBalanceLamports(rpcUrl, walletAddress)
        val holdings = discoverTokenAccounts(rpcUrl, walletAddress)

        return buildList {
            add(nativeBalance(chainId, lamports))
            // A wallet can hold more than one token account for the same mint, so report the
            // total per mint rather than one entry per account.
            holdings
                .groupBy { it.mint }
                .forEach { (mint, accounts) ->
                    val total = accounts.fold(BigInteger.ZERO) { sum, account -> sum + account.amount }
                    if (total <= BigInteger.ZERO) return@forEach
                    val info = namesByMint[mint]
                    add(
                        Balance(
                            token = Token.Contract(mint),
                            chainId = chainId,
                            rawAmount = total,
                            decimals = accounts.first().decimals,
                            symbol = info?.symbol,
                            name = info?.name
                        )
                    )
                }
        }
    }

    /** Token accounts held under either token program, enumerated concurrently and per-program tolerant. */
    suspend fun discoverTokenAccounts(rpcUrl: String, walletAddress: String): List<SolanaTokenAccount> =
        coroutineScope {
            listOf(SolanaPrograms.TOKEN_ADDRESS, SolanaPrograms.TOKEN_2022_ADDRESS)
                .map { program ->
                    async {
                        try {
                            solanaRpcClient.getTokenAccountsByOwner(rpcUrl, walletAddress, program)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(
                                e,
                                "Rain SDK: token-account enumeration failed for program $program — omitting its holdings"
                            )
                            emptyList()
                        }
                    }
                }
                .flatMap { it.await() }
        }

    private fun nativeBalance(chainId: Int, lamports: BigInteger): Balance {
        val native = SolanaChains.NATIVE_CURRENCY
        return Balance(
            token = Token.Native,
            chainId = chainId,
            rawAmount = lamports,
            decimals = native.decimals,
            symbol = native.symbol,
            name = native.name
        )
    }

    override suspend fun getERC20Balance(
        chainId: Int,
        tokenAddress: String,
        walletAddress: String,
        decimals: Int?
    ): BigDecimal = getBalance(chainId, walletAddress, Token.contract(tokenAddress), null)
        .decimalAmount

    /** A mint's scale, read from the mint account. */
    override suspend fun getDecimals(chainId: Int, tokenAddress: String): Int {
        val rpcUrl = resolveRpcUrl(chainId)
        return solanaRpcClient.getMintInfo(rpcUrl, tokenAddress)?.decimals
            ?: throw RainError.TokenNotFound(tokenAddress, chainId)
    }

    /**
     * Null: an SPL mint carries no on-chain symbol. Names live in Metaplex token metadata, a
     * separate program the SDK does not read — so this reports "unknown" rather than throwing,
     * which would abort metadata enrichment for a token whose balance is perfectly readable.
     */
    override suspend fun getSymbol(chainId: Int, tokenAddress: String): String? = null

    /** Null, for the same reason as [getSymbol]. */
    override suspend fun getName(chainId: Int, tokenAddress: String): String? = null

    /**
     * SPL has no ERC-20 allowance: a delegate is approved on a token account, not by an
     * (owner, spender) pair on the mint. Throws rather than reporting a misleading zero.
     */
    override suspend fun getErc20Allowance(
        chainId: Int,
        tokenAddress: String,
        owner: String,
        spender: String,
        atBlock: String
    ): BigInteger = throw RainError.InternalError(
        "ERC-20 allowances are not supported on Solana (chainId=$chainId)"
    )

    override suspend fun getTransactionReceipt(
        chainId: Int,
        transactionHash: String
    ): MinedReceipt? = throw RainError.InternalError(
        "EVM transaction receipts are not supported on Solana (chainId=$chainId)"
    )

    private fun resolveRpcUrl(chainId: Int): String {
        val rpcUrl = rpcUrlResolver(chainId)
            ?: throw RainError.InvalidConfig("No RPC endpoint configured for chainId=$chainId")
        if (rpcUrl.toHttpUrlOrNull() == null) {
            throw RainError.InvalidConfig("Invalid RPC URL for chainId=$chainId: $rpcUrl")
        }
        return rpcUrl
    }

    private fun validateAddress(address: String) {
        val valid = runCatching { Base58.decode(address).size == SolanaAddresses.PUBLIC_KEY_LENGTH }.getOrDefault(false)
        if (!valid) {
            throw RainError.InternalError("Invalid Solana wallet address: $address")
        }
    }

    private fun decodeAddress(address: String, label: String): ByteArray {
        val bytes = runCatching { Base58.decode(address) }.getOrNull()
        if (bytes == null || bytes.size != SolanaAddresses.PUBLIC_KEY_LENGTH) {
            throw RainError.InternalError("Invalid Solana $label address: $address")
        }
        return bytes
    }
}
