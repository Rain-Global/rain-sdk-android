package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainSdk
import com.rain.sdk.models.RainPreparedWithdrawal
import com.rain.sdk.provider.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64

/**
 * Integration coverage for the Turnkey path through a resolved [com.rain.sdk.interfaces.RainClient]
 * — registers the descriptor over a mock TurnkeyContext, resolves a client the way a host does, and
 * drives `prepareWithdrawal` end-to-end to assert the EIP-712 payload is signed via
 * `signRawPayload` with the correct encoding.
 *
 * Driven through the public builder rather than core's `RainSdkManager`: that class is internal to
 * core, and Kotlin `internal` does not cross a Gradle module boundary. Going through
 * `RainSdk.provider(id)` also exercises descriptor registration and client resolution, which is
 * what a host actually does.
 *
 * Gated on JDK 24+ because Turnkey's published AAR is compiled against major class version 68.
 *
 * IMPORTANT: this file deliberately avoids referencing any Turnkey type (or any of our own
 * types that have Turnkey in their signature, like `MockTurnkey`) in method or field signatures.
 * JUnit calls `Class.getDeclaredMethods()` during test discovery, which eagerly resolves every
 * method's parameter/return types — and that would trigger a cascading load of `TurnkeyContext`
 * on JDK 21, failing before `assumeTrue` runs. All Turnkey-touched values live inside test method
 * bodies and are typed as `Any` at the field level.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnkeyWalletFlowTest {

    private var sdk: Any? = null
    private var mockTurnkey: Any? = null

    @Before
    fun setUpAndGate() {
        assumeJdk24()

        // TurnkeyConfig touches the vendor's TurnkeyContext singleton, whose class initializer
        // dispatches onto Dispatchers.Main; on the JVM that dispatcher is absent.
        Dispatchers.setMain(StandardTestDispatcher())

        // Build Turnkey-touching objects only after the JDK-24 gate has passed.
        val tk = MockTurnkey()
        mockTurnkey = tk
        sdk = RainSdk.builder()
            .rpcEndpoints(mapOf(1 to "https://rpc.example/test"))
            .register(TurnkeyProvider(TurnkeyConfig(turnkey = com.turnkey.core.TurnkeyContext), contextOverride = tk))
            .build()
    }

    @After
    fun tearDown() {
        (sdk as? RainSdk)?.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `withdrawCollateral routes signing through Turnkey`() = runBlocking {
        val rain = sdk as RainSdk
        val turnkey = mockTurnkey as MockTurnkey
        val client = rain.provider(ProviderId.TURNKEY)

        val chainId = 1

        assertThat(client.isInitialized).isTrue()
        assertThat(client.getWalletAddress()).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)

        val addresses = com.rain.sdk.models.RainWithdrawAddresses(
            proxyAddress = "0x0000000000000000000000000000000000000001",
            controllerAddress = "0x0000000000000000000000000000000000000002",
            tokenAddress = "0x0000000000000000000000000000000000000003",
            recipientAddress = "0x0000000000000000000000000000000000000004"
        )
        val adminSignature = com.rain.sdk.models.RainAdminSignature(
            salt = Base64.getEncoder().encodeToString(ByteArray(32)),
            signature = "0x" + "01".repeat(65),
            expiresAt = "2025-12-31T23:59:59Z"
        )

        val prepared = client.prepareWithdrawal(
            chainId = chainId,
            addresses = addresses,
            amount = BigDecimal("100.0"),
            decimals = 18,
            adminSignature = adminSignature,
            nonce = BigInteger.valueOf(42) // explicit nonce, builder skips its RPC
        )

        // A complete, submittable transaction — not the bare calldata the old result carried.
        val parameters = (prepared as RainPreparedWithdrawal.Evm).parameters
        assertThat(parameters.data).startsWith("0x")
        assertThat(parameters.from).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        assertThat(parameters.to).isEqualTo(addresses.controllerAddress)
        assertThat(parameters.value).isEqualTo("0x0")

        // EIP-712 message went through Turnkey signRawPayload with the right encoding/hash.
        assertThat(turnkey.signRawPayloadCalls).hasSize(1)
        val signCall = turnkey.signRawPayloadCalls.single()
        assertThat(signCall.signWith).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        assertThat(signCall.encoding)
            .isEqualTo(com.turnkey.types.V1PayloadEncoding.PAYLOAD_ENCODING_EIP712)
        assertThat(signCall.hashFunction)
            .isEqualTo(com.turnkey.types.V1HashFunction.HASH_FUNCTION_NO_OP)
        assertThat(signCall.payload).isNotEmpty()
    }
}
