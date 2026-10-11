package com.rain.sdk.turnkey

import com.rain.sdk.internal.network.chainreader.ChainReader
import com.rain.sdk.internal.network.chainreader.EvmChainReader
import com.rain.sdk.internal.network.chainreader.JsonRpcClient
import com.rain.sdk.internal.network.chainreader.SolanaChainReader
import com.rain.sdk.internal.solana.SolanaRpcClient
import com.rain.sdk.internal.solana.SolanaTransferComposer
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.turnkey.core.models.errors.TurnkeyKotlinError
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import timber.log.Timber

/**
 * Test doubles and fixtures for the Turnkey adapter's own suites.
 *
 * Core's equivalents live in its test source set, which does not cross a Gradle module boundary,
 * so each adapter module carries its own — the same arrangement as `PortalTestFixtures`. Keep these
 * in step with core's when a shared seam changes.
 */
internal object TurnkeyTestFixtures {
    const val RECIPIENT_ADDRESS = "0xfedcbafedcbafedcbafedcbafedcbafedcbafedc"
    const val TOKEN_ADDRESS = "0x9876543210987654321098765432109876543210"
    const val USDC_ADDRESS = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
}

/**
 * Turnkey's published AAR is compiled to class-file major version 68 (Java 24), so loading any
 * Turnkey type on an older JVM fails before a test body runs. Unit tests run on a JDK 24 launcher
 * (see the root build); this guard keeps a local run on an older JDK from reporting a hard failure.
 */
internal fun assumeJdk24() {
    val major = System.getProperty("java.version")?.substringBefore('.')?.toIntOrNull() ?: 0
    assumeTrue(
        "Turnkey SDK types load JDK-24 class files. Current: $major",
        major >= 24
    )
}

/** Runs [block] with a Timber tree planted and returns every (priority, message) it logged, in order. */
internal fun capturingLogs(block: () -> Unit): List<Pair<Int, String>> {
    val entries = mutableListOf<Pair<Int, String>>()
    val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            entries += priority to message
        }
    }
    Timber.plant(tree)
    try {
        block()
    } finally {
        Timber.uproot(tree)
    }
    return entries
}

/**
 * The vendor's failure for a refresh Turnkey refused. `TurnkeyContext.refreshSession` (sdk-kotlin
 * 2.0.2) wraps the stamp-login failure in `FailedToRefreshSession`, and the typed client's message
 * (http 2.1.1 `TurnkeyClient`) carries the status in the shape `TurnkeyErrorMapping.turnkeyHttpStatus`
 * reads. `PUBLIC_KEY_NOT_FOUND` is the answer a session revoked by a login on another device gets.
 */
internal fun revokedSessionRefreshFailure(): Exception = TurnkeyKotlinError.FailedToRefreshSession(
    RuntimeException(
        "HTTP error calling ACTIVITY_TYPE_STAMP_LOGIN request\nError: PUBLIC_KEY_NOT_FOUND\nCode: 401"
    )
)

/**
 * Runs a suspending [block] and returns the [T] it threw. Fails the test when it threw nothing;
 * rethrows anything of another type so a wrong error class is never mistaken for the right one.
 */
internal suspend inline fun <reified T : Throwable> expectThrows(block: suspend () -> Unit): T {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return t
        throw t
    }
    throw AssertionError("Expected ${T::class.simpleName} to be thrown")
}

/**
 * Builds a [TurnkeyWalletProvider] over a [TurnkeyManager] the way `TurnkeyProvider.create()` does,
 * with the parameter list the provider suites were written against, so a test states its doubles once.
 * One HTTP client, one JSON-RPC client and one Solana RPC client back both classes, as in production.
 */
@Suppress("LongParameterList") // mirrors the wiring the suites were written against; every argument is named
internal fun turnkeyWalletProvider(
    turnkey: TurnkeyContextProtocol,
    rpcEndpoints: Map<Int, String>,
    walletAddressOverride: String? = null,
    httpClient: OkHttpClient = OkHttpClient(),
    pollingIntervalMs: Long = TurnkeyManager.POLLING_INTERVAL_MS,
    chainReader: ChainReader? = null,
    solanaChainReader: ChainReader? = null,
    tokenStore: TokenMetadataStore? = null,
    sessionCoordinator: TurnkeySessionCoordinator? = null,
    sponsorGas: Boolean = false,
    registryReadTimeoutMs: Long = TurnkeyWalletProvider.REGISTRY_READ_TIMEOUT_MS
): TurnkeyWalletProvider {
    val jsonRpcClient = JsonRpcClient(httpClient)
    val solanaRpcClient = SolanaRpcClient(jsonRpcClient)
    val evmReader = chainReader ?: EvmChainReader(rpcEndpoints = rpcEndpoints, jsonRpcClient = jsonRpcClient)
    val manager = TurnkeyManager(
        turnkey = turnkey,
        rpcEndpoints = rpcEndpoints,
        solanaRpcClient = solanaRpcClient,
        sponsorGas = sponsorGas,
        walletAddressOverride = walletAddressOverride,
        httpClient = httpClient,
        pollingIntervalMs = pollingIntervalMs,
        jsonRpcClient = jsonRpcClient,
        sessionCoordinator = sessionCoordinator ?: TurnkeySessionCoordinator(turnkey),
    )
    return TurnkeyWalletProvider(
        manager = manager,
        chainReader = evmReader,
        solanaChainReader = solanaChainReader
            ?: SolanaChainReader(rpcEndpoints = rpcEndpoints, solanaRpcClient = solanaRpcClient),
        solanaTransferComposer = SolanaTransferComposer(solanaRpcClient, rpcEndpoints::get),
        tokenStore = tokenStore ?: TokenMetadataStore(evmReader),
        registryReadTimeoutMs = registryReadTimeoutMs,
    )
}
