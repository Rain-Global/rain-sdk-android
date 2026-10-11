package com.rain.sdk.turnkey

import com.rain.sdk.error.RainError
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * This throwable and its causes, nearest first, at most [MAX_CAUSE_DEPTH] deep so a cyclic cause
 * chain cannot spin a walk. The module's one cause walk: the error mapping, the export failure
 * translation, the session coordinator, the wallet provider's history and balance fallbacks and
 * [cancellationInChain] all read an exception through it.
 */
internal fun Throwable.causeChain(): Sequence<Throwable> =
    generateSequence(this) { current -> current.cause?.takeIf { it !== current } }.take(MAX_CAUSE_DEPTH)

/**
 * The caller's cancellation inside a vendor failure, or null. The vendor's session calls
 * (`refreshSession`, `setSelectedSession`, `signRawPayload`, the exports) catch `Throwable` and
 * rethrow their own type with the original as the cause, a coroutine cancellation included, so a
 * `catch (e: CancellationException)` above them never matches.
 */
internal fun Throwable.cancellationInChain(): CancellationException? =
    causeChain().filterIsInstance<CancellationException>().firstOrNull()

/** Bounds the one cause walk so a cyclic cause chain cannot spin it. */
private const val MAX_CAUSE_DEPTH = 8

/**
 * The mapper's one exit for the vendor's HTTP response body. A [RainError.ProviderError] or
 * [RainError.InternalError] whose cause chain carries a vendor HTTP failure is rebuilt around
 * [TurnkeyHttpFailure], so the host sees the call and the status and never the body, whichever of
 * the vendor's wrappers the failure arrived in and whichever mapping branch produced the error; no
 * branch sanitizes on its own. The typed client's message embeds the raw body, which for the
 * user-update activities can echo the email or phone being attached, and every vendor wrapper
 * copies its cause's message into its own, which `ProviderError` copies into the message hosts
 * log. Every other error, and one whose chain holds no HTTP failure, passes through untouched. The
 * wrapper's name goes to the debug log, because the rebuilt message drops it.
 */
internal fun RainError.withoutResponseBody(): RainError {
    val http = causeChain().drop(1).firstOrNull { TurnkeyErrorMapping.turnkeyHttpStatus(it) != null }
        ?.takeUnless { it is TurnkeyHttpFailure }
        ?: return this
    Timber.d(
        "Rain SDK: a wallet backend call failed with HTTP %s inside %s",
        TurnkeyErrorMapping.turnkeyHttpStatus(http),
        cause?.takeIf { it !== http }?.javaClass?.simpleName ?: "no wrapper",
    )
    return when (this) {
        is RainError.ProviderError -> RainError.ProviderError(http.sanitizedForHost())
        is RainError.InternalError -> RainError.InternalError(detailsWithoutCauseText(), http.sanitizedForHost())
        else -> this
    }
}

/**
 * This error's own details without the vendor cause text: the base class prefixes every message
 * with the code, and the vendor joins a wrapper's text to its cause's with " - error: ".
 */
private fun RainError.detailsWithoutCauseText(): String =
    message.orEmpty().removePrefix("RainSDK Error [${errorCode.code}]: ").substringBefore(" - error: ")

/**
 * This vendor HTTP failure with the response body removed, or this throwable itself when its
 * message is not a recognizable vendor HTTP failure. The status and the request path or activity
 * type are what a host can act on.
 */
internal fun Throwable.sanitizedForHost(): Throwable {
    val status = TurnkeyErrorMapping.turnkeyHttpStatus(this) ?: return this
    val target = TURNKEY_HTTP_TARGET_REGEX.find(message.orEmpty())?.groupValues?.getOrNull(1)?.trimEnd(':')
        ?: "the wallet backend"
    return TurnkeyHttpFailure(status, target)
}

/**
 * A vendor HTTP failure with the response body removed; see [withoutResponseBody]. The message keeps
 * the vendor's path shape, so [TurnkeyErrorMapping.turnkeyHttpStatus] still reads the status off a
 * mapped error's cause, which the wallet provider's history and balance fallbacks (`isBackendRefusal`) rely on.
 */
internal class TurnkeyHttpFailure(status: Int, target: String) : RuntimeException("HTTP error from $target: $status")

/** The first token after "from" or "calling": the request path or the activity type, never the body. */
private val TURNKEY_HTTP_TARGET_REGEX = Regex("""^HTTP error (?:from|calling) (\S+)""")

/**
 * What kotlinx appends to a decoding failure's message: the input it could not read. For a Turnkey
 * answer that is the activity itself (ids, addresses, the unsigned transaction), so a vendor message
 * is cut there before it reaches a host-visible error or a log line.
 */
private const val KOTLINX_INPUT_MARKER = "\nJSON input:"

/**
 * This vendor failure's message for a host-visible error or a log line. A vendor HTTP failure is
 * reduced to its status and target first ([sanitizedForHost]), so no response body passes; the
 * message is then cut before [KOTLINX_INPUT_MARKER] and capped at
 * [TurnkeySendFailures.MAX_VENDOR_MESSAGE_LENGTH]; the class name when there is no message.
 */
internal fun Throwable.vendorMessage(): String = sanitizedForHost().let { safe ->
    safe.message?.substringBefore(KOTLINX_INPUT_MARKER)?.take(TurnkeySendFailures.MAX_VENDOR_MESSAGE_LENGTH) ?: safe.javaClass.simpleName
}
