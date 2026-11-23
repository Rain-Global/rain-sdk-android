package com.rain.sdk.internal.network.chainreader

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.constants.SolanaPrograms
import com.rain.sdk.internal.helpers.MockRpcServer
import com.rain.sdk.internal.solana.Base58
import com.rain.sdk.internal.solana.SolanaAddresses
import com.rain.sdk.models.Token
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * Verifies the Solana reader parses lamports from Solana JSON-RPC `getBalance` (a JSON number,
 * not the hex EVM returns) and exposes SOL with 9-decimal metadata.
 */
class SolanaChainReaderTest {

    private lateinit var rpc: MockRpcServer
    private val devnet = RainChain.SOLANA_DEVNET
    private val wallet = "So11111111111111111111111111111111111111112"

    @Before
    fun setUp() {
        rpc = MockRpcServer().also { it.start() }
    }

    @After
    fun tearDown() = rpc.shutdown()

    private fun reader(): SolanaChainReader =
        SolanaChainReader(rpcEndpoints = mapOf(devnet to rpc.urlFor(devnet)))

    private fun balanceResult(lamports: Long): JSONObject =
        JSONObject().put("context", JSONObject().put("slot", 1)).put("value", lamports)

    @Test
    fun `getBalance native parses lamports into a SOL balance`(): Unit = runBlocking {
        rpc.stubObject("getBalance", balanceResult(2_500_000_000L)) // 2.5 SOL

        val balance = reader().getBalance(devnet, wallet, Token.Native, null)

        assertThat(balance.rawAmount).isEqualTo(BigInteger.valueOf(2_500_000_000L))
        assertThat(balance.decimals).isEqualTo(9)
        assertThat(balance.symbol).isEqualTo("SOL")
        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-9).of(2.5)
        assertThat(rpc.recordedMethods).containsExactly("getBalance")
    }

    @Test
    fun `getNativeBalance returns human-readable SOL`(): Unit = runBlocking {
        rpc.stubObject("getBalance", balanceResult(1_000_000_000L))
        assertThat(reader().getNativeBalance(devnet, wallet)).isEqualToIgnoringScale("1")
    }

    @Test
    fun `getBalances discovers SPL holdings alongside the native balance`(): Unit = runBlocking {
        rpc.stubObject("getBalance", balanceResult(750_000_000L))
        stubTokenAccounts(classic = listOf(tokenAccountEntry(ata, usdcMint, "20000000", 6)))

        val balances = reader().getBalances(devnet, wallet, emptyList())

        assertThat(balances).hasSize(2)
        assertThat(balances.first().token).isEqualTo(Token.Native)
        assertThat(balances.first().decimalAmount.toDouble()).isWithin(1e-9).of(0.75)

        // Holdings come from the chain, so a mint in no registry still shows up.
        val usdc = balances.last()
        assertThat(usdc.token).isEqualTo(Token.Contract(usdcMint))
        assertThat(usdc.decimals).isEqualTo(6)
        assertThat(usdc.decimalAmount.toDouble()).isWithin(1e-6).of(20.0)
    }

    @Test
    fun `getBalances covers both token programs and omits empty accounts`(): Unit = runBlocking {
        rpc.stubObject("getBalance", balanceResult(1L))
        stubTokenAccounts(
            classic = listOf(
                tokenAccountEntry(ata, usdcMint, "20000000", 6),
                tokenAccountEntry(wallet, "EmptyMint1111111111111111111111111111111111", "0", 6)
            ),
            token2022 = listOf(tokenAccountEntry(wallet, token2022Mint, "5000000000", 9))
        )

        val balances = reader().getBalances(devnet, wallet, emptyList())
        val mints = balances.mapNotNull { (it.token as? Token.Contract)?.address }

        // A zero-balance token account is a leftover, not a holding.
        assertThat(mints).containsExactly(usdcMint, token2022Mint)
    }

    @Test
    fun `getBalances omits a token program whose enumeration fails`(): Unit = runBlocking {
        rpc.stubObject("getBalance", balanceResult(1_000_000_000L))
        rpc.stubObjectWhenBodyContains(
            "getTokenAccountsByOwner",
            SolanaPrograms.TOKEN_ADDRESS,
            accountEnvelope(JSONArray().put(tokenAccountEntry(ata, usdcMint, "20000000", 6)))
        )
        rpc.stubNetworkFailureWhenBodyContains(
            "getTokenAccountsByOwner",
            SolanaPrograms.TOKEN_2022_ADDRESS
        )

        val balances = reader().getBalances(devnet, wallet, emptyList())

        assertThat(balances.first().token).isEqualTo(Token.Native)
        val mints = balances.mapNotNull { (it.token as? Token.Contract)?.address }
        assertThat(mints).containsExactly(usdcMint)
    }

    @Test
    fun `getBalances stays fatal when the native read fails`() {
        rpc.stubNetworkFailure("getBalance")
        stubTokenAccounts(classic = listOf(tokenAccountEntry(ata, usdcMint, "20000000", 6)))

        assertThrows(RainError.NetworkError::class.java) {
            runBlocking { reader().getBalances(devnet, wallet, emptyList()) }
        }
    }

    @Test
    fun `getBalance reads a single mint from its associated token account`(): Unit = runBlocking {
        rpc.stubObjectFor("getAccountInfo", usdcMint, accountEnvelope(mintValue(decimals = 6)))
        rpc.stubObjectFor(
            "getAccountInfo",
            expectedAta(),
            accountEnvelope(tokenAccountValue("20000000", 6))
        )

        val balance = reader().getBalance(devnet, wallet, Token.Contract(usdcMint), null)

        assertThat(balance.decimals).isEqualTo(6)
        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-6).of(20.0)
    }

    @Test
    fun `getBalance reports zero when the wallet has no account for the mint`(): Unit = runBlocking {
        rpc.stubObjectFor("getAccountInfo", usdcMint, accountEnvelope(mintValue(decimals = 6)))
        rpc.stubObjectFor("getAccountInfo", expectedAta(), accountEnvelope(JSONObject.NULL))

        val balance = reader().getBalance(devnet, wallet, Token.Contract(usdcMint), null)

        // Never having held the token is a zero balance, not an error.
        assertThat(balance.rawAmount).isEqualTo(BigInteger.ZERO)
        assertThat(balance.decimals).isEqualTo(6)
    }

    @Test
    fun `getBalance rejects an address that is not a mint on this cluster`() {
        rpc.stubObject("getAccountInfo", accountEnvelope(JSONObject.NULL))
        assertThrows(RainError.TokenNotFound::class.java) {
            runBlocking { reader().getBalance(devnet, wallet, Token.Contract(usdcMint), null) }
        }
    }

    @Test
    fun `getDecimals reads the mint scale`(): Unit = runBlocking {
        rpc.stubObject("getAccountInfo", accountEnvelope(mintValue(decimals = 9)))
        assertThat(reader().getDecimals(devnet, usdcMint)).isEqualTo(9)
    }

    @Test
    fun `symbol and name are unknown rather than fatal`(): Unit = runBlocking {
        // SPL names live in Metaplex metadata, which the SDK does not read; returning null keeps
        // token-store enrichment working instead of aborting it.
        assertThat(reader().getSymbol(devnet, usdcMint)).isNull()
        assertThat(reader().getName(devnet, usdcMint)).isNull()
    }

    @Test
    fun `rejects a non-base58 wallet address`() {
        assertThrows(RainError.InternalError::class.java) {
            runBlocking { reader().getNativeBalance(devnet, "0xnot-base58!") }
        }
    }

    // ---------- fixtures ----------

    /**
     * Real devnet values: this mint and holding were read off the cluster, so the derived token
     * account address below is the one the chain actually uses.
     */
    private val usdcMint = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    private val token2022Mint = "GDDMwNyyx8uB6zrqwBFHjLLG3TBYk2F86azcm4Rr4jSN"
    private val ata = "6gab94XKwMVEuiT7WgWHCmc8cPdZrg4VhRF6y1v7SLZQ"

    private fun expectedAta(): String = Base58.encode(
        SolanaAddresses.associatedTokenAddress(
            owner = Base58.decode(wallet),
            mint = Base58.decode(usdcMint),
            tokenProgramId = SolanaPrograms.TOKEN
        )
    )

    private fun stubTokenAccounts(
        classic: List<JSONObject> = emptyList(),
        token2022: List<JSONObject> = emptyList()
    ) {
        rpc.stubObjectWhenBodyContains(
            "getTokenAccountsByOwner",
            SolanaPrograms.TOKEN_ADDRESS,
            accountEnvelope(JSONArray().also { array -> classic.forEach(array::put) })
        )
        rpc.stubObjectWhenBodyContains(
            "getTokenAccountsByOwner",
            SolanaPrograms.TOKEN_2022_ADDRESS,
            accountEnvelope(JSONArray().also { array -> token2022.forEach(array::put) })
        )
    }

    private fun tokenAccountEntry(
        address: String,
        mint: String,
        amount: String,
        decimals: Int
    ): JSONObject = JSONObject()
        .put("pubkey", address)
        .put("account", tokenAccountValue(amount, decimals, mint))

    private fun accountEnvelope(value: Any): JSONObject =
        JSONObject().put("context", JSONObject().put("slot", 1)).put("value", value)

    private fun mintValue(decimals: Int): JSONObject = JSONObject()
        .put("owner", SolanaPrograms.TOKEN_ADDRESS)
        .put(
            "data",
            JSONObject().put("program", "spl-token").put(
                "parsed",
                JSONObject().put("type", "mint").put("info", JSONObject().put("decimals", decimals))
            )
        )

    private fun tokenAccountValue(
        amount: String,
        decimals: Int,
        mint: String = usdcMint
    ): JSONObject = JSONObject()
        .put("owner", SolanaPrograms.TOKEN_ADDRESS)
        .put(
            "data",
            JSONObject().put("program", "spl-token").put(
                "parsed",
                JSONObject().put("type", "account").put(
                    "info",
                    JSONObject()
                        .put("mint", mint)
                        .put("owner", wallet)
                        .put(
                            "tokenAmount",
                            JSONObject().put("amount", amount).put("decimals", decimals)
                        )
                )
            )
        )
}
