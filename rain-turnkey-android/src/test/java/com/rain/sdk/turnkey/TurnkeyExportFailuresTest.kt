package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import org.junit.Before
import org.junit.Test

/**
 * [TurnkeyExportFailures.rethrowable] is the only thing between the vendor's crypto errors, whose
 * messages can carry the ephemeral decryption key, and the session coordinator's log line. These
 * pin every branch with a hex marker standing in for that key.
 *
 * Gated on JDK 24+ because Turnkey's published AAR is compiled to major class version 68. Vendor
 * types appear inside method bodies only, as [TurnkeyErrorMappingTest] explains.
 */
class TurnkeyExportFailuresTest {

    @Before
    fun requireJdk24() = assumeJdk24()

    @Test
    fun `a crypto error wrapped by the vendor becomes a cause-free rejection naming the variant only`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet(
            com.turnkey.crypto.utils.TurnkeyCryptoError.InvalidHexString(MARKER)
        )

        val out = TurnkeyExportFailures.rethrowable(error)

        assertThat(out).isInstanceOf(com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet::class.java)
        assertThat(out.cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(out.cause?.cause).isNull()
        assertThat(out.cause).hasMessageThat().contains("InvalidHexString")
        assertNoMaterial(out)
    }

    @Test
    fun `a bare crypto error is wrapped and stripped the same way`() {
        val error = com.turnkey.crypto.utils.TurnkeyCryptoError.OperationFailed(RuntimeException(MARKER))

        val out = TurnkeyExportFailures.rethrowable(error)

        assertThat(out).isInstanceOf(com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet::class.java)
        assertThat(out.cause).hasMessageThat().contains("OperationFailed")
        assertNoMaterial(out)
    }

    @Test
    fun `a crypto error two links deep is still found`() {
        val error = RuntimeException(
            "outer",
            IllegalStateException("middle", com.turnkey.crypto.utils.TurnkeyCryptoError.InvalidHexString(MARKER))
        )

        val out = TurnkeyExportFailures.rethrowable(error)

        assertThat(out.cause).hasMessageThat().contains("InvalidHexString")
        assertNoMaterial(out)
    }

    @Test
    fun `a vendor export failure without a crypto error passes through as the same instance`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet(
            RuntimeException("HTTP error from /public/v1/submit/export_wallet: 401")
        )

        assertThat(TurnkeyExportFailures.rethrowable(error)).isSameInstanceAs(error)
    }

    @Test
    fun `any other exception is wrapped with itself as the cause`() {
        val error = RuntimeException("HTTP error from /public/v1/submit/export_wallet_account: 503")

        val out = TurnkeyExportFailures.rethrowable(error)

        assertThat(out).isInstanceOf(com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet::class.java)
        assertThat(out.cause).isSameInstanceAs(error)
    }

    @Test
    fun `a cancellation the vendor wrapped comes back bare and untouched`() {
        val cancellation = CancellationException("the caller left")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet(cancellation)

        assertThat(TurnkeyExportFailures.rethrowable(error)).isSameInstanceAs(cancellation)
    }

    @Test
    fun `a cyclic cause chain terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        val out = TurnkeyExportFailures.rethrowable(a)

        assertThat(out).isInstanceOf(com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet::class.java)
        assertThat(out.cause).isSameInstanceAs(a)
    }

    /** No link of the chain, and no toString of it, carries the marker; the vendor's data classes print fields. */
    private fun assertNoMaterial(root: Throwable) {
        val chain = root.causeChain().toList()
        chain.forEach { link ->
            assertThat(link.message.orEmpty()).doesNotContain(MARKER)
            assertThat(link.toString()).doesNotContain(MARKER)
            assertThat(link).isNotInstanceOf(com.turnkey.crypto.utils.TurnkeyCryptoError::class.java)
        }
    }

    private companion object {
        const val MARKER = "deadbeefcafef00ddeadbeefcafef00ddeadbeefcafef00ddeadbeefcafef00d"
    }
}
