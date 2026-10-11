package com.rain.sdk.error

import java.math.BigDecimal

/**
 * The `RAIN_*` codes every [RainError] carries. The values are a published contract hosts switch on, and
 * `RainErrorCodeParityTest` pins them: changing one is a breaking change. RAIN_105, RAIN_303 and RAIN_304 are
 * unassigned and stay so: a new 1xx code starts at RAIN_106 and a new 3xx code at RAIN_306.
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
    message: String? = null,
    cause: Throwable? = null
) : Exception("RainSDK Error [${errorCode.code}]: ${message ?: "See docs for details"}", cause) {

    /** The published `RAIN_xxx` string, `errorCode.code`; a cross-platform contract shared by Rain's SDKs. */
    val code: String get() = errorCode.code

    // --- 1xx Initialization ---
    class SdkNotInitialized : RainError(RainErrorCode.SDK_NOT_INITIALIZED)

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
    class TokenExpired : RainError(RainErrorCode.TOKEN_EXPIRED)

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
        RainError(RainErrorCode.NETWORK_ERROR, message, cause)

    /**
     * The transaction was handed to the wallet provider or broadcast, but the SDK stopped waiting
     * before it could confirm it: the hash was not yet visible when status polling stopped, or the
     * confirmation window lapsed. NOT a failure: the transaction may still confirm, and resending it
     * risks a duplicate transfer. Resume from [statusId], the identifier the path had (a provider
     * status id, which getTransactions resolves to the hash once available, or the transaction or
     * user-operation hash itself), rather than sending again. On a Turnkey or Rain wallet send, EVM
     * or Solana, whose activity the backend accepted without a readable status id, it is the
     * backend's activity id: no call takes it, and it matches `uniqueId` on the activity-log history
     * row for the send. Reported as `RAIN_302`.
     */
    class TransactionPending(val statusId: String) :
        RainError(
            RainErrorCode.TRANSACTION_PENDING,
            "Transaction submitted but not yet confirmed (statusId=$statusId). " +
                "Not a failure: resume polling with the status id; do not resend."
        )

    /**
     * The send was handed to the wallet provider and the SDK then lost track of it: the provider's
     * answer was a server error, a transport failure or a response the SDK could not read, after
     * the provider may already have accepted the send. The SDK cannot say whether anything was
     * broadcast and has no identifier to resume from, which is what separates this from
     * [TransactionPending]. NOT a settled failure: the transaction may still land, and resending it
     * risks a duplicate transfer. Read the wallet's history or balance before sending again. A
     * [ProviderError] on the same send paths means the provider settled the send as failed, or
     * refused it, and nothing moved. Raised today by the Turnkey provider and the Rain wallet, on
     * EVM and Solana sends alike; `docs/TURNKEY_SUPPORT.md`, "After a send is accepted", lists the
     * cases. Reported as `RAIN_305`.
     */
    class TransactionOutcomeUnknown(details: String, cause: Throwable? = null) :
        RainError(RainErrorCode.TRANSACTION_OUTCOME_UNKNOWN, details, cause)

    // --- 4xx User Action ---
    class UserRejected : RainError(RainErrorCode.USER_REJECTED)

    /**
     * The wallet holds less of the chain's native currency than the amount, the network fee or the
     * account rent needs. [required] and [available] are in the currency's human units and
     * [currency] names that unit: the Solana preflight fills all three (`SOL`); the wallet backend's
     * report of a shortfall after a sponsored Solana send, and every EVM path, which maps vendor
     * prose without amounts, leave them null.
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
     * already-used signature, contract guard tripped). [transactionId] is the hash or signature of
     * a withdrawal the network included and reverted, when the provider named one; null when the
     * dry run caught it.
     */
    class WithdrawalRevertedByNetwork(
        details: String = "Withdrawal reverted by the network",
        cause: Throwable? = null,
        val transactionId: String? = null
    ) :
        RainError(RainErrorCode.WITHDRAWAL_REVERTED_BY_NETWORK, details, cause)

    /**
     * The amount is invalid for the token — more decimal places than the token supports, or
     * negative/unrepresentable.
     */
    class InvalidAmount(val amount: String, val reason: String) :
        RainError(RainErrorCode.INVALID_AMOUNT, "Invalid amount ($amount): $reason")

    /**
     * The signing wallet is not an admin of the collateral contract. `withdrawAsset` verifies the
     * withdrawal signature against the collateral's admin set, so a non-admin signer reverts
     * on-chain with `InvalidSignature()`.
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
