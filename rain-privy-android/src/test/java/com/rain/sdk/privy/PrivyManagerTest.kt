package com.rain.sdk.privy

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.privy.auth.AuthState
import io.privy.auth.PrivyUser
import io.privy.network.PrivyApiException
import io.privy.sdk.Privy
import io.privy.wallet.ethereum.EmbeddedEthereumWallet
import io.privy.wallet.ethereum.EmbeddedEthereumWalletProvider
import io.privy.wallet.ethereum.EthereumChain
import io.privy.wallet.ethereum.EthereumRpcResponse
import io.privy.wallet.solana.EmbeddedSolanaWallet
import io.privy.wallet.solana.EmbeddedSolanaWalletProvider
import io.privy.wallet.solana.SolanaCluster
import io.privy.wallet.transactions.GetTransactionsParams
import io.privy.wallet.transactions.TransactionChain
import io.privy.wallet.transactions.TransactionsPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PrivyManagerTest {

    private fun wallet(address: String, provider: EmbeddedEthereumWalletProvider): EmbeddedEthereumWallet =
        mockk<EmbeddedEthereumWallet>().also {
            every { it.address } returns address
            every { it.provider } returns provider
        }

    private fun privyWith(wallets: List<EmbeddedEthereumWallet>?): Privy {
        val privy = mockk<Privy>()
        if (wallets == null) {
            coEvery { privy.getUser() } returns null
            every { privy.authState } returns MutableStateFlow(AuthState.Unauthenticated)
        } else {
            val user = mockk<PrivyUser>()
            every { user.embeddedEthereumWallets } returns wallets
            coEvery { privy.getUser() } returns user
            every { privy.authState } returns MutableStateFlow(AuthState.Authenticated(user))
        }
        return privy
    }

    @Test
    fun `resolveWallet throws TokenExpired when no authenticated user`() = runBlocking {
        val manager = PrivyManager(privyWith(null))
        val error = runCatching { manager.getAddress(null) }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `resolveWallet throws WalletUnavailable when user has no embedded wallet`() = runBlocking {
        val manager = PrivyManager(privyWith(emptyList()))
        val error = runCatching { manager.getAddress(null) }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.WalletUnavailable::class.java)
    }

    @Test
    fun `resolveSolanaWallet throws WalletUnavailable when user has no solana wallet`() = runBlocking {
        val privy = privyWith(emptyList())
        val user = privy.getUser()!!
        every { user.embeddedSolanaWallets } returns emptyList()

        val manager = PrivyManager(privy)
        val error = runCatching { manager.getSolanaAddress() }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.WalletUnavailable::class.java)
    }

    @Test
    fun `getSolanaAddress returns the first solana wallet's address`() = runBlocking {
        val privy = privyWith(emptyList())
        val user = privy.getUser()!!
        val solanaWallet = mockk<EmbeddedSolanaWallet>().also {
            every { it.address } returns SOLANA_WALLET
        }
        every { user.embeddedSolanaWallets } returns listOf(solanaWallet)

        assertThat(PrivyManager(privy).getSolanaAddress()).isEqualTo(SOLANA_WALLET)
    }

    @Test
    fun `resolveWallet matches the override case-insensitively`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))
        // Override in a different case than the wallet's stored address still resolves.
        assertThat(manager.getAddress(WALLET.lowercase())).isEqualTo(WALLET)
    }

    @Test
    fun `resolveWallet throws WalletUnavailable when the override matches no wallet`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))
        val error = runCatching { manager.getAddress("0xNOPE") }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.WalletUnavailable::class.java)
    }

    @Test
    fun `resolveWallet falls back to the first wallet when no override is given`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        val first = wallet(WALLET, provider)
        val second = wallet("0xSECOND", provider)
        val manager = PrivyManager(privyWith(listOf(first, second)))
        assertThat(manager.getAddress(null)).isEqualTo(WALLET)
    }

    @Test
    fun `signTypedData returns the provider signature`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        coEvery { provider.request(any()) } returns
            Result.success(EthereumRpcResponse(method = "eth_signTypedData_v4", data = "0xSIG"))
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))

        assertThat(manager.signTypedData(WALLET, "{}")).isEqualTo("0xSIG")
    }

    @Test
    fun `sendTransaction switches to the custom RPC chain then broadcasts`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        every { provider.switchChain(any()) } just Runs
        coEvery { provider.request(any()) } returns
            Result.success(EthereumRpcResponse(method = "eth_sendTransaction", data = "0xHASH"))
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))

        val hash = manager.sendTransaction(walletAddress = WALLET, rpcUrl = RPC, transactionJson = "{}")

        assertThat(hash).isEqualTo("0xHASH")
        coVerify(exactly = 1) { provider.switchChain(EthereumChain.Custom(RPC)) }
    }

    @Test
    fun `a rejection in a non-Privy provider failure keeps its code through the boundary`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        every { provider.switchChain(any()) } just Runs
        val raw = IllegalStateException("user rejected the request")
        coEvery { provider.request(any()) } returns Result.failure(raw)
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))

        val error = runCatching {
            manager.sendTransaction(walletAddress = WALLET, rpcUrl = RPC, transactionJson = "{}")
        }.exceptionOrNull()

        // The manager lets it bubble raw; the session coordinator reads the prose before anything
        // leaves the adapter, so the host sees the rejection, never a generic ProviderError.
        assertThat(error).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `a raw failure in the Solana send leaves the adapter as ProviderError with its cause`() = runBlocking {
        val privy = privyWith(emptyList())
        val user = privy.getUser()!!
        val raw = IllegalStateException("node refused the transaction")
        val solanaProvider = mockk<EmbeddedSolanaWalletProvider>()
        coEvery { solanaProvider.signAndSendTransaction(any(), any(), any()) } throws raw
        val solanaWallet = mockk<EmbeddedSolanaWallet>().also {
            every { it.address } returns SOLANA_WALLET
            every { it.provider } returns solanaProvider
        }
        every { user.embeddedSolanaWallets } returns listOf(solanaWallet)
        val manager = PrivyManager(privy)

        val error = runCatching {
            manager.signAndSendSolanaTransaction(ByteArray(8), SolanaCluster.DevNet, RPC)
        }.exceptionOrNull()

        // The call site rethrows what it cannot map; the session coordinator converts it before it
        // leaves the adapter, so core's withdrawal wrapper sees a RainError, never its InternalError.
        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(error?.cause).isSameInstanceAs(raw)
    }

    @Test
    fun `a Solana send the wallet RPC refused for a funds shortfall leaves as InsufficientFunds`() = runBlocking {
        val privy = privyWith(emptyList())
        val user = privy.getUser()!!
        // Privy's answer to a SOL send from an empty wallet, 2026-10-02 (beta QA PV-SEND-01).
        val refusal = PrivyApiException(
            400,
            null,
            "Error broadcasting transaction with message: Error: Transaction simulation failed: " +
                "Attempt to debit an account but found no record of a prior credit.",
            RuntimeException("HTTP 400"),
        )
        val solanaProvider = mockk<EmbeddedSolanaWalletProvider>()
        coEvery { solanaProvider.signAndSendTransaction(any(), any(), any()) } returns Result.failure(refusal)
        val solanaWallet = mockk<EmbeddedSolanaWallet>().also {
            every { it.address } returns SOLANA_WALLET
            every { it.provider } returns solanaProvider
        }
        every { user.embeddedSolanaWallets } returns listOf(solanaWallet)
        val manager = PrivyManager(privy)

        val error = runCatching {
            manager.signAndSendSolanaTransaction(ByteArray(8), SolanaCluster.DevNet, RPC)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `getTransactions unwraps the page from the resolved wallet`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        val w = wallet(WALLET, provider)
        val page = TransactionsPage(transactions = emptyList(), nextCursor = "cursor-1")
        coEvery { w.getTransactions(any()) } returns Result.success(page)
        val manager = PrivyManager(privyWith(listOf(w)))

        val params = GetTransactionsParams<TransactionChain.Evm>(chain = TransactionChain.Evm.Base, limit = 5)
        assertThat(manager.getTransactions(WALLET, params)).isSameInstanceAs(page)
        coVerify(exactly = 1) { w.getTransactions(params) }
    }

    @Test
    fun `getTransactions leaves an unrecognized failure as ProviderError carrying the cause`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        val w = wallet(WALLET, provider)
        val raw = IllegalStateException("wallet does not support transaction history")
        coEvery { w.getTransactions(any()) } returns Result.failure(raw)
        val manager = PrivyManager(privyWith(listOf(w)))

        val error = runCatching {
            manager.getTransactions(WALLET, GetTransactionsParams(chain = TransactionChain.Evm.Base))
        }.exceptionOrNull()

        // Nothing recognizes this one, so the boundary floors it; the raw failure stays as the cause.
        assertThat(error).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(error!!.cause).isSameInstanceAs(raw)
    }

    @Test
    fun `getTransactions classifies a Privy authentication failure as TokenExpired`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        val w = wallet(WALLET, provider)
        coEvery { w.getTransactions(any()) } returns
            Result.failure(io.privy.auth.AuthenticationException("User must be authenticated before calling refresh."))
        val manager = PrivyManager(privyWith(listOf(w)))

        val error = runCatching {
            manager.getTransactions(WALLET, GetTransactionsParams(chain = TransactionChain.Evm.Base))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `request classifies a Privy authentication failure as TokenExpired`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        coEvery { provider.request(any()) } returns
            Result.failure(io.privy.auth.AuthenticationException("User must be authenticated before calling refresh."))
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))

        val error = runCatching { manager.signTypedData(WALLET, "{}") }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `request classifies a Privy wallet rejection as UserRejected`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        every { provider.switchChain(any()) } just Runs
        coEvery { provider.request(any()) } returns
            Result.failure(io.privy.wallet.EmbeddedWalletException("User rejected the request"))
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))

        val error = runCatching {
            manager.sendTransaction(walletAddress = WALLET, rpcUrl = RPC, transactionJson = "{}")
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `a funds shortfall thrown by the provider call keeps its code through the boundary`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        val raw = RuntimeException("insufficient funds for gas")
        coEvery { provider.request(any()) } throws raw
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))

        val error = runCatching { manager.signTypedData(WALLET, "{}") }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    private companion object {
        const val RPC = "https://rpc.example/1"
        const val WALLET = "0x000000000000000000000000000000000000dEaD"
        const val SOLANA_WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    }
}
