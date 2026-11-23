package com.rain.sdk.internal.helpers

/**
 * Runs a suspending [block] and returns the [T] it threw. Fails the test when it threw nothing;
 * rethrows anything of another type so a wrong error class is never mistaken for the right one.
 */
internal suspend inline fun <reified T : Throwable> expectThrows(block: suspend () -> Unit): T {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return t
        throw t
    }
    throw AssertionError("Expected ${T::class.simpleName} to be thrown")
}
