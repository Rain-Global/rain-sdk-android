package com.rain.sdk.turnkey

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [TurnkeyManagedConfigurator], the guard around the vendor's process-wide one-shot configuration:
 * idempotent for the same ids and passkey domain, refusing different ones, blank ids and a
 * foreign initialization, and retrying after a failed one. Process-global state, reset around
 * every test; gated on JDK 24 like every Turnkey suite.
 */
class TurnkeyManagedConfiguratorTest {

    @Before
    fun setUp() {
        assumeJdk24()
        TurnkeyManagedConfigurator.resetForTest()
    }

    @After
    fun tearDown() = TurnkeyManagedConfigurator.resetForTest()

    @Test
    fun `configure is idempotent for identical ids and initializes the vendor once`() = runTest {
        var initCalls = 0
        TurnkeyManagedConfigurator.initImpl = { _, _, _ -> initCalls++ }
        TurnkeyManagedConfigurator.vendorInitializedProbe = { false }
        val app = mockk<Application>()

        assertThat(TurnkeyManagedConfigurator.configure(app, "org-a", "https://api-a.example", null)).isNull()
        assertThat(TurnkeyManagedConfigurator.configure(app, "org-a", "https://api-a.example", null)).isNull()

        assertThat(initCalls).isEqualTo(1)
    }

    @Test
    fun `configure with different ids returns InvalidConfig and leaves the first configuration in place`() = runTest {
        var initCalls = 0
        TurnkeyManagedConfigurator.initImpl = { _, _, _ -> initCalls++ }
        TurnkeyManagedConfigurator.vendorInitializedProbe = { false }
        val app = mockk<Application>()

        assertThat(TurnkeyManagedConfigurator.configure(app, "org-a", "https://api-a.example", null)).isNull()
        val mismatch = TurnkeyManagedConfigurator.configure(app, "org-b", "https://api-a.example", null)

        assertThat(mismatch).isInstanceOf(RainError.InvalidConfig::class.java)
        assertThat(initCalls).isEqualTo(1)
        // The original ids still work.
        assertThat(TurnkeyManagedConfigurator.configure(app, "org-a", "https://api-a.example", null)).isNull()
    }

    @Test
    fun `configure hands the passkey domain to the vendor configuration, null when passkeys are off`() = runTest {
        val domains = mutableListOf<String?>()
        TurnkeyManagedConfigurator.initImpl = { _, _, domain -> domains += domain }
        TurnkeyManagedConfigurator.vendorInitializedProbe = { false }

        assertThat(TurnkeyManagedConfigurator.configure(mockk<Application>(), "org-a", "https://api-a.example", "passkeys.example.com")).isNull()
        assertThat(domains).containsExactly("passkeys.example.com")

        TurnkeyManagedConfigurator.resetForTest()
        TurnkeyManagedConfigurator.initImpl = { _, _, domain -> domains += domain }
        TurnkeyManagedConfigurator.vendorInitializedProbe = { false }
        assertThat(TurnkeyManagedConfigurator.configure(mockk<Application>(), "org-a", "https://api-a.example", null)).isNull()
        assertThat(domains).containsExactly("passkeys.example.com", null).inOrder()
    }

    @Test
    fun `configure with a different passkey domain returns InvalidConfig and keeps the first configuration`() = runTest {
        // The vendor reads the relying party from its one-shot configuration, so a second domain in
        // the same launch would silently use the first one; refused like different ids, the same rule
        // on both of Rain's SDKs.
        var initCalls = 0
        TurnkeyManagedConfigurator.initImpl = { _, _, _ -> initCalls++ }
        TurnkeyManagedConfigurator.vendorInitializedProbe = { false }
        val app = mockk<Application>()

        assertThat(TurnkeyManagedConfigurator.configure(app, "org-a", "https://api-a.example", "passkeys.example.com")).isNull()
        val mismatch = TurnkeyManagedConfigurator.configure(app, "org-a", "https://api-a.example", "other.example.com")
        val droppedDomain = TurnkeyManagedConfigurator.configure(app, "org-a", "https://api-a.example", null)

        assertThat(mismatch).isInstanceOf(RainError.InvalidConfig::class.java)
        assertThat(mismatch).hasMessageThat().contains("different ids")
        assertThat(droppedDomain).isInstanceOf(RainError.InvalidConfig::class.java)
        assertThat(initCalls).isEqualTo(1)
        assertThat(TurnkeyManagedConfigurator.configure(app, "org-a", "https://api-a.example", "passkeys.example.com")).isNull()
    }

    @Test
    fun `configure rejects blank ids without recording them`() = runTest {
        var initCalls = 0
        TurnkeyManagedConfigurator.initImpl = { _, _, _ -> initCalls++ }
        TurnkeyManagedConfigurator.vendorInitializedProbe = { false }
        val app = mockk<Application>()

        assertThat(
            TurnkeyManagedConfigurator.configure(app, "", "https://api-a.example", null)
        ).isInstanceOf(RainError.InvalidConfig::class.java)
        assertThat(
            TurnkeyManagedConfigurator.configure(app, "org-a", "  ", null)
        ).isInstanceOf(RainError.InvalidConfig::class.java)
        assertThat(initCalls).isEqualTo(0)
        // A blank attempt must not burn the process slot.
        assertThat(TurnkeyManagedConfigurator.configure(app, "org-a", "https://api-a.example", null)).isNull()
        assertThat(initCalls).isEqualTo(1)
    }

    @Test
    fun `configure refuses a vendor context that was initialized outside the SDK`() = runTest {
        var initCalls = 0
        TurnkeyManagedConfigurator.initImpl = { _, _, _ -> initCalls++ }
        TurnkeyManagedConfigurator.vendorInitializedProbe = { true }

        val result = TurnkeyManagedConfigurator.configure(mockk<Application>(), "org-a", "https://api-a.example", null)

        assertThat(result).isInstanceOf(RainError.InvalidConfig::class.java)
        assertThat(initCalls).isEqualTo(0)
    }

    @Test
    fun `a failing vendor initialization returns InternalError, records nothing, and can be retried`() = runTest {
        var attempts = 0
        TurnkeyManagedConfigurator.initImpl = { _, _, _ ->
            attempts++
            if (attempts == 1) error("keystore unavailable")
        }
        // Like the vendor: it reads as initialized from the first attempt on, even a failed one.
        TurnkeyManagedConfigurator.vendorInitializedProbe = { attempts > 0 }
        val app = mockk<Application>()

        val first = TurnkeyManagedConfigurator.configure(app, "org-a", "https://api-a.example", null)
        assertThat(first).isInstanceOf(RainError.InternalError::class.java)
        // Nothing was recorded, so the same ids try again — and the SDK's own attempt must not be
        // mistaken for a context configured outside the SDK.
        assertThat(TurnkeyManagedConfigurator.configure(app, "org-a", "https://api-a.example", null)).isNull()
        assertThat(attempts).isEqualTo(2)
    }
}
