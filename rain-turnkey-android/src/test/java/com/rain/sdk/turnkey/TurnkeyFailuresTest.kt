package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import org.junit.Test

/**
 * Pins the module's one cause walk, [causeChain]: this throwable first, then its causes, at most
 * eight deep, and finite on a cyclic chain. No vendor type is involved, so the suite needs no JDK gate.
 */
class TurnkeyFailuresTest {

    @Test
    fun `causeChain lists the throwable and then its causes, nearest first`() {
        val root = IllegalStateException("root")
        val middle = RuntimeException("middle", root)
        val top = RuntimeException("top", middle)

        assertThat(top.causeChain().toList()).containsExactly(top, middle, root).inOrder()
    }

    @Test
    fun `causeChain stops eight deep on a longer chain`() {
        var deepest: Throwable = RuntimeException("0")
        repeat(20) { i -> deepest = RuntimeException("${i + 1}", deepest) }

        assertThat(deepest.causeChain().count()).isEqualTo(8)
    }

    @Test
    fun `causeChain is finite on a cyclic chain`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        assertThat(a.causeChain().toList()).hasSize(8)
    }

    @Test
    fun `cancellationInChain finds a wrapped cancellation and nothing else`() {
        val cancellation = CancellationException("cancelled")
        val wrapped = IllegalStateException("vendor", RuntimeException("inner", cancellation))

        assertThat(wrapped.cancellationInChain()).isSameInstanceAs(cancellation)
        assertThat(IllegalStateException("plain").cancellationInChain()).isNull()
    }
}
