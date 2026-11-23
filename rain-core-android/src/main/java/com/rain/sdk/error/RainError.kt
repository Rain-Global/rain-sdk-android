package com.rain.sdk.error

import java.math.BigDecimal

/**
 * The `RAIN_*` codes every [RainError] carries. The values are a published contract hosts switch on, and
 * `RainErrorCodeParityTest` pins them: changing one is a breaking change. *Before opening a PR*
 * in `CONTRIBUTING.md` names the unassigned codes and the next free ones.
 */
enum class RainErrorCode(val code: String) {
    SDK_NOT_INITIALIZED("RAIN_101"),
    INVALID_CONFIG("RAIN_102"),
    INVALID_RPC_URL("RAIN_103"),
    CHAIN_NOT_SUPPORTED("RAIN_104"),

    TOKEN_EXPIRED("RAIN_201"),
    UNAUTHORIZED("RAIN_202"),
    INVALID_LOGIN_CODE("RAIN_203"),

    NETWORK_ERROR("RAIN_301"),
    TRANSACTION_PENDING("RAIN_302"),
    TRANSACTION_OUTCOME_UNKNOWN("RAIN_305"),

    USER_REJECTED("RAIN_401"),
    INSUFFICIENT_FUNDS("RAIN_402"),
    TRANSACTION_SIMULATION_FAILED("RAIN_403"),
    WALLET_UNAVAILABLE("RAIN_404"),
    WITHDRAWAL_REVERTED_BY_NETWORK("RAIN_405"),
    INVALID_AMOUNT("RAIN_406"),
    WALLET_NOT_AUTHORIZED("RAIN_407"),

    PROVIDER_ERROR("RAIN_501"),
    INTERNAL_ERROR("RAIN_502")
}

/**
 * Base Exception class for all Rain SDK errors.
 */
@Suppress("SerializableHasSerializationMethods")
sealed class RainError(
    val errorCode: RainErrorCode,
    message: String,
    cause: Throwable? = null
) : Exception("RainSDK Error [${errorCode.code}]: $message", cause) {

    /** The published `RAIN_xxx` string, `errorCode.code`; a cross-platform contract shared by Rain's SDKs. */
    val code: String get() = errorCode.code

    // --- 1xx Initialization ---
    class SdkNotInitialized :
        RainError(RainErrorCode.SDK_NOT_INITIALIZED, "The SDK or provider was closed, or no provider has been resolved yet")

    class InvalidConfig(details: String) :
        RainError(RainErrorCode.INVALID_CONFIG, "Invalid Config: $details")

    /**
     * No provider was registered for the requested id, or none matched the requested capability.
     * Reuses [RainErrorCode.INVALID_CONFIG] — it is a configuration mistake, not a new failure mode.
     */
    class ProviderNotRegistered(val details: String) :
        RainError(RainErrorCode.INVALID_CONFIG, "Provider not registered: $details")

    /** RPC URL could not be parsed as a valid URL (no chain-ID context). */
    class InvalidRpcUrl(rpcUrl: String) :
        RainError(RainErrorCode.INVALID_RPC_URL, "Invalid RPC URL: $rpcUrl")

    /**
     * The active wallet provider cannot broadcast transactions on this chain, so the send was
     * refused up front instead of failing opaquely mid-flight (e.g. Turnkey-managed broadcast
     * does not cover Avalanche). Reads (balances, history, fee estimates) and `prepareWithdrawal`
     * are not gated.
     */
    class ChainNotSupported(val chainId: Int, details: String) :
        RainError(RainErrorCode.CHAIN_NOT_SUPPORTED, "Sends not supported on chain $chainId: $details")

    // --- 2xx Authentication ---
    class TokenExpired :
        RainError(
            RainErrorCode.TOKEN_EXPIRED,
            "No usable wallet session: it expired, the wallet backend refused it, it could not be refreshed, or no user is signed in"
        )

    class Unauthorized(details: String) :
        RainError(RainErrorCode.UNAUTHORIZED, details)

    /**
     * The one-time login code was rejected — mistyped, expired, or already used. Ask the user to
     * re-enter it or request a new one. Distinct from [TokenExpired] on purpose: a login screen has
     * to tell a bad code from a dead session, and during code verification there is no session yet.
     * Raised by the Rain wallet and by the Turnkey provider's managed authentication mode.
     */
    class InvalidLoginCode :
        RainError(
            RainErrorCode.INVALID_LOGIN_CODE,
            "Login code rejected — ask the user to re-enter it or request a new one"
        )

    // --- 3xx Network ---
    class NetworkError(message: String? = null, cause: Throwable? = null) :
        RainError(
            RainErrorCode.NETWORK_ERROR,
            message?.takeIf { it.isNotBlank() } ?: "A network request to the chain or the wallet backend failed",
            cause
        )

    /**
     * The transaction was handed to the wallet provider or broadcast, and the SDK stopped waiting
     * before it could confirm it: the hash was not yet visible when status polling stopped, or the
     * confirmation window lapsed. Not a failure: resume from [statusId] instead of sending again.
     * The `RAIN_302` row in `docs/METHODS.md` says what [statusId] holds on each path.
     */
    class TransactionPending(val statusId: String) :
        RainError(
            RainErrorCode.TRANSACTION_PENDING,
            "Transaction submitted but not yet confirmed (statusId=$statusId). " +
                "Not a failure: resume polling with the status id; do not resend."
        )

    /**
     * The SDK lost track of a send after handing it to the wallet provider, and holds no identifier
     * to resume from, unlike [TransactionPending]. Not a settled failure: read the wallet's history
     * or balance before sending again. The `RAIN_305` row in `docs/METHODS.md` says where each
     * provider's cases are, and what a [ProviderError] on the same send paths means.
     */
    class TransactionOutcomeUnknown(details: String, cause: Throwable? = null) :
        RainError(RainErrorCode.TRANSACTION_OUTCOME_UNKNOWN, details, cause)

    // --- 4xx User Action ---
    class UserRejected :
        RainError(
            RainErrorCode.USER_REJECTED,
            "The user declined the request in the wallet or the passkey sheet, or the device holds no passkey for this domain"
        )

    /**
     * The wallet holds less of the chain's native currency than the amount, the network fee or the
     * account rent needs. [required] and [available] are in the currency's human units and
     * [currency] names that unit: the Solana preflight fills all three (`SOL`), and so does a Solana
     * dry run or wallet backend status that carries the System Program's `Transfer: insufficient
     * lamports` line; the runtime's bare `InsufficientFundsFor*` error, and every EVM path, which
     * maps vendor prose without amounts, leave them null.
     */
    class InsufficientFunds(
        val required: BigDecimal? = null,
        val available: BigDecimal? = null,
        val currency: String? = null
    ) : RainError(
        RainErrorCode.INSUFFICIENT_FUNDS,
        "Insufficient funds for the amount, the network fee or account rent: " +
            "required ${amountText(required, currency)}, available ${amountText(available, currency)}"
    )

    /**
     * The chain refused the transaction: the dry run reverted before anything was signed, or the
     * provider reported a transaction that failed or reverted after broadcast. [transactionId] is the
     * hash or signature of a transaction the network included and reverted, when the provider named
     * one; null for a dry run.
     */
    class TransactionSimulationFailed(cause: Throwable?, val transactionId: String? = null) :
        RainError(
            RainErrorCode.TRANSACTION_SIMULATION_FAILED,
            "Transaction simulation failed: ${cause?.message}",
            cause
        )

    /**
     * The active wallet provider returned no usable wallet address — e.g. the user has not
     * created or connected a wallet, or the Turnkey context contains no Ethereum account.
     */
    class WalletUnavailable(details: String = "No wallet address from the wallet provider") :
        RainError(RainErrorCode.WALLET_UNAVAILABLE, details)

    /**
     * Withdrawal transaction reverted on-chain (e.g. duplicate withdrawal in a short window,
     * already-used signature, contract guard tripped). [transactionId] follows
     * [TransactionSimulationFailed]: the hash or signature of an included withdrawal when the
     * provider named one, null when the dry run caught it.
     */
    class WithdrawalRevertedByNetwork(
        details: String = "Withdrawal reverted by the network",
        cause: Throwable? = null,
        val transactionId: String? = null
    ) :
        RainError(RainErrorCode.WITHDRAWAL_REVERTED_BY_NETWORK, details, cause)

    /** An amount the token cannot represent; the `RAIN_406` row in `docs/METHODS.md` has the cases. */
    class InvalidAmount(val amount: String, val reason: String) :
        RainError(RainErrorCode.INVALID_AMOUNT, "Invalid amount ($amount): $reason")

    /**
     * The signing wallet is not an admin of the collateral contract. `withdrawAsset` verifies the
     * withdrawal signature against the collateral's admin set, so a non-admin signer reverts
     * on-chain with `InvalidSignature()`. A non-zero Auth Pull approval is refused for the same
     * wallet, because a collateral contract pulls only from its admins. On Solana, the wallet is
     * not the owner of the collateral account, whose address is then [proxyAddress].
     */
    class WalletNotAuthorized(val walletAddress: String, val proxyAddress: String) :
        RainError(
            RainErrorCode.WALLET_NOT_AUTHORIZED,
            "Wallet $walletAddress is not an admin of collateral contract $proxyAddress"
        )

    // The four cases below are token-transfer failures that callers need to tell apart in the UI.
    // They carry their own type (and fields) but deliberately reuse existing [RainErrorCode]s: the
    // code map is a published contract host apps switch on (see RainErrorCodeParityTest), so adding
    // codes here would break it. Promote them to codes of their own in a deliberate version bump.

    /**
     * The wallet holds less of the token than the transfer asks for. A subtype of
     * [InsufficientFunds]'s code, though the shortfall is in the token rather than in the
     * chain's native currency.
     */
    class InsufficientTokenBalance(
        val requested: BigDecimal,
        val available: BigDecimal,
        val token: String
    ) : RainError(
        RainErrorCode.INSUFFICIENT_FUNDS,
        "Insufficient balance for $token: requested ${requested.toPlainString()}, available ${available.toPlainString()}"
    )

    /**
     * The wallet has no account for this token, so there is nothing to send. On Solana a balance
     * lives in a per-mint token account that only exists once the wallet has received the token.
     */
    class TokenAccountNotFound(val walletAddress: String, val token: String) :
        RainError(
            RainErrorCode.INSUFFICIENT_FUNDS,
            "Wallet $walletAddress holds no account for token $token"
        )

    /** No token exists at this address on this chain (wrong address, or wrong cluster/network). */
    class TokenNotFound(val token: String, val chainId: Int) :
        RainError(RainErrorCode.INVALID_CONFIG, "No token found at $token on chainId=$chainId")

    /** The recipient address cannot receive this transfer — see [reason]. */
    class InvalidRecipient(val address: String, val reason: String) :
        RainError(RainErrorCode.INVALID_CONFIG, "Invalid recipient $address: $reason")

    // --- 5xx Internal ---
    class ProviderError(cause: Throwable?) :
        RainError(RainErrorCode.PROVIDER_ERROR, "Provider Error: ${cause?.message}", cause)

    class InternalError(details: String, cause: Throwable? = null) :
        RainError(RainErrorCode.INTERNAL_ERROR, details, cause)
}

/** The one message for a chain the SDK was not built with, shared by the RPC-backed paths. */
internal fun noRpcEndpointConfigured(chainId: Int): RainError.InvalidConfig =
    RainError.InvalidConfig("No RPC endpoint configured for chainId=$chainId")

/** `0.002 SOL`; `0.002` when the currency is unknown; `unknown` when the amount is. */
private fun amountText(amount: BigDecimal?, currency: String?): String =
    amount?.let { it.toPlainString() + currency?.let { unit -> " $unit" }.orEmpty() } ?: "unknown"
