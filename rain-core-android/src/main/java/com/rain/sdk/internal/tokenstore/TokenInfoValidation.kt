package com.rain.sdk.internal.tokenstore

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.constants.SolanaChains
import com.rain.sdk.internal.solana.Base58
import com.rain.sdk.internal.solana.SolanaAddresses
import com.rain.sdk.internal.utils.RainAmountUtils
import com.rain.sdk.internal.utils.validateAndChecksumAddress
import com.rain.sdk.models.TokenInfo

/** Checks shared by every token-registration and token-lookup entry point. */
internal object TokenInfoValidation {

    /**
     * Rejects a malformed token address at the source: an entry that enters the store rides into
     * every balance batch on its chain. On EVM chains the address must be `0x` followed by 40 hex
     * characters and, when mixed-case, carry a correct EIP-55 checksum (all-lowercase and
     * all-uppercase carry none); the prefix is required because the store keys entries by the string
     * as given, so a bare or `0X` spelling would never match its `0x` twin. On Solana chains the
     * address must be base58 decoding to 32 bytes.
     */
    fun requireValidAddress(chainId: Int, address: String) {
        if (SolanaChains.isSolanaChain(chainId)) {
            // Base58.decode is pure and rejects a bad character with IllegalArgumentException.
            val bytes = try {
                Base58.decode(address)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (bytes == null || bytes.size != SolanaAddresses.PUBLIC_KEY_LENGTH) {
                throw RainError.InvalidConfig("Invalid token mint for chainId=$chainId: $address")
            }
        } else {
            if (!address.startsWith("0x")) {
                throw RainError.InvalidConfig("Invalid token address for chainId=$chainId: expected a 0x prefix: $address")
            }
            validateAndChecksumAddress(address, "token address for chainId=$chainId")
        }
    }

    /**
     * Validates a whole registration list before anything is registered, so one bad entry
     * registers nothing: addresses per [requireValidAddress], and `decimals` within
     * [RainAmountUtils.DECIMALS_RANGE], since no money path can scale by a value outside it.
     */
    fun requireValid(tokens: List<TokenInfo>) {
        tokens.forEach { token ->
            requireValidAddress(token.chainId, token.address)
            if (token.decimals !in RainAmountUtils.DECIMALS_RANGE) {
                throw RainError.InvalidConfig(
                    "Invalid token decimals for chainId=${token.chainId} ${token.address}: " +
                        "${token.decimals}, expected ${RainAmountUtils.DECIMALS_RANGE}"
                )
            }
        }
    }
}
