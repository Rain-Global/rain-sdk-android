package com.rain.sdk.internal.error

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Unit tests for [ErrorMapper]: the entry points (signing vs transaction), the shared prose
 * heuristics for user rejection and funds shortfall, and the ProviderError floor. Vendor-typed
 * classification lives in the adapter modules and is tested there.
 */
class ErrorMapperTest {

    private val mapper = ErrorMapper()

    // ---- mapSigningError -----------------------------------------------------------

    // The standard: a message classifies only on a phrase of at least two words, or on the
    // EIP-1193 code 4001. A lone "rejected" / "cancelled" / "insufficient" is not enough.

    @Test
    fun `mapSigningError returns UserRejected for a two-word rejection phrase`() {
        for (message in listOf(
            "User rejected the request",
            "User denied transaction signature",
            "User cancelled signing",
            "User canceled signing",
            "User declined the request",
            "Signature rejected by user",
            "Request denied by user",
            "Transaction cancelled by user",
            "Request denied by the user"
        )) {
            val mapped = mapper.mapSigningError(RuntimeException(message))
            assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
        }
    }

    @Test
    fun `mapSigningError returns UserRejected for the EIP-1193 code 4001`() {
        for (message in listOf("code: 4001, message: nope", "RPC error [4001]", "Provider error (4001)")) {
            val mapped = mapper.mapSigningError(RuntimeException(message))
            assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
        }
    }

    @Test
    fun `mapSigningError does not classify on a single word`() {
        for (message in listOf(
            "User doesn't have an embedded wallet",
            "Transaction cancelled",
            "request was denied",
            "Rejected: nonce too low",
            "insufficient permissions for this operation",
            "nonce 4001 too low"
        )) {
            val mapped = mapper.mapSigningError(RuntimeException(message))
            assertThat(mapped).isNotInstanceOf(RainError.UserRejected::class.java)
            assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        }
    }

    @Test
    fun `mapSigningError classifies a vendor exception whose type name says the user rejected`() {
        // Vendors often spell the reason only in the class and leave the message generic.
        assertThat(mapper.mapSigningError(UserRejectedRequestException()))
            .isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `InsufficientFunds from vendor prose carries no amounts`() {
        val mapped = mapper.mapTransactionError(RuntimeException("insufficient funds for gas * price + value"))

        val error = mapped as RainError.InsufficientFunds
        assertThat(error.required).isNull()
        assertThat(error.available).isNull()
        assertThat(error.message).contains("required unknown, available unknown")
    }

    @Test
    fun `mapSigningError returns InsufficientFunds for a two-word funds phrase`() {
        for (message in listOf(
            "insufficient funds for gas * price + value",
            "Insufficient balance for transfer",
            "Transfer: insufficient lamports 100, need 5000",
            "Attempt to debit an account but found no record of a prior credit.",
            // Base Sepolia's node on a native send over the balance; the camel case is split.
            "RPC error [-32003]: EVM error: OutOfFunds"
        )) {
            val mapped = mapper.mapSigningError(RuntimeException(message))
            assertThat(mapped).isInstanceOf(RainError.InsufficientFunds::class.java)
        }
    }

    @Test
    fun `a gas exhaustion is not a funds shortfall, even though the camel case is split the same way`() {
        val mapped = mapper.mapSigningError(RuntimeException("RPC error [-32003]: EVM error: OutOfGas"))
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(VendorErrorClassifier.insufficientFundsOrNull("EVM error: OutOfGas")).isNull()
        assertThat(VendorErrorClassifier.insufficientFundsOrNull("EVM error: OutOfFunds"))
            .isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `mapSigningError prefers user-rejection over insufficient-funds heuristic`() {
        val mapped = mapper.mapSigningError(
            RuntimeException("User rejected: insufficient funds warning shown")
        )
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `mapSigningError wraps generic exception as ProviderError`() {
        val cause = IllegalStateException("boom")
        val mapped = mapper.mapSigningError(cause)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapped.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `mapSigningError wraps exception with null message as ProviderError`() {
        val mapped = mapper.mapSigningError(IOException())
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    // ---- mapTransactionError -------------------------------------------------------

    @Test
    fun `mapTransactionError returns InsufficientFunds when message says insufficient funds`() {
        val mapped = mapper.mapTransactionError(RuntimeException("Insufficient funds for gas"))
        assertThat(mapped).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `mapTransactionError matches the funds phrase case-insensitively`() {
        val mapped = mapper.mapTransactionError(RuntimeException("INSUFFICIENT BALANCE for transfer"))
        assertThat(mapped).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `mapTransactionError does not treat coroutine cancellation as a rejection`() {
        val mapped = mapper.mapTransactionError(CancellationException("User cancelled the job"))
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `mapTransactionError prefers user-rejection over insufficient-funds heuristic`() {
        // Order of checks in ErrorMapper.mapTransactionError: user rejection first,
        // then insufficient. This protects "user cancelled" UX paths.
        val mapped = mapper.mapTransactionError(
            RuntimeException("User cancelled the transaction")
        )
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `a vendor-shaped HTTP status message is a plain ProviderError in core`() {
        // Status parsing is the adapters' job now; core reads no vendor message shapes.
        val e = RuntimeException("HTTP error from /public/v1/query/get_activity: 401")
        val mapped = mapper.mapTransactionError(e)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapped.cause).isSameInstanceAs(e)
    }

    @Test
    fun `mapTransactionError wraps unknown error as ProviderError`() {
        val cause = IllegalArgumentException("RPC error -32603")
        val mapped = mapper.mapTransactionError(cause)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapped.cause).isSameInstanceAs(cause)
    }

    /** A vendor exception that names the reason only in its type, as some SDKs do. */
    private class UserRejectedRequestException : RuntimeException("request failed")
}
