package com.rain.sdk.internal.constants

import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.solana.Base58

/**
 * On-chain program addresses the SDK builds instructions against, decoded once from their
 * canonical Base58 form. A transfer must use the token program that owns the mint ([TOKEN] or
 * [TOKEN_2022]), which is also a seed of the associated-token-account address.
 *
 * The arrays are shared, not copied — treat them as immutable.
 */
@RainAdapterApi
object SolanaPrograms {
    const val SYSTEM_ADDRESS = "11111111111111111111111111111111"
    const val TOKEN_ADDRESS = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val TOKEN_2022_ADDRESS = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    const val ASSOCIATED_TOKEN_ADDRESS = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    const val ED25519_VERIFY_ADDRESS = "Ed25519SigVerify111111111111111111111111111"
    const val SYSVAR_INSTRUCTIONS_ADDRESS = "Sysvar1nstructions1111111111111111111111111"

    val SYSTEM: ByteArray = Base58.decode(SYSTEM_ADDRESS)
    val TOKEN: ByteArray = Base58.decode(TOKEN_ADDRESS)
    val TOKEN_2022: ByteArray = Base58.decode(TOKEN_2022_ADDRESS)
    val ASSOCIATED_TOKEN: ByteArray = Base58.decode(ASSOCIATED_TOKEN_ADDRESS)
    val ED25519_VERIFY: ByteArray = Base58.decode(ED25519_VERIFY_ADDRESS)
    val SYSVAR_INSTRUCTIONS: ByteArray = Base58.decode(SYSVAR_INSTRUCTIONS_ADDRESS)

    /** True when [address] is one of the two SPL token programs. */
    fun isTokenProgram(address: String): Boolean =
        address == TOKEN_ADDRESS || address == TOKEN_2022_ADDRESS
}
