package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.turnkey.MockTurnkeyClient.StatusFixture
import com.turnkey.http.utils.TurnkeyHttpError
import com.turnkey.types.V1ActivityStatus
import com.turnkey.types.V1RevertChainEntry
import com.turnkey.types.V1SolanaFailureDetails
import org.junit.Test

/**
 * The status classification both poll loops share. A structured `error` naming anything marks a failure
 * even without a message or a FAILED status. Decoded revert details, an EVM revert chain, a Solana
 * `InstructionError` or the runtime's failed-program log line, make a status a
 * [RainError.TransactionSimulationFailed] (so a withdrawal reads `WithdrawalRevertedByNetwork`), whether
 * the transaction failed before inclusion or was included and reverted; a Solana fee or rent shortfall is
 * [RainError.InsufficientFunds] and wins over a failed-program log; every other failure is a
 * [RainError.ProviderError]; a status that has not failed yields null. A status that names the transaction
 * carries the hash or signature in the message and on `transactionId`, and the vendor's text is capped.
 */
class TurnkeySendFailuresTest {

    private fun classify(fixture: StatusFixture): RainError? =
        TurnkeySendFailures.sendFailure(fixture.toResponse(), fallback = "fallback message")

    private fun solanaFailed(details: V1SolanaFailureDetails, message: String? = null) =
        StatusFixture(txStatus = "TX_STATUS_FAILED", errorMessage = message, solanaFailure = details)

    // ---- not a failure ------------------------------------------------------------------------

    @Test
    fun `a broadcasted status is not a failure`() {
        assertThat(classify(StatusFixture.broadcasted("0xaa"))).isNull()
    }

    @Test
    fun `a pending status is not a failure`() {
        assertThat(classify(StatusFixture.pending())).isNull()
    }

    @Test
    fun `an included status without an error is not a failure`() {
        assertThat(classify(StatusFixture(txHash = "0xaa", txStatus = "TX_STATUS_INCLUDED"))).isNull()
    }

    // ---- EVM -----------------------------------------------------------------------------------

    @Test
    fun `a bare txError is a ProviderError carrying that text`() {
        val error = classify(StatusFixture.failed(message = "nonce too low"))

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(error?.cause?.message).isEqualTo("nonce too low")
    }

    @Test
    fun `a status whose only detail is error message is a ProviderError carrying that text`() {
        val error = classify(StatusFixture(txStatus = "TX_STATUS_FAILED", errorMessage = "rejected by policy"))

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(error?.cause?.message).isEqualTo("rejected by policy")
    }

    @Test
    fun `eth details without a revert chain stay a ProviderError`() {
        val error = classify(StatusFixture(txStatus = "TX_STATUS_FAILED", errorMessage = "failed", ethRevertChain = emptyList()))

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `an empty top-level revert chain stays a ProviderError`() {
        val error = classify(StatusFixture(txStatus = "TX_STATUS_FAILED", errorMessage = "failed", revertChain = emptyList()))

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a top-level revert chain is a TransactionSimulationFailed carrying the message`() {
        val error = classify(StatusFixture.revertedOnChain("execution reverted: cooldown"))

        assertThat(error).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
        assertThat(error?.cause?.message).isEqualTo("execution reverted: cooldown")
    }

    @Test
    fun `a revert chain under eth is a TransactionSimulationFailed`() {
        assertThat(classify(StatusFixture.ethRevertedOnChain()))
            .isInstanceOf(RainError.TransactionSimulationFailed::class.java)
    }

    @Test
    fun `revert details win over a bare txError and the txError text is the cause`() {
        val fixture = StatusFixture(
            txStatus = "TX_STATUS_FAILED",
            txError = "execution reverted",
            errorMessage = "decoded",
            revertChain = listOf(V1RevertChainEntry(displayMessage = "decoded", errorType = "native"))
        )

        val error = classify(fixture)

        assertThat(error).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
        assertThat(error?.cause?.message).isEqualTo("execution reverted")
    }

    @Test
    fun `an included transaction that reverted on chain is a TransactionSimulationFailed naming the hash`() {
        val hash = "0x" + "d".repeat(64)

        val error = classify(StatusFixture.includedButReverted(hash, message = "execution reverted: cooldown"))

        assertThat(error).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
        assertThat(error?.cause?.message).isEqualTo("execution reverted: cooldown (transaction $hash)")
        assertThat((error as RainError.TransactionSimulationFailed).transactionId).isEqualTo(hash)
    }

    @Test
    fun `an included status whose error carries only eth details is a failure naming the hash`() {
        // No message, no FAILED status: the structured error alone says the send failed.
        val fixture = StatusFixture(txHash = "0xab", txStatus = "TX_STATUS_INCLUDED", ethRevertChain = emptyList())

        val error = classify(fixture)

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(error?.cause?.message).isEqualTo("fallback message (transaction 0xab)")
    }

    @Test
    fun `a vendor message is capped at 300 characters and still ends with the transaction id`() {
        val error = classify(StatusFixture(txHash = "0xab", txStatus = "TX_STATUS_FAILED", txError = "x".repeat(5_000)))

        val message = error?.cause?.message.orEmpty()
        assertThat(message).endsWith(" (transaction 0xab)")
        assertThat(message).hasLength(300 + " (transaction 0xab)".length)
    }

    @Test
    fun `a failed status that names a hash carries it in a ProviderError too`() {
        val error = classify(StatusFixture(txHash = "0xab", txStatus = "TX_STATUS_FAILED", txError = "nonce too low"))

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(error?.cause?.message).isEqualTo("nonce too low (transaction 0xab)")
    }

    // ---- Solana --------------------------------------------------------------------------------

    @Test
    fun `a solana program failure with an rpc message is a TransactionSimulationFailed`() {
        assertThat(classify(StatusFixture.solanaRevertedOnChain()))
            .isInstanceOf(RainError.TransactionSimulationFailed::class.java)
    }

    @Test
    fun `a solana InstructionError is a TransactionSimulationFailed`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(transactionErrorJson = "{\"InstructionError\":[0,\"Custom\"]}"))

        assertThat(classify(fixture)).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
    }

    @Test
    fun `solana logs saying a program failed are a TransactionSimulationFailed`() {
        val fixture = solanaFailed(
            V1SolanaFailureDetails(logs = listOf("Program 11111111111111111111111111111111 failed: custom program error: 0x1"))
        )

        assertThat(classify(fixture)).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
    }

    @Test
    fun `solana logs without a failure line stay a ProviderError`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(logs = listOf("Program log: Instruction: Transfer")), message = "failed")

        assertThat(classify(fixture)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `solana details with only an rpc message stay a ProviderError`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(rpcMessage = "Transaction simulation failed: Blockhash not found"))

        assertThat(classify(fixture)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `an expired solana blockhash stays a ProviderError`() {
        val fixture = solanaFailed(
            V1SolanaFailureDetails(rpcMessage = "Blockhash not found", transactionErrorJson = "\"BlockhashNotFound\"")
        )

        assertThat(classify(fixture)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `an already processed solana transaction stays a ProviderError`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(transactionErrorJson = "\"AlreadyProcessed\""))

        assertThat(classify(fixture)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a solana fee shortfall is an InsufficientFunds without amounts`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(transactionErrorJson = "\"InsufficientFundsForFee\""))

        val error = classify(fixture) as RainError.InsufficientFunds

        assertThat(error.required).isNull()
        assertThat(error.available).isNull()
        assertThat(error.currency).isNull()
    }

    @Test
    fun `a solana rent shortfall is an InsufficientFunds without amounts`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(transactionErrorJson = "{\"InsufficientFundsForRent\":{\"account_index\":1}}"))

        val error = classify(fixture) as RainError.InsufficientFunds

        assertThat(error.required).isNull()
        assertThat(error.available).isNull()
    }

    @Test
    fun `a shortfall wins over the runtime's failed-program log`() {
        // Rent shortfalls surface during execution, so the runtime logs the failed program too.
        val fixture = solanaFailed(
            V1SolanaFailureDetails(
                transactionErrorJson = "{\"InsufficientFundsForRent\":{\"account_index\":1}}",
                logs = listOf("Program 11111111111111111111111111111111 failed: insufficient lamports")
            )
        )

        assertThat(classify(fixture)).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `a program's own log mentioning failed is not the runtime's failure line`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(logs = listOf("Program log: transfer failed")), message = "failed")

        assertThat(classify(fixture)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `an included status whose error carries only solana details is a failure`() {
        // The signature and a message-less structured error arrive together: a failure, not a send.
        val fixture = StatusFixture(
            solanaSignature = "5sig",
            txStatus = "TX_STATUS_INCLUDED",
            solanaFailure = V1SolanaFailureDetails(transactionErrorJson = "\"InsufficientFundsForFee\"")
        )

        assertThat(classify(fixture)).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `solana details with only an rpc code stay a ProviderError`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(rpcCode = -32002L))

        assertThat(classify(fixture)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `solana details with none of the revert fields stay a ProviderError`() {
        val fixture = solanaFailed(V1SolanaFailureDetails(source = "preflight"))

        assertThat(classify(fixture)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a solana signature is carried in the message`() {
        val fixture = StatusFixture(
            solanaSignature = "5sig",
            txStatus = "TX_STATUS_FAILED",
            errorMessage = "failed",
            solanaFailure = V1SolanaFailureDetails(transactionErrorJson = "{\"InstructionError\":[0,\"Custom\"]}")
        )

        val error = classify(fixture)

        assertThat(error).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
        assertThat(error?.cause?.message).isEqualTo("failed (transaction 5sig)")
        assertThat((error as RainError.TransactionSimulationFailed).transactionId).isEqualTo("5sig")
    }

    // ---- fallback ------------------------------------------------------------------------------

    @Test
    fun `a rejected status with no detail is a ProviderError carrying the fallback`() {
        val error = classify(StatusFixture(txStatus = "TX_STATUS_REJECTED"))

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(error?.cause?.message).isEqualTo("fallback message")
    }

    // ---------- send activities ----------

    private fun solanaActivity(status: V1ActivityStatus, reason: String?) = MockTurnkey.makeSolanaActivity(
        id = "act",
        signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
        caip2 = "solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1",
        unsignedTransaction = "00",
        sendTransactionStatusId = null,
        shape = MockTurnkey.SolanaSendShape.V2,
        status = status,
        failureMessage = reason
    )

    @Test
    fun `a failed activity is a ProviderError carrying the backend's reason, capped like a status failure`() {
        val failed = solanaActivity(V1ActivityStatus.ACTIVITY_STATUS_FAILED, "x".repeat(400))

        val error = TurnkeySendFailures.activityFailure(failed, "fallback")

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(error!!.cause?.message).hasLength(TurnkeySendFailures.MAX_VENDOR_MESSAGE_LENGTH)
    }

    @Test
    fun `a rejected activity without a reason carries the fallback`() {
        val rejected = solanaActivity(V1ActivityStatus.ACTIVITY_STATUS_REJECTED, null)

        val error = TurnkeySendFailures.activityFailure(rejected, "Wallet backend rejected the Solana send")

        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(error!!.cause?.message).isEqualTo("Wallet backend rejected the Solana send")
    }

    @Test
    fun `a refusal of the submit is told apart from a failure after acceptance by the vendor's wording and status`() {
        fun submitAnswer(status: String) =
            RuntimeException("HTTP error calling ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2 request\nError: {}\nCode: $status")
        val noStatus = RuntimeException("HTTP error calling ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2 request")
        assertThat(TurnkeySendFailures.isSubmitRefusal(submitAnswer("400"))).isTrue()
        assertThat(TurnkeySendFailures.isSubmitRefusal(submitAnswer("401"))).isTrue()
        assertThat(TurnkeySendFailures.isSubmitRefusal(submitAnswer("403"))).isTrue()
        // A redirect or a rate limit precedes execution as well.
        assertThat(TurnkeySendFailures.isSubmitRefusal(submitAnswer("302"))).isTrue()
        assertThat(TurnkeySendFailures.isSubmitRefusal(submitAnswer("429"))).isTrue()
        // A gateway can answer 5xx after Turnkey created the activity: not a refusal, the activity is read back.
        assertThat(TurnkeySendFailures.isSubmitRefusal(submitAnswer("500"))).isFalse()
        assertThat(TurnkeySendFailures.isSubmitRefusal(submitAnswer("504"))).isFalse()
        assertThat(TurnkeySendFailures.isSubmitRefusal(noStatus)).isFalse()
        assertThat(TurnkeySendFailures.isSubmitRefusal(RuntimeException("HTTP error from /public/v1/query/get_activity: 401"))).isFalse()
        assertThat(TurnkeySendFailures.isSubmitRefusal(java.io.IOException("unexpected end of stream"))).isFalse()
    }

    @Test
    fun `the vendor's typed not-completed error leaves nothing to the coordinator`() {
        val path = "/public/v1/submit/sol_send_transaction"
        val pending = TurnkeyHttpError.ActivityNotCompleted(solanaActivity(V1ActivityStatus.ACTIVITY_STATUS_PENDING, null), path)
        val rejected = TurnkeyHttpError.ActivityNotCompleted(solanaActivity(V1ActivityStatus.ACTIVITY_STATUS_REJECTED, "denied"), path)

        // Neither a refusal of the submit nor a RainError: the manager takes the activity out of it inside the write.
        assertThat(TurnkeySendFailures.isSubmitRefusal(pending)).isFalse()
        assertThat(TurnkeySendFailures.leavesTheSendAsItself(pending)).isFalse()
        assertThat(TurnkeySendFailures.leavesTheSendAsItself(rejected)).isFalse()
        // Thrown by the vendor before any request when the client has no session stamper: nothing was sent.
        assertThat(TurnkeySendFailures.leavesTheSendAsItself(TurnkeyHttpError.StamperNotInitialized())).isTrue()
        assertThat(TurnkeyErrorMapping.turnkeyHttpStatus(rejected)).isNull()
        assertThat(TurnkeySendFailures.activityFailure(rejected.activity, "f")!!.cause?.message).isEqualTo("denied")
    }

    @Test
    fun `an unknown-fate send is a TransactionOutcomeUnknown that carries the vendor's message but not its exception`() {
        val vendor = RuntimeException("HTTP error from /public/v1/query/get_activity: 401")

        val error = TurnkeySendFailures.unknownFate(vendor)

        assertThat(error).isInstanceOf(RainError.TransactionOutcomeUnknown::class.java)
        assertThat(error.code).isEqualTo("RAIN_305")
        assertThat(error.cause).isNull()
        assertThat(error.message).contains("HTTP error from /public/v1/query/get_activity: 401")
        assertThat(error.message).contains("the send may still land")
        assertThat(TurnkeyErrorMapping.turnkeyHttpStatus(error)).isNull()
    }

    /** The two endings a host must tell apart carry different codes, not only different messages. */
    @Test
    fun `an unknown-fate send and a settled activity failure carry different codes`() {
        val failed = solanaActivity(V1ActivityStatus.ACTIVITY_STATUS_FAILED, "policy engine denied the request")
        val gateway = RuntimeException("HTTP error calling ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2 request\nError: {}\nCode: 504")

        val settled = TurnkeySendFailures.activityFailure(failed, "fallback")!!
        val unknown = TurnkeySendFailures.unknownFate(gateway)

        assertThat(settled).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(settled.code).isEqualTo("RAIN_501")
        assertThat(unknown.code).isEqualTo("RAIN_305")
    }

    @Test
    fun `an unknown-fate send's message stops before the input kotlinx quotes`() {
        val decode = kotlinx.serialization.SerializationException(
            "Unexpected JSON token at offset 12\nJSON input: {\"activity\":{\"id\":\"act-secret\"}}"
        )

        val error = TurnkeySendFailures.unknownFate(decode)

        assertThat(error.message).contains("Unexpected JSON token at offset 12")
        assertThat(error.message).doesNotContain("JSON input")
        assertThat(error.message).doesNotContain("act-secret")
        assertThat(RuntimeException().vendorMessage()).isEqualTo("RuntimeException")
        assertThat(RuntimeException("x".repeat(400)).vendorMessage()).hasLength(TurnkeySendFailures.MAX_VENDOR_MESSAGE_LENGTH)
    }

    /** A 5xx answer to the submit carries the response body in the vendor's message; the host gets status and target only. */
    @Test
    fun `an unknown-fate send after a 5xx submit answer names the status, never the response body`() {
        val gateway = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2 request\nError: {\"marker\":\"gateway-body-7f3a\"}\nCode: 504"
        )

        val error = TurnkeySendFailures.unknownFate(gateway)

        assertThat(error.message).contains("ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2: 504")
        assertThat(error.message).doesNotContain("gateway-body-7f3a")
        assertThat(TurnkeyErrorMapping.turnkeyHttpStatus(error)).isNull()
    }

    @Test
    fun `a pending or completed activity is not a failure`() {
        assertThat(TurnkeySendFailures.activityFailure(solanaActivity(V1ActivityStatus.ACTIVITY_STATUS_PENDING, null), "f")).isNull()
        assertThat(TurnkeySendFailures.activityFailure(solanaActivity(V1ActivityStatus.ACTIVITY_STATUS_COMPLETED, null), "f")).isNull()
    }
}
