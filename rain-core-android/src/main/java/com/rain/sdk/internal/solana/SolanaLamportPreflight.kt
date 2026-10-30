package com.rain.sdk.internal.solana

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.RainAdapterApi
import timber.log.Timber
import java.math.BigInteger

/**
 * The SOL a wallet must hold before a composed transaction is handed to a provider, shared by the
 * transfer and collateral-withdrawal composers so the two cannot drift: the fee unless sponsored,
 * plus the rent of a token account the transaction creates unless sponsored, read from the cluster.
 * The check gates a friendlier up-front error than a broadcast that fails on chain; a self-paid
 * transaction is simulated afterwards as well, and [shortfall] reads the runtime's verdict on that
 * dry run and on a failed send status the same way.
 */
@RainAdapterApi
object SolanaLamportPreflight {
    /** Base fee for a single-signature Solana transaction. */
    const val FEE_LAMPORTS = 5_000L

    /** The classic SPL token account; a Token-2022 associated account carries extensions and is larger. */
    private const val TOKEN_ACCOUNT_SIZE = 165

    /** The System Program's own refusal to move lamports the payer does not hold; a program's log line never has this shape. */
    private val SYSTEM_TRANSFER_SHORTFALL = Regex("^Transfer: insufficient lamports (\\d+), need (\\d+)$")

    /**
     * Throws [RainError.InsufficientFunds] when [address] cannot cover the fee (unless
     * [feesSponsored]) plus the rent of a token account (when [includeAccountRent], unless
     * [rentSponsored]); reads nothing it does not need. [signatures] is what the runtime bills: the
     * transaction's own signature plus one per ed25519 precompile verification.
     */
    @Suppress("LongParameterList") // the node, the wallet, and the facts that decide what it must hold
    suspend fun require(
        rpc: SolanaRpcClient,
        rpcUrl: String,
        address: String,
        includeAccountRent: Boolean,
        feesSponsored: Boolean,
        rentSponsored: Boolean,
        signatures: Int = 1
    ) {
        val rent = if (includeAccountRent && !rentSponsored) {
            rpc.getMinimumBalanceForRentExemption(rpcUrl, TOKEN_ACCOUNT_SIZE)
        } else {
            BigInteger.ZERO
        }
        val required = BigInteger.valueOf(if (feesSponsored) 0L else FEE_LAMPORTS * signatures) + rent
        if (required.signum() == 0) return
        val lamports = rpc.getBalanceLamports(rpcUrl, address)
        if (lamports < required) {
            Timber.w(
                "Rain SDK: wallet %s holds %s lamports, needs %s for this transaction",
                address,
                lamports.toString(),
                required.toString()
            )
            throw RainError.InsufficientFunds(
                required = SolanaConverter.lamportsToSol(required),
                available = SolanaConverter.lamportsToSol(lamports),
                currency = "SOL"
            )
        }
    }

    /**
     * The payer could not pay, as opposed to the program refusing: the runtime's `InsufficientFundsForFee`
     * or `InsufficientFundsForRent` in [error], or the System Program's transfer line in [logs], which names
     * the lamports held and needed when the payer could not fund an account the transaction creates. Null
     * for any other failure.
     */
    fun shortfall(error: String?, logs: List<String>): RainError.InsufficientFunds? {
        val json = error.orEmpty()
        val transfer = logs.firstNotNullOfOrNull { SYSTEM_TRANSFER_SHORTFALL.matchEntire(it) }
        return when {
            json.contains("InsufficientFundsForFee") || json.contains("InsufficientFundsForRent") -> RainError.InsufficientFunds()
            transfer != null -> {
                val (held, needed) = transfer.destructured
                RainError.InsufficientFunds(
                    required = SolanaConverter.lamportsToSol(BigInteger(needed)),
                    available = SolanaConverter.lamportsToSol(BigInteger(held)),
                    currency = "SOL"
                )
            }
            else -> null
        }
    }
}
