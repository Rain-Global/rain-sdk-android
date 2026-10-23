package com.rain.sdk.turnkey

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.turnkey.types.V1Attestation
import com.turnkey.types.V1AuthenticatorTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * [SharedPreferencesPendingPasskeySignupStore] at its own boundary: the JSON it writes, what reads as
 * none, the one preferences key and the dispatcher. The vendor types it round-trips are Java 11 class
 * files, so the suite needs no JDK gate. The preferences are the [FakeSharedPreferences] from
 * `TurnkeyTestFixtures.kt`, and the store runs on the test's scheduler.
 */
class PendingPasskeySignupStoreTest {

    /** Two transports, so the array round-trips as a list in order. */
    private val registration = PasskeyRegistration(
        challenge = "challenge-1",
        attestation = V1Attestation(
            attestationObject = "attestation-1",
            clientDataJson = "client-data-1",
            credentialId = "credential-1",
            transports = listOf(
                V1AuthenticatorTransport.AUTHENTICATOR_TRANSPORT_INTERNAL,
                V1AuthenticatorTransport.AUTHENTICATOR_TRANSPORT_HYBRID,
            ),
        ),
    )

    private val preferences: SharedPreferences = FakeSharedPreferences()

    private fun TestScope.store() = SharedPreferencesPendingPasskeySignupStore({ preferences }, StandardTestDispatcher(testScheduler))

    @Test
    fun `encode writes the five keys with the transports by name, and decode reads the registration back`() {
        val encoded = SharedPreferencesPendingPasskeySignupStore.encode(registration)

        val json = JSONObject(encoded)
        assertThat(json.keys().asSequence().toList())
            .containsExactly("challenge", "credentialId", "clientDataJson", "attestationObject", "transports")
        assertThat(json.getJSONArray("transports").getString(0)).isEqualTo("AUTHENTICATOR_TRANSPORT_INTERNAL")
        assertThat(SharedPreferencesPendingPasskeySignupStore.decode(encoded)).isEqualTo(registration)
    }

    @Test
    fun `decode returns null for anything that is not an encoded registration`() {
        val encoded = SharedPreferencesPendingPasskeySignupStore.encode(registration)
        val stored = listOf(
            "not json" to "not json",
            "empty" to "",
            "array" to "[]",
            "missing field" to JSONObject(encoded).apply { remove("credentialId") }.toString(),
            "unknown transport" to JSONObject(encoded).put("transports", JSONArray(listOf("usb"))).toString(),
        )

        stored.forEach { (case, json) ->
            assertWithMessage(case).that(SharedPreferencesPendingPasskeySignupStore.decode(json)).isNull()
        }
    }

    @Test
    fun `the preferences store holds the registration under its one key until clear, and an undecodable value reads as none`() = runTest {
        val store = store()
        assertThat(store.load()).isNull()

        store.save(registration)
        assertThat(preferences.all.keys).containsExactly("registration")
        assertThat(store.load()).isEqualTo(registration)

        store.clear()
        assertThat(preferences.all).isEmpty()
        assertThat(store.load()).isNull()

        preferences.edit().putString("registration", "{\"challenge\":\"only\"}").apply()
        assertThat(store.load()).isNull()
    }

    @Test
    fun `the preferences store reads and writes on the dispatcher it was given`() = runTest {
        // Hosts call from the main thread and the first touch loads the file: one dispatch per call.
        var dispatches = 0
        val counting = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatches++
                block.run()
            }
        }
        val store = SharedPreferencesPendingPasskeySignupStore({ preferences }, counting)

        store.load()
        assertThat(dispatches).isEqualTo(1)
        store.save(registration)
        assertThat(dispatches).isEqualTo(2)
        store.clear()
        assertThat(dispatches).isEqualTo(3)
        assertThat(preferences.all).isEmpty()
    }
}
