package com.rain.sdk.portal

import com.rain.sdk.error.RainError
import com.rain.sdk.utils.EthereumConverter
import io.portalhq.android.provider.data.PortalProviderResult
import io.portalhq.android.provider.data.PortalProviderRpcResponse

/**
 * Portal-specific bridge from a [PortalProviderResult] to a normalized hex string.
 *
 * Kept here (rather than inside [EthereumConverter]) so the converter has no `portal-android`
 * dependency and can be reused from non-Portal code paths. The normalization rules
 * (`nil` / missing-prefix / too-short fallback to `"0x0"`) live in
 * [EthereumConverter.normalizedHexString] so both paths agree.
 */
internal fun PortalProviderResult.toHexString(): String {
    val hex: String? = when (val inner = result) {
        is String -> inner
        is PortalProviderRpcResponse -> inner.result as? String
        else -> null
    }
    return EthereumConverter.normalizedHexString(hex)
}

/**
 * Extracts a transaction hash from a Portal provider result. Throws
 * [RainError.ProviderError] if Portal returned a non-string result (which would otherwise
 * silently parse as junk downstream).
 */
internal fun PortalProviderResult.toTransactionHash(): String {
    val inner = result
    if (inner !is String) {
        throw RainError.ProviderError(
            IllegalStateException("Portal returned invalid transaction result: $inner")
        )
    }
    return inner
}
