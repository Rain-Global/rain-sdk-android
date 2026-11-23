package com.rain.sdk.internal.utils

import com.rain.sdk.internal.RainAdapterApi
import kotlinx.coroutines.CancellationException

/** [runCatching] for a suspend [block] that rethrows cancellation and lets an [Error] propagate. */
@RainAdapterApi
@Suppress("TooGenericExceptionCaught") // runCatching's catch-all, minus cancellation
suspend fun <T> suspendRunCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
