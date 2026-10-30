package com.rain.sdk.privy

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.solana.SolanaSupport
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.privy.wallet.solana.SolanaCluster
import io.privy.wallet.transactions.GetTransactionsParams
import io.privy.wallet.transactions.TransactionChain
import io.privy.wallet.transactions.TransactionDetails
import io.privy.wallet.transactions.TransactionStatus
import io.privy.wallet.transactions.TransactionType
import io.privy.wallet.transactions.TransactionsPage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Solana support on the Privy adapter. Composition and preflights run for real against a mock
 * Solana RPC (through core's [SolanaSupport]); only the Privy custody seam ([PrivyManager]) is
 * mocked, so these tests pin what reaches Privy: the unsigned bytes, the cluster, and the RPC
 * URL broadcast goes through.
 */
class PrivySolanaProviderTest {

    private lateinit var rpc: SolanaRpcFixture

    @Before
    fun setUp() {
        rpc = SolanaRpcFixture().also { it.start() }
    }

    @After
    fun tearDown() = rpc.shutdown()

    // ---------- native send ----------

    @Test
    fun `sendNativeToken on solana composes and broadcasts through privy with the devnet cluster`(): Unit =
        runBlocking {
            rpc.stub("getLatestBlockhash", envelope(JSONObject().put("blockhash", BLOCKHASH)))
            val manager = solanaManager()
            val transaction = slot<ByteArray>()
            coEvery {
                manager.signAndSendSolanaTransaction(
                    capture(transaction),
                    SolanaCluster.DevNet,
                    rpc.url()
                )
            } returns SIGNATURE

            val result = provider(manager)
                .sendNativeToken(DEVNET, RECIPIENT, BigDecimal("0.5"))

            assertThat(result).isEqualTo(SIGNATURE)
            assertThat(transaction.captured).isNotEmpty()
        }

    @Test
    fun `sendNativeToken on solana rejects sub-lamport precision before signing`() {
        val manager = solanaManager()

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                provider(manager).sendNativeToken(DEVNET, RECIPIENT, BigDecimal("0.0000000015"))
            }
        }
        coVerify(exactly = 0) { manager.signAndSendSolanaTransaction(any(), any(), any()) }
    }

    // ---------- SPL send ----------

    @Test
    fun `sendToken on solana runs preflights and broadcasts through privy`(): Unit = runBlocking {
        splFixture(sourceAmount = "2000000", recipientAccountExists = false)
        val manager = solanaManager()
        val transaction = slot<ByteArray>()
        coEvery {
            manager.signAndSendSolanaTransaction(
                capture(transaction),
                SolanaCluster.DevNet,
                rpc.url()
            )
        } returns SIGNATURE

        val result = provider(manager)
            .sendToken(DEVNET, MINT, RECIPIENT, BigDecimal("1.5"), decimals = 6)

        assertThat(result).isEqualTo(SIGNATURE)
        assertThat(transaction.captured).isNotEmpty()
        // The full preflight chain ran against the RPC before Privy saw the transaction.
        assertThat(rpc.recordedMethods).containsAtLeast(
            "getAccountInfo",
            "getBalance",
            "getLatestBlockhash",
            "simulateTransaction"
        )
    }

    @Test
    fun `sendToken on solana does not broadcast when simulation fails`() {
        splFixture(sourceAmount = "2000000", recipientAccountExists = false, simulationError = "InstructionError")
        val manager = solanaManager()

        assertThrows(RainError.TransactionSimulationFailed::class.java) {
            runBlocking {
                provider(manager).sendToken(DEVNET, MINT, RECIPIENT, BigDecimal("1.5"), decimals = 6)
            }
        }
        coVerify(exactly = 0) { manager.signAndSendSolanaTransaction(any(), any(), any()) }
    }

    @Test
    fun `sendToken on solana fails typed on insufficient token balance`() {
        splFixture(sourceAmount = "100", recipientAccountExists = true)
        val manager = solanaManager()

        assertThrows(RainError.InsufficientTokenBalance::class.java) {
            runBlocking {
                provider(manager).sendToken(DEVNET, MINT, RECIPIENT, BigDecimal("1.5"), decimals = 6)
            }
        }
        coVerify(exactly = 0) { manager.signAndSendSolanaTransaction(any(), any(), any()) }
    }

    // ---------- core-composed transactions ----------

    @Test
    fun `sendSolanaTransaction signs and broadcasts a core-composed transaction through privy`(): Unit =
        runBlocking {
            // Compose a real transaction through core (as RainSdkManager.withdrawCollateral does),
            // then hand it to the port hook and pin what reaches Privy.
            rpc.stub("getLatestBlockhash", envelope(JSONObject().put("blockhash", BLOCKHASH)))
            val unsigned = SolanaSupport(mapOf(DEVNET to rpc.url()))
                .composeNativeTransfer(DEVNET, SENDER, RECIPIENT, BigDecimal("0.5"))

            val manager = solanaManager()
            val transaction = slot<ByteArray>()
            coEvery {
                manager.signAndSendSolanaTransaction(
                    capture(transaction),
                    SolanaCluster.DevNet,
                    rpc.url()
                )
            } returns SIGNATURE

            val result = provider(manager).sendSolanaTransaction(DEVNET, unsigned)

            assertThat(result).isEqualTo(SIGNATURE)
            assertThat(transaction.captured).isEqualTo(unsigned.transaction)
        }

    @Test
    fun `sendSolanaTransaction rejects an unconfigured cluster before signing`() {
        val manager = solanaManager()
        val unsigned = runBlocking {
            rpc.stub("getLatestBlockhash", envelope(JSONObject().put("blockhash", BLOCKHASH)))
            SolanaSupport(mapOf(DEVNET to rpc.url()))
                .composeNativeTransfer(DEVNET, SENDER, RECIPIENT, BigDecimal.ONE)
        }
        val wallet = PrivyWalletProvider(
            manager,
            emptyMap(),
            mockk(),
            rpcClient = mockk(),
            solanaSupport = SolanaSupport(emptyMap()),
        )

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { wallet.sendSolanaTransaction(DEVNET, unsigned) }
        }
        coVerify(exactly = 0) { manager.signAndSendSolanaTransaction(any(), any(), any()) }
    }

    // ---------- EVM-only guards ----------

    @Test
    fun `evm-only entry points reject a solana chainId`() {
        val wallet = provider(solanaManager())

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { wallet.signTypedData(DEVNET, SENDER, "{}") }
        }
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { wallet.sendTransaction(DEVNET, SENDER, RECIPIENT, "0x", "0x0") }
        }
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { wallet.estimateTransactionFee(DEVNET, SENDER, RECIPIENT, "0x", "0x0") }
        }
    }

    // ---------- balances ----------

    @Test
    fun `getBalance native on solana reads lamports with SOL metadata`(): Unit = runBlocking {
        rpc.stub("getBalance", envelope(2_500_000_000L))

        val balance = provider(solanaManager()).getBalance(DEVNET, Token.Native)

        assertThat(balance.rawAmount).isEqualTo(BigInteger.valueOf(2_500_000_000L))
        assertThat(balance.decimals).isEqualTo(9)
        assertThat(balance.symbol).isEqualTo("SOL")
    }

    @Test
    fun `getBalance for a registered mint carries its symbol`(): Unit = runBlocking {
        rpc.stubFor("getAccountInfo", MINT, envelope(mintValue(decimals = 6)))
        // The derived source token account, read after the mint.
        rpc.stubQueue("getAccountInfo", envelope(tokenAccountValue("20000000", decimals = 6)))

        val balance = provider(
            solanaManager(),
            devnetTokens = listOf(TokenInfo(DEVNET, MINT, "USDC", 6, "USD Coin"))
        ).getBalance(DEVNET, Token.Contract(MINT))

        assertThat(balance.symbol).isEqualTo("USDC")
        assertThat(balance.rawAmount).isEqualTo(BigInteger.valueOf(20_000_000L))
    }

    @Test
    fun `getBalances on solana discovers SPL holdings from chain`(): Unit = runBlocking {
        rpc.stub("getBalance", envelope(1_000_000_000L))
        rpc.stubWhenBodyContains(
            "getTokenAccountsByOwner",
            TOKEN_PROGRAM,
            envelope(JSONArray().put(tokenAccountEntry(amount = "20000000", decimals = 6)))
        )
        rpc.stubWhenBodyContains(
            "getTokenAccountsByOwner",
            TOKEN_2022_PROGRAM,
            envelope(JSONArray())
        )

        val balances = provider(solanaManager()).getBalances(DEVNET)

        assertThat(balances).hasSize(2)
        assertThat(balances.first().token).isEqualTo(Token.Native)
        assertThat((balances.last().token as Token.Contract).address).isEqualTo(MINT)
        assertThat(balances.last().rawAmount).isEqualTo(BigInteger.valueOf(20_000_000L))
    }

    // ---------- history ----------

    @Test
    fun `getTransactions on solana devnet returns empty without touching the indexer`(): Unit =
        runBlocking {
            val manager = solanaManager()

            val result = provider(manager).getTransactions(DEVNET, limit = 10, offset = null, order = null)

            assertThat(result).isEmpty()
            coVerify(exactly = 0) { manager.getSolanaTransactions(any()) }
        }

    @Test
    fun `getTransactions on solana mainnet queries the indexer for sol and registered mints`(): Unit =
        runBlocking {
            val manager = solanaManager()
            val params = mutableListOf<GetTransactionsParams<TransactionChain.Solana>>()
            coEvery { manager.getSolanaTransactions(capture(params)) } answers {
                TransactionsPage(
                    transactions = listOf(solanaTransaction(asset = "sol"), solanaTransaction(hash = "sig2", asset = MINT)),
                    nextCursor = null,
                )
            }

            val result = provider(manager, mainnetTokens = listOf(TokenInfo(MAINNET, MINT, "USDC", 6, "USDC")))
                .getTransactions(MAINNET, limit = 10, offset = null, order = null)

            // One query for native SOL, one for the registered-mint chunk.
            assertThat(params).hasSize(2)
            assertThat(params[0].chain).isEqualTo(TransactionChain.Solana.Mainnet)
            assertThat(params[0].assets).containsExactly("sol")
            assertThat(params[1].tokens).containsExactly(MINT)

            // Asset routing: "sol" is a symbol, a base58 mint is a token address.
            val bySymbol = result.associateBy { it.asset }
            assertThat(bySymbol["sol"]?.tokenAddress).isNull()
            assertThat(bySymbol[null]?.tokenAddress).isEqualTo(MINT)
        }

    // ---------- fixtures ----------

    private fun solanaManager(): PrivyManager = mockk<PrivyManager>().also {
        coEvery { it.getSolanaAddress() } returns SENDER
    }

    private fun provider(
        manager: PrivyManager,
        mainnetTokens: List<TokenInfo> = emptyList(),
        devnetTokens: List<TokenInfo> = emptyList(),
    ): PrivyWalletProvider {
        val endpoints = mapOf(DEVNET to rpc.url(), MAINNET to rpc.url())
        val tokenStore = mockk<TokenMetadataStore>()
        coEvery { tokenStore.registeredTokens(DEVNET) } returns devnetTokens
        coEvery { tokenStore.registeredTokens(MAINNET) } returns mainnetTokens
        return PrivyWalletProvider(
            manager,
            endpoints,
            tokenStore,
            rpcClient = mockk(),
            solanaSupport = SolanaSupport(endpoints),
        )
    }

    /**
     * Stubs the full SPL preflight chain: mint account, recipient wallet, source token account
     * (FIFO), destination token account (FIFO), fee balance, blockhash, and simulation.
     */
    private fun splFixture(
        sourceAmount: String,
        recipientAccountExists: Boolean,
        simulationError: String? = null,
    ) {
        rpc.stubFor("getAccountInfo", MINT, envelope(mintValue(decimals = 6)))
        rpc.stubFor("getAccountInfo", RECIPIENT, envelope(JSONObject.NULL))
        // The source and destination token-account addresses are derived inside core, so they are
        // unknown here; the composer reads them in order, so a FIFO queue stands in.
        rpc.stubQueue(
            "getAccountInfo",
            envelope(tokenAccountValue(sourceAmount, decimals = 6)),
            envelope(if (recipientAccountExists) tokenAccountValue("0", decimals = 6) else JSONObject.NULL),
        )
        rpc.stub("getBalance", envelope(10_000_000L))
        rpc.stub("getMinimumBalanceForRentExemption", 1_488_440L)
        rpc.stub("getLatestBlockhash", envelope(JSONObject().put("blockhash", BLOCKHASH)))
        rpc.stub(
            "simulateTransaction",
            envelope(
                JSONObject()
                    .put("err", simulationError ?: JSONObject.NULL)
                    .put("logs", JSONArray())
            )
        )
    }

    private fun envelope(value: Any): JSONObject =
        JSONObject().put("context", JSONObject().put("slot", 1)).put("value", value)

    private fun mintValue(decimals: Int): JSONObject = JSONObject()
        .put("owner", TOKEN_PROGRAM)
        .put(
            "data",
            JSONObject().put("program", "spl-token").put(
                "parsed",
                JSONObject().put("type", "mint").put("info", JSONObject().put("decimals", decimals))
            )
        )

    private fun tokenAccountValue(amount: String, decimals: Int): JSONObject = JSONObject()
        .put("owner", TOKEN_PROGRAM)
        .put(
            "data",
            JSONObject().put("program", "spl-token").put(
                "parsed",
                JSONObject().put("type", "account").put(
                    "info",
                    JSONObject()
                        .put("mint", MINT)
                        .put("owner", SENDER)
                        .put(
                            "tokenAmount",
                            JSONObject().put("amount", amount).put("decimals", decimals)
                        )
                )
            )
        )

    private fun tokenAccountEntry(amount: String, decimals: Int): JSONObject = JSONObject()
        .put("pubkey", SENDER)
        .put("account", tokenAccountValue(amount, decimals))

    private fun solanaTransaction(hash: String = "sig1", asset: String) = io.privy.wallet.transactions.Transaction(
        caip2 = "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp",
        transactionHash = hash,
        userOperationHash = null,
        status = TransactionStatus.Confirmed,
        createdAt = 1_700_000_000_000,
        sponsored = false,
        privyTransactionId = null,
        walletId = "wallet-id",
        details = TransactionDetails(
            type = TransactionType.TransferSent,
            sender = SENDER,
            senderPrivyUserId = null,
            recipient = RECIPIENT,
            recipientPrivyUserId = null,
            chain = "solana",
            asset = asset,
            rawValue = "1000000",
            rawValueDecimals = 6,
            displayValues = emptyMap(),
        ),
    )

    private companion object {
        const val DEVNET = RainChain.SOLANA_DEVNET
        const val MAINNET = RainChain.SOLANA_MAINNET
        const val SENDER = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        const val RECIPIENT = "So11111111111111111111111111111111111111112"
        const val MINT = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"

        /** Valid 32-byte base58 stand-in. */
        const val BLOCKHASH = "H9CfPZKZBBnpFQEwCu5F3Fkm1D9Wj5oL8UnZKPKmvRZa"
        const val SIGNATURE = "2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAB7jTLd5p3Kq"

        // Canonical program ids (core's constants are module-internal).
        const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    }
}

/**
 * Minimal JSON-RPC [MockWebServer] wrapper for the Solana reads core runs during composition.
 * Stubs resolve in priority order: exact first-param match, body substring match, FIFO queue,
 * then the method-wide default.
 */
internal class SolanaRpcFixture {
    private val server = MockWebServer()
    private val methodStubs = mutableMapOf<String, Any>()
    private val paramStubs = mutableMapOf<Pair<String, String>, Any>()
    private val bodyStubs = mutableMapOf<Pair<String, String>, Any>()
    private val queues = mutableMapOf<String, ArrayDeque<Any>>()
    private val recorded = mutableListOf<String>()

    fun start() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val json = runCatching { JSONObject(body) }.getOrNull()
                val method = json?.optString("method", "").orEmpty()
                synchronized(recorded) { recorded += method }

                val firstParam = json?.optJSONArray("params")?.optString(0, "").orEmpty()
                val result = paramStubs[method to firstParam]
                    ?: bodyStubs.entries
                        .firstOrNull { (key, _) -> key.first == method && body.contains(key.second) }
                        ?.value
                    ?: synchronized(queues) { queues[method]?.removeFirstOrNull() }
                    ?: methodStubs[method]
                    ?: return MockResponse().setResponseCode(404).setBody(
                        """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"unstubbed method $method"}}"""
                    )

                val payload = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 1)
                    .put("result", result)
                return MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(payload.toString())
            }
        }
    }

    fun shutdown() = server.shutdown()

    fun url(): String = server.url("/").toString()

    fun stub(method: String, result: Any) {
        methodStubs[method] = result
    }

    fun stubFor(method: String, firstParam: String, result: Any) {
        paramStubs[method to firstParam] = result
    }

    fun stubWhenBodyContains(method: String, bodyContains: String, result: Any) {
        bodyStubs[method to bodyContains] = result
    }

    fun stubQueue(method: String, vararg results: Any) {
        synchronized(queues) { queues.getOrPut(method) { ArrayDeque() }.addAll(results) }
    }

    val recordedMethods: List<String>
        get() = synchronized(recorded) { recorded.toList() }
}
