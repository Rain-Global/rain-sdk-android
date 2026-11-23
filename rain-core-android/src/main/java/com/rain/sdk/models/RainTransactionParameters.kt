package com.rain.sdk.models

/**
 * Wallet-agnostic transaction parameter bag returned by
 * [com.rain.sdk.RainSdk.buildTransactionParameters].
 *
 * This is a Rain-owned struct so the public API does not leak Portal / Turnkey / web3j types.
 * Hosts can hand the resulting parameters to either provider (or any future provider) for
 * signing and broadcast.
 *
 * Field shapes are deliberately string-typed in the EVM wire format so callers can pass them
 * straight to JSON-RPC (`eth_sendTransaction`, `eth_estimateGas`, etc.) without re-encoding.
 *
 * @property from Sender address (hex, 0x-prefixed).
 * @property to Target contract address (hex, 0x-prefixed).
 * @property value Wei value as a hex string (e.g. `"0x0"` for non-payable calls).
 * @property data Hex-encoded calldata (e.g. an ABI-encoded ERC-20 `transfer(...)` blob).
 */
data class RainTransactionParameters(
    val from: String,
    val to: String,
    val value: String,
    val data: String
)
