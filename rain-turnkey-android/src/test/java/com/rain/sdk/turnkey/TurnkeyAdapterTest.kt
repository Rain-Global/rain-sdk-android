package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import com.rain.sdk.models.Token
import com.rain.sdk.provider.Capability
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Adapter-level tests for [TurnkeyWalletProvider] covering the RPC-bound paths not exercised
 * by [TurnkeyWalletProviderTest]: polling status transitions (failure / pending →
 * broadcasted), RPC fee estimation, network-failure mapping, and session/client resolution
 * edge cases.
 *
 * Gated on JDK 24+ because the Turnkey AAR is compiled to major class version 68 — see
 * [TurnkeyWalletFlowTest] for the same pattern. The whole
 * test class would fail to load on JDK 21 since its method/field signatures touch Turnkey
 * types directly — but JUnit reflects on `@Test` methods individually, so per-test `@Before`
 * gating still works for the parameter-free public methods below.
 */
class TurnkeyAdapterTest {

    private lateinit var rpc: MockRpcServer

    @Before
    fun requireJdk24() {
        assumeJdk24()
        rpc = MockRpcServer().also { it.start() }
    }

    @After
    fun tearDown() {
        if (::rpc.isInitialized) rpc.shutdown()
    }

    private fun makeProvider(
        turnkey: MockTurnkey = MockTurnkey(),
        walletAddressOverride: String? = null,
        chainId: Int = 1,
        sponsorGas: Boolean = false
    ): TurnkeyWalletProvider = turnkeyWalletProvider(
        turnkey = turnkey,
        rpcEndpoints = mapOf(chainId to rpc.urlFor(chainId)),
        walletAddressOverride = walletAddressOverride,
        httpClient = OkHttpClient(),
        // Zero out the 1s production polling delay so retry-based tests run in milliseconds
        // and regressions in failure detection fail fast instead of hanging for 30s.
        pollingIntervalMs = 0L,
        sponsorGas = sponsorGas
    )

    // ---- Polling: pending → broadcasted -----------------------------------------

    @Test
    fun `sendTransaction polls until status returns a tx hash`(): Unit = runBlocking {
        stubSendTransactionRPCs()
        val expectedHash = "0x" + "9".repeat(64)

        val turnkey = MockTurnkey()
        val client = (turnkey.turnkeyClient as MockTurnkeyClient).apply {
            sendTransactionStatusQueue = mutableListOf(
                MockTurnkeyClient.StatusFixture.pending(),
                MockTurnkeyClient.StatusFixture.broadcasted(expectedHash)
            )
        }
        val provider = makeProvider(turnkey)

        val txHash = provider.sendTransaction(
            chainId = 1,
            from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
            to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
            data = "0x",
            value = "0x0"
        )

        assertThat(txHash).isEqualTo(expectedHash)
        assertThat(client.sendTransactionStatusCalls).hasSize(2)
    }

    @Test
    fun `Turnkey broadcasts arbitrary ERC-20 approve calldata unchanged`(): Unit = runBlocking {
        // The Auth Pull capability question: Turnkey must accept approve calldata through its
        // generic signed-transaction path, with no adapter-side gating.
        stubSendTransactionRPCs()
        val approveCalldata = "0x095ea7b3" +
            "0000000000000000000000005a6e6b0d5ea051cfff9b3dcc2aa8dac226458f29" +
            "f".repeat(64)
        val expectedHash = "0x" + "c".repeat(64)

        val turnkey = MockTurnkey()
        val client = (turnkey.turnkeyClient as MockTurnkeyClient).apply {
            sendTransactionStatusQueue =
                mutableListOf(MockTurnkeyClient.StatusFixture.broadcasted(expectedHash))
        }
        val provider = makeProvider(turnkey)

        val txHash = provider.sendTransaction(
            chainId = 1,
            from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
            to = TurnkeyTestFixtures.TOKEN_ADDRESS,
            data = approveCalldata,
            value = "0x0"
        )

        assertThat(txHash).isEqualTo(expectedHash)
        assertThat(client.sendTransactionStatusCalls).hasSize(1)
    }

    // ---- Amount validation ---------------------------------------------------------

    @Test
    fun `sendNativeToken rejects a negative amount before contacting anything`() {
        // A negative amount would hex-encode as "0x-..." and surface as an opaque RPC error.
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        val provider = makeProvider(turnkey)

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                provider.sendNativeToken(1, TurnkeyTestFixtures.RECIPIENT_ADDRESS, java.math.BigDecimal("-1"))
            }
        }
        assertThat(client.ethSendTransactionCalls).isEmpty()
        assertThat(rpc.recordedMethods).isEmpty()
    }

    @Test
    fun `sendNativeToken rejects sub-wei precision as a typed error`() {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        val provider = makeProvider(turnkey)

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                provider.sendNativeToken(
                    1,
                    TurnkeyTestFixtures.RECIPIENT_ADDRESS,
                    java.math.BigDecimal("0.0000000000000000015")
                )
            }
        }
        assertThat(client.ethSendTransactionCalls).isEmpty()
    }

    // ---- Polling: timeout is pending, not failure ---------------------------------

    @Test
    fun `sendTransaction surfaces a poll timeout as TransactionPending carrying the status id`() {
        // Turnkey never reported failure, so the transaction may still confirm; the host needs
        // the status id to resume instead of resending (which could duplicate the transfer).
        stubSendTransactionRPCs()
        val turnkey = MockTurnkey()
        val client = (turnkey.turnkeyClient as MockTurnkeyClient).apply {
            sendTransactionStatusQueue = mutableListOf(MockTurnkeyClient.StatusFixture.pending())
        }
        val provider = makeProvider(turnkey)

        val ex = runCatching {
            runBlocking {
                provider.sendTransaction(
                    chainId = 1,
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(RainError.TransactionPending::class.java)
        assertThat((ex as RainError.TransactionPending).statusId).isEqualTo("send-status-id")
        // The full polling budget was spent before giving up.
        assertThat(client.sendTransactionStatusCalls).hasSize(30)
    }

    /**
     * Turnkey accepted the transaction before the status read failed, so any error but pending
     * would invite a resend of a live transfer. Not only session expiry: any failure.
     */
    @Test
    fun `sendTransaction surfaces a failed status read after submission as TransactionPending`() {
        stubSendTransactionRPCs()
        val turnkey = MockTurnkey()
        (turnkey.turnkeyClient as MockTurnkeyClient).sendTransactionStatusError =
            IllegalStateException("status service unavailable")
        val provider = makeProvider(turnkey)

        val ex = runCatching {
            runBlocking {
                provider.sendTransaction(
                    chainId = 1,
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(RainError.TransactionPending::class.java)
        assertThat((ex as RainError.TransactionPending).statusId).isEqualTo("send-status-id")
    }

    // ---- Polling: failure status path -------------------------------------------

    @Test
    fun `sendTransaction surfaces a failed status with revert details as TransactionSimulationFailed`() {
        // Turnkey decoded the revert (error.revertChain): the chain rejected the transaction,
        // the same fact a self-paid preflight catches, so withdrawals map it to RAIN_405.
        stubSendTransactionRPCs()
        val turnkey = MockTurnkey()
        (turnkey.turnkeyClient as MockTurnkeyClient).sendTransactionStatusQueue =
            mutableListOf(MockTurnkeyClient.StatusFixture.revertedOnChain(message = "reverted"))
        val provider = makeProvider(turnkey)

        val ex = runCatching {
            runBlocking {
                provider.sendTransaction(
                    chainId = 1,
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
        assertThat(ex?.cause?.message).contains("reverted")
    }

    @Test
    fun `sendTransaction surfaces a failed status with only txError as ProviderError`() {
        // A bare txError is any broadcast-or-confirm failure, not proof that the chain executed and
        // rejected the transaction, so it stays the provider's error (a withdrawal reads RAIN_501).
        stubSendTransactionRPCs()
        val turnkey = MockTurnkey()
        (turnkey.turnkeyClient as MockTurnkeyClient).sendTransactionStatusQueue =
            mutableListOf(MockTurnkeyClient.StatusFixture.failed(message = "nonce too low"))
        val provider = makeProvider(turnkey)

        val ex = runCatching {
            runBlocking {
                provider.sendTransaction(
                    chainId = 1,
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(ex?.cause?.message).contains("nonce too low")
    }

    @Test
    fun `sendTransaction surfaces an included transaction that reverted on chain as TransactionSimulationFailed`() {
        // Turnkey reports an on-chain revert on an INCLUDED status, hash and decoded revert together:
        // the revert wins over the hash, and the hash rides in the message so the host can look it up.
        stubSendTransactionRPCs()
        val hash = "0x" + "d".repeat(64)
        val turnkey = MockTurnkey()
        (turnkey.turnkeyClient as MockTurnkeyClient).sendTransactionStatusQueue =
            mutableListOf(MockTurnkeyClient.StatusFixture.includedButReverted(hash, message = "execution reverted"))
        val provider = makeProvider(turnkey)

        val ex = runCatching {
            runBlocking {
                provider.sendTransaction(
                    chainId = 1,
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
        assertThat(ex?.cause?.message).contains("execution reverted")
        assertThat(ex?.cause?.message).contains(hash)
        assertThat((ex as RainError.TransactionSimulationFailed).transactionId).isEqualTo(hash)
    }

    @Test
    fun `sendTransaction surfaces a failed status without details as ProviderError`() {
        // A rejection with no decoded execution failure (policy, submission) is the provider's.
        stubSendTransactionRPCs()
        val turnkey = MockTurnkey()
        (turnkey.turnkeyClient as MockTurnkeyClient).sendTransactionStatusQueue =
            mutableListOf(MockTurnkeyClient.StatusFixture(txStatus = "TX_STATUS_REJECTED"))
        val provider = makeProvider(turnkey)

        val ex = runCatching {
            runBlocking {
                provider.sendTransaction(
                    chainId = 1,
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(RainError.ProviderError::class.java)
    }

    // ---- ethSendTransaction error propagation -----------------------------------

    @Test
    fun `sendTransaction surfaces an ethSendTransaction failure as TransactionOutcomeUnknown`() {
        stubSendTransactionRPCs()
        val turnkey = MockTurnkey()
        (turnkey.turnkeyClient as MockTurnkeyClient).ethSendTransactionError =
            RuntimeException("turnkey rejected send")
        val provider = makeProvider(turnkey)

        val ex = runCatching {
            runBlocking {
                provider.sendTransaction(
                    chainId = 1,
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }.exceptionOrNull()
        // The vendor failure leaves the adapter as a RainError, never raw. It is neither a refusal of
        // the submit nor the vendor's typed not-completed error, so it may have followed acceptance and
        // lost the activity: the host sees TransactionOutcomeUnknown (RAIN_305), built around the
        // vendor's message but not its exception, so no layer reads a status off it and retries.
        assertThat(ex).isInstanceOf(RainError.TransactionOutcomeUnknown::class.java)
        assertThat(ex?.message).contains("turnkey rejected send")
        assertThat(ex?.message).contains("the send may still land")
        assertThat(ex?.cause).isNull()
    }

    // ---- estimateTransactionFee via RPC -----------------------------------------

    @Test
    fun `estimateTransactionFee multiplies gas estimate by gas price`(): Unit = runBlocking {
        rpc.stub(method = "eth_estimateGas", result = "0x5208") // 21000
        rpc.stub(method = "eth_gasPrice", result = "0x4a817c800") // 20 gwei = 20_000_000_000

        val provider = makeProvider()

        val fee = provider.estimateTransactionFee(
            chainId = 1,
            from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
            to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
            data = "0x",
            value = "0x0"
        )

        // 21000 * 20 gwei = 4.2e14 wei = 0.00042 ETH, exactly.
        assertThat(fee).isEqualToIgnoringScale(java.math.BigDecimal("0.00042"))
        assertThat(rpc.recordedMethods).containsAtLeast("eth_estimateGas", "eth_gasPrice")
    }

    @Test
    fun `estimateTransactionFee surfaces RPC network failure as NetworkError`() {
        rpc.stubNetworkFailure(method = "eth_estimateGas")

        val provider = makeProvider()

        assertThrows(RainError.NetworkError::class.java) {
            runBlocking {
                provider.estimateTransactionFee(
                    chainId = 1,
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }
    }

    // ---- getBalance contract via RPC (eth_call) ----------------------------------

    @Test
    fun `getBalance contract parses eth_call result using token decimals`(): Unit = runBlocking {
        // 1 USDC = 1_000_000 with 6 decimals
        rpc.stub(method = "eth_call", result = "0x0f4240")

        val provider = makeProvider()
        val balance = provider.getBalance(
            chainId = 1,
            token = Token.Contract(TurnkeyTestFixtures.USDC_ADDRESS)
        )

        // USDC is in the chain-1 registry (decimals 6), so no extra metadata RPC is needed.
        assertThat(balance.decimals).isEqualTo(6)
        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-12).of(1.0)
        assertThat(rpc.recordedMethods).containsExactly("eth_call")
    }

    @Test
    fun `getBalance contract maps RPC network failure to NetworkError`() {
        rpc.stubNetworkFailure(method = "eth_call")

        val provider = makeProvider()
        assertThrows(RainError.NetworkError::class.java) {
            runBlocking {
                provider.getBalance(
                    chainId = 1,
                    token = Token.Contract(TurnkeyTestFixtures.USDC_ADDRESS)
                )
            }
        }
    }

    // ---- native balance on an unsupported chain reads directly via RPC ----------

    @Test
    fun `getBalance native on unsupported chain reads via eth_getBalance`(): Unit = runBlocking {
        // 1 ETH in wei = 0xde0b6b3a7640000
        rpc.stub(method = "eth_getBalance", result = "0xde0b6b3a7640000")

        // 43113 (Avalanche Fuji) is outside BALANCE_API_CHAIN_IDS, so the balance read
        // falls through to the chain reader / RPC rather than the Turnkey indexer.
        val provider = makeProvider(chainId = 43113)

        val balance = provider.getBalance(chainId = 43113, token = Token.Native)
        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-12).of(1.0)
        assertThat(rpc.recordedMethods).contains("eth_getBalance")
    }

    // ---- Session / client missing → TokenExpired -------------------------------

    @Test
    fun `sendTransaction throws TokenExpired when turnkeyClient is missing`() {
        val turnkey = MockTurnkey(turnkeyClient = null)
        val provider = makeProvider(turnkey)
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                provider.sendTransaction(
                    chainId = 1,
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }
    }

    @Test
    fun `getTransactions throws TokenExpired when session missing`() {
        val turnkey = MockTurnkey(session = null)
        val provider = makeProvider(turnkey)
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { provider.getTransactions(chainId = 1) }
        }
    }

    // ---- getTransactions error propagation --------------------------------------

    @Test
    fun `getTransactions surfaces a getActivities failure as ProviderError`() {
        val turnkey = MockTurnkey()
        (turnkey.turnkeyClient as MockTurnkeyClient).getActivitiesError =
            RuntimeException("service unavailable")
        val provider = makeProvider(turnkey)

        val ex = runCatching {
            runBlocking { provider.getTransactions(chainId = 1) }
        }.exceptionOrNull()
        // Leaves as a RainError carrying the vendor failure, mapped at the session coordinator.
        assertThat(ex).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(ex?.cause?.message).contains("service unavailable")
    }

    // ---- signTypedData failure --------------------------------------------------

    @Test
    fun `signTypedData surfaces a signRawPayload failure as ProviderError`() {
        val turnkey = MockTurnkey()
        turnkey.signRawPayloadError =
            RuntimeException("hardware key denied")
        val provider = makeProvider(turnkey)

        val ex = runCatching {
            runBlocking {
                provider.signTypedData(
                    chainId = 1,
                    walletAddress = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    typedDataJson = "{}"
                )
            }
        }.exceptionOrNull()
        // The adapter is the boundary: a vendor failure leaves as a RainError, never unwrapped.
        // Pin the type so a refactor that lets a raw vendor exception escape fails this test
        // instead of silently passing.
        assertThat(ex).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(ex?.cause?.message).contains("hardware key denied")
    }

    // ---- Broadcast-chain gate + gas sponsorship ---------------------------------

    @Test
    fun `sendNativeToken on avalanche fails closed before contacting anything`() {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        val provider = makeProvider(turnkey, chainId = 43114)

        val error = assertThrows(RainError.ChainNotSupported::class.java) {
            runBlocking {
                provider.sendNativeToken(43114, TurnkeyTestFixtures.RECIPIENT_ADDRESS, java.math.BigDecimal.ONE)
            }
        }
        assertThat(error.chainId).isEqualTo(43114)
        assertThat(error.errorCode).isEqualTo(RainErrorCode.CHAIN_NOT_SUPPORTED)
        assertThat(client.ethSendTransactionCalls).isEmpty()
        assertThat(rpc.recordedMethods).isEmpty()
    }

    @Test
    fun `sendToken on an unlisted EVM chain fails with the published code`() {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        val provider = makeProvider(turnkey, chainId = 42220) // Celo: readable, never broadcastable

        val error = assertThrows(RainError.ChainNotSupported::class.java) {
            runBlocking {
                provider.sendToken(
                    chainId = 42220,
                    contractAddress = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
                    toAddress = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
                    amount = java.math.BigDecimal.ONE,
                    decimals = 6
                )
            }
        }
        assertThat(error.errorCode).isEqualTo(RainErrorCode.CHAIN_NOT_SUPPORTED)
        assertThat(client.ethSendTransactionCalls).isEmpty()
        assertThat(rpc.recordedMethods).isEmpty()
    }

    @Test
    fun `sendNativeToken on the solana testnet cluster fails closed`() {
        val provider = makeProvider(chainId = 902)
        assertThrows(RainError.ChainNotSupported::class.java) {
            runBlocking {
                provider.sendNativeToken(902, TurnkeyTestFixtures.RECIPIENT_ADDRESS, java.math.BigDecimal.ONE)
            }
        }
    }

    @Test
    fun `send carries sponsor=false when sponsorship is off`(): Unit = runBlocking {
        // sponsorGas defaults to true on TurnkeyConfig; a host on an organization without
        // sponsorship passes false and must get the full self-paid envelope back.
        stubSendTransactionRPCs()
        val expectedHash = "0x" + "a".repeat(64)
        val turnkey = MockTurnkey()
        val client = (turnkey.turnkeyClient as MockTurnkeyClient).apply {
            sendTransactionStatusQueue = mutableListOf(
                MockTurnkeyClient.StatusFixture.broadcasted(expectedHash)
            )
        }
        val provider = makeProvider(turnkey, sponsorGas = false)

        val hash = provider.sendNativeToken(1, TurnkeyTestFixtures.RECIPIENT_ADDRESS, java.math.BigDecimal("0.01"))

        assertThat(hash).isEqualTo(expectedHash)
        val body = client.ethSendTransactionCalls.single()
        assertThat(body.sponsor).isEqualTo(false)
        // The self-paid path keeps pinning its own envelope: nonce and fees stay client-filled.
        assertThat(body.nonce).isNotNull()
        assertThat(body.gasLimit).isNotNull()
        assertThat(body.maxFeePerGas).isNotNull()
        // And it never asks Turnkey for a gas-station nonce: that is a sponsored-only field.
        assertThat(body.gasStationNonce).isNull()
        assertThat(client.getNoncesCalls).isEmpty()
    }

    @Test
    fun `the raw send funnel is gated - unsupported chains fail before the vendor call`() {
        // withdrawCollateral, Auth Pull approvals, and host-composed sends reach the funnel
        // without passing the transfer entries; the funnel gate is what covers them.
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        val provider = makeProvider(turnkey, chainId = 43114)

        val error = assertThrows(RainError.ChainNotSupported::class.java) {
            runBlocking {
                provider.sendTransaction(
                    chainId = 43114,
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }
        assertThat(error.errorCode).isEqualTo(RainErrorCode.CHAIN_NOT_SUPPORTED)
        assertThat(client.ethSendTransactionCalls).isEmpty()
        assertThat(rpc.recordedMethods).isEmpty()
    }

    @Test
    fun `raw sends are sponsored when sponsorGas is on`(): Unit = runBlocking {
        // Withdrawals, approvals, and host-composed sends enter through the raw entry, and a
        // zero-balance user's first action is often an approval, so the raw entry follows the
        // flag with the same minimal payload as the transfer entries. Deliberately no RPC
        // stubs: any self-paid fee RPC would fail this send.
        val turnkey = MockTurnkey()
        val client = (turnkey.turnkeyClient as MockTurnkeyClient).apply {
            sendTransactionStatusQueue = mutableListOf(
                MockTurnkeyClient.StatusFixture.broadcasted("0x" + "d".repeat(64))
            )
        }
        val provider = makeProvider(turnkey, sponsorGas = true)

        provider.sendTransaction(
            chainId = 1,
            from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
            to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
            data = "0x",
            value = "0x0"
        )

        val body = client.ethSendTransactionCalls.single()
        assertThat(body.sponsor).isEqualTo(true)
        assertThat(body.nonce).isNull()
        assertThat(body.gasLimit).isNull()
        assertThat(body.gasStationNonce).isNotNull()
    }

    @Test
    fun `advertises gas sponsorship as a capability only when sponsorGas is on`() {
        // Core reads this to skip the self-paid Solana dry run when composing collateral
        // withdrawals this provider will sign; a self-paid provider must not advertise it.
        assertThat(makeProvider(sponsorGas = true).capabilities).contains(Capability.GAS_SPONSORSHIP)
        assertThat(makeProvider(sponsorGas = false).capabilities).doesNotContain(Capability.GAS_SPONSORSHIP)
    }

    @Test
    fun `sponsored fee estimate quotes the chain cost through eth_estimateGas and eth_gasPrice`(): Unit = runBlocking {
        // The estimate is what the send would cost on chain, so a host can show what sponsorship
        // saves; the sponsored send itself never charges the wallet.
        rpc.stub(method = "eth_estimateGas", result = "0x5208") // 21000
        rpc.stub(method = "eth_gasPrice", result = "0x4a817c800") // 20 gwei
        val provider = makeProvider(sponsorGas = true)

        val fee = provider.estimateTransactionFee(
            chainId = 1,
            from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
            to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
            data = "0x",
            value = "0x0"
        )

        assertThat(fee.compareTo(java.math.BigDecimal("0.00042"))).isEqualTo(0) // 21000 * 20 gwei
        assertThat(rpc.recordedMethods).containsAtLeast("eth_estimateGas", "eth_gasPrice")
        // The call object carries no fee field: nodes skip the fee-affordability check when no gas
        // price is sent, which is what keeps a zero-balance wallet's quote alive.
        val estimateBody = JSONObject(rpc.recordedBodies.last { it.contains("eth_estimateGas") })
        val estimateParams = estimateBody.getJSONArray("params").getJSONObject(0)
        assertThat(estimateParams.keys().asSequence().toList()).containsExactly("from", "to", "value")
    }

    @Test
    fun `sponsored send is a minimal payload - no nonce, no fees, no fee RPCs`(): Unit = runBlocking {
        // Deliberately NO stubSendTransactionRPCs(): if the sponsored path still called
        // eth_getTransactionCount / eth_estimateGas / eth_gasPrice, the unstubbed mock RPC
        // would fail this send. Passing proves the fee RPCs are skipped entirely.
        val turnkey = MockTurnkey()
        val client = (turnkey.turnkeyClient as MockTurnkeyClient).apply {
            mockGasStationNonce = "7"
            sendTransactionStatusQueue = mutableListOf(
                MockTurnkeyClient.StatusFixture.broadcasted("0x" + "b".repeat(64))
            )
        }
        val provider = makeProvider(turnkey, sponsorGas = true)

        val hash = provider.sendNativeToken(1, TurnkeyTestFixtures.RECIPIENT_ADDRESS, java.math.BigDecimal("0.01"))

        assertThat(hash).isEqualTo("0x" + "b".repeat(64))
        val body = client.ethSendTransactionCalls.single()
        assertThat(body.sponsor).isEqualTo(true)
        // Omitted fields are auto-filled by Turnkey's Gas Station; a client-computed account
        // nonce would pin the wrong account's sequence on the sponsored outer transaction.
        assertThat(body.nonce).isNull()
        assertThat(body.gasLimit).isNull()
        assertThat(body.maxFeePerGas).isNull()
        assertThat(body.maxPriorityFeePerGas).isNull()
        // Replay protection: the request carries Turnkey's gas-station nonce, fetched with one
        // Turnkey call (not a chain RPC) for this wallet on this chain.
        assertThat(body.gasStationNonce).isEqualTo("7")
        val nonceRequest = client.getNoncesCalls.single()
        assertThat(nonceRequest.address).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        assertThat(nonceRequest.caip2).isEqualTo("eip155:1")
        assertThat(nonceRequest.gasStationNonce).isEqualTo(true)
    }

    @Test
    fun `sponsored send does not broadcast when the gas station nonce cannot be fetched`() {
        // A sponsored request without its gas-station nonce would broadcast with no replay
        // protection, so a failed nonce lookup must stop the send before the vendor call.
        val turnkey = MockTurnkey()
        val client = (turnkey.turnkeyClient as MockTurnkeyClient).apply {
            getNoncesError = RuntimeException("nonce service unavailable")
        }
        val provider = makeProvider(turnkey, sponsorGas = true)

        val ex = runCatching {
            runBlocking {
                provider.sendNativeToken(1, TurnkeyTestFixtures.RECIPIENT_ADDRESS, java.math.BigDecimal("0.01"))
            }
        }.exceptionOrNull()

        assertThat(ex).hasMessageThat().contains("nonce service unavailable")
        assertThat(client.ethSendTransactionCalls).isEmpty()
    }

    @Test
    fun `sponsored fee estimate keeps the real quote on a read-only chain`(): Unit = runBlocking {
        // Sponsorship only applies where Turnkey can broadcast. A chain outside that list is
        // read-only here, so its fee quote must stay honest: a real RPC estimate, not zero.
        rpc.stub(method = "eth_estimateGas", result = "0x5208") // 21000
        rpc.stub(method = "eth_gasPrice", result = "0x4a817c800") // 20 gwei
        val provider = makeProvider(chainId = 43114, sponsorGas = true)

        val fee = provider.estimateTransactionFee(
            chainId = 43114,
            from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
            to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
            data = "0x",
            value = "0x0"
        )

        assertThat(fee.compareTo(java.math.BigDecimal.ZERO)).isGreaterThan(0)
        assertThat(rpc.recordedMethods).containsAtLeast("eth_estimateGas", "eth_gasPrice")
    }

    @Test
    fun `sponsored send still goes out when Turnkey returns no gas station nonce`(): Unit = runBlocking {
        // Lenient by design: a null nonce is omitted from the body, which falls back to Turnkey's
        // server-side fetch, rather than failing the send.
        val turnkey = MockTurnkey()
        val client = (turnkey.turnkeyClient as MockTurnkeyClient).apply {
            mockGasStationNonce = null
            sendTransactionStatusQueue = mutableListOf(
                MockTurnkeyClient.StatusFixture.broadcasted("0x" + "c".repeat(64))
            )
        }
        val provider = makeProvider(turnkey, sponsorGas = true)

        val hash = provider.sendNativeToken(1, TurnkeyTestFixtures.RECIPIENT_ADDRESS, java.math.BigDecimal("0.01"))

        assertThat(hash).isEqualTo("0x" + "c".repeat(64))
        val body = client.ethSendTransactionCalls.single()
        assertThat(body.sponsor).isEqualTo(true)
        assertThat(body.gasStationNonce).isNull()
        assertThat(client.getNoncesCalls).hasSize(1)
    }

    @Test
    fun `send support and fee sponsorship follow the registry and the flag`() {
        // The port hooks core calls before a withdrawal or approval: the registry decides where
        // Turnkey can broadcast, and sponsorship applies exactly there while the flag is on.
        val sponsored = makeProvider(sponsorGas = true)
        assertThat(sponsored.sponsorsFees(1)).isTrue()
        assertThat(sponsored.sponsorsFees(43114)).isFalse()
        sponsored.requireSendSupport(8453)
        val error = assertThrows(RainError.ChainNotSupported::class.java) {
            sponsored.requireSendSupport(43114)
        }
        assertThat(error.chainId).isEqualTo(43114)

        assertThat(makeProvider(sponsorGas = false).sponsorsFees(1)).isFalse()
    }

    // ---- helpers ----------------------------------------------------------------

    /** Stubs the three JSON-RPC calls made when building a Turnkey send-transaction body. */
    private fun stubSendTransactionRPCs() {
        rpc.stub(method = "eth_getTransactionCount", result = "0x1")
        rpc.stub(method = "eth_estimateGas", result = "0x5208") // 21000
        rpc.stub(method = "eth_gasPrice", result = "0x4a817c800") // 20 gwei
    }
}
