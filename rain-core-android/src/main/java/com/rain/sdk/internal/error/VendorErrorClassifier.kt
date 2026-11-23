package com.rain.sdk.internal.error

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.RainAdapterApi
import java.util.concurrent.CancellationException

/**
 * The one standard for classifying free-text vendor error prose, shared by core's [ErrorMapper]
 * and every adapter module so the same vendor message never classifies two ways.
 *
 * Every phrase is at least two words. A lone "rejected", "cancelled" or "insufficient" says
 * nothing: "Transaction cancelled" is a chain outcome, "User doesn't have an embedded wallet" is
 * a wallet-availability failure, and neither is the user declining a prompt.
 */
@RainAdapterApi
object VendorErrorClassifier {

    val USER_REJECTED_PHRASES = listOf(
        "user rejected", "user denied", "user cancelled", "user canceled", "user declined",
        "rejected by user", "denied by user", "cancelled by user", "canceled by user",
        "rejected by the user", "denied by the user", "cancelled by the user",
        "canceled by the user"
    )

    val INSUFFICIENT_FUNDS_PHRASES = listOf(
        "insufficient funds",
        "insufficient balance",
        "insufficient lamports",
        // Base Sepolia's public node, on a native send over the balance: "RPC error [-32003]:
        // EVM error: OutOfFunds". [normalize] splits the camel case.
        "out of funds",
        // Solana: "Attempt to debit an account but found no record of a prior credit".
        "found no record of a prior credit"
    )

    /** EIP-1193 `userRejectedRequest`; the Solana wallet standard reuses the same code. */
    private const val USER_REJECTED_CODE = 4001

    /** How providers spell the code: `code: 4001`, `code=4001`, `[4001]`, `(4001)`. */
    private val USER_REJECTED_CODE_REGEX =
        Regex("""\bcode\W{0,3}$USER_REJECTED_CODE\b|[\[(]$USER_REJECTED_CODE[\])]""")

    /**
     * Classifies vendor prose into [RainError.UserRejected] / [RainError.InsufficientFunds], or
     * null when it matches neither and the caller's own fallback should stand.
     */
    fun fromVendorMessage(message: String?): RainError? {
        val text = normalize(message ?: return null)
        if (text.isBlank()) return null
        return when {
            USER_REJECTED_CODE_REGEX.containsMatchIn(text) -> RainError.UserRejected()
            USER_REJECTED_PHRASES.any { text.contains(it) } -> RainError.UserRejected()
            else -> insufficientFundsOrNull(message)
        }
    }

    /**
     * The funds-shortfall verdict alone: [RainError.InsufficientFunds] when [message] names one by
     * the phrases above, null otherwise. For prose a node or a server wallet relays, where no user
     * ever saw a prompt and a rejection marker in the text can only be noise.
     */
    fun insufficientFundsOrNull(message: String?): RainError.InsufficientFunds? {
        val text = normalize(message ?: return null)
        return if (INSUFFICIENT_FUNDS_PHRASES.any { text.contains(it) }) RainError.InsufficientFunds() else null
    }

    /**
     * Classifies a throwable by its message plus its type name — vendors often spell the reason
     * only in the class (`UserRejectedRequestException`) and leave the message generic.
     */
    fun fromVendorError(e: Throwable): RainError? {
        // Coroutine cancellation is not a wallet-UI rejection, and its type name says "cancel".
        if (e is CancellationException) return null
        return fromVendorMessage(e.javaClass.simpleName + " " + e.message.orEmpty())
    }

    /** Lowercases, and splits camelCase so a type name like `userRejectedRequest` reads as prose. */
    private fun normalize(message: String): String {
        val out = StringBuilder(message.length + 8)
        var previousWasLower = false
        for (ch in message) {
            if (ch.isUpperCase() && previousWasLower) out.append(' ')
            out.append(ch)
            previousWasLower = ch.isLowerCase()
        }
        return out.toString().lowercase()
    }
}
