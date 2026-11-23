package com.rain.sdk.internal

/**
 * Marks a core declaration that the wallet adapter modules link against: public in the binary
 * because each adapter is its own Gradle module and Kotlin `internal` would wall it off, but not
 * part of the SDK's contract with host apps. A host that uses a marked declaration gets a compile
 * error; the Rain modules that are allowed through opt in module-wide with
 * `-opt-in=com.rain.sdk.internal.RainAdapterApi`. No compatibility guarantees: it changes whenever
 * an adapter needs it to.
 *
 * The guard is the Kotlin compiler's, so it does not constrain a Java caller; a marked declaration
 * is still published ABI. Treat the marker as the contract, not as enforcement.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal Rain SDK seam reserved for the wallet adapter modules. Host apps use the " +
        "public API in com.rain.sdk. No compatibility guarantees.",
)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
)
annotation class RainAdapterApi
