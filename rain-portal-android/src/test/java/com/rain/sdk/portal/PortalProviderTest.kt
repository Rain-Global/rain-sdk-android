package com.rain.sdk.portal

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.provider.ProviderContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.portalhq.android.Portal
import io.portalhq.android.exceptions.PortalException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/** Covers PortalProvider resolution wiring, in particular the onPortalCreated escape hatch. */
class PortalProviderTest {

    private fun context(): ProviderContext {
        val context = mockk<ProviderContext>()
        every { context.rpcEndpoints } returns mapOf(43114 to "https://rpc.test")
        every { context.tokenStore } returns mockk(relaxed = true)
        return context
    }

    @Test
    fun `create invokes onPortalCreated with the raw Portal instance`(): Unit = runBlocking {
        val portal = mockk<Portal>()
        val manager = mockk<PortalManager>(relaxed = true)
        every { manager.getPortalInstance() } returns portal

        var received: Portal? = null
        val provider = PortalProvider(
            config = PortalConfig(sessionToken = "token"),
            onPortalCreated = { received = it },
            portalManagerFactory = { manager },
        )
        provider.create(context())

        assertThat(received).isSameInstanceAs(portal)
        // Construction is offline, so the token is only proven by the probe.
        coVerify(exactly = 1) { manager.verifySession() }
    }

    /**
     * Portal's constructor never touches the network: a bad token gets past initialize and is
     * first rejected by the session probe. That rejection must fail creation, typed, and must not
     * leave a half-built manager behind for later calls to trip over.
     */
    @Test
    fun `a token the session probe rejects fails creation as TokenExpired`() {
        val manager = mockk<PortalManager>(relaxed = true)
        coEvery { manager.verifySession() } throws RainError.TokenExpired()
        var created = false
        val provider = PortalProvider(
            config = PortalConfig(sessionToken = "bad"),
            onPortalCreated = { created = true },
            portalManagerFactory = { manager },
        )

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { provider.create(context()) }
        }
        assertThat(created).isFalse()
        assertThrows(RainError.SdkNotInitialized::class.java) {
            runBlocking { provider.refreshSession() }
        }
    }

    /**
     * Anything the vendor throws while the client is built goes through the error contract. Hosts
     * branch on TokenExpired to re-authenticate, and init is exactly when the token tends to be wrong.
     */
    @Test
    fun `a rejected session token at creation surfaces as TokenExpired`() {
        val manager = mockk<PortalManager>(relaxed = true)
        every {
            manager.initialize(any(), any(), any(), any(), any())
        } throws PortalException.Api.HttpUnauthorized("401 Unauthorized")
        val provider = PortalProvider(
            config = PortalConfig(sessionToken = "bad"),
            onPortalCreated = null,
            portalManagerFactory = { manager },
        )

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { provider.create(context()) }
        }
    }

    @Test
    fun `an unclassified creation failure is wrapped as ProviderError`() {
        val manager = mockk<PortalManager>(relaxed = true)
        every {
            manager.initialize(any(), any(), any(), any(), any())
        } throws IllegalStateException("boom")
        val provider = PortalProvider(
            config = PortalConfig(sessionToken = "token"),
            onPortalCreated = null,
            portalManagerFactory = { manager },
        )

        val error = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { provider.create(context()) }
        }
        assertThat(error.cause?.message).isEqualTo("boom")
    }

    @Test
    fun `create without a callback still initializes Portal`(): Unit = runBlocking {
        val manager = mockk<PortalManager>(relaxed = true)
        val provider = PortalProvider(
            config = PortalConfig(sessionToken = "token"),
            onPortalCreated = null,
            portalManagerFactory = { manager },
        )

        provider.create(context())

        verify(exactly = 1) {
            manager.initialize(
                apiKey = "token",
                legacyEthChainId = 43114,
                rpcConfig = mapOf("eip155:43114" to "https://rpc.test"),
                featureFlags = any(),
                autoApprove = true,
            )
        }
        verify(exactly = 0) { manager.getPortalInstance() }
    }

    /**
     * Signing is approved by default: Portal shows no approval UI, so a host that never answers
     * `PortalSigningRequested` would see every signature hang. Opting out is explicit.
     */
    @Test
    fun `a host can opt out of adapter-side signing approval`(): Unit = runBlocking {
        val manager = mockk<PortalManager>(relaxed = true)
        val provider = PortalProvider(
            config = PortalConfig(sessionToken = "token", autoApprove = false),
            onPortalCreated = null,
            portalManagerFactory = { manager },
        )

        provider.create(context())

        verify(exactly = 1) {
            manager.initialize(
                apiKey = "token",
                legacyEthChainId = 43114,
                rpcConfig = mapOf("eip155:43114" to "https://rpc.test"),
                featureFlags = any(),
                autoApprove = false,
            )
        }
    }
}
