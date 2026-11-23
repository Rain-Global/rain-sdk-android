package com.rain.sdk.internal.utils

import java.math.BigInteger
import java.util.Locale

internal object RainEip712Utils {

    /** JSON string-escapes [s] so an interpolated value can never break out of its literal. */
    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(Locale.ROOT, c.code))
            else -> append(c)
        }
    }

    fun createEIP712Json(
        chainId: Long,
        verifyingContract: String,
        saltHex: String,
        walletAddress: String,
        tokenAddress: String,
        recipientAddress: String,
        amount: BigInteger,
        nonce: BigInteger
    ): String {
        return """
        {
          "domain": {
            "salt": "${esc(saltHex)}",
            "chainId": $chainId,
            "verifyingContract": "${esc(verifyingContract)}",
            "name": "Collateral",
            "version": "2"
          },
          "primaryType": "Withdraw",
          "message": {
            "recipient": "${esc(recipientAddress)}",
            "nonce": "${nonce.toString(10)}",
            "amount": "${amount.toString(10)}",
            "user": "${esc(walletAddress)}",
            "asset": "${esc(tokenAddress)}"
          },
          "types": {
            "Withdraw": [
              {"type": "address", "name": "user"},
              {"name": "asset", "type": "address"},
              {"type": "uint256", "name": "amount"},
              {"name": "recipient", "type": "address"},
              {"name": "nonce", "type": "uint256"}
            ],
            "EIP712Domain": [
              {"type": "string", "name": "name"},
              {"type": "string", "name": "version"},
              {"type": "uint256", "name": "chainId"},
              {"name": "verifyingContract", "type": "address"},
              {"name": "salt", "type": "bytes32"}
            ]
          }
        }
        """.trimIndent()
    }
}
