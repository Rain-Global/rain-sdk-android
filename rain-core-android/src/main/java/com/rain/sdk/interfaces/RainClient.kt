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
     * @throws RainError.TransactionOutcomeUnknown (`RAIN_305`) when the provider may have accepted the
     *   withdrawal and its answer was then lost; not a settled failure, read history before sending again
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
     * Builds a collateral withdrawal without broadcasting it, with the parameters of
     * [withdrawCollateral]. Not gated on the provider's broadcast chains, since preparing never
     * broadcasts. [RainPreparedWithdrawal] says what the result holds and how long a Solana
     * blockhash lasts; the `prepareWithdrawal` section in `docs/METHODS.md` has the rules.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`) for a malformed address, or a mixed-case one
     *   whose EIP-55 checksum does not match, before anything is signed, as on [withdrawCollateral]
     * @see withdrawCollateral
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
     * The wallet address for [chainId]'s chain family: the hex address of [getWalletAddress] on an
     * EVM chain, and on a provider advertising [Capability.MULTI_CHAIN] the matching non-EVM
     * account, such as a base58 address for a `RainChain.SOLANA_*` sentinel. An EVM-only provider
     * returns the hex address for any chain id. The `getWalletAddress(chainId)` section in
     * `docs/METHODS.md` has the rules.
     *
     * @param chainId The numeric chain ID (EVM chain ID, or a `RainChain.SOLANA_*` sentinel).
     * @throws RainError if the address cannot be retrieved.
     * @see getWalletAddress
     */
    @Throws(RainError::class)
    suspend fun getWalletAddress(chainId: Int): String

    /**
     * Estimates the gas fee required for a transaction. Sponsorship does not change the quote: it is
     * what the wallet would pay itself, not what a sponsor pays. The `estimateGas` section in
     * `docs/METHODS.md` has the rules.
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
     * Estimates the fee of a collateral withdrawal, in the chain's native token, without
     * broadcasting. EVM only. The estimate signs once with the wallet, because the calldata embeds
     * a signature the controller verifies; to quote without a second signature, prepare once with
     * [prepareWithdrawal] and estimate on the result. Sponsorship does not change the quote, as on
     * [estimateGas]. The `estimateWithdrawalFee` section in `docs/METHODS.md` has the rules.
     *
     * @param chainId The chain ID for the transaction. EVM only.
     * @param addresses All required addresses for the withdrawal, validated as [withdrawCollateral]
     *   validates them.
     * @param amount The amount to withdraw (human units).
     * @param decimals Token decimals.
     * @param adminSignature Rain's authorization for this withdrawal, fetched by the host from the Rain API
     * @param nonce Optional nonce; pin the estimate to the nonce the withdrawal will sign.
     * @return Estimated withdrawal fee in the chain's native token, as an exact [BigDecimal].
     * @throws RainError.InvalidConfig (`RAIN_102`) for a malformed address or a mixed-case one whose
     *   EIP-55 checksum does not match, before anything is signed
     * @throws RainError.WithdrawalRevertedByNetwork (`RAIN_405`) for a node revert
     * @throws RainError.InternalError (`RAIN_502`) for a Solana [chainId]
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
     * Estimates the fee of a withdrawal already built by [prepareWithdrawal]: prepare once, estimate
     * on the result, then submit. What differs from the full-argument [estimateWithdrawalFee]:
     * nothing is built or signed, and [chainId] is not checked against [prepared], so pass the chain
     * id [prepareWithdrawal] was called with. EVM only.
     *
     * @param chainId The chain the withdrawal was prepared for.
     * @param prepared The result of [prepareWithdrawal].
     * @return The estimated fee, as an exact [BigDecimal] in the chain's native token.
     * @throws RainError.WithdrawalRevertedByNetwork (`RAIN_405`) when the node reverts the prepared call
     * @throws RainError.InternalError (`RAIN_502`) for a Solana [prepared] or [chainId]
     */
    @Throws(RainError::class)
    suspend fun estimateWithdrawalFee(chainId: Int, prepared: RainPreparedWithdrawal): BigDecimal

    /**
     * Sends the chain's native token (e.g. ETH, AVAX). The `sendNative` section in `docs/METHODS.md`
     * has the rules.
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
     * @throws RainError.TransactionOutcomeUnknown (`RAIN_305`) when the provider may have accepted the
     *   send and its answer was then lost; not a settled failure, read history before sending again
     * @see sendToken
     */
    @Throws(RainError::class)
    suspend fun sendNative(
        chainId: Int,
        to: String,
        amount: BigDecimal
    ): RainTokenTransferResult

    /**
     * Sends an ERC-20 token, or an SPL token on a Solana chain. The `sendToken` section in
     * `docs/METHODS.md` has the rules.
     *
     * @param chainId Network ID
     * @param contractAddress ERC-20 token contract address, or the SPL mint on Solana. On EVM chains
     *   the format rule of [sendNative]'s `to`, forwarded in checksum form
     * @param to Recipient's wallet address, in the format [sendNative] takes
     * @param amount Amount to send (in human-readable unit, e.g. 1.5 USDC)
     * @param decimals Token decimals. `null` (the default) has the SDK resolve them, and a token whose
     *   decimals it cannot establish fails with [RainError.TokenNotFound] rather than scaling by a
     *   guess. Not used to scale a Solana amount: the SPL mint's own decimals are read from the chain
     *   and enforced by `TransferChecked`
     * @return RainTokenTransferResult containing the transaction hash
     * @throws RainError.InvalidRecipient (`RAIN_102`) for a [to] that breaks the address rule of
     *   [sendNative], and [RainError.InvalidConfig] (`RAIN_102`) for a [contractAddress] that does;
     *   nothing is sent
     * @throws RainError.TransactionOutcomeUnknown (`RAIN_305`) as on [sendNative]: read history before
     *   sending again
     * @see sendNative
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
     * The current wallet's balances on [chainId]: the native balance, plus one [Balance] per contract
     * token the provider lists that has a non-zero balance. The `getTokenBalances` section in
     * `docs/METHODS.md` says which contract tokens each provider lists and what the SDK reads from
     * the chain for it; [getBalance] reads any single token by address, listed or not.
     *
     * @param chainId The numeric chain ID.
     * @throws RainError if no wallet provider is set, or if the request fails.
     * @see getAllBalances
     */
    @Throws(RainError::class)
    suspend fun getTokenBalances(chainId: Int): List<Balance>

    /**
     * Balances on every configured chain, read in parallel and flattened into one list; each
     * [Balance] carries its own `chainId`. A chain whose read fails contributes no entries, so one
     * bad RPC endpoint does not hide the other chains; the exception is a dead session, which fails
     * the whole call. Built from the per-chain read of [getTokenBalances], whose section in
     * `docs/METHODS.md` applies here too.
     *
     * @throws RainError.TokenExpired (`RAIN_201`) when any chain's read fails on a dead session.
     * @throws RainError if the SDK was not initialized or no wallet provider is set.
     * @see getTokenBalances
     */
    @Throws(RainError::class)
    suspend fun getAllBalances(): List<Balance>

    // ---------------------------------------------------------------------------------------
    // Token approvals (Auth Pull)
    // ---------------------------------------------------------------------------------------

    /**
     * The chains this client accepts an Auth Pull approval on: the same set as
     * [com.rain.sdk.RainSdk.authPullChainIds], and what the approval methods below enforce. Empty
     * until `RainSdk.Builder.authPullConfig(...)` supplies the trusted targets. Gate host UI on it,
     * not on an environment's static chain set; the `authPullChainIds` section in `docs/METHODS.md`
     * has the rules.
     */
    val authPullChainIds: Set<Int> get() = emptySet()

    /**
     * Approves [spender] to move up to [amount] of an ERC-20 token from this wallet, and returns
     * the resulting transaction hash: the wallet-side prerequisite for Rain's Auth Pull, which sets
     * the allowance and nothing more. Refused for a chain or token outside the targets
     * `RainSdk.Builder.authPullConfig(...)` supplied. The `approveTokenAllowance` section in
     * `docs/METHODS.md` and `docs/AUTH_PULL.md` have the rules.
     *
     * @param chainId EVM chain the token lives on. Solana chain IDs throw: SPL has no
     *                ERC-20-style allowance.
     * @param contractAddress The ERC-20 token contract (USDC for Auth Pull).
     * @param spender The address being approved: the user's collateral contract on [chainId], the
     *                `proxyAddress` the Rain API returns. Take it from the Rain API, never from
     *                user input: the SDK's `isAdmin` read on it stops a typo, a plain address and a
     *                contract of another kind, and does not prove that Rain issued the contract.
     * @param amount Human-readable allowance (e.g. `250` for 250 USDC). `null` (the default)
     *               approves an unlimited (`uint256` max) allowance, so the user never has to
     *               re-approve; `BigDecimal.ZERO` revokes an existing approval.
     * @return [RainTokenApprovalResult] carrying the transaction hash.
     * @throws RainError.InvalidConfig (`RAIN_102`), [RainError.WalletNotAuthorized] (`RAIN_407`),
     *   [RainError.NetworkError] (`RAIN_301`) or [RainError.InternalError] (`RAIN_502`); the
     *   `approveTokenAllowance` section in `docs/METHODS.md` has the condition for each
     * @see getTokenAllowance
     * @see confirmTokenAllowance
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
     * Estimates the fee of the approval [approveTokenAllowance] would send, in the chain's native
     * token, with the same parameters; nothing is broadcast and no signature is requested.
     * Sponsorship does not change the quote, as on [estimateGas]. The `estimateApprovalFee`
     * section in `docs/METHODS.md` has the rules.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`), [RainError.WalletNotAuthorized] (`RAIN_407`),
     *   [RainError.NetworkError] (`RAIN_301`) or [RainError.InternalError] (`RAIN_502`) as on
     *   [approveTokenAllowance]
     */
    @Throws(RainError::class)
    suspend fun estimateApprovalFee(
        chainId: Int,
        contractAddress: String,
        spender: String,
        amount: BigDecimal? = null
    ): BigDecimal = throw RainError.InvalidConfig(AUTH_PULL_NOT_SUPPORTED)

    /**
     * Confirms that the approval [transactionHash] mined, by reading the allowance back at the
     * block it mined in; a submitted hash alone does not make Auth Pull ready. The read is pinned
     * to that block, so anything but [amount] means the approval did not do what was asked (a wrong
     * owner, token or spender, or a revert inside a bundle), and throws. The
     * `confirmTokenAllowance` section in `docs/METHODS.md` has the rules.
     *
     * @param amount The allowance that was requested, so the result can be checked against it.
     *               `null` (the default) means the unlimited approval.
     * @throws RainError.TransactionSimulationFailed (`RAIN_403`) when the mined transaction reverted
     * @throws RainError.TransactionPending (`RAIN_302`) when the transaction has not mined by the end
     *   of the poll window; not a failure, `statusId` carries [transactionHash], so re-read or
     *   confirm again rather than re-approving
     * @throws RainError.InternalError (`RAIN_502`) when the mined allowance contradicts the request,
     *   which an Auth Pull `transferFrom` mined in the same block also causes; re-read with
     *   [getTokenAllowance] before treating it as a failed approval
     * @see approveTokenAllowance
     * @see getTokenAllowance
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
     * Adds [tokens] to the shared token store, so their decimals and symbol resolve without an
     * on-chain read. The same operation as [com.rain.sdk.RainSdk.registerTokens], for a host that
     * holds a resolved client; that KDoc and the `registerTokens` section under `RainClient` in
     * `docs/METHODS.md` have the rules, what survives [reset] included.
     *
     * @param tokens Tokens to add to the SDK's token store.
     * @throws RainError.InvalidConfig (`RAIN_102`) for a malformed entry, validated as
     *   [com.rain.sdk.RainSdk.registerTokens] validates; the whole list is checked first, so nothing
     *   is registered
     * @see com.rain.sdk.RainSdk.registerTokens
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
     * A square QR code [Bitmap] for an address the host shows, such as a chain-specific wallet
     * address (the Solana account rather than the EVM one) or a Rain collateral deposit address.
     *
     * @param address The address to encode; null encodes the provider's wallet address.
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
