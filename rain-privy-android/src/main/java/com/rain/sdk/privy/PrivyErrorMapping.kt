package com.rain.sdk.privy

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.error.VendorErrorClassifier
import io.privy.auth.AuthenticationException
import io.privy.network.PrivyApiException
import io.privy.wallet.EmbeddedWalletException

/**
 * Classifies Privy vendor exceptions into specific [RainError] cases at the adapter boundary:
 * - authentication failures (not logged in / invalid or expired JWT) map to [RainError.TokenExpired]
 * - user cancellation / rejection maps to [RainError.UserRejected]
 * - a funds shortfall in a node or wallet-RPC message ("insufficient funds", "found no record of
 *   a prior credit") maps to [RainError.InsufficientFunds], and a revert a wallet-API answer
 *   relays maps to [RainError.TransactionSimulationFailed]
 * - missing wallet / failed wallet creation maps to [RainError.WalletUnavailable]
 * - any other Privy exception maps to [RainError.ProviderError]
 *
 * The Privy Kotlin SDK carries no structured reason codes on its exceptions
 * ([AuthenticationException] / [EmbeddedWalletException] are message-only), so wallet and RPC
 * failures classify by message. Rejection and funds-shortfall prose goes through core's
 * [VendorErrorClassifier] so Privy is held to the same standard as every other vendor.
 *
 * Non-Privy exceptions return `null` from [mapOrNull] and keep bubbling raw inside the adapter.
 * [map], which [PrivySessionCoordinator] applies to everything that leaves the adapter, reads them
 * by the shared prose rules and floors at [RainError.ProviderError], so a user rejection or funds
 * shortfall in a node message still classifies rather than hiding behind a generic error.
 */
internal object PrivyErrorMapping {

    /**
     * The total mapping for every failure that leaves the adapter: a [RainError] passes through,
     * a Privy exception maps by type via [mapOrNull], and anything else is read by the shared
     * prose rules before flooring at [RainError.ProviderError].
     */
    fun map(e: Throwable): RainError =
        e as? RainError
            ?: mapOrNull(e)
            ?: VendorErrorClassifier.fromVendorError(e)
            ?: RainError.ProviderError(e)

    fun mapOrNull(e: Throwable): RainError? = when (e) {
        is AuthenticationException -> mapAuthenticationException(e)
        is EmbeddedWalletException -> mapEmbeddedWalletException(e)
        is PrivyApiException -> mapApiException(e)
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
     * A failure from Privy's API, on any wallet-API call (a send, a signature, history). 401 and
     * 403 are the session, whatever the body says. Any other 4xx, the one shape the vendor keeps
     * a status and the response body's `error` text for, is read the way the preflight reads a
     * node answer before it floors at [RainError.ProviderError]: a revert first, as
     * [RainError.TransactionSimulationFailed], then a funds shortfall. For a send Privy's server
     * wallet refused the text is the node's own sentence ("Error broadcasting transaction with
     * message: Error: Transaction simulation failed: Attempt to debit an account but found no
     * record of a prior credit."). Only those two verdicts are asked for, because a server wallet
     * never prompts the user, so a rejection marker in relayed text is noise.
     */
    private fun mapApiException(e: PrivyApiException): RainError = when (e.statusCode) {
        401, 403 -> RainError.TokenExpired()
        else -> relayedNodeVerdict(e) ?: RainError.ProviderError(e)
    }

    /** The preflight's reading of a node answer, for text a 4xx relays: a revert, then a funds shortfall. */
    private fun relayedNodeVerdict(e: PrivyApiException): RainError? = when {
        e.message?.contains("revert", ignoreCase = true) == true -> RainError.TransactionSimulationFailed(e)
        else -> VendorErrorClassifier.insufficientFundsOrNull(e.message)
    }
}
