package com.rain.sdk.turnkey

import android.app.Activity
import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.solana.SolanaSupport
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.provider.ProviderContext
import com.turnkey.core.TurnkeyContext
import com.turnkey.types.V1AddressFormat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The managed-auth surface of [TurnkeyProvider]: the two [TurnkeyConfig] modes, the BYO guard,
 * forwarding to the controller through the real wiring (only the vendor context and the Rain
 * client are faked), and account provisioning at resolution.
 *
 * The BYO constructor takes the vendor's `TurnkeyContext` singleton, whose class initializer
 * dispatches onto `Dispatchers.Main`; on the JVM that dispatcher is absent, so a test dispatcher
 * stands in for it. Nothing dispatched there is ever run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnkeyManagedProviderTest {

    /** True only once setMain ran: on a JDK the assumption skips, @After still runs. */
    private var mainSet = false

    @Before
    fun setUp() {
        assumeJdk24()
        Dispatchers.setMain(StandardTestDispatcher())
        mainSet = true
        TurnkeyManagedConfigurator.resetForTest()
        TurnkeyManagedConfigurator.initImpl = { _, _, _, _ -> }
        TurnkeyManagedConfigurator.vendorInitializedProbe = { false }
    }

    @After
    fun tearDown() {
        TurnkeyManagedConfigurator.resetForTest()
        if (mainSet) Dispatchers.resetMain()
    }

    private val tokenRequests = mutableListOf<LoginContact?>()

    private fun managedConfig(
        organizationId: String = "org-a",
        rainApiBaseUrl: String = "https://api-dev.rain.xyz",
        passkeyDomain: String? = null,
        authTokenProvider: suspend (LoginContact?) -> String = { "wat_1" },
    ) = TurnkeyConfig(
        application = mockk<Application>(),
        organizationId = organizationId,
        rainApiBaseUrl = rainApiBaseUrl,
        authTokenProvider = { contact ->
            tokenRequests += contact
            authTokenProvider(contact)
        },
        passkeyDomain = passkeyDomain,
    )

    /** The system sheet's anchor; the mock never touches it. */
    private val activity: Activity = mockk(relaxed = true)

    /** The vendor-free context core hands every descriptor; nothing here hits the network. */
    private fun providerContext(): ProviderContext {
        val rpcEndpoints = mapOf(1 to "http://127.0.0.1:1/unused")
        val evm = EvmChainReader(rpcEndpoints = rpcEndpoints)
        return ProviderContext(
            rpcEndpoints = rpcEndpoints,
            tokenStore = TokenMetadataStore(chainReader = evm),
            evmChainReader = evm,
            solanaSupport = SolanaSupport(rpcEndpoints)
        )
    }

    // ---------- bring-your-own mode ----------

    @Test
    fun `BYO mode keeps its constructor and exposes no authentication`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithVectorAccounts()))
        val provider = TurnkeyProvider(
            TurnkeyConfig(turnkey = TurnkeyContext, sponsorGas = false),
            contextOverride = turnkey,
        )

        assertThat(provider.hasActiveSession()).isFalse()
        assertThat(provider.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        assertThat(provider.authState.first()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        provider.awaitSessionRestore(timeoutMs = 10) // no-op, must not throw
        val thrown = expectThrows<RainError.InvalidConfig> { provider.sendLoginCode(LoginContact.Email("user@example.com")) }
        assertThat(thrown).hasMessageThat().contains("managed mode")
        expectThrows<RainError.InvalidConfig> { provider.sendLoginCode(LoginContact.Phone("+19999999999")) }
        expectThrows<RainError.InvalidConfig> { provider.confirmLoginCode("123456") }
        expectThrows<RainError.InvalidConfig> { provider.logout() }
        assertThat(turnkey.createdDeviceKeys).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
        assertThat(TurnkeyConfig(turnkey = TurnkeyContext).managedRainApiBaseUrl).isNull()
        assertThat(TurnkeyConfig(turnkey = TurnkeyContext).managedAuthTokenProvider).isNull()

        // Key export is not an authentication member: with the host's live session it works here too.
        assertThat(provider.exportRecoveryPhrase()).isEqualTo(turnkey.stubbedMnemonic)
        assertThat(provider.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM)).isEqualTo("0x" + turnkey.stubbedKeyHex)
        assertThat(provider.exportPrivateKey(TurnkeyKeyFamily.SOLANA)).isEqualTo(MockTurnkey.VECTOR_SOLANA_KEYPAIR)
        assertThat(turnkey.exportMnemonicCalls).containsExactly("wallet-id")
        assertThat(turnkey.awaitReadyCallCount).isEqualTo(0) // no managed configuration to run
    }

    // ---------- managed mode ----------

    @Test
    fun `managed mode exports after running the one-shot configuration`() = runTest {
        val configured = mutableListOf<Pair<String, String>>()
        TurnkeyManagedConfigurator.initImpl = { _, organizationId, rainApiBaseUrl, _ ->
            configured += organizationId to rainApiBaseUrl
        }
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithVectorAccounts())) // restored live session
        val provider = TurnkeyProvider(managedConfig("org-a", "https://api-dev.rain.xyz/"), contextOverride = turnkey)

        val phrase = provider.exportRecoveryPhrase()

        assertThat(phrase).isEqualTo(turnkey.stubbedMnemonic)
        // The base URL is keyed without its trailing slash, however the host wrote it.
        assertThat(configured).containsExactly("org-a" to "https://api-dev.rain.xyz")
        assertThat(turnkey.awaitReadyCallCount).isEqualTo(1)
        assertThat(turnkey.exportMnemonicCalls).containsExactly("wallet-id")

        assertThat(provider.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM)).isEqualTo("0x" + turnkey.stubbedKeyHex)
        assertThat(provider.exportPrivateKey(TurnkeyKeyFamily.SOLANA)).isEqualTo(MockTurnkey.VECTOR_SOLANA_KEYPAIR)
        assertThat(turnkey.exportAccountKeyCalls)
            .containsExactly(MockTurnkey.VECTOR_ETHEREUM_ADDRESS, MockTurnkey.VECTOR_SOLANA_ADDRESS)
            .inOrder()
    }

    @Test
    fun `managed mode configures the vendor with the config's ids on the first auth call, not at construction`() = runTest {
        val configured = mutableListOf<Pair<String, String>>()
        TurnkeyManagedConfigurator.initImpl = { _, organizationId, rainApiBaseUrl, _ ->
            configured += organizationId to rainApiBaseUrl
        }
        val turnkey = MockTurnkey(session = null)
        val rainApi = MockRainApiAuth()
        val provider = TurnkeyProvider(managedConfig("org-a"), contextOverride = turnkey, rainApiOverride = rainApi)
        assertThat(configured).isEmpty()

        provider.sendLoginCode(LoginContact.Email("user@example.com"))

        assertThat(configured).containsExactly("org-a" to "https://api-dev.rain.xyz")
        assertThat(tokenRequests).containsExactly(LoginContact.Email("user@example.com"))
        assertThat(rainApi.initOtpCalls).containsExactly("wat_1")
    }

    @Test
    fun `managed mode asks the config's token provider for an SMS send with the canonical number`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val rainApi = MockRainApiAuth()
        val provider = TurnkeyProvider(managedConfig(), contextOverride = turnkey, rainApiOverride = rainApi)

        provider.sendLoginCode(LoginContact.Phone("+1 999 999 9999"))

        assertThat(tokenRequests).containsExactly(LoginContact.Phone("+19999999999"))
        assertThat(rainApi.initOtpCalls).containsExactly("wat_1")
    }

    @Test
    fun `managed mode forwards the OTP flow and reports the session it produced`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        turnkey.onStoreSession = { turnkey.authenticate() }
        val rainApi = MockRainApiAuth()
        val provider = TurnkeyProvider(managedConfig(), contextOverride = turnkey, rainApiOverride = rainApi)
        assertThat(provider.hasActiveSession()).isFalse()

        provider.sendLoginCode(LoginContact.Email("user@example.com"))
        provider.confirmLoginCode("123456")

        assertThat(rainApi.verifyOtpCalls).hasSize(1)
        assertThat(rainApi.loginCalls).hasSize(1)
        assertThat(turnkey.storeSessionCalls).hasSize(1)
        assertThat(provider.hasActiveSession()).isTrue()
        assertThat(provider.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
        assertThat(provider.authState.first()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `resolving a managed provider provisions a half-provisioned restored session before the wallet probe`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.defaultWallet())) // live session, Ethereum only
        turnkey.onCreateWalletAccounts = { turnkey.wallets = listOf(MockTurnkey.walletWithEthAndSolana()) }
        val provider = TurnkeyProvider(managedConfig(), contextOverride = turnkey)
        try {
            val wallet = provider.create(providerContext())

            val added = turnkey.createWalletAccountsCalls.single()
            assertThat(added.walletId).isEqualTo("wallet-id")
            assertThat(added.accounts.map { it.addressFormat })
                .containsExactly(V1AddressFormat.ADDRESS_FORMAT_SOLANA)
            assertThat(turnkey.createWalletCalls).isEmpty()
            assertThat(wallet.getWalletAddress()).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        } finally {
            provider.close()
        }
    }

    @Test
    fun `a managed login over a live session hands the wallet create built the new user's address`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        val provider = TurnkeyProvider(managedConfig(), contextOverride = turnkey, rainApiOverride = MockRainApiAuth())
        try {
            val wallet = provider.create(providerContext())
            assertThat(wallet.getWalletAddress()).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)

            // Another user logs in over the live session; the vendor now lists that user's wallet.
            val other = "0x9999999999999999999999999999999999999999"
            turnkey.onStoreSession = {
                val reAddressed = MockTurnkey.walletWithEthereumAddress(other)
                turnkey.wallets = listOf(reAddressed.copy(accounts = reAddressed.accounts + MockTurnkey.solanaAccount()))
            }
            provider.sendLoginCode(LoginContact.Email("other@example.com"))
            provider.confirmLoginCode("123456")

            // The replacement advanced the coordinator the manager shares with the descriptor, so the
            // cached address is stale and the next read resolves the new user's wallet.
            assertThat(wallet.getWalletAddress()).isEqualTo(other)
        } finally {
            provider.close()
        }
    }

    @Test
    fun `resolving a BYO provider never provisions`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.defaultWallet()))
        val provider = TurnkeyProvider(TurnkeyConfig(turnkey = TurnkeyContext), contextOverride = turnkey)
        try {
            provider.create(providerContext())
            assertThat(turnkey.createWalletCalls).isEmpty()
        } finally {
            provider.close()
        }
    }

    @Test
    fun `a blank organization id is rejected on the first auth call with InvalidConfig`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val rainApi = MockRainApiAuth()
        val provider = TurnkeyProvider(managedConfig(organizationId = "  "), contextOverride = turnkey, rainApiOverride = rainApi)

        expectThrows<RainError.InvalidConfig> { provider.sendLoginCode(LoginContact.Email("user@example.com")) }
        assertThat(tokenRequests).isEmpty()
        assertThat(rainApi.initOtpCalls).isEmpty()
    }

    @Test
    fun `a refresh the backend refused reads signed out on the provider as soon as it returns`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.refreshSessionError = revokedSessionRefreshFailure()
        var hookCalls = 0
        val provider = TurnkeyProvider(
            TurnkeyConfig(
                application = mockk<Application>(),
                organizationId = "org-a",
                rainApiBaseUrl = "https://api-dev.rain.xyz",
                authTokenProvider = { "wat_1" },
                onSessionExpired = { hookCalls++ },
            ),
            contextOverride = turnkey,
        )
        assertThat(provider.hasActiveSession()).isTrue()

        expectThrows<RainError.TokenExpired> { provider.refreshSession() }

        // The public reads a login screen keys on, before the scheduler runs anything else.
        assertThat(provider.hasActiveSession()).isFalse()
        assertThat(provider.currentAuthState()).isEqualTo(TurnkeyAuthState.Unauthenticated)
        assertThat(provider.currentSessionState()).isEqualTo(TurnkeySessionState.Unauthenticated)
        assertThat(turnkey.clearSessionCalls).containsExactly(MockTurnkey.DEFAULT_SESSION_KEY)
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `a closed managed provider refuses authentication and reads unauthenticated`() = runTest {
        val turnkey = MockTurnkey()
        val provider = TurnkeyProvider(managedConfig(), contextOverride = turnkey)

        provider.close()

        expectThrows<RainError.InvalidConfig> { provider.sendLoginCode(LoginContact.Email("user@example.com")) }
        expectThrows<RainError.InvalidConfig> { provider.sendLoginCode(LoginContact.Phone("+19999999999")) }
        expectThrows<RainError.InvalidConfig> { provider.logout() }
        assertThat(provider.hasActiveSession()).isFalse()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)

        val phrase = expectThrows<RainError.InvalidConfig> { provider.exportRecoveryPhrase() }
        val key = expectThrows<RainError.InvalidConfig> { provider.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM) }
        assertThat(phrase).hasMessageThat().contains(TURNKEY_PROVIDER_CLOSED_MESSAGE)
        assertThat(key).hasMessageThat().contains(TURNKEY_PROVIDER_CLOSED_MESSAGE)
        assertThat(turnkey.exportMnemonicCalls).isEmpty()
        assertThat(turnkey.exportAccountKeyCalls).isEmpty()

        // A closed bring-your-own provider refuses the same way, with no vendor call.
        val byo = TurnkeyProvider(TurnkeyConfig(turnkey = TurnkeyContext), contextOverride = turnkey)
        byo.close()
        expectThrows<RainError.InvalidConfig> { byo.exportRecoveryPhrase() }
        expectThrows<RainError.InvalidConfig> { byo.exportPrivateKey(TurnkeyKeyFamily.SOLANA) }
        assertThat(turnkey.exportMnemonicCalls).isEmpty()
        assertThat(turnkey.exportAccountKeyCalls).isEmpty()
    }

    // ---------- passkeys ----------

    @Test
    fun `a blank passkeyDomain reads as none and a padded one is trimmed`() {
        assertThat(managedConfig().managedPasskeyDomain).isNull()
        assertThat(managedConfig(passkeyDomain = "   ").managedPasskeyDomain).isNull()
        assertThat(managedConfig(passkeyDomain = " passkeys.example.com ").managedPasskeyDomain)
            .isEqualTo("passkeys.example.com")
        assertThat(TurnkeyConfig(turnkey = TurnkeyContext).managedPasskeyDomain).isNull()
    }

    @Test
    fun `a passkeyDomain with a scheme, port, path or quote is refused when the config is built`() = runTest {
        val malformed = listOf(
            "https://passkeys.example.com",
            "passkeys.example.com:443",
            "passkeys.example.com/path",
            "pass\"keys.example.com",
            "passkeys example.com",
            "localhost",
        )
        malformed.forEach { bad ->
            val refused = expectThrows<RainError.InvalidConfig> { managedConfig(passkeyDomain = bad) }
            assertThat(refused).hasMessageThat().contains("two labels")
        }
    }

    @Test
    fun `BYO mode refuses the passkey flows with the managed-mode message`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val byo = TurnkeyProvider(TurnkeyConfig(turnkey = TurnkeyContext), contextOverride = turnkey)

        val login = expectThrows<RainError.InvalidConfig> { byo.loginWithPasskey(activity) }
        val signUp = expectThrows<RainError.InvalidConfig> { byo.signUpWithPasskey(activity) }

        assertThat(login).hasMessageThat().contains("managed mode")
        assertThat(signUp).hasMessageThat().contains("managed mode")
        assertThat(turnkey.passkeyLoginCalls).isEmpty()
        assertThat(turnkey.createPasskeyCalls).isEmpty()
    }

    @Test
    fun `managed mode forwards both passkey flows with the domain and configures once`() = runTest {
        var initCalls = 0
        val configuredDomains = mutableListOf<String?>()
        TurnkeyManagedConfigurator.initImpl = { _, _, _, domain ->
            initCalls++
            configuredDomains += domain
        }
        val login = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        login.onPasskeyLogin = { login.authenticate() }
        val loginProvider = TurnkeyProvider(managedConfig(passkeyDomain = "passkeys.example.com"), contextOverride = login)

        loginProvider.loginWithPasskey(activity)

        // The domain travels in the one-shot configuration, not on the call.
        assertThat(login.passkeyLoginCalls.single().sessionKey).startsWith("rain-turnkey-")
        assertThat(configuredDomains).containsExactly("passkeys.example.com")
        // The flow and the backfill each run the readiness guard; the vendor init ran once.
        assertThat(login.awaitReadyCallCount).isAtLeast(1)
        assertThat(initCalls).isEqualTo(1)
        assertThat(loginProvider.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)

        val signUp = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), session = null)
        signUp.onPasskeyLogin = { signUp.authenticate() }
        val rainApi = MockRainApiAuth()
        rainApi.signupPasskey = { RainApiSignup("sub-passkey") }
        val signUpProvider = TurnkeyProvider(
            managedConfig(passkeyDomain = "passkeys.example.com"),
            contextOverride = signUp,
            rainApiOverride = rainApi,
        )

        signUpProvider.signUpWithPasskey(activity)

        assertThat(signUp.createPasskeyCalls.single().name).startsWith("passkey-")
        assertThat(signUp.createPasskeyCalls.single().rpId).isEqualTo("passkeys.example.com")
        assertThat(rainApi.signupPasskeyCalls.single().token).isEqualTo("wat_1")
        assertThat(tokenRequests).containsExactly(null)
        assertThat(signUp.passkeyLoginCalls.single().organizationId).isEqualTo("sub-passkey")
        assertThat(signUp.awaitReadyCallCount).isAtLeast(1)
        // Same ids as the first provider: the one-shot configuration is not repeated.
        assertThat(initCalls).isEqualTo(1)
        assertThat(signUpProvider.currentAuthState()).isEqualTo(TurnkeyAuthState.Authenticated)
    }

    @Test
    fun `managed mode forwards addPasskey and a BYO or closed provider refuses it`() = runTest {
        val turnkey = MockTurnkey()
        val provider = TurnkeyProvider(managedConfig(passkeyDomain = "passkeys.example.com"), contextOverride = turnkey)

        provider.addPasskey(activity)

        assertThat(turnkey.createPasskeyCalls.single().rpId).isEqualTo("passkeys.example.com")
        assertThat(turnkey.registerAuthenticatorCalls.single().organizationId).isEqualTo(MockTurnkey.DEFAULT_ORG_ID)

        val byoContext = MockTurnkey()
        val byo = TurnkeyProvider(TurnkeyConfig(turnkey = TurnkeyContext), contextOverride = byoContext)
        val refused = expectThrows<RainError.InvalidConfig> { byo.addPasskey(activity) }
        assertThat(refused).hasMessageThat().contains("managed mode")
        assertThat(byoContext.createPasskeyCalls).isEmpty()

        provider.close()
        expectThrows<RainError.InvalidConfig> { provider.addPasskey(activity) }
        assertThat(turnkey.createPasskeyCalls).hasSize(1)
    }

    @Test
    fun `managed mode forwards the contact-attach calls with the canonical contact`() = runTest {
        val turnkey = MockTurnkey()
        // Rain's login, asked which wallet signs in with the contact, finds none: the update runs.
        val rainApi = MockRainApiAuth().apply { login = { throw MockRainApiAuth.noWallet() } }
        val provider = TurnkeyProvider(managedConfig(), contextOverride = turnkey, rainApiOverride = rainApi)

        provider.sendContactVerificationCode(LoginContact.Phone("+1 999-999-9999"))
        provider.confirmContactVerification("000000")

        assertThat(tokenRequests).containsExactly(LoginContact.Phone("+19999999999"))
        assertThat(rainApi.initOtpCalls).containsExactly("wat_1")
        assertThat(turnkey.encryptOtpCodeCalls.single().code).isEqualTo("000000")
        assertThat(rainApi.verifyOtpCalls).hasSize(1)
        assertThat(turnkey.setUserPhoneNumberCalls.single().contact).isEqualTo("+19999999999")
        assertThat(turnkey.setUserEmailCalls).isEmpty()
    }

    @Test
    fun `a BYO or closed provider refuses the contact-attach calls with no vendor call`() = runTest {
        val byoContext = MockTurnkey()
        val byo = TurnkeyProvider(TurnkeyConfig(turnkey = TurnkeyContext), contextOverride = byoContext)
        val refused = expectThrows<RainError.InvalidConfig> {
            byo.sendContactVerificationCode(LoginContact.Email("user@example.com"))
        }
        assertThat(refused).hasMessageThat().contains("managed mode")
        expectThrows<RainError.InvalidConfig> { byo.confirmContactVerification("123456") }
        assertThat(byoContext.createdDeviceKeys).isEmpty()

        val turnkey = MockTurnkey()
        val rainApi = MockRainApiAuth()
        val provider = TurnkeyProvider(managedConfig(), contextOverride = turnkey, rainApiOverride = rainApi)
        provider.close()
        expectThrows<RainError.InvalidConfig> { provider.sendContactVerificationCode(LoginContact.Email("user@example.com")) }
        expectThrows<RainError.InvalidConfig> { provider.confirmContactVerification("123456") }
        assertThat(tokenRequests).isEmpty()
        assertThat(rainApi.tokensSent).isEmpty()
    }

    @Test
    fun `a closed managed provider refuses both passkey flows with no vendor call`() = runTest {
        val turnkey = MockTurnkey()
        val provider = TurnkeyProvider(managedConfig(passkeyDomain = "passkeys.example.com"), contextOverride = turnkey)

        provider.close()

        expectThrows<RainError.InvalidConfig> { provider.loginWithPasskey(activity) }
        expectThrows<RainError.InvalidConfig> { provider.signUpWithPasskey(activity) }
        assertThat(turnkey.passkeyLoginCalls).isEmpty()
        assertThat(turnkey.createPasskeyCalls).isEmpty()
        assertThat(turnkey.clearSelectedSessionCallCount).isEqualTo(0)
    }
}
