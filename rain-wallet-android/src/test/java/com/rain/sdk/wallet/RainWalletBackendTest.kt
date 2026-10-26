package com.rain.sdk.wallet

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The embedded backend identity per environment: each one pairs its own organization with its own Rain API host. */
class RainWalletBackendTest {

    @Test
    fun `the sandbox row is the sandbox organization on Rain's sandbox API`() {
        val row = RainWalletBackend.rowFor(RainWalletEnvironment.Sandbox)
        assertThat(row.organizationId).isEqualTo("63495e45-8e64-42b5-b602-c68f019ca806")
        assertThat(row.apiBaseUrl).isEqualTo("https://api-dev.rain.xyz")
    }

    @Test
    fun `the production row is the production organization on Rain's production API`() {
        val row = RainWalletBackend.rowFor(RainWalletEnvironment.Production)
        assertThat(row.organizationId).isEqualTo("306c2e82-5ae2-4be6-8b41-1ab0cc6b0ab5")
        assertThat(row.apiBaseUrl).isEqualTo("https://api.rain.xyz")
    }
}
