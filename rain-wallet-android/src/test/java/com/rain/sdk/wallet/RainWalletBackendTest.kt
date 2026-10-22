package com.rain.sdk.wallet

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The embedded backend identity per environment, and the production-host refusal read through the
 * one parser the managed configuration validates the URL with. `RainWalletMappingTest` pins the
 * `Production` refusal and the `Custom` rows where a host meets them, at `RainProvider(...)`.
 */
class RainWalletBackendTest {

    @Test
    fun `the sandbox row is the shared organization on Rain's sandbox API`() {
        val row = RainWalletBackend.rowFor(RainWalletEnvironment.Sandbox)
        assertThat(row.organizationId).isEqualTo("63495e45-8e64-42b5-b602-c68f019ca806")
        assertThat(row.apiBaseUrl).isEqualTo("https://api-dev.rain.xyz")
    }

    @Test
    fun `a custom URL on production's host is refused like Production, however the host is spelled`() {
        // The plain forms, then spellings a second parser read as another host or failed on, while the
        // client's own parser resolves every one of them to api.rain.xyz.
        val spellings = listOf(
            "https://api.rain.xyz",
            "https://api.rain.xyz/",
            " https://API.rain.xyz:443/ ",
            "https:\\\\api.rain.xyz",
            "https://api%2Erain.xyz",
            "https://api.rain.xyz.",
        )
        for (url in spellings) {
            val refused = assertThrows(RainError.InvalidConfig::class.java) { RainWalletBackend.rowFor(RainWalletEnvironment.Custom(url)) }
            assertWithMessage(url).that(refused).hasMessageThat().contains("Production")
        }
        // The other bypass spellings carry a query, a path or userinfo, which the parser refuses before the host is compared.
        for (url in listOf("https://api.rain.xyz/?{", "https://api.rain.xyz\\v1", "https://u:p{@api.rain.xyz")) {
            val refused = assertThrows(RainError.InvalidConfig::class.java) { RainWalletBackend.rowFor(RainWalletEnvironment.Custom(url)) }
            assertWithMessage(url).that(refused.errorCode).isEqualTo(RainErrorCode.INVALID_CONFIG)
        }
    }
}
