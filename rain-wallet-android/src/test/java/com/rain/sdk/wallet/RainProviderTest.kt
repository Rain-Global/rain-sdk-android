package com.rain.sdk.wallet

import android.app.Activity
import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.WalletProvider
import com.rain.sdk.turnkey.LoginContact
import com.rain.sdk.turnkey.TurnkeyAuthState
import com.rain.sdk.turnkey.TurnkeyKeyFamily
import com.rain.sdk.turnkey.TurnkeyProvider
import com.rain.sdk.turnkey.TurnkeySessionPolicy
import com.rain.sdk.turnkey.TurnkeySessionState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The descriptor forwards every call to its backing provider with a one-to-one mapped argument and
 * returns a one-to-one mapped result. The backing is a MockK stand-in, so each test asserts the
 * call the wrapper made, never the mock's own behaviour.
 */
class RainProviderTest {

    // Lazy, so the vendor type is not touched before a test's JDK guard runs.
    private val backing by lazy { mockk<TurnkeyProvider>() }
    private val provider by lazy { RainProvider(backing) }
    private val activity = mockk<Activity>()
    private val realBacking = RealBackingGuard()

    @After
    fun tearDown() = realBacking.tearDown()

    @Test
    fun `the descriptor id is the Rain wallet id`() {
        assertThat(provider.id).isEqualTo(ProviderId.RAIN)
    }

    @Test
    fun `capabilities come from the backing descriptor`() {
        every { backing.capabilities } returns setOf(Capability.EXPORT, Capability.MULTI_CHAIN)

        assertThat(provider.capabilities).containsExactly(Capability.EXPORT, Capability.MULTI_CHAIN)
    }

    @Test
    fun `create forwards the context and returns the backing wallet`() = runBlocking {
        val context = mockk<ProviderContext>()
        val wallet = mockk<WalletProvider>()
        coEvery { backing.create(context) } returns wallet

        assertThat(provider.create(context)).isSameInstanceAs(wallet)
        coVerify(exactly = 1) { backing.create(context) }
    }

    @Test
    fun `close forwards once`() {
        every { backing.close() } just Runs

        provider.close()

        verify(exactly = 1) { backing.close() }
    }

    @Test
    fun `sessionState maps each emission in order`() = runBlocking {
        // The full state table is pinned in RainWalletMappingTest; this checks the flow is wired.
        every { backing.sessionState } returns flowOf(TurnkeySessionState.Active(7.0), TurnkeySessionState.Expired)

        assertThat(provider.sessionState.toList())
            .containsExactly(RainWalletSessionState.Active(7.0), RainWalletSessionState.Expired)
            .inOrder()
    }

    @Test
    fun `the config maps onto the backing configuration field for field`() {
        useRealBacking()
        val hook: () -> Unit = {}
        val policy = RainWalletSessionPolicy(
            refreshBufferSeconds = 61,
            autoRefresh = false,
            refreshExpirationSeconds = 901,
            maxTransientRetries = 3,
            initialRetryDelayMs = 501,
            maxRetryDelayMs = 4_001,
        )
        val mapped = RainWalletConfig(
            authTokenProvider = { "wat" },
            environment = RainWalletEnvironment.Sandbox,
            sessionPolicy = policy,
            onSessionExpired = hook,
            sponsorGas = false,
            passkeyDomain = "passkeys.example.com",
        ).toBacking(mockk<Application>())

        // No address override exists on the Rain wallet, so the backing always resolves the
        // provisioned Ethereum account.
        assertThat(mapped.walletAddress).isNull()
        assertThat(mapped.managedOrganizationId).isEqualTo(RainWalletBackend.ORGANIZATION_ID)
        assertThat(mapped.managedAuthProxyConfigId).isEqualTo(RainWalletBackend.AUTH_CONFIG_ID)
        assertThat(mapped.managedRainApiBaseUrl).isEqualTo(RainWalletBackend.SANDBOX_API_BASE_URL)
        assertThat(mapped.onSessionExpired).isSameInstanceAs(hook)
        assertThat(mapped.sponsorGas).isFalse()
        assertThat(mapped.managedPasskeyDomain).isEqualTo("passkeys.example.com")
        // The backing policy is a data class, so one assertion pins all six fields.
        assertThat(mapped.sessionPolicy).isEqualTo(
            TurnkeySessionPolicy(
                refreshBufferSeconds = 61L,
                autoRefresh = false,
                refreshExpirationSeconds = 901L,
                maxTransientRetries = 3,
                initialRetryDelayMs = 501L,
                maxRetryDelayMs = 4_001L,
            )
        )
    }

    @Test
    fun `toString names the id, the capabilities and the closed flag, never a contact`() {
        every { backing.capabilities } returns setOf(Capability.EXPORT)
        every { backing.close() } just Runs

        assertThat(provider.toString()).isEqualTo("RainProvider(id=rain, capabilities=[EXPORT], closed=false)")
        provider.close()
        assertThat(provider.toString()).endsWith("closed=true)")
    }

    @Test
    fun `the state flows are the same instance on every read`() {
        every { backing.sessionState } returns flowOf(TurnkeySessionState.Loading)
        every { backing.authState } returns flowOf(TurnkeyAuthState.Loading)

        assertThat(provider.sessionState).isSameInstanceAs(provider.sessionState)
        assertThat(provider.authState).isSameInstanceAs(provider.authState)
    }

    @Test
    fun `currentSessionState maps the snapshot`() {
        every { backing.currentSessionState() } returns TurnkeySessionState.Active(3.0)

        assertThat(provider.currentSessionState()).isEqualTo(RainWalletSessionState.Active(3.0))
    }

    @Test
    fun `refreshSession forwards`() = runBlocking {
        coEvery { backing.refreshSession() } just Runs

        provider.refreshSession()

        coVerify(exactly = 1) { backing.refreshSession() }
    }

    @Test
    fun `authState maps each emission in order`() = runBlocking {
        every { backing.authState } returns flowOf(TurnkeyAuthState.Authenticated, TurnkeyAuthState.Unauthenticated)

        assertThat(provider.authState.toList())
            .containsExactly(RainWalletAuthState.Authenticated, RainWalletAuthState.Unauthenticated)
            .inOrder()
    }

    @Test
    fun `currentAuthState maps the snapshot`() {
        every { backing.currentAuthState() } returns TurnkeyAuthState.Authenticated

        assertThat(provider.currentAuthState()).isEqualTo(RainWalletAuthState.Authenticated)
    }

    @Test
    fun `hasActiveSession forwards the answer`() {
        every { backing.hasActiveSession() } returnsMany listOf(true, false)

        assertThat(provider.hasActiveSession()).isTrue()
        assertThat(provider.hasActiveSession()).isFalse()
    }

    @Test
    fun `awaitSessionRestore forwards the timeout, and five seconds by default`() = runBlocking {
        coEvery { backing.awaitSessionRestore(any()) } just Runs

        provider.awaitSessionRestore(1_234)
        provider.awaitSessionRestore()

        coVerify(exactly = 1) { backing.awaitSessionRestore(1_234) }
        coVerify(exactly = 1) { backing.awaitSessionRestore(5_000) }
    }

    @Test
    fun `sendLoginCode forwards each channel as the matching backing contact`() = runBlocking {
        coEvery { backing.sendLoginCode(any<LoginContact>()) } just Runs

        provider.sendLoginCode(RainWalletContact.Email("user@example.com"))
        provider.sendLoginCode(RainWalletContact.Phone("+15551234567"))
        provider.sendLoginCode(RainWalletContact.Email("overload@example.com"))

        coVerify(exactly = 1) { backing.sendLoginCode(LoginContact.Email("user@example.com")) }
        coVerify(exactly = 1) { backing.sendLoginCode(LoginContact.Phone("+15551234567")) }
        coVerify(exactly = 1) { backing.sendLoginCode(LoginContact.Email("overload@example.com")) }
    }

    @Test
    fun `confirmLoginCode forwards the code`() = runBlocking {
        coEvery { backing.confirmLoginCode("481902") } just Runs

        provider.confirmLoginCode("481902")

        coVerify(exactly = 1) { backing.confirmLoginCode("481902") }
    }

    @Test
    fun `logout forwards`() = runBlocking {
        coEvery { backing.logout() } just Runs

        provider.logout()

        coVerify(exactly = 1) { backing.logout() }
    }

    @Test
    fun `loginWithPasskey forwards the activity`() = runBlocking {
        coEvery { backing.loginWithPasskey(activity) } just Runs

        provider.loginWithPasskey(activity)

        coVerify(exactly = 1) { backing.loginWithPasskey(activity) }
    }

    @Test
    fun `signUpWithPasskey forwards the activity`() = runBlocking {
        coEvery { backing.signUpWithPasskey(activity) } just Runs

        provider.signUpWithPasskey(activity)

        coVerify(exactly = 1) { backing.signUpWithPasskey(activity) }
    }

    @Test
    fun `addPasskey forwards the activity`() = runBlocking {
        coEvery { backing.addPasskey(activity) } just Runs

        provider.addPasskey(activity)

        coVerify(exactly = 1) { backing.addPasskey(activity) }
    }

    @Test
    fun `sendContactVerificationCode forwards each channel as the matching backing contact`() = runBlocking {
        coEvery { backing.sendContactVerificationCode(any<LoginContact>()) } just Runs

        provider.sendContactVerificationCode(RainWalletContact.Email("user@example.com"))
        provider.sendContactVerificationCode(RainWalletContact.Phone("+15551234567"))

        coVerify(exactly = 1) { backing.sendContactVerificationCode(LoginContact.Email("user@example.com")) }
        coVerify(exactly = 1) { backing.sendContactVerificationCode(LoginContact.Phone("+15551234567")) }
    }

    @Test
    fun `confirmContactVerification forwards the code`() = runBlocking {
        coEvery { backing.confirmContactVerification("481902") } just Runs

        provider.confirmContactVerification("481902")

        coVerify(exactly = 1) { backing.confirmContactVerification("481902") }
    }

    @Test
    fun `exportRecoveryPhrase forwards and returns the phrase`() = runBlocking {
        coEvery { backing.exportRecoveryPhrase() } returns "twelve words"

        assertThat(provider.exportRecoveryPhrase()).isEqualTo("twelve words")
    }

    @Test
    fun `exportPrivateKey forwards the matching key family`() = runBlocking {
        coEvery { backing.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM) } returns "0xabc"
        coEvery { backing.exportPrivateKey(TurnkeyKeyFamily.SOLANA) } returns "base58"

        assertThat(provider.exportPrivateKey(RainWalletKeyAccount.ETHEREUM)).isEqualTo("0xabc")
        assertThat(provider.exportPrivateKey(RainWalletKeyAccount.SOLANA)).isEqualTo("base58")
    }

    @Test
    fun `a RainError from the backing passes through unchanged`() {
        val failure = RainError.TokenExpired()
        coEvery { backing.confirmLoginCode("1") } throws failure

        val thrown = assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { provider.confirmLoginCode("1") }
        }

        assertThat(thrown).isSameInstanceAs(failure)
    }

    @Test
    fun `a cancellation from the backing passes through unchanged`() {
        val cancellation = CancellationException("caller went away")
        coEvery { backing.refreshSession() } throws cancellation

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { provider.refreshSession() }
        }

        assertThat(thrown).isSameInstanceAs(cancellation)
    }

    @Test
    fun `a passkey call passes a RainError and a cancellation through unchanged`() {
        val refused = RainError.UserRejected()
        val cancellation = CancellationException("caller went away")
        coEvery { backing.loginWithPasskey(activity) } throws refused
        coEvery { backing.addPasskey(activity) } throws cancellation

        val thrownError = assertThrows(RainError.UserRejected::class.java) {
            runBlocking { provider.loginWithPasskey(activity) }
        }
        val thrownCancellation = assertThrows(CancellationException::class.java) {
            runBlocking { provider.addPasskey(activity) }
        }

        assertThat(thrownError).isSameInstanceAs(refused)
        assertThat(thrownCancellation).isSameInstanceAs(cancellation)
    }

    @Test
    fun `the public constructor yields the Rain id and the backing capabilities`() {
        useRealBacking()

        // The default environment, Production, is refused this release, so the sandbox is named.
        val sandbox = RainWalletEnvironment.Sandbox
        val application = mockk<Application>()
        val sponsored = RainProvider(application, RainWalletConfig(authTokenProvider = { "wat" }, environment = sandbox, sponsorGas = true))
        val unsponsored = RainProvider(application, RainWalletConfig(authTokenProvider = { "wat" }, environment = sandbox, sponsorGas = false))
        try {
            assertThat(sponsored.id).isEqualTo(ProviderId.RAIN)
            assertThat(sponsored.capabilities).containsExactly(
                Capability.EXPORT,
                Capability.MULTI_CHAIN,
                Capability.GAS_SPONSORSHIP,
            )
            assertThat(unsponsored.capabilities).containsExactly(
                Capability.EXPORT,
                Capability.MULTI_CHAIN,
            )
        } finally {
            // A real backing owns a monitor scope from construction; close() is what cancels it.
            sponsored.close()
            unsponsored.close()
        }
    }

    private fun useRealBacking() = realBacking.useRealBacking()
}
