package com.rain.sdk.internal.solana

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.constants.SolanaPrograms
import com.rain.sdk.internal.network.chainreader.JsonRpcClient
import org.json.JSONObject
import timber.log.Timber
import java.math.BigInteger
import java.util.Base64

/**
 * Thin Solana JSON-RPC helper layered on the shared [JsonRpcClient]. Solana RPC returns JSON
 * objects/numbers rather than the hex strings EVM uses, so it needs the raw `call()` surface.
 *
 * Covers the native-SOL path (balance, blockhash, signature lookup) and the account reads an SPL
 * transfer needs. The latter all go through [getAccountInfo] with `jsonParsed` encoding, which
 * answers existence, owning program, mint decimals and token balances in one call — and, unlike
 * `getTokenAccountBalance`, returns a null value rather than an RPC *error* for an account that
 * does not exist yet, which is the normal case for a first-time recipient.
 */
@RainAdapterApi
class SolanaRpcClient(
    private val jsonRpcClient: JsonRpcClient = JsonRpcClient()
) {
    private companion object {
        /** How many post-send signatures to inspect when recovering a send's own signature. */
        const val SIGNATURE_SCAN_LIMIT = 10
    }

    /** Native SOL balance in lamports via `getBalance`. */
    suspend fun getBalanceLamports(rpcUrl: String, address: String): BigInteger {
        val response = jsonRpcClient.call(rpcUrl, "getBalance", listOf(address))
        val result = response.optJSONObject("result")
            ?: throw RainError.InternalError("Unexpected getBalance response for $address")
        return BigInteger.valueOf(result.getLong("value"))
    }

    /** The lamports an account of [dataLength] bytes must hold to be rent exempt, via `getMinimumBalanceForRentExemption`. */
    suspend fun getMinimumBalanceForRentExemption(rpcUrl: String, dataLength: Int): BigInteger {
        val response = jsonRpcClient.call(rpcUrl, "getMinimumBalanceForRentExemption", listOf(dataLength))
        val rent = response.opt("result")?.toString()?.toBigDecimalOrNull()
            ?.let { runCatching { it.toBigIntegerExact() }.getOrNull() }
        if (rent == null || rent.signum() <= 0) {
            throw RainError.InternalError("Unexpected getMinimumBalanceForRentExemption response for $dataLength bytes")
        }
        return rent
    }

    /** Most recent blockhash via `getLatestBlockhash`, used as a transfer's `recentBlockhash`. */
    suspend fun getLatestBlockhash(rpcUrl: String): String {
        val response = jsonRpcClient.call(
            rpcUrl,
            "getLatestBlockhash",
            listOf(mapOf("commitment" to "finalized"))
        )
        val value = response.optJSONObject("result")?.optJSONObject("value")
            ?: throw RainError.InternalError("Unexpected getLatestBlockhash response")
        return value.optString("blockhash", "").ifEmpty {
            throw RainError.InternalError("getLatestBlockhash returned no blockhash")
        }
    }

    /**
     * The most recent transaction signature involving [address], or null if none. A managed-broadcast
     * send can return a status id rather than the signature, so the signature of a just-submitted
     * transfer is recovered from the chain.
     */
    suspend fun getLatestSignature(rpcUrl: String, address: String): String? {
        val response = jsonRpcClient.call(
            rpcUrl,
            "getSignaturesForAddress",
            listOf(address, mapOf("limit" to 1))
        )
        val results = response.optJSONArray("result") ?: return null
        if (results.length() == 0) return null
        return results.getJSONObject(0).optString("signature", "").ifEmpty { null }
    }

    /**
     * Signatures involving [address] newer than [until] (all recent ones when null), newest first.
     * `until` is the RPC's own cursor, so a deposit landing after a send cannot hide the send.
     */
    suspend fun getSignaturesSince(rpcUrl: String, address: String, until: String?): List<String> {
        val options = mutableMapOf<String, Any>("limit" to SIGNATURE_SCAN_LIMIT)
        if (until != null) options["until"] = until
        val response = jsonRpcClient.call(rpcUrl, "getSignaturesForAddress", listOf(address, options))
        val results = response.optJSONArray("result") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            results.optJSONObject(index)?.optString("signature", "")?.ifEmpty { null }
        }
    }

    /**
     * The confirmed transaction for [signature], or null when the cluster does not know it yet.
     * Read at `confirmed` so a just-landed send is visible.
     */
    suspend fun getTransaction(rpcUrl: String, signature: String): SolanaTransactionRecord? {
        val response = jsonRpcClient.call(
            rpcUrl,
            "getTransaction",
            listOf(
                signature,
                mapOf(
                    "encoding" to "json",
                    "commitment" to "confirmed",
                    "maxSupportedTransactionVersion" to 0
                )
            )
        )
        val result = response.optJSONObject("result") ?: return null
        val message = result.optJSONObject("transaction")?.optJSONObject("message")
        val accountKeys = message?.optJSONArray("accountKeys")
            ?: throw RainError.InternalError("Unexpected getTransaction response for $signature")
        // The first `numRequiredSignatures` account keys are the signers, fee payer first. A
        // sponsored send may carry the sponsor's key there with this wallet as a later signer,
        // so the record keeps every signer rather than just the payer. A missing header reads
        // as the single-signer layout.
        val signerCount = message.optJSONObject("header")
            ?.optInt("numRequiredSignatures", 1)
            ?.coerceAtLeast(1)
            ?: 1
        // `json` encoding lists keys as strings; `jsonParsed` wraps them in {pubkey, signer, ...}.
        val signers = (0 until minOf(signerCount, accountKeys.length())).mapNotNull { index ->
            when (val key = accountKeys.opt(index)) {
                is String -> key
                is JSONObject -> key.optString("pubkey", "")
                else -> ""
            }.takeIf { it.isNotEmpty() }
        }
        val error = result.optJSONObject("meta")?.opt("err")
            ?.takeIf { it != JSONObject.NULL }
            ?.toString()
        return SolanaTransactionRecord(signers = signers, error = error)
    }

    // ---------- accounts ----------

    /**
     * `getAccountInfo` for [address], or null when no account exists there.
     *
     * Read at `confirmed` rather than the default `finalized`: a token account created moments
     * earlier must be visible, or the SDK would try to create it a second time.
     */
    internal suspend fun getAccountInfo(rpcUrl: String, address: String): SolanaAccountInfo? {
        val response = jsonRpcClient.call(
            rpcUrl,
            "getAccountInfo",
            listOf(address, mapOf("encoding" to "jsonParsed", "commitment" to "confirmed"))
        )
        val result = response.optJSONObject("result")
            ?: throw RainError.InternalError("Unexpected getAccountInfo response for $address")
        // `value` is JSON null for an address that holds no account.
        val value = result.optJSONObject("value") ?: return null

        // `data` is a [base64, encoding] array for accounts the cluster cannot parse (a plain
        // wallet, for one), and an object only for programs it understands.
        val parsed = value.optJSONObject("data")?.optJSONObject("parsed")
        return SolanaAccountInfo(
            ownerProgram = value.optString("owner", ""),
            parsedType = parsed?.optString("type", "")?.ifEmpty { null },
            parsedInfo = parsed?.optJSONObject("info")
        )
    }

    /** Whether anything exists at [address] — used to decide if a token account must be created. */
    suspend fun accountExists(rpcUrl: String, address: String): Boolean =
        getAccountInfo(rpcUrl, address) != null

    /**
     * `getAccountInfo` with raw base64 data, or null when no account exists — for accounts the
     * SDK parses itself (Rain's collateral program accounts), where `jsonParsed` has nothing.
     */
    internal suspend fun getAccountRaw(rpcUrl: String, address: String): SolanaRawAccount? {
        val response = jsonRpcClient.call(
            rpcUrl,
            "getAccountInfo",
            listOf(address, mapOf("encoding" to "base64", "commitment" to "confirmed"))
        )
        val result = response.optJSONObject("result")
            ?: throw RainError.InternalError("Unexpected getAccountInfo response for $address")
        val value = result.optJSONObject("value") ?: return null
        val base64 = value.optJSONArray("data")?.optString(0).orEmpty()
        return SolanaRawAccount(
            ownerProgram = value.optString("owner", ""),
            data = Base64.getDecoder().decode(base64)
        )
    }

    /**
     * Decimals and owning token program for [mint], or null when [mint] is not an SPL mint on
     * this cluster (missing account, or an account owned by some other program).
     *
     * The owning program matters twice over: it is the program a transfer must be addressed to,
     * and it is a seed of the associated-token-account address.
     */
    suspend fun getMintInfo(rpcUrl: String, mint: String): SolanaMintInfo? {
        val account = getAccountInfo(rpcUrl, mint) ?: return null
        if (!SolanaPrograms.isTokenProgram(account.ownerProgram)) return null
        if (account.parsedType != "mint") return null

        val decimals = account.parsedInfo?.optInt("decimals", -1) ?: -1
        if (decimals < 0) {
            throw RainError.InternalError("Mint $mint returned no decimals")
        }
        return SolanaMintInfo(
            address = mint,
            decimals = decimals,
            tokenProgram = account.ownerProgram
        )
    }

    /** The SPL token account at [address], or null when it does not exist or is not one. */
    suspend fun getTokenAccount(rpcUrl: String, address: String): SolanaTokenAccount? {
        val account = getAccountInfo(rpcUrl, address) ?: return null
        if (!SolanaPrograms.isTokenProgram(account.ownerProgram)) return null
        if (account.parsedType != "account") return null

        val info = account.parsedInfo ?: return null
        return parseTokenAccount(address, info)
            ?: throw RainError.InternalError("Token account $address returned no tokenAmount")
    }

    /**
     * Every token account [owner] holds under [tokenProgram], via `getTokenAccountsByOwner`.
     *
     * This is how SPL holdings are *discovered*: unlike EVM, where balances are read per known
     * contract, the cluster can enumerate a wallet's token accounts directly — so no registry of
     * mints is needed. Call once per token program; the two programs hold separate accounts.
     */
    suspend fun getTokenAccountsByOwner(
        rpcUrl: String,
        owner: String,
        tokenProgram: String
    ): List<SolanaTokenAccount> {
        val response = jsonRpcClient.call(
            rpcUrl,
            "getTokenAccountsByOwner",
            listOf(
                owner,
                mapOf("programId" to tokenProgram),
                mapOf("encoding" to "jsonParsed", "commitment" to "confirmed")
            )
        )
        val accounts = response.optJSONObject("result")?.optJSONArray("value")
            ?: throw RainError.InternalError("Unexpected getTokenAccountsByOwner response for $owner")

        return (0 until accounts.length()).mapNotNull { index ->
            val entry = accounts.optJSONObject(index) ?: return@mapNotNull null
            val address = entry.optString("pubkey", "")
            val info = entry.optJSONObject("account")
                ?.optJSONObject("data")
                ?.optJSONObject("parsed")
                ?.optJSONObject("info")
                ?: return@mapNotNull null
            val parsed = parseTokenAccount(address, info)
            if (parsed == null) {
                Timber.w("Rain SDK: skipping malformed token account $address — missing tokenAmount")
            }
            parsed
        }
    }

    /**
     * Builds a [SolanaTokenAccount] from a `jsonParsed` token-account `info`, or null when the
     * `tokenAmount` fields are missing or unreadable — defaulting them would mis-scale a real
     * balance.
     */
    private fun parseTokenAccount(address: String, info: JSONObject): SolanaTokenAccount? {
        val tokenAmount = info.optJSONObject("tokenAmount") ?: return null
        val amount = runCatching { BigInteger(tokenAmount.getString("amount")) }.getOrNull()
            ?: return null
        val decimals = tokenAmount.optInt("decimals", -1)
        if (decimals < 0) return null
        return SolanaTokenAccount(
            address = address,
            mint = info.optString("mint", ""),
            owner = info.optString("owner", ""),
            amount = amount,
            decimals = decimals
        )
    }

    // ---------- simulation ----------

    /**
     * Dry-runs [base64Transaction] against the cluster before it is submitted for signing.
     *
     * The transaction is still unsigned at this point — the wallet provider holds the key — so
     * signature verification is off and the cluster substitutes a current blockhash. Everything
     * that actually matters here (funds, account ownership, mint/decimals agreement, rent) is
     * still checked, which is what turns a silent on-chain failure into an upfront error.
     */
    internal suspend fun simulateTransaction(rpcUrl: String, base64Transaction: String): SolanaSimulation {
        val response = jsonRpcClient.call(
            rpcUrl,
            "simulateTransaction",
            listOf(
                base64Transaction,
                mapOf(
                    "encoding" to "base64",
                    "sigVerify" to false,
                    "replaceRecentBlockhash" to true,
                    "commitment" to "confirmed"
                )
            )
        )
        val value = response.optJSONObject("result")?.optJSONObject("value")
            ?: throw RainError.InternalError("Unexpected simulateTransaction response")

        val error = value.opt("err")?.takeIf { it != JSONObject.NULL }?.toString()
        val logs = value.optJSONArray("logs")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it, "").ifEmpty { null } }
        } ?: emptyList()
        return SolanaSimulation(error = error, logs = logs)
    }
}

/** Raw account bytes plus the program that owns the account. */
internal data class SolanaRawAccount(
    val ownerProgram: String,
    val data: ByteArray
)

/** The fields of a `getAccountInfo` result the SDK reads. */
internal data class SolanaAccountInfo(
    /** Program that owns the account — the System Program for a plain wallet. */
    val ownerProgram: String,
    /** `jsonParsed` account type, e.g. `mint` or `account`; null when the cluster can't parse it. */
    val parsedType: String?,
    val parsedInfo: JSONObject?
)

/** An SPL mint: its scale, and the token program that owns it. */
@RainAdapterApi
data class SolanaMintInfo(
    val address: String,
    val decimals: Int,
    val tokenProgram: String
)

/** An SPL token account: which mint it holds, for whom, and how much. */
@RainAdapterApi
data class SolanaTokenAccount(
    val address: String,
    val mint: String,
    val owner: String,
    val amount: BigInteger,
    val decimals: Int
)

/** The fields of a `getTransaction` result the SDK reads. [error] is null when it succeeded. */
@RainAdapterApi
data class SolanaTransactionRecord(
    /** The transaction's signers in account-key order; the first one paid the fee. */
    val signers: List<String>,
    val error: String?
) {
    /** First signer: the account that paid the fee (possibly a sponsor's key on a sponsored send). */
    val feePayer: String? get() = signers.firstOrNull()
    val succeeded: Boolean get() = error == null

    /** True when [address] had to sign this transaction: its own send, sponsored or not. */
    fun signedBy(address: String): Boolean = address in signers
}

/** Outcome of a `simulateTransaction` dry run. [error] is null when the transaction would succeed. */
internal data class SolanaSimulation(
    val error: String?,
    val logs: List<String>
) {
    val succeeded: Boolean get() = error == null

    /** The payer could not pay the fee or the rent, read by [SolanaLamportPreflight.shortfall]; null for a program's refusal. */
    val shortfall: RainError.InsufficientFunds?
        get() = SolanaLamportPreflight.shortfall(error, logs)
}
