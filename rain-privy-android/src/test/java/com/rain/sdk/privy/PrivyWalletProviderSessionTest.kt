package com.rain.sdk.privy

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.privy.auth.AuthState
import io.privy.auth.PrivyUser
import io.privy.sdk.Privy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Session-death behavior through the wallet provider: cached-account eviction, and the
 * guard against an in-flight resolution writing a dead session's address back into the cache.
 */
class PrivyWalletProviderSessionTest {

    private fun authenticatedPrivy(auth: MutableStateFlow<AuthState>): Privy {
        val privy = mockk<Privy>()
        every { privy.authState } returns auth
        return privy
    }

    private fun provider(
        manager: PrivyManager,
        coordinator: PrivySessionCoordinator,
    ) = PrivyWalletProvider(
        manager = manager,
        rpcEndpoints = mapOf(1 to "https://rpc.example/1"),
        tokenStore = mockk<TokenMetadataStore>(),
        rpcClient = mockk(),
        sessionCoordinator = coordinator,
    )

    @Test
    fun `session death evicts the cached address so the next call re-resolves`() = runTest {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val coordinator = PrivySessionCoordinator(
            privy = authenticatedPrivy(auth),
            retryDelay = { },
        )
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(any()) } returns "0xFIRST" andThen "0xSECOND"
        val walletProvider = provider(manager, coordinator)
        val monitorScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        coordinator.startMonitoring(monitorScope)
        runCurrent()

        assertThat(walletProvider.getWalletAddress()).isEqualTo("0xFIRST")
        // Cached: a second call must not hit the manager.
        assertThat(walletProvider.getWalletAddress()).isEqualTo("0xFIRST")
        coVerify(exactly = 1) { manager.getAddress(any()) }

        auth.value = AuthState.Unauthenticated
        runCurrent()

        assertThat(walletProvider.getWalletAddress()).isEqualTo("0xSECOND")
        coVerify(exactly = 2) { manager.getAddress(any()) }
        monitorScope.cancel()
    }

    @Test
    fun `eviction during an in-flight resolution does not repopulate the cache`() = runTest {
        val user = mockk<PrivyUser>()
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(user))
        val coordinator = PrivySessionCoordinator(
            privy = authenticatedPrivy(auth),
            retryDelay = { },
        )
        val manager = mockk<PrivyManager>()
        val gate = CompletableDeferred<String>()
        coEvery { manager.getAddress(any()) } coAnswers { gate.await() } andThen "0xFRESH"
        val walletProvider = provider(manager, coordinator)
        val monitorScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        coordinator.startMonitoring(monitorScope)
        runCurrent()

        // The resolution suspends mid-flight...
        val inFlight = async { walletProvider.getWalletAddress() }
        runCurrent()
        assertThat(inFlight.isCompleted).isFalse()

        // ...the session dies (eviction bumps the epoch)...
        auth.value = AuthState.Unauthenticated
        runCurrent()

        // ...and the stale result completes. It is returned to its caller but NOT cached.
        gate.complete("0xSTALE")
        assertThat(inFlight.await()).isEqualTo("0xSTALE")

        assertThat(walletProvider.getWalletAddress()).isEqualTo("0xFRESH")
        coVerify(exactly = 2) { manager.getAddress(any()) }
        monitorScope.cancel()
    }
}
