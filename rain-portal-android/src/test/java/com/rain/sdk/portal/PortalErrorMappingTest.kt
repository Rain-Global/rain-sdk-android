package com.rain.sdk.portal

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import io.mockk.mockk
import io.portalhq.android.exceptions.PortalException
import io.portalhq.android.utils.errors.PortalError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Vendor-error classification, split by call path:
 * auth mapping (401 / invalid API key to TokenExpired) applies everywhere; JSON-RPC
 * "execution reverted" (code 3) maps to TransactionSimulationFailed only on the send /
 * fee-estimation path, and everything else stays unmapped so existing wrapping applies.
 */
class PortalErrorMappingTest {

    // ---- HTTP 401 / unauthorized -------------------------------------------------

    @Test
    fun `HttpUnauthorized maps to TokenExpired`() {
        val mapped = PortalErrorMapping.mapAuthOrNull(
            PortalException.Api.HttpUnauthorized("401 Unauthorized")
        )
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    // ---- MPC invalid API key -----------------------------------------------------

    @Test
    fun `MpcResultError with legacy id 320 maps to TokenExpired`() {
        val error = PortalException.Mpc.MpcResultError(
            PortalError(rawCode = null, rawMessage = "invalid api key", id = "320")
        )
        assertThat(PortalErrorMapping.mapAuthOrNull(error))
            .isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `MpcResultError with INVALID_API_KEY code maps to TokenExpired`() {
        // PortalErrorCodes.INVALID_API_KEY.code == 202
        val error = PortalException.Mpc.MpcResultError(
            PortalError(rawCode = 202, rawMessage = "invalid api key", id = "")
        )
        assertThat(PortalErrorMapping.mapAuthOrNull(error))
            .isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `MpcResultError with an unrelated code stays unmapped`() {
        val error = PortalException.Mpc.MpcResultError(
            PortalError(rawCode = 108, rawMessage = "failed to finish sign", id = "108")
        )
        assertThat(PortalErrorMapping.mapAuthOrNull(error)).isNull()
    }

    // ---- JSON-RPC execution reverted ----------------------------------------------

    @Test
    fun `RpcError code 3 maps to TransactionSimulationFailed on the simulation path`() {
        val error = PortalException.Api.RpcError(code = 3, message = "execution reverted")
        val mapped = PortalErrorMapping.mapSimulationOrNull(error)
        assertThat(mapped).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
        assertThat(mapped?.cause).isSameInstanceAs(error)
    }

    @Test
    fun `RpcError code 3 stays unmapped on the auth-only path used by reads`() {
        val error = PortalException.Api.RpcError(code = 3, message = "execution reverted")
        assertThat(PortalErrorMapping.mapAuthOrNull(error)).isNull()
    }

    /** Some nodes report an execution failure under the generic -32000 with only the prose to go on. */
    @Test
    fun `RpcError naming an execution failure maps to TransactionSimulationFailed on the simulation path`() {
        for (message in listOf("out of gas", "invalid opcode: INVALID", "Execution reverted: custom error")) {
            val error = PortalException.Api.RpcError(code = -32000, message = message)
            assertThat(PortalErrorMapping.mapSimulationOrNull(error))
                .isInstanceOf(RainError.TransactionSimulationFailed::class.java)
            assertThat(PortalErrorMapping.mapAuthOrNull(error)).isNull()
        }
    }

    @Test
    fun `RpcError with another code stays unmapped`() {
        val error = PortalException.Api.RpcError(code = -32000, message = "nonce too low")
        assertThat(PortalErrorMapping.mapSimulationOrNull(error)).isNull()
    }

    @Test
    fun `simulation path still maps auth failures to TokenExpired`() {
        val mapped = PortalErrorMapping.mapSimulationOrNull(
            PortalException.Api.HttpUnauthorized("401 Unauthorized")
        )
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    // ---- fallthrough ---------------------------------------------------------------

    @Test
    fun `unrelated exceptions stay unmapped`() {
        assertThat(PortalErrorMapping.mapAuthOrNull(RuntimeException("boom"))).isNull()
        assertThat(PortalErrorMapping.mapSimulationOrNull(RuntimeException("boom"))).isNull()
    }

    // ---- provider creation guard ----------------------------------------------------

    @Test
    fun `PortalProvider create throws Unauthorized for an empty session token`() {
        val provider = PortalProvider(PortalConfig(sessionToken = ""))
        assertThrows(RainError.Unauthorized::class.java) {
            runBlocking { provider.create(mockk()) }
        }
    }

    @Test
    fun `PortalProvider create throws Unauthorized for a blank session token`() {
        val provider = PortalProvider(PortalConfig(sessionToken = "   "))
        assertThrows(RainError.Unauthorized::class.java) {
            runBlocking { provider.create(mockk()) }
        }
    }
}
