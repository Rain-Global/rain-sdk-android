package com.rain.sdk.internal.transaction

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.network.Web3jProvider
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainWithdrawAddresses
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.EthCall
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * Tests for the transaction-building primitives behind `withdrawCollateral`: nonce read,
 * EIP-712 message generation, and withdraw calldata encoding. Drives
 * [RainTransactionBuilderImpl] directly rather than going through `RainSdkManager`, since
 * the builder is wallet-provider-agnostic.
 */
class TransactionBuildingTest {

    private lateinit var mockWeb3j: Web3j

    /** Builder over the mocked Web3j, configured for every chain these tests exercise. */
    private lateinit var builder: RainTransactionBuilderImpl

    private val validAddresses = RainWithdrawAddresses(
        proxyAddress = TestFixtures.PROXY_ADDRESS,
        controllerAddress = TestFixtures.CONTROLLER_ADDRESS,
        tokenAddress = TestFixtures.TOKEN_ADDRESS,
        recipientAddress = TestFixtures.RECIPIENT_ADDRESS
    )

    @Before
    fun setUp() {
        mockkStatic(URLUtil::class)
        every { URLUtil.isValidUrl(any()) } returns true

        mockWeb3j = mockk(relaxed = true)
        builder = RainTransactionBuilderImpl(
            mapOf(1 to "https://rpc.example/test", 137 to "https://rpc.example/polygon")
        ) { mockWeb3j }

        Web3jProvider.resetForTest()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Web3jProvider.resetForTest()
    }

    // ---- buildEIP712Message success cases ---------------------------------------

    @Test
    fun `buildEIP712Message succeeds with explicit nonce and 18 decimals`() = runBlocking {
        val eip712 = builder.buildEIP712Message(
            chainId = 1,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            addresses = validAddresses,
            amount = BigDecimal("100.0"),
            decimals = 18,
            nonce = BigInteger.valueOf(42)
        )
        val json = eip712.message

        assertThat(json).contains("\"primaryType\": \"Withdraw\"")
        assertThat(json).contains("\"chainId\": 1")
        assertThat(json).contains("\"nonce\": \"42\"")
        // 100 * 10^18 = 100000000000000000000
        assertThat(json).contains("\"amount\": \"100000000000000000000\"")
        assertThat(eip712.salt).hasLength(32)
        // saltHex renders the same 32 bytes the EIP-712 domain carries.
        assertThat(eip712.saltHex).isEqualTo(
            "0x" + eip712.salt.joinToString("") { "%02x".format(Locale.ROOT, it) }
        )
        assertThat(json).contains(eip712.saltHex)
    }

    @Test
    fun `buildEIP712Message uses 6 decimals correctly (USDC-like)`() = runBlocking {
        val json = builder.buildEIP712Message(
            chainId = 1,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            addresses = validAddresses,
            amount = BigDecimal("100.5"),
            decimals = 6,
            nonce = BigInteger.ONE
        ).message

        assertThat(json).contains("\"amount\": \"100500000\"")
    }

    @Test
    fun `buildEIP712Message handles zero amount`() = runBlocking {
        val json = builder.buildEIP712Message(
            chainId = 1,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            addresses = validAddresses,
            amount = BigDecimal.ZERO,
            decimals = 18,
            nonce = BigInteger.ZERO
        ).message

        assertThat(json).contains("\"amount\": \"0\"")
    }

    @Test
    fun `buildEIP712Message generates a different salt on each call`() = runBlocking {
        val first = builder.buildEIP712Message(
            chainId = 1,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            addresses = validAddresses,
            amount = BigDecimal("100.0"),
            decimals = 18,
            nonce = BigInteger.ONE
        )
        val second = builder.buildEIP712Message(
            chainId = 1,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            addresses = validAddresses,
            amount = BigDecimal("100.0"),
            decimals = 18,
            nonce = BigInteger.ONE
        )

        assertThat(first.salt).isNotEqualTo(second.salt)
        assertThat(first.message).isNotEqualTo(second.message)
    }

    @Test
    fun `buildEIP712Message respects different chain IDs`() = runBlocking {
        val mainnet = builder.buildEIP712Message(
            chainId = 1,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            addresses = validAddresses,
            amount = BigDecimal("100.0"),
            decimals = 18,
            nonce = BigInteger.ONE
        ).message
        val polygon = builder.buildEIP712Message(
            chainId = 137,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            addresses = validAddresses,
            amount = BigDecimal("100.0"),
            decimals = 18,
            nonce = BigInteger.ONE
        ).message

        assertThat(mainnet).contains("\"chainId\": 1")
        assertThat(polygon).contains("\"chainId\": 137")
    }

    // ---- buildEIP712Message nonce resolution ------------------------------------

    @Test
    fun `buildEIP712Message reads nonce from RPC when nonce param is null`() = runBlocking {
        val mockEthCall = mockk<Request<*, EthCall>>()
        val response = EthCall().apply {
            // BigInteger.TEN encoded as a uint256
            result = "0x" + "0".repeat(63) + "a"
        }
        every { mockWeb3j.ethCall(any(), any()) } returns mockEthCall
        every { mockEthCall.sendAsync() } returns CompletableFuture.completedFuture(response)

        val json = builder.buildEIP712Message(
            chainId = 1,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            addresses = validAddresses,
            amount = BigDecimal("1.0"),
            decimals = 18,
            nonce = null
        ).message

        assertThat(json).contains("\"nonce\": \"10\"")
    }

    @Test
    fun `buildEIP712Message throws InvalidConfig when nonce is null and RPC not configured`() {
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                builder.buildEIP712Message(
                    chainId = 999,
                    addresses = validAddresses,
                    walletAddress = TestFixtures.WALLET_ADDRESS,
                    amount = BigDecimal("100.0"),
                    decimals = 18,
                    nonce = null
                )
            }
        }
    }

    @Test
    fun `buildEIP712Message uses explicit nonce even for unknown chainId (no RPC needed)`() = runBlocking {
        val json = builder.buildEIP712Message(
            chainId = 999,
            walletAddress = TestFixtures.WALLET_ADDRESS,
            addresses = validAddresses,
            amount = BigDecimal("100.0"),
            decimals = 18,
            nonce = BigInteger.ONE
        ).message
        assertThat(json).contains("\"chainId\": 999")
    }

    // ---- buildEIP712Message address validation ----------------------------------

    @Test
    fun `buildEIP712Message throws InvalidConfig for malformed proxy address`() {
        val bad = validAddresses.copy(proxyAddress = "not-an-address")
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                builder.buildEIP712Message(
                    chainId = 1,
                    addresses = bad,
                    walletAddress = TestFixtures.WALLET_ADDRESS,
                    amount = BigDecimal("100.0"),
                    decimals = 18,
                    nonce = BigInteger.ONE
                )
            }
        }
    }

    @Test
    fun `buildEIP712Message throws InvalidConfig for malformed recipient address`() {
        val bad = validAddresses.copy(recipientAddress = "invalid")
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                builder.buildEIP712Message(
                    chainId = 1,
                    addresses = bad,
                    walletAddress = TestFixtures.WALLET_ADDRESS,
                    amount = BigDecimal("100.0"),
                    decimals = 18,
                    nonce = BigInteger.ONE
                )
            }
        }
    }

    @Test
    fun `buildEIP712Message throws InvalidConfig for malformed wallet address`() {
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                builder.buildEIP712Message(
                    chainId = 1,
                    addresses = validAddresses,
                    walletAddress = "0xnope",
                    amount = BigDecimal("100.0"),
                    decimals = 18,
                    nonce = BigInteger.ONE
                )
            }
        }
    }

    // ---- buildWithdrawTransactionData -------------------------------------------

    @Test
    fun `buildWithdrawTransactionData encodes 0x-prefixed hex calldata`() {
        val data = builder.buildWithdrawTransactionData(
            addresses = validAddresses,
            amount = BigDecimal("100.0"),
            decimals = 18,
            executorSignature = RainAdminSignature(
                salt = Base64.getEncoder().encodeToString(ByteArray(32) { 0xAA.toByte() }),
                signature = "0x" + "bb".repeat(65),
                expiresAt = "2025-01-01T00:00:00Z"
            ),
            walletSalt = ByteArray(32) { 0x11.toByte() },
            walletSignature = "0x" + "42".repeat(65)
        )

        assertThat(data).startsWith("0x")
        // ABI-encoded function selectors are 4 bytes (8 hex chars after "0x"), so data is much longer
        assertThat(data.length).isGreaterThan(10)
    }

    @Test
    fun `buildWithdrawTransactionData accepts ISO8601 timestamp`() {
        val instant = Instant.parse("2025-06-15T12:00:00Z")
        // Just verify it doesn't throw — the timestamp is parsed and embedded.
        val data = builder.buildWithdrawTransactionData(
            addresses = validAddresses,
            amount = BigDecimal("1.0"),
            decimals = 18,
            executorSignature = RainAdminSignature(
                salt = Base64.getEncoder().encodeToString(ByteArray(32)),
                signature = "0x" + "bb".repeat(65),
                expiresAt = instant.toString()
            ),
            walletSalt = ByteArray(32) { 0x11.toByte() },
            walletSignature = "0x" + "42".repeat(65)
        )
        assertThat(data).startsWith("0x")
    }

    @Test
    fun `buildWithdrawTransactionData accepts a unix-seconds expiresAt`() {
        // Rain's API has returned both unix-seconds and ISO-8601 shapes.
        val data = builder.buildWithdrawTransactionData(
            addresses = validAddresses,
            amount = BigDecimal("1.0"),
            decimals = 18,
            executorSignature = RainAdminSignature(
                salt = Base64.getEncoder().encodeToString(ByteArray(32)),
                signature = "0x" + "bb".repeat(65),
                expiresAt = "1735689600"
            ),
            walletSalt = ByteArray(32) { 0x11.toByte() },
            walletSignature = "0x" + "42".repeat(65)
        )
        assertThat(data).startsWith("0x")

        // Same instant expressed as ISO-8601 must encode identically.
        val iso = builder.buildWithdrawTransactionData(
            addresses = validAddresses,
            amount = BigDecimal("1.0"),
            decimals = 18,
            executorSignature = RainAdminSignature(
                salt = Base64.getEncoder().encodeToString(ByteArray(32)),
                signature = "0x" + "bb".repeat(65),
                expiresAt = Instant.ofEpochSecond(1735689600).toString()
            ),
            walletSalt = ByteArray(32) { 0x11.toByte() },
            walletSignature = "0x" + "42".repeat(65)
        )
        assertThat(data).isEqualTo(iso)
    }

    @Test
    fun `buildWithdrawTransactionData throws InvalidConfig for malformed expiresAt`() {
        val ex = runCatching {
            builder.buildWithdrawTransactionData(
                addresses = validAddresses,
                amount = BigDecimal("100.0"),
                decimals = 18,
                executorSignature = RainAdminSignature(
                    salt = Base64.getEncoder().encodeToString(ByteArray(32)),
                    signature = "0x" + "bb".repeat(65),
                    expiresAt = "not-a-timestamp"
                ),
                walletSalt = ByteArray(32),
                walletSignature = "0x" + "42".repeat(65)
            )
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(RainError.InvalidConfig::class.java)
    }

    private fun withdrawWith(executorSignature: RainAdminSignature): String = builder.buildWithdrawTransactionData(
        addresses = validAddresses,
        amount = BigDecimal("100.0"),
        decimals = 18,
        executorSignature = executorSignature,
        walletSalt = ByteArray(32),
        walletSignature = "0x" + "42".repeat(65)
    )

    private fun executorSignature(
        salt: String = Base64.getEncoder().encodeToString(ByteArray(32)),
        signature: String = "0x" + "bb".repeat(65),
        expiresAt: String = "2025-01-01T00:00:00Z"
    ) = RainAdminSignature(salt = salt, signature = signature, expiresAt = expiresAt)

    /** A host builds the signature from its own JSON, so a bad salt is its configuration error, not an SDK failure. */
    @Test
    fun `buildWithdrawTransactionData rejects an executor salt that is not base64 as InvalidConfig`() {
        val error = assertThrows(RainError.InvalidConfig::class.java) { withdrawWith(executorSignature(salt = "not base64!")) }
        assertThat(error).hasMessageThat().contains("RainAdminSignature.salt is not valid base64")
    }

    @Test
    fun `buildWithdrawTransactionData rejects a 31-byte executor salt`() {
        val error = assertThrows(RainError.InvalidConfig::class.java) {
            withdrawWith(executorSignature(salt = Base64.getEncoder().encodeToString(ByteArray(31))))
        }
        assertThat(error).hasMessageThat().contains("RainAdminSignature.salt must be 32 bytes")
    }

    @Test
    fun `buildWithdrawTransactionData rejects a 64-byte executor signature`() {
        val error = assertThrows(RainError.InvalidConfig::class.java) {
            withdrawWith(executorSignature(signature = "0x" + "bb".repeat(64)))
        }
        assertThat(error).hasMessageThat().contains("expected 65 bytes")
    }

    @Test
    fun `buildWithdrawTransactionData rejects a blank expiresAt`() {
        val error = assertThrows(RainError.InvalidConfig::class.java) { withdrawWith(executorSignature(expiresAt = "  ")) }
        assertThat(error).hasMessageThat().contains("Invalid expiresAt format")
    }

    /** Every accepted shape of the same instant must encode the same calldata. */
    @Test
    fun `buildWithdrawTransactionData accepts every expiresAt shape the parser accepts`() {
        val reference = withdrawWith(executorSignature(expiresAt = "2025-01-01T00:00:00Z"))
        for (shape in listOf("1735689600", " 1735689600 ", "2025-01-01T02:00:00+02:00", "2025-01-01T00:00:00.000Z")) {
            assertWithMessage(shape).that(withdrawWith(executorSignature(expiresAt = shape))).isEqualTo(reference)
        }
    }

    @Test
    fun `buildWithdrawTransactionData throws InvalidConfig for malformed proxy address`() {
        val bad = validAddresses.copy(proxyAddress = "bad")
        assertThrows(RainError.InvalidConfig::class.java) {
            builder.buildWithdrawTransactionData(
                addresses = bad,
                amount = BigDecimal("100.0"),
                decimals = 18,
                executorSignature = RainAdminSignature(
                    salt = Base64.getEncoder().encodeToString(ByteArray(32)),
                    signature = "0x" + "bb".repeat(65),
                    expiresAt = "2025-01-01T00:00:00Z"
                ),
                walletSalt = ByteArray(32),
                walletSignature = "0x" + "42".repeat(65)
            )
        }
    }

    @Test
    fun `buildWithdrawTransactionData accepts 6-decimal tokens (USDC-like)`() {
        val data = builder.buildWithdrawTransactionData(
            addresses = validAddresses,
            amount = BigDecimal("100.5"),
            decimals = 6,
            executorSignature = RainAdminSignature(
                salt = Base64.getEncoder().encodeToString(ByteArray(32)),
                signature = "0x" + "bb".repeat(65),
                expiresAt = "2025-01-01T00:00:00Z"
            ),
            walletSalt = ByteArray(32) { 0x11.toByte() },
            walletSignature = "0x" + "42".repeat(65)
        )
        assertThat(data).startsWith("0x")
    }
}
