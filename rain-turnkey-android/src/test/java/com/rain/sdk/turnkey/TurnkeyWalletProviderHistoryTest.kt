package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.utils.validateAndChecksumAddress
import com.rain.sdk.models.RainTransactionCategory
import com.rain.sdk.models.RainTransactionOrder
import com.turnkey.types.TListEthTransactionHistoryResponse
import com.turnkey.types.TListSolTransactionHistoryResponse
import com.turnkey.types.V1EthTransactionHistoryItem
import com.turnkey.types.V1SolTransactionHistoryItem
import com.turnkey.types.V1TransactionHistoryAsset
import com.turnkey.types.V1TransactionHistoryBlock
import com.turnkey.types.V1TransactionHistoryDisplay
import com.turnkey.types.V1TransactionHistoryFee
import com.turnkey.types.V1TransactionHistoryTransfer
import com.turnkey.types.V1TransactionHistoryTurnkey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/**
 * Indexed history through the vendor client's `list_eth_transaction_history` and
 * `list_sol_transaction_history` queries, and the fallback to the activity log when they fail.
 */
class TurnkeyWalletProviderHistoryTest {

    @Before
    fun requireJdk24() = assumeJdk24()

    private val caip2Devnet = "solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1"

    // Indexer-supplied EVM addresses in the fixtures: all-digit hex, so the EIP-55 form is the string itself.
    private val sender = "0x1111111111111111111111111111111111111111"
    private val recipient = "0x2222222222222222222222222222222222222222"
    private val payer = "0x3333333333333333333333333333333333333333"
    private val minter = "0x4444444444444444444444444444444444444444"
    private val pool = "0x5555555555555555555555555555555555555555"
    private val wethContract = "0x6666666666666666666666666666666666666666"
    private val nftContract = "0x7777777777777777777777777777777777777777"

    private fun makeProvider(
        turnkey: MockTurnkey = MockTurnkey(),
        rpcEndpoints: Map<Int, String> = mapOf(1 to "https://eth.example/rpc")
    ): TurnkeyWalletProvider = turnkeyWalletProvider(
        turnkey = turnkey,
        rpcEndpoints = rpcEndpoints,
        httpClient = OkHttpClient(),
        chainReader = MockChainReader(),
        // No real sleeping: the transient-retry path is exercised below.
        sessionCoordinator = TurnkeySessionCoordinator(turnkey = turnkey, retryDelay = { }),
    )

    private fun clientOf(turnkey: MockTurnkey) = turnkey.turnkeyClient as MockTurnkeyClient

    /** A provider over a mock whose client answers the EVM history query with [rows]. */
    private fun evmProvider(vararg rows: V1EthTransactionHistoryItem): Pair<TurnkeyWalletProvider, MockTurnkeyClient> {
        val turnkey = MockTurnkey()
        clientOf(turnkey).mockEthHistory = TListEthTransactionHistoryResponse(transactions = rows.toList())
        return makeProvider(turnkey) to clientOf(turnkey)
    }

    /** A provider over a wallet with a Solana account whose client answers the Solana query with [rows]. */
    private fun solanaProvider(vararg rows: V1SolTransactionHistoryItem): Pair<TurnkeyWalletProvider, MockTurnkeyClient> {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))
        clientOf(turnkey).mockSolHistory = TListSolTransactionHistoryResponse(transactions = rows.toList())
        val provider = makeProvider(turnkey, rpcEndpoints = mapOf(RainChain.SOLANA_DEVNET to "https://sol.example/rpc"))
        return provider to clientOf(turnkey)
    }

    private fun block(number: String = "123", timestamp: String = "2026-08-12T10:00:00Z") =
        V1TransactionHistoryBlock(hash = "0xblock", number = number, timestamp = timestamp)

    private fun ethTransaction(
        hash: String = "0xhash",
        timestamp: String = "2026-08-12T10:00:00Z",
        blockNumber: String = "123",
        status: String = "CONFIRMED",
        from: String = sender,
        to: String? = recipient,
        transfers: List<V1TransactionHistoryTransfer> = emptyList(),
        sponsored: Boolean? = false
    ) = V1EthTransactionHistoryItem(
        block = block(blockNumber, timestamp),
        fee = V1TransactionHistoryFee(amount = "21000000000000", caip19 = "eip155:1/slip44:60"),
        from = from,
        origin = "TRANSACTION_ORIGIN_EXTERNAL",
        status = status,
        to = to,
        transactionHash = hash,
        transfers = transfers,
        turnkey = sponsored?.let { V1TransactionHistoryTurnkey(sponsored = it) }
    )

    @Suppress("LongParameterList") // fixture builder: one knob per field of the vendor row, all but two defaulted
    private fun solTransaction(
        signature: String,
        timestamp: String,
        blockNumber: String = "9",
        status: String = "FINALIZED",
        feePayer: String = MockTurnkey.DEFAULT_SOLANA_ADDRESS,
        transfers: List<V1TransactionHistoryTransfer> = emptyList(),
        sponsored: Boolean? = null
    ) = V1SolTransactionHistoryItem(
        block = block(blockNumber, timestamp),
        fee = V1TransactionHistoryFee(amount = "5000", caip19 = "$caip2Devnet/slip44:501"),
        feePayer = feePayer,
        origin = "TRANSACTION_ORIGIN_EXTERNAL",
        signature = signature,
        signers = emptyList(),
        status = status,
        transfers = transfers,
        turnkey = sponsored?.let { V1TransactionHistoryTurnkey(sponsored = it) }
    )

    private fun asset(caip19: String, symbol: String, decimals: Long, name: String = symbol) =
        V1TransactionHistoryAsset(caip19 = caip19, decimals = decimals, name = name, symbol = symbol)

    private fun transfer(
        direction: String,
        asset: V1TransactionHistoryAsset?,
        amount: String,
        counterparty: String,
        display: V1TransactionHistoryDisplay? = null
    ) = V1TransactionHistoryTransfer(
        amount = amount,
        asset = asset,
        counterparty = counterparty,
        direction = direction,
        display = display
    )

    private fun nativeOut(
        amount: String = "1500000000000000000",
        counterparty: String = recipient
    ) = transfer(
        direction = "OUT",
        asset = asset("eip155:1/slip44:60", "ETH", 18, name = "Ether"),
        amount = amount,
        counterparty = counterparty,
        display = V1TransactionHistoryDisplay(crypto = "1.5", usd = "5000.00")
    )

    // ---------- EVM mapping ----------

    @Test
    fun `evm native OUT transfer maps hash block value and counterparty`() = runBlocking<Unit> {
        val (provider, _) = evmProvider(ethTransaction(transfers = listOf(nativeOut())))

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.hash).isEqualTo("0xhash")
        assertThat(tx.uniqueId).isEqualTo("0xhash")
        assertThat(tx.blockNumber).isEqualTo("123")
        assertThat(tx.timestamp).isEqualTo("2026-08-12T10:00:00Z")
        // OUT is relative to the queried wallet, so the wallet is the sender.
        assertThat(tx.from).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        assertThat(tx.to).isEqualTo(recipient)
        assertThat(tx.value).isEqualTo(BigDecimal("1.5"))
        assertThat(tx.rawValue).isEqualTo("1500000000000000000")
        assertThat(tx.decimals).isEqualTo(18)
        assertThat(tx.asset).isEqualTo("ETH")
        assertThat(tx.tokenAddress).isNull()
        assertThat(tx.category).isEqualTo(RainTransactionCategory.External)
        assertThat(tx.chainId).isEqualTo(1)
        assertThat(tx.metadata?.caip2).isEqualTo("eip155:1")
        assertThat(tx.metadata?.status).isEqualTo("confirmed")
        assertThat(tx.metadata?.sponsored).isFalse()
        assertThat(tx.metadata?.type).isEqualTo("transferSent")
        assertThat(tx.metadata?.displayValues).containsExactly("crypto", "1.5", "usd", "5000.00")
    }

    @Test
    fun `evm IN transfer swaps counterparty into from and wallet into to`() = runBlocking {
        val incoming = transfer(
            direction = "IN",
            asset = asset("eip155:1/slip44:60", "ETH", 18),
            amount = "1000000000000000000",
            counterparty = payer
        )
        val (provider, _) = evmProvider(ethTransaction(from = payer, to = null, transfers = listOf(incoming)))

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.from).isEqualTo(payer)
        assertThat(tx.to).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        assertThat(tx.metadata?.type).isEqualTo("transferReceived")
    }

    @Test
    fun `evm erc20 transfer carries token address and erc20 category`() = runBlocking {
        val tokenOut = transfer(
            direction = "OUT",
            asset = asset("eip155:1/erc20:0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", 6),
            amount = "2500000",
            counterparty = recipient
        )
        val (provider, _) = evmProvider(ethTransaction(transfers = listOf(tokenOut)))

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.tokenAddress).isEqualTo("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48")
        assertThat(tx.category).isEqualTo(RainTransactionCategory.Erc20)
        assertThat(tx.value).isEqualTo(BigDecimal("2.5"))
        assertThat(tx.asset).isEqualTo("USDC")
    }

    @Test
    fun `evm row without transfers keeps transaction addresses and carries no amount`() = runBlocking {
        val (provider, _) = evmProvider(ethTransaction(transfers = emptyList()))

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.from).isEqualTo(sender)
        assertThat(tx.to).isEqualTo(recipient)
        assertThat(tx.value).isNull()
        assertThat(tx.rawValue).isNull()
        assertThat(tx.category).isEqualTo(RainTransactionCategory.External)
        assertThat(tx.metadata?.type).isNull()
    }

    @Test
    fun `evm large amount scales exactly without Double precision loss`() = runBlocking {
        val (provider, _) = evmProvider(ethTransaction(transfers = listOf(nativeOut(amount = "123456789012345678901"))))

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.value).isEqualTo(BigDecimal("123.456789012345678901"))
    }

    // ---------- request parameters ----------

    @Test
    fun `evm history request carries the session's organization wallet caip2 and clamped limit`() = runBlocking {
        val (provider, client) = evmProvider()

        provider.getTransactions(1, 7, 5, null)

        val request = client.listEthHistoryCalls.single()
        assertThat(request.organizationId).isEqualTo(MockTurnkey.DEFAULT_ORG_ID)
        assertThat(request.address).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        assertThat(request.caip2).isEqualTo("eip155:1")
        assertThat(request.paginationOptions?.limit).isEqualTo("12")
        assertThat(client.listSolHistoryCalls).isEmpty()
    }

    @Test
    fun `history limit defaults to 10 and caps at 100`() = runBlocking {
        val (provider, client) = evmProvider()

        provider.getTransactions(1, null, null, null)
        provider.getTransactions(1, 90, 40, null)

        assertThat(client.listEthHistoryCalls[0].paginationOptions?.limit).isEqualTo("10")
        assertThat(client.listEthHistoryCalls[1].paginationOptions?.limit).isEqualTo("100")
    }

    // ---------- ordering and slicing ----------

    @Test
    fun `rows sort DESC by default and honor ASC offset and limit`() = runBlocking<Unit> {
        val (provider, _) = evmProvider(
            ethTransaction(hash = "0xnewest", timestamp = "2026-08-12T12:00:00Z"),
            ethTransaction(hash = "0xoldest", timestamp = "2026-08-12T10:00:00Z"),
            ethTransaction(hash = "0xmiddle", timestamp = "2026-08-12T11:00:00Z")
        )

        val desc = provider.getTransactions(1, null, null, null)
        assertThat(desc.map { it.hash }).containsExactly("0xnewest", "0xmiddle", "0xoldest").inOrder()

        val asc = provider.getTransactions(1, null, null, RainTransactionOrder.ASC)
        assertThat(asc.map { it.hash }).containsExactly("0xoldest", "0xmiddle", "0xnewest").inOrder()

        val sliced = provider.getTransactions(1, 1, 1, RainTransactionOrder.DESC)
        assertThat(sliced.map { it.hash }).containsExactly("0xmiddle")
    }

    @Test
    fun `offset timestamps with explicit zone parse for sorting`() = runBlocking {
        val (provider, _) = evmProvider(
            ethTransaction(hash = "0xolder", timestamp = "2026-08-12T10:00:00+00:00"),
            ethTransaction(hash = "0xnewer", timestamp = "2026-08-12T11:00:00+00:00")
        )

        val desc = provider.getTransactions(1, null, null, null)
        assertThat(desc.map { it.hash }).containsExactly("0xnewer", "0xolder").inOrder()
    }

    @Test
    fun `unparsable timestamp sorts newest so the row stays on the first page`() = runBlocking<Unit> {
        val (provider, _) = evmProvider(
            ethTransaction(hash = "0xmined", timestamp = "2026-08-12T12:00:00Z"),
            ethTransaction(hash = "0xodd", timestamp = "not-a-timestamp")
        )

        val firstPage = provider.getTransactions(1, 1, null, null)

        assertThat(firstPage.single().hash).isEqualTo("0xodd")
        assertThat(firstPage.single().timestamp).isEqualTo("not-a-timestamp")
    }

    @Test
    fun `rows sharing a timestamp keep API order under DESC and reverse under ASC`() = runBlocking<Unit> {
        val (provider, _) = evmProvider(
            ethTransaction(hash = "0xfirstListed", timestamp = "2026-08-12T10:00:00Z"),
            ethTransaction(hash = "0xsecondListed", timestamp = "2026-08-12T10:00:00Z")
        )

        val desc = provider.getTransactions(1, null, null, RainTransactionOrder.DESC)
        assertThat(desc.map { it.hash }).containsExactly("0xfirstListed", "0xsecondListed").inOrder()

        val asc = provider.getTransactions(1, null, null, RainTransactionOrder.ASC)
        assertThat(asc.map { it.hash }).containsExactly("0xsecondListed", "0xfirstListed").inOrder()
    }

    // ---------- Solana ----------

    @Test
    fun `solana history maps signature native SOL and SPL rows`() = runBlocking {
        val wallet = MockTurnkey.DEFAULT_SOLANA_ADDRESS
        val (provider, client) = solanaProvider(
            solTransaction(
                signature = "5solSig",
                timestamp = "2026-08-12T11:00:00Z",
                blockNumber = "9",
                status = "FINALIZED",
                transfers = listOf(
                    transfer(
                        direction = "OUT",
                        asset = asset("$caip2Devnet/slip44:501", "SOL", 9),
                        amount = "1000000000",
                        counterparty = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
                    )
                ),
                sponsored = false
            ),
            solTransaction(
                signature = "5splSig",
                timestamp = "2026-08-12T10:00:00Z",
                blockNumber = "8",
                status = "CONFIRMED",
                transfers = listOf(
                    transfer(
                        direction = "IN",
                        asset = asset("$caip2Devnet/token:MintAddr111", "USDC", 6),
                        amount = "2500000",
                        counterparty = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
                    )
                )
            )
        )

        val txs = provider.getTransactions(RainChain.SOLANA_DEVNET, null, null, null)

        val request = client.listSolHistoryCalls.single()
        assertThat(request.address).isEqualTo(wallet)
        assertThat(request.caip2).isEqualTo(caip2Devnet)
        assertThat(client.listEthHistoryCalls).isEmpty()

        val native = txs[0]
        assertThat(native.hash).isEqualTo("5solSig")
        assertThat(native.from).isEqualTo(wallet)
        assertThat(native.to).isEqualTo(MockTurnkey.DEFAULT_SOLANA_RECIPIENT)
        assertThat(native.value).isEqualTo(BigDecimal("1"))
        assertThat(native.asset).isEqualTo("SOL")
        assertThat(native.tokenAddress).isNull()
        assertThat(native.category).isEqualTo(RainTransactionCategory.External)
        assertThat(native.metadata?.status).isEqualTo("finalized")

        val spl = txs[1]
        assertThat(spl.hash).isEqualTo("5splSig")
        assertThat(spl.from).isEqualTo(MockTurnkey.DEFAULT_SOLANA_RECIPIENT)
        assertThat(spl.to).isEqualTo(wallet)
        assertThat(spl.tokenAddress).isEqualTo("MintAddr111")
        assertThat(spl.category).isEqualTo(RainTransactionCategory.Token)
        assertThat(spl.value).isEqualTo(BigDecimal("2.5"))
        assertThat(spl.metadata?.type).isEqualTo("transferReceived")
    }

    @Test
    fun `erc721 transfer maps the collection address and erc721 category`() = runBlocking<Unit> {
        val nftIn = transfer(
            direction = "IN",
            asset = asset("eip155:1/erc721:$nftContract/1234", "COOL", 0),
            amount = "1",
            counterparty = minter
        )
        val (provider, _) = evmProvider(ethTransaction(transfers = listOf(nftIn)))

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.tokenAddress).isEqualTo(nftContract)
        assertThat(tx.category).isEqualTo(RainTransactionCategory.Erc721)
    }

    @Test
    fun `transfer without an asset keeps value null and rawValue set`() = runBlocking<Unit> {
        val unknownAsset = transfer(direction = "OUT", asset = null, amount = "12345", counterparty = recipient)
        val (provider, _) = evmProvider(ethTransaction(transfers = listOf(unknownAsset)))

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.value).isNull()
        assertThat(tx.decimals).isNull()
        assertThat(tx.asset).isNull()
        assertThat(tx.rawValue).isEqualTo("12345")
        assertThat(tx.category).isEqualTo(RainTransactionCategory.External)
    }

    @Test
    fun `multi-transfer row renders its first transfer only`() = runBlocking<Unit> {
        val swap = ethTransaction(
            transfers = listOf(
                nativeOut(amount = "1000000000000000000"),
                transfer(
                    direction = "IN",
                    asset = asset("eip155:1/erc20:$wethContract", "WETH", 18),
                    amount = "300000000000000000",
                    counterparty = pool
                )
            )
        )
        val (provider, _) = evmProvider(swap)

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.asset).isEqualTo("ETH")
        assertThat(tx.metadata?.type).isEqualTo("transferSent")
    }

    @Test
    fun `multi-word status maps to the privy-style camelCase vocabulary`() = runBlocking<Unit> {
        val (provider, _) = evmProvider(ethTransaction(status = "EXECUTION_REVERTED"))

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.metadata?.status).isEqualTo("executionReverted")
    }

    @Test
    fun `fractional timestamp is normalized to second-precision Zulu`() = runBlocking<Unit> {
        val (provider, _) = evmProvider(ethTransaction(timestamp = "2026-08-12T10:00:00.123Z"))

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.timestamp).isEqualTo("2026-08-12T10:00:00Z")
    }

    @Test
    fun `sponsored solana OUT reports the wallet as sender, not the fee payer`() = runBlocking<Unit> {
        val wallet = MockTurnkey.DEFAULT_SOLANA_ADDRESS
        val (provider, _) = solanaProvider(
            solTransaction(
                signature = "sponsoredSig",
                timestamp = "2026-08-13T18:31:47Z",
                feePayer = "SponsorFeePayer111",
                transfers = listOf(
                    transfer(
                        direction = "OUT",
                        asset = asset("$caip2Devnet/slip44:501", "SOL", 9),
                        amount = "1000000000",
                        counterparty = MockTurnkey.DEFAULT_SOLANA_RECIPIENT
                    )
                ),
                sponsored = true
            )
        )

        val tx = provider.getTransactions(RainChain.SOLANA_DEVNET, null, null, null).single()

        assertThat(tx.from).isEqualTo(wallet)
        assertThat(tx.to).isEqualTo(MockTurnkey.DEFAULT_SOLANA_RECIPIENT)
        assertThat(tx.metadata?.sponsored).isTrue()
    }

    @Test
    fun `hostile payload shapes render defensively instead of crashing or scaling absurdly`() = runBlocking<Unit> {
        val hostile = transfer(
            direction = "OUT",
            // Malformed CAIP-19: empty reference before a trailing slash; decimals beyond any token.
            asset = asset("eip155:1/erc20:/", "EVIL", 999_999_999),
            amount = "12345",
            counterparty = recipient
        )
        val (provider, _) = evmProvider(ethTransaction(transfers = listOf(hostile)))

        val tx = provider.getTransactions(1, null, null, null).single()

        assertThat(tx.tokenAddress).isNull()
        assertThat(tx.category).isEqualTo(RainTransactionCategory.External)
        assertThat(tx.value).isNull()
        assertThat(tx.decimals).isNull()
        assertThat(tx.rawValue).isEqualTo("12345")
    }

    @Test
    fun `evm addresses that are not addresses are dropped, valid ones surface in EIP-55 form`() = runBlocking<Unit> {
        val lowercase = "0xfedcbafedcbafedcbafedcbafedcbafedcbafedc"
        // One letter of a checksummed address lowercased: 40 hex characters whose checksum no longer matches.
        val wrongChecksum = "0xa0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
        val (provider, _) = evmProvider(
            ethTransaction(
                hash = "0xtoken",
                timestamp = "2026-08-12T12:00:00Z",
                transfers = listOf(
                    transfer(
                        direction = "OUT",
                        asset = asset("eip155:1/erc20:$wrongChecksum", "USDC", 6),
                        amount = "1",
                        counterparty = "0xnot-an-address"
                    )
                )
            ),
            ethTransaction(hash = "0xplain", timestamp = "2026-08-12T11:00:00Z", from = "not-an-address", to = lowercase),
            ethTransaction(hash = "0xnoaddresses", timestamp = "2026-08-12T10:00:00Z", from = "not-an-address", to = "0xnot-an-address")
        )

        val txs = provider.getTransactions(1, null, null, null)

        val tokenRow = txs.first { it.hash == "0xtoken" }
        assertThat(tokenRow.tokenAddress).isNull()
        // The dropped counterparty falls back to the transaction-level recipient.
        assertThat(tokenRow.to).isEqualTo(recipient)
        assertThat(tokenRow.rawValue).isEqualTo("1")
        assertThat(txs.first { it.hash == "0xnoaddresses" }.to).isNull()
        val plainRow = txs.first { it.hash == "0xplain" }
        // The sender is not optional on a row, so a value that is not an address stays as sent.
        assertThat(plainRow.from).isEqualTo("not-an-address")
        assertThat(plainRow.to).isEqualTo(validateAndChecksumAddress(lowercase, "to"))
        assertThat(plainRow.to).isNotEqualTo(lowercase)
    }

    @Test
    fun `blank counterparty falls back to transaction addresses instead of empty strings`() = runBlocking<Unit> {
        val wallet = MockTurnkey.DEFAULT_SOLANA_ADDRESS

        // Live shape: Turnkey sends counterparty as "" (not null) when it is unknown.
        fun blankTransfer(direction: String) = transfer(
            direction = direction,
            asset = asset("$caip2Devnet/token:Mint111", "USDC", 6),
            amount = "1000000",
            counterparty = ""
        )
        val (provider, _) = solanaProvider(
            solTransaction(signature = "inSig", timestamp = "2026-08-13T18:31:47Z", transfers = listOf(blankTransfer("IN"))),
            solTransaction(signature = "outSig", timestamp = "2026-08-13T18:14:24Z", transfers = listOf(blankTransfer("OUT")))
        )

        val txs = provider.getTransactions(RainChain.SOLANA_DEVNET, null, null, null)

        val incoming = txs.first { it.hash == "inSig" }
        assertThat(incoming.from).isEqualTo(wallet)
        assertThat(incoming.to).isEqualTo(wallet)
        val outgoing = txs.first { it.hash == "outSig" }
        assertThat(outgoing.from).isEqualTo(wallet)
        assertThat(outgoing.to).isNull()
    }

    // ---------- fallback ----------

    @Test
    fun `history failure falls back to the activity log`() = runBlocking {
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeActivity(
                    id = "activity-1",
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = recipient,
                    caip2 = "eip155:1",
                    value = "1000000000000000000",
                    data = "0x",
                    sendTransactionStatusId = "status-1"
                )
            )
        )
        client.listEthHistoryError = MockTurnkey.httpError(MockTurnkey.ETH_HISTORY_PATH, 403)
        val provider = makeProvider(MockTurnkey(turnkeyClient = client))

        val txs = provider.getTransactions(1, null, null, null)

        assertThat(client.listEthHistoryCalls).hasSize(1)
        assertThat(client.getActivitiesCalls).hasSize(1)
        assertThat(txs.single().uniqueId).isEqualTo("activity-1")
        assertThat(txs.single().value).isEqualTo(BigDecimal("1"))
    }

    @Test
    fun `a 5xx on the indexed query is retried and then falls back to the activity log`() = runBlocking {
        val client = MockTurnkeyClient(
            mockActivities = listOf(
                MockTurnkey.makeActivity(
                    id = "activity-1",
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = recipient,
                    caip2 = "eip155:1",
                    value = "1000000000000000000",
                    data = "0x",
                    sendTransactionStatusId = "status-1"
                )
            )
        )
        client.listEthHistoryError = MockTurnkey.httpError(MockTurnkey.ETH_HISTORY_PATH, 503)
        val provider = makeProvider(MockTurnkey(turnkeyClient = client))

        val txs = provider.getTransactions(1, null, null, null)

        // The default policy retries a transient status twice before the coordinator gives up.
        assertThat(client.listEthHistoryCalls).hasSize(3)
        assertThat(client.getActivitiesCalls).hasSize(1)
        assertThat(txs.single().uniqueId).isEqualTo("activity-1")
    }

    @Test
    fun `a transport failure on the indexed query surfaces without consulting the activity log`() {
        val client = MockTurnkeyClient()
        client.listEthHistoryError = IOException("connection reset")
        val provider = makeProvider(MockTurnkey(turnkeyClient = client))

        val error = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { provider.getTransactions(1, null, null, null) }
        }

        assertThat(error.cause).isInstanceOf(IOException::class.java)
        // Retried by the coordinator, then surfaced: the activity path would fail the same way.
        assertThat(client.listEthHistoryCalls).hasSize(3)
        assertThat(client.getActivitiesCalls).isEmpty()
    }

    @Test
    fun `a page the client cannot decode surfaces as a provider failure, not as a shorter history`() {
        val client = MockTurnkeyClient()
        client.listEthHistoryError = SerializationException("Field 'block' is required for type 'V1EthTransactionHistoryItem'")
        val provider = makeProvider(MockTurnkey(turnkeyClient = client))

        assertThrows(RainError.ProviderError::class.java) {
            runBlocking { provider.getTransactions(1, null, null, null) }
        }

        assertThat(client.listEthHistoryCalls).hasSize(1)
        assertThat(client.getActivitiesCalls).isEmpty()
    }

    @Test
    fun `the history feature gate is logged once at info level, not once per page`() = runBlocking {
        // The default mock client answers the way an organization without the feature does: HTTP 403.
        val provider = makeProvider(MockTurnkey())
        val entries = capturingLogs {
            runBlocking {
                provider.getTransactions(1, null, null, null)
                provider.getTransactions(1, null, null, null)
            }
        }

        val gate = entries.filter { it.second.contains("indexed transaction history is not enabled") }
        assertThat(gate).hasSize(1)
        assertThat(gate.single().first).isEqualTo(android.util.Log.INFO)
        assertThat(entries.none { it.second.contains("falling back") }).isTrue()
    }

    @Test
    fun `history success does not touch the activity log`() = runBlocking {
        val (provider, client) = evmProvider(ethTransaction())

        provider.getTransactions(1, null, null, null)

        assertThat(client.getActivitiesCalls).isEmpty()
    }

    @Test
    fun `missing session throws TokenExpired without consulting the activity log`() {
        val client = MockTurnkeyClient()
        val provider = makeProvider(MockTurnkey(session = null, turnkeyClient = client))

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { provider.getTransactions(1, null, null, null) }
        }
        assertThat(client.listEthHistoryCalls).isEmpty()
        assertThat(client.getActivitiesCalls).isEmpty()
    }
}
