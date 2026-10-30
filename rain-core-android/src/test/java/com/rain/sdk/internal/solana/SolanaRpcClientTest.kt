package com.rain.sdk.internal.solana

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.constants.SolanaPrograms
import com.rain.sdk.internal.helpers.MockRpcServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * Covers the account reads and simulation an SPL transfer depends on.
 *
 * The fixtures mirror what a cluster actually returns for `jsonParsed` encoding, including the
 * shapes that are easy to mishandle: a `value` of JSON null for an account that does not exist,
 * and a `data` *array* (rather than an object) for accounts the cluster cannot parse.
 */
class SolanaRpcClientTest {

    private lateinit var rpc: MockRpcServer
    private val devnet = RainChain.SOLANA_DEVNET
    private val mint = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    private val wallet = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private val tokenAccount = "H9CfPZKZBBnpFQEwCu5F3Fkm1D9Wj5oL8UnZKPKmvRZa"

    @Before
    fun setUp() {
        rpc = MockRpcServer().also { it.start() }
    }

    @After
    fun tearDown() = rpc.shutdown()

    private fun client() = SolanaRpcClient()
    private fun url() = rpc.urlFor(devnet)

    // ---------- getMinimumBalanceForRentExemption ----------

    @Test
    fun `reads the cluster's rent-exempt minimum for the account size it is asked about`(): Unit = runBlocking {
        rpc.stubObject("getMinimumBalanceForRentExemption", 1_488_440L)

        assertThat(client().getMinimumBalanceForRentExemption(url(), 165)).isEqualTo(BigInteger.valueOf(1_488_440L))
        assertThat(rpc.recordedBodies.single()).contains("[165]")
    }

    // ---------- getAccountInfo ----------

    @Test
    fun `returns null for an address that holds no account`(): Unit = runBlocking {
        rpc.stubObject("getAccountInfo", contextual(JSONObject.NULL))

        assertThat(client().getAccountInfo(url(), wallet)).isNull()
        assertThat(client().accountExists(url(), wallet)).isFalse()
    }

    @Test
    fun `parses a plain wallet whose data is an unparsed array`(): Unit = runBlocking {
        // A system account has no parsed representation: `data` comes back as [base64, encoding].
        val value = JSONObject()
            .put("owner", SolanaPrograms.SYSTEM_ADDRESS)
            .put("lamports", 2_000_000_000L)
            .put("data", JSONArray().put("").put("base64"))
        rpc.stubObject("getAccountInfo", contextual(value))

        val account = client().getAccountInfo(url(), wallet)

        assertThat(account).isNotNull()
        assertThat(account!!.ownerProgram).isEqualTo(SolanaPrograms.SYSTEM_ADDRESS)
        assertThat(account.parsedType).isNull()
        assertThat(account.parsedInfo).isNull()
    }

    // ---------- getMintInfo ----------

    @Test
    fun `reads decimals and the owning token program from a mint`(): Unit = runBlocking {
        rpc.stubObject("getAccountInfo", contextual(mintValue(decimals = 6)))

        val info = client().getMintInfo(url(), mint)

        assertThat(info).isNotNull()
        assertThat(info!!.decimals).isEqualTo(6)
        assertThat(info.tokenProgram).isEqualTo(SolanaPrograms.TOKEN_ADDRESS)
    }

    @Test
    fun `recognises a token-2022 mint by its owning program`(): Unit = runBlocking {
        rpc.stubObject(
            "getAccountInfo",
            contextual(mintValue(decimals = 9, program = SolanaPrograms.TOKEN_2022_ADDRESS))
        )

        val info = client().getMintInfo(url(), mint)

        // The owning program decides both the transfer target and the derived account address,
        // so it must be reported as-is rather than defaulted to the original token program.
        assertThat(info!!.tokenProgram).isEqualTo(SolanaPrograms.TOKEN_2022_ADDRESS)
        assertThat(info.decimals).isEqualTo(9)
    }

    @Test
    fun `returns null when the address is not a mint`(): Unit = runBlocking {
        // A wallet, not a mint.
        rpc.stubObject(
            "getAccountInfo",
            contextual(
                JSONObject()
                    .put("owner", SolanaPrograms.SYSTEM_ADDRESS)
                    .put("data", JSONArray().put("").put("base64"))
            )
        )
        assertThat(client().getMintInfo(url(), wallet)).isNull()

        // A token account is owned by the token program but is not a mint either.
        rpc.stubObject("getAccountInfo", contextual(tokenAccountValue(amount = "1", decimals = 6)))
        assertThat(client().getMintInfo(url(), tokenAccount)).isNull()
    }

    @Test
    fun `returns null when the mint account does not exist`(): Unit = runBlocking {
        rpc.stubObject("getAccountInfo", contextual(JSONObject.NULL))
        assertThat(client().getMintInfo(url(), mint)).isNull()
    }

    // ---------- getTokenAccount ----------

    @Test
    fun `reads a token account balance as exact base units`(): Unit = runBlocking {
        // Larger than Long.MAX_VALUE would be, to prove the string is parsed rather than a number.
        rpc.stubObject(
            "getAccountInfo",
            contextual(tokenAccountValue(amount = "18446744073709551615", decimals = 6))
        )

        val account = client().getTokenAccount(url(), tokenAccount)

        assertThat(account).isNotNull()
        assertThat(account!!.amount).isEqualTo(BigInteger("18446744073709551615"))
        assertThat(account.decimals).isEqualTo(6)
        assertThat(account.mint).isEqualTo(mint)
        assertThat(account.owner).isEqualTo(wallet)
    }

    @Test
    fun `returns null for a token account that has not been created`(): Unit = runBlocking {
        rpc.stubObject("getAccountInfo", contextual(JSONObject.NULL))
        assertThat(client().getTokenAccount(url(), tokenAccount)).isNull()
    }

    @Test
    fun `distinguishes accounts by address`(): Unit = runBlocking {
        rpc.stubObjectFor("getAccountInfo", mint, contextual(mintValue(decimals = 6)))
        rpc.stubObjectFor(
            "getAccountInfo",
            tokenAccount,
            contextual(tokenAccountValue(amount = "500", decimals = 6))
        )
        rpc.stubObjectFor("getAccountInfo", wallet, contextual(JSONObject.NULL))

        assertThat(client().getMintInfo(url(), mint)!!.decimals).isEqualTo(6)
        assertThat(client().getTokenAccount(url(), tokenAccount)!!.amount)
            .isEqualTo(BigInteger.valueOf(500))
        assertThat(client().accountExists(url(), wallet)).isFalse()
    }

    @Test
    fun `fails loudly on a token account with no tokenAmount`() {
        val malformed = tokenAccountValue(amount = "1", decimals = 6).also {
            it.getJSONObject("data").getJSONObject("parsed").getJSONObject("info")
                .remove("tokenAmount")
        }
        rpc.stubObject("getAccountInfo", contextual(malformed))

        assertThrows(RainError.InternalError::class.java) {
            runBlocking { client().getTokenAccount(url(), tokenAccount) }
        }
    }

    // ---------- getTokenAccountsByOwner ----------

    @Test
    fun `enumeration skips a malformed token account and keeps the rest`(): Unit = runBlocking {
        val good = JSONObject()
            .put("pubkey", tokenAccount)
            .put("account", tokenAccountValue(amount = "500", decimals = 6))
        val malformed = JSONObject()
            .put("pubkey", wallet)
            .put(
                "account",
                tokenAccountValue(amount = "1", decimals = 6).also {
                    it.getJSONObject("data").getJSONObject("parsed").getJSONObject("info")
                        .remove("tokenAmount")
                }
            )
        rpc.stubObject(
            "getTokenAccountsByOwner",
            contextual(JSONArray().put(good).put(malformed))
        )

        val accounts = client().getTokenAccountsByOwner(url(), wallet, SolanaPrograms.TOKEN_ADDRESS)

        assertThat(accounts).hasSize(1)
        assertThat(accounts.single().address).isEqualTo(tokenAccount)
        assertThat(accounts.single().amount).isEqualTo(BigInteger.valueOf(500))
    }

    // ---------- simulateTransaction ----------

    @Test
    fun `reports a successful simulation`(): Unit = runBlocking {
        rpc.stubObject(
            "simulateTransaction",
            contextual(
                JSONObject()
                    .put("err", JSONObject.NULL)
                    .put("logs", JSONArray().put("Program Tokenkeg... success"))
                    .put("unitsConsumed", 4_500)
            )
        )

        val simulation = client().simulateTransaction(url(), "AQAB")

        assertThat(simulation.succeeded).isTrue()
        assertThat(simulation.error).isNull()
        assertThat(simulation.logs).hasSize(1)
    }

    @Test
    fun `surfaces the program error and logs from a failed simulation`(): Unit = runBlocking {
        rpc.stubObject(
            "simulateTransaction",
            contextual(
                JSONObject()
                    .put("err", JSONObject().put("InstructionError", JSONArray().put(0).put("Custom")))
                    .put(
                        "logs",
                        JSONArray()
                            .put("Program log: Error: insufficient funds")
                            .put("Program Tokenkeg... failed")
                    )
            )
        )

        val simulation = client().simulateTransaction(url(), "AQAB")

        assertThat(simulation.succeeded).isFalse()
        assertThat(simulation.error).contains("InstructionError")
        assertThat(simulation.logs).hasSize(2)
    }

    @Test
    fun `fails loudly on a malformed simulation response`() {
        rpc.stubObject("simulateTransaction", JSONObject().put("context", JSONObject()))
        assertThrows(RainError.InternalError::class.java) {
            runBlocking { client().simulateTransaction(url(), "AQAB") }
        }
    }

    // ---------- signatures ----------

    @Test
    fun `getSignaturesSince scans newer than the baseline, newest first`(): Unit = runBlocking {
        rpc.stubObject(
            "getSignaturesForAddress",
            JSONArray()
                .put(JSONObject().put("signature", "newest").put("slot", 151))
                .put(JSONObject().put("signature", "older").put("slot", 150))
        )

        val signatures = client().getSignaturesSince(url(), wallet, until = "baseline")

        assertThat(signatures).containsExactly("newest", "older").inOrder()
        assertThat(rpc.recordedBodies.single()).contains("\"until\":\"baseline\"")
    }

    @Test
    fun `getSignaturesSince omits the cursor for a wallet with no history`(): Unit = runBlocking {
        rpc.stubObject("getSignaturesForAddress", JSONArray())

        val signatures = client().getSignaturesSince(url(), wallet, until = null)

        assertThat(signatures).isEmpty()
        assertThat(rpc.recordedBodies.single()).doesNotContain("until")
    }

    // ---------- getTransaction ----------

    @Test
    fun `getTransaction reads the fee payer and a clean meta`(): Unit = runBlocking {
        rpc.stubObject("getTransaction", transactionValue(feePayer = wallet, err = JSONObject.NULL))

        val record = client().getTransaction(url(), "sig")

        assertThat(record?.feePayer).isEqualTo(wallet)
        assertThat(record?.error).isNull()
        assertThat(record?.succeeded).isTrue()
        assertThat(rpc.recordedBodies.single()).contains("\"commitment\":\"confirmed\"")
        assertThat(rpc.recordedBodies.single()).contains("\"maxSupportedTransactionVersion\":0")
    }

    @Test
    fun `getTransaction surfaces an on-chain error`(): Unit = runBlocking {
        val err = JSONObject().put("InstructionError", JSONArray().put(0).put("InsufficientFunds"))
        rpc.stubObject("getTransaction", transactionValue(feePayer = wallet, err = err))

        val record = client().getTransaction(url(), "sig")

        assertThat(record?.succeeded).isFalse()
        assertThat(record?.error).contains("InsufficientFunds")
    }

    @Test
    fun `getTransaction accepts jsonParsed account keys`(): Unit = runBlocking {
        val value = transactionValue(feePayer = wallet, err = JSONObject.NULL)
        value.getJSONObject("transaction").getJSONObject("message").put(
            "accountKeys",
            JSONArray().put(JSONObject().put("pubkey", wallet).put("signer", true).put("writable", true))
        )
        rpc.stubObject("getTransaction", value)

        assertThat(client().getTransaction(url(), "sig")?.feePayer).isEqualTo(wallet)
    }

    @Test
    fun `getTransaction lists every required signer with the fee payer first`(): Unit = runBlocking {
        // A sponsored send: the sponsor's key pays, the wallet is the second required signer,
        // and the token account is a plain (non-signing) account.
        val sponsor = "SponsorFeePayer1111111111111111111111111111"
        val value = transactionValue(feePayer = sponsor, err = JSONObject.NULL)
        value.getJSONObject("transaction").getJSONObject("message")
            .put("header", JSONObject().put("numRequiredSignatures", 2))
            .put("accountKeys", JSONArray().put(sponsor).put(wallet).put(tokenAccount))
        rpc.stubObject("getTransaction", value)

        val record = client().getTransaction(url(), "sig")!!

        assertThat(record.signers).containsExactly(sponsor, wallet).inOrder()
        assertThat(record.feePayer).isEqualTo(sponsor)
        assertThat(record.signedBy(wallet)).isTrue()
        assertThat(record.signedBy(tokenAccount)).isFalse()
    }

    @Test
    fun `getTransaction treats a missing header as a single-signer transaction`(): Unit = runBlocking {
        rpc.stubObject("getTransaction", transactionValue(feePayer = wallet, err = JSONObject.NULL))

        val record = client().getTransaction(url(), "sig")!!

        assertThat(record.signers).containsExactly(wallet)
        assertThat(record.signedBy(tokenAccount)).isFalse()
    }

    @Test
    fun `getTransaction returns null for a signature the cluster does not know`(): Unit = runBlocking {
        rpc.stubObject("getTransaction", JSONObject.NULL)

        assertThat(client().getTransaction(url(), "sig")).isNull()
    }

    @Test
    fun `getTransaction fails loudly on a malformed response`() {
        rpc.stubObject("getTransaction", JSONObject().put("slot", 1))
        assertThrows(RainError.InternalError::class.java) {
            runBlocking { client().getTransaction(url(), "sig") }
        }
    }

    // ---------- fixtures ----------

    private fun transactionValue(feePayer: String, err: Any): JSONObject = JSONObject()
        .put("slot", 150)
        .put(
            "transaction",
            JSONObject()
                .put("signatures", JSONArray().put("sig"))
                .put("message", JSONObject().put("accountKeys", JSONArray().put(feePayer).put(tokenAccount)))
        )
        .put("meta", JSONObject().put("err", err).put("fee", 5000))

    /** Wraps [value] in the `{context, value}` envelope every Solana account read returns. */
    private fun contextual(value: Any): JSONObject =
        JSONObject().put("context", JSONObject().put("slot", 1)).put("value", value)

    private fun mintValue(
        decimals: Int,
        program: String = SolanaPrograms.TOKEN_ADDRESS
    ): JSONObject = JSONObject()
        .put("owner", program)
        .put("lamports", 1_461_600L)
        .put("space", 82)
        .put(
            "data",
            JSONObject()
                .put("program", "spl-token")
                .put("space", 82)
                .put(
                    "parsed",
                    JSONObject()
                        .put("type", "mint")
                        .put(
                            "info",
                            JSONObject()
                                .put("decimals", decimals)
                                .put("isInitialized", true)
                                .put("supply", "1000000000")
                        )
                )
        )

    private fun tokenAccountValue(
        amount: String,
        decimals: Int,
        program: String = SolanaPrograms.TOKEN_ADDRESS
    ): JSONObject = JSONObject()
        .put("owner", program)
        .put("lamports", 2_039_280L)
        .put("space", 165)
        .put(
            "data",
            JSONObject()
                .put("program", "spl-token")
                .put("space", 165)
                .put(
                    "parsed",
                    JSONObject()
                        .put("type", "account")
                        .put(
                            "info",
                            JSONObject()
                                .put("mint", mint)
                                .put("owner", wallet)
                                .put("state", "initialized")
                                .put(
                                    "tokenAmount",
                                    JSONObject()
                                        .put("amount", amount)
                                        .put("decimals", decimals)
                                        .put("uiAmountString", "0")
                                )
                        )
                )
        )
}
