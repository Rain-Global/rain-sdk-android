package com.rain.sdk.privy

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.privy.auth.AuthenticationException
import io.privy.network.ApiResult
import io.privy.network.NoNetworkException
import io.privy.network.PrivyApiException
import io.privy.network.toResult
import io.privy.wallet.EmbeddedWalletException
import kotlinx.coroutines.CancellationException
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Vendor-error classification. The Privy Kotlin SDK carries no structured reason codes, so
 * classification is by exception type + message, with rejection and funds-shortfall prose held
 * to core's two-word standard: a lone "denied" or "cancelled" does not classify.
 */
class PrivyErrorMappingTest {

    // ---- authentication ------------------------------------------------------------

    @Test
    fun `not-logged-in authentication failure maps to TokenExpired`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            AuthenticationException("User must be authenticated before calling refresh.")
        )
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `invalid JWT authentication failure maps to TokenExpired`() {
        val mapped = PrivyErrorMapping.mapOrNull(AuthenticationException("Invalid JWT provided"))
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `expired session that also mentions cancellation maps to TokenExpired, not UserRejected`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            AuthenticationException("session expired, request cancelled")
        )
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `cancelled authentication maps to UserRejected`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            AuthenticationException("Passkey prompt cancelled by user")
        )
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `other authentication failures map to ProviderError`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            AuthenticationException("App URL scheme must be provided")
        )
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    // ---- embedded wallet -------------------------------------------------------------

    @Test
    fun `user rejection during a wallet request maps to UserRejected`() {
        for (message in listOf(
            "User rejected the request",
            "Request denied by user",
            "Signing cancelled by the user",
            "MetaMask Tx Signature: User denied transaction signature.",
            "RPC Error: code 4001"
        )) {
            val mapped = PrivyErrorMapping.mapOrNull(EmbeddedWalletException(message))
            assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
        }
    }

    @Test
    fun `a single rejection word during a wallet request is not a UserRejected`() {
        // Same standard as core: "Transaction cancelled" is a chain outcome, not the user
        // declining a prompt.
        for (message in listOf("Request denied", "Signing cancelled", "Rejected: nonce too low")) {
            val mapped = PrivyErrorMapping.mapOrNull(EmbeddedWalletException(message))
            assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        }
    }

    @Test
    fun `insufficient-funds wallet failure maps to InsufficientFunds`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            EmbeddedWalletException("RPC error: insufficient funds for gas * price + value")
        )
        assertThat(mapped).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `missing wallet maps to WalletUnavailable`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            EmbeddedWalletException("User doesn't have an embedded wallet.")
        )
        assertThat(mapped).isInstanceOf(RainError.WalletUnavailable::class.java)
    }

    @Test
    fun `wallet creation failure maps to WalletUnavailable`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            EmbeddedWalletException("Failed to create embedded wallet.")
        )
        assertThat(mapped).isInstanceOf(RainError.WalletUnavailable::class.java)
    }

    @Test
    fun `other wallet failures map to ProviderError`() {
        val mapped = PrivyErrorMapping.mapOrNull(
            EmbeddedWalletException("RPC response missing result")
        )
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    // ---- network ------------------------------------------------------------------

    /** The vendor's confirmed-offline answer to an API call, built the way its `toResult()` builds it. */
    private fun offlineApiCall(): Throwable =
        checkNotNull(ApiResult.Error.NoNetworkError.toResult().exceptionOrNull())

    /** The vendor's status-less wrapper for any other exception the HTTP client raised. */
    private fun wrapped(cause: Throwable) = PrivyApiException(null, null, "Something went wrong", cause)

    @Test
    fun `a status-less API failure is left to map, which reads it by the call it failed on`() {
        // privy-core 0.15.0 reports a request that got no answer as a status-less PrivyApiException.
        // The typed mapping stays out of it: only the call site knows whether the request left.
        assertThat(PrivyErrorMapping.mapOrNull(offlineApiCall())).isNull()
        assertThat(PrivyErrorMapping.mapOrNull(wrapped(ConnectException("No route to host")))).isNull()
    }

    @Test
    fun `the vendor's confirmed-offline answer maps to NetworkError on any call`() {
        // An API call on a device the vendor can confirm offline comes back as
        // ApiResult.Error.NoNetworkError, which toResult() wraps as a status-less PrivyApiException
        // carrying the same sentence; a wallet call fails with NoNetworkException itself.
        for (send in listOf(false, true)) {
            assertThat(PrivyErrorMapping.map(offlineApiCall(), send))
                .isInstanceOf(RainError.NetworkError::class.java)
            assertThat(PrivyErrorMapping.map(NoNetworkException, send))
                .isInstanceOf(RainError.NetworkError::class.java)
        }
    }

    @Test
    fun `a failure before the request left the device maps to NetworkError on a send too`() {
        for (cause in listOf<Throwable>(
            ConnectException("Failed to connect to api.privy.io"),
            UnknownHostException("api.privy.io"),
            NoRouteToHostException("No route to host"),
            ConnectTimeoutException("Connect timeout has expired"),
        )) {
            val failure = wrapped(cause)
            val mapped = PrivyErrorMapping.map(failure, send = true)
            assertThat(mapped).isInstanceOf(RainError.NetworkError::class.java)
            assertThat(mapped.cause).isSameInstanceAs(failure)
            assertThat(PrivyErrorMapping.map(cause, send = true)).isInstanceOf(RainError.NetworkError::class.java)
        }
    }

    @Test
    fun `a connect failure after the client resent the request is an unknown fate on a send`() {
        // OkHttp resends a request after a recoverable send-time failure and, when the retry cannot
        // connect, throws that failure with the earlier one attached as suppressed.
        val resent = UnknownHostException("api.privy.io").apply { addSuppressed(SocketException("Connection reset")) }
        assertThat(PrivyErrorMapping.map(wrapped(resent), send = true)).isInstanceOf(RainError.TransactionOutcomeUnknown::class.java)
        assertThat(PrivyErrorMapping.map(wrapped(resent), send = false)).isInstanceOf(RainError.NetworkError::class.java)
        // Two connect failures in a row never left the device.
        val neverLeft = UnknownHostException("api.privy.io").apply { addSuppressed(ConnectException("Failed to connect")) }
        assertThat(PrivyErrorMapping.map(wrapped(neverLeft), send = true)).isInstanceOf(RainError.NetworkError::class.java)
    }

    @Test
    fun `an unknown fate names the failure kind, never a class, and carries no cause`() {
        val resent = UnknownHostException("api.privy.io").apply { addSuppressed(SocketException("Connection reset")) }
        val onResend = PrivyErrorMapping.map(wrapped(resent), send = true)
        assertThat(onResend.message).contains("resent")
        assertThat(onResend.message).doesNotContain("UnknownHostException")
        assertThat(onResend.cause).isNull()
        assertThat(PrivyErrorMapping.map(wrapped(SocketTimeoutException("timeout")), send = true).message)
            .contains("timed out")
        assertThat(PrivyErrorMapping.map(wrapped(IllegalStateException("503")), send = true).message)
            .contains("server error")
    }

    @Test
    fun `a transport failure after the request left is NetworkError off a send and TransactionOutcomeUnknown on one`() {
        // A read timeout or a reset may follow a broadcast Privy already made inside the request,
        // so on a send it is RAIN_305, never the "retry" that RAIN_301 means nor the settled RAIN_501.
        for (cause in listOf<Throwable>(SocketTimeoutException("timeout"), IOException("connection reset"))) {
            val failure = wrapped(cause)
            assertThat(PrivyErrorMapping.map(failure, send = false)).isInstanceOf(RainError.NetworkError::class.java)
            assertThat(PrivyErrorMapping.map(cause, send = false)).isInstanceOf(RainError.NetworkError::class.java)
            val onSend = PrivyErrorMapping.map(failure, send = true)
            assertThat(onSend).isInstanceOf(RainError.TransactionOutcomeUnknown::class.java)
            assertThat(onSend.cause).isNull()
            assertThat(PrivyErrorMapping.map(cause, send = true)).isInstanceOf(RainError.TransactionOutcomeUnknown::class.java)
        }
    }

    @Test
    fun `a status-less failure that is not the network is ProviderError off a send and TransactionOutcomeUnknown on one`() {
        // A response the vendor could not decode, and a 5xx, which ktor raises as a
        // ServerResponseException (an IllegalStateException) and the vendor's catch-all reports
        // status-less: neither is a transport failure, but both answer a request that left, so a
        // send reads them as an unknown fate.
        for (cause in listOf<Throwable>(
            IllegalStateException("no body"),
            IllegalStateException("Server error(https://api.privy.io/v1/wallets/clxyz/rpc: 503 Service Unavailable"),
        )) {
            for (send in listOf(false, true)) {
                val failure = wrapped(cause)
                val mapped = PrivyErrorMapping.map(failure, send)
                assertThat(mapped).isInstanceOf(expectedStatusLess(send))
                if (send) assertThat(mapped.cause).isNull() else assertThat(mapped.cause).isSameInstanceAs(failure)
            }
        }
    }

    @Test
    fun `an HTTP answer is never a network failure, whatever caused the client to throw`() {
        assertThat(PrivyErrorMapping.mapOrNull(PrivyApiException(401, null, "Unauthorized", IOException("x"))))
            .isInstanceOf(RainError.TokenExpired::class.java)
        val answered = PrivyApiException(404, null, "Not found", IOException("x"))
        assertThat(PrivyErrorMapping.mapOrNull(answered)).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(PrivyErrorMapping.isNetworkFailure(answered)).isFalse()
        assertThat(PrivyErrorMapping.answerLost(answered)).isFalse()
    }

    /** A status-less failure that is not the network: a provider failure off a send, an unknown fate on one. */
    private fun expectedStatusLess(send: Boolean): Class<out RainError> =
        if (send) RainError.TransactionOutcomeUnknown::class.java else RainError.ProviderError::class.java

    @Test
    fun `a cancellation the vendor wrapped is found in the chain`() {
        val cancel = CancellationException("caller went away")
        assertThat(wrapped(cancel).cancellationInChain()).isSameInstanceAs(cancel)
        assertThat(IOException("x").cancellationInChain()).isNull()
    }

    // ---- API ----------------------------------------------------------------------

    @Test
    fun `a 400 from the wallet RPC that names a funds shortfall maps to InsufficientFunds`() {
        // Privy's answer to 0.001 SOL from a wallet holding 0 SOL on 2026-10-02 (beta QA PV-SEND-01).
        val mapped = PrivyErrorMapping.mapOrNull(
            apiException(
                400,
                "Error broadcasting transaction with message: Error: Transaction simulation failed: " +
                    "Attempt to debit an account but found no record of a prior credit."
            )
        )
        assertThat(mapped).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `a 400 with any other message maps to ProviderError, a relayed rejection marker included`() {
        // A server wallet never prompts, so "code 4001" in relayed node text is not the user.
        for (message in listOf("Invalid transaction encoding", "RPC Error: code 4001", "Request denied by user")) {
            val failure = apiException(400, message)
            val mapped = PrivyErrorMapping.mapOrNull(failure)
            assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
            assertThat(mapped!!.cause).isSameInstanceAs(failure)
        }
    }

    @Test
    fun `a 400 whose body relays a revert maps to TransactionSimulationFailed before the funds read`() {
        // The preflight's order: a revert that also names a balance is a simulation failure, never
        // a native shortfall with null amounts. Whether Privy's wallet API relays a decoded revert
        // is not confirmed on a device.
        for (message in listOf(
            "execution reverted: insufficient balance",
            "Error broadcasting transaction with message: execution reverted: ERC20InsufficientBalance(0xabc, 0, 1)",
        )) {
            val failure = apiException(400, message)
            val mapped = PrivyErrorMapping.mapOrNull(failure)
            assertThat(mapped).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
            assertThat(mapped!!.cause).isSameInstanceAs(failure)
        }
    }

    @Test
    fun `a failure with no HTTP status never reads its cause for a funds verdict`() {
        // privy-core 0.15.0 keeps a status and the body text only for a 4xx. A request that got no
        // answer, a 3xx and a 5xx arrive as a status-less PrivyApiException with the vendor's fixed
        // sentence and the client's exception as the cause, so the funds read never sees a cause
        // that names a shortfall. Asserted through map, the adapter's total mapping, because the
        // Privy-type branch hands a status-less shape on to the network rule, which reads it by the
        // call it failed on, so both kinds of call are asserted.
        for (cause in listOf(
            IllegalStateException("no answer"),
            IllegalStateException("503 Service Unavailable"),
            IllegalStateException("EVM error: OutOfFunds"),
        )) {
            for (send in listOf(false, true)) {
                val failure = PrivyApiException(null, null, "Something went wrong", cause)
                val mapped = PrivyErrorMapping.map(failure, send)
                assertThat(mapped).isInstanceOf(expectedStatusLess(send))
                if (send) assertThat(mapped.cause).isNull() else assertThat(mapped.cause).isSameInstanceAs(failure)
            }
        }
    }

    @Test
    fun `a 401 maps to TokenExpired whatever its message says`() {
        val mapped = PrivyErrorMapping.mapOrNull(apiException(401, "insufficient funds"))
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    private fun apiException(status: Int, message: String) =
        PrivyApiException(status, null, message, RuntimeException(message))

    // ---- fallthrough --------------------------------------------------------------

    @Test
    fun `non-Privy exceptions stay unmapped so they bubble raw`() {
        assertThat(PrivyErrorMapping.mapOrNull(IllegalStateException("user rejected"))).isNull()
        assertThat(PrivyErrorMapping.mapOrNull(RuntimeException("insufficient funds"))).isNull()
    }
}
