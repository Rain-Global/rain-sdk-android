package com.rain.sdk

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.constants.TokenRegistry
import com.rain.sdk.internal.utils.RainHexUtils
import org.junit.Assert.assertThrows
import org.junit.Test

class RainAuthPullConfigTest {

    private val zeroAddress = "0x0000000000000000000000000000000000000000"
    private val baseSepoliaUsdc = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"

    /** A real EVM chain that is not an Auth Pull chain in either environment. */
    private val ETHEREUM_MAINNET = 1

    @Test
    fun `sandbox config permits a trusted subset with a configured RPC`() {
        RainSdk.builder()
            .rpcEndpoints(mapOf(RainChain.BASE_SEPOLIA to "https://rpc.example/base"))
            .authPullConfig(RainAuthPullConfig.sandbox())
            .build()
    }

    // ---- the resolved chain set --------------------------------------------------------------

    /**
     * The set the approval guard enforces, which is not the environment's set: a sandbox config
     * covers two chains, but only the one with an RPC endpoint can carry an approval.
     */
    @Test
    fun `the resolved chain set is the config narrowed to chains with an RPC`() {
        val rain = RainSdk.builder()
            .rpcEndpoints(mapOf(RainChain.BASE_SEPOLIA to "https://rpc.example/base"))
            .authPullConfig(RainAuthPullConfig.sandbox())
            .build()

        assertThat(rain.authPullChainIds).containsExactly(RainChain.BASE_SEPOLIA)
        // The environment's set is the wider answer, and the one a host must not gate UI on.
        assertThat(RainAuthPullChains.SANDBOX).contains(RainChain.ARBITRUM_SEPOLIA)
    }

    @Test
    fun `every configured chain with an RPC is resolved`() {
        val rain = RainSdk.builder()
            .rpcEndpoints(
                mapOf(
                    RainChain.BASE_SEPOLIA to "https://rpc.example/base",
                    RainChain.ARBITRUM_SEPOLIA to "https://rpc.example/arbitrum",
                    // An unrelated chain must not leak into the Auth Pull set.
                    RainChain.AVALANCHE_TESTNET to "https://rpc.example/fuji"
                )
            )
            .authPullConfig(RainAuthPullConfig.sandbox())
            .build()

        assertThat(rain.authPullChainIds)
            .containsExactly(RainChain.BASE_SEPOLIA, RainChain.ARBITRUM_SEPOLIA)
    }

    /** No `authPullConfig(...)` means Auth Pull is off, and the set has to say so. */
    @Test
    fun `an unconfigured SDK resolves no Auth Pull chains`() {
        val rain = RainSdk.builder()
            .rpcEndpoints(mapOf(RainChain.BASE_SEPOLIA to "https://rpc.example/base"))
            .build()

        assertThat(rain.authPullChainIds).isEmpty()
    }

    /**
     * A custom configuration's chains are discoverable only through the resolved set: the static
     * sets answer for an environment, and a custom deployment may draw on either.
     */
    @Test
    fun `a custom gateway can enumerate the chains it configured`() {
        val rain = RainSdk.builder()
            .rpcEndpoints(
                mapOf(
                    RainChain.BASE_SEPOLIA to "https://rpc.example/base",
                    RainChain.ARBITRUM_SEPOLIA to "https://rpc.example/arbitrum"
                )
            )
            .authPullConfig(
                RainAuthPullConfig.custom(
                    tokenAddresses = mapOf(
                        RainChain.ARBITRUM_SEPOLIA to RainAuthPullConfig.sandbox().tokenAddresses.getValue(RainChain.ARBITRUM_SEPOLIA)
                    )
                )
            )
            .build()

        assertThat(rain.authPullChainIds).containsExactly(RainChain.ARBITRUM_SEPOLIA)
    }

    // ---- the canonical token maps ------------------------------------------------------------
    // Hardcoded addresses with nothing to check them against: a one-character typo would pass
    // every other test here and send approvals to the wrong contract.

    @Test
    fun `the sandbox token map is the registry's USDC on exactly the sandbox chains`() {
        assertTokenMapMatchesRegistry(
            RainAuthPullConfig.sandbox().tokenAddresses,
            RainAuthPullChains.SANDBOX
        )
    }

    @Test
    fun `the production token map is the registry's USDC on exactly the production chains`() {
        assertTokenMapMatchesRegistry(
            RainAuthPullConfig.production().tokenAddresses,
            RainAuthPullChains.PRODUCTION
        )
    }

    private fun assertTokenMapMatchesRegistry(tokenAddresses: Map<Int, String>, chains: Set<Int>) {
        assertThat(tokenAddresses.keys).containsExactlyElementsIn(chains)
        for ((chainId, address) in tokenAddresses) {
            val usdc = TokenRegistry.tokensFor(chainId).single { it.symbol == "USDC" }
            // Exact, checksum casing included: the registry is the one copy that gets maintained.
            assertThat(address).isEqualTo(usdc.address)
        }
    }

    // ---- builder validation ------------------------------------------------------------------
    // One test per rejection branch in `RainSdk.Builder.validateAuthPullConfig`.

    private fun customConfig(tokenAddresses: Map<Int, String>) = RainAuthPullConfig.custom(tokenAddresses)

    private fun buildCustom(config: RainAuthPullConfig): RainError.InvalidConfig =
        assertThrows(RainError.InvalidConfig::class.java) {
            RainSdk.builder()
                .rpcEndpoints(mapOf(RainChain.BASE_SEPOLIA to "https://rpc.example/base"))
                .authPullConfig(config)
                .build()
        }

    @Test
    fun `a custom config must name at least one token contract`() {
        val error = buildCustom(customConfig(tokenAddresses = emptyMap()))
        assertThat(error.message).contains("at least one token")
    }

    /** Even a custom gateway may only name chains from the two Auth Pull sets. */
    @Test
    fun `a chain outside both Auth Pull sets is rejected on a custom gateway`() {
        val error = buildCustom(
            customConfig(tokenAddresses = mapOf(ETHEREUM_MAINNET to baseSepoliaUsdc))
        )
        assertThat(error.message).contains("are not Auth Pull chains")
    }

    @Test
    fun `a malformed token contract is rejected`() {
        val error = buildCustom(customConfig(tokenAddresses = mapOf(RainChain.BASE_SEPOLIA to "0xnope")))
        assertThat(error.message).contains("token contract")
    }

    @Test
    fun `the zero address is rejected as a token contract`() {
        val error = buildCustom(customConfig(tokenAddresses = mapOf(RainChain.BASE_SEPOLIA to zeroAddress)))
        assertThat(error.message).contains("token contract")
    }

    @Test
    fun `the prefix-less zero address is rejected as a token contract`() {
        val error = buildCustom(
            customConfig(tokenAddresses = mapOf(RainChain.BASE_SEPOLIA to zeroAddress.removePrefix("0x")))
        )
        assertThat(error.message).contains("token contract")
    }

    @Test
    fun `a token contract whose checksum doesn't match is rejected`() {
        val error = buildCustom(
            customConfig(tokenAddresses = mapOf(RainChain.BASE_SEPOLIA to withFirstLetterCaseFlipped(baseSepoliaUsdc)))
        )
        assertThat(error.message).contains("token contract checksum")
    }

    /** The presets' own spellings go through the same check, so a wrong one would fail every build. */
    @Test
    fun `every preset token contract carries a valid checksum`() {
        val presets = RainAuthPullConfig.sandbox().tokenAddresses.values +
            RainAuthPullConfig.production().tokenAddresses.values
        presets.forEach { address ->
            assertWithMessage(address).that(RainHexUtils.hasValidChecksum(address)).isTrue()
        }
    }

    /** [address] with its first letter's case flipped: the same bytes and a wrong EIP-55 checksum. */
    private fun withFirstLetterCaseFlipped(address: String): String {
        val i = (2 until address.length).first { address[it].isLetter() }
        val c = address[i]
        return address.substring(0, i) + (if (c.isUpperCase()) c.lowercaseChar() else c.uppercaseChar()) +
            address.substring(i + 1)
    }

    /** A trusted chain with no RPC endpoint can never carry an approval; all of them missing is a misconfiguration. */
    @Test
    fun `a config none of whose chains has an RPC endpoint is rejected`() {
        val error = assertThrows(RainError.InvalidConfig::class.java) {
            RainSdk.builder()
                .rpcEndpoints(mapOf(RainChain.AVALANCHE_TESTNET to "https://rpc.example/fuji"))
                .authPullConfig(RainAuthPullConfig.sandbox())
                .build()
        }
        assertThat(error.message).contains("No RPC endpoint")
    }

    /** No environment setting exists any more; the canonical configurations carry their own. */
    @Test
    fun `a production config builds on its own`() {
        val rain = RainSdk.builder()
            .rpcEndpoints(
                mapOf(
                    RainChain.BASE_MAINNET to "https://rpc.example/base",
                    RainChain.ARBITRUM_MAINNET to "https://rpc.example/arbitrum"
                )
            )
            .authPullConfig(RainAuthPullConfig.production())
            .build()

        assertThat(rain.authPullChainIds).containsExactly(RainChain.BASE_MAINNET, RainChain.ARBITRUM_MAINNET)
    }

    /** A custom deployment cannot be placed in one environment, so its chains may come from either set. */
    @Test
    fun `a custom config may mix chains from both environments`() {
        val rain = RainSdk.builder()
            .rpcEndpoints(
                mapOf(
                    RainChain.BASE_SEPOLIA to "https://rpc.example/base-sepolia",
                    RainChain.BASE_MAINNET to "https://rpc.example/base"
                )
            )
            .authPullConfig(
                RainAuthPullConfig.custom(
                    tokenAddresses = mapOf(
                        RainChain.BASE_SEPOLIA to baseSepoliaUsdc,
                        RainChain.BASE_MAINNET to RainAuthPullConfig.production().tokenAddresses.getValue(RainChain.BASE_MAINNET)
                    )
                )
            )
            .build()

        assertThat(rain.authPullChainIds).containsExactly(RainChain.BASE_SEPOLIA, RainChain.BASE_MAINNET)
    }
}
