package com.rain.sdk.internal.core

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.RainSdk
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderDescriptor
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.WalletProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for the modular SDK entry point [RainSdk] — builder validation and provider resolution.
 *
 * Replaces the former `RainSdkManagerTest`, whose subject (the manager's `initializePortal` /
 * `initialize` / `.portal` / `.turnkey` surface) was removed when the manager became vendor-free
 * and provider-bound. RPC/chain-id validation now lives in [RainSdk.Builder] / [ConfigManager],
 * which these tests exercise instead.
 */
class RainSdkBuilderTest {

    /** A minimal host-supplied provider used to drive resolution without any vendor SDK. */
    private class FakeProvider(
        private val walletProvider: WalletProvider,
        override val id: ProviderId = ProviderId("fake"),
        override val capabilities: Set<Capability> = emptySet(),
    ) : ProviderDescriptor {
        var closed = false
        override suspend fun create(context: ProviderContext): WalletProvider = walletProvider
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `build throws InvalidConfig when no rpcEndpoints configured`() {
        assertThrows(RainError.InvalidConfig::class.java) {
            RainSdk.builder().build()
        }
    }

    @Test
    fun `build accepts an https or loopback RPC URL and refuses any other`() {
        listOf(
            "https://rpc.test/v1?key=1",
            "http://127.0.0.1:8545",
            "http://localhost:8545",
            "http://[::1]:8545"
        ).forEach { url ->
            RainSdk.builder().rpcEndpoints(mapOf(1 to url)).build()
        }
        listOf(
            "rpc.test",
            "https://",
            "wss://rpc.test",
            "file:///rpc",
            "javascript:void(0)",
            "http://rpc.test",
            "http://10.0.2.2:8545"
        ).forEach { url ->
            val refused = runCatching { RainSdk.builder().rpcEndpoints(mapOf(1 to url)).build() }.exceptionOrNull()
            assertWithMessage(url).that(refused).isInstanceOf(RainError.InvalidConfig::class.java)
        }
    }

    @Test
    fun `build succeeds with zero providers (wallet-agnostic mode)`() {
        // The builder methods and tokenMetadata work without any wallet provider.
        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .build()

        assertThat(sdk.providerIds).isEmpty()
        val parameters = sdk.buildTransactionParameters(TestFixtures.WALLET_ADDRESS, TestFixtures.CONTROLLER_ADDRESS, "0x")
        assertThat(parameters.to).isEqualTo(TestFixtures.CONTROLLER_ADDRESS)
    }

    @Test
    fun `resolving a provider on a provider-less SDK throws ProviderNotRegistered`() {
        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .build()

        assertThrows(RainError.ProviderNotRegistered::class.java) {
            runBlocking { sdk.provider(ProviderId.PORTAL) }
        }
        assertThrows(RainError.ProviderNotRegistered::class.java) {
            runBlocking { sdk.first { true } }
        }
    }

    @Test
    fun `provider resolves a registered provider into a RainClient`(): Unit = runBlocking {
        val stub = object : StubWalletProvider() {
            override val id: ProviderId = ProviderId("fake")
        }
        stub.addressToReturn = "0xb0b0000000000000000000000000000000000000"

        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .register(FakeProvider(stub))
            .build()

        val client = sdk.provider(ProviderId("fake"))

        assertThat(client.providerId.value).isEqualTo("fake")
        assertThat(sdk.providers.map { it.id }).containsExactly(ProviderId("fake"))
        assertThat(client.getWalletAddress())
            .isEqualTo("0xb0b0000000000000000000000000000000000000")
    }

    @Test
    fun `first resolves the earliest registered descriptor matching the predicate`(): Unit = runBlocking {
        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .register(FakeProvider(StubWalletProvider()))
            .register(FakeProvider(StubWalletProvider(), ProviderId("second")))
            .build()

        assertThat(sdk.first { it.id == ProviderId("second") }.providerId).isEqualTo(ProviderId("second"))
        assertThat(sdk.first { true }.providerId).isEqualTo(ProviderId("fake"))
        assertThat(sdk.providers.map { it.id })
            .containsExactly(ProviderId("fake"), ProviderId("second"))
            .inOrder()
    }

    @Test
    fun `build rejects the Rain wallet and Turnkey providers together`() {
        val error = assertThrows(RainError.InvalidConfig::class.java) {
            RainSdk.builder()
                .rpcEndpoints(mapOf(1 to "https://rpc.test"))
                .register(FakeProvider(StubWalletProvider(), ProviderId.RAIN))
                .register(FakeProvider(StubWalletProvider(), ProviderId.TURNKEY))
                .build()
        }
        assertThat(error).hasMessageThat().contains(RainSdk.RAIN_AND_TURNKEY_CONFLICT_MESSAGE)
    }

    @Test
    fun `re-registering an id replaces the descriptor and closes the replaced instance`() {
        val first = FakeProvider(StubWalletProvider(), ProviderId.RAIN)
        val second = FakeProvider(StubWalletProvider(), ProviderId.RAIN)

        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .register(first)
            .register(second)
            .build()

        assertThat(sdk.providers).containsExactly(second)
        assertThat(first.closed).isTrue()
        assertThat(second.closed).isFalse()
    }

    @Test
    fun `re-registering a replaced instance keeps it open and closes the one it displaced`() {
        val first = FakeProvider(StubWalletProvider(), ProviderId.RAIN)
        val second = FakeProvider(StubWalletProvider(), ProviderId.RAIN)

        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .register(first)
            .register(second)
            .register(first)
            .build()

        assertThat(sdk.providers).containsExactly(first)
        assertThat(first.closed).isFalse()
        assertThat(second.closed).isTrue()
    }

    @Test
    fun `a build that fails leaves a replaced descriptor open`() {
        val first = FakeProvider(StubWalletProvider(), ProviderId.RAIN)
        val second = FakeProvider(StubWalletProvider(), ProviderId.RAIN)

        assertThrows(RainError.InvalidConfig::class.java) {
            RainSdk.builder().register(first).register(second).build() // no RPC endpoints
        }

        assertThat(first.closed).isFalse()
        assertThat(second.closed).isFalse()
    }

    @Test
    fun `registering the same instance twice is a no-op`() {
        val only = FakeProvider(StubWalletProvider(), ProviderId.RAIN)

        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .register(only)
            .register(only)
            .build()

        assertThat(sdk.providers).containsExactly(only)
        assertThat(only.closed).isFalse()
    }

    @Test
    fun `build rejects the pair in either registration order`() {
        val error = assertThrows(RainError.InvalidConfig::class.java) {
            RainSdk.builder()
                .rpcEndpoints(mapOf(1 to "https://rpc.test"))
                .register(FakeProvider(StubWalletProvider(), ProviderId.TURNKEY))
                .register(FakeProvider(StubWalletProvider(), ProviderId.RAIN))
                .build()
        }
        assertThat(error).hasMessageThat().contains(RainSdk.RAIN_AND_TURNKEY_CONFLICT_MESSAGE)
    }

    @Test
    fun `build accepts the Rain wallet provider alone`() {
        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .register(FakeProvider(StubWalletProvider(), ProviderId.RAIN))
            .build()

        assertThat(sdk.providerIds).containsExactly(ProviderId.RAIN)
    }

    @Test
    fun `build accepts the Turnkey provider alone`() {
        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .register(FakeProvider(StubWalletProvider(), ProviderId.TURNKEY))
            .build()

        assertThat(sdk.providerIds).containsExactly(ProviderId.TURNKEY)
    }

    @Test
    fun `build accepts the Rain wallet provider beside Portal and Privy`() {
        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .register(FakeProvider(StubWalletProvider(), ProviderId.RAIN))
            .register(FakeProvider(StubWalletProvider(), ProviderId.PORTAL))
            .register(FakeProvider(StubWalletProvider(), ProviderId.PRIVY))
            .build()

        assertThat(sdk.providers.map { it.id })
            .containsExactly(ProviderId.RAIN, ProviderId.PORTAL, ProviderId.PRIVY)
            .inOrder()
    }

    @Test
    fun `resolving by EXPORT follows registration order when the Rain wallet advertises it`(): Unit = runBlocking {
        fun build(first: ProviderId, second: ProviderId) = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .register(FakeProvider(StubWalletProvider(), first, setOf(Capability.EXPORT)))
            .register(FakeProvider(StubWalletProvider(), second, setOf(Capability.EXPORT)))
            .build()

        assertThat(build(ProviderId.RAIN, ProviderId.PORTAL).first { Capability.EXPORT in it.capabilities }.providerId)
            .isEqualTo(ProviderId.RAIN)
        assertThat(build(ProviderId.PORTAL, ProviderId.RAIN).first { Capability.EXPORT in it.capabilities }.providerId)
            .isEqualTo(ProviderId.PORTAL)
    }

    @Test
    fun `provider throws ProviderNotRegistered for an unregistered id`() {
        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .register(FakeProvider(StubWalletProvider()))
            .build()

        assertThrows(RainError.ProviderNotRegistered::class.java) {
            runBlocking { sdk.provider(ProviderId("missing")) }
        }
    }

    @Test
    fun `reset leaves the SDK usable and re-resolves providers`(): Unit = runBlocking {
        val stub = object : StubWalletProvider() {
            override val id: ProviderId = ProviderId("fake")
        }
        stub.addressToReturn = "0xb0b0000000000000000000000000000000000000"

        val sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.test"))
            .register(FakeProvider(stub))
            .build()

        val first = sdk.provider(ProviderId("fake"))
        sdk.reset()

        // The chain configuration survives, so the instance stays usable and the next resolve
        // re-runs create(). A rebuild via builder() is only needed to change configuration.
        val second = sdk.provider(ProviderId("fake"))
        assertThat(second).isNotSameInstanceAs(first)
        assertThat(second.getWalletAddress())
            .isEqualTo("0xb0b0000000000000000000000000000000000000")
    }
}
