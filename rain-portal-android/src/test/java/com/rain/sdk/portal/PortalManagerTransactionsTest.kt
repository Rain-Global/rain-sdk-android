package com.rain.sdk.portal

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.NativeCurrency
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import io.portalhq.android.Portal
import io.portalhq.android.api.data.Transaction
import io.portalhq.android.exceptions.PortalException
import io.portalhq.android.mpc.data.FeatureFlags
import io.portalhq.android.provider.data.RequestOptions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Symbol resolution for transaction history: native transfers resolve the chain's native
 * currency from the registry (no more hardcoded "AVAX"), and unknown chains yield a null
 * symbol rather than a wrong one.
 */
class PortalManagerTransactionsTest {

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

    /** A native transfer: no rawContract, value already parsed by Portal. */
    private fun nativeTransfer(chainId: Int) = Transaction(
        blockNum = "100",
        uniqueId = "unique-1",
        hash = "0xabc",
        from = "0xfrom",
        to = "0xto",
        value = 1.5,
        erc721TokenId = null,
        erc1155Metadata = null,
        tokenId = null,
        asset = null,
        category = "external",
        rawContract = null,
        metadata = null,
        chainId = chainId
    )

    /** An ERC-20 transfer: rawContract present, value already parsed by Portal. */
    private fun contractTransfer(chainId: Int, contractAddress: String) = Transaction(
        blockNum = "100",
        uniqueId = "unique-2",
        hash = "0xdef",
        from = "0xfrom",
        to = "0xto",
        value = 1.5,
        erc721TokenId = null,
        erc1155Metadata = null,
        tokenId = null,
        asset = null,
        category = "erc20",
        rawContract = Transaction.RawContract(value = null, address = contractAddress, decimal = "6"),
        metadata = null,
        chainId = chainId
    )

    @Test
    fun `native transfer asset resolves from the chain's native currency`() = runBlocking {
        val portal = mockk<Portal>(relaxed = true)
        coEvery {
            portal.api.getTransactions(any(), any(), any(), any())
        } returns Result.success(listOf(nativeTransfer(43114)))
        val tokenStore = mockk<TokenMetadataStore>()
        every { tokenStore.nativeCurrencyOrNull(43114) } returns
            NativeCurrency(symbol = "AVAX", name = "Avalanche")

        val result = managerWith(portal).getTransactions(chainId = 43114, tokenStore = tokenStore)

        assertThat(result).hasSize(1)
        assertThat(result.single().asset).isEqualTo("AVAX")
    }

    @Test
    fun `an expired token on the ERC-20 symbol eth_call surfaces as TokenExpired, not null metadata`() {
        val contractAddress = "0x9876543210987654321098765432109876543210"
        val portal = mockk<Portal>(relaxed = true)
        coEvery {
            portal.api.getTransactions(any(), any(), any(), any())
        } returns Result.success(listOf(contractTransfer(43114, contractAddress)))
        coEvery {
            portal.request(any(), any(), any(), null as RequestOptions?)
        } throws PortalException.Api.HttpUnauthorized("401 - Unauthorized")
        val tokenStore = mockk<TokenMetadataStore>()
        every { tokenStore.nativeCurrencyOrNull(43114) } returns
            NativeCurrency(symbol = "AVAX", name = "Avalanche")

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { managerWith(portal).getTransactions(chainId = 43114, tokenStore = tokenStore) }
        }
    }

    @Test
    fun `contract transfer symbol is null when the on-chain symbol fetch fails`() = runBlocking {
        val contractAddress = "0x9876543210987654321098765432109876543210"
        val portal = mockk<Portal>(relaxed = true)
        coEvery {
            portal.api.getTransactions(any(), any(), any(), any())
        } returns Result.success(listOf(contractTransfer(43114, contractAddress)))
        // The ERC-20 symbol() eth_call fails; the old code fell back to a hardcoded "AVAX".
        coEvery {
            portal.request(any(), any(), any(), null as RequestOptions?)
        } throws RuntimeException("symbol fetch failed")
        val tokenStore = mockk<TokenMetadataStore>()
        every { tokenStore.nativeCurrencyOrNull(43114) } returns
            NativeCurrency(symbol = "AVAX", name = "Avalanche")

        val result = managerWith(portal).getTransactions(chainId = 43114, tokenStore = tokenStore)

        assertThat(result).hasSize(1)
        val transaction = result.single()
        assertThat(transaction.asset).isNull()
        assertThat(transaction.tokenAddress).isEqualTo(contractAddress)
    }

    @Test
    fun `repeated transfers in one token resolve its metadata once, not per row`() = runBlocking {
        val contractAddress = "0x9876543210987654321098765432109876543210"
        val portal = mockk<Portal>(relaxed = true)
        // Five rows, same token: the old code issued one symbol() eth_call per row.
        coEvery {
            portal.api.getTransactions(any(), any(), any(), any())
        } returns Result.success(List(5) { contractTransfer(43114, contractAddress) })
        coEvery {
            portal.request(any(), any(), any(), null as RequestOptions?)
        } throws RuntimeException("symbol fetch failed")
        val tokenStore = mockk<TokenMetadataStore>()
        every { tokenStore.nativeCurrencyOrNull(43114) } returns
            NativeCurrency(symbol = "AVAX", name = "Avalanche")

        val result = managerWith(portal).getTransactions(chainId = 43114, tokenStore = tokenStore)

        assertThat(result).hasSize(5)
        // Rows carry rawContract.decimal, so only symbol() is needed — once for the one address.
        coVerify(exactly = 1) { portal.request(any(), any(), any(), null as RequestOptions?) }
    }

    @Test
    fun `native transfer asset is null for a chain the registry does not know`() = runBlocking {
        val unknownChainId = 999999
        val portal = mockk<Portal>(relaxed = true)
        coEvery {
            portal.api.getTransactions(any(), any(), any(), any())
        } returns Result.success(listOf(nativeTransfer(unknownChainId)))
        val tokenStore = mockk<TokenMetadataStore>()
        every { tokenStore.nativeCurrencyOrNull(unknownChainId) } returns null

        val result = managerWith(portal).getTransactions(chainId = unknownChainId, tokenStore = tokenStore)

        assertThat(result).hasSize(1)
        assertThat(result.single().asset).isNull()
    }
}
