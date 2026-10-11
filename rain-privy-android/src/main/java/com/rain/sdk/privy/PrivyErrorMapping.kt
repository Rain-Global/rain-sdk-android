package com.rain.sdk.privy

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.error.VendorErrorClassifier
import io.privy.auth.AuthenticationException
import io.privy.network.ApiResult
import io.privy.network.NoNetworkException
import io.privy.network.PrivyApiException
import io.privy.wallet.EmbeddedWalletException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/**
 * Classifies Privy vendor exceptions into specific [RainError] cases at the adapter boundary:
 * - authentication failures (not logged in / invalid or expired JWT) map to [RainError.TokenExpired]
 * - user cancellation / rejection maps to [RainError.UserRejected]
 * - a funds shortfall in a node or wallet-RPC message ("insufficient funds", "found no record of
 *   a prior credit") maps to [RainError.InsufficientFunds], and a revert a wallet-API answer
 *   relays maps to [RainError.TransactionSimulationFailed]
 * - missing wallet / failed wallet creation maps to [RainError.WalletUnavailable]
 * - a request that got no answer from the network maps to [RainError.NetworkError] when nothing
 *   was broadcast, and to [RainError.TransactionOutcomeUnknown] when a send may have been, see [map]
 * - any other Privy exception maps to [RainError.ProviderError]
 *
 * The Privy Kotlin SDK carries no structured reason codes on its exceptions
 * ([AuthenticationException] / [EmbeddedWalletException] are message-only), so wallet and RPC
 * failures classify by message. Rejection and funds-shortfall prose goes through core's
 * [VendorErrorClassifier] so Privy is held to the same standard as every other vendor.
 *
 * Non-Privy exceptions return `null` from [mapOrNull] and keep bubbling raw inside the adapter.
 * [map], which [PrivySessionCoordinator] applies to everything that leaves the adapter, reads them
 * for a network failure and then by the shared prose rules, and floors at
 * [RainError.ProviderError], so a user rejection or funds shortfall in a node message still
 * classifies rather than hiding behind a generic error.
 */
internal object PrivyErrorMapping {

    /**
     * The total mapping for every failure that leaves the adapter. A [RainError] passes through;
     * a Privy exception maps by type via [mapOrNull]; a request the network did not answer maps by
     * what the call was; anything else is read by the shared prose rules before flooring at
     * [RainError.ProviderError].
     *
     * [send] says whether the call that failed broadcasts: a send does, a read, a session refresh
     * and a signature do not. On a call that does not broadcast every transport failure is a
     * [RainError.NetworkError] ([isNetworkFailure]), the code hosts read as "retry, nothing was
     * broadcast". On a send only a failure that provably happened before any request left the
     * device is ([neverReachedPrivy]): Privy signs and broadcasts a send inside the one request
     * that carried it, so any failure after a request for it left may follow a broadcast, and a
     * host that retried on `RAIN_301` would send twice. A transport failure after that (a timeout,
     * a reset), an answer the vendor reports status-less (a 5xx, one it could not read; only a 4xx
     * keeps its status, see [isNetworkFailure]) and a send the HTTP client resent after such a
     * failure and then could not connect for leave as [RainError.TransactionOutcomeUnknown]
     * ([answerLost]), built without the vendor exception as its cause, as the Turnkey provider's
     * is, and with the failure kind in its message: the send may still land, so the host reads
     * history or the balance before sending again. The request that failed is the send's own, or
     * the signing-key fetch the vendor runs before the first send after a login on its
     * server-wallet stack; the session refresh it also runs there is made first, as its own call,
     * by [PrivySessionCoordinator.executeSend]. A 4xx is a refusal, mapped by type before this
     * rule, and nothing moved.
     */
    fun map(e: Throwable, send: Boolean): RainError =
        e as? RainError
            ?: mapOrNull(e)
            ?: mapNetworkOrNull(e, send)
            ?: VendorErrorClassifier.fromVendorError(e)
            ?: RainError.ProviderError(e)

    /**
     * The typed mapping of a Privy exception, or null for anything else. A status-less
     * [PrivyApiException], the shape the vendor gives a request that got no answer, returns null
     * too: only the call site knows whether that request may have left the device, so [map]
     * decides with its `send` argument.
     */
    fun mapOrNull(e: Throwable): RainError? = when (e) {
        is AuthenticationException -> mapAuthenticationException(e)
        is EmbeddedWalletException -> mapEmbeddedWalletException(e)
        is PrivyApiException -> mapApiException(e)
        else -> null
    }

    /**
     * Whether [e], or a cause of it, is a request that never reached Privy or got no answer. The
     * vendor (privy-core 0.15.0) reports those two ways: a device it can confirm offline is refused
     * before the request, as [NoNetworkException] on wallet calls and as a [PrivyApiException]
     * with no HTTP status and `ApiResult.Error.NoNetworkError`'s one `Throwable` as its cause on
     * API calls (`toResult` passes it through); a transport failure is the [IOException] the HTTP
     * client threw, bare or as the cause of a status-less [PrivyApiException]
     * (`ApiResult.Error.GenericError`). An
     * HTTP answer is not a network failure, whatever the client threw underneath it, so the walk
     * stops at a [PrivyApiException] that carries a status. Only a 4xx carries one: the vendor's
     * `requestCatching` keeps the status of ktor's `ClientRequestException` and reports a 3xx, a
     * 5xx and every other exception status-less, with that exception as the cause, which is not
     * an [IOException] for an answered request.
     */
    fun isNetworkFailure(e: Throwable): Boolean = networkVerdict(e)?.isNoAnswer() == true

    /**
     * Whether [e] is a network failure that provably happened before the request left the device:
     * the vendor's confirmed-offline refusal, no DNS answer, no route, a refused connection or a
     * connect timeout, with nothing else behind it. A timeout or a reset after the request left is
     * a network failure too, but its fate is unknown, so it is not one of these. Nor is a connect
     * failure that followed one: the HTTP client (ktor over OkHttp, `retryOnConnectionFailure` on)
     * resends a request after a recoverable send-time failure such as a connection reset and, when
     * the retry then fails to connect, throws that last failure with the earlier ones attached as
     * suppressed, so every suppressed failure on the chain has to be a before-request one too.
     * Safe to retry on any call.
     */
    fun neverReachedPrivy(e: Throwable): Boolean {
        val verdict = networkVerdict(e) ?: return false
        return verdict.isBeforeRequest() &&
            e.causeChain().flatMap { it.suppressed.asSequence() }.all { it.isBeforeRequest() }
    }

    /** The first element of the cause chain that settles the network question, or null. */
    private fun networkVerdict(e: Throwable): Throwable? =
        e.causeChain().firstOrNull { it.isHttpAnswer() || it.isNoAnswer() }

    /**
     * Whether [e], or a cause of it, shows that a request made for the call left the device and
     * its answer was lost or unreadable: a transport failure ([isNetworkFailure]), or the vendor's
     * status-less wrapper, which only its request helper puts around the failure of a request it
     * made. The vendor's confirmed-offline refusal wears the same wrapper but is refused before any
     * request, so a send reads [neverReachedPrivy] first.
     */
    fun answerLost(e: Throwable): Boolean =
        isNetworkFailure(e) || e.causeChain().any { it is PrivyApiException && it.statusCode == null }

    private fun mapNetworkOrNull(e: Throwable, send: Boolean): RainError? = when {
        !send -> if (isNetworkFailure(e)) RainError.NetworkError(e.message, e) else null
        neverReachedPrivy(e) -> RainError.NetworkError(e.message, e)
        answerLost(e) -> RainError.TransactionOutcomeUnknown(
            "A request made for the send got no usable answer after it left the device: " +
                "${e.lostAnswerKind()}. The send may have been broadcast and may still land; read " +
                "history or the balance before sending again"
        )
        else -> null
    }

    private fun mapAuthenticationException(e: AuthenticationException): RainError {
        val lower = e.message?.lowercase().orEmpty()
        val classified = VendorErrorClassifier.fromVendorMessage(e.message)
        return when {
            // Token-expiry keywords win over rejection phrases: an expired session aborts the
            // in-flight request too ("session expired, request cancelled"), and TokenExpired is
            // the actionable classification there.
            lower.contains("not logged in") ||
                lower.contains("not authenticated") ||
                lower.contains("must be authenticated") ||
                lower.contains("jwt") ||
                lower.contains("unauthorized") ||
                lower.contains("expired") ||
                lower.contains("session") -> RainError.TokenExpired()
            // Only a rejection: an auth failure never reports a funds shortfall.
            classified is RainError.UserRejected -> classified
            else -> RainError.ProviderError(e)
        }
    }

    private fun mapEmbeddedWalletException(e: EmbeddedWalletException): RainError {
        VendorErrorClassifier.fromVendorMessage(e.message)?.let { return it }
        val lower = e.message?.lowercase().orEmpty()
        return when {
            lower.contains("not logged in") ||
                lower.contains("not authenticated") ||
                lower.contains("jwt") ||
                lower.contains("unauthorized") -> RainError.TokenExpired()
            // "User doesn't have an embedded wallet.", "Failed to create embedded wallet.",
            // "Primary wallet for entropy is null", ...
            lower.contains("have an embedded wallet") ||
                lower.contains("no wallet") ||
                lower.contains("wallet for entropy is null") ||
                lower.contains("failed to create") ||
                lower.contains("creation failed") -> RainError.WalletUnavailable(
                "Privy: ${e.message}"
            )
            else -> RainError.ProviderError(e)
        }
    }

    /**
     * An HTTP answer from Privy's API, or a request that got none, which the vendor reports through
     * the same type with no status, on any wallet-API call (a send, a signature, history). No status
     * is left to [map], which knows whether the request may have left. 401 and 403 are the session,
     * whatever the body says. Any other 4xx, the one shape the vendor keeps a status and the
     * response body's `error` text for, is read the way the preflight reads a node answer before it
     * floors at [RainError.ProviderError]: a revert first, as [RainError.TransactionSimulationFailed],
     * then a funds shortfall. For a send Privy's server wallet refused the text is the node's own
     * sentence ("Error broadcasting transaction with message: Error: Transaction simulation failed:
     * Attempt to debit an account but found no record of a prior credit."). Only those two verdicts
     * are asked for, because a server wallet never prompts the user, so a rejection marker in
     * relayed text is noise.
     */
    private fun mapApiException(e: PrivyApiException): RainError? = when (e.statusCode) {
        null -> null
        401, 403 -> RainError.TokenExpired()
        else -> relayedNodeVerdict(e) ?: RainError.ProviderError(e)
    }

    /** The preflight's reading of a node answer, for text a 4xx relays: a revert, then a funds shortfall. */
    private fun relayedNodeVerdict(e: PrivyApiException): RainError? = when {
        e.message?.contains("revert", ignoreCase = true) == true -> RainError.TransactionSimulationFailed(e)
        else -> VendorErrorClassifier.insufficientFundsOrNull(e.message)
    }
}

private fun Throwable.isHttpAnswer(): Boolean = this is PrivyApiException && statusCode != null

private fun Throwable.isNoAnswer(): Boolean =
    this is NoNetworkException ||
        this is IOException ||
        isVendorOfflineAnswer()

/**
 * The vendor's confirmed-offline answer to an API call: `ApiResult.Error.NoNetworkError` through
 * `toResult()`, which passes that data object's one `Throwable` as the cause, so the identity
 * check survives a rewording of either message.
 */
private fun Throwable.isVendorOfflineAnswer(): Boolean =
    this is PrivyApiException && cause === ApiResult.Error.NoNetworkError.exception

/**
 * The failures the HTTP client raises before any byte of the request has left the device. ktor's
 * connect-timeout exception extends [ConnectException], so it is covered.
 */
private val BEFORE_REQUEST_FAILURES = listOf(
    NoNetworkException::class,
    UnknownHostException::class,
    NoRouteToHostException::class,
    ConnectException::class,
)

private fun Throwable.isBeforeRequest(): Boolean =
    BEFORE_REQUEST_FAILURES.any { it.isInstance(this) } || isVendorOfflineAnswer()

/**
 * What happened to a request whose answer was lost, for the message of a
 * [RainError.TransactionOutcomeUnknown]: named by the event, never by a class, which a minified
 * host may have renamed.
 */
private fun Throwable.lostAnswerKind(): String {
    val chain = causeChain().toList()
    return when {
        chain.last().isBeforeRequest() ->
            "the HTTP client resent the request after a failure and then could not connect"
        chain.any { it is InterruptedIOException || it.javaClass.simpleName.contains("Timeout") } ->
            "the request timed out"
        chain.any { it is IOException } -> "the connection failed"
        else -> "the answer was a server error or could not be read"
    }
}
