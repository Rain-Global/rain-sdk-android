package com.rain.sdk.interfaces

import android.graphics.Bitmap
import com.rain.sdk.ExperimentalRainApi
import com.rain.sdk.error.RainError
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainPreparedWithdrawal
import com.rain.sdk.models.RainTokenAllowance
import com.rain.sdk.models.RainTokenApprovalResult
import com.rain.sdk.models.RainTokenTransferResult
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderId
import java.math.BigDecimal
import java.math.BigInteger

/** Default body of the Auth Pull methods: implementations that do not support them keep compiling. */
private const val AUTH_PULL_NOT_SUPPORTED = "Auth Pull is not supported by this RainClient implementation"

/**
 * Operations Rain exposes against a single, already-resolved wallet provider.
 *
 * A `RainClient` is obtained from [com.rain.sdk.RainSdk.provider] / [com.rain.sdk.RainSdk.first];
 * it is bound to one provider for its lifetime and never references a concrete vendor type. Which provider backs it is described by [providerId] and
 * [capabilities].
 */
@SubclassOptInRequired(ExperimentalRainApi::class)
interface RainClient {
    /**
     * Checks if the SDK has been successfully initialized.
     */
    val isInitialized: Boolean

    /**
     * Identifier of the provider backing this client (e.g. [ProviderId.PORTAL]).
     */
    val providerId: ProviderId

    /**
     * Optional behaviours the backing provider supports (see [Capability]).
     */
    val capabilities: Set<Capability>

    /**
     * Executes a collateral withdrawal on-chain. Always broadcasts; [prepareWithdrawal] builds without
     * sending.
     *
     * @param chainId The chain ID for the transaction
     * @param addresses All required addresses for the withdrawal. EVM addresses go through
     *   [RainWithdrawAddresses.validated]: a mixed-case address must carry its EIP-55 checksum
     * @param amount The amount to withdraw
     * @param decimals Token decimals. Scales [amount] on every chain including Solana, where it is
     *                 not checked against the SPL mint — pass the mint's real decimals
     * @param adminSignature Rain's authorization for this withdrawal, fetched by the host from the Rain API
     * @param nonce Optional nonce; resolved from the contract when null
     * @return The transaction hash (EVM) or transaction signature (Solana)
     * @throws RainError.InvalidConfig (`RAIN_102`) when an address is malformed or a mixed-case address
     *   carries a wrong EIP-55 checksum, or [adminSignature]'s salt, signature or expiry cannot be
     *   decoded; nothing is signed. The full `RAIN_102` table is `## Errors` in `docs/METHODS.md`
     * @throws RainError.ChainNotSupported (`RAIN_104`) when the provider cannot broadcast on [chainId]
     * @throws RainError.WithdrawalRevertedByNetwork (`RAIN_405`) when the network rejected the withdrawal,
     *   in the dry run or after broadcast
     * @throws RainError.InsufficientFunds (`RAIN_402`) when the wallet cannot pay the amount, the fee or the rent
     * @throws RainError.ProviderError (`RAIN_501`) for a provider failure; [RainError.InternalError] (`RAIN_502`)
     *   for one nothing mapped
     */
    @Throws(RainError::class)
    suspend fun withdrawCollateral(
        chainId: Int,
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        adminSignature: RainAdminSignature,
        nonce: BigInteger? = null
    ): String

    /**
     * Builds a collateral withdrawal without broadcasting it. Takes the same parameters as
     * [withdrawCollateral]. Not gated on the provider's broadcast chains: preparing never broadcasts,
     * so it works where [withdrawCollateral] throws [RainError.ChainNotSupported]. On Solana the fee
     * check and the dry run always run, because the prepared transaction is the host's own self-paid
     * submission. See [RainPreparedWithdrawal] for what this does and does not do offline, and for
     * the Solana blockhash lifetime. Refuses a malformed address, or a mixed-case one whose EIP-55
     * checksum doesn't match, with [RainError.InvalidConfig] (`RAIN_102`) before anything is signed,
     * as [withdrawCollateral] does.
     */
    @Throws(RainError::class)
    suspend fun prepareWithdrawal(
        chainId: Int,
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        adminSignature: RainAdminSignature,
        nonce: BigInteger? = null
    ): RainPreparedWithdrawal

    /**
     * Gets the current wallet address from the underlying provider.
     * @return Hex-encoded wallet address
     * @throws RainError if the address cannot be retrieved
     */
    @Throws(RainError::class)
    suspend fun getWalletAddress(): String

    /**
     * Gets the wallet address for a specific chain. For EVM chains this matches [getWalletAddress]
     * (a hex address). A provider that also holds non-EVM accounts (one advertising
     * [Capability.MULTI_CHAIN]) returns the address matching [chainId]'s family — e.g. a base58
     * address for a Solana chain id (`RainChain.SOLANA_*`). EVM-only providers return the hex
     * address regardless.
     *
     * @param chainId The numeric chain ID (EVM chain ID, or a `RainChain.SOLANA_*` sentinel).
     * @return The wallet address for that chain's family.
     * @throws RainError if the address cannot be retrieved.
     */
    @Throws(RainError::class)
    suspend fun getWalletAddress(chainId: Int): String

    /**
     * Estimates the gas fee required for a transaction. On a provider that sponsors fees the quote is
     * what the wallet would pay itself; a sponsor pays instead and its own cost is not quoted.
     *
     * @param chainId The chain ID for the transaction
     * @param from The sender address
     * @param to The target contract address
     * @param data The transaction data (hex-encoded)
     * @return Estimated gas fee in the chain's native token
     * @throws RainError if estimation fails
     */
    @Throws(RainError::class)
    suspend fun estimateGas(
        chainId: Int,
        from: String,
        to: String,
        data: String
    ): BigDecimal

    /**
     * Estimates the total fee (in the chain's native token, e.g. ETH/AVAX) required to
     * execute a collateral withdrawal transaction.
     *
     * Builds the EIP-712 payload, signs it with the wallet, then runs `eth_estimateGas`
     * against the controller. Nothing is broadcast. Note: the calldata also embeds a wallet
     * signature the controller verifies, so the estimate signs once with the wallet (a
     * placeholder signature would revert the estimate). To quote without a second signature,
     * prepare once with [prepareWithdrawal] and call `estimateWithdrawalFee(chainId, prepared)`
     * passing the result. On a provider that sponsors fees the quote is what the wallet would pay
     * itself; a sponsor pays instead and its own cost is not quoted.
     *
     * @param chainId The chain ID for the transaction. EVM only.
     * @param addresses All required addresses for the withdrawal. The same validation as
     *   [withdrawCollateral]: a mixed-case EVM address must carry its EIP-55 checksum.
     * @param amount The amount to withdraw (human units).
     * @param decimals Token decimals.
     * @param adminSignature Rain's authorization for this withdrawal, fetched by the host from the Rain API
     * @param nonce Optional nonce; pin the estimate to the nonce the withdrawal will sign.
     * @return Estimated withdrawal fee in the chain's native token, as an exact [BigDecimal].
     * @throws RainError.InvalidConfig (`RAIN_102`) for a malformed address or a mixed-case one whose
     *   EIP-55 checksum doesn't match, before anything is signed.
     * @throws RainError if estimation fails; a Solana [chainId] throws [RainError.InternalError].
     */
    @Throws(RainError::class)
    suspend fun estimateWithdrawalFee(
        chainId: Int,
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        adminSignature: RainAdminSignature,
        nonce: BigInteger? = null
    ): BigDecimal

    /**
     * Estimates the fee of a withdrawal already built by [prepareWithdrawal], in the chain's native
     * token, without building or signing anything: prepare once, estimate on the result, then
     * submit. EVM only.
     *
     * @param chainId The chain the withdrawal was prepared for. Not checked against [prepared]: pass the
     *   chain id [prepareWithdrawal] was called with.
     * @param prepared The result of [prepareWithdrawal].
     * @return Estimated withdrawal fee in the chain's native token, as an exact [BigDecimal].
     * @throws RainError if estimation fails; a node revert arrives as [RainError.WithdrawalRevertedByNetwork].
     *   A Solana preparation or chain id throws [RainError.InternalError].
     */
    @Throws(RainError::class)
    suspend fun estimateWithdrawalFee(chainId: Int, prepared: RainPreparedWithdrawal): BigDecimal

    /**
     * Sends the chain's native token (e.g. ETH, AVAX).
     *
     * @param chainId Network ID
     * @param to Recipient's wallet address. On EVM chains, 40 hex characters with an optional `0x`
     *   prefix; a mixed-case address must carry its EIP-55 checksum, and the provider receives it in
     *   checksum form. On Solana chains, the recipient's base58 public key
     * @param amount Amount to send, in the native token's human unit (e.g. 0.1 AVAX)
     * @return RainTokenTransferResult containing the transaction hash
     * @throws RainError.InvalidRecipient (`RAIN_102`) when [to] is not a valid EVM address or its
     *   mixed-case EIP-55 checksum doesn't match; nothing is sent. The full `RAIN_102` table is
     *   `## Errors` in `docs/METHODS.md`
     */
    @Throws(RainError::class)
    suspend fun sendNative(
        chainId: Int,
        to: String,
        amount: BigDecimal
    ): RainTokenTransferResult

    /**
     * Sends an ERC-20 token.
     *
     * @param chainId Network ID
     * @param contractAddress ERC-20 token contract address, or the SPL mint on Solana. On EVM chains,
     *   40 hex characters with an optional `0x` prefix; a mixed-case address must carry its EIP-55
     *   checksum, and the provider receives it in checksum form
     * @param to Recipient's wallet address. The same format rule as [contractAddress] on EVM chains,
     *   forwarded in checksum form
     * @param amount Amount to send (in human-readable unit, e.g. 1.5 USDC)
     * @param decimals Optional number of decimals the token uses (e.g. 6 for USDC, 18 for most
     *                 tokens). When `null` (the default), the SDK resolves the token's
     *                 `decimals()` itself — from its token registry or, for unknown tokens, an
     *                 on-chain `decimals()` read — so callers don't have to track it. If neither
     *                 can establish it, the send fails with [RainError.TokenNotFound] rather than
     *                 scaling the amount by a guess.
     *                 On Solana it never scales the amount: the SPL mint's own decimals are read
     *                 from the chain and enforced by `TransferChecked`.
     * @return RainTokenTransferResult containing the transaction hash
     * @throws RainError.InvalidRecipient (`RAIN_102`) when [to] is not a valid EVM address or its
     *   mixed-case EIP-55 checksum doesn't match, and [RainError.InvalidConfig] (`RAIN_102`) when the
     *   same is true of [contractAddress]; nothing is sent. The full `RAIN_102` table is `## Errors`
     *   in `docs/METHODS.md`
     */
    @Throws(RainError::class)
    suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        to: String,
        amount: BigDecimal,
        decimals: Int? = null
    ): RainTokenTransferResult

    /**
     * Fetches a single balance (native or a contract token) for the current wallet.
     *
     * @param chainId The numeric chain ID (e.g. 1 for Ethereum, 43114 for Avalanche).
     * @param token [Token.Native] for the chain's gas currency, or [Token.Contract] for an
     *              ERC-20. Contract-address comparison is case-insensitive.
     * @return A [Balance] carrying the exact `rawAmount` plus resolved decimals / symbol / name.
     * @throws RainError if no wallet provider is set, or if the request fails.
     */
    @Throws(RainError::class)
    suspend fun getBalance(chainId: Int, token: Token): Balance

    /**
     * Fetches the current wallet's balances on the given network: the native balance, always, plus
     * one entry per contract token the wallet holds that the provider lists. Zero-balance contract
     * tokens are omitted. Which contract tokens each provider lists, and what the SDK reads from the
     * chain for it, is stated once under `getTokenBalances` in `docs/METHODS.md`; [getBalance] reads
     * any single token by address, listed or not.
     *
     * @param chainId The numeric chain ID.
     * @return The native balance plus one [Balance] per listed contract token with a non-zero balance.
     * @throws RainError if no wallet provider is set, or if the request fails.
     */
    @Throws(RainError::class)
    suspend fun getTokenBalances(chainId: Int): List<Balance>

    /**
     * Fetches balances across every chain the SDK was initialized with, in parallel,
     * flattened into a single list. Each [Balance] carries its own `chainId`.
     *
     * Per-chain failures are tolerated — a chain that errors out contributes no entries
     * rather than failing the whole call, so a single bad RPC endpoint doesn't hide
     * balances on the other chains.
     *
     * @return A flat list of balances spanning all healthy configured chains.
     * @throws RainError if the SDK was not initialized or no wallet provider is set.
     */
    @Throws(RainError::class)
    suspend fun getAllBalances(): List<Balance>

    // ---------------------------------------------------------------------------------------
    // Token approvals (Auth Pull)
    // ---------------------------------------------------------------------------------------

    /**
     * The chains this client will accept an Auth Pull approval on, and the only answer that
     * matches what the approval methods below enforce.
     *
     * Empty until `RainSdk.Builder.authPullConfig(...)` supplies the trusted targets, and narrower
     * than the static `RainAuthPullChains.SANDBOX` / `PRODUCTION` sets whenever the configuration
     * is narrower than its environment or a chain has no RPC endpoint. Gate host UI on this rather
     * than on an environment's chain set, so a chain is never offered that an approval would reject.
     */
    val authPullChainIds: Set<Int> get() = emptySet()

    /**
     * Approves [spender] to move up to [amount] of an ERC-20 token from this wallet, and returns
     * the resulting transaction hash.
     *
     * This is the wallet-side prerequisite for Rain's Auth Pull: the Rain operator must be
     * approved on the user's wallet before an authorization can pull USDC into their collateral
     * contract. Rain executes the pull itself; the SDK only sets the allowance.
     *
     * Auth Pull is disabled until `RainSdk.Builder.authPullConfig(...)` supplies the trusted
     * operator and token targets. This method rejects any different chain, token, or spender.
     *
     * @param chainId EVM chain the token lives on. Solana chain IDs throw — SPL has no
     *                ERC-20-style allowance.
     * @param contractAddress The ERC-20 token contract (USDC for Auth Pull).
     * @param spender The address being approved. Source Rain's operator address from Rain rather
     *                than hardcoding it — it differs between sandbox and production.
     * @param amount Human-readable allowance (e.g. `250` for 250 USDC). `null` (the default)
     *               approves an unlimited (`uint256` max) allowance, so the user never has to
     *               re-approve; `BigDecimal.ZERO` revokes an existing approval.
     * @return [RainTokenApprovalResult] carrying the transaction hash.
     */
    @Throws(RainError::class)
    suspend fun approveTokenAllowance(
        chainId: Int,
        contractAddress: String,
        spender: String,
        amount: BigDecimal? = null
    ): RainTokenApprovalResult = throw RainError.InvalidConfig(AUTH_PULL_NOT_SUPPORTED)

    /**
     * Reads the ERC-20 allowance [spender] currently holds over [owner]'s balance.
     *
     * Call it before approving (to skip a redundant transaction). To confirm an approval was
     * mined, use [confirmTokenAllowance], this read is unpinned and can still return the
     * pre-approval value right after submitting.
     *
     * @param owner The wallet whose balance is approved. `null` (the default) reads this client's
     *              own wallet.
     */
    @Throws(RainError::class)
    suspend fun getTokenAllowance(
        chainId: Int,
        contractAddress: String,
        spender: String,
        owner: String? = null
    ): RainTokenAllowance = throw RainError.InvalidConfig(AUTH_PULL_NOT_SUPPORTED)

    /**
     * Estimates the total fee (estimated gas x gas price) to submit the approval, in the chain's
     * native token. Same parameters as [approveTokenAllowance]; nothing is broadcast and no
     * signature is requested. On a provider that sponsors fees the quote is what the wallet would
     * pay itself; a sponsor pays instead and its own cost is not quoted.
     */
    @Throws(RainError::class)
    suspend fun estimateApprovalFee(
        chainId: Int,
        contractAddress: String,
        spender: String,
        amount: BigDecimal? = null
    ): BigDecimal = throw RainError.InvalidConfig(AUTH_PULL_NOT_SUPPORTED)

    /**
     * Waits for an approval transaction to mine successfully, then reads back the resulting
     * allowance at the block it mined in. A submitted transaction hash alone does not make Auth
     * Pull ready.
     *
     * Because the read is pinned to the transaction's own block, the result is exactly what the
     * approval left behind: anything but [amount] means the approval did not do what was asked
     * (wrong owner, token, or spender, or a revert inside a bundle — leaving less when raising
     * the allowance, more when lowering it) and throws, as does a revoke that left a spendable
     * allowance.
     *
     * @param amount The allowance that was requested, so the result can be checked against it.
     *               `null` (the default) means the unlimited approval.
     * @throws RainError.TransactionSimulationFailed when the mined transaction reverted.
     * @throws RainError.TransactionPending when the transaction has not mined by the end of the
     *   poll window. Not a failure: `statusId` carries [transactionHash]; re-read the allowance or
     *   confirm again rather than re-approving.
     * @throws RainError.InternalError when the mined allowance contradicts the request. An Auth
     *   Pull `transferFrom` mined later in the same block also reads back lower and surfaces here;
     *   re-read with [getTokenAllowance] before treating it as a failed approval.
     */
    @Throws(RainError::class)
    suspend fun confirmTokenAllowance(
        transactionHash: String,
        chainId: Int,
        contractAddress: String,
        spender: String,
        amount: BigDecimal? = null,
        owner: String? = null
    ): RainTokenAllowance = throw RainError.InvalidConfig(AUTH_PULL_NOT_SUPPORTED)

    /**
     * Registers additional tokens with the SDK so their metadata (decimals / symbol) resolves
     * without an on-chain enrichment call. Retained across [reset], since the store is shared by every
     * client the `RainSdk` resolves.
     * Re-registering a host-added address replaces its entry; built-in registry tokens are
     * trusted and cannot be overridden.
     *
     * The entries are stored in the shared store before this returns, so a lookup issued right after
     * this call sees them; [com.rain.sdk.RainSdk.registerTokens] is the same operation for a host that
     * has no resolved client yet.
     *
     * @param tokens Tokens to add to the SDK's token store.
     * @throws RainError.InvalidConfig (`RAIN_102`) when an entry's address is malformed for its
     *   chain family (EVM: `0x` followed by 40 hex characters with a correct EIP-55 checksum when mixed-case;
     *   Solana: base58 decoding to 32 bytes) or its `decimals` lies outside 0..77. The whole list
     *   is validated first, so nothing is registered.
     */
    @Throws(RainError::class)
    suspend fun registerTokens(tokens: List<TokenInfo>)

    /**
     * Clears this client's own state only. The shared token store and the chain configuration the
     * `RainSdk` owns survive, so one client resetting does not deconfigure the others. Idempotent.
     * Prefer `RainSdk.close()` to tear down the whole SDK.
     */
    fun reset()

    /**
     * Generates a square QR code [Bitmap] encoding [address], or the wallet's own address when
     * [address] is null.
     *
     * Use this for any address the host needs to show — a chain-specific wallet address (the
     * Solana account rather than the EVM one), or a Rain collateral deposit address.
     *
     * @param address Address to encode. Null encodes the provider's wallet address.
     * @param dimension Output width and height in pixels (the QR is square). Defaults to 256.
     * @return A [Bitmap] containing the QR code.
     * @throws RainError If [address] is null and the provider's address cannot be retrieved.
     */
    @Throws(RainError::class)
    suspend fun generateAddressQRCode(
        address: String? = null,
        dimension: Int = 256
    ): Bitmap

    /**
     * Retrieves the transaction history for the specified chain.
     *
     * @param chainId The numeric chain ID
     * @param limit Optional maximum number of transactions to return
     * @param offset Optional number of transactions to skip for pagination
     * @param order Optional sort order (ASC or DESC)
     * @return List<RainTransaction> containing a list of transactions
     * @throws RainError if the transaction history cannot be retrieved
     */
    @Throws(RainError::class)
    suspend fun getTransactions(
        chainId: Int,
        limit: Int? = null,
        offset: Int? = null,
        order: RainTransactionOrder? = null
    ): List<RainTransaction>
}
