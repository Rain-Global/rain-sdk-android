package com.rain.sdk.provider

import com.rain.sdk.ExperimentalRainApi
import com.rain.sdk.error.RainError
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import com.rain.sdk.models.UnsignedSolanaTransfer
import java.math.BigDecimal

/**
 * Interface for abstracting wallet operations — the **port** in Rain's ports-and-adapters split.
 *
 * Allows the SDK to support multiple wallet providers (Portal, Turnkey, Magic, Web3Auth,
 * external providers like Coinbase / Privy / Dynamic, etc.). Each provider module ships an
 * adapter that implements this port and owns its vendor SDK as a private dependency; core never
 * imports a provider.
 *
 * Public so host apps can ship their own implementations and register them via a
 * [ProviderDescriptor]. Implementing it requires `@OptIn(ExperimentalRainApi::class)`: Rain may
 * add members in any release, with a default body wherever one makes sense, so an
 * implementation should expect the interface to grow. Calling it needs no opt-in.
 *
 * Error contract: a failure leaves an implementation as a [com.rain.sdk.error.RainError],
 * never as a vendor exception. Core passes a `RainError` through with its code, the withdrawal
 * paths' rewrap of a simulation failure as `WithdrawalRevertedByNetwork` aside. Anything else it
 * wraps as `ProviderError` after its shared prose heuristics, with two exceptions: on
 * `estimateGas`, `estimateWithdrawalFee` and the Solana `withdrawCollateral` and
 * `prepareWithdrawal` paths a raw exception floors at `InternalError`, and the hooks core calls
 * before it enters a wrapper (the EVM wallet-address read and [requireSendSupport] on
 * `withdrawCollateral`)
 * are not wrapped at all, so a raw exception there reaches the host as thrown. Either way an
 * adapter that lets a vendor type escape loses the specific code a host branches on,
 * `TokenExpired` above all. Rain's adapters convert in their session coordinator, which every
 * wallet call passes through. Reads and estimates an adapter answers straight from an RPC node
 * bypass it and raise core's error types directly. A creation-time probe converts at its own call
 * site, and managed authentication converts at its own boundary.
 */
@SubclassOptInRequired(ExperimentalRainApi::class)
interface WalletProvider {
    /**
     * Stable identifier for this provider. Defaults to a generated id for host-supplied providers
     * that don't override it; Rain's own adapters return [ProviderId.PORTAL], [ProviderId.TURNKEY]
     * or [ProviderId.PRIVY].
     */
    val id: ProviderId get() = ProviderId("custom")

    /**
     * Optional behaviours this provider supports (see [Capability]). Defaults to empty — a provider
     * that advertises nothing extra still satisfies the lowest-common-denominator port.
     */
    val capabilities: Set<Capability> get() = emptySet()

    /**
     * Refuses a send on [chainId] before any work starts. Core calls this at the top of every
     * flow that signs and broadcasts (withdrawals, Auth Pull approvals), so a chain the provider
     * cannot broadcast on fails closed before the contract reads and the signing prompt rather
     * than after them. A provider that can broadcast on every configured chain keeps the no-op
     * default; an adapter whose vendor broadcasts on a fixed set consults its own chain registry.
     * Preparing a withdrawal is not gated: it never broadcasts.
     *
     * @throws RainError.ChainNotSupported when this provider cannot broadcast on [chainId].
     */
    fun requireSendSupport(chainId: Int) {}

    /**
     * True when this provider pays the network fee for sends on [chainId], so core skips the
     * self-paid preflight that would charge the fee to the wallet: the Solana withdrawal dry run.
     * Fee estimates are not affected; they quote what the wallet would pay itself whether or not a sponsor pays.
     * The per-chain refinement of [Capability.GAS_SPONSORSHIP]: a provider may sponsor only where
     * it can broadcast. Defaults to advertising the capability.
     */
    fun sponsorsFees(chainId: Int): Boolean = Capability.GAS_SPONSORSHIP in capabilities

    /**
     * Gets the current wallet address.
     */
    suspend fun getWalletAddress(): String

    /**
     * Gets the wallet address for a specific chain. EVM chains share one address; a provider
     * that also holds non-EVM accounts (e.g. Turnkey with a Solana account) returns the
     * address matching [chainId]'s family.
     *
     * Defaults to [getWalletAddress] so EVM-only providers (e.g. Portal) need no change.
     */
    suspend fun getWalletAddress(chainId: Int): String = getWalletAddress()

    /**
     * Sends native token (e.g., AVAX).
     *
     * @param chainId The network ID (e.g., 43114 for Avalanche).
     * @param toAddress The recipient's wallet address.
     * @param amountInEth The amount of token to send (in Eth/Avax unit, not Wei).
     * @return The transaction hash of the transaction.
     */
    suspend fun sendNativeToken(
        chainId: Int,
        toAddress: String,
        amountInEth: BigDecimal
    ): String

    /**
     * Sends an ERC-20 token, or an SPL token when [chainId] is a Solana chain (then
     * [contractAddress] is the mint).
     *
     * @param chainId The network ID.
     * @param contractAddress The ERC-20 token contract address, or the SPL mint on Solana.
     * @param toAddress The recipient's wallet address.
     * @param amount The amount of token to send (in human-readable unit).
     * @param decimals The number of decimals the token uses. On Solana this is not authoritative,
     *   and 0 when the caller supplied none — never scale the amount with it. Read the mint's
     *   decimals and pass those to `TransferChecked`; the token program rejects a mismatch
     *   (`TokenError` 18). 0 is also a legal mint value, so never branch on it.
     * @return The transaction hash.
     */
    suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        toAddress: String,
        amount: BigDecimal,
        decimals: Int
    ): String

    /**
     * Fetches a single balance (native or a contract token) as a rich [Balance].
     *
     * @param chainId The target blockchain network identifier.
     * @param token [Token.Native] or a [Token.Contract].
     * @return A [Balance] with exact `rawAmount` plus resolved decimals / symbol / name.
     */
    suspend fun getBalance(chainId: Int, token: Token): Balance

    /**
     * Fetches the current wallet's balances on the given network: the native balance, always, plus
     * one entry per contract token the wallet holds that this provider lists; zero-balance contract
     * tokens are omitted. Which tokens each shipped provider lists is stated under
     * `getTokenBalances` in `docs/METHODS.md`.
     *
     * @param chainId The target blockchain network identifier.
     * @return The native balance plus one [Balance] per listed contract token with a non-zero balance.
     */
    suspend fun getBalances(chainId: Int): List<Balance>

    /**
     * Gets the transaction history for the specified chain.
     */
    suspend fun getTransactions(
        chainId: Int,
        limit: Int? = null,
        offset: Int? = null,
        order: RainTransactionOrder? = null
    ): List<RainTransaction>

    /**
     * Signs EIP-712 typed data with the provider's wallet.
     *
     * @param chainId The chain ID
     * @param walletAddress The wallet address to sign with
     * @param typedDataJson The EIP-712 typed data as JSON string
     * @return The signature as a hex string (0x-prefixed)
     */
    suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String
    ): String

    /**
     * Sends a low-level transaction with the provider's wallet.
     * Used by flows like withdraw-collateral that prepare their own calldata.
     *
     * @param chainId The chain ID
     * @param from The sender address
     * @param to The target contract address
     * @param data Hex-encoded calldata (or "0x" / empty for plain transfers)
     * @param value Hex-encoded wei value (e.g. "0x0")
     * @return The transaction hash
     */
    suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): String

    /**
     * Estimates the total fee (in the chain's native token, e.g. ETH) for a transaction.
     *
     * @param chainId The chain ID
     * @param from The sender address
     * @param to The target contract address
     * @param data Hex-encoded calldata (or "0x" / empty for plain transfers)
     * @param value Hex-encoded wei value (e.g. "0x0")
     * @return Estimated fee in the chain's native token, as an exact [BigDecimal]
     */
    suspend fun estimateTransactionFee(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): BigDecimal

    /**
     * Signs and broadcasts a pre-composed Solana transaction with the wallet's ed25519 key,
     * returning the transaction signature. Core composes the transaction (all preflights and
     * simulation already done); only the signing step is vendor-specific.
     *
     * Defaults to unsupported so EVM-only providers need no change.
     */
    suspend fun sendSolanaTransaction(
        chainId: Int,
        unsigned: UnsignedSolanaTransfer
    ): String = throw RainError.InvalidConfig(
        "Provider '${id.value}' cannot sign Solana transactions"
    )
}
