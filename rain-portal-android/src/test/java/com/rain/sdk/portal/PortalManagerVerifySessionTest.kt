package com.rain.sdk.portal

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.portalhq.android.Portal
import io.portalhq.android.api.Api
import io.portalhq.android.exceptions.PortalException
import io.portalhq.android.mpc.data.FeatureFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** The init-time session probe: the one call that turns a bad token into a typed error up front. */
class PortalManagerVerifySessionTest {

    private val chainId = 43114

    /** A Portal whose API surface is [api]; construction itself is offline, so nothing else matters. */
    private fun portalWith(api: Api): Portal {
        val portal = mockk<Portal>(relaxed = true)
        every { portal.api } returns api
        return portal
    }

    private fun managerWith(portal: Portal): PortalManager {
        val manager = spyk(PortalManager(retryIntervalMs = 0))
        every { manager.createPortal(any(), any(), any(), any(), any()) } returns portal
        manager.initialize(
            apiKey = "session-token",
            legacyEthChainId = chainId,
            rpcConfig = mapOf("eip155:$chainId" to "https://rpc.test"),
            featureFlags = FeatureFlags(isMultiBackupEnabled = true),
            autoApprove = true
        )
        return manager
    }

    @Test
    fun `a valid token round-trips once and returns quietly`(): Unit = runBlocking {
        val api = mockk<Api>(relaxed = true)
        val manager = managerWith(portalWith(api))

        manager.verifySession()

        coVerify(exactly = 1) { api.getClient() }
    }

    @Test
    fun `a 401 from Portal is TokenExpired`(): Unit = runBlocking {
        val api = mockk<Api>(relaxed = true)
        coEvery { api.getClient() } throws PortalException.Api.HttpUnauthorized("401 Unauthorized")
        val manager = managerWith(portalWith(api))

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { manager.verifySession() }
        }
    }

    /** No network is not a bad token; hosts must be able to tell the two apart at init. */
    @Test
    fun `a transport failure is NetworkError, not a credentials verdict`(): Unit = runBlocking {
        val api = mockk<Api>(relaxed = true)
        val wire = IOException("Unable to resolve host")
        coEvery { api.getClient() } throws wire
        val manager = managerWith(portalWith(api))

        val error = assertThrows(RainError.NetworkError::class.java) {
            runBlocking { manager.verifySession() }
        }
        assertThat(error.cause).isSameInstanceAs(wire)
    }

    @Test
    fun `an unclassified vendor failure is wrapped as ProviderError`(): Unit = runBlocking {
        val api = mockk<Api>(relaxed = true)
        coEvery { api.getClient() } throws IllegalStateException("boom")
        val manager = managerWith(portalWith(api))

        val error = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { manager.verifySession() }
        }
        assertThat(error.cause?.message).isEqualTo("boom")
    }

    @Test
    fun `verifying before initialize is SdkNotInitialized`() {
        assertThrows(RainError.SdkNotInitialized::class.java) {
            runBlocking { PortalManager(retryIntervalMs = 0).verifySession() }
        }
    }
}
