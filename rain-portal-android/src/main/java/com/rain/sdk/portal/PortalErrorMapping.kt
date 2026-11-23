package com.rain.sdk.portal

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.error.VendorErrorClassifier
import io.portalhq.android.exceptions.PortalException
import io.portalhq.android.utils.errors.PortalErrorCodes
import java.io.IOException

/**
 * Maps Portal vendor errors to specific [RainError] cases before generic wrapping. Mapping is
 * split by call path:
 * - [mapAuthOrNull] applies at every Portal call site:
 *   - HTTP 401 / unauthorized ([PortalException.Api.HttpUnauthorized], the only path token-expired
 *     errors reach the SDK through) maps to [RainError.TokenExpired].
 *   - MPC result errors carrying id 320 or `INVALID_API_KEY` map to [RainError.TokenExpired].
 * - [mapSimulationOrNull] applies only at send and fee-estimation call sites: auth mapping first,
 *   then JSON-RPC error code 3 ("execution reverted", not declared by the Portal SDK) or a node
 *   message naming an execution failure ("out of gas", "invalid opcode") maps to
 *   [RainError.TransactionSimulationFailed]. Read paths never map these (a revert reported
 *   during a read is not a failed simulation). Whether a simulation failure is a
 *   [RainError.WithdrawalRevertedByNetwork] is decided in core, on the withdrawal flows only.
 *
 * Anything else returns `null` from the `OrNull` functions, so call sites keep their existing
 * behavior (wrap in [RainError.ProviderError], or let the raw error flow to the session
 * coordinator, whose [map] is the last word before anything leaves the adapter).
 */
internal object PortalErrorMapping {

    /**
     * The total mapping [PortalSessionCoordinator] applies to every failure it does not refresh or
     * retry, so no vendor exception leaves this module. A [RainError] passes through; a Portal
     * auth failure maps by type; anything else is read by the shared prose rules and floors at
     * [RainError.ProviderError]. Revert detection stays at the send and fee-estimation call sites,
     * because only they know that a revert means a failed simulation.
     */
    fun map(e: Throwable): RainError =
        e as? RainError
            ?: mapAuthOrNull(e)
            ?: VendorErrorClassifier.fromVendorError(e)
            ?: RainError.ProviderError(e)

    /** Legacy MPC error id Portal's backend returns for an invalid API key. */
    private const val MPC_INVALID_API_KEY_LEGACY_CODE = 320

    /** JSON-RPC error code nodes return for "execution reverted". */
    private const val RPC_EXECUTION_REVERTED_CODE = 3

    /** Execution failures some nodes report under a generic code (-32000) rather than code 3. */
    private val SIMULATION_FAILURE_PHRASES = listOf("execution reverted", "out of gas", "invalid opcode")

    /** Auth-only mapping (401 / invalid API key to TokenExpired). Applies at every call site. */
    fun mapAuthOrNull(e: Throwable): RainError? = when (e) {
        is PortalException.Api.HttpUnauthorized -> RainError.TokenExpired()
        is PortalException.Mpc.MpcResultError -> mapMpcResultError(e)
        else -> null
    }

    /** A read's failure: auth first, one that got no answer as [RainError.NetworkError], the rest as [RainError.ProviderError]. */
    fun mapRead(e: Throwable): RainError =
        mapAuthOrNull(e) ?: if (isTransient(e)) RainError.NetworkError(cause = e) else RainError.ProviderError(e)

    /**
     * Auth mapping plus JSON-RPC code 3 to [RainError.TransactionSimulationFailed]. Applies only
     * where a revert means a failed simulation: `sendTransaction` and `estimateTransactionFee`.
     */
    fun mapSimulationOrNull(e: Throwable): RainError? =
        mapAuthOrNull(e) ?: (e as? PortalException.Api.RpcError)?.let { mapRpcError(it) }

    private fun mapMpcResultError(e: PortalException.Mpc.MpcResultError): RainError? {
        val code = e.id.toIntOrNull() ?: e.error.rawCode
        return if (
            code == MPC_INVALID_API_KEY_LEGACY_CODE ||
            code == PortalErrorCodes.INVALID_API_KEY.code ||
            e.id == PortalErrorCodes.INVALID_API_KEY.name
        ) {
            RainError.TokenExpired()
        } else {
            null
        }
    }

    private fun mapRpcError(e: PortalException.Api.RpcError): RainError? {
        val message = e.message.orEmpty()
        val executionFailed = e.code == RPC_EXECUTION_REVERTED_CODE ||
            SIMULATION_FAILURE_PHRASES.any { message.contains(it, ignoreCase = true) }
        return if (executionFailed) RainError.TransactionSimulationFailed(e) else null
    }

    /** Network I/O or HTTP 408/429/5xx; Portal only carries the status as a `"<status> - …"` prefix. */
    fun isTransient(e: Throwable): Boolean = when (e) {
        is IOException, is RainError.NetworkError -> true
        is PortalException.Api.HttpRequestFailed ->
            httpStatusOrNull(e)?.let(::isTransientStatus) == true ||
                e.message.orEmpty().contains(NO_HTTP_RESPONSE_MARKER)
        else -> false
    }

    fun httpStatusOrNull(e: PortalException.Api.HttpRequestFailed): Int? =
        e.message?.substringBefore(" - ", missingDelimiterValue = "")?.trim()?.toIntOrNull()

    private fun isTransientStatus(status: Int): Boolean =
        status == 408 || status == 429 || status in 500..599

    private const val NO_HTTP_RESPONSE_MARKER = "unable to receive a valid HTTP response"
}
