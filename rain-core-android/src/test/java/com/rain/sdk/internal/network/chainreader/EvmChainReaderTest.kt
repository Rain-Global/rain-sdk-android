package com.rain.sdk.internal.network.chainreader

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.helpers.MockRpcServer
import com.rain.sdk.models.RainTokenAllowance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * Covers the two read paths the reader exposes:
 *  - Multicall3 batched aggregate3 (canonically-deployed chain — Ethereum mainnet, id = 1)
 *  - Parallel `eth_call` fallback (chain outside the deployment list — Avalanche Fuji 43113)
 *
 * Plus the rich [Token]/[com.rain.sdk.models.Balance] surface and metadata reads
 * (`getDecimals` / `getSymbol`).
 */
class EvmChainReaderTest {

    private lateinit var rpc: MockRpcServer

    private val wallet = "0x1111111111111111111111111111111111111111"
    private val usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val dai = "0x6b175474e89094c44da98b954eedeac495271d0f"

    /** Rain's sandbox Auth Pull operator — the allowance spender. */
    private val spender = "0x5a6E6b0d5Ea051CfFF9b3dcC2Aa8Dac226458f29"
    private val txHash = "0x" + "ab".repeat(32)

    @Before
    fun setUp() {
        rpc = MockRpcServer().also { it.start() }
    }

    @After
    fun tearDown() {
        rpc.shutdown()
    }

    private fun makeReader(chainId: Int): EvmChainReader =
        EvmChainReader(rpcEndpoints = mapOf(chainId to rpc.urlFor(chainId)))

    // ---------- single-token paths ----------

    @Test
    fun `getNativeBalance parses eth_getBalance hex into ether units`(): Unit = runBlocking {
        // 0xDE0B6B3A7640000 = 1e18 wei = 1 ETH
        rpc.stub("eth_getBalance", "0xde0b6b3a7640000")
        val reader = makeReader(chainId = 1)

        val balance = reader.getNativeBalance(1, wallet)

        assertThat(balance).isEqualToIgnoringScale("1")
        assertThat(rpc.recordedMethods).containsExactly("eth_getBalance")
    }

    @Test
    fun `getNativeBalance keeps full 18-decimal precision above Double range`(): Unit = runBlocking {
        // 12345678901234567890123 wei — above 2^53, so a Double round-trip would corrupt it.
        rpc.stub("eth_getBalance", "0x29d42b64e76714244cb")
        val reader = makeReader(chainId = 1)

        val balance = reader.getNativeBalance(1, wallet)

        assertThat(balance).isEqualToIgnoringScale("12345.678901234567890123")
    }

    @Test
    fun `getERC20Balance issues eth_call and scales by token decimals`() = runBlocking {
        // 0xf4240 = 1_000_000 (1.00 USDC at 6 decimals), padded to a 32-byte uint256.
        rpc.stub("eth_call", "0x" + "0".repeat(59) + "f4240")
        val reader = makeReader(chainId = 1)

        val balance = reader.getERC20Balance(1, usdc, wallet, decimals = 6)

        assertThat(balance).isEqualToIgnoringScale("1")
    }

    // ---------- rich getBalances: parallel-fallback path ----------

    @Test
    fun `getBalances on a non-Multicall3 chain fans out one balanceOf per token`() = runBlocking {
        // Use a chain not in MULTICALL3_DEPLOYMENTS so the parallel fallback runs.
        val chainId = 43113 // Avalanche Fuji testnet
        rpc.stub("eth_getBalance", "0x0") // native = 0
        // 0xf4240 = 1_000_000 → exact raw. Both tokens share the same stubbed eth_call
        // response (MockRpcServer dispatches by method, not by request body).
        rpc.stub("eth_call", "0x" + "0".repeat(59) + "f4240")
        val reader = EvmChainReader(rpcEndpoints = mapOf(chainId to rpc.urlFor(chainId)))

        val balances = reader.getBalances(
            chainId = chainId,
            walletAddress = wallet,
            tokens = listOf(
                TokenInfo(chainId, usdc, "USDC", 6),
                TokenInfo(chainId, dai, "DAI", 6)
            )
        )

        assertThat(balances).hasSize(3)
        val native = balances.single { it.token is Token.Native }
        assertThat(native.rawAmount).isEqualTo(BigInteger.ZERO)
        assertThat(native.decimals).isEqualTo(18)

        val usdcBalance = balances.single { it.token == Token.Contract(usdc) }
        assertThat(usdcBalance.rawAmount).isEqualTo(BigInteger("1000000"))
        assertThat(usdcBalance.decimals).isEqualTo(6)
        assertThat(usdcBalance.symbol).isEqualTo("USDC")
        assertThat(usdcBalance.formatted).isEqualTo("1")

        assertThat(balances.single { it.token == Token.Contract(dai) }.rawAmount)
            .isEqualTo(BigInteger("1000000"))
    }

    @Test
    fun `getBalances surfaces native success but omits per-token failures`() = runBlocking {
        // Native call works; the eth_call shared by both tokens returns an error.
        val chainId = 43113
        rpc.stub("eth_getBalance", "0xde0b6b3a7640000") // 1 ETH
        rpc.stubNetworkFailure("eth_call")
        val reader = EvmChainReader(rpcEndpoints = mapOf(chainId to rpc.urlFor(chainId)))

        val balances = reader.getBalances(
            chainId = chainId,
            walletAddress = wallet,
            tokens = listOf(TokenInfo(chainId, usdc, "USDC", 6))
        )

        assertThat(balances).hasSize(1)
        assertThat(balances.single().token).isEqualTo(Token.Native)
        assertThat(balances.single().rawAmount).isEqualTo(BigInteger("1000000000000000000"))
    }

    @Test
    fun `getBalances drops a malformed token address instead of failing the batch`() = runBlocking {
        val chainId = 43113
        rpc.stub("eth_getBalance", "0x0")
        rpc.stub("eth_call", "0x" + "0".repeat(59) + "f4240")
        val reader = EvmChainReader(rpcEndpoints = mapOf(chainId to rpc.urlFor(chainId)))

        val balances = reader.getBalances(
            chainId = chainId,
            walletAddress = wallet,
            tokens = listOf(
                TokenInfo(chainId, "0x7f5c764c", "BAD", 6), // truncated address
                TokenInfo(chainId, usdc, "USDC", 6)
            )
        )

        // Native + USDC; the malformed entry loses only its own balance.
        assertThat(balances).hasSize(2)
        assertThat(balances.single { it.token is Token.Contract }.token)
            .isEqualTo(Token.Contract(usdc))
    }

    @Test
    fun `getBalances on a Multicall3 chain excludes a malformed token address from the batch`() = runBlocking {
        // The response below carries exactly two results (native + USDC). It only decodes if
        // the malformed token never entered the batch: the reader demands one result per
        // submitted call, so a three-call batch against this payload would throw.
        fun slot(v: String) = "0".repeat(64 - v.length) + v
        rpc.stub(
            "eth_call",
            "0x" +
                slot("20") + // outer offset
                slot("2") + // count = 2
                slot("40") + // offset to tuple 0
                slot("c0") + // offset to tuple 1 (0x40 + 0x80 tuple size)
                slot("1") + // t0 success
                slot("40") + // t0 returnData offset
                slot("20") + // t0 returnData length
                slot("de0b6b3a7640000") + // t0 = 1 ETH in wei
                slot("1") + // t1 success
                slot("40") + // t1 returnData offset
                slot("20") + // t1 returnData length
                slot("f4240") // t1 = 1_000_000 (1 USDC at 6 decimals)
        )
        val reader = makeReader(chainId = 1)

        val balances = reader.getBalances(
            chainId = 1,
            walletAddress = wallet,
            tokens = listOf(
                TokenInfo(1, "0x7f5c764c", "BAD", 6),
                TokenInfo(1, usdc, "USDC", 6)
            )
        )

        assertThat(balances).hasSize(2)
        assertThat(balances.single { it.token is Token.Native }.rawAmount)
            .isEqualTo(BigInteger("1000000000000000000"))
        assertThat(balances.single { it.token == Token.Contract(usdc) }.rawAmount)
            .isEqualTo(BigInteger("1000000"))
    }

    /** The canonical address has no code on zkSync Era; batching there must go to its own deployment. */
    @Test
    fun `getBalances on zkSync Era targets the zkSync Multicall3 address, not the canonical one`() = runBlocking {
        fun slot(v: String) = "0".repeat(64 - v.length) + v
        rpc.stub(
            "eth_call",
            "0x" +
                slot("20") + // outer offset
                slot("1") + // count = 1
                slot("20") + // offset to tuple 0
                slot("1") + // t0 success
                slot("40") + // t0 returnData offset
                slot("20") + // t0 returnData length
                slot("de0b6b3a7640000") // t0 = 1 ETH in wei
        )
        val zkSyncEra = 324
        val reader = makeReader(chainId = zkSyncEra)

        val balances = reader.getBalances(chainId = zkSyncEra, walletAddress = wallet, tokens = emptyList())

        assertThat(balances).hasSize(1)
        assertThat(rpc.recordedMethods).containsExactly("eth_call")
        val body = rpc.recordedBodies.single()
        // The checksummed form only appears in the `to` field; calldata targets are lowercase hex.
        assertThat(body).contains(Multicall3.ZKSYNC_ERA_ADDRESS)
        assertThat(body).doesNotContain(Multicall3.CANONICAL_ADDRESS)
    }

    @Test
    fun `getBalances treats native eth_getBalance failure as fatal`() {
        val chainId = 43113
        rpc.stubNetworkFailure("eth_getBalance")
        val reader = EvmChainReader(rpcEndpoints = mapOf(chainId to rpc.urlFor(chainId)))

        val ex = runCatching {
            runBlocking {
                reader.getBalances(
                    chainId = chainId,
                    walletAddress = wallet,
                    tokens = listOf(TokenInfo(chainId, usdc, "USDC", 6))
                )
            }
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(RainError.NetworkError::class.java)
    }

    // ---------- rich getBalance (single) ----------

    @Test
    fun `getBalance native builds a Balance from eth_getBalance with registry metadata`() = runBlocking {
        rpc.stub("eth_getBalance", "0xde0b6b3a7640000") // 1 ETH
        val reader = makeReader(chainId = 1)

        val balance = reader.getBalance(1, wallet, Token.Native, tokenInfo = null)

        assertThat(balance.token).isEqualTo(Token.Native)
        assertThat(balance.rawAmount).isEqualTo(BigInteger("1000000000000000000"))
        assertThat(balance.symbol).isEqualTo("ETH")
        assertThat(balance.decimals).isEqualTo(18)
    }

    @Test
    fun `getBalance contract builds a Balance from balanceOf with supplied metadata`() = runBlocking {
        rpc.stub("eth_call", "0x" + "0".repeat(59) + "f4240") // 1_000_000
        val reader = makeReader(chainId = 1)

        val balance = reader.getBalance(
            chainId = 1,
            walletAddress = wallet,
            token = Token.Contract(usdc),
            tokenInfo = TokenInfo(1, usdc, "USDC", 6, "USD Coin")
        )

        assertThat(balance.token).isEqualTo(Token.Contract(usdc))
        assertThat(balance.rawAmount).isEqualTo(BigInteger("1000000"))
        assertThat(balance.decimals).isEqualTo(6)
        assertThat(balance.symbol).isEqualTo("USDC")
        assertThat(balance.name).isEqualTo("USD Coin")
    }

    // ---------- metadata reads ----------

    @Test
    fun `getDecimals parses the eth_call uint into an Int`() = runBlocking {
        rpc.stub("eth_call", "0x" + "6".padStart(64, '0')) // 0x...06 = 6
        val reader = makeReader(chainId = 1)

        assertThat(reader.getDecimals(1, usdc)).isEqualTo(6)
    }

    @Test
    fun `getDecimals rejects malformed responses instead of treating them as zero`() {
        rpc.stub("eth_call", "0x")
        val reader = makeReader(chainId = 1)

        assertThrows(RainError.InternalError::class.java) {
            runBlocking { reader.getDecimals(1, usdc) }
        }
    }

    // ---------- allowances ----------

    @Test
    fun `getErc20Allowance reads eth_call and returns exact base units`() = runBlocking {
        // 250 USDC at 6 decimals = 250_000_000 = 0xee6b280.
        rpc.stub("eth_call", "0x" + "ee6b280".padStart(64, '0'))
        val reader = makeReader(chainId = 1)

        val allowance = reader.getErc20Allowance(1, usdc, wallet, spender)

        assertThat(allowance).isEqualTo(BigInteger.valueOf(250_000_000))
    }

    @Test
    fun `an unlimited allowance comes back exact rather than saturating`() = runBlocking {
        rpc.stub("eth_call", "0x" + "f".repeat(64))
        val reader = makeReader(chainId = 1)

        val allowance = reader.getErc20Allowance(1, usdc, wallet, spender)

        assertThat(allowance).isEqualTo(RainTokenAllowance.UNLIMITED_RAW_AMOUNT)
    }

    /**
     * The block tag is the whole defence against a load-balanced endpoint answering a
     * post-transaction read from a replica that is still behind. Defaulting it is fine for a
     * standalone read; silently ignoring a caller's block would put the staleness bug straight back.
     */
    @Test
    fun `getErc20Allowance reads at the requested block rather than at latest`() = runBlocking {
        rpc.stub("eth_call", "0x" + "ee6b280".padStart(64, '0'))
        val reader = makeReader(chainId = 1)

        reader.getErc20Allowance(1, usdc, wallet, spender, atBlock = "0x1a2b3c")

        val call = rpc.recordedBodies.single { it.contains("eth_call") }
        assertThat(call).contains("\"0x1a2b3c\"")
        assertThat(call).doesNotContain("latest")
    }

    @Test
    fun `getErc20Allowance defaults to latest when no block is given`() = runBlocking {
        rpc.stub("eth_call", "0x" + "ee6b280".padStart(64, '0'))
        val reader = makeReader(chainId = 1)

        reader.getErc20Allowance(1, usdc, wallet, spender)

        assertThat(rpc.recordedBodies.single { it.contains("eth_call") }).contains("\"latest\"")
    }

    @Test
    fun `getErc20Allowance surfaces a malformed payload instead of reporting no allowance`() =
        runBlocking {
            rpc.stub("eth_call", "0x")
            val reader = makeReader(chainId = 1)

            assertThrows(RainError::class.java) {
                runBlocking { reader.getErc20Allowance(1, usdc, wallet, spender) }
            }
            Unit
        }

    @Test
    fun `getErc20Allowance rejects a malformed spender before hitting the network`() =
        runBlocking {
            val reader = makeReader(chainId = 1)

            assertThrows(RainError::class.java) {
                runBlocking { reader.getErc20Allowance(1, usdc, wallet, "0xnope") }
            }
            Unit
        }

    @Test
    fun `getSymbol decodes an ABI-encoded string`() = runBlocking {
        // ABI string: [offset=0x20][length=4]["USDC" right-padded]
        val symbolHex = "0x" +
            "20".padStart(64, '0') +
            "4".padStart(64, '0') +
            "55534443".padEnd(64, '0') // "USDC"
        rpc.stub("eth_call", symbolHex)
        val reader = makeReader(chainId = 1)

        assertThat(reader.getSymbol(1, usdc)).isEqualTo("USDC")
    }

    // ---------- transaction receipts ----------

    /**
     * The status field is a JSON-RPC *quantity*, and nodes disagree about minimal encoding. Every
     * spelling of 1 has to read as success: an approval that mined is the input this drives, and
     * rejecting a valid receipt would fail a confirmation that in fact succeeded.
     */
    @Test
    fun `a successful receipt is recognised however the node encodes the status`(): Unit =
        runBlocking {
            for (encoded in listOf("0x1", "0X1", "0x01", "0x0000000000000001")) {
                rpc.stubObject("eth_getTransactionReceipt", receipt(status = encoded))
                val reader = makeReader(chainId = 1)

                assertThat(reader.getTransactionReceipt(1, txHash)?.succeeded).isTrue()
            }
        }

    @Test
    fun `a reverted receipt is recognised however the node encodes the status`(): Unit =
        runBlocking {
            for (encoded in listOf("0x0", "0X0", "0x00", "0x0000000000000000")) {
                rpc.stubObject("eth_getTransactionReceipt", receipt(status = encoded))
                val reader = makeReader(chainId = 1)

                assertThat(reader.getTransactionReceipt(1, txHash)?.succeeded).isFalse()
            }
        }

    /**
     * The block the transaction landed in is what a confirmation pins its read to, so losing it
     * here is what forced verification back onto `latest` — and onto whatever head answered.
     */
    @Test
    fun `a mined receipt carries the block it landed in`(): Unit = runBlocking {
        rpc.stubObject("eth_getTransactionReceipt", receipt(status = "0x1"))
        val reader = makeReader(chainId = 1)

        assertThat(reader.getTransactionReceipt(1, txHash)?.blockNumber).isEqualTo("0x10")
    }

    @Test
    fun `a receipt with no blockNumber is malformed rather than confirmable`() {
        rpc.stubObject(
            "eth_getTransactionReceipt",
            JSONObject().put("transactionHash", txHash).put("status", "0x1")
        )
        val reader = makeReader(chainId = 1)

        assertThrows(RainError.InternalError::class.java) {
            runBlocking { reader.getTransactionReceipt(1, txHash) }
        }
    }

    /** The value is sent back to a node as a block tag, so a non-quantity cannot be passed through. */
    @Test
    fun `a non-quantity blockNumber is rejected rather than used as a block tag`() {
        rpc.stubObject(
            "eth_getTransactionReceipt",
            JSONObject()
                .put("transactionHash", txHash)
                .put("blockNumber", "latest")
                .put("status", "0x1")
        )
        val reader = makeReader(chainId = 1)

        assertThrows(RainError.InternalError::class.java) {
            runBlocking { reader.getTransactionReceipt(1, txHash) }
        }
    }

    /** A pending transaction: the node has the hash but no receipt yet. `null` means keep polling. */
    @Test
    fun `a null result reads as pending rather than reverted`(): Unit = runBlocking {
        rpc.stubObject("eth_getTransactionReceipt", JSONObject.NULL)
        val reader = makeReader(chainId = 1)

        assertThat(reader.getTransactionReceipt(1, txHash)).isNull()
    }

    @Test
    fun `a status outside 0 and 1 is malformed rather than guessed at`() {
        rpc.stubObject("eth_getTransactionReceipt", receipt(status = "0x2"))
        val reader = makeReader(chainId = 1)

        assertThrows(RainError.InternalError::class.java) {
            runBlocking { reader.getTransactionReceipt(1, txHash) }
        }
    }

    @Test
    fun `a non-hex status is malformed rather than guessed at`() {
        rpc.stubObject("eth_getTransactionReceipt", receipt(status = "success"))
        val reader = makeReader(chainId = 1)

        assertThrows(RainError.InternalError::class.java) {
            runBlocking { reader.getTransactionReceipt(1, txHash) }
        }
    }

    /** Pre-Byzantium receipts carry no status. Unknown is not success. */
    @Test
    fun `a receipt with no status field throws instead of reading as mined`() {
        rpc.stubObject("eth_getTransactionReceipt", JSONObject().put("blockNumber", "0x1"))
        val reader = makeReader(chainId = 1)

        assertThrows(RainError.InternalError::class.java) {
            runBlocking { reader.getTransactionReceipt(1, txHash) }
        }
    }

    @Test
    fun `a malformed transaction hash is rejected before hitting the network`() {
        val reader = makeReader(chainId = 1)

        for (bad in listOf("0xnope", "0x" + "a".repeat(63), "0x" + "a".repeat(65), "a".repeat(64))) {
            assertThrows(RainError.InvalidConfig::class.java) {
                runBlocking { reader.getTransactionReceipt(1, bad) }
            }
        }
        assertThat(rpc.recordedMethods).isEmpty()
    }

    private fun receipt(status: String): JSONObject = JSONObject()
        .put("transactionHash", txHash)
        .put("blockNumber", "0x10")
        .put("status", status)

    // ---------- guards ----------

    @Test
    fun `getNativeBalance throws InvalidConfig when chain has no rpc configured`() {
        val reader = EvmChainReader(rpcEndpoints = emptyMap())
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { reader.getNativeBalance(1, wallet) }
        }
    }

    @Test
    fun `getNativeBalance throws InternalError on syntactically invalid wallet address`() {
        val reader = makeReader(chainId = 1)
        assertThrows(RainError.InternalError::class.java) {
            runBlocking { reader.getNativeBalance(1, walletAddress = "not-an-address") }
        }
    }
}
