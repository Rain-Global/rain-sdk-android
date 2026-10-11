package com.rain.sdk.privy

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.NativeCurrency
import com.rain.sdk.models.RainTransactionCategory
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.privy.wallet.transactions.GetTransactionsParams
import io.privy.wallet.transactions.TransactionChain
import io.privy.wallet.transactions.TransactionDetails
import io.privy.wallet.transactions.TransactionStatus
import io.privy.wallet.transactions.TransactionType
import io.privy.wallet.transactions.TransactionsPage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import java.math.BigDecimal
import io.privy.wallet.transactions.Transaction as PrivyTransaction

class PrivyWalletProviderTest {

    @Test
    fun `sendNativeToken builds an eth value transfer with the chainId hex and no data`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        val txJson = slot<String>()
        coEvery { manager.sendTransaction(WALLET, RPC, capture(txJson)) } returns "0xHASH"

        val wallet = provider(manager)
        val hash = wallet.sendNativeToken(chainId = 1, toAddress = TO, amountInEth = BigDecimal.ONE)

        assertThat(hash).isEqualTo("0xHASH")
        val tx = JSONObject(txJson.captured)
        assertThat(tx.getString("from")).isEqualTo(WALLET)
        assertThat(tx.getString("to")).isEqualTo(TO)
        // 1 ETH = 1e18 wei = 0xde0b6b3a7640000
        assertThat(tx.getString("value")).isEqualTo("0xde0b6b3a7640000")
        assertThat(tx.getString("chainId")).isEqualTo("0x1")
        assertThat(tx.has("data")).isFalse()
    }

    @Test
    fun `ERC-20 approve calldata reaches Privy unchanged through the generic send path`() =
        runBlocking {
            // approve(0x5a6E...8f29, uint256 max) — the Auth Pull approval. Core encodes it and
            // hands it to the provider's generic sendTransaction; nothing here may rewrite it.
            val approveCalldata = "0x095ea7b3" +
                "0000000000000000000000005a6e6b0d5ea051cfff9b3dcc2aa8dac226458f29" +
                "f".repeat(64)
            val manager = mockk<PrivyManager>()
            coEvery { manager.getAddress(null) } returns WALLET
            val txJson = slot<String>()
            coEvery { manager.sendTransaction(WALLET, RPC, capture(txJson)) } returns "0xAPPROVE"

            val wallet = provider(manager)
            val hash = wallet.sendTransaction(
                chainId = 1,
                from = WALLET,
                to = CONTRACT,
                data = approveCalldata,
                value = "0x0"
            )

            assertThat(hash).isEqualTo("0xAPPROVE")
            val tx = JSONObject(txJson.captured)
            assertThat(tx.getString("to")).isEqualTo(CONTRACT)
            assertThat(tx.getString("value")).isEqualTo("0x0")
            assertThat(tx.getString("data")).isEqualTo(approveCalldata)
        }

    @Test
    fun `sendNativeToken scales the value by the registry's native decimals, not a fixed 18`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        val txJson = slot<String>()
        coEvery { manager.sendTransaction(WALLET, RPC, capture(txJson)) } returns "0xHASH"

        val wallet = provider(manager, nativeDecimals = 9)
        wallet.sendNativeToken(chainId = 1, toAddress = TO, amountInEth = BigDecimal.ONE)

        // 1 unit of a 9-decimal native currency = 1e9 base units = 0x3b9aca00
        assertThat(JSONObject(txJson.captured).getString("value")).isEqualTo("0x3b9aca00")
    }

    @Test
    fun `sendToken encodes an ERC-20 transfer to the contract with zero value`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        val txJson = slot<String>()
        coEvery { manager.sendTransaction(WALLET, RPC, capture(txJson)) } returns "0xHASH"

        val wallet = provider(manager)
        wallet.sendToken(
            chainId = 1,
            contractAddress = CONTRACT,
            toAddress = TO,
            amount = BigDecimal.ONE,
            decimals = 6,
        )

        val tx = JSONObject(txJson.captured)
        assertThat(tx.getString("to")).isEqualTo(CONTRACT)
        assertThat(tx.getString("value")).isEqualTo("0x0")
        // transfer(address,uint256) selector.
        assertThat(tx.getString("data")).startsWith("0xa9059cbb")
        // recipient in the first arg slot, then 1 * 10^6 = 0xf4240 base units.
        assertThat(tx.getString("data")).contains(TO.removePrefix("0x").lowercase())
        assertThat(tx.getString("data")).endsWith("f4240")
    }

    @Test
    fun `sendTransaction defaults an empty value to 0x0 and drops empty data`() = runBlocking {
        val manager = mockk<PrivyManager>()
        val txJson = slot<String>()
        coEvery { manager.sendTransaction(WALLET, RPC, capture(txJson)) } returns "0xHASH"

        val wallet = provider(manager)
        wallet.sendTransaction(chainId = 1, from = WALLET, to = TO, data = "0x", value = "")

        val tx = JSONObject(txJson.captured)
        assertThat(tx.getString("value")).isEqualTo("0x0")
        assertThat(tx.has("data")).isFalse()
    }

    @Test
    fun `sendTransaction simulates via eth_call before broadcasting`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.sendTransaction(WALLET, RPC, any()) } returns "0xHASH"
        val rpc = mockk<PrivyRpcClient>()
        val simulationParams = slot<List<Any>>()
        coEvery {
            rpc.callForHexResult(RPC, "eth_call", capture(simulationParams), RpcCallPurpose.SIMULATION)
        } returns "0x"

        val wallet = provider(manager, rpc)
        val hash = wallet.sendTransaction(chainId = 1, from = WALLET, to = TO, data = "0xabcdef", value = "0x1")

        assertThat(hash).isEqualTo("0xHASH")
        // The simulation call carries the same transaction object, against the latest block.
        val callObject = simulationParams.captured.first() as Map<*, *>
        assertThat(callObject["from"]).isEqualTo(WALLET)
        assertThat(callObject["to"]).isEqualTo(TO)
        assertThat(callObject["data"]).isEqualTo("0xabcdef")
        assertThat(callObject["value"]).isEqualTo("0x1")
        assertThat(simulationParams.captured.last()).isEqualTo("latest")
        coVerify(exactly = 1) { manager.sendTransaction(WALLET, RPC, any()) }
    }

    @Test
    fun `a failing eth_call simulation surfaces as TransactionSimulationFailed without broadcasting`() = runBlocking {
        val manager = mockk<PrivyManager>()
        val rpc = mockk<PrivyRpcClient>()
        coEvery {
            rpc.callForHexResult(RPC, "eth_call", any(), RpcCallPurpose.SIMULATION)
        } throws RuntimeException("execution reverted")

        val wallet = provider(manager, rpc)
        val error = runCatching {
            wallet.sendTransaction(chainId = 1, from = WALLET, to = TO, data = "0x", value = "0x1")
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
        coVerify(exactly = 0) { manager.sendTransaction(any(), any(), any()) }
    }

    @Test
    fun `an already-classified RainError from the eth_call preflight surfaces unchanged`() = runBlocking {
        // A transport failure during the preflight is retryable. Wrapped as a simulation
        // failure it would escalate to WithdrawalRevertedByNetwork and tell the host not to retry.
        for (classified in listOf(
            RainError.NetworkError("RPC request failed for eth_call"),
            RainError.TokenExpired(),
            // The node's verdict on the balance is the host's answer as it is (beta QA PV-SEND-01).
            RainError.InsufficientFunds()
        )) {
            val manager = mockk<PrivyManager>()
            val rpc = mockk<PrivyRpcClient>()
            coEvery {
                rpc.callForHexResult(RPC, "eth_call", any(), RpcCallPurpose.SIMULATION)
            } throws classified

            val wallet = provider(manager, rpc)
            val error = runCatching {
                wallet.sendTransaction(chainId = 1, from = WALLET, to = TO, data = "0x", value = "0x1")
            }.exceptionOrNull()

            assertThat(error).isSameInstanceAs(classified)
            coVerify(exactly = 0) { manager.sendTransaction(any(), any(), any()) }
        }
    }

    @Test
    fun `estimateTransactionFee multiplies gas limit by gas price`() = runBlocking {
        val manager = mockk<PrivyManager>()
        val rpc = mockk<PrivyRpcClient>()
        coEvery { rpc.callForHexResult(RPC, "eth_estimateGas", any(), RpcCallPurpose.SIMULATION) } returns "0x5208" // 21000
        coEvery { rpc.callForHexResult(RPC, "eth_gasPrice", any()) } returns "0x3b9aca00" // 1 gwei

        val wallet = PrivyWalletProvider(manager, mapOf(1 to RPC), mockk(), rpcClient = rpc)
        val fee = wallet.estimateTransactionFee(1, WALLET, TO, "0x", "0x0")

        // 21000 * 1e9 wei = 2.1e13 wei = 0.000021 ETH, exactly
        assertThat(fee).isEqualToIgnoringScale(java.math.BigDecimal("0.000021"))
    }

    @Test
    fun `getBalances keeps native and healthy tokens when one token read fails`() = runBlocking<Unit> {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        val tokenStore = mockk<TokenMetadataStore>()
        every { tokenStore.nativeCurrency(1) } returns NativeCurrency("ETH", "Ether", 18)
        coEvery { tokenStore.registeredTokens(1) } returns listOf(
            TokenInfo(chainId = 1, address = "0xUSDC", symbol = "USDC", decimals = 6, name = "USD Coin"),
            TokenInfo(chainId = 1, address = "0xBAD", symbol = "BAD", decimals = 18, name = "Bad"),
        )
        coEvery { tokenStore.tokenInfo(1, "0xUSDC") } returns TokenInfo(1, "0xUSDC", "USDC", 6, "USD Coin")
        coEvery { tokenStore.tokenInfo(1, "0xBAD") } returns TokenInfo(1, "0xBAD", "BAD", 18, "Bad")

        val rpc = mockk<PrivyRpcClient>()
        coEvery { rpc.callForHexResult(RPC, "eth_getBalance", any()) } returns "0x1"
        // eth_call routed by the "to" address inside the call object; the bad token throws.
        coEvery {
            rpc.callForHexResult(RPC, "eth_call", match<List<Any>> { callTarget(it) == "0xUSDC" })
        } returns "0x5"
        coEvery {
            rpc.callForHexResult(RPC, "eth_call", match<List<Any>> { callTarget(it) == "0xBAD" })
        } throws RuntimeException("rpc down for this token")

        val wallet = PrivyWalletProvider(manager, mapOf(1 to RPC), tokenStore, rpcClient = rpc)
        val balances = wallet.getBalances(1)

        // Native + USDC survive; the failing token is dropped, not fatal.
        assertThat(balances.map { it.token }).containsExactly(Token.Native, Token.Contract("0xUSDC"))
    }

    // ---------- getTransactions ----------

    @Test
    fun `getTransactions returns empty for a chain Privy does not index without calling Privy`() = runBlocking {
        val manager = mockk<PrivyManager>()

        val wallet = PrivyWalletProvider(manager, mapOf(UNINDEXED_CHAIN to RPC), mockk(), rpcClient = mockk())
        val result = wallet.getTransactions(chainId = UNINDEXED_CHAIN)

        assertThat(result).isEmpty()
        coVerify(exactly = 0) { manager.getTransactions(any(), any()) }
    }

    @Test
    fun `getTransactions queries Privy for Base Sepolia`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        coEvery { manager.getTransactions(WALLET, any()) } returns TransactionsPage(
            transactions = emptyList(),
            nextCursor = null,
        )
        val tokenStore = mockk<TokenMetadataStore>()
        coEvery { tokenStore.registeredTokens(BASE_SEPOLIA) } returns emptyList()

        val wallet = PrivyWalletProvider(manager, mapOf(BASE_SEPOLIA to RPC), tokenStore, rpcClient = mockk())
        wallet.getTransactions(chainId = BASE_SEPOLIA)

        coVerify { manager.getTransactions(WALLET, any()) }
    }

    @Test
    fun `getTransactions maps Privy transactions onto RainTransaction`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        coEvery { manager.getTransactions(WALLET, any()) } returns TransactionsPage(
            transactions = listOf(
                privyTransaction(
                    hash = "0xABC",
                    createdAt = 1_700_000_000_000,
                    privyTransactionId = "privy-tx-id",
                    details = privyDetails(asset = "usdc", rawValue = "1500000", rawValueDecimals = 6),
                )
            ),
            nextCursor = null,
        )

        val result = historyProvider(manager).getTransactions(chainId = 1)

        val tx = result.single()
        assertThat(tx.hash).isEqualTo("0xABC")
        assertThat(tx.from).isEqualTo(WALLET)
        assertThat(tx.to).isEqualTo(TO)
        assertThat(tx.value!!.compareTo(BigDecimal("1.5"))).isEqualTo(0)
        assertThat(tx.asset).isEqualTo("usdc")
        assertThat(tx.tokenAddress).isNull()
        assertThat(tx.chainId).isEqualTo(1)
        assertThat(tx.timestamp).isEqualTo("2023-11-14T22:13:20Z")
        assertThat(tx.category).isEqualTo(RainTransactionCategory.External)
        assertThat(tx.metadata?.status).isEqualTo("confirmed")
        assertThat(tx.metadata?.caip2).isEqualTo("eip155:1")
        assertThat(tx.metadata?.type).isEqualTo("transferSent")
    }

    @Test
    fun `getTransactions truncates a millisecond timestamp to whole seconds`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        coEvery { manager.getTransactions(WALLET, any()) } returns TransactionsPage(
            transactions = listOf(privyTransaction(createdAt = 1_700_000_000_987)),
            nextCursor = null,
        )

        val tx = historyProvider(manager).getTransactions(chainId = 1).single()

        assertThat(tx.timestamp).isEqualTo("2023-11-14T22:13:20Z")
    }

    @Test
    fun `getTransactions routes a contract-address asset to tokenAddress instead of symbol`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        coEvery { manager.getTransactions(WALLET, any()) } returns TransactionsPage(
            transactions = listOf(
                privyTransaction(details = privyDetails(asset = CONTRACT))
            ),
            nextCursor = null,
        )

        val tx = historyProvider(manager).getTransactions(chainId = 1).single()

        assertThat(tx.tokenAddress).isEqualTo(CONTRACT)
        assertThat(tx.asset).isNull()
        assertThat(tx.category).isEqualTo(RainTransactionCategory.Erc20)
    }

    @Test
    fun `getTransactions falls back to privyTransactionId when a pending transaction has no hash`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        coEvery { manager.getTransactions(WALLET, any()) } returns TransactionsPage(
            transactions = listOf(
                privyTransaction(hash = null, privyTransactionId = "privy-tx-1", status = TransactionStatus.Pending)
            ),
            nextCursor = null,
        )

        val tx = historyProvider(manager).getTransactions(chainId = 1).single()

        assertThat(tx.hash).isEqualTo("privy-tx-1")
        assertThat(tx.metadata?.status).isEqualTo("pending")
    }

    @Test
    fun `getTransactions follows the cursor until offset plus limit rows are collected`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        val params = mutableListOf<GetTransactionsParams<TransactionChain.Evm>>()
        coEvery { manager.getTransactions(WALLET, capture(params)) } returnsMany listOf(
            TransactionsPage(
                transactions = (0 until 3).map { privyTransaction(hash = "0xA$it", createdAt = 5_000L - it) },
                nextCursor = "cursor-1",
            ),
            TransactionsPage(
                transactions = (0 until 2).map { privyTransaction(hash = "0xB$it", createdAt = 2_000L - it) },
                nextCursor = "cursor-2",
            ),
        )

        val result = historyProvider(manager).getTransactions(chainId = 1, limit = 3, offset = 2)

        // Needs 5 rows: page one (3 rows, limit 5) then page two via cursor (2 rows, limit 2).
        assertThat(params).hasSize(2)
        assertThat(params[0].limit).isEqualTo(5)
        assertThat(params[0].cursor).isNull()
        assertThat(params[0].chain).isEqualTo(TransactionChain.Evm.Ethereum)
        assertThat(params[0].assets).containsExactly("eth")
        assertThat(params[0].tokens).isNull()
        assertThat(params[1].limit).isEqualTo(2)
        assertThat(params[1].cursor).isEqualTo("cursor-1")
        // Newest-first by default; offset 2 drops the two newest, limit 3 keeps the rest.
        assertThat(result.map { it.hash }).containsExactly("0xA2", "0xB0", "0xB1").inOrder()
    }

    @Test
    fun `getTransactions stops paging when history is exhausted and honors ASC order`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        coEvery { manager.getTransactions(WALLET, any()) } returns TransactionsPage(
            transactions = listOf(
                privyTransaction(hash = "0xNEW", createdAt = 2_000),
                privyTransaction(hash = "0xOLD", createdAt = 1_000),
            ),
            nextCursor = null,
        )

        val result = historyProvider(manager).getTransactions(chainId = 1, limit = 10, order = RainTransactionOrder.ASC)

        coVerify(exactly = 1) { manager.getTransactions(WALLET, any()) }
        assertThat(result.map { it.hash }).containsExactly("0xOLD", "0xNEW").inOrder()
    }

    @Test
    fun `getTransactions propagates Privy failures`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        coEvery { manager.getTransactions(WALLET, any()) } throws RuntimeException("indexer down")

        val error = runCatching { historyProvider(manager).getTransactions(chainId = 1) }.exceptionOrNull()

        assertThat(error).hasMessageThat().isEqualTo("indexer down")
    }

    @Test
    fun `getTransactions merges native and registered-token history and dedupes overlapping rows`() = runBlocking {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        val params = mutableListOf<GetTransactionsParams<TransactionChain.Evm>>()
        coEvery { manager.getTransactions(WALLET, capture(params)) } returnsMany listOf(
            TransactionsPage(
                transactions = listOf(privyTransaction(hash = "0xNATIVE", createdAt = 3_000)),
                nextCursor = null,
            ),
            TransactionsPage(
                transactions = listOf(
                    privyTransaction(hash = "0xTOKEN", createdAt = 2_000),
                    privyTransaction(hash = "0xNATIVE", createdAt = 3_000),
                ),
                nextCursor = null,
            ),
        )

        val usdc = TokenInfo(chainId = 1, address = CONTRACT, symbol = "USDC", decimals = 6, name = "USD Coin")
        val result = historyProvider(manager, tokens = listOf(usdc)).getTransactions(chainId = 1)

        // One native-asset query plus one token-address query; the duplicate row appears once.
        assertThat(params).hasSize(2)
        assertThat(params[0].assets).containsExactly("eth")
        assertThat(params[0].tokens).isNull()
        assertThat(params[1].assets).isNull()
        assertThat(params[1].tokens).containsExactly(CONTRACT)
        assertThat(result.map { it.hash }).containsExactly("0xNATIVE", "0xTOKEN").inOrder()
    }

    @Test
    fun `getTransactions fails the whole call when a token query fails instead of returning partial history`() = runBlocking<Unit> {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        coEvery {
            manager.getTransactions(WALLET, match<GetTransactionsParams<TransactionChain.Evm>> { it.assets != null })
        } returns TransactionsPage(
            transactions = listOf(privyTransaction(hash = "0xNATIVE")),
            nextCursor = null,
        )
        // PrivyManager surfaces mapped RainErrors for Privy vendor failures; that mapped error
        // must fail the whole call rather than being swallowed into native-only history.
        val mapped = RainError.ProviderError(RuntimeException("bad token filter"))
        coEvery {
            manager.getTransactions(WALLET, match<GetTransactionsParams<TransactionChain.Evm>> { it.tokens != null })
        } throws mapped

        val usdc = TokenInfo(chainId = 1, address = CONTRACT, symbol = "USDC", decimals = 6, name = "USD Coin")
        val error = runCatching {
            historyProvider(manager, tokens = listOf(usdc)).getTransactions(chainId = 1)
        }.exceptionOrNull()

        assertThat(error).isSameInstanceAs(mapped)
    }

    @Test
    fun `getTransactions surfaces an auth failure on a token query as TokenExpired`() = runBlocking<Unit> {
        val manager = mockk<PrivyManager>()
        coEvery { manager.getAddress(null) } returns WALLET
        coEvery {
            manager.getTransactions(WALLET, match<GetTransactionsParams<TransactionChain.Evm>> { it.assets != null })
        } returns TransactionsPage(
            transactions = listOf(privyTransaction(hash = "0xNATIVE")),
            nextCursor = null,
        )
        // What PrivyManager maps a Privy AuthenticationException to (see PrivyManagerTest).
        coEvery {
            manager.getTransactions(WALLET, match<GetTransactionsParams<TransactionChain.Evm>> { it.tokens != null })
        } throws RainError.TokenExpired()

        val usdc = TokenInfo(chainId = 1, address = CONTRACT, symbol = "USDC", decimals = 6, name = "USD Coin")
        val error = runCatching {
            historyProvider(manager, tokens = listOf(usdc)).getTransactions(chainId = 1)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.TokenExpired::class.java)
    }

    // privyTransactionId defaults to null so fixtures dedupe by their distinct hashes.
    private fun privyTransaction(
        hash: String? = "0xHASH",
        createdAt: Long = 1_700_000_000_000,
        status: TransactionStatus = TransactionStatus.Confirmed,
        privyTransactionId: String? = null,
        details: TransactionDetails? = privyDetails(),
    ) = PrivyTransaction(
        caip2 = "eip155:1",
        transactionHash = hash,
        userOperationHash = null,
        status = status,
        createdAt = createdAt,
        sponsored = false,
        privyTransactionId = privyTransactionId,
        walletId = "wallet-id",
        details = details,
    )

    private fun privyDetails(
        asset: String = "eth",
        rawValue: String = "1000000000000000000",
        rawValueDecimals: Int = 18,
    ) = TransactionDetails(
        type = TransactionType.TransferSent,
        sender = WALLET,
        senderPrivyUserId = null,
        recipient = TO,
        recipientPrivyUserId = null,
        chain = "ethereum",
        asset = asset,
        rawValue = rawValue,
        rawValueDecimals = rawValueDecimals,
        displayValues = emptyMap(),
    )

    private fun provider(
        manager: PrivyManager,
        rpc: PrivyRpcClient = simulationPassingRpc(),
        nativeDecimals: Int = 18,
    ): PrivyWalletProvider {
        val tokenStore = mockk<TokenMetadataStore>()
        every { tokenStore.nativeCurrency(1) } returns NativeCurrency("ETH", "Ether", nativeDecimals)
        return PrivyWalletProvider(manager, mapOf(1 to RPC), tokenStore, rpcClient = rpc)
    }

    /** A provider for history tests: token store seeded with [tokens], RPC never touched. */
    private fun historyProvider(manager: PrivyManager, tokens: List<TokenInfo> = emptyList()): PrivyWalletProvider {
        val tokenStore = mockk<TokenMetadataStore>()
        coEvery { tokenStore.registeredTokens(1) } returns tokens
        return PrivyWalletProvider(manager, mapOf(1 to RPC), tokenStore, rpcClient = mockk())
    }

    /** An RPC client whose pre-broadcast `eth_call` simulation always succeeds. */
    private fun simulationPassingRpc(): PrivyRpcClient {
        val rpc = mockk<PrivyRpcClient>()
        coEvery { rpc.callForHexResult(RPC, "eth_call", any(), RpcCallPurpose.SIMULATION) } returns "0x"
        return rpc
    }

    /** Pulls the `to` field out of an `eth_call` params list (first param is the call object). */
    private fun callTarget(params: List<Any>): String? =
        (params.firstOrNull() as? Map<*, *>)?.get("to") as? String

    private companion object {
        const val RPC = "https://rpc.example/1"
        const val WALLET = "0x000000000000000000000000000000000000dEaD"
        const val TO = "0x1111111111111111111111111111111111111111"
        const val CONTRACT = "0x2222222222222222222222222222222222222222"

        /** Avalanche Fuji — a chain Privy's transaction indexer does not support. */
        const val UNINDEXED_CHAIN = 43113
        const val BASE_SEPOLIA = 84532
    }
}
