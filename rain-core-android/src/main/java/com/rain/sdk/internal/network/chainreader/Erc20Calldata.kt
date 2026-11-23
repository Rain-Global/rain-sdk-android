package com.rain.sdk.internal.network.chainreader

import com.rain.sdk.internal.utils.strippingHexPrefix

/**
 * Hand-rolled calldata for the ERC-20 *read* functions the chain reader issues.
 *
 * Write-path calldata (`transfer`, `approve`) is encoded by
 * [com.rain.sdk.internal.abi.Erc20Abi] against the ERC-20 ABI — this is only for `eth_call`
 * reads, matching how [Multicall3.encodeBalanceOf] already encodes `balanceOf` here.
 */
internal object Erc20Calldata {

    /** `allowance(owner, spender)` — how much of `owner`'s balance `spender` may still move. */
    fun allowance(owner: String, spender: String): String =
        "0x" + ERC20Selectors.ALLOWANCE + word(owner) + word(spender)

    /** Left-pads a 20-byte address into a 32-byte ABI word. */
    private fun word(address: String): String {
        val clean = address.strippingHexPrefix().lowercase()
        return "0".repeat(maxOf(0, 64 - clean.length)) + clean
    }
}
