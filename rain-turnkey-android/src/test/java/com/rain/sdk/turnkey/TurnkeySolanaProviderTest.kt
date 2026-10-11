package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.constants.SolanaPrograms
import com.rain.sdk.internal.network.chainreader.SolanaChainReader
import com.rain.sdk.internal.solana.Base58
import com.rain.sdk.internal.solana.SolanaAddresses
import com.rain.sdk.internal.solana.SolanaInstructions
import com.rain.sdk.internal.solana.SolanaLamportPreflight
import com.rain.sdk.internal.solana.SolanaTransactionBuilder
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransactionCategory
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import com.rain.sdk.models.UnsignedSolanaTransfer
import com.turnkey.http.utils.TurnkeyHttpError
import com.turnkey.types.TSolSendTransactionBody
import com.turnkey.types.V1Activity
import com.turnkey.types.V1AssetBalance
import com.turnkey.types.V1SolanaFailureDetails
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Solana behaviour of [TurnkeyWalletProvider]: chain-aware address resolution, balance reads
 * sourced from Turnkey (with an RPC fallback for native SOL), and the native SOL send path
 * through Turnkey's `sol_send_transaction`.
 */
class TurnkeySolanaProviderTest {

    private lateinit var rpc: MockRpcServer
    private val devnet = RainChain.SOLANA_DEVNET
    private val devnetCaip2 = "solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1"

    @Before
    fun setUp() {
        // Skips on pre-JDK-24 JVMs (Turnkey AAR is Java-24 bytecode) before touching `rpc`.
        assumeJdk24()
        rpc = MockRpcServer().also { it.start() }
    }

    @After
    fun tearDown() {
        if (::rpc.isInitialized) rpc.shutdown()
    }

    /**
     * @param solanaReader pass `null` to exercise the real [SolanaChainReader] against the mock
     *        RPC server — needed by the balance tests, where SPL holdings are discovered on chain.
     */
    private fun makeProvider(
        client: MockTurnkeyClient = MockTurnkeyClient(),
        evmReader: MockChainReader = MockChainReader(),
        solanaReader: MockChainReader? = MockChainReader(),
        sponsorGas: Boolean = false,
        turnkey: MockTurnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), turnkeyClient = client)
    ): TurnkeyWalletProvider {
        return turnkeyWalletProvider(
            turnkey = turnkey,
            rpcEndpoints = mapOf(devnet to rpc.urlFor(devnet)),
            httpClient = OkHttpClient(),
            chainReader = evmReader,
            solanaChainReader = solanaReader,
            pollingIntervalMs = 0L,
            sponsorGas = sponsorGas
        )
    }

    @Test
    fun `getAddress is chain-aware - solana chain returns the solana account`() = runBlocking {
        val provider = makeProvider()

        assertThat(provider.getWalletAddress()).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        assertThat(provider.getWalletAddress(devnet)).isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
    }

    @Test
    fun `getBalance native on solana reads from Turnkey with the devnet caip2`() = runBlocking {
        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "2500000000", // 2.5 SOL in lamports
                    caip19 = "$devnetCaip2/slip44:501",
                    decimals = 9L,
                    display = null,
                    name = "Solana",
                    symbol = "SOL"
                )
            )
        )
        val solanaReader = MockChainReader()
        val provider = makeProvider(client = client, solanaReader = solanaReader)

        val balance = provider.getBalance(devnet, Token.Native)

        assertThat(balance.symbol).isEqualTo("SOL")
        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-9).of(2.5)
        assertThat(client.walletAddressBalanceCalls).hasSize(1)
        assertThat(client.walletAddressBalanceCalls.single().caip2).isEqualTo(devnetCaip2)
        assertThat(client.walletAddressBalanceCalls.single().address)
            .isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        // The RPC reader is untouched when Turnkey succeeds.
        assertThat(solanaReader.balanceCalls).isEmpty()
    }

    @Test
    fun `getBalance native on solana falls back to the RPC reader when Turnkey fails`() = runBlocking {
        val client = MockTurnkeyClient().apply {
            walletAddressBalancesError = RuntimeException("turnkey unavailable")
        }
        val solanaReader = MockChainReader(
            balance = Balance(Token.Native, devnet, BigInteger.valueOf(1_000_000_000L), 9, "SOL", "Solana")
        )
        val provider = makeProvider(client = client, solanaReader = solanaReader)

        val balance = provider.getBalance(devnet, Token.Native)

        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-9).of(1.0)
        assertThat(solanaReader.balanceCalls).hasSize(1)
        assertThat(solanaReader.balanceCalls.single().walletAddress)
            .isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
    }

    @Test
    fun `getBalance native on solana shows zero when Turnkey omits the asset`() = runBlocking {
        // Turnkey returns only non-zero balances; an empty list means a 0-SOL wallet, not a failure.
        val client = MockTurnkeyClient(mockBalances = emptyList())
        val solanaReader = MockChainReader()
        val provider = makeProvider(client = client, solanaReader = solanaReader)

        val balance = provider.getBalance(devnet, Token.Native)

        assertThat(balance.symbol).isEqualTo("SOL")
        assertThat(balance.rawAmount).isEqualTo(BigInteger.ZERO)
        assertThat(solanaReader.balanceCalls).isEmpty()
    }

    @Test
    fun `getBalance for an spl mint Turnkey lists uses Turnkey's amount and metadata`() = runBlocking {
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "100500000",
                    caip19 = "$devnetCaip2/token:$mint",
                    decimals = 6L,
                    display = null,
                    name = "USD Coin",
                    symbol = "USDC"
                )
            )
        )
        val solanaReader = MockChainReader()
        val provider = makeProvider(client = client, solanaReader = solanaReader)

        val balance = provider.getBalance(devnet, Token.Contract(mint))

        assertThat(balance.rawAmount).isEqualTo(BigInteger.valueOf(100_500_000L))
        assertThat(balance.decimals).isEqualTo(6)
        assertThat(balance.symbol).isEqualTo("USDC")
        assertThat(balance.name).isEqualTo("USD Coin")
        // The node is never consulted when Turnkey lists the mint.
        assertThat(solanaReader.balanceCalls).isEmpty()
    }

    @Test
    fun `getBalance for an spl mint Turnkey does not list falls back to the node`() = runBlocking {
        // Turnkey omits zero balances, and on a cluster it does not index every mint looks like
        // a zero — so a missing entry is re-read from the node.
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val client = MockTurnkeyClient(mockBalances = emptyList())
        val solanaReader = MockChainReader(
            balance = Balance(Token.Contract(mint), devnet, BigInteger.valueOf(2_500_000L), 6)
        )
        val provider = makeProvider(client = client, solanaReader = solanaReader)

        val balance = provider.getBalance(devnet, Token.Contract(mint))

        assertThat(balance.rawAmount).isEqualTo(BigInteger.valueOf(2_500_000L))
        assertThat(solanaReader.balanceCalls).hasSize(1)
    }

    @Test
    fun `getBalance for an spl mint falls back to the node when Turnkey fails`() = runBlocking {
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val client = MockTurnkeyClient().apply {
            walletAddressBalancesError = RuntimeException("turnkey unavailable")
        }
        val solanaReader = MockChainReader(
            balance = Balance(Token.Contract(mint), devnet, BigInteger.valueOf(2_500_000L), 6)
        )
        val provider = makeProvider(client = client, solanaReader = solanaReader)

        val balance = provider.getBalance(devnet, Token.Contract(mint))

        assertThat(balance.rawAmount).isEqualTo(BigInteger.valueOf(2_500_000L))
        assertThat(solanaReader.balanceCalls).hasSize(1)
    }

    @Test
    fun `getBalances on solana uses Turnkey's SPL list when it indexes the cluster`() = runBlocking {
        val usdcMint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT // valid 32-byte base58 stand-in for a mint
        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "2500000000", // 2.5 SOL
                    caip19 = "$devnetCaip2/slip44:501",
                    decimals = 9L,
                    display = null,
                    name = "Solana",
                    symbol = "SOL"
                ),
                V1AssetBalance(
                    balance = "100500000",
                    caip19 = "$devnetCaip2/token:$usdcMint",
                    decimals = 6L,
                    display = null,
                    name = "USD Coin",
                    symbol = "USDC"
                )
            )
        )
        // No RPC stubs installed: the node is never consulted when Turnkey lists SPL assets.
        val provider = makeProvider(client = client, solanaReader = null)

        val balances = provider.getBalances(devnet)

        assertThat(balances).hasSize(2)
        val sol = balances.single { it.token is Token.Native }
        assertThat(sol.decimalAmount.toDouble()).isWithin(1e-9).of(2.5)

        val usdc = balances.single { it.token == Token.Contract(usdcMint) }
        // Amount and naming both from Turnkey's asset entry.
        assertThat(usdc.rawAmount).isEqualTo(BigInteger.valueOf(100_500_000L))
        assertThat(usdc.symbol).isEqualTo("USDC")
        assertThat(usdc.name).isEqualTo("USD Coin")
        assertThat(client.walletAddressBalanceCalls.single().caip2).isEqualTo(devnetCaip2)
    }

    @Test
    fun `getBalances on solana still lists SPL tokens Turnkey does not index`() = runBlocking {
        // Devnet mints are absent from Turnkey's asset list. Because it also omits zero balances,
        // an SPL-less answer is treated as "not indexed" and the list is read from the node.
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "5000000000",
                    caip19 = "$devnetCaip2/slip44:501",
                    decimals = 9L,
                    display = null,
                    name = "Solana",
                    symbol = "SOL"
                )
            )
        )
        rpc.stubObject("getBalance", balanceResult(5_000_000_000L))
        stubDiscoveredTokenAccounts(mint = mint, amount = "20000000", decimals = 6)
        val provider = makeProvider(client = client, solanaReader = null)

        val balances = provider.getBalances(devnet)

        val token = balances.single { it.token == Token.Contract(mint) }
        assertThat(token.decimalAmount.toDouble()).isWithin(1e-6).of(20.0)
        assertThat(token.decimals).isEqualTo(6)
        // Unindexed by Turnkey, so it has no symbol — but the balance is still correct.
        assertThat(token.symbol).isNull()
    }

    @Test
    fun `getBalances on solana names a mint from host-registered tokens`(): Unit = runBlocking {
        // Neither the chain nor Turnkey can name an SPL mint on devnet, so `registerTokens` is
        // how a caller labels it — otherwise the balance shows only a mint address.
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val store = TokenMetadataStore(MockChainReader())
        store.register(listOf(TokenInfo(devnet, mint, "USDC", 6, "USD Coin")))

        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "5000000000",
                    caip19 = "$devnetCaip2/slip44:501",
                    decimals = 9L,
                    display = null,
                    name = "Solana",
                    symbol = "SOL"
                )
            )
        )
        rpc.stubObject("getBalance", balanceResult(5_000_000_000L))
        stubDiscoveredTokenAccounts(mint = mint, amount = "20000000", decimals = 6)
        val provider = turnkeyWalletProvider(
            turnkey = MockTurnkey(
                wallets = listOf(MockTurnkey.walletWithEthAndSolana()),
                turnkeyClient = client
            ),
            rpcEndpoints = mapOf(devnet to rpc.urlFor(devnet)),
            httpClient = OkHttpClient(),
            chainReader = MockChainReader(),
            solanaChainReader = null,
            tokenStore = store
        )

        val token = provider.getBalances(devnet).single { it.token == Token.Contract(mint) }

        assertThat(token.symbol).isEqualTo("USDC")
        assertThat(token.name).isEqualTo("USD Coin")
        // The amount still comes from the chain, not from the registration.
        assertThat(token.decimalAmount.toDouble()).isWithin(1e-6).of(20.0)
    }

    @Test
    fun `getBalances on solana keeps the native balance when token discovery fails`() = runBlocking {
        // Turnkey lists no SPL assets, so the list is read from the node; discovery is
        // per-program tolerant, so a failed enumeration still yields the native balance.
        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "5000000000",
                    caip19 = "$devnetCaip2/slip44:501",
                    decimals = 9L,
                    display = null,
                    name = "Solana",
                    symbol = "SOL"
                )
            )
        )
        rpc.stubObject("getBalance", balanceResult(5_000_000_000L))
        // getTokenAccountsByOwner left unstubbed -> the RPC call fails.
        val provider = makeProvider(client = client, solanaReader = null)

        val balances = provider.getBalances(devnet)

        assertThat(balances).hasSize(1)
        assertThat(balances.single().decimalAmount.toDouble()).isWithin(1e-9).of(5.0)
    }

    private fun balanceResult(lamports: Long): JSONObject =
        JSONObject()
            .put("context", JSONObject().put("slot", 1))
            .put("value", lamports)

    /** Stubs `getTokenAccountsByOwner` so the real Solana reader discovers one holding. */
    private fun stubDiscoveredTokenAccounts(mint: String, amount: String, decimals: Int) {
        val entry = JSONObject()
            .put("pubkey", MockTurnkey.DEFAULT_SOLANA_ADDRESS)
            .put(
                "account",
                JSONObject().put("owner", SolanaPrograms.TOKEN_ADDRESS).put(
                    "data",
                    JSONObject().put("program", "spl-token").put(
                        "parsed",
                        JSONObject().put("type", "account").put(
                            "info",
                            JSONObject()
                                .put("mint", mint)
                                .put("owner", MockTurnkey.DEFAULT_SOLANA_ADDRESS)
                                .put(
                                    "tokenAmount",
                                    JSONObject().put("amount", amount).put("decimals", decimals)
                                )
                        )
                    )
                )
            )
        rpc.stubObjectWhenBodyContains(
            "getTokenAccountsByOwner",
            SolanaPrograms.TOKEN_ADDRESS,
            JSONObject().put("context", JSONObject().put("slot", 1))
                .put("value", JSONArray().put(entry))
        )
        rpc.stubObjectWhenBodyContains(
            "getTokenAccountsByOwner",
            SolanaPrograms.TOKEN_2022_ADDRESS,
            JSONObject().put("context", JSONObject().put("slot", 1)).put("value", JSONArray())
        )
    }

    @Test
    fun `sendNativeToken on solana submits an unsigned transfer and returns the chain signature`() = runBlocking {
        val blockhash = MockTurnkey.DEFAULT_SOLANA_ADDRESS // a valid 32-byte base58 stand-in
        val priorSignature = "5oldSigFromAnEarlierTransfer11111111111111111111111111111111"
        val signature = "2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAB7jTLd5p3KqJ7xQy9bniaP4q1hk2N1nF"
        rpc.stubObject(
            "getLatestBlockhash",
            JSONObject()
                .put("context", JSONObject().put("slot", 1))
                .put("value", JSONObject().put("blockhash", blockhash).put("lastValidBlockHeight", 150))
        )
        // Pre-send baseline sees an older signature; after the send the new one is newest.
        rpc.stubObjectSequence(
            "getSignaturesForAddress",
            JSONArray().put(JSONObject().put("signature", priorSignature).put("slot", 140)),
            JSONArray().put(JSONObject().put("signature", signature).put("slot", 150))
        )
        stubTransaction(signature, feePayer = MockTurnkey.DEFAULT_SOLANA_ADDRESS)

        // Included with no hash in the status -> we recover the signature from chain (RPC).
        val client = MockTurnkeyClient().apply {
            sendTransactionStatusQueue = mutableListOf(
                MockTurnkeyClient.StatusFixture(txHash = null, txStatus = "TX_STATUS_INCLUDED")
            )
        }
        val provider = makeProvider(client = client)

        val result = provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5"))

        assertThat(result).isEqualTo(signature)
        // Recovery scans only what landed after the baseline, then verifies the candidate.
        val recoveryBody = rpc.recordedBodies.last { it.contains("getSignaturesForAddress") }
        assertThat(recoveryBody).contains("\"until\":\"$priorSignature\"")
        assertThat(rpc.recordedMethods).contains("getTransaction")
        assertThat(client.ethSendTransactionCalls).isEmpty()
        assertThat(client.solSendTransactionCalls).hasSize(1)
        val body = client.solSendTransactionCalls.single()
        assertThat(body.signWiths).containsExactly(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        assertThat(body.caip2).isEqualTo(devnetCaip2)
        assertThat(body.recentBlockhash).isEqualTo(blockhash)
        assertThat(body.sponsor).isEqualTo(false)
        assertThat(body.unsignedTransaction).isNotEmpty()
    }

    /**
     * Turnkey accepted the transfer before the status read failed — for any reason, not just
     * session expiry — so polling stops but the send did not: the signature is recovered from
     * chain exactly as when the status carries no hash.
     */
    @Test
    fun `sendNativeToken on solana recovers the signature when the status read fails outright`() = runBlocking {
        val priorSignature = "5oldSigFromAnEarlierTransfer11111111111111111111111111111111"
        val signature = "2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAB7jTLd5p3KqJ7xQy9bniaP4q1hk2N1nF"
        stubBlockhash()
        rpc.stubObjectSequence(
            "getSignaturesForAddress",
            JSONArray().put(JSONObject().put("signature", priorSignature).put("slot", 140)),
            JSONArray().put(JSONObject().put("signature", signature).put("slot", 150))
        )
        stubTransaction(signature, feePayer = MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        val client = MockTurnkeyClient().apply {
            sendTransactionStatusError = IllegalStateException("status service unavailable")
        }
        val provider = makeProvider(client = client)

        val result = provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5"))

        assertThat(result).isEqualTo(signature)
        assertThat(client.solSendTransactionCalls).hasSize(1)
    }

    @Test
    fun `sendNativeToken on solana reports pending when no newer signature appears`() {
        val staleSignature = "5oldSigFromAnEarlierTransfer11111111111111111111111111111111"
        stubBlockhash()
        // The wallet's newest signature never changes across the send: whatever was broadcast
        // has not landed, so neither the pre-send signature nor the status id may be reported
        // as this transfer's hash.
        rpc.stubObject(
            "getSignaturesForAddress",
            JSONArray().put(JSONObject().put("signature", staleSignature).put("slot", 140))
        )
        val provider = makeProvider(client = includedWithoutSignatureClient())

        val ex = assertThrows(RainError.TransactionPending::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.statusId).isEqualTo("sol-send-status-id")
        assertThat(rpc.recordedMethods).doesNotContain("getTransaction")
    }

    @Test
    fun `sendNativeToken on solana skips a newer deposit from someone else and finds its own send`() = runBlocking {
        val priorSignature = "5oldSigFromAnEarlierTransfer11111111111111111111111111111111"
        val ownSignature = "2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAB7jTLd5p3KqJ7xQy9bniaP4q1hk2N1nF"
        val depositSignature = "3depositFromSomeoneElse1111111111111111111111111111111111111"
        stubBlockhash()
        // After the send, a third party's deposit is the wallet's newest signature; the send
        // itself is the one behind it. Newest-first, like the RPC.
        rpc.stubObjectSequence(
            "getSignaturesForAddress",
            JSONArray().put(JSONObject().put("signature", priorSignature).put("slot", 140)),
            JSONArray()
                .put(JSONObject().put("signature", depositSignature).put("slot", 151))
                .put(JSONObject().put("signature", ownSignature).put("slot", 150))
        )
        stubTransaction(depositSignature, feePayer = MockTurnkey.DEFAULT_SOLANA_RECIPIENT)
        stubTransaction(ownSignature, feePayer = MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        val provider = makeProvider(client = includedWithoutSignatureClient())

        val result = provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5"))

        assertThat(result).isEqualTo(ownSignature)
    }

    @Test
    fun `sendNativeToken on solana does not report a deposit from someone else as its own send`() {
        val priorSignature = "5oldSigFromAnEarlierTransfer11111111111111111111111111111111"
        val depositSignature = "3depositFromSomeoneElse1111111111111111111111111111111111111"
        stubBlockhash()
        rpc.stubObjectSequence(
            "getSignaturesForAddress",
            JSONArray().put(JSONObject().put("signature", priorSignature).put("slot", 140)),
            JSONArray().put(JSONObject().put("signature", depositSignature).put("slot", 151))
        )
        // Newer than the baseline, but signed only by another wallet: not this send.
        stubTransaction(depositSignature, feePayer = MockTurnkey.DEFAULT_SOLANA_RECIPIENT)
        val provider = makeProvider(client = includedWithoutSignatureClient())

        val ex = assertThrows(RainError.TransactionPending::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.statusId).isEqualTo("sol-send-status-id")
    }

    @Test
    fun `sendNativeToken on solana recovers a sponsored send that the sponsor fee-paid`() = runBlocking {
        // Under sponsorship Turnkey's key may sit in the payer slot with this wallet as the
        // second signer. Recovery matches on "did this wallet sign it", so the send is found.
        val priorSignature = "5oldSigFromAnEarlierTransfer11111111111111111111111111111111"
        val signature = "4sponsoredSendSignature1111111111111111111111111111111111111"
        val sponsorKey = "SponsorFeePayer1111111111111111111111111111"
        stubBlockhash()
        rpc.stubObjectSequence(
            "getSignaturesForAddress",
            JSONArray().put(JSONObject().put("signature", priorSignature).put("slot", 140)),
            JSONArray().put(JSONObject().put("signature", signature).put("slot", 150))
        )
        stubTransaction(signature, feePayer = sponsorKey, coSigner = MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        val client = includedWithoutSignatureClient()
        val provider = makeProvider(client = client, sponsorGas = true)

        val result = provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5"))

        assertThat(result).isEqualTo(signature)
        assertThat(client.solSendTransactionCalls.single().sponsor).isEqualTo(true)
    }

    @Test
    fun `sendNativeToken on solana does not claim a deposit just because this wallet appears in it`() {
        // A deposit lists this wallet as the recipient account, not as a signer. The signer
        // check must reject it even though the wallet address is present in the account keys.
        val priorSignature = "5oldSigFromAnEarlierTransfer11111111111111111111111111111111"
        val depositSignature = "3depositFromSomeoneElse1111111111111111111111111111111111111"
        stubBlockhash()
        rpc.stubObjectSequence(
            "getSignaturesForAddress",
            JSONArray().put(JSONObject().put("signature", priorSignature).put("slot", 140)),
            JSONArray().put(JSONObject().put("signature", depositSignature).put("slot", 151))
        )
        stubTransaction(
            depositSignature,
            feePayer = MockTurnkey.DEFAULT_SOLANA_RECIPIENT,
            nonSigner = MockTurnkey.DEFAULT_SOLANA_ADDRESS
        )
        val provider = makeProvider(client = includedWithoutSignatureClient())

        val ex = assertThrows(RainError.TransactionPending::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.statusId).isEqualTo("sol-send-status-id")
    }

    @Test
    fun `sendNativeToken on solana surfaces a failed status with details as TransactionSimulationFailed`() {
        // Turnkey decoded the program failure into error.solana: the chain rejected the transaction,
        // so it maps like a failed dry run (a withdrawal turns it into RAIN_405).
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            sendTransactionStatusQueue = mutableListOf(
                MockTurnkeyClient.StatusFixture.solanaRevertedOnChain(message = "custom program error: 0x1")
            )
        }
        val provider = makeProvider(client = client, sponsorGas = true)

        val ex = assertThrows(RainError.TransactionSimulationFailed::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.cause?.message).contains("custom program error")
    }

    @Test
    fun `sendNativeToken on solana classifies an included transaction that failed on chain before reading its signature`() {
        // The signature and the decoded failure arrive on one INCLUDED status. The failure wins and
        // the signature rides on the error; a loop that read the signature first would report a send.
        stubBlockhash()
        val signature = "5" + "7".repeat(86)
        val client = MockTurnkeyClient().apply {
            sendTransactionStatusQueue = mutableListOf(
                MockTurnkeyClient.StatusFixture(
                    solanaSignature = signature,
                    txStatus = "TX_STATUS_INCLUDED",
                    errorMessage = "custom program error: 0x1",
                    solanaFailure = V1SolanaFailureDetails(transactionErrorJson = "{\"InstructionError\":[0,{\"Custom\":1}]}")
                )
            )
        }
        val provider = makeProvider(client = client, sponsorGas = true)

        val ex = assertThrows(RainError.TransactionSimulationFailed::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.transactionId).isEqualTo(signature)
        assertThat(ex.cause?.message).contains(signature)
    }

    @Test
    fun `sendNativeToken on solana surfaces a failed status with only txError as ProviderError`() {
        // A bare txError is a broadcast-or-confirm failure with nothing decoded, so it stays the
        // provider's error rather than a revert.
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            sendTransactionStatusQueue = mutableListOf(
                MockTurnkeyClient.StatusFixture.failed(message = "blockhash not found")
            )
        }
        val provider = makeProvider(client = client, sponsorGas = true)

        val ex = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.cause?.message).contains("blockhash not found")
    }

    @Test
    fun `sendNativeToken on solana does not report its own failed transaction as success`() {
        val priorSignature = "5oldSigFromAnEarlierTransfer11111111111111111111111111111111"
        val failedSignature = "2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAB7jTLd5p3KqJ7xQy9bniaP4q1hk2N1nF"
        stubBlockhash()
        rpc.stubObjectSequence(
            "getSignaturesForAddress",
            JSONArray().put(JSONObject().put("signature", priorSignature).put("slot", 140)),
            JSONArray().put(JSONObject().put("signature", failedSignature).put("slot", 150))
        )
        stubTransaction(
            failedSignature,
            feePayer = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            err = JSONObject().put("InstructionError", JSONArray().put(0).put("InsufficientFunds"))
        )
        val provider = makeProvider(client = includedWithoutSignatureClient())

        val ex = assertThrows(RainError.TransactionPending::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.statusId).isEqualTo("sol-send-status-id")
    }

    @Test
    fun `sendNativeToken on solana skips chain recovery when the baseline read fails`() {
        stubBlockhash()
        // Without a pre-send baseline, nothing distinguishes this send from older history or a
        // stranger's deposit, so the SDK must not guess: pending, with the status id to resume.
        rpc.stubNetworkFailure("getSignaturesForAddress")
        val client = includedWithoutSignatureClient()
        val provider = makeProvider(client = client)

        val ex = assertThrows(RainError.TransactionPending::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.statusId).isEqualTo("sol-send-status-id")
        assertThat(client.solSendTransactionCalls).hasSize(1)
        assertThat(rpc.recordedMethods.count { it == "getSignaturesForAddress" }).isEqualTo(1)
        assertThat(rpc.recordedMethods).doesNotContain("getTransaction")
    }

    @Test
    fun `sendNativeToken on solana recovers the chain signature for a wallet with no history`() = runBlocking {
        val signature = "2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAB7jTLd5p3KqJ7xQy9bniaP4q1hk2N1nF"
        rpc.stubObject(
            "getLatestBlockhash",
            JSONObject()
                .put("context", JSONObject().put("slot", 1))
                .put(
                    "value",
                    JSONObject().put("blockhash", MockTurnkey.DEFAULT_SOLANA_ADDRESS).put("lastValidBlockHeight", 150)
                )
        )
        // No signatures before the send; the transfer's own signature appears after it.
        rpc.stubObjectSequence(
            "getSignaturesForAddress",
            JSONArray(),
            JSONArray().put(JSONObject().put("signature", signature).put("slot", 150))
        )
        stubTransaction(signature, feePayer = MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        val client = MockTurnkeyClient().apply {
            sendTransactionStatusQueue = mutableListOf(
                MockTurnkeyClient.StatusFixture(txHash = null, txStatus = "TX_STATUS_INCLUDED")
            )
        }
        val provider = makeProvider(client = client)

        val result = provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5"))

        assertThat(result).isEqualTo(signature)
    }

    @Test
    fun `sendNativeToken on solana returns the signature from the Turnkey status response`() = runBlocking {
        val turnkeySignature = "TurnkeyProvidedSolanaSig111111111111111111"
        rpc.stubObject(
            "getLatestBlockhash",
            JSONObject()
                .put("context", JSONObject().put("slot", 1))
                .put(
                    "value",
                    JSONObject().put("blockhash", MockTurnkey.DEFAULT_SOLANA_ADDRESS).put("lastValidBlockHeight", 150)
                )
        )
        // Turnkey SDK 2.0 reports the signature in solana.signature once Included -> returned
        // directly, without the RPC fallback.
        val client = MockTurnkeyClient().apply {
            sendTransactionStatusQueue = mutableListOf(
                MockTurnkeyClient.StatusFixture(solanaSignature = turnkeySignature, txStatus = "TX_STATUS_INCLUDED")
            )
        }
        val provider = makeProvider(client = client)

        val result = provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5"))

        assertThat(result).isEqualTo(turnkeySignature)
    }

    /**
     * A raw failure from Turnkey's send is not a [RainError] and not a refusal of the submit, so it may
     * have followed acceptance and lost the activity: the host sees `TransactionOutcomeUnknown`
     * (`RAIN_305`), built around the vendor's message but not its exception, so no layer reads a status
     * off it and retries. Core's withdrawal wrapper sees that `RainError`, not the `InternalError` it
     * puts around anything unmapped. Pins the Solana half of the error boundary that
     * `withdrawCollateral` relies on for its send.
     */
    @Test
    fun `sendSolanaTransaction maps a raw send failure to TransactionOutcomeUnknown before it leaves the adapter`() {
        val raw = RuntimeException("node refused the transaction")
        val client = MockTurnkeyClient().apply { solSendTransactionError = raw }
        val provider = makeProvider(client = client)
        val unsigned = UnsignedSolanaTransfer(
            transaction = ByteArray(8),
            recentBlockhash = MockTurnkey.DEFAULT_SOLANA_ADDRESS
        )

        val ex = assertThrows(RainError.TransactionOutcomeUnknown::class.java) {
            runBlocking { provider.sendSolanaTransaction(devnet, unsigned) }
        }

        assertThat(ex.message).contains("node refused the transaction")
        assertThat(ex.cause).isNull()
        assertThat(client.solSendTransactionCalls).hasSize(1)
        // Nothing is read back: the vendor's client lost the activity, and the activity log is not consulted.
        assertThat(client.getActivitiesCalls).isEmpty()
    }

    /**
     * Turnkey may record a completed send's result under the V1 name. The vendor's client never returns
     * that activity (it insists on the V2 result and throws its typed error carrying the activity), so
     * it arrives through the error; either result shape carries the status id, and the send goes on to
     * the status poll.
     */
    @Test
    fun `sendNativeToken on solana reads the status id from a V1-shaped result`(): Unit = runBlocking {
        stubBlockhash()
        val client = includedStatusClient().apply {
            notCompletedAfterSubmit { input ->
                MockTurnkey.makeSolanaActivity(
                    id = "act-v1-result",
                    signWith = input.signWiths.single(),
                    caip2 = input.caip2,
                    unsignedTransaction = input.unsignedTransaction,
                    sendTransactionStatusId = "status-from-v1-result",
                    shape = MockTurnkey.SolanaSendShape.V2,
                    resultShape = MockTurnkey.SolanaSendShape.V1
                )
            }
        }
        val provider = makeProvider(client = client)

        val result = provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5"))

        assertThat(result).isEqualTo(SIGNATURE)
        assertThat(client.sendTransactionStatusCalls.single().sendTransactionStatusId).isEqualTo("status-from-v1-result")
    }

    /**
     * The vendor's client throws `TurnkeyHttpError.ActivityNotCompleted` carrying the activity when it is
     * not completed after its poll (since `com.turnkey:http` 2.2.0), and the send takes the activity out
     * of the error. The hook records the activity Turnkey holds, so `getActivity` finds it, then throws
     * the way the vendor does.
     */
    private fun MockTurnkeyClient.notCompletedAfterSubmit(build: (TSolSendTransactionBody) -> V1Activity) {
        solSendActivity = { input ->
            val activity = build(input)
            mockActivities = mockActivities + activity
            throw TurnkeyHttpError.ActivityNotCompleted(activity, "/public/v1/submit/sol_send_transaction")
        }
    }

    private fun pendingActivity(
        input: TSolSendTransactionBody,
        id: String,
        createdAtSeconds: String = "1714521600",
        unsignedTransaction: String = input.unsignedTransaction
    ) =
        MockTurnkey.makeSolanaActivity(
            id = id,
            signWith = input.signWiths.single(),
            caip2 = input.caip2,
            unsignedTransaction = unsignedTransaction,
            sendTransactionStatusId = null,
            createdAtSeconds = createdAtSeconds,
            shape = MockTurnkey.SolanaSendShape.V2,
            status = com.turnkey.types.V1ActivityStatus.ACTIVITY_STATUS_PENDING
        )

    /**
     * Turnkey completed the activity, but its result carries no status id this build can read, so the
     * vendor's client throws instead of returning it. Taken from the error, the send may well have gone
     * out: this is not a failure a host may retry but pending on the activity id, and nothing is polled with a
     * status id that does not exist.
     */
    @Test
    fun `sendNativeToken on solana ends pending when the completed activity carries no status id`() {
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            notCompletedAfterSubmit { input ->
                MockTurnkey.makeSolanaActivity(
                    id = "act-without-result",
                    signWith = input.signWiths.single(),
                    caip2 = input.caip2,
                    unsignedTransaction = input.unsignedTransaction,
                    sendTransactionStatusId = null,
                    shape = MockTurnkey.SolanaSendShape.V2
                )
            }
        }
        val provider = makeProvider(client = client)

        val ex = assertThrows(RainError.TransactionPending::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.statusId).isEqualTo("act-without-result")
        assertThat(client.solSendTransactionCalls).hasSize(1)
        assertThat(client.sendTransactionStatusCalls).isEmpty()
        // The activity came with the vendor's error: nothing is listed, and a completed activity is not read again.
        assertThat(client.getActivitiesCalls).isEmpty()
        assertThat(client.getActivityCalls).isEmpty()
        // Only the pre-send baseline read the chain; no signature recovery ran for a send with no status id.
        assertThat(rpc.recordedMethods.count { it == "getSignaturesForAddress" }).isEqualTo(1)
        assertThat(rpc.recordedMethods).doesNotContain("getTransaction")
        // The one public place the id can be matched, as the error's KDoc says: the activity-log row's uniqueId.
        val row = runBlocking { provider.getTransactions(devnet, limit = 10) }.single()
        assertThat(row.uniqueId).isEqualTo(ex.statusId)
        assertThat(row.hash).isEqualTo(ex.statusId)
    }

    /** A row whose blob does not decode falls back to the intent's signer as the sender, under either shape. */
    @Test
    fun `getTransactions on solana falls back to the intent's signer when the blob does not decode`(): Unit = runBlocking {
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "opaque-v1",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = "zz",
                    sendTransactionStatusId = "status-opaque-v1",
                    createdAtSeconds = "1714521700"
                ),
                MockTurnkey.makeSolanaActivity(
                    id = "opaque-v2",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = "zz",
                    sendTransactionStatusId = "status-opaque-v2",
                    createdAtSeconds = "1714521600",
                    shape = MockTurnkey.SolanaSendShape.V2,
                    coSignerFirst = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
                )
            )
        )
        val provider = makeProvider(client = client)

        val rows = provider.getTransactions(devnet, limit = 10, order = RainTransactionOrder.DESC)

        assertThat(rows.map { it.hash }).containsExactly("status-opaque-v1", "status-opaque-v2").inOrder()
        assertThat(rows[0].from).isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        // The V2 shape's first signer is the co-signer; with no decoded transfer, that is what the row shows.
        assertThat(rows[1].from).isEqualTo("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM")
        assertThat(rows.map { it.to }).containsExactly(null, null)
        assertThat(rows.map { it.value }).containsExactly(null, null)
    }

    /** Still pending after the vendor's poll and the SDK's own wait: pending on the activity id, not a failure. */
    @Test
    fun `sendNativeToken on solana ends pending when the activity has not settled within the SDK's own wait`() {
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            notCompletedAfterSubmit { input ->
                MockTurnkey.makeSolanaActivity(
                    id = "act-still-pending",
                    signWith = input.signWiths.single(),
                    caip2 = input.caip2,
                    unsignedTransaction = input.unsignedTransaction,
                    sendTransactionStatusId = null,
                    shape = MockTurnkey.SolanaSendShape.V2,
                    status = com.turnkey.types.V1ActivityStatus.ACTIVITY_STATUS_PENDING
                )
            }
        }
        val provider = makeProvider(client = client)

        val ex = assertThrows(RainError.TransactionPending::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.statusId).isEqualTo("act-still-pending")
        assertThat(client.sendTransactionStatusCalls).isEmpty()
        // Read again by id at the SDK's own interval until the budget is spent, then pending on the activity id.
        assertThat(client.getActivityCalls).hasSize(TurnkeyManager.SEND_ACTIVITY_POLL_ATTEMPTS)
        assertThat(client.getActivityCalls.map { it.activityId }.distinct()).containsExactly("act-still-pending")
    }

    /**
     * An activity Turnkey failed before it broadcast anything (a policy denial, a transaction it
     * could not sign) has no status id and moved no money, so it is the one failure after
     * acceptance the host may retry. The vendor's client throws its typed error carrying the activity,
     * and Turnkey's reason travels with the error.
     */
    @Test
    fun `sendNativeToken on solana reports a failed activity as a provider failure with the reason`() {
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            notCompletedAfterSubmit { input ->
                MockTurnkey.makeSolanaActivity(
                    id = "act-failed",
                    signWith = input.signWiths.single(),
                    caip2 = input.caip2,
                    unsignedTransaction = input.unsignedTransaction,
                    sendTransactionStatusId = null,
                    shape = MockTurnkey.SolanaSendShape.V2,
                    status = com.turnkey.types.V1ActivityStatus.ACTIVITY_STATUS_FAILED,
                    failureMessage = "policy engine denied the request"
                )
            }
        }
        val provider = makeProvider(client = client)

        val ex = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.message).contains("policy engine denied the request")
        assertThat(client.sendTransactionStatusCalls).isEmpty()
    }

    /** A rejected activity without a reason still names what happened. */
    @Test
    fun `sendNativeToken on solana names a rejected activity that carries no reason`() {
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            notCompletedAfterSubmit { input ->
                MockTurnkey.makeSolanaActivity(
                    id = "act-rejected",
                    signWith = input.signWiths.single(),
                    caip2 = input.caip2,
                    unsignedTransaction = input.unsignedTransaction,
                    sendTransactionStatusId = null,
                    shape = MockTurnkey.SolanaSendShape.V2,
                    status = com.turnkey.types.V1ActivityStatus.ACTIVITY_STATUS_REJECTED
                )
            }
        }
        val provider = makeProvider(client = client)

        val ex = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.message).contains("rejected the Solana send")
    }

    /** A refusal of the submit itself happened before anything was executed: no lookup, the failure maps as before. */
    @Test
    fun `sendNativeToken on solana does not look the activity up when the submit itself was refused`() {
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            solSendTransactionError = RuntimeException("HTTP error calling ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2 request\nCode: 400")
        }
        val provider = makeProvider(client = client)

        assertThrows(RainError.ProviderError::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(client.getActivitiesCalls).isEmpty()
    }

    /**
     * A 5xx answer to the submit says nothing about the send: Turnkey executes activities synchronously
     * and a gateway can fail after the activity was created. It is not a refusal, and the vendor's client
     * has no activity to hand back, so the host is told the fate is unknown rather than that nothing was
     * sent; nothing is listed and nothing is resent.
     */
    @Test
    fun `sendNativeToken on solana reports a 5xx submit answer as a send of unknown fate`() {
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            solSendTransactionError = RuntimeException("HTTP error calling ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2 request\nError: {}\nCode: 504")
        }
        val provider = makeProvider(client = client)

        val ex = assertThrows(RainError.TransactionOutcomeUnknown::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.message).contains("the send may still land")
        assertThat(client.solSendTransactionCalls).hasSize(1)
        assertThat(client.getActivitiesCalls).isEmpty()
        assertThat(client.sendTransactionStatusCalls).isEmpty()
    }

    /**
     * A transport failure during the vendor call says nothing about the send: the vendor's poll after an
     * accepted submit raises the same exception types as a submit that never left the device. With no
     * activity in hand, the host is told the fate is unknown rather than that nothing was sent.
     */
    @Test
    fun `sendNativeToken on solana treats a transport failure during the send as a send of unknown fate`() {
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            solSendTransactionError = java.net.UnknownHostException("api.turnkey.com")
        }
        val provider = makeProvider(client = client)

        val ex = assertThrows(RainError.TransactionOutcomeUnknown::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.message).contains("may still land")
        assertThat(client.getActivitiesCalls).isEmpty()
    }

    /** A 5xx answer to the submit carries the response body; the host sees status and target only. */
    @Test
    fun `sendNativeToken on solana keeps a 5xx submit answer's body out of the host's error`() {
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            solSendTransactionError = RuntimeException(
                "HTTP error calling ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2 request\nError: {\"marker\":\"gateway-body-7f3a\"}\nCode: 504"
            )
        }
        val provider = makeProvider(client = client)

        val ex = assertThrows(RainError.TransactionOutcomeUnknown::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.message).contains("504")
        assertThat(ex.message).doesNotContain("gateway-body-7f3a")
        assertThat(TurnkeyErrorMapping.turnkeyHttpStatus(ex)).isNull()
        assertThat(client.getActivitiesCalls).isEmpty()
    }

    /** A failed read during the wait ends the wait, not the send: the last activity read is what gets classified. */
    @Test
    fun `sendNativeToken on solana ends the wait on a failed activity read and reports the send pending`() {
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            notCompletedAfterSubmit { input -> pendingActivity(input, "act-read-failed") }
            // A 404 is not a transient status, so the coordinator's read does not retry it.
            getActivityError = RuntimeException("HTTP error from /public/v1/query/get_activity: 404")
        }
        val provider = makeProvider(client = client)

        val ex = assertThrows(RainError.TransactionPending::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.statusId).isEqualTo("act-read-failed")
        assertThat(client.getActivityCalls).hasSize(1)
        assertThat(client.solSendTransactionCalls).hasSize(1)
    }

    /** A caller cancelled during the wait leaves as a cancellation, not as a pending send. */
    @Test
    fun `sendNativeToken on solana propagates a cancellation from the activity wait`() {
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            notCompletedAfterSubmit { input -> pendingActivity(input, "act-cancelled-wait") }
            getActivityError = kotlinx.coroutines.CancellationException("caller went away")
        }
        val provider = makeProvider(client = client)

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(client.getActivityCalls).hasSize(1)
        assertThat(client.sendTransactionStatusCalls).isEmpty()
    }

    /** An activity waiting for a person (consensus) is not waited on: pending at once, on the activity id. */
    @Test
    fun `sendNativeToken on solana does not wait for an activity that needs consensus`() {
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            notCompletedAfterSubmit { input ->
                MockTurnkey.makeSolanaActivity(
                    id = "act-consensus",
                    signWith = input.signWiths.single(),
                    caip2 = input.caip2,
                    unsignedTransaction = input.unsignedTransaction,
                    sendTransactionStatusId = null,
                    shape = MockTurnkey.SolanaSendShape.V2,
                    status = com.turnkey.types.V1ActivityStatus.ACTIVITY_STATUS_CONSENSUS_NEEDED
                )
            }
        }
        val provider = makeProvider(client = client)

        val ex = assertThrows(RainError.TransactionPending::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.statusId).isEqualTo("act-consensus")
        assertThat(client.getActivityCalls).isEmpty()
    }

    /**
     * A 401 on the submit itself is the one refusal the coordinator refreshes and retries. The retry posts
     * the same body, timestamp included, so Turnkey sees the identical envelope and not a second send.
     */
    @Test
    @Suppress("TooGenericExceptionThrown") // the vendor's own refusal is a bare RuntimeException
    fun `sendNativeToken on solana retries a 401 refusal once with the same timestamp`(): Unit = runBlocking {
        stubBlockhash()
        var submits = 0
        val client = includedStatusClient().apply {
            solSendActivity = { input ->
                submits++
                if (submits == 1) {
                    throw RuntimeException("HTTP error calling ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2 request\nError: {}\nCode: 401")
                }
                MockTurnkey.makeSolanaActivity(
                    id = "act-after-refresh",
                    signWith = input.signWiths.single(),
                    caip2 = input.caip2,
                    unsignedTransaction = input.unsignedTransaction,
                    sendTransactionStatusId = "status-after-refresh",
                    shape = MockTurnkey.SolanaSendShape.V2
                )
            }
        }
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), turnkeyClient = client)
        turnkey.onRefreshSession = {
            // Wide enough for a clock read inside the write to differ between the two submits.
            kotlinx.coroutines.delay(CLOCK_GAP_MS)
            turnkey.session = MockTurnkey.defaultSession()
        }
        val provider = makeProvider(client = client, turnkey = turnkey)

        val result = provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5"))

        assertThat(result).isEqualTo(SIGNATURE)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
        val posted = client.solSendTransactionCalls
        assertThat(posted).hasSize(2)
        assertThat(posted[0].timestampMs).isNotNull()
        assertThat(posted[1].timestampMs).isEqualTo(posted[0].timestampMs)
        assertThat(client.sendTransactionStatusCalls.single().sendTransactionStatusId).isEqualTo("status-after-refresh")
    }

    /**
     * Turnkey was still executing the activity when the vendor's client gave up on it and threw it. The
     * send reads it again by id at the SDK's own interval, and once it completes with a status id the
     * normal status poll takes over: the host gets the signature, not an activity id.
     */
    @Test
    fun `sendNativeToken on solana waits for the activity the vendor's error carries while Turnkey executes it`(): Unit = runBlocking {
        stubBlockhash()
        var posted: TSolSendTransactionBody? = null
        var reads = 0
        val client = includedStatusClient().apply {
            notCompletedAfterSubmit { input ->
                posted = input
                pendingActivity(input, "act-settling")
            }
            getActivityAnswer = { request ->
                reads++
                if (reads < 2) {
                    mockActivities.single { it.id == request.activityId }
                } else {
                    val body = checkNotNull(posted)
                    MockTurnkey.makeSolanaActivity(
                        id = request.activityId,
                        signWith = body.signWiths.single(),
                        caip2 = body.caip2,
                        unsignedTransaction = body.unsignedTransaction,
                        sendTransactionStatusId = "status-after-wait",
                        shape = MockTurnkey.SolanaSendShape.V2
                    )
                }
            }
        }
        val provider = makeProvider(client = client)

        val result = provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5"))

        assertThat(result).isEqualTo(SIGNATURE)
        assertThat(client.getActivityCalls.map { it.activityId }).containsExactly("act-settling", "act-settling")
        assertThat(client.sendTransactionStatusCalls.single().sendTransactionStatusId).isEqualTo("status-after-wait")
    }

    /**
     * kotlinx quotes the input it could not decode, and for a Turnkey answer that is the activity. The
     * host-visible error carries the vendor's message cut before that quote.
     */
    @Test
    fun `sendNativeToken on solana keeps the undecodable answer's body out of the host's error`() {
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            solSendTransactionError = kotlinx.serialization.SerializationException(
                "Unexpected JSON token at offset 12\nJSON input: {\"activity\":{\"id\":\"act-secret\"}}"
            )
        }
        val provider = makeProvider(client = client)

        val ex = assertThrows(RainError.TransactionOutcomeUnknown::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.message).contains("Unexpected JSON token at offset 12")
        assertThat(ex.message).doesNotContain("JSON input")
        assertThat(ex.message).doesNotContain("act-secret")
        // Nothing is read back: the vendor's client lost the activity, and the activity log is not consulted.
        assertThat(client.getActivitiesCalls).isEmpty()
    }

    /**
     * The vendor polls `get_activity` after Turnkey accepted the activity; a 401 there is not a refusal
     * of the send, and the vendor's client loses the activity with it. It must not re-run the submit
     * through the coordinator's refresh-and-retry (a second activity, a second transfer): the send ends
     * with the fate unknown, one submit, no refresh, and no HTTP status on the error to retry on.
     */
    @Test
    fun `sendNativeToken on solana does not resubmit when the vendor's poll fails with 401 after acceptance`() {
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            solSendTransactionError = RuntimeException("HTTP error from /public/v1/query/get_activity: 401")
        }
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()), turnkeyClient = client)
        val provider = makeProvider(client = client, turnkey = turnkey)

        val ex = assertThrows(RainError.TransactionOutcomeUnknown::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.message).contains("the send may still land")
        assertThat(TurnkeyErrorMapping.turnkeyHttpStatus(ex)).isNull()
        assertThat(client.solSendTransactionCalls).hasSize(1)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
        assertThat(client.getActivitiesCalls).isEmpty()
    }

    /** A cancelled caller leaves as a cancellation, before anything else. */
    @Test
    fun `sendNativeToken on solana propagates a cancellation from the send without looking anything up`() {
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            solSendTransactionError = kotlinx.coroutines.CancellationException("caller went away")
        }
        val provider = makeProvider(client = client)

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(client.getActivitiesCalls).isEmpty()
    }

    /** The timestamp is pinned on the body, so a re-run of the write would post the identical envelope. */
    @Test
    fun `sendNativeToken on solana pins the timestamp on the body it posts`(): Unit = runBlocking {
        stubBlockhash()
        val client = includedStatusClient()
        val provider = makeProvider(client = client)

        provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5"))

        val posted = client.solSendTransactionCalls.single()
        assertThat(posted.timestampMs).isNotNull()
        assertThat(posted.timestampMs!!.toLong()).isGreaterThan(0L)
    }

    /**
     * The vendor's client throws `StamperNotInitialized` before it builds the request when it has no
     * session stamper (a logout landing between the coordinator's session check and its client read).
     * Nothing left the device, so the send is a provider failure, not one of unknown fate.
     */
    @Test
    fun `sendNativeToken on solana reports a client without a session stamper as a provider failure`() {
        stubBlockhash()
        val client = MockTurnkeyClient().apply {
            solSendTransactionError = TurnkeyHttpError.StamperNotInitialized()
        }
        val provider = makeProvider(client = client)

        val ex = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { provider.sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.5")) }
        }

        assertThat(ex.message).doesNotContain("may still land")
        assertThat(client.solSendTransactionCalls).hasSize(1)
        assertThat(client.getActivitiesCalls).isEmpty()
    }

    /** An activity Turnkey failed or rejected broadcast nothing, so the activity-log history leaves it out; a pending one stays. */
    @Test
    fun `getTransactions on solana leaves out a send the backend failed or rejected`(): Unit = runBlocking {
        fun activity(id: String, status: com.turnkey.types.V1ActivityStatus, statusId: String?) = MockTurnkey.makeSolanaActivity(
            id = id,
            signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            caip2 = devnetCaip2,
            unsignedTransaction = "00",
            sendTransactionStatusId = statusId,
            shape = MockTurnkey.SolanaSendShape.V2,
            status = status,
            failureMessage = "policy engine denied the request".takeIf { status != com.turnkey.types.V1ActivityStatus.ACTIVITY_STATUS_COMPLETED }
        )
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                activity("act-sent", com.turnkey.types.V1ActivityStatus.ACTIVITY_STATUS_COMPLETED, "status-sent"),
                activity("act-rejected", com.turnkey.types.V1ActivityStatus.ACTIVITY_STATUS_REJECTED, null),
                activity("act-failed", com.turnkey.types.V1ActivityStatus.ACTIVITY_STATUS_FAILED, null),
                activity("act-pending", com.turnkey.types.V1ActivityStatus.ACTIVITY_STATUS_PENDING, null)
            )
        )
        val provider = makeProvider(client = client)

        val rows = provider.getTransactions(devnet, limit = 10)

        assertThat(rows.map { it.uniqueId }).containsExactly("act-sent", "act-pending")
        assertThat(rows.single { it.uniqueId == "act-pending" }.hash).isEqualTo("act-pending")
    }

    @Test
    fun `sendNativeToken on solana rejects sub-lamport precision before contacting anything`() {
        val client = MockTurnkeyClient()

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                makeProvider(client = client)
                    .sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("0.0000000015"))
            }
        }
        assertThat(client.solSendTransactionCalls).isEmpty()
    }

    @Test
    fun `sendNativeToken on solana rejects a negative amount as a typed error`() {
        val client = MockTurnkeyClient()

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                makeProvider(client = client)
                    .sendNativeToken(devnet, MockTurnkey.DEFAULT_SOLANA_RECIPIENT, BigDecimal("-1"))
            }
        }
        assertThat(client.solSendTransactionCalls).isEmpty()
    }

    @Test
    fun `getTransactions on solana reads sol_send activities and decodes recipient and amount`(): Unit = runBlocking {
        val unsignedTx = SolanaTransactionBuilder.buildTransferHex(
            fromAddress = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            toAddress = MockTurnkey.DEFAULT_SOLANA_RECIPIENT,
            lamports = 1_000_000_000L, // 1 SOL
            recentBlockhash = MockTurnkey.DEFAULT_SOLANA_ADDRESS // valid 32-byte base58 stand-in
        )
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "act-1",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "sol-status-1"
                )
            )
        )
        val provider = makeProvider(client = client)

        val result = provider.getTransactions(devnet, limit = 10, order = RainTransactionOrder.DESC)

        assertThat(result).hasSize(1)
        val tx = result.single()
        assertThat(tx.hash).isEqualTo("sol-status-1") // Turnkey status id, not an on-chain signature
        assertThat(tx.from).isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        assertThat(tx.to).isEqualTo(MockTurnkey.DEFAULT_SOLANA_RECIPIENT)
        assertThat(tx.value!!.compareTo(BigDecimal("1"))).isEqualTo(0)
        assertThat(tx.asset).isEqualTo("SOL")
        assertThat(tx.chainId).isEqualTo(devnet)
        assertThat(tx.category).isEqualTo(RainTransactionCategory.External)
        // History is sourced from the activity log, under both types a Solana send is recorded as,
        // not from chain RPC.
        assertThat(client.getActivitiesCalls.single().filterByType)
            .containsExactly(
                com.turnkey.types.V1ActivityType.ACTIVITY_TYPE_SOL_SEND_TRANSACTION,
                com.turnkey.types.V1ActivityType.ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2
            )
            .inOrder()
    }

    /**
     * The request the SDK posts is `ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2`, so Turnkey records the
     * send with the V2 intent (`signWiths`) and the V2 result. A row in that shape lists like a V1
     * one: the wallet as `from`, the status id as `hash`, recipient and amount from the blob.
     */
    @Test
    fun `getTransactions on solana lists a send recorded in the V2 shape`(): Unit = runBlocking {
        val unsignedTx = SolanaTransactionBuilder.buildTransferHex(
            fromAddress = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            toAddress = MockTurnkey.DEFAULT_SOLANA_RECIPIENT,
            lamports = 250_000_000L, // 0.25 SOL
            recentBlockhash = MockTurnkey.DEFAULT_SOLANA_ADDRESS
        )
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "act-v2",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "sol-status-v2",
                    shape = MockTurnkey.SolanaSendShape.V2
                )
            )
        )
        val provider = makeProvider(client = client)

        val tx = provider.getTransactions(devnet, limit = 10).single()

        assertThat(tx.hash).isEqualTo("sol-status-v2")
        assertThat(tx.uniqueId).isEqualTo("act-v2")
        assertThat(tx.from).isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        assertThat(tx.to).isEqualTo(MockTurnkey.DEFAULT_SOLANA_RECIPIENT)
        assertThat(tx.value!!.compareTo(BigDecimal("0.25"))).isEqualTo(0)
        assertThat(tx.asset).isEqualTo("SOL")
    }

    /** A page mixing both shapes lists every send once, newest first. */
    @Test
    fun `getTransactions on solana lists V1 and V2 sends together`(): Unit = runBlocking {
        val unsignedTx = SolanaTransactionBuilder.buildTransferHex(
            MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            MockTurnkey.DEFAULT_SOLANA_RECIPIENT,
            1L,
            MockTurnkey.DEFAULT_SOLANA_ADDRESS
        )
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "older-v1",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "status-v1",
                    createdAtSeconds = "1714521600"
                ),
                MockTurnkey.makeSolanaActivity(
                    id = "newer-v2",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "status-v2",
                    createdAtSeconds = "1714521700",
                    shape = MockTurnkey.SolanaSendShape.V2
                )
            )
        )
        val provider = makeProvider(client = client)

        val newestFirst = provider.getTransactions(devnet, limit = 10, order = RainTransactionOrder.DESC).map { it.hash }
        val oldestFirst = provider.getTransactions(devnet, limit = 10, order = RainTransactionOrder.ASC).map { it.hash }
        val secondPage = provider.getTransactions(devnet, limit = 1, offset = 1, order = RainTransactionOrder.DESC).map { it.hash }

        assertThat(newestFirst).containsExactly("status-v2", "status-v1").inOrder()
        assertThat(oldestFirst).containsExactly("status-v1", "status-v2").inOrder()
        assertThat(secondPage).containsExactly("status-v1")
    }

    /**
     * A V2 activity lists every signer in transaction order, and a co-signer may come first. The row's
     * sender is the account the decoded transfer moves funds from, not whoever signed first.
     */
    @Test
    fun `getTransactions on solana names the transfer's source as the sender, not the first co-signer`(): Unit = runBlocking {
        val coSigner = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        val unsignedTx = SolanaTransactionBuilder.buildTransferHex(
            fromAddress = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            toAddress = MockTurnkey.DEFAULT_SOLANA_RECIPIENT,
            lamports = 1L,
            recentBlockhash = MockTurnkey.DEFAULT_SOLANA_ADDRESS
        )
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "act-cosigned",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "status-cosigned",
                    shape = MockTurnkey.SolanaSendShape.V2,
                    coSignerFirst = coSigner
                )
            )
        )
        val provider = makeProvider(client = client)

        val tx = provider.getTransactions(devnet, limit = 10).single()

        assertThat(tx.from).isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        assertThat(tx.to).isEqualTo(MockTurnkey.DEFAULT_SOLANA_RECIPIENT)
    }

    @Test
    fun `getTransactions on solana ignores activities from a different cluster`(): Unit = runBlocking {
        val unsignedTx = SolanaTransactionBuilder.buildTransferHex(
            MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            MockTurnkey.DEFAULT_SOLANA_RECIPIENT,
            1L,
            MockTurnkey.DEFAULT_SOLANA_ADDRESS
        )
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "mainnet-act",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp", // mainnet, not devnet
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "x"
                )
            )
        )
        val provider = makeProvider(client = client)

        assertThat(provider.getTransactions(devnet, limit = 10)).isEmpty()
    }

    @Test
    fun `getTransactions on solana decodes an spl transfer and resolves the recipient wallet`(): Unit = runBlocking {
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val recipientWallet = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        val destinationAta = Base58.encode(
            SolanaAddresses.associatedTokenAddress(
                Base58.decode(recipientWallet),
                Base58.decode(mint),
                SolanaPrograms.TOKEN
            )
        )
        val unsignedTx = unsignedHex(
            feePayer = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
            recentBlockhash = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            instructions = listOf(
                SolanaInstructions.transferChecked(
                    tokenProgramId = SolanaPrograms.TOKEN,
                    source = SolanaAddresses.associatedTokenAddress(
                        Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
                        Base58.decode(mint),
                        SolanaPrograms.TOKEN
                    ),
                    mint = Base58.decode(mint),
                    destination = Base58.decode(destinationAta),
                    owner = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
                    amount = java.math.BigInteger.valueOf(1_500_000L),
                    decimals = 6
                )
            )
        )
        // The recipient's token account resolves back to the wallet that owns it.
        rpc.stubObjectFor(
            "getAccountInfo",
            destinationAta,
            JSONObject().put("context", JSONObject().put("slot", 1)).put(
                "value",
                JSONObject().put("owner", SolanaPrograms.TOKEN_ADDRESS).put(
                    "data",
                    JSONObject().put("program", "spl-token").put(
                        "parsed",
                        JSONObject().put("type", "account").put(
                            "info",
                            JSONObject().put("mint", mint).put("owner", recipientWallet).put(
                                "tokenAmount",
                                JSONObject().put("amount", "1500000").put("decimals", 6)
                            )
                        )
                    )
                )
            )
        )
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "act-spl",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "sol-status-spl"
                )
            )
        )

        val tx = makeProvider(client = client).getTransactions(devnet, limit = 10).single()

        assertThat(tx.from).isEqualTo(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
        // The recipient is reported as their wallet, not the token account the transfer targets.
        assertThat(tx.to).isEqualTo(recipientWallet)
        assertThat(tx.value!!.compareTo(BigDecimal("1.5"))).isEqualTo(0)
        assertThat(tx.tokenAddress).isEqualTo(mint)
        assertThat(tx.asset).isNull()
        assertThat(tx.category).isEqualTo(RainTransactionCategory.Token)
        assertThat(tx.metadata?.destinationTokenAccount).isEqualTo(destinationAta)
        assertThat(tx.metadata?.sourceTokenAccount).isNotNull()
    }

    @Test
    fun `getTransactions on solana falls back to the token account when the owner is unreadable`(): Unit = runBlocking {
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val destinationAta = Base58.encode(
            SolanaAddresses.associatedTokenAddress(
                Base58.decode(mint),
                Base58.decode(mint),
                SolanaPrograms.TOKEN
            )
        )
        val unsignedTx = unsignedHex(
            feePayer = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
            recentBlockhash = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            instructions = listOf(
                SolanaInstructions.transferChecked(
                    tokenProgramId = SolanaPrograms.TOKEN,
                    source = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
                    mint = Base58.decode(mint),
                    destination = Base58.decode(destinationAta),
                    owner = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
                    amount = java.math.BigInteger.valueOf(250L),
                    decimals = 2
                )
            )
        )
        // getAccountInfo left unstubbed -> the lookup fails.
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "act-spl-2",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "sol-status-spl-2"
                )
            )
        )

        val tx = makeProvider(client = client).getTransactions(devnet, limit = 10).single()

        // A real, lookup-able address beats reporting nothing.
        assertThat(tx.to).isEqualTo(destinationAta)
        assertThat(tx.value!!.compareTo(BigDecimal("2.5"))).isEqualTo(0)
    }

    @Test
    fun `getTransactions on solana recovers the recipient wallet from the create-account instruction`(): Unit = runBlocking {
        // A first-time transfer carries the recipient's wallet in its own ATA-creation
        // instruction, so no node read is needed to resolve it.
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val recipientWallet = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        val destinationAta = SolanaAddresses.associatedTokenAddress(
            Base58.decode(recipientWallet),
            Base58.decode(mint),
            SolanaPrograms.TOKEN
        )
        val unsignedTx = unsignedHex(
            feePayer = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
            recentBlockhash = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            instructions = listOf(
                SolanaInstructions.createAssociatedTokenAccountIdempotent(
                    tokenProgramId = SolanaPrograms.TOKEN,
                    payer = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
                    associatedAccount = destinationAta,
                    owner = Base58.decode(recipientWallet),
                    mint = Base58.decode(mint)
                ),
                SolanaInstructions.transferChecked(
                    tokenProgramId = SolanaPrograms.TOKEN,
                    source = SolanaAddresses.associatedTokenAddress(
                        Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
                        Base58.decode(mint),
                        SolanaPrograms.TOKEN
                    ),
                    mint = Base58.decode(mint),
                    destination = destinationAta,
                    owner = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
                    amount = java.math.BigInteger.valueOf(1_500_000L),
                    decimals = 6
                )
            )
        )
        // getAccountInfo deliberately unstubbed: the wallet must come from the blob itself.
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "act-spl-create",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "sol-status-spl-create"
                )
            )
        )

        val tx = makeProvider(client = client).getTransactions(devnet, limit = 10).single()

        assertThat(tx.to).isEqualTo(recipientWallet)
        assertThat(tx.value!!.compareTo(BigDecimal("1.5"))).isEqualTo(0)
        assertThat(tx.tokenAddress).isEqualTo(mint)
    }

    @Test
    fun `getTransactions on solana decodes a bare transfer built by other tooling`(): Unit = runBlocking {
        // TokenInstruction::Transfer (tag 3): @solana/spl-token's default, never produced by this
        // SDK but present in the same Turnkey activity feed when other tooling drives the wallet.
        // It carries neither mint nor decimals.
        val source = ByteArray(32) { (it + 11).toByte() }
        val destination = ByteArray(32) { (it + 55).toByte() }
        val data = ByteArray(9)
        data[0] = 3
        var amount = 250L
        for (i in 0 until 8) {
            data[1 + i] = (amount and 0xFF).toByte()
            amount = amount ushr 8
        }
        val unsignedTx = unsignedHex(
            feePayer = Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
            recentBlockhash = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
            instructions = listOf(
                com.rain.sdk.internal.solana.Instruction(
                    programId = SolanaPrograms.TOKEN,
                    accounts = listOf(
                        com.rain.sdk.internal.solana.AccountMeta.writable(source),
                        com.rain.sdk.internal.solana.AccountMeta.writable(destination),
                        com.rain.sdk.internal.solana.AccountMeta.signer(
                            Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
                        )
                    ),
                    data = data
                )
            )
        )
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeSolanaActivity(
                    id = "act-bare",
                    signWith = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    caip2 = devnetCaip2,
                    unsignedTransaction = unsignedTx,
                    sendTransactionStatusId = "sol-status-bare"
                )
            )
        )

        val tx = makeProvider(client = client).getTransactions(devnet, limit = 10).single()

        // The row lists rather than showing as undecodable; the destination token account is the
        // best recoverable recipient and the unscaled amount stays in the metadata.
        assertThat(tx.hash).isEqualTo("sol-status-bare")
        assertThat(tx.to).isEqualTo(Base58.encode(destination))
        assertThat(tx.value).isNull() // no decimals on the wire and no mint to read them from
        assertThat(tx.tokenAddress).isNull()
        assertThat(tx.rawValue).isEqualTo("250")
    }

    // ---------- SPL transfers ----------

    @Test
    fun `sendToken on solana transfers between existing token accounts`(): Unit = runBlocking {
        val fixture = splFixture(recipientAccountExists = true)
        val client = includedStatusClient()
        val provider = makeProvider(client = client)

        val result = provider.sendToken(devnet, mint, recipient, BigDecimal("1.5"), decimals = 18)

        assertThat(result).isEqualTo(SIGNATURE)
        val body = client.solSendTransactionCalls.single()
        assertThat(body.caip2).isEqualTo(devnetCaip2)
        assertThat(body.signWiths).containsExactly(MockTurnkey.DEFAULT_SOLANA_ADDRESS)

        // One instruction (the transfer) — no account creation, since the recipient has one.
        val instructions = decodeInstructions(body.unsignedTransaction)
        assertThat(instructions).hasSize(1)
        // transfer_checked: tag 12, 1.5 at the mint's 6 decimals = 1_500_000 base units, and the
        // decimals byte comes from the mint, NOT the 18 the caller passed in.
        assertThat(instructions.single().first).isEqualTo(SolanaPrograms.TOKEN_ADDRESS)
        assertThat(instructions.single().second)
            .isEqualTo(byteArrayOf(12, 0x60, 0xE3.toByte(), 0x16, 0, 0, 0, 0, 0, 6))
        // The transfer is dry-run before it is handed to Turnkey.
        assertThat(rpc.recordedMethods).contains("simulateTransaction")
        // Self-paid, the message carries only what the transfer references: no System Program.
        assertThat(decodeAccountKeys(body.unsignedTransaction)).doesNotContain(SolanaPrograms.SYSTEM_ADDRESS)
        assertThat(fixture).isTrue()
    }

    @Test
    fun `sendToken on solana creates the recipient token account when missing`(): Unit = runBlocking {
        splFixture(recipientAccountExists = false)
        val client = includedStatusClient()
        val provider = makeProvider(client = client)

        provider.sendToken(devnet, mint, recipient, BigDecimal("1.5"), decimals = 6)

        val instructions = decodeInstructions(client.solSendTransactionCalls.single().unsignedTransaction)
        assertThat(instructions).hasSize(2)
        // Create-idempotent (discriminator 1) against the associated-token program, ahead of the
        // transfer — the sender pays the new account's rent.
        assertThat(instructions[0].first).isEqualTo(SolanaPrograms.ASSOCIATED_TOKEN_ADDRESS)
        assertThat(instructions[0].second).isEqualTo(byteArrayOf(1))
        assertThat(instructions[1].first).isEqualTo(SolanaPrograms.TOKEN_ADDRESS)
    }

    @Test
    fun `sendToken on solana addresses token-2022 mints to their own program`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true, tokenProgram = SolanaPrograms.TOKEN_2022_ADDRESS)
        val client = includedStatusClient()
        val provider = makeProvider(client = client)

        provider.sendToken(devnet, mint, recipient, BigDecimal("1.5"), decimals = 6)

        val instructions = decodeInstructions(client.solSendTransactionCalls.single().unsignedTransaction)
        assertThat(instructions.single().first).isEqualTo(SolanaPrograms.TOKEN_2022_ADDRESS)
    }

    @Test
    fun `sendToken on solana rejects a mint that does not exist on this cluster`(): Unit = runBlocking {
        rpc.stubObjectFor("getAccountInfo", mint, accountEnvelope(JSONObject.NULL))

        val error = assertThrows(RainError.TokenNotFound::class.java) {
            runBlocking { makeProvider().sendToken(devnet, mint, recipient, BigDecimal("1"), decimals = 6) }
        }
        assertThat(error.token).isEqualTo(mint)
    }

    @Test
    fun `sendToken on solana rejects a wallet that holds none of the token`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true, senderAccountExists = false)

        assertThrows(RainError.TokenAccountNotFound::class.java) {
            runBlocking { makeProvider().sendToken(devnet, mint, recipient, BigDecimal("1"), decimals = 6) }
        }
        Unit
    }

    @Test
    fun `sendToken on solana rejects an amount larger than the balance`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true, senderBaseUnits = "1000000") // 1.0 at 6dp

        val error = assertThrows(RainError.InsufficientTokenBalance::class.java) {
            runBlocking { makeProvider().sendToken(devnet, mint, recipient, BigDecimal("2.5"), decimals = 6) }
        }
        assertThat(error.requested.compareTo(BigDecimal("2.5"))).isEqualTo(0)
        assertThat(error.available.compareTo(BigDecimal.ONE)).isEqualTo(0)
    }

    @Test
    fun `sendToken on solana rejects an amount finer than the mint's decimals`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true)

        // The mint has 6 decimals; 7 would be silently truncated by a naive conversion.
        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking { makeProvider().sendToken(devnet, mint, recipient, BigDecimal("1.0000001"), decimals = 6) }
        }
        Unit
    }

    @Test
    fun `sendToken on solana rejects a token account as the recipient`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true)
        // The recipient address itself resolves to a token account, not a wallet.
        rpc.stubObjectFor("getAccountInfo", recipient, accountEnvelope(tokenAccountValue("1")))

        assertThrows(RainError.InvalidRecipient::class.java) {
            runBlocking { makeProvider().sendToken(devnet, mint, recipient, BigDecimal("1"), decimals = 6) }
        }
        Unit
    }

    @Test
    fun `sendToken on solana rejects sending to the wallet itself`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true)

        assertThrows(RainError.InvalidRecipient::class.java) {
            runBlocking {
                makeProvider().sendToken(
                    devnet,
                    mint,
                    MockTurnkey.DEFAULT_SOLANA_ADDRESS,
                    BigDecimal("1"),
                    decimals = 6
                )
            }
        }
        Unit
    }

    @Test
    fun `sendToken on solana surfaces a failed simulation instead of broadcasting`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true, simulationError = "InstructionError")
        val client = includedStatusClient()

        assertThrows(RainError.TransactionSimulationFailed::class.java) {
            runBlocking {
                makeProvider(
                    client = client
                ).sendToken(devnet, mint, recipient, BigDecimal("1"), decimals = 6)
            }
        }
        // Nothing reached Turnkey.
        assertThat(client.solSendTransactionCalls).isEmpty()
    }

    @Test
    fun `sponsored sendToken on solana proceeds with zero fee lamports and skips the dry run`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true, lamports = 0L)
        val client = includedStatusClient()
        val provider = makeProvider(client = client, sponsorGas = true)

        val result = provider.sendToken(devnet, mint, recipient, BigDecimal("1.5"), decimals = 6)

        assertThat(result).isEqualTo(SIGNATURE)
        val body = client.solSendTransactionCalls.single()
        assertThat(body.sponsor).isEqualTo(true)
        // The self-paid dry run would false-fail a zero-SOL sponsored wallet, so it is skipped.
        assertThat(rpc.recordedMethods).doesNotContain("simulateTransaction")
        // Turnkey requires the System Program among a sponsored transaction's static keys; the
        // transfer itself never references it, so the composer carries it explicitly.
        assertThat(decodeAccountKeys(body.unsignedTransaction)).contains(SolanaPrograms.SYSTEM_ADDRESS)
    }

    @Test
    fun `sponsored sendToken needs only the rent when the recipient account must be created`(): Unit = runBlocking {
        // Exactly the rent and not a lamport more: the fee is the sponsor's, the rent the sender's.
        // Self-paid, this balance fails the fee check.
        splFixture(recipientAccountExists = false, lamports = SolanaLamportPreflight.TOKEN_ACCOUNT_RENT_LAMPORTS)
        val client = includedStatusClient()

        val result = makeProvider(client = client, sponsorGas = true)
            .sendToken(devnet, mint, recipient, BigDecimal("1"), decimals = 6)

        assertThat(result).isEqualTo(SIGNATURE)
        assertThat(client.solSendTransactionCalls.single().sponsor).isEqualTo(true)
    }

    @Test
    fun `sendSolanaTransaction on the solana testnet cluster fails closed`() {
        // The raw entry core uses for collateral withdrawals is gated like the transfer entries.
        val client = includedStatusClient()
        val provider = makeProvider(client = client)
        val unsigned = UnsignedSolanaTransfer(ByteArray(8), recentBlockhash = mint)

        val error = assertThrows(RainError.ChainNotSupported::class.java) {
            runBlocking { provider.sendSolanaTransaction(RainChain.SOLANA_TESTNET, unsigned) }
        }

        assertThat(error.chainId).isEqualTo(RainChain.SOLANA_TESTNET)
        assertThat(client.solSendTransactionCalls).isEmpty()
        assertThat(rpc.recordedMethods).isEmpty()
    }

    @Test
    fun `sponsored sendToken still requires rent when the recipient account must be created`(): Unit = runBlocking {
        // Fee sponsorship covers the network fee only; token-account rent is a separate,
        // default-off dashboard toggle, so the sender must still hold it.
        splFixture(recipientAccountExists = false, lamports = 0L)

        assertThrows(RainError.InsufficientFunds::class.java) {
            runBlocking {
                makeProvider(sponsorGas = true).sendToken(devnet, mint, recipient, BigDecimal("1"), decimals = 6)
            }
        }
    }

    @Test
    fun `sendToken on solana rejects a wallet that cannot cover the fee`(): Unit = runBlocking {
        splFixture(recipientAccountExists = true, lamports = 100L)

        val error = assertThrows(RainError.InsufficientFunds::class.java) {
            runBlocking { makeProvider().sendToken(devnet, mint, recipient, BigDecimal("1"), decimals = 6) }
        }

        // Self-paid with the recipient account in place: the 5000-lamport fee alone against 100 lamports, in SOL.
        assertThat(error.required?.compareTo(BigDecimal("0.000005"))).isEqualTo(0)
        assertThat(error.available?.compareTo(BigDecimal("0.0000001"))).isEqualTo(0)
    }

    // ---------- EVM-only entry points ----------

    @Test
    fun `low-level EVM operations are rejected on solana chain ids`(): Unit = runBlocking {
        val provider = makeProvider()

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { provider.sendTransaction(devnet, "from", "to", "0x", "0x0") }
        }
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { provider.estimateTransactionFee(devnet, "from", "to", "0x", "0x0") }
        }
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { provider.signTypedData(devnet, "wallet", "{}") }
        }
        Unit
    }

    // ---------- SPL fixtures ----------

    private val mint = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    private val recipient get() = MockTurnkey.DEFAULT_SOLANA_RECIPIENT

    /**
     * Stubs every read the SPL path makes: the mint, the recipient wallet, both token accounts,
     * the fee balance, a blockhash and a simulation. Returns true so callers can assert on it.
     */
    private fun splFixture(
        recipientAccountExists: Boolean,
        senderAccountExists: Boolean = true,
        senderBaseUnits: String = "5000000", // 5.0 at 6 decimals
        tokenProgram: String = SolanaPrograms.TOKEN_ADDRESS,
        simulationError: String? = null,
        lamports: Long = 1_000_000_000L
    ): Boolean {
        val tokenProgramKey = Base58.decode(tokenProgram)
        val mintKey = Base58.decode(mint)
        val senderAta = Base58.encode(
            SolanaAddresses.associatedTokenAddress(
                Base58.decode(MockTurnkey.DEFAULT_SOLANA_ADDRESS),
                mintKey,
                tokenProgramKey
            )
        )
        val recipientAta = Base58.encode(
            SolanaAddresses.associatedTokenAddress(
                Base58.decode(recipient),
                mintKey,
                tokenProgramKey
            )
        )

        rpc.stubObjectFor("getAccountInfo", mint, accountEnvelope(mintValue(tokenProgram)))
        // The recipient is a plain wallet: parsed data absent, owned by the system program.
        rpc.stubObjectFor("getAccountInfo", recipient, accountEnvelope(walletValue()))
        rpc.stubObjectFor(
            "getAccountInfo",
            senderAta,
            accountEnvelope(
                if (senderAccountExists) {
                    tokenAccountValue(senderBaseUnits, tokenProgram)
                } else {
                    JSONObject.NULL
                }
            )
        )
        rpc.stubObjectFor(
            "getAccountInfo",
            recipientAta,
            accountEnvelope(
                if (recipientAccountExists) tokenAccountValue("0", tokenProgram) else JSONObject.NULL
            )
        )
        rpc.stubObject(
            "getBalance",
            JSONObject().put("context", JSONObject().put("slot", 1)).put("value", lamports)
        )
        rpc.stubObject(
            "getLatestBlockhash",
            JSONObject()
                .put("context", JSONObject().put("slot", 1))
                .put(
                    "value",
                    JSONObject()
                        .put("blockhash", MockTurnkey.DEFAULT_SOLANA_ADDRESS)
                        .put("lastValidBlockHeight", 150)
                )
        )
        rpc.stubObject(
            "simulateTransaction",
            JSONObject().put("context", JSONObject().put("slot", 1)).put(
                "value",
                JSONObject()
                    .put("err", simulationError?.let { JSONObject().put(it, JSONArray()) } ?: JSONObject.NULL)
                    .put("logs", JSONArray())
            )
        )
        rpc.stubObject(
            "getSignaturesForAddress",
            JSONArray().put(JSONObject().put("signature", SIGNATURE).put("slot", 150))
        )
        return true
    }

    private fun stubBlockhash() {
        rpc.stubObject(
            "getLatestBlockhash",
            JSONObject()
                .put("context", JSONObject().put("slot", 1))
                .put(
                    "value",
                    JSONObject().put("blockhash", MockTurnkey.DEFAULT_SOLANA_ADDRESS).put("lastValidBlockHeight", 150)
                )
        )
    }

    /**
     * A `getTransaction` result for [signature]: `json` encoding, [feePayer] first, then an
     * optional [coSigner], with `numRequiredSignatures` counting exactly those signers; the
     * [nonSigner] account and the System Program follow; [err] in meta.
     */
    private fun stubTransaction(
        signature: String,
        feePayer: String,
        err: JSONObject? = null,
        coSigner: String? = null,
        nonSigner: String = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
    ) {
        val signers = listOfNotNull(feePayer, coSigner)
        val accountKeys = JSONArray()
        signers.forEach { accountKeys.put(it) }
        accountKeys.put(nonSigner).put(SolanaPrograms.SYSTEM_ADDRESS)
        rpc.stubObjectFor(
            "getTransaction",
            signature,
            JSONObject()
                .put("slot", 150)
                .put(
                    "transaction",
                    JSONObject()
                        .put("signatures", JSONArray().put(signature))
                        .put(
                            "message",
                            JSONObject()
                                .put("header", JSONObject().put("numRequiredSignatures", signers.size))
                                .put("accountKeys", accountKeys)
                        )
                )
                .put("meta", JSONObject().put("err", err ?: JSONObject.NULL))
        )
    }

    /** Included on Turnkey's side but with no signature in the status: forces chain recovery. */
    private fun includedWithoutSignatureClient() = MockTurnkeyClient().apply {
        sendTransactionStatusQueue = mutableListOf(
            MockTurnkeyClient.StatusFixture(txHash = null, txStatus = "TX_STATUS_INCLUDED")
        )
    }

    private fun includedStatusClient() = MockTurnkeyClient().apply {
        sendTransactionStatusQueue = mutableListOf(
            MockTurnkeyClient.StatusFixture(solanaSignature = SIGNATURE, txStatus = "TX_STATUS_INCLUDED")
        )
    }

    private fun accountEnvelope(value: Any): JSONObject =
        JSONObject().put("context", JSONObject().put("slot", 1)).put("value", value)

    private fun walletValue(): JSONObject = JSONObject()
        .put("owner", SolanaPrograms.SYSTEM_ADDRESS)
        .put("lamports", 1_000_000_000L)
        .put("data", JSONArray().put("").put("base64"))

    private fun mintValue(program: String): JSONObject = JSONObject()
        .put("owner", program)
        .put(
            "data",
            JSONObject().put("program", "spl-token").put(
                "parsed",
                JSONObject().put("type", "mint").put("info", JSONObject().put("decimals", 6))
            )
        )

    private fun tokenAccountValue(
        amount: String,
        program: String = SolanaPrograms.TOKEN_ADDRESS
    ): JSONObject = JSONObject()
        .put("owner", program)
        .put(
            "data",
            JSONObject().put("program", "spl-token").put(
                "parsed",
                JSONObject().put("type", "account").put(
                    "info",
                    JSONObject()
                        .put("mint", mint)
                        .put("owner", MockTurnkey.DEFAULT_SOLANA_ADDRESS)
                        .put(
                            "tokenAmount",
                            JSONObject().put("amount", amount).put("decimals", 6)
                        )
                )
            )
        )

    /** Lowercase hex of an unsigned transaction, the form the vendor's unsigned-transaction field carries. */
    private fun unsignedHex(
        feePayer: ByteArray,
        recentBlockhash: String,
        instructions: List<com.rain.sdk.internal.solana.Instruction>
    ): String = SolanaTransactionBuilder.buildUnsignedTransaction(feePayer, recentBlockhash, instructions)
        .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** The static account keys of a serialized unsigned transaction, in table order. */
    private fun decodeAccountKeys(unsignedTransactionHex: String): List<String> {
        val bytes = unsignedTransactionHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        var i = 0
        val signatureCount = bytes[i++].toInt() and 0xFF
        i += signatureCount * 64 + 3 // signatures + header
        val accountCount = bytes[i++].toInt() and 0xFF
        return (0 until accountCount).map {
            Base58.encode(bytes.copyOfRange(i + it * 32, i + it * 32 + 32))
        }
    }

    /**
     * Pulls `(programId, data)` out of each instruction in a serialized unsigned transaction, so
     * tests can assert what was actually submitted without re-implementing the whole parser.
     */
    private fun decodeInstructions(unsignedTransactionHex: String): List<Pair<String, ByteArray>> {
        val bytes = unsignedTransactionHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        var i = 0
        fun byte(): Int = bytes[i++].toInt() and 0xFF

        val signatureCount = byte()
        i += signatureCount * 64
        i += 3 // header
        val accountCount = byte()
        val accounts = (0 until accountCount).map {
            Base58.encode(bytes.copyOfRange(i + it * 32, i + it * 32 + 32))
        }
        i += accountCount * 32
        i += 32 // blockhash

        val instructionCount = byte()
        return (0 until instructionCount).map {
            val programId = accounts[byte()]
            val accountIndexCount = byte()
            i += accountIndexCount
            val dataLength = byte()
            val data = bytes.copyOfRange(i, i + dataLength)
            i += dataLength
            programId to data
        }
    }

    private companion object {
        const val SIGNATURE = "2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAB7jTLd5p3KqJ7xQy9bniaP4q1hk2N1nF"

        /** A gap between two submits long enough for a timestamp read per submit to differ. */
        const val CLOCK_GAP_MS = 5L
    }

    @Test
    fun `getBalance on solana reads the node once when Turnkey lists no row and a failing read surfaces`() {
        val mint = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
        val reader = MockChainReader()
        val failure = RainError.TokenNotFound(mint, devnet)
        reader.balanceFailures += failure
        // Turnkey lists no assets, so the SPL read misses and falls to the node.
        val provider = makeProvider(client = MockTurnkeyClient(mockBalances = emptyList()), solanaReader = reader)

        val error = assertThrows(RainError.TokenNotFound::class.java) {
            runBlocking { provider.getBalance(devnet, Token.Contract(mint)) }
        }

        // One node read, and its failure is the caller's to see as the reader threw it: nothing
        // retries it and nothing rewraps it.
        assertThat(error).isSameInstanceAs(failure)
        assertThat(reader.balanceCalls).hasSize(1)
    }
}
