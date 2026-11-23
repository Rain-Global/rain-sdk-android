package com.rain.sdk.portal

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.WalletProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import io.portalhq.android.Portal
import io.portalhq.android.exceptions.PortalException
import io.portalhq.android.mpc.data.FeatureFlags
import io.portalhq.android.provider.data.PortalRequestMethod
import io.portalhq.android.storage.mobile.PortalNamespace
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Session hardening wired end to end through [PortalProvider]: a rejected token re-mints via
 * `onSessionTokenNeeded`, rebuilds the vendor client with the retained configuration, re-fires
 * `onPortalCreated`, and retries the wallet call; a declined re-mint fires `onSessionExpired`.
 */
class PortalProviderSessionTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun context(): ProviderContext {
        val context = mockk<ProviderContext>()
        every { context.rpcEndpoints } returns mapOf(43114 to "https://rpc.test")
        every { context.tokenStore } returns mockk(relaxed = true)
        return context
    }

    /** A real PortalManager whose vendor client is swapped per token via the createPortal seam. */
    private fun managerBackedBy(portals: Map<String, Portal>): PortalManager {
        val manager = spyk(PortalManager())
        every { manager.createPortal(any(), any(), any(), any(), any()) } answers {
            portals.getValue(firstArg())
        }
        return manager
    }

    private fun portalReturning(address: String): Portal {
        val portal = mockk<Portal>(relaxed = true)
        coEvery { portal.getAddress(PortalNamespace.EIP155) } returns address
        return portal
    }

    private fun portalRejecting(): Portal {
        val portal = mockk<Portal>(relaxed = true)
        coEvery { portal.getAddress(PortalNamespace.EIP155) } throws
            PortalException.Api.HttpUnauthorized("401 - Unauthorized")
        return portal
    }

    @Test
    fun `rejected token is re-minted, the client rebuilt with retained config, and the call retried`() = runBlocking {
        val dead = portalRejecting()
        val fresh = portalReturning(TestFixtures.WALLET_ADDRESS)
        val manager = managerBackedBy(mapOf("dead" to dead, "fresh" to fresh))
        val created = mutableListOf<Portal>()
        var expiredCalls = 0
        val provider = PortalProvider(
            config = PortalConfig(
                sessionToken = "dead",
                onSessionTokenNeeded = { "fresh" },
                onSessionExpired = { expiredCalls++ },
            ),
            onPortalCreated = { created += it },
            portalManagerFactory = { manager },
        )

        val wallet: WalletProvider = provider.create(context())
        val address = wallet.getWalletAddress()

        assertThat(address).isEqualTo(TestFixtures.WALLET_ADDRESS)
        assertThat(created).containsExactly(dead, fresh).inOrder()
        assertThat(manager.getPortalInstance()).isSameInstanceAs(fresh)
        assertThat(expiredCalls).isEqualTo(0)
        assertThat(provider.currentSessionState()).isEqualTo(PortalSessionState.Active)
        // The rebuild reuses the configuration from the first initialize().
        verify(exactly = 1) {
            manager.createPortal(
                apiKey = "fresh",
                legacyEthChainId = 43114,
                rpcConfig = mapOf("eip155:43114" to "https://rpc.test"),
                featureFlags = FeatureFlags(isMultiBackupEnabled = true),
                autoApprove = true,
            )
        }
    }

    @Test
    fun `declined re-mint surfaces TokenExpired, fires onSessionExpired, and keeps the old client`() = runBlocking {
        val dead = portalRejecting()
        val manager = managerBackedBy(mapOf("dead" to dead))
        var expiredCalls = 0
        val provider = PortalProvider(
            config = PortalConfig(
                sessionToken = "dead",
                onSessionTokenNeeded = { null },
                onSessionExpired = { expiredCalls++ },
            ),
            onPortalCreated = null,
            portalManagerFactory = { manager },
        )
        val wallet = provider.create(context())

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { wallet.getWalletAddress() }
        }

        assertThat(expiredCalls).isEqualTo(1)
        assertThat(manager.getPortalInstance()).isSameInstanceAs(dead)
        assertThat(provider.currentSessionState()).isEqualTo(PortalSessionState.Expired)
    }

    @Test
    fun `no refresh hook means TokenExpired plus the expiry hook, once`() = runBlocking {
        val manager = managerBackedBy(mapOf("dead" to portalRejecting()))
        var expiredCalls = 0
        val provider = PortalProvider(
            config = PortalConfig(sessionToken = "dead", onSessionExpired = { expiredCalls++ }),
            onPortalCreated = null,
            portalManagerFactory = { manager },
        )
        val wallet = provider.create(context())

        repeat(2) {
            assertThrows(RainError.TokenExpired::class.java) {
                runBlocking { wallet.getWalletAddress() }
            }
        }
        assertThat(expiredCalls).isEqualTo(1)
    }

    @Test
    fun `updateSessionToken swaps the client and re-fires onPortalCreated`() = runBlocking {
        val first = portalReturning("0xfirst")
        val second = portalReturning("0xsecond")
        val manager = managerBackedBy(mapOf("one" to first, "two" to second))
        val created = mutableListOf<Portal>()
        val provider = PortalProvider(
            config = PortalConfig(sessionToken = "one"),
            onPortalCreated = { created += it },
            portalManagerFactory = { manager },
        )
        val wallet = provider.create(context())
        assertThat(wallet.getWalletAddress()).isEqualTo("0xfirst")

        provider.updateSessionToken("two")

        assertThat(wallet.getWalletAddress()).isEqualTo("0xsecond")
        assertThat(created).containsExactly(first, second).inOrder()
    }

    @Test
    fun `refreshSession and updateSessionToken before create fail with SdkNotInitialized and stay silent`() {
        var expiredCalls = 0
        val provider = PortalProvider(
            config = PortalConfig(
                sessionToken = "one",
                onSessionTokenNeeded = { "two" },
                onSessionExpired = { expiredCalls++ },
            ),
            onPortalCreated = null,
            portalManagerFactory = { mockk(relaxed = true) },
        )
        assertThrows(RainError.SdkNotInitialized::class.java) {
            runBlocking { provider.refreshSession() }
        }
        assertThrows(RainError.SdkNotInitialized::class.java) {
            runBlocking { provider.updateSessionToken("two") }
        }
        assertThat(expiredCalls).isEqualTo(0)
        assertThat(provider.currentSessionState()).isEqualTo(PortalSessionState.Unknown)
    }

    @Test
    fun `a send rejected on the pre-flight is retried once on the rebuilt client`() = runBlocking {
        val dead = mockk<Portal>(relaxed = true)
        coEvery { dead.request(any(), any(), any(), null as io.portalhq.android.provider.data.RequestOptions?) } throws
            PortalException.Api.HttpUnauthorized("401 - Unauthorized")
        val fresh = mockk<Portal>(relaxed = true)
        coEvery { fresh.request(any(), any(), any(), null as io.portalhq.android.provider.data.RequestOptions?) } returns
            io.portalhq.android.provider.data.PortalProviderResult(id = "1", result = "0xhash")
        // The chain must know the hash: an unverifiable one is pending, not returned.
        coEvery {
            fresh.request(any(), PortalRequestMethod.eth_getTransactionByHash, any(), null as io.portalhq.android.provider.data.RequestOptions?)
        } returns io.portalhq.android.provider.data.PortalProviderResult(
            id = "1",
            result = io.portalhq.android.provider.data.PortalProviderRpcResponse(jsonrpc = "2.0", result = mapOf("hash" to "0xhash"))
        )
        val manager = managerBackedBy(mapOf("dead" to dead, "fresh" to fresh))
        val provider = PortalProvider(
            config = PortalConfig(sessionToken = "dead", onSessionTokenNeeded = { "fresh" }),
            onPortalCreated = null,
            portalManagerFactory = { manager },
        )
        val wallet = provider.create(context())

        val hash = wallet.sendTransaction(43114, "0xfrom", "0xto", "0x", "0x0")

        assertThat(hash).isEqualTo("0xhash")
        // The dead client only ever saw the pre-flight; the rebuilt one ran the whole send
        // pipeline exactly once. Asserting by method keeps read-only RPCs on the path
        // (block-number reads for the AA hash scan) from being counted as retries.
        val opts = null as io.portalhq.android.provider.data.RequestOptions?
        coVerify(exactly = 1) { dead.request(any(), any(), any(), opts) }
        coVerify(exactly = 1) { dead.request(any(), PortalRequestMethod.eth_call, any(), opts) }
        coVerify(exactly = 1) { fresh.request(any(), PortalRequestMethod.eth_call, any(), opts) }
        coVerify(exactly = 1) { fresh.request(any(), PortalRequestMethod.eth_sendTransaction, any(), opts) }
    }

    @Test
    fun `close silences the expiry hook and tears the client down`() = runBlocking {
        val manager = managerBackedBy(mapOf("dead" to portalRejecting()))
        var expiredCalls = 0
        val provider = PortalProvider(
            config = PortalConfig(sessionToken = "dead", onSessionExpired = { expiredCalls++ }),
            onPortalCreated = null,
            portalManagerFactory = { manager },
        )
        val wallet = provider.create(context())
        provider.close()

        // close() destroys the Portal client, so calls fail as uninitialized rather than
        // reaching Portal and being classified as an expired token.
        assertThrows(RainError.SdkNotInitialized::class.java) {
            runBlocking { wallet.getWalletAddress() }
        }
        assertThat(manager.isInitialized).isFalse()
        assertThat(expiredCalls).isEqualTo(0)
    }

    @Test
    fun `reinitialize before initialize is rejected`() {
        val manager = PortalManager()
        assertThrows(RainError.SdkNotInitialized::class.java) { manager.reinitialize("token") }
    }
}
