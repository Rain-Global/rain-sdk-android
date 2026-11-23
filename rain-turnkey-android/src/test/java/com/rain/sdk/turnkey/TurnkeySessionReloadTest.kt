package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.turnkey.core.models.errors.TurnkeyKotlinError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The reload of the selected session that follows a successful refresh in `TurnkeyContextAdapter`,
 * and the cause walk both it and the session coordinator use to find a wrapped cancellation.
 */
class TurnkeySessionReloadTest {

    @Before
    fun requireJdk24() = assumeJdk24()

    @Test
    fun `reloads the selected session under its key`() = runBlocking<Unit> {
        val reloaded = mutableListOf<String>()

        reloadSelectedSession("session-key") { reloaded += it }

        assertThat(reloaded).containsExactly("session-key")
    }

    @Test
    fun `does nothing when no session is selected`() = runBlocking<Unit> {
        var calls = 0

        reloadSelectedSession(null) { calls++ }

        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `a failed reload is logged and swallowed, because the refresh itself succeeded`() {
        val entries = mutableListOf<Pair<Int, Throwable?>>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                entries += priority to t
            }
        }
        val failure = TurnkeyKotlinError.FailedToSetSelectedSession(IOException("wallets read failed"))
        timber.log.Timber.plant(tree)
        try {
            runBlocking { reloadSelectedSession("session-key") { throw failure } }
        } finally {
            timber.log.Timber.uproot(tree)
        }

        assertThat(entries).hasSize(1)
        assertThat(entries.single().first).isEqualTo(android.util.Log.WARN)
        assertThat(entries.single().second).isSameInstanceAs(failure)
    }

    @Test
    fun `a cancellation the vendor wrapped in the reload failure is rethrown as itself`() {
        val cancel = CancellationException("caller went away")

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                reloadSelectedSession("session-key") { throw TurnkeyKotlinError.FailedToSetSelectedSession(cancel) }
            }
        }

        assertThat(thrown).isSameInstanceAs(cancel)
    }

    @Test
    fun `a bare cancellation is rethrown as itself`() {
        val cancel = CancellationException("caller went away")

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { reloadSelectedSession("session-key") { throw cancel } }
        }

        assertThat(thrown).isSameInstanceAs(cancel)
    }

    @Test
    fun `cancellationInChain finds a cancellation two wrappers deep and nothing otherwise`() {
        val cancel = CancellationException("deep")
        val wrapped = TurnkeyKotlinError.FailedToRefreshSession(TurnkeyKotlinError.FailedToSetSelectedSession(cancel))

        assertThat(wrapped.cancellationInChain()).isSameInstanceAs(cancel)
        assertThat(IOException("plain").cancellationInChain()).isNull()
    }

    @Test
    fun `cancellationInChain stops on a cyclic cause chain`() {
        val a = IOException("a")
        val b = IOException("b")
        a.initCause(b)
        b.initCause(a)

        assertThat(a.cancellationInChain()).isNull()
    }
}
