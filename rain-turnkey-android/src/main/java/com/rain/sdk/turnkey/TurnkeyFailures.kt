package com.rain.sdk.turnkey

import com.rain.sdk.error.RainError
import com.turnkey.http.utils.TurnkeyHttpError
import com.turnkey.types.V1Activity
import com.turnkey.types.V1ActivityStatus
import com.turnkey.types.V1ActivityType
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
 * The mapper's one exit for what a vendor failure carries. A [RainError.ProviderError] or
 * [RainError.InternalError] whose cause chain carries a vendor HTTP failure is rebuilt around
 * [TurnkeyHttpFailure], so the host sees the call and the status and never the body; one whose chain
 * carries the typed client's `ActivityNotCompleted` is rebuilt around [TurnkeyActivityFailure], so
 * the host sees the activity's id, type, status and reason and never the activity itself. Both apply
 * whichever of the vendor's wrappers the failure arrived in and whichever mapping branch produced
 * the error; no branch sanitizes on its own. The typed client's HTTP message embeds the raw body,
 * which for the user-update activities can echo the email or phone being attached, and every vendor
 * wrapper copies its cause's message into its own, which `ProviderError` copies into the message
 * hosts log. `ActivityNotCompleted` (http 2.2.0) is a data class whose `toString()` prints the whole
 * `V1Activity`, intent and votes included, and the intent echoes the request: the contact being
 * attached, the EIP-712 payload being signed. Every other error, and one whose chain holds neither,
 * passes through untouched. The wrapper's name goes to the debug log, because the rebuilt message
 * drops it.
 */
internal fun RainError.withoutResponseBody(): RainError {
    val vendor = causeChain().drop(1).firstOrNull { it.carriesVendorData() } ?: return this
    Timber.d(
        "Rain SDK: a wallet backend call failed inside %s: %s",
        cause?.takeIf { it !== vendor }?.javaClass?.simpleName ?: "no wrapper",
        vendor.sanitizedForHost().message,
    )
    return when (this) {
        is RainError.ProviderError -> RainError.ProviderError(vendor.sanitizedForHost())
        is RainError.InternalError -> RainError.InternalError(detailsWithoutCauseText(), vendor.sanitizedForHost())
        else -> this
    }
}

/**
 * True for a vendor failure that carries more than a host may see: an HTTP failure, whose message
 * embeds the response body, or the typed client's `ActivityNotCompleted`, whose `toString()` prints
 * the whole activity. The adapter's own replacements, [TurnkeyHttpFailure] and
 * [TurnkeyActivityFailure], carry nothing more and are not; nor is [RainApiAuthFailure], which the
 * Rain client builds from the status and the path alone.
 */
internal fun Throwable.carriesVendorData(): Boolean =
    this is TurnkeyHttpError.ActivityNotCompleted ||
        (this !is TurnkeyHttpFailure && TurnkeyErrorMapping.turnkeyHttpStatus(this) != null)

/**
 * This error's own details without the vendor cause text: the base class prefixes every message
 * with the code, and the vendor joins a wrapper's text to its cause's with " - error: ".
 */
private fun RainError.detailsWithoutCauseText(): String =
    message.orEmpty().removePrefix("RainSDK Error [${errorCode.code}]: ").substringBefore(" - error: ")

/**
 * This vendor failure with what a host may not see removed: an HTTP failure without the response
 * body, the typed client's `ActivityNotCompleted` without the activity, or this throwable itself
 * when it is neither. The status and the request path or activity type, or the activity's id, type,
 * status and reason, are what a host can act on.
 */
internal fun Throwable.sanitizedForHost(): Throwable = when (this) {
    is TurnkeyHttpError.ActivityNotCompleted -> TurnkeyActivityFailure(activity, path)
    else -> TurnkeyErrorMapping.turnkeyHttpStatus(this)?.let { status ->
        val target = TURNKEY_HTTP_TARGET_REGEX.find(message.orEmpty())?.groupValues?.getOrNull(1)?.trimEnd(':')
            ?: "the wallet backend"
        TurnkeyHttpFailure(status, target)
    } ?: this
}

/**
 * A vendor HTTP failure with the response body removed; see [withoutResponseBody]. The message keeps
 * the vendor's path shape, so [TurnkeyErrorMapping.turnkeyHttpStatus] still reads the status off a
 * mapped error's cause, which the wallet provider's history and balance fallbacks (`isBackendRefusal`) rely on.
 */
internal class TurnkeyHttpFailure(status: Int, target: String) : RuntimeException("HTTP error from $target: $status")

/**
 * A submit whose activity the vendor's client could not complete, reduced to what a host can act on;
 * see [withoutResponseBody]. The message keeps the typed client's sentence shape and adds the
 * activity type and Turnkey's reason, capped like every vendor message, so a host reads what
 * happened to the activity without its intent or votes.
 */
internal class TurnkeyActivityFailure(
    val activityId: String,
    val type: V1ActivityType,
    val status: V1ActivityStatus,
    val path: String,
    val reason: String?
) : RuntimeException(
    "Activity $activityId (${type.name}) from $path has status ${status.name}" + reason?.let { ", $it" }.orEmpty()
) {
    constructor(activity: V1Activity, path: String) : this(
        activityId = activity.id,
        type = activity.type,
        status = activity.status,
        path = path,
        reason = activity.failure?.message?.take(TurnkeySendFailures.MAX_VENDOR_MESSAGE_LENGTH)
    )
}

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
