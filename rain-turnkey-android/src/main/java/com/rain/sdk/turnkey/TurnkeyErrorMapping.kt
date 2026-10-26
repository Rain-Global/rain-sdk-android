package com.rain.sdk.turnkey

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.DataError
import androidx.credentials.exceptions.domerrors.DomError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.error.VendorErrorClassifier
import com.turnkey.core.models.errors.TurnkeyKotlinError
import com.turnkey.passkey.utils.TurnkeyPasskeyError
import com.turnkey.stamper.utils.TurnkeyStamperError
import timber.log.Timber

/**
 * Turnkey's exception vocabulary, translated into the Rain error contract at the adapter boundary.
 *
 * [TurnkeySessionCoordinator] runs [map] on every failure it does not refresh or retry, so no
 * vendor exception leaves this module and core never sees a Turnkey type. Two properties of
 * [classify] matter, and the tests pin both:
 *
 * - It is **total** for every [TurnkeyKotlinError]. For a plain HTTP failure carrying the status
 *   in its message it claims only 401 (`TokenExpired`) and 403 (`Unauthorized`); any other status
 *   returns null on purpose so the shared prose rules can read the body, pinned by the test
 *   `an unclassified HTTP status falls through to the prose checks`.
 * - It is total for a [RainApiAuthFailure], the Rain client's refusal on a managed-auth leg, by
 *   the kind the client read off the status; see [mapRainApiAuthFailure].
 * - It returns null for everything else, which leaves the shared prose fallback in charge.
 *
 * A failed passkey ceremony is the one place the vendor's own type says nothing: the login and
 * sign-up wrappers, and the passkey package's own errors, carry the decisive Credential Manager
 * exception or HTTP failure two or three causes down, so [mapPasskeyCeremonyFailure] classifies
 * those by the innermost cause the chain carries. No session exists during a passkey login or
 * sign-up, so an HTTP 401 or 403 inside one is never `TokenExpired` or `Unauthorized`.
 */
@Suppress("TooManyFunctions") // one mapping per vendor failure shape; a split would scatter the single exit
internal object TurnkeyErrorMapping {

    /**
     * Every failure that leaves the adapter passes through here. A [RainError] is already the
     * contract and passes through; a Turnkey failure is classified by type, then by HTTP status;
     * anything else is read by the shared prose rules and floors at [RainError.ProviderError]. The
     * result leaves through [withoutResponseBody], the one place a vendor HTTP failure loses its
     * response body, so no branch below has to remember to.
     */
    fun map(e: Throwable): RainError {
        val mapped = e as? RainError
            ?: classify(e)
            ?: VendorErrorClassifier.fromVendorError(e)
            ?: RainError.ProviderError(e)
        return mapped.withoutResponseBody()
    }

    /**
     * A [RainError] for a Turnkey failure, null when the throwable is not one. The typed check
     * runs first, then the HTTP status parsed out of the message, because a typed signal beats
     * prose. The passkey and stamper packages have their own error hierarchies beside
     * [TurnkeyKotlinError]; a bare one arrives from the add-passkey ceremony, which the adapter
     * runs without the vendor's login or sign-up wrapper around it. The Rain client's own failure
     * is claimed here too, before the prose rules can read its message.
     */
    fun classify(t: Throwable): RainError? = when (t) {
        is TurnkeyKotlinError -> mapTurnkeyError(t)
        is TurnkeyPasskeyError, is TurnkeyStamperError -> mapPasskeyCeremonyFailure(t)
        is RainApiAuthFailure -> mapRainApiAuthFailure(t)
        else -> mapTurnkeyHttpStatus(t)
    }

    /**
     * A refusal from Rain's embedded-wallet endpoints, by [RainApiAuthFailure.Kind]: a wrong code is
     * [RainError.InvalidLoginCode], a refused or spent token or a refused tenant is
     * [RainError.Unauthorized], a passkey that already has a wallet is [RainError.InvalidConfig]
     * naming `loginWithPasskey` (a state error, as the other session-state refusals are; the message
     * is true whenever Rain says it), and everything else is [RainError.ProviderError] over the
     * failure, which carries the status, the path and a 500's correlation id, and no body.
     */
    fun mapRainApiAuthFailure(e: RainApiAuthFailure): RainError = when (e.kind) {
        RainApiAuthFailure.Kind.InvalidCode -> RainError.InvalidLoginCode()
        RainApiAuthFailure.Kind.TokenRejected ->
            RainError.Unauthorized("Rain rejected the wallet auth token on ${e.path}: HTTP ${e.status}")
        RainApiAuthFailure.Kind.TokenUsed ->
            RainError.Unauthorized("Rain answered that another call already spent the wallet auth token (${e.path}: HTTP ${e.status})")
        RainApiAuthFailure.Kind.Forbidden ->
            RainError.Unauthorized(
                "Rain refused embedded-wallet auth on ${e.path}: HTTP ${e.status}; " +
                    "the tenant cannot use embedded wallets, or the wallet auth token is of the wrong kind for this route",
            )
        RainApiAuthFailure.Kind.PasskeyTaken ->
            RainError.InvalidConfig(
                "Rain answered that this passkey already has a wallet (${e.path}: HTTP ${e.status}); " +
                    "sign in with loginWithPasskey instead of signing up",
            )
        RainApiAuthFailure.Kind.NoWallet,
        RainApiAuthFailure.Kind.SignupInProgress,
        RainApiAuthFailure.Kind.Refused -> RainError.ProviderError(e)
    }

    /**
     * [map] plus the authentication log line, for the managed-login catch-all boundary. On the
     * authentication calls nothing hands the throwable to the log: a failure there can echo the
     * user's contact address, so only the code and the class name are recorded, and no step of the
     * mapping logs the throwable itself.
     */
    fun mapAuthError(e: Throwable): RainError {
        val mapped = map(e)
        logAuthFailure(mapped, e)
        return mapped
    }

    /** No throwable in this log line; see [mapAuthError]. */
    private fun logAuthFailure(mapped: RainError, e: Throwable) {
        Timber.e(
            "Rain SDK: Authentication error %s (%s)",
            mapped.errorCode.code,
            e.javaClass.simpleName,
        )
    }

    /**
     * Maps Turnkey SDK errors to RainError:
     *  - InvalidSession → TokenExpired
     *  - Config/setup-style errors (missing rpId, missing config param, client not initialized,
     *    invalid parameter / message / refresh TTL / response, OAuth state mismatch, key already
     *    exists / not found) → InternalError
     *  - FailedToLoginWithPasskey / FailedToSignUpWithPasskey → by the innermost cause, see
     *    [mapPasskeyCeremonyFailure]
     *  - Wrapper errors with an underlying cause → recurse / classify the cause's vendor prose
     *  - Everything else → ProviderError
     *
     * Logs nothing itself: the session coordinator records an unmapped failure at its boundary, and
     * the authentication calls keep the throwable out of the log; see [mapAuthError].
     */
    @Suppress("ReturnCount") // one return per vendor variant reads better than a nested when
    fun mapTurnkeyError(e: TurnkeyKotlinError): RainError {
        when (e) {
            is TurnkeyKotlinError.InvalidSession -> return RainError.TokenExpired()

            // Config / setup-time errors that indicate misuse rather than provider failure.
            is TurnkeyKotlinError.InvalidRefreshTTL,
            is TurnkeyKotlinError.ClientNotInitialized,
            is TurnkeyKotlinError.InvalidParameter,
            is TurnkeyKotlinError.InvalidResponse,
            is TurnkeyKotlinError.InvalidMessage,
            is TurnkeyKotlinError.MissingRpId,
            is TurnkeyKotlinError.MissingConfigParam,
            is TurnkeyKotlinError.OAuthStateMismatch,
            is TurnkeyKotlinError.KeyAlreadyExists,
            is TurnkeyKotlinError.KeyNotFound -> return RainError.InternalError("Wallet backend: ${e.message}", e)

            // A passkey ceremony that failed. The wrapper says nothing about why; the Credential
            // Manager exception, the HTTP failure or the nested vendor error it carries does.
            is TurnkeyKotlinError.FailedToLoginWithPasskey,
            is TurnkeyKotlinError.FailedToSignUpWithPasskey -> return mapPasskeyCeremonyFailure(e)

            else -> Unit // fall through to cause inspection
        }

        // Recurse into wrapped causes (e.g. FailedToSignRawPayload(underlying)). The status
        // check precedes the prose check here too, for the same reason as classify().
        val cause = e.cause
        if (cause != null && cause !== e) {
            if (cause is TurnkeyKotlinError) return mapTurnkeyError(cause)
            mapTurnkeyHttpStatus(cause)?.let { return it }
            VendorErrorClassifier.fromVendorError(cause)?.let { return it }
        }

        // A wrapped HTTP failure with a status the rules above do not claim; its body goes at the exit.
        return RainError.ProviderError(e)
    }

    /**
     * A failed passkey ceremony, classified by the innermost cause the chain carries rather than
     * by the wrapper. The vendor wraps a login in `FailedToLoginWithPasskey` over the stamper's and
     * the passkey package's assertion failures, a sign-up in `FailedToSignUpWithPasskey` over the
     * package's registration failure, and a bare passkey creation (the add-passkey path) in
     * `TurnkeyPasskeyError.RegistrationFailed`; the Credential Manager exception, the HTTP failure
     * or a nested vendor error sits below those. The first recognisable cause in the chain decides:
     *  - a dismissed sheet (`GetCredentialCancellationException`, `CreateCredentialCancellationException`),
     *    no passkey for the domain or consent declined (`NoCredentialException`), or a `NotAllowedError`
     *    DOM error whose message says the user cancelled → UserRejected
     *  - a `SecurityError` or `DataError` DOM error, the association file or the signing fingerprint not
     *    vouching for this build, or the file lacking the site's own statement (current Play services
     *    builds report that check as `DataError`, their code 50152, older ones as `SecurityError`)
     *    → InvalidConfig, with [PASSKEY_ASSOCIATION_MESSAGE]
     *  - no passkey provider on the device → ProviderError, with the dependency named in the log
     *  - a nested vendor error (`FailedToCreateSession(KeyAlreadyExists)`, `InvalidResponse`, ...) → its own rule
     *  - an HTTP failure, whatever the status → ProviderError: no session exists during a passkey
     *    login or sign-up, so 401 and 403 cannot mean an expired session or a permission problem
     *  - any other Credential Manager exception (interrupted, unsupported, unknown, no create option,
     *    any other DOM error) → ProviderError
     *  - nothing recognisable → the shared prose rules over the whole message, then ProviderError
     */
    fun mapPasskeyCeremonyFailure(e: Throwable): RainError {
        val leaf = e.causeChain().drop(1).firstOrNull { it.isPasskeyLeaf() }
        return when (leaf) {
            is GetCredentialCancellationException,
            is CreateCredentialCancellationException,
            is NoCredentialException -> RainError.UserRejected()
            is GetPublicKeyCredentialDomException -> mapDomError(leaf.domError, leaf.message, e)
            is CreatePublicKeyCredentialDomException -> mapDomError(leaf.domError, leaf.message, e)
            is GetCredentialProviderConfigurationException,
            is CreateCredentialProviderConfigurationException -> noPasskeyProvider(leaf, e)
            is TurnkeyKotlinError -> mapTurnkeyError(leaf)
            null -> VendorErrorClassifier.fromVendorError(e) ?: RainError.ProviderError(e)
            else -> RainError.ProviderError(e)
        }
    }

    /** The causes that decide a ceremony's outcome; the vendor's own wrappers are skipped. */
    private fun Throwable.isPasskeyLeaf(): Boolean =
        this is GetCredentialException ||
            this is CreateCredentialException ||
            this is TurnkeyKotlinError ||
            turnkeyHttpStatus(this) != null

    /**
     * The DOM errors that mean something to a host. `SecurityError` and `DataError` are both the
     * association check, which Play services moved from the first name to the second (its code
     * 50152), so they map alike. `NotAllowedError` covers both a dismissed dialog and a time-out, so
     * only its message tells them apart. Every other DOM error is device or provider state.
     */
    private fun mapDomError(domError: DomError, message: String?, outer: Throwable): RainError = when {
        domError is SecurityError || domError is DataError -> RainError.InvalidConfig(PASSKEY_ASSOCIATION_MESSAGE)
        domError is NotAllowedError && message.orEmpty().contains("cancel", ignoreCase = true) -> RainError.UserRejected()
        else -> RainError.ProviderError(outer)
    }

    /**
     * No passkey provider can serve the request: Google Play services are unavailable, or the app
     * excluded the provider dependency. Class name only in the log, never the message.
     */
    private fun noPasskeyProvider(leaf: Throwable, outer: Throwable): RainError {
        Timber.w(
            "Rain SDK: no passkey provider is available (%s); the device needs a credential provider " +
                "(Google Play services on Android 9 to 13, any installed provider on Android 14 and later) and " +
                "the app androidx.credentials:credentials-play-services-auth on its runtime classpath",
            leaf.javaClass.simpleName,
        )
        return RainError.ProviderError(outer)
    }

    /**
     * Maps a Turnkey API HTTP failure to [RainError.TokenExpired] (401) or
     * [RainError.Unauthorized] (403).
     *
     * The Kotlin SDK throws a plain `RuntimeException` and carries the status only inside the
     * message, in one of two generated shapes:
     *   "HTTP error from <path>: <code>"
     *   "HTTP error calling <activityType> request\nError: <body>\nCode: <code>"
     * so the status has to be parsed out. Returns `null` for anything that is not a recognizable
     * HTTP failure, leaving the caller's fallback in place. Replace this with a typed check once
     * the Turnkey SDK exposes the status code.
     */
    @Suppress("MagicNumber") // HTTP status codes read better inline than as named constants
    private fun mapTurnkeyHttpStatus(e: Throwable): RainError? {
        return when (val status = turnkeyHttpStatus(e) ?: return null) {
            401 -> RainError.TokenExpired()
            403 -> RainError.Unauthorized("The wallet backend rejected the request: HTTP $status")
            else -> null
        }
    }

    const val TURNKEY_HTTP_ERROR_PREFIX = "HTTP error"

    /**
     * The one passkey failure a host fixes in its own setup. Generic on purpose: the mapper holds
     * no configuration, and the host knows its own domain and package name.
     */
    const val PASSKEY_ASSOCIATION_MESSAGE =
        "The device refused the passkey request for this app: check that the configured passkeyDomain " +
            "serves /.well-known/assetlinks.json with a get_login_creds statement for the site itself and an " +
            "app statement (handle_all_urls and get_login_creds) listing this app's package name and this " +
            "build's signing certificate fingerprint, and that passkeyDomain matches that host"

    /** Trailing ": <code>" or "Code: <code>" — the two shapes the Turnkey SDK generates. */
    val TURNKEY_HTTP_STATUS_REGEX = Regex("""(?:Code:\s*|:\s*)(\d{3})\s*$""")

    /**
     * The HTTP status carried in a Turnkey SDK failure message, or null when the throwable
     * is not a recognizable Turnkey HTTP failure. Shared with the session coordinator's
     * retry classification.
     */
    @Suppress("ReturnCount") // two guard clauses before the parse
    fun turnkeyHttpStatus(e: Throwable): Int? {
        val message = e.message ?: return null
        if (!message.startsWith(TURNKEY_HTTP_ERROR_PREFIX)) return null
        return TURNKEY_HTTP_STATUS_REGEX.find(message)
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
    }
}
