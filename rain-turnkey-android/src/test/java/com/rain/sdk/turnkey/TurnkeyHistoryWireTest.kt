package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.turnkey.crypto.generateP256KeyPair
import com.turnkey.http.TurnkeyClient
import com.turnkey.http.utils.ActivityPollerConfig
import com.turnkey.http.utils.TurnkeyHttpError
import com.turnkey.stamper.Stamper
import com.turnkey.types.TListEthTransactionHistoryBody
import com.turnkey.types.TListSolTransactionHistoryBody
import com.turnkey.types.TSolSendTransactionBody
import com.turnkey.types.V1ActivityStatus
import com.turnkey.types.V1Pagination
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * The indexed history queries on the wire, through the vendor's own client and stamper against a
 * mock server: what is posted, what a page decodes to, and what the client does with a refusal or a
 * page it cannot decode. These are the facts the provider's fallback rules rest on, so a vendor
 * release that changes them fails here first.
 */
class TurnkeyHistoryWireTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TurnkeyClientProtocol
    private lateinit var stamper: Stamper
    private lateinit var sessionPublicKey: String

    @Before
    fun setUp() {
        assumeJdk24()
        server = MockWebServer()
        server.start()
        // The stamper signs with an API-key-shaped P-256 pair, the shape a session key has once the
        // vendor rebuilt its client from it; nothing here needs a device keystore.
        val keyPair = generateP256KeyPair()
        sessionPublicKey = keyPair.publicKeyCompressed
        stamper = Stamper(keyPair.publicKeyCompressed, keyPair.privateKey)
        client = TurnkeyClientAdapter(
            TurnkeyClient(
                apiBaseUrl = server.url("/").toString().trimEnd('/'),
                stamper = stamper,
                organizationId = "org-1",
            )
        )
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    private fun solanaSendBody() = TSolSendTransactionBody(
        organizationId = "org-1",
        unsignedTransaction = "0100deadbeef",
        signWiths = listOf(SOLANA_SENDER),
        sponsor = true,
        caip2 = "solana:devnet",
    )

    private fun solanaActivityJson(status: String, result: String) =
        """
        {
          "activity": {
            "id": "activity-1",
            "organizationId": "org-1",
            "status": "$status",
            "type": "ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2",
            "intent": {"solSendTransactionIntentV2": {"unsignedTransaction": "0100deadbeef", "signWiths": ["$SOLANA_SENDER"], "sponsor": true, "caip2": "solana:devnet"}},
            "result": $result,
            "votes": [],
            "fingerprint": "fp",
            "canApprove": false,
            "canReject": false,
            "createdAt": {"seconds": "1700000000", "nanos": "0"},
            "updatedAt": {"seconds": "1700000000", "nanos": "0"}
          }
        }
        """.trimIndent()

    /**
     * The vendor client the module relies on since `com.turnkey:http` 2.1.1: the Solana send is posted
     * as the V2 activity type with the V2 body, through the vendor's own stamper. 2.1.0 posted the V1
     * type with this body and Turnkey refused it; a bump back below 2.1.1 fails here.
     */
    @Test
    fun `the vendor client posts the V2 Solana send activity type with the V2 body`() {
        server.enqueue(
            MockResponse().setBody(
                solanaActivityJson("ACTIVITY_STATUS_COMPLETED", """{"solSendTransactionResultV2": {"sendTransactionStatusId": "status-1"}}""")
            )
        )

        val activity = runBlocking { client.solSendTransaction(solanaSendBody()) }

        assertThat(activity.result.solSendTransactionResultV2?.sendTransactionStatusId).isEqualTo("status-1")
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/public/v1/submit/sol_send_transaction")
        val envelope = JSONObject(recorded.body.readUtf8())
        assertThat(envelope.getString("type")).isEqualTo("ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2")
        val parameters = envelope.getJSONObject("parameters")
        assertThat(parameters.getJSONArray("signWiths").getString(0)).isEqualTo(SOLANA_SENDER)
        assertThat(parameters.has("signWith")).isFalse()
        val stamp = JSONObject(String(Base64.getUrlDecoder().decode(recorded.getHeader("X-Stamp"))))
        assertThat(stamp.getString("publicKey")).isEqualTo(sessionPublicKey)
    }

    /**
     * The vendor behaviour the manager's send path rests on since `com.turnkey:http` 2.2.0: when the
     * activity is not completed after the vendor's poll, `solSendTransaction` throws
     * `TurnkeyHttpError.ActivityNotCompleted` carrying the activity it polled, so the send reads the id
     * and the status off it instead of looking the activity up. A bump below 2.2.0 throws a bare
     * `RuntimeException` without the activity and fails here. The request count pins the vendor's poll
     * loop as well (the submit, one poll, the final read): a count mismatch alone means the loop
     * changed, not the throw.
     */
    @Test
    fun `the vendor client throws its typed error carrying the activity on a Solana send without a result`() {
        val vendor = TurnkeyClient(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            stamper = stamper,
            organizationId = "org-1",
            activityPoller = ActivityPollerConfig(intervalMs = 0L, numRetries = 0),
        )
        // The submit, one delayed poll (numRetries 0 runs the loop once) and the vendor's final read.
        repeat(3) { server.enqueue(MockResponse().setBody(solanaActivityJson("ACTIVITY_STATUS_PENDING", "{}"))) }

        val error = assertThrows(TurnkeyHttpError.ActivityNotCompleted::class.java) {
            runBlocking { vendor.solSendTransaction(solanaSendBody()) }
        }

        assertThat(error.activity.id).isEqualTo("activity-1")
        assertThat(error.activity.status).isEqualTo(V1ActivityStatus.ACTIVITY_STATUS_PENDING)
        assertThat(error.path).isEqualTo("/public/v1/submit/sol_send_transaction")
        assertThat(TurnkeySendFailures.isSubmitRefusal(error)).isFalse()
        assertThat(TurnkeySendFailures.leavesTheSendAsItself(error)).isFalse()
        // The vendor held the activity and polled it by id before it threw it.
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(server.takeRequest().path).isEqualTo("/public/v1/submit/sol_send_transaction")
        repeat(2) {
            val poll = server.takeRequest()
            assertThat(poll.path).isEqualTo("/public/v1/query/get_activity")
            assertThat(JSONObject(poll.body.readUtf8()).getString("activityId")).isEqualTo("activity-1")
        }
    }

    private fun ethBody(limit: String = "25") = TListEthTransactionHistoryBody(
        organizationId = "org-1",
        address = "0xabc",
        caip2 = "eip155:84532",
        paginationOptions = V1Pagination(limit = limit),
    )

    private fun listEth() = runBlocking { client.listEthTransactionHistory(ethBody()) }

    @Test
    fun `eth request posts a stamped body to the query path`() {
        server.enqueue(MockResponse().setBody("""{"transactions":[]}"""))

        val response = listEth()

        assertThat(response.transactions).isEmpty()
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo(MockTurnkey.ETH_HISTORY_PATH)
        val body = JSONObject(recorded.body.readUtf8())
        assertThat(body.getString("organizationId")).isEqualTo("org-1")
        assertThat(body.getString("address")).isEqualTo("0xabc")
        assertThat(body.getString("caip2")).isEqualTo("eip155:84532")
        assertThat(body.getJSONObject("paginationOptions").getString("limit")).isEqualTo("25")
        // The stamp names the key the client was built from: the session's, never one read from storage.
        val stamp = JSONObject(String(Base64.getUrlDecoder().decode(recorded.getHeader("X-Stamp"))))
        assertThat(stamp.getString("publicKey")).isEqualTo(sessionPublicKey)
        assertThat(stamp.getString("signature")).isNotEmpty()
    }

    @Test
    fun `eth page decodes rows, transfers and display values, and ignores page metadata`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "transactions": [
                    {
                      "transactionHash": "0xhash",
                      "block": {"number": "123", "hash": "0xblock", "timestamp": "2026-08-12T10:00:00Z"},
                      "status": "CONFIRMED",
                      "origin": "TRANSACTION_ORIGIN_TURNKEY",
                      "from": "0xfrom",
                      "to": "0xto",
                      "fee": {"amount": "21", "caip19": "eip155:84532/slip44:60"},
                      "transfers": [
                        {
                          "direction": "OUT",
                          "asset": {"caip19": "eip155:84532/erc20:0xtoken", "symbol": "USDC", "name": "USD Coin", "decimals": 6},
                          "amount": "2500000",
                          "counterparty": "0xcounterparty",
                          "display": {"crypto": "2.5", "usd": "2.50"}
                        }
                      ],
                      "turnkey": {"sponsored": true, "activityFingerprint": "fp"},
                      "someFutureField": {"ignored": true}
                    }
                  ],
                  "pageInfo": {"hasNextPage": false, "endCursor": "cursor"}
                }
                """.trimIndent()
            )
        )

        val tx = listEth().transactions.single()

        assertThat(tx.transactionHash).isEqualTo("0xhash")
        assertThat(tx.block.number).isEqualTo("123")
        assertThat(tx.block.timestamp).isEqualTo("2026-08-12T10:00:00Z")
        assertThat(tx.status).isEqualTo("CONFIRMED")
        assertThat(tx.from).isEqualTo("0xfrom")
        assertThat(tx.to).isEqualTo("0xto")
        assertThat(tx.turnkey?.sponsored).isTrue()
        val transfer = tx.transfers.single()
        assertThat(transfer.direction).isEqualTo("OUT")
        assertThat(transfer.amount).isEqualTo("2500000")
        assertThat(transfer.counterparty).isEqualTo("0xcounterparty")
        assertThat(transfer.asset?.symbol).isEqualTo("USDC")
        assertThat(transfer.asset?.decimals).isEqualTo(6)
        assertThat(transfer.display?.crypto).isEqualTo("2.5")
        assertThat(transfer.display?.usd).isEqualTo("2.50")
    }

    @Test
    fun `sol request posts to the sol query path and decodes the signature`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "transactions": [
                    {
                      "signature": "5solSig",
                      "block": {"number": "9", "hash": "blockhash", "timestamp": "2026-08-12T11:00:00Z"},
                      "status": "FINALIZED",
                      "origin": "TRANSACTION_ORIGIN_EXTERNAL",
                      "feePayer": "FeePayer111",
                      "signers": [],
                      "fee": {"amount": "5000", "caip19": "solana:devnet/slip44:501"},
                      "transfers": []
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val response = runBlocking {
            client.listSolTransactionHistory(
                TListSolTransactionHistoryBody(
                    organizationId = "org-1",
                    address = "SolAddr111",
                    caip2 = "solana:devnet",
                    paginationOptions = V1Pagination(limit = "10"),
                )
            )
        }

        assertThat(server.takeRequest().path).isEqualTo(MockTurnkey.SOL_HISTORY_PATH)
        val tx = response.transactions.single()
        assertThat(tx.signature).isEqualTo("5solSig")
        assertThat(tx.feePayer).isEqualTo("FeePayer111")
        assertThat(tx.block.number).isEqualTo("9")
    }

    @Test
    fun `a refusal arrives as the status alone, the reason in the body is dropped by the vendor client`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"code":7,"message":"transaction history feature is not enabled for organization org-1"}""")
        )

        val error = assertThrows(RuntimeException::class.java) { listEth() }

        assertThat(TurnkeyErrorMapping.turnkeyHttpStatus(error)).isEqualTo(403)
        assertThat(TurnkeyErrorMapping.map(error)).isInstanceOf(RainError.Unauthorized::class.java)
        // Pinned so a vendor release that starts forwarding the body is noticed and the log line can carry it.
        assertThat(error.message).doesNotContain("not enabled")
    }

    @Test
    fun `a row without a block fails the whole page as a decode failure`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "transactions": [
                    {
                      "transactionHash": "0xpending",
                      "status": "PENDING",
                      "origin": "TRANSACTION_ORIGIN_TURNKEY",
                      "from": "0xfrom",
                      "fee": {"amount": "0", "caip19": "eip155:84532/slip44:60"},
                      "transfers": []
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val error = assertThrows(SerializationException::class.java) { listEth() }

        // Not an HTTP refusal: the provider surfaces it instead of falling back to the activity log.
        assertThat(TurnkeyErrorMapping.turnkeyHttpStatus(error)).isNull()
        assertThat(TurnkeyErrorMapping.map(error)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a page without the transactions field is a decode failure, not an empty history`() {
        server.enqueue(MockResponse().setBody("{}"))

        assertThrows(SerializationException::class.java) { listEth() }
    }

    @Test
    fun `a malformed body is a decode failure`() {
        server.enqueue(MockResponse().setBody("not json"))

        assertThrows(SerializationException::class.java) { listEth() }
    }

    @Test
    fun `quoted numeric decimals decode`() {
        // proto3-JSON may emit int64 values as strings.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "transactions": [
                    {
                      "transactionHash": "0xhash",
                      "block": {"number": "1", "hash": "0xblock", "timestamp": "2026-08-12T10:00:00Z"},
                      "status": "CONFIRMED",
                      "origin": "TRANSACTION_ORIGIN_EXTERNAL",
                      "from": "0xfrom",
                      "fee": {"amount": "1", "caip19": "eip155:1/slip44:60"},
                      "transfers": [
                        {
                          "direction": "OUT",
                          "asset": {"caip19": "eip155:1/erc20:0xt", "symbol": "USDC", "name": "USDC", "decimals": "6"},
                          "amount": "1",
                          "counterparty": "0xc"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val tx = listEth().transactions.single()

        assertThat(tx.transfers.single().asset?.decimals).isEqualTo(6)
    }

    private companion object {
        const val SOLANA_SENDER = "9C6hybhQ6Aycep9jaUnP6uL9ZYvDjUp1aSkFWPUFJtpj"
    }
}
