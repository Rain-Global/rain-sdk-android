package com.rain.sdk.internal.tokenstore

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.constants.RainConstants
import com.rain.sdk.internal.helpers.MockChainReader
import com.rain.sdk.models.TokenInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import timber.log.Timber

/**
 * Registry-seeded lookups skip enrichment, unknown tokens enrich exactly once and cache, and
 * host-registered tokens are returned without an on-chain read.
 */
class TokenMetadataStoreTest {

    // Chain-1 registry USDC (checksummed in the registry; lookups are case-insensitive).
    private val usdcEthereum = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val unknown = "0xAbCdEf1234567890abcdef1234567890aBcDeF12"

    // A base58 SPL mint; no Solana chain has registry entries, so it is unknown until registered.
    private val solanaMint = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"

    @Test
    fun `tokenInfo returns registry metadata without enrichment for a known token`() = runBlocking {
        val reader = MockChainReader(decimals = 99, symbol = "WRONG")
        val store = TokenMetadataStore(reader)

        val info = store.tokenInfo(chainId = 1, address = usdcEthereum)

        assertThat(info.symbol).isEqualTo("USDC")
        assertThat(info.decimals).isEqualTo(6)
        // Registry hit — no on-chain enrichment.
        assertThat(reader.decimalsCalls).isEmpty()
        assertThat(reader.symbolCalls).isEmpty()
    }

    // ---- decimalsOrNull: the strict accessor money paths use -------------------------

    @Test
    fun `decimalsOrNull returns registry decimals without an on-chain read`() = runBlocking {
        val reader = MockChainReader(decimals = 99)
        val store = TokenMetadataStore(reader)

        assertThat(store.decimalsOrNull(chainId = 1, address = usdcEthereum)).isEqualTo(6)
        assertThat(reader.decimalsCalls).isEmpty()
    }

    @Test
    fun `decimalsOrNull returns null rather than the 18-decimal default when the read fails`() =
        runBlocking {
            // The whole point of this accessor: a transfer or approval must never scale by a guessed 18.
            val reader = MockChainReader(metadataError = RuntimeException("rpc down"))
            val store = TokenMetadataStore(reader)

            assertThat(store.decimalsOrNull(chainId = 1, address = unknown)).isNull()
        }

    @Test
    fun `decimalsOrNull does not cache a failed read, so a later call can succeed`() = runBlocking {
        val reader = MockChainReader(decimals = 8, metadataError = RuntimeException("rpc down"))
        val store = TokenMetadataStore(reader)

        assertThat(store.decimalsOrNull(chainId = 1, address = unknown)).isNull()

        // The RPC recovers; the store must retry rather than serve a cached guess.
        reader.metadataError = null
        assertThat(store.decimalsOrNull(chainId = 1, address = unknown)).isEqualTo(8)
    }

    @Test
    fun `decimalsOrNull caches a successful read`() = runBlocking {
        val reader = MockChainReader(decimals = 8)
        val store = TokenMetadataStore(reader)

        assertThat(store.decimalsOrNull(chainId = 1, address = unknown)).isEqualTo(8)
        assertThat(store.decimalsOrNull(chainId = 1, address = unknown.lowercase())).isEqualTo(8)

        assertThat(reader.decimalsCalls).hasSize(1)
    }

    @Test
    fun `decimalsOrNull sees host-registered tokens`() = runBlocking {
        val reader = MockChainReader(decimals = 99)
        val store = TokenMetadataStore(reader)
        store.register(listOf(TokenInfo(1, unknown, "FOO", 4, "Foo")))

        assertThat(store.decimalsOrNull(chainId = 1, address = unknown)).isEqualTo(4)
        assertThat(reader.decimalsCalls).isEmpty()
    }

    @Test
    fun `tokenInfo enriches an unknown token once and caches the result`() = runBlocking {
        val reader = MockChainReader(decimals = 8, symbol = "WBTC", name = "Wrapped BTC")
        val store = TokenMetadataStore(reader)

        val first = store.tokenInfo(chainId = 1, address = unknown)
        // Second lookup with a different-cased address must hit the cache.
        val second = store.tokenInfo(chainId = 1, address = unknown.lowercase())

        assertThat(first.decimals).isEqualTo(8)
        assertThat(first.symbol).isEqualTo("WBTC")
        assertThat(first.name).isEqualTo("Wrapped BTC")
        assertThat(second).isEqualTo(first)
        // Enriched exactly once despite two lookups.
        assertThat(reader.decimalsCalls).hasSize(1)
        assertThat(reader.symbolCalls).hasSize(1)
        assertThat(reader.nameCalls).hasSize(1)
    }

    @Test
    fun `tokenInfo enrichment tolerates a missing name`() = runBlocking {
        // name defaults to null on the mock — a failed/absent name() read must leave name null
        // without breaking decimals/symbol resolution.
        val reader = MockChainReader(decimals = 18, symbol = "FOO")
        val store = TokenMetadataStore(reader)

        val info = store.tokenInfo(chainId = 1, address = unknown)

        assertThat(info.symbol).isEqualTo("FOO")
        assertThat(info.decimals).isEqualTo(18)
        assertThat(info.name).isNull()
    }

    @Test
    fun `registered token is returned without enrichment`() = runBlocking {
        val reader = MockChainReader(decimals = 8, symbol = "WBTC")
        val store = TokenMetadataStore(reader)
        store.register(
            listOf(TokenInfo(chainId = 1, address = unknown, symbol = "HOST", decimals = 12, name = "Host Token"))
        )

        val info = store.tokenInfo(chainId = 1, address = unknown)

        assertThat(info.symbol).isEqualTo("HOST")
        assertThat(info.decimals).isEqualTo(12)
        assertThat(reader.decimalsCalls).isEmpty()
        assertThat(reader.symbolCalls).isEmpty()
    }

    /**
     * A wrong `decimals` on a known token would rescale its balances and approvals by orders of
     * magnitude, so the registry stays authoritative for the addresses it ships.
     */
    @Test
    fun `a registration cannot override a built-in token's metadata`() = runBlocking {
        val reader = MockChainReader(decimals = 99, symbol = "WRONG")
        val store = TokenMetadataStore(reader)

        store.register(
            listOf(TokenInfo(chainId = 1, address = usdcEthereum, symbol = "USDX", decimals = 18, name = "Not USDC"))
        )

        val info = store.tokenInfo(chainId = 1, address = usdcEthereum)
        assertThat(info.symbol).isEqualTo("USDC")
        assertThat(info.decimals).isEqualTo(6)
        assertThat(store.decimalsOrNull(chainId = 1, address = usdcEthereum)).isEqualTo(6)
        assertThat(store.registeredTokens(chainId = 1).filter { it.address.lowercase() == usdcEthereum }).hasSize(1)
        assertThat(reader.decimalsCalls).isEmpty()
    }

    @Test
    fun `re-registering a built-in token's own metadata under another address case warns about nothing`() = runBlocking {
        val store = TokenMetadataStore(MockChainReader())
        val messages = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                messages += message
            }
        }

        Timber.plant(tree)
        try {
            // Lowercase address, the registry's symbol, decimals and name.
            store.register(listOf(TokenInfo(chainId = 1, address = usdcEthereum, symbol = "USDC", decimals = 6, name = "USDC")))
            // Same spelling, different decimals: still refused, and said so.
            store.register(listOf(TokenInfo(chainId = 1, address = usdcEthereum, symbol = "USDC", decimals = 18, name = "USDC")))
        } finally {
            Timber.uproot(tree)
        }

        assertThat(messages.count { it.contains("cannot be overridden") }).isEqualTo(1)
        assertThat(store.decimalsOrNull(chainId = 1, address = usdcEthereum)).isEqualTo(6)
        assertThat(store.registeredTokens(chainId = 1).filter { it.address.lowercase() == usdcEthereum }).hasSize(1)
    }

    @Test
    fun `a seed token cannot override a built-in token's metadata either`() = runBlocking {
        val reader = MockChainReader(decimals = 99)
        val store = TokenMetadataStore(
            reader,
            seedTokens = listOf(TokenInfo(chainId = 1, address = usdcEthereum, symbol = "USDC", decimals = 18))
        )

        assertThat(store.decimalsOrNull(chainId = 1, address = usdcEthereum)).isEqualTo(6)
        assertThat(reader.decimalsCalls).isEmpty()
    }

    @Test
    fun `a host-registered token can still be re-registered`() = runBlocking {
        val store = TokenMetadataStore(MockChainReader())

        store.register(listOf(TokenInfo(chainId = 1, address = unknown, symbol = "FOO", decimals = 12)))
        store.register(
            listOf(TokenInfo(chainId = 1, address = unknown.uppercase(), symbol = "FOO", decimals = 8, name = "Foo"))
        )

        val info = store.tokenInfo(chainId = 1, address = unknown)
        assertThat(info.decimals).isEqualTo(8)
        assertThat(info.name).isEqualTo("Foo")
        assertThat(
            store.registeredTokens(chainId = 1).filter { it.address.lowercase() == unknown.lowercase() }
        ).hasSize(1)
    }

    @Test
    fun `registeredTokens returns registry tokens plus host registrations in order`() = runBlocking {
        val store = TokenMetadataStore(MockChainReader())
        val host = TokenInfo(chainId = 1, address = unknown, symbol = "HOST", decimals = 12)
        store.register(listOf(host))

        val tokens = store.registeredTokens(chainId = 1)

        assertThat(tokens).contains(host)
        // Registry first, host registrations appended.
        assertThat(tokens.last()).isEqualTo(host)
    }

    @Test
    fun `seedTokens passed to the constructor are registered`() = runBlocking {
        val reader = MockChainReader(decimals = 8, symbol = "WBTC")
        val seed = TokenInfo(chainId = 999, address = unknown, symbol = "SEED", decimals = 4)
        val store = TokenMetadataStore(reader, seedTokens = listOf(seed))

        val info = store.tokenInfo(chainId = 999, address = unknown)

        assertThat(info).isEqualTo(seed)
        assertThat(reader.decimalsCalls).isEmpty()
    }

    @Test
    fun `nativeCurrency resolves from the registry`() {
        val store = TokenMetadataStore(MockChainReader())
        assertThat(store.nativeCurrency(43114).symbol).isEqualTo("AVAX")
        assertThat(store.nativeCurrency(1).symbol).isEqualTo("ETH")
        // Unknown chain falls back to an ETH-like default.
        assertThat(store.nativeCurrency(123456).symbol).isEqualTo("ETH")
    }

    @Test
    fun `a fallback decimals is not cached so a later lookup re-reads the chain`() = runBlocking {
        // A transient RPC failure must not pin the 18-decimals guess for the process lifetime:
        // caching it would misreport a 6-decimal token's balance by a factor of 10^12.
        val reader = MockChainReader(decimals = 6, symbol = "EURC")
        reader.metadataError = RuntimeException("rpc timeout")
        val store = TokenMetadataStore(reader)

        val failed = store.tokenInfo(chainId = 1, address = unknown)
        assertThat(failed.decimals).isEqualTo(18)

        reader.metadataError = null
        val retried = store.tokenInfo(chainId = 1, address = unknown)

        assertThat(retried.decimals).isEqualTo(6)
        assertThat(retried.symbol).isEqualTo("EURC")
        assertThat(reader.decimalsCalls).hasSize(2)

        // The successful read is cached — a third lookup issues no further RPC.
        store.tokenInfo(chainId = 1, address = unknown)
        assertThat(reader.decimalsCalls).hasSize(2)
    }

    @Test
    fun `nativeCurrencyOrNull returns null for an unknown chain instead of a default`() {
        val store = TokenMetadataStore(MockChainReader())
        assertThat(store.nativeCurrencyOrNull(43114)?.symbol).isEqualTo("AVAX")
        assertThat(store.nativeCurrencyOrNull(com.rain.sdk.RainChain.SOLANA_MAINNET)?.symbol)
            .isEqualTo("SOL")
        assertThat(store.nativeCurrencyOrNull(123456)).isNull()
    }

    // ---- tokenInfoOrNull: the strict lookup behind decimalsOrNull and RainSdk.tokenMetadata ----

    @Test
    fun `tokenInfoOrNull returns null and caches nothing when decimals() fails`() = runBlocking {
        val reader = MockChainReader(decimals = 8, metadataError = RuntimeException("rpc down"))
        val store = TokenMetadataStore(reader)

        assertThat(store.tokenInfoOrNull(chainId = 1, address = unknown)).isNull()

        // The RPC recovers; the store must retry rather than serve a cached miss.
        reader.metadataError = null
        assertThat(store.tokenInfoOrNull(chainId = 1, address = unknown)?.decimals).isEqualTo(8)
        assertThat(reader.decimalsCalls).hasSize(2)
    }

    @Test
    fun `tokenInfoOrNull caches a successful read`() = runBlocking {
        val reader = MockChainReader(decimals = 8, symbol = "TKN", name = "Token")
        val store = TokenMetadataStore(reader)

        val first = store.tokenInfoOrNull(chainId = 1, address = unknown)
        val second = store.tokenInfoOrNull(chainId = 1, address = unknown.lowercase())

        assertThat(first).isEqualTo(TokenInfo(1, unknown, "TKN", 8, "Token"))
        assertThat(second).isSameInstanceAs(first)
        assertThat(reader.decimalsCalls).hasSize(1)
    }

    @Test
    fun `tokenInfoOrNull returns registry metadata without an on-chain read`() = runBlocking {
        val reader = MockChainReader(decimals = 99, symbol = "WRONG")
        val store = TokenMetadataStore(reader)

        val info = store.tokenInfoOrNull(chainId = 1, address = usdcEthereum)

        assertThat(info?.symbol).isEqualTo("USDC")
        assertThat(info?.decimals).isEqualTo(6)
        assertThat(reader.decimalsCalls).isEmpty()
    }

    @Test
    fun `tokenInfoOrNull returns host-registered metadata without an on-chain read`() = runBlocking {
        val reader = MockChainReader(decimals = 99)
        val store = TokenMetadataStore(reader)
        val registered = TokenInfo(1, unknown, "HOST", 4, "Host Token")
        store.register(listOf(registered))

        assertThat(store.tokenInfoOrNull(chainId = 1, address = unknown.lowercase())).isEqualTo(registered)
        assertThat(reader.decimalsCalls).isEmpty()
    }

    @Test
    fun `tokenInfoOrNull on a Solana chain never touches the reader and is null when unregistered`() =
        runBlocking {
            val reader = MockChainReader(decimals = 6, symbol = "USDC")
            val store = TokenMetadataStore(reader)

            assertThat(store.tokenInfoOrNull(chainId = 901, address = solanaMint)).isNull()

            val registered = TokenInfo(901, solanaMint, "USDC", 6, "USD Coin")
            store.register(listOf(registered))
            assertThat(store.tokenInfoOrNull(chainId = 901, address = solanaMint)).isEqualTo(registered)

            assertThat(reader.decimalsCalls).isEmpty()
            assertThat(reader.symbolCalls).isEmpty()
        }

    @Test
    fun `tokenInfoOrNull keeps a result whose symbol() and name() failed but decimals() resolved`() =
        runBlocking {
            val reader = MockChainReader(
                decimals = 8,
                symbolError = RuntimeException("symbol reverted"),
                nameError = RuntimeException("name reverted")
            )
            val store = TokenMetadataStore(reader)

            assertThat(store.tokenInfoOrNull(chainId = 1, address = unknown))
                .isEqualTo(TokenInfo(1, unknown, null, 8, null))
        }

    @Test
    fun `decimalsOrNull on a Solana chain id returns null without an on-chain read`() = runBlocking {
        // Both assertions carry weight: against this mock the old path returned 6, and the call
        // count pins that a base58 mint is never sent to the EVM reader.
        val reader = MockChainReader(decimals = 6)
        val store = TokenMetadataStore(reader)

        assertThat(store.decimalsOrNull(chainId = 901, address = solanaMint)).isNull()
        assertThat(reader.decimalsCalls).isEmpty()
    }

    // ---- cancellation propagates through every enrichment branch ------------------------

    @Test
    fun `tokenInfoOrNull rethrows a cancellation raised by the decimals read`() {
        val store = TokenMetadataStore(MockChainReader(metadataError = CancellationException("cancelled")))

        assertThrows(CancellationException::class.java) {
            runBlocking { store.tokenInfoOrNull(chainId = 1, address = unknown) }
        }
    }

    @Test
    fun `tokenInfoOrNull rethrows a cancellation raised by the symbol read`() {
        val store = TokenMetadataStore(MockChainReader(decimals = 8, symbolError = CancellationException("cancelled")))

        assertThrows(CancellationException::class.java) {
            runBlocking { store.tokenInfoOrNull(chainId = 1, address = unknown) }
        }
    }

    @Test
    fun `tokenInfoOrNull rethrows a cancellation raised by the name read`() {
        val store = TokenMetadataStore(MockChainReader(decimals = 8, nameError = CancellationException("cancelled")))

        assertThrows(CancellationException::class.java) {
            runBlocking { store.tokenInfoOrNull(chainId = 1, address = unknown) }
        }
    }

    // ---- the decimals range and in-place registration -------------------------------------

    @Test
    fun `tokenInfoOrNull refuses decimals outside the supported range as InvalidConfig and caches nothing`() = runBlocking {
        val reader = MockChainReader(decimals = 78, symbol = "BAD")
        val store = TokenMetadataStore(reader)

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { store.tokenInfoOrNull(chainId = 1, address = unknown) }
        }
        assertThat(error).hasMessageThat().contains("reports 78 decimals, outside the supported range 0..77")

        // The next read answers within range; the store must read again rather than serve a cached refusal.
        reader.decimals = 6
        assertThat(store.tokenInfoOrNull(chainId = 1, address = unknown)?.decimals).isEqualTo(6)
        assertThat(reader.decimalsCalls).hasSize(2)
    }

    @Test
    fun `tokenInfo falls back to the default for decimals outside the supported range and caches nothing`() = runBlocking {
        val reader = MockChainReader(decimals = 78, symbol = "BAD")
        val store = TokenMetadataStore(reader)

        assertThat(store.tokenInfo(chainId = 1, address = unknown).decimals).isEqualTo(RainConstants.DEFAULT_ERC20_DECIMALS)
        store.tokenInfo(chainId = 1, address = unknown)
        assertThat(reader.decimalsCalls).hasSize(2)
    }

    @Test
    fun `registerNow stores before it returns so a lookup that follows needs no chain read`() = runBlocking {
        val reader = MockChainReader(decimals = 99)
        val store = TokenMetadataStore(reader)
        val token = TokenInfo(1, unknown, "TKN", 8, "Token")

        store.registerNow(listOf(token))

        assertThat(store.tokenInfoOrNull(chainId = 1, address = unknown)).isEqualTo(token)
        assertThat(reader.decimalsCalls).isEmpty()
    }
}
