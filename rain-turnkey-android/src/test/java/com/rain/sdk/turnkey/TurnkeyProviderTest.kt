package com.rain.sdk.turnkey

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.RainSdk
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.solana.SolanaSupport
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderDescriptor
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.WalletProvider
import com.turnkey.core.TurnkeyContext
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins what the Turnkey descriptor advertises. `RainSdk` copies the descriptor's capability set
 * onto the resolved client, so this is the set a host sees through `client.capabilities` and
 * `rain.first { }`. It has to match what the materialized wallet provider reports, or the host
 * and core would disagree about whether this provider's sends are sponsored.
 *
 * [TurnkeyConfig] needs the vendor's `TurnkeyContext` singleton, whose class initializer
 * dispatches onto `Dispatchers.Main`; on the JVM that dispatcher is absent, so a test dispatcher
 * stands in for it. Nothing dispatched there is ever run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnkeyProviderTest {

    @Before
    fun setUp() {
        assumeJdk24()
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun config(sponsorGas: Boolean) =
        TurnkeyConfig(turnkey = TurnkeyContext, sponsorGas = sponsorGas)

    @Test
    fun `advertises gas sponsorship by default`() {
        val descriptor = TurnkeyProvider(TurnkeyConfig(turnkey = TurnkeyContext))

        assertThat(descriptor.capabilities).containsExactly(
            Capability.EXPORT,
            Capability.MULTI_CHAIN,
            Capability.GAS_SPONSORSHIP
        )
    }

    @Test
    fun `a walletAddress override is checksummed at construction and a malformed one is refused`() {
        val vector = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"

        assertThat(TurnkeyConfig(turnkey = TurnkeyContext, walletAddress = vector.lowercase()).walletAddress)
            .isEqualTo(vector)
        // Blank means no override, as the manager and the exporter already read it.
        assertThat(TurnkeyConfig(turnkey = TurnkeyContext, walletAddress = "").walletAddress).isNull()
        assertThat(TurnkeyConfig(turnkey = TurnkeyContext, walletAddress = "  ").walletAddress).isNull()
        assertThrows(RainError.InvalidConfig::class.java) {
            TurnkeyConfig(turnkey = TurnkeyContext, walletAddress = "0x1234")
        }
        assertThrows(RainError.InvalidConfig::class.java) {
            TurnkeyConfig(turnkey = TurnkeyContext, walletAddress = "0x5AAeb6053F3E94C9b9A09f33669435E7Ef1BeAed")
        }
    }

    @Test
    fun `does not advertise gas sponsorship when sponsorGas is off`() {
        val descriptor = TurnkeyProvider(config(sponsorGas = false))

        assertThat(descriptor.capabilities).containsExactly(
            Capability.EXPORT,
            Capability.MULTI_CHAIN
        )
    }

    @Test
    fun `resolving by EXPORT follows registration order now that Turnkey advertises it`(): Unit = runBlocking {
        mockkStatic(URLUtil::class)
        every { URLUtil.isValidUrl(any()) } returns true
        val exportStub = object : ProviderDescriptor {
            override val id = ProviderId("stub-export")
            override val capabilities = setOf(Capability.EXPORT)
            override suspend fun create(context: ProviderContext): WalletProvider = error("stub-export resolved")
        }

        // Turnkey first: the Turnkey client comes back where a host used to get the other provider.
        val turnkeyFirst = MockTurnkey()
        val rainTurnkeyFirst = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.example/test"))
            .register(TurnkeyProvider(config(sponsorGas = false), contextOverride = turnkeyFirst))
            .register(exportStub)
            .build()
        try {
            val client = rainTurnkeyFirst.first { Capability.EXPORT in it.capabilities }
            assertThat(client.providerId).isEqualTo(ProviderId.TURNKEY)
            assertThat(Capability.EXPORT in client.capabilities).isTrue()
        } finally {
            rainTurnkeyFirst.close()
        }

        // Stub first: the stub wins and Turnkey is never materialized.
        val turnkeySecond = MockTurnkey(wallets = emptyList())
        val rainStubFirst = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.example/test"))
            .register(exportStub)
            .register(TurnkeyProvider(config(sponsorGas = false), contextOverride = turnkeySecond))
            .build()
        try {
            val thrown = runCatching { rainStubFirst.first { Capability.EXPORT in it.capabilities } }.exceptionOrNull()
            assertThat(thrown).isNotNull()
            assertThat(turnkeySecond.refreshWalletsCallCount).isEqualTo(0)
        } finally {
            rainStubFirst.close()
        }
    }

    @Test
    fun `descriptor and the wallet provider it creates advertise the same capabilities`(): Unit =
        runBlocking {
            listOf(true, false).forEach { sponsorGas ->
                val descriptor = TurnkeyProvider(config(sponsorGas), contextOverride = MockTurnkey())
                try {
                    val wallet = descriptor.create(providerContext())

                    assertThat(wallet.capabilities).isEqualTo(descriptor.capabilities)
                    assertThat(Capability.GAS_SPONSORSHIP in wallet.capabilities).isEqualTo(sponsorGas)
                } finally {
                    descriptor.close()
                }
            }
        }

    @Test
    fun `hands sponsorRent to the wallet it creates, read with sponsorGas`(): Unit = runBlocking {
        listOf(true to false, true to true, false to true).forEach { (sponsorGas, sponsorRent) ->
            val config = TurnkeyConfig(turnkey = TurnkeyContext, sponsorGas = sponsorGas, sponsorRent = sponsorRent)
            val descriptor = TurnkeyProvider(config, contextOverride = MockTurnkey())
            try {
                val wallet = descriptor.create(providerContext())

                assertThat(wallet.sponsorsRent(RainChain.SOLANA_DEVNET)).isEqualTo(sponsorGas && sponsorRent)
            } finally {
                descriptor.close()
            }
        }
    }

    /** The vendor-free context core hands every descriptor; nothing here is called during create. */
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

    @Test
    fun `sponsorGas reaches the send body of the wallet provider create builds`(): Unit = runBlocking {
        val turnkey = MockTurnkey()
        val descriptor = TurnkeyProvider(config(sponsorGas = true), contextOverride = turnkey)
        try {
            val wallet = descriptor.create(providerContext())

            val hash = wallet.sendTransaction(
                chainId = 1,
                from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
                data = "0x",
                value = "0x0"
            )

            val client = turnkey.turnkeyClient as MockTurnkeyClient
            assertThat(client.ethSendTransactionCalls.single().sponsor).isTrue()
            assertThat(hash).isEqualTo(client.mockTransactionHash)
        } finally {
            descriptor.close()
        }
    }

    @Test
    fun `the wallet create builds shares the descriptor's coordinator, so a death seen there stales its address`() {
        val hookCalls = AtomicInteger(0)
        val turnkey = MockTurnkey(wallets = emptyList())
        turnkey.onRefreshWallets = { turnkey.wallets = listOf(MockTurnkey.defaultWallet()) }
        val config = TurnkeyConfig(
            turnkey = TurnkeyContext,
            sponsorGas = false,
            onSessionExpired = { hookCalls.incrementAndGet() }
        )
        val descriptor = TurnkeyProvider(config, contextOverride = turnkey)
        try {
            // The probe inside create() resolves through the coordinator, which has now seen a live session.
            val wallet = runBlocking { descriptor.create(providerContext()) }
            assertThat(runBlocking { wallet.getWalletAddress() }).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)

            // The session dies for good, seen through the descriptor and never through a wallet call.
            turnkey.session = null
            assertThrows(RainError.TokenExpired::class.java) {
                runBlocking { descriptor.refreshSession() }
            }
            assertThat(hookCalls.get()).isEqualTo(1)

            // Another user logs in against the same vendor singleton.
            val other = "0x9999999999999999999999999999999999999999"
            turnkey.wallets = listOf(MockTurnkey.walletWithEthereumAddress(other))
            turnkey.session = MockTurnkey.defaultSession()

            // A manager over a private coordinator never learns of that death and would still serve
            // the first address here.
            assertThat(runBlocking { wallet.getWalletAddress() }).isEqualTo(other)
        } finally {
            descriptor.close()
        }
    }

    @Test
    fun `refreshSession on a closed descriptor throws InvalidConfig without reaching the vendor`(): Unit = runBlocking {
        val turnkey = MockTurnkey()
        val descriptor = TurnkeyProvider(config(sponsorGas = false), contextOverride = turnkey)
        descriptor.close()

        val thrown = runCatching { descriptor.refreshSession() }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(RainError.InvalidConfig::class.java)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
    }

    @Test
    fun `create builds a fresh manager each time so a re-resolve reads the current wallets`(): Unit = runBlocking {
        val turnkey = MockTurnkey()
        val descriptor = TurnkeyProvider(config(sponsorGas = false), contextOverride = turnkey)
        try {
            val first = descriptor.create(providerContext())
            assertThat(first.getWalletAddress()).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)

            // A different account appears with no session death: reset() then re-resolve must see it.
            val other = "0x2222222222222222222222222222222222222222"
            turnkey.wallets = listOf(MockTurnkey.walletWithEthereumAddress(other))
            val second = descriptor.create(providerContext())

            assertThat(second.getWalletAddress()).isEqualTo(other)
            // The first provider keeps its own cache: nothing is shared through the descriptor.
            assertThat(first.getWalletAddress()).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        } finally {
            descriptor.close()
        }
    }
}
