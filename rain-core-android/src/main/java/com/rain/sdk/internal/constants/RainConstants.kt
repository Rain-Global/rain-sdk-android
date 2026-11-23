package com.rain.sdk.internal.constants

internal object RainConstants {
    const val FUNC_ADMIN_NONCE = "adminNonce"
    const val FUNC_WITHDRAW_ASSET = "withdrawAsset"
    const val FUNC_IS_ADMIN = "isAdmin"

    // Network Config
    const val NETWORK_TIMEOUT_SECONDS = 30L

    /**
     * CAIP-2 namespace for EVM chains. Used to build `eip155:<chainId>` identifiers without
     * pulling a provider SDK's namespace enum into core.
     */
    const val EIP155_NAMESPACE = "eip155"

    /**
     * Decimals assumed for an ERC-20 whose `decimals()` could not be read, on the display paths only;
     * money paths refuse to guess.
     */
    const val DEFAULT_ERC20_DECIMALS = 18
}
