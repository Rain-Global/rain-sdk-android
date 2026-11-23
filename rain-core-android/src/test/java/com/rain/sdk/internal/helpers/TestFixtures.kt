package com.rain.sdk.internal.helpers

import com.rain.sdk.RainAuthPullChains
import com.rain.sdk.RainChain
import com.rain.sdk.interfaces.RainTransactionBuilder
import com.rain.sdk.internal.core.RainSdkManager
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import com.rain.sdk.internal.network.chainreader.ChainReader
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.RainAdminSignature
import java.util.Base64

/**
 * Shared test fixtures: canonical addresses, salts, and signatures so manager-contract
 * tests don't have to invent valid bytes themselves.
 */
internal object TestFixtures {
    const val WALLET_ADDRESS = "0x1234567890123456789012345678901234567890"
    const val CONTRACT_ADDRESS = "0x1234567890123456789012345678901234567890"
    const val PROXY_ADDRESS = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
    const val CONTROLLER_ADDRESS = "0x5555555555555555555555555555555555555555"
    const val RECIPIENT_ADDRESS = "0xfedcbafedcbafedcbafedcbafedcbafedcbafedc"

    /** EIP-55 checksummed form of [RECIPIENT_ADDRESS] — what the send paths forward downstream. */
    const val RECIPIENT_ADDRESS_CHECKSUMMED = "0xfeDcbaFEdcBaFEDcbAfedcBAfeDCBAFeDCBafEdc"
    const val TOKEN_ADDRESS = "0x9876543210987654321098765432109876543210"
    const val USDC_ADDRESS = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    const val AUTH_PULL_USDC_ADDRESS = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"

    /** 32-byte salt encoded as base64. Used by withdrawCollateral / estimateWithdrawalFee. */
    val validSaltBase64: String = Base64.getEncoder().encodeToString(ByteArray(32) { 0xAA.toByte() })

    /** 65-byte signature encoded as hex with `0x` prefix. */
    val validSignatureHex: String = "0x" + "01".repeat(65)

    /** Rain-issued withdrawal authorization. */
    fun adminSignature(
        salt: String = validSaltBase64,
        signature: String = validSignatureHex,
        expiresAt: String = "2030-12-31T23:59:59Z"
    ): RainAdminSignature = RainAdminSignature(salt, signature, expiresAt)
}

/**
 * Manager factories for tests.
 *
 * Constructs [RainSdkManager] directly with a fake [WalletProvider] (the modular SDK binds a
 * manager to a resolved provider), so tests can exercise routing without standing up a real
 * Portal or Turnkey context or driving the [com.rain.sdk.RainSdk] builder.
 */
internal object TestManagers {

    /**
     * Returns a manager bound to [stub], optionally configured with a specific set of
     * [rpcEndpoints] (whose keys become `configuredChainIds`, used by `getAllBalances`), an
     * optional [tokenStore] (used by `sendToken` decimal resolution), and an optional
     * [transactionBuilder] (pass one over a mocked Web3j to exercise the withdrawal paths).
     */
    fun stubProviderManager(
        stub: StubWalletProvider = StubWalletProvider(),
        rpcEndpoints: Map<Int, String> = mapOf(1 to "https://rpc.test"),
        tokenStore: TokenMetadataStore? = null,
        transactionBuilder: RainTransactionBuilder = RainTransactionBuilderImpl(rpcEndpoints),
        chainReader: ChainReader = EvmChainReader(rpcEndpoints = rpcEndpoints),
    ): Pair<RainSdkManager, StubWalletProvider> {
        val manager = RainSdkManager(
            walletProvider = stub,
            rpcEndpoints = rpcEndpoints,
            tokenStore = tokenStore,
            transactionBuilder = transactionBuilder,
            chainReader = chainReader,
        )
        return manager to stub
    }

    /**
     * Manager wired to a [MockChainReader] and a [TokenMetadataStore] backed by the same reader —
     * the seam the approval flow needs, since it both encodes calldata and reads allowances off
     * chain.
     */
    fun approvalManager(
        stub: StubWalletProvider = StubWalletProvider(),
        reader: MockChainReader = MockChainReader(),
        transactionBuilder: RainTransactionBuilder = StubTransactionBuilder(),
        rpcEndpoints: Map<Int, String> = mapOf(RainChain.BASE_SEPOLIA to "https://rpc.test"),
        seedTokens: List<com.rain.sdk.models.TokenInfo> = emptyList(),
        authPullChainIds: Set<Int> = RainAuthPullChains.SANDBOX,
        authPullTokenAddresses: Map<Int, String>? = null,
        // 1 ms, not the production second: the confirmation tests assert attempt counts and outcomes,
        // never elapsed time, and at 1 s x 60 attempts four of them each waited out a real minute in CI.
        approvalConfirmationIntervalMs: Long = 1,
    ): Triple<RainSdkManager, StubWalletProvider, MockChainReader> {
        val manager = RainSdkManager(
            walletProvider = stub,
            rpcEndpoints = rpcEndpoints,
            tokenStore = TokenMetadataStore(chainReader = reader, seedTokens = seedTokens),
            transactionBuilder = transactionBuilder,
            chainReader = reader,
            authPullChainIds = authPullChainIds,
            authPullTokenAddresses = authPullTokenAddresses ?: authPullChainIds.associateWith {
                when (it) {
                    RainChain.BASE_SEPOLIA -> TestFixtures.AUTH_PULL_USDC_ADDRESS
                    RainChain.ARBITRUM_SEPOLIA ->
                        "0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d"
                    RainChain.BASE_MAINNET ->
                        "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
                    RainChain.ARBITRUM_MAINNET ->
                        "0xaf88d065e77c8cC2239327C5EDb3A432268e5831"
                    else -> TestFixtures.AUTH_PULL_USDC_ADDRESS
                }
            },
            approvalConfirmationIntervalMs = approvalConfirmationIntervalMs,
        )
        return Triple(manager, stub, reader)
    }

    /** A manager built the way [RainSdk] builds one when no `authPullConfig(...)` was supplied. */
    fun authPullDisabledManager(
        reader: MockChainReader = MockChainReader(),
        rpcEndpoints: Map<Int, String> = mapOf(RainChain.BASE_SEPOLIA to "https://rpc.test"),
    ): RainSdkManager = RainSdkManager(
        walletProvider = StubWalletProvider(),
        rpcEndpoints = rpcEndpoints,
        tokenStore = TokenMetadataStore(chainReader = reader, seedTokens = emptyList()),
        transactionBuilder = RainTransactionBuilderImpl(rpcEndpoints),
        chainReader = reader,
    )
}
