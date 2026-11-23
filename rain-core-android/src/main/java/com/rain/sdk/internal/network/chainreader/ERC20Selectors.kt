package com.rain.sdk.internal.network.chainreader

/**
 * 4-byte method selectors for ERC-20 functions (`keccak256(signature)[:4]`).
 * Centralized so the hex literals don't sprawl across adapters.
 */
internal object ERC20Selectors {
    /** `balanceOf(address)` */
    const val BALANCE_OF = "70a08231"

    /** `symbol()` */
    const val SYMBOL = "95d89b41"

    /** `name()` */
    const val NAME = "06fdde03"

    /** `decimals()` */
    const val DECIMALS = "313ce567"

    /** `allowance(address,address)` */
    const val ALLOWANCE = "dd62ed3e"
}
