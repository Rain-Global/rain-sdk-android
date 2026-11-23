package com.rain.sdk.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assume.assumeTrue

/**
 * For tests that build a real backing: constructing it references the wallet backend's
 * process-wide singleton, whose class initializer needs JDK 24 class files and a main dispatcher.
 * Same guards as the backend module's own tests; the skip is a CI failure unless the JDK 24
 * launcher runs. One instance per test class; call [useRealBacking] in the test and [tearDown]
 * from `@After`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RealBackingGuard {
    private var mainSet = false

    fun useRealBacking() {
        val major = System.getProperty("java.version")?.substringBefore('.')?.toIntOrNull() ?: 0
        assumeTrue("the wallet backend's class files need a JDK 24 test launcher", major >= JDK_24)
        Dispatchers.setMain(StandardTestDispatcher())
        mainSet = true
    }

    fun tearDown() {
        if (mainSet) Dispatchers.resetMain()
        mainSet = false
    }

    private companion object {
        const val JDK_24 = 24
    }
}
