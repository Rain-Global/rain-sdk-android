package com.rain.sdk.portal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.NativeCurrency
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import io.portalhq.android.Portal
import io.portalhq.android.exceptions.PortalException
import io.portalhq.android.mpc.data.FeatureFlags
import io.portalhq.android.provider.data.PortalProviderResult
import io.portalhq.android.provider.data.RequestOptions
import io.portalhq.android.storage.mobile.PortalNamespace
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.IOException

/**
 * Error semantics of the native-balance read inside [PortalManager.getBalances]:
 * vendor exceptions from the `eth_getBalance` portal request map to [RainError] rather
 * than leaking raw, auth failures classify as TokenExpired, and a malformed hex payload
 * surfaces as an error instead of a silent zero balance.
 */
class PortalManagerBalanceTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun managerWith(portal: Portal): PortalManager {
        val manager = spyk(PortalManager())
        every { manager.createPortal(any(), any(), any(), any(), any()) } returns portal
        manager.initialize(
            apiKey = "session-token",
            legacyEthChainId = 43114,
            rpcConfig = mapOf("eip155:43114" to "https://rpc.test"),
            featureFlags = FeatureFlags(isMultiBackupEnabled = true),
            autoApprove = true
        )
        return manager
    }

    private fun tokenStore(): TokenMetadataStore {
        val tokenStore = mockk<TokenMetadataStore>()
        every { tokenStore.nativeCurrency(43114) } returns
            NativeCurrency(symbol = "AVAX", name = "Avalanche")
        return tokenStore
    }

    private fun portalWithAddress(): Portal {
        val portal = mockk<Portal>(relaxed = true)
        coEvery { portal.getAddress(PortalNamespace.EIP155) } returns TestFixtures.WALLET_ADDRESS
        return portal
    }

    @Test
    fun `a vendor exception on eth_getBalance surfaces from getBalances as ProviderError`() = runBlocking {
        val portal = portalWithAddress()
        val vendor = RuntimeException("portal provider blew up")
        coEvery { portal.request(any(), any(), any(), null as RequestOptions?) } throws vendor

        val error = runCatching {
            managerWith(portal).getBalances(chainId = 43114, tokenStore = tokenStore())
        }.exceptionOrNull()

        // The raw vendor exception must never leak — it maps to ProviderError with the cause kept.
        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
        assertThat((error as RainError.ProviderError).cause).isSameInstanceAs(vendor)
    }

    @Test
    fun `a read or a fee estimate that got no answer is NetworkError`() = runBlocking {
        val portal = portalWithAddress()
        coEvery { portal.request(any(), any(), any(), null as RequestOptions?) } throws IOException("offline")
        val manager = managerWith(portal)
        val calls = listOf<Pair<String, suspend () -> Any>>(
            "getBalances" to { manager.getBalances(chainId = 43114, tokenStore = tokenStore()) },
            "estimateTransactionFee" to {
                manager.estimateTransactionFee(43114, TestFixtures.WALLET_ADDRESS, TestFixtures.WALLET_ADDRESS, "0x")
            },
        )

        calls.forEach { (name, call) ->
            val error = runCatching { call() }.exceptionOrNull()
            assertWithMessage(name).that(error).isInstanceOf(RainError.NetworkError::class.java)
        }
    }

    @Test
    fun `an HttpUnauthorized on eth_getBalance surfaces from getBalances as TokenExpired`() = runBlocking {
        val portal = portalWithAddress()
        coEvery {
            portal.request(any(), any(), any(), null as RequestOptions?)
        } throws PortalException.Api.HttpUnauthorized("401 Unauthorized")

        val error = runCatching {
            managerWith(portal).getBalances(chainId = 43114, tokenStore = tokenStore())
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `a malformed eth_getBalance payload surfaces as an error instead of a zero balance`() = runBlocking {
        val portal = portalWithAddress()
        // A "0x"-prefixed but non-hex body survives toHexString normalization; the strict
        // parser must reject it rather than reporting a zero native balance.
        val malformed = mockk<PortalProviderResult>()
        every { malformed.result } returns "0xZZZZ"
        coEvery { portal.request(any(), any(), any(), null as RequestOptions?) } returns malformed

        val error = runCatching {
            managerWith(portal).getBalances(chainId = 43114, tokenStore = tokenStore())
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.InternalError::class.java)
        assertThat(error).hasMessageThat().contains("Malformed hex payload")
    }
}
