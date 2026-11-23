package com.rain.sdk.internal.constants

import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.internal.utils.RainHexUtils
import org.junit.Test

/**
 * Registry addresses reach hosts as written, in `Balance.token` and from `tokenMetadata`, and
 * `sendToken` refuses a mixed-case address whose EIP-55 checksum doesn't match. A registry literal
 * with a wrong letter case would therefore refuse the SDK's own token on a send, so every entry
 * must be `0x`-prefixed, well-formed and carry its checksum.
 */
class TokenRegistryTest {

    @Test
    fun `every registry address is well-formed and carries a valid EIP-55 checksum`() {
        val offenders = TokenRegistry.tokensByChainId.flatMap { (chainId, tokens) ->
            tokens
                .filterNot { token ->
                    token.address.startsWith("0x") &&
                        RainHexUtils.isValidAddress(token.address) &&
                        RainHexUtils.hasValidChecksum(token.address)
                }
                .map { token ->
                    val expected = if (RainHexUtils.isValidAddress(token.address)) {
                        RainHexUtils.toChecksumAddress(token.address)
                    } else {
                        "a 0x-prefixed 40-hex address"
                    }
                    "chainId=$chainId ${token.symbol} ${token.address} (expected $expected)"
                }
        }

        assertWithMessage("registry addresses that are malformed or carry a wrong EIP-55 checksum")
            .that(offenders)
            .isEmpty()
    }
}
