package com.rain.sdk.models

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.utils.RainHexUtils

/**
 * The addresses a withdrawal needs. Three come from the user's collateral contract as the Rain API
 * returns it (`GET /v1/issuing/users/{userId}/contracts`: `proxyAddress`, `controllerAddress` and
 * the token's `tokens[].address`); the recipient is the host's choice.
 *
 * @property proxyAddress The address of the collateral proxy contract.
 * @property controllerAddress The address of the collateral controller contract.
 * @property tokenAddress The address of the token being withdrawn.
 * @property recipientAddress The address receiving the tokens.
 * @property depositAddress The contract's `depositAddress` from the same Rain API response. Required
 *   on Solana, where the SDK checks it against the collateral program the RPC node reports; unused
 *   on EVM chains.
 */
data class RainWithdrawAddresses(
    val proxyAddress: String,
    val controllerAddress: String,
    val tokenAddress: String,
    val recipientAddress: String,
    val depositAddress: String? = null
) {
    /**
     * Returns a copy with every address in EIP-55 checksum form. A mixed-case address must already
     * carry its checksum: the letter case is EIP-55's typo check, so a wrong one is refused rather
     * than repaired. All-lowercase and all-uppercase input carries no checksum and is normalized.
     *
     * @throws RainError.InvalidConfig (`RAIN_102`) when an address is not 40 hex characters with an
     *   optional `0x` prefix, or a mixed-case address's checksum doesn't match
     */
    fun validated(): RainWithdrawAddresses {
        return copy(
            proxyAddress = RainHexUtils.validateAndChecksum(proxyAddress, "proxyAddress"),
            controllerAddress = RainHexUtils.validateAndChecksum(controllerAddress, "controllerAddress"),
            tokenAddress = RainHexUtils.validateAndChecksum(tokenAddress, "tokenAddress"),
            recipientAddress = RainHexUtils.validateAndChecksum(recipientAddress, "recipientAddress")
        )
    }
}

/**
 * Rain's authorization for one withdrawal, as the Rain API returns it
 * (`GET /v1/issuing/users/{userId}/signatures/withdrawals`): `signature.salt`, `signature.data`
 * and the top-level `expiresAt`, passed through unchanged. The host fetches it from its backend;
 * the SDK only consumes it.
 *
 * @property salt Base64 of the 32-byte salt Rain signed with, on every chain.
 * @property signature The admin signature: on EVM chains 0x-prefixed hex of 65 bytes, on Solana
 *   base64 of the 64-byte ed25519 signature.
 * @property expiresAt When the authorization lapses: unix seconds, or an ISO-8601 date-time with Z or a
 *   numeric offset, such as "2030-12-31T23:59:59Z" or "2030-12-31T23:59:59+02:00".
 */
data class RainAdminSignature(
    val salt: String,
    val signature: String,
    val expiresAt: String
)
