package com.rain.sdk.privy

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.NativeCurrency
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.privy.sdk.Privy
import io.privy.wallet.transactions.TransactionsPage
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PrivyProviderTest {

    @Test
    fun `descriptor advertises the privy id and capabilities`() {
        val provider = PrivyProvider(PrivyConfig(privy = mockk<Privy>()))
        assertThat(provider.id).isEqualTo(ProviderId.PRIVY)
        assertThat(provider.capabilities)
            .containsExactly(Capability.EXPORT, Capability.RECOVERY, Capability.MULTI_CHAIN)
    }

    @Test
    fun `wallet provider reports the privy id`() {
        val wallet = PrivyWalletProvider(
            manager = mockk(),
            rpcEndpoints = emptyMap(),
            tokenStore = mockk<TokenMetadataStore>(),
        )
        assertThat(wallet.id).isEqualTo(ProviderId.PRIVY)
    }

    @Test
    fun `wallet provider advertises the same capabilities as its descriptor`() {
        // A resolved RainClient must not deny a capability the descriptor advertised — Privy
        // signs on Solana as well as EVM, so MULTI_CHAIN has to appear on both.
        val descriptor = PrivyProvider(PrivyConfig(privy = mockk<Privy>()))
        val wallet = PrivyWalletProvider(
            manager = mockk(),
            rpcEndpoints = emptyMap(),
            tokenStore = mockk<TokenMetadataStore>(),
        )
        assertThat(wallet.capabilities).isEqualTo(descriptor.capabilities)
        assertThat(wallet.capabilities).contains(Capability.MULTI_CHAIN)
    }

    @Test
    fun `getWalletAddress uses the override without hitting the manager`() = runBlocking {
        val manager = mockk<PrivyManager>()
        val wallet = PrivyWalletProvider(
            manager = manager,
            rpcEndpoints = emptyMap(),
            tokenStore = mockk<TokenMetadataStore>(),
            walletAddressOverride = "0xOVERRIDE",
        )
        assertThat(wallet.getWalletAddress()).isEqualTo("0xOVERRIDE")
        coVerify(exactly = 0) { manager.getAddress(any()) }
    }

    @Test
    fun `getWalletAddress delegates to the manager and caches`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns "0xWALLET"
        val wallet = PrivyWalletProvider(
            manager = manager,
            rpcEndpoints = emptyMap(),
            tokenStore = mockk<TokenMetadataStore>(),
        )
        assertThat(wallet.getWalletAddress()).isEqualTo("0xWALLET")
        assertThat(wallet.getWalletAddress()).isEqualTo("0xWALLET")
        coVerify(exactly = 1) { manager.getAddress(null) }
    }

    @Test
    fun `signTypedData delegates to the manager`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.signTypedData("0xWALLET", "{}") } returns "0xSIG"
        val wallet = PrivyWalletProvider(
            manager = manager,
            rpcEndpoints = emptyMap(),
            tokenStore = mockk<TokenMetadataStore>(),
        )
        val sig = wallet.signTypedData(chainId = 1, walletAddress = "0xWALLET", typedDataJson = "{}")
        assertThat(sig).isEqualTo("0xSIG")
    }

    @Test
    fun `getTransactions fetches history through the manager on an indexed chain`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns "0xWALLET"
        coEvery { manager.getTransactions("0xWALLET", any()) } returns
            TransactionsPage(transactions = emptyList(), nextCursor = null)
        val tokenStore = mockk<TokenMetadataStore>()
        coEvery { tokenStore.registeredTokens(1) } returns emptyList()
        val wallet = PrivyWalletProvider(
            manager = manager,
            rpcEndpoints = emptyMap(),
            tokenStore = tokenStore,
        )
        val result = wallet.getTransactions(chainId = 1)
        assertThat(result).isEmpty()
        // No registered tokens, so history is a single native-asset query.
        coVerify(exactly = 1) { manager.getTransactions("0xWALLET", any()) }
    }

    @Test
    fun `getBalance native reads eth_getBalance via rpc`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        val tokenStore = mockk<TokenMetadataStore>()
        every { tokenStore.nativeCurrency(1) } returns NativeCurrency("ETH", "Ether", 18)
        val rpc = mockk<PrivyRpcClient>()
        coEvery { rpc.callForHexResult(RPC, "eth_getBalance", listOf(WALLET, "latest")) } returns "0x0de0b6b3a7640000"

        val wallet = PrivyWalletProvider(manager, mapOf(1 to RPC), tokenStore, rpcClient = rpc)
        val balance = wallet.getBalance(1, Token.Native)

        assertThat(balance.token).isEqualTo(Token.Native)
        assertThat(balance.rawAmount).isEqualTo(java.math.BigInteger("1000000000000000000"))
        assertThat(balance.symbol).isEqualTo("ETH")
    }

    @Test
    fun `getBalances includes native and drops zero-balance tokens`() = runBlocking<Unit> {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        val tokenStore = mockk<TokenMetadataStore>()
        every { tokenStore.nativeCurrency(1) } returns NativeCurrency("ETH", "Ether", 18)
        coEvery { tokenStore.registeredTokens(1) } returns listOf(
            TokenInfo(chainId = 1, address = "0xUSDC", symbol = "USDC", decimals = 6, name = "USD Coin"),
            TokenInfo(chainId = 1, address = "0xZERO", symbol = "ZERO", decimals = 18, name = "Zero"),
        )
        coEvery { tokenStore.tokenInfo(1, "0xUSDC") } returns
            TokenInfo(1, "0xUSDC", "USDC", 6, "USD Coin")
        coEvery { tokenStore.tokenInfo(1, "0xZERO") } returns
            TokenInfo(1, "0xZERO", "ZERO", 18, "Zero")
        val rpc = mockk<PrivyRpcClient>()
        coEvery { rpc.callForHexResult(RPC, "eth_getBalance", any()) } returns "0x1"
        coEvery { rpc.callForHexResult(RPC, "eth_call", any()) } returnsMany listOf("0x5", "0x0")

        val wallet = PrivyWalletProvider(manager, mapOf(1 to RPC), tokenStore, rpcClient = rpc)
        val balances = wallet.getBalances(1)

        // Native + the non-zero USDC only; the zero-balance token is dropped.
        assertThat(balances.map { it.token }).containsExactly(Token.Native, Token.Contract("0xUSDC"))
    }

    private companion object {
        const val RPC = "https://rpc.example/1"
        const val WALLET = "0x000000000000000000000000000000000000dEaD"
    }
}
