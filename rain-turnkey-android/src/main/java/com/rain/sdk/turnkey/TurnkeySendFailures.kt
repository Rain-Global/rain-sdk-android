package com.rain.sdk.turnkey

import com.rain.sdk.error.RainError
import com.turnkey.http.utils.TurnkeyHttpError
import com.turnkey.types.TGetSendTransactionStatusResponse
import com.turnkey.types.V1Activity
import com.turnkey.types.V1ActivityStatus
import com.turnkey.types.V1SolanaFailureDetails
import com.turnkey.types.V1TxError

/**
 * Classifies a send status. A status has failed when its `txStatus` says so, when it carries a
 * `txError`, or when it carries a structured `error` naming anything at all: the backend attaches
 * one only when the send failed, with or without a message, so an included transaction whose error
 * holds only Solana details is a failure even though the status also names its signature.
 *
 * A Solana fee or rent shortfall is [RainError.InsufficientFunds], checked first because the runtime
 * may log a failed program for it too. A status carrying decoded revert details is the contract or
 * program refusing the transaction, the fact a self-paid dry run would have caught before signing,
 * which a sponsored send skips: an EVM revert chain (top level or under `error.eth`), whether the
 * transaction failed before inclusion or was included and reverted on chain, or a Solana
 * `InstructionError` or the runtime's own `Program <id> failed` log line. It maps to
 * [RainError.TransactionSimulationFailed], carrying the hash or signature on `transactionId` when
 * the status names one, and the withdrawal path yields `WithdrawalRevertedByNetwork`. Any other
 * failure, a bare `txError` (the vendor's "error encountered when broadcasting or confirming the
 * transaction"), `error.eth` without a chain, or Solana details such as an expired blockhash, is a
 * [RainError.ProviderError]: it says the send failed, not that the chain executed and rejected it.
 * The message carries the vendor's text, capped like the Rain API client caps error bodies, and the
 * transaction id when there is one, so a host can look the transaction up.
 */
internal object TurnkeySendFailures {

    /** Vendor prose reaches hosts' logs through the cause; bound it as the Rain API client bounds error bodies. */
    internal const val MAX_VENDOR_MESSAGE_LENGTH = 300

    /**
     * The start of the message the vendor's `activity()` helper throws, as a bare `RuntimeException`,
     * when Turnkey answers the submit itself with an error: `HTTP error calling <type> request ...
     * Code: <status>`. With a status below 500 nothing has been executed: Turnkey, or something in
     * front of it, refused the request. A 5xx can come from a gateway after Turnkey created the
     * activity (it executes activities synchronously), so it says nothing about the send. The vendor's
     * query methods say
     * `HTTP error from <path>: <status>` instead, which inside a submit can only come from the poll
     * after acceptance.
     */
    internal const val VENDOR_SUBMIT_REFUSAL_PREFIX = "HTTP error calling "

    /** The first status that may follow acceptance; everything below it refused the request before executing anything. */
    private const val FIRST_SERVER_ERROR_STATUS = 500

    /** The runtime's own failure line, `Program <base58 id> failed: ...`; a program's own log mentioning "failed" does not count. */
    private val PROGRAM_FAILED_LOG = Regex("^Program [1-9A-HJ-NP-Za-km-z]{32,44} failed")

    /** Null when [status] is not a failure; otherwise the error the poll loop throws. */
    fun sendFailure(status: TGetSendTransactionStatusResponse, fallback: String): RainError? {
        val normalized = status.txStatus.uppercase()
        val error = status.error
        val failed = normalized.contains("FAILED") ||
            normalized.contains("REJECTED") ||
            status.txError != null ||
            carriesDetail(error)
        if (!failed) return null
        val transactionId = status.eth?.txHash?.takeIf { it.isNotEmpty() }
            ?: status.solana?.signature?.takeIf { it.isNotEmpty() }
        val message = (status.txError ?: error?.message ?: fallback).take(MAX_VENDOR_MESSAGE_LENGTH) +
            transactionId?.let { " (transaction $it)" }.orEmpty()
        return when {
            isSolanaFundsShortfall(error?.solana) -> RainError.InsufficientFunds()
            carriesOnChainRevert(error) ->
                RainError.TransactionSimulationFailed(IllegalStateException(message), transactionId)
            else -> RainError.ProviderError(IllegalStateException(message))
        }
    }

    /**
     * The settled failure of a send activity, or null. An activity Turnkey failed or rejected without a
     * send status id never broadcast anything (a policy denial, a transaction it could not sign), so
     * it is a [RainError.ProviderError] carrying Turnkey's reason, bounded like the status failures
     * above, or [fallback] when Turnkey gave none; nothing moved, and the host may retry. Any other
     * status is not a failure here: pending and completed activities are the caller's to read.
     */
    fun activityFailure(activity: V1Activity, fallback: String): RainError? {
        val settled = activity.status == V1ActivityStatus.ACTIVITY_STATUS_FAILED ||
            activity.status == V1ActivityStatus.ACTIVITY_STATUS_REJECTED
        if (!settled) return null
        val reason = activity.failure?.message?.take(MAX_VENDOR_MESSAGE_LENGTH) ?: fallback
        return RainError.ProviderError(IllegalStateException(reason))
    }

    /**
     * True for a refusal of a submit before anything was executed: the vendor's `HTTP error calling ...`
     * shape with a status below 500, see [VENDOR_SUBMIT_REFUSAL_PREFIX]. A 5xx, or a message without a
     * readable status, may have followed acceptance and is not one.
     */
    fun isSubmitRefusal(e: Throwable): Boolean =
        e.message?.startsWith(VENDOR_SUBMIT_REFUSAL_PREFIX) == true &&
            TurnkeyErrorMapping.turnkeyHttpStatus(e)?.let { it < FIRST_SERVER_ERROR_STATUS } == true

    /**
     * True for a send failure that says nothing was executed and so leaves the write as itself for the
     * session coordinator to classify: a [RainError] already decided, the typed client's
     * `StamperNotInitialized`, which its `activity()` throws before it builds the request when the
     * client it was handed has no session stamper (a logout landing between the coordinator's session
     * check and its client read), or a refusal of the submit (a 401 there is the one refresh-and-retry
     * is safe on). Everything else may follow acceptance, a transport failure included: the vendor's
     * poll raises the same exception types after an accepted submit, so nothing in such an exception
     * says which phase threw it, and a connection failure is no exception, since OkHttp's retry on
     * connection failure can already have sent the request on a pooled connection.
     */
    fun leavesTheSendAsItself(e: Exception): Boolean =
        e is RainError || e is TurnkeyHttpError.StamperNotInitialized || isSubmitRefusal(e)

    /**
     * The error for a send whose activity the vendor's client lost (a 5xx answer to the submit, a poll
     * read that failed, a lost or undecodable answer; see `TurnkeyManager.sendOutcome`): the fate is
     * unknown, so it is a [RainError.TransactionOutcomeUnknown] (`RAIN_305`) whose message carries the
     * vendor's failure as [Throwable.vendorMessage] renders it, and nothing else. The vendor exception
     * stays out of the cause chain, so no layer reads an HTTP status off it and retries the send. It
     * has its own code so a host can tell it from a [RainError.ProviderError], which on this module's
     * send paths means the send settled as failed or was refused and nothing moved.
     */
    fun unknownFate(failure: Throwable): RainError.TransactionOutcomeUnknown = RainError.TransactionOutcomeUnknown(
        "Wallet backend did not return the send activity; the send may still land: ${failure.vendorMessage()}"
    )

    /** A structured error naming anything: the backend attaches one only when the send failed. */
    private fun carriesDetail(error: V1TxError?): Boolean =
        error != null &&
            (error.message != null || error.eth != null || error.solana != null || !error.revertChain.isNullOrEmpty())

    private fun carriesOnChainRevert(error: V1TxError?): Boolean {
        if (error == null) return false
        val evmRevert = !error.revertChain.isNullOrEmpty() || !error.eth?.revertChain.isNullOrEmpty()
        return evmRevert || isSolanaProgramFailure(error.solana)
    }

    /** A Solana program refused the transaction: an `InstructionError`, or the runtime's failed-program line. */
    private fun isSolanaProgramFailure(solana: V1SolanaFailureDetails?): Boolean {
        if (solana == null) return false
        val instructionError = solana.transactionErrorJson?.contains("InstructionError") == true
        val failedLog = solana.logs.orEmpty().any { PROGRAM_FAILED_LOG.containsMatchIn(it) }
        return instructionError || failedLog
    }

    /** The sender cannot pay the fee or the rent: a shortfall, not a program refusal. */
    private fun isSolanaFundsShortfall(solana: V1SolanaFailureDetails?): Boolean {
        val json = solana?.transactionErrorJson ?: return false
        return json.contains("InsufficientFundsForFee") || json.contains("InsufficientFundsForRent")
    }
}
