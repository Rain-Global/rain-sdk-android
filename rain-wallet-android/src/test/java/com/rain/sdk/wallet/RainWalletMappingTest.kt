package com.rain.sdk.wallet

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import com.rain.sdk.turnkey.LoginContact
import com.rain.sdk.turnkey.TurnkeyAuthState
import com.rain.sdk.turnkey.TurnkeySessionPolicy
import com.rain.sdk.turnkey.TurnkeySessionState
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/** Every neutral type maps one-to-one onto its backing type, and value semantics hold. */
class RainWalletMappingTest {

    private val realBacking = RealBackingGuard()

    @After
    fun tearDown() = realBacking.tearDown()

    @Test
    fun `the default policy maps onto the backing defaults`() {
        // The backing policy is a data class, so this one assertion pins all six defaults.
        assertThat(RainWalletSessionPolicy().toBacking()).isEqualTo(TurnkeySessionPolicy())
    }

    @Test
    fun `a customised policy maps every field`() {
        val backing = RainWalletSessionPolicy(
            refreshBufferSeconds = 30,
            autoRefresh = false,
            refreshExpirationSeconds = 1_200,
            maxTransientRetries = 5,
            initialRetryDelayMs = 1_000,
            maxRetryDelayMs = 8_000,
        ).toBacking()

        assertThat(backing.refreshBufferSeconds).isEqualTo(30L)
        assertThat(backing.autoRefresh).isFalse()
        assertThat(backing.refreshExpirationSeconds).isEqualTo(1_200L)
        assertThat(backing.maxTransientRetries).isEqualTo(5)
        assertThat(backing.initialRetryDelayMs).isEqualTo(1_000L)
        assertThat(backing.maxRetryDelayMs).isEqualTo(8_000L)
    }

    @Test
    fun `out-of-range policy values are refused at construction, naming the value`() {
        fun refused(build: () -> RainWalletSessionPolicy): String =
            assertThrows(IllegalArgumentException::class.java) { build() }.message.orEmpty()

        assertThat(refused { RainWalletSessionPolicy(refreshBufferSeconds = -1) })
            .contains("refreshBufferSeconds must be >= 0, was -1")
        assertThat(refused { RainWalletSessionPolicy(refreshExpirationSeconds = 0) })
            .contains("refreshExpirationSeconds must be > 0, was 0")
        assertThat(refused { RainWalletSessionPolicy(maxTransientRetries = -1) })
            .contains("maxTransientRetries must be >= 0, was -1")
        assertThat(refused { RainWalletSessionPolicy(initialRetryDelayMs = -1) })
            .contains("initialRetryDelayMs must be >= 0, was -1")
        assertThat(refused { RainWalletSessionPolicy(initialRetryDelayMs = 5_000, maxRetryDelayMs = 4_000) })
            .contains("maxRetryDelayMs must be >= initialRetryDelayMs, was 4000 vs 5000")
    }

    @Test
    fun `session states map one-to-one and carry the expiry`() {
        assertThat(TurnkeySessionState.Loading.toRainWallet()).isEqualTo(RainWalletSessionState.Loading)
        assertThat(TurnkeySessionState.Active(42.5).toRainWallet()).isEqualTo(RainWalletSessionState.Active(42.5))
        assertThat(TurnkeySessionState.Expired.toRainWallet()).isEqualTo(RainWalletSessionState.Expired)
        assertThat(TurnkeySessionState.Unauthenticated.toRainWallet())
            .isEqualTo(RainWalletSessionState.Unauthenticated)
    }

    @Test
    fun `an active state compares by expiry`() {
        assertThat(RainWalletSessionState.Active(1.0)).isEqualTo(RainWalletSessionState.Active(1.0))
        assertThat(RainWalletSessionState.Active(1.0)).isNotEqualTo(RainWalletSessionState.Active(2.0))
        assertThat(RainWalletSessionState.Active(1.0).hashCode())
            .isEqualTo(RainWalletSessionState.Active(1.0).hashCode())
        assertThat(RainWalletSessionState.Active(1.0).toString()).contains("1.0")
    }

    @Test
    fun `auth states map one-to-one`() {
        assertThat(TurnkeyAuthState.Loading.toRainWallet()).isEqualTo(RainWalletAuthState.Loading)
        assertThat(TurnkeyAuthState.Authenticated.toRainWallet()).isEqualTo(RainWalletAuthState.Authenticated)
        assertThat(TurnkeyAuthState.Unauthenticated.toRainWallet()).isEqualTo(RainWalletAuthState.Unauthenticated)
    }

    @Test
    fun `contacts map to the matching backing contact with the value unchanged`() {
        assertThat(RainWalletContact.Email("User@Example.com").toBacking())
            .isEqualTo(LoginContact.Email("User@Example.com"))
        assertThat(RainWalletContact.Phone("+1 (555) 123-4567").toBacking())
            .isEqualTo(LoginContact.Phone("+1 (555) 123-4567"))
    }

    @Test
    fun `contacts compare by value and hide it in toString`() {
        assertThat(RainWalletContact.Email("a@b.c")).isEqualTo(RainWalletContact.Email("a@b.c"))
        assertThat(RainWalletContact.Email("a@b.c").hashCode()).isEqualTo(RainWalletContact.Email("a@b.c").hashCode())
        assertThat(RainWalletContact.Email("a@b.c")).isNotEqualTo(RainWalletContact.Phone("a@b.c"))
        assertThat(RainWalletContact.Email("a@b.c").hashCode()).isNotEqualTo(RainWalletContact.Phone("a@b.c").hashCode())
        assertThat(RainWalletContact.Email("a@b.c").toString()).doesNotContain("a@b.c")
        assertThat(RainWalletContact.Phone("+15551234567").toString()).doesNotContain("555")
    }

    @Test
    fun `every key account maps to the backing family of the same name`() {
        // Totality over the wallet enum; the exhaustive `when` in toBacking() already guarantees
        // no wallet constant is unmapped, so this pins that none is crossed.
        assertThat(RainWalletKeyAccount.entries.map { it.toBacking().name })
            .containsExactlyElementsIn(RainWalletKeyAccount.entries.map { it.name })
            .inOrder()
    }

    @Test
    fun `the policy prints every field`() {
        assertThat(RainWalletSessionPolicy().toString()).isEqualTo(
            "RainWalletSessionPolicy(refreshBufferSeconds=60, autoRefresh=true, refreshExpirationSeconds=null, " +
                "maxTransientRetries=2, initialRetryDelayMs=500, maxRetryDelayMs=4000)"
        )
    }

    @Test
    fun `the config prints every field, the functions as set or null, and the environment by name`() {
        assertThat(config().toString()).isEqualTo(
            "RainWalletConfig(authTokenProvider=set, environment=Sandbox, sessionPolicy=${RainWalletSessionPolicy()}, " +
                "onSessionExpired=null, sponsorGas=true, passkeyDomain=null)"
        )
        assertThat(config(onSessionExpired = {}).toString()).contains("onSessionExpired=set")
        assertThat(config(passkeyDomain = "passkeys.example.com").toString())
            .endsWith("passkeyDomain=passkeys.example.com)")
        assertThat(config(environment = RainWalletEnvironment.Custom("https://staging.example.com")).toString())
            .contains("environment=Custom(apiBaseUrl=https://staging.example.com)")
    }

    @Test
    fun `a null or blank passkeyDomain maps to none, a host name is trimmed`() {
        useRealBacking()
        val application = mockk<Application>()

        assertThat(config().toBacking(application).managedPasskeyDomain).isNull()
        assertThat(config(passkeyDomain = "   ").toBacking(application).managedPasskeyDomain).isNull()
        assertThat(config(passkeyDomain = " passkeys.example.com ").toBacking(application).managedPasskeyDomain)
            .isEqualTo("passkeys.example.com")
    }

    @Test
    fun `a passkeyDomain with a scheme, port or path is refused when the provider is built`() {
        useRealBacking()
        val application = mockk<Application>()

        for (malformed in listOf("https://passkeys.example.com", "passkeys.example.com:443", "passkeys.example.com/app")) {
            val thrown = assertThrows(RainError.InvalidConfig::class.java) {
                RainProvider(application, config(passkeyDomain = malformed))
            }
            assertThat(thrown.message).contains("two labels")
        }
    }

    @Test
    fun `the environment picks the backend row the backing is built with, and Production, the default, is refused at construction`() {
        useRealBacking()
        val application = mockk<Application>()

        val sandbox = config().toBacking(application)
        assertThat(sandbox.managedOrganizationId).isEqualTo(RainWalletBackend.ORGANIZATION_ID)
        assertThat(sandbox.managedRainApiBaseUrl).isEqualTo("https://api-dev.rain.xyz")

        // Production is the default and is refused where a host would feel it: at construction, before any login.
        assertThat(RainWalletConfig(authTokenProvider = { "wat" }).environment).isEqualTo(RainWalletEnvironment.Production)
        val defaulted = assertThrows(RainError.InvalidConfig::class.java) {
            RainProvider(application, RainWalletConfig(authTokenProvider = { "wat" }))
        }
        assertThat(defaulted.errorCode).isEqualTo(RainErrorCode.INVALID_CONFIG)
        assertThat(defaulted).hasMessageThat().contains("Production")
        assertThat(defaulted).hasMessageThat().contains("Sandbox")
        val production = assertThrows(RainError.InvalidConfig::class.java) {
            RainProvider(application, config(environment = RainWalletEnvironment.Production))
        }
        assertThat(production).hasMessageThat().contains("Production")
        val disguised = assertThrows(RainError.InvalidConfig::class.java) {
            RainProvider(application, config(environment = RainWalletEnvironment.Custom("https://api.rain.xyz")))
        }
        assertThat(disguised).hasMessageThat().contains("Production")

        val custom = config(environment = RainWalletEnvironment.Custom("https://staging.example.com/")).toBacking(application)
        assertThat(custom.managedOrganizationId).isEqualTo(RainWalletBackend.ORGANIZATION_ID)
        assertThat(custom.managedRainApiBaseUrl).isEqualTo("https://staging.example.com")
    }

    @Test
    fun `a custom URL that is not https, or names more than a host, is refused when the provider is built`() {
        useRealBacking()
        val application = mockk<Application>()

        val refused = listOf(
            "http://staging.example.com",
            "staging.example.com",
            "",
            "http://localhost:8080",
            "https://staging.example.com/v1",
            "https://staging.example.com/?env=1",
            "https://staging.example.com/#rain",
            "https://user:secret@staging.example.com",
        )
        for (bad in refused) {
            val thrown = assertThrows(RainError.InvalidConfig::class.java) {
                RainProvider(application, config(environment = RainWalletEnvironment.Custom(bad)))
            }
            assertWithMessage(bad).that(thrown.message).contains("rainApiBaseUrl")
        }
    }

    @Test
    fun `the backing's token supplier hands the host the contact in the wallet's own terms, null included`() {
        useRealBacking()
        val asked = mutableListOf<RainWalletContact?>()
        val backing = config(authTokenProvider = { contact ->
            asked += contact
            "wat_${asked.size}"
        }).toBacking(mockk<Application>())
        val supplier = checkNotNull(backing.managedAuthTokenProvider)

        val first = runBlocking { supplier(LoginContact.Email("user@example.com")) }
        val second = runBlocking { supplier(LoginContact.Phone("+13214567890")) }
        val third = runBlocking { supplier(null) }

        assertThat(listOf(first, second, third)).containsExactly("wat_1", "wat_2", "wat_3").inOrder()
        assertThat(asked).containsExactly(
            RainWalletContact.Email("user@example.com"),
            RainWalletContact.Phone("+13214567890"),
            null,
        ).inOrder()
    }

    private fun config(
        authTokenProvider: suspend (RainWalletContact?) -> String = { "wat" },
        environment: RainWalletEnvironment = RainWalletEnvironment.Sandbox,
        onSessionExpired: (() -> Unit)? = null,
        passkeyDomain: String? = null,
    ) = RainWalletConfig(
        authTokenProvider = authTokenProvider,
        environment = environment,
        onSessionExpired = onSessionExpired,
        passkeyDomain = passkeyDomain,
    )

    private fun useRealBacking() = realBacking.useRealBacking()
}
