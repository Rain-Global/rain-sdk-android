package com.rain.sdk.turnkey

import android.content.Context
import android.content.SharedPreferences
import com.turnkey.types.V1Attestation
import com.turnkey.types.V1AuthenticatorTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Where a passkey sign-up Rain left in doubt keeps its registration for the next call to resend; one at a time. */
internal interface PendingPasskeySignupStore {
    suspend fun load(): PasskeyRegistration?

    suspend fun save(registration: PasskeyRegistration)

    suspend fun clear()
}

/** The process's memory: the controller's default, and the tests'. */
internal class InMemoryPendingPasskeySignupStore : PendingPasskeySignupStore {
    private var registration: PasskeyRegistration? = null

    override suspend fun load(): PasskeyRegistration? = registration

    override suspend fun save(registration: PasskeyRegistration) {
        this.registration = registration
    }

    override suspend fun clear() {
        registration = null
    }
}

/**
 * `SharedPreferences`, so the registration survives a relaunch. The challenge and the attestation are
 * what Rain receives, public by construction, so plain preferences hold them; a value that does not
 * decode reads as none. Read and written on [ioDispatcher], where the first touch loads the file and
 * each write is committed before the call returns. A write the disk refused is silent (`commit()`'s
 * result is not read) and shows only after a relaunch: a failed save loses the registration, a failed
 * clear resends a settled one.
 */
internal class SharedPreferencesPendingPasskeySignupStore(
    preferencesProvider: () -> SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PendingPasskeySignupStore {

    private val preferences: SharedPreferences by lazy(preferencesProvider)

    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this({ context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }, ioDispatcher)

    override suspend fun load(): PasskeyRegistration? =
        withContext(ioDispatcher) { preferences.getString(KEY, null)?.let { decode(it) } }

    override suspend fun save(registration: PasskeyRegistration) {
        withContext(ioDispatcher) { preferences.edit().putString(KEY, encode(registration)).commit() }
    }

    override suspend fun clear() {
        withContext(ioDispatcher) { preferences.edit().remove(KEY).commit() }
    }

    companion object {
        internal const val FILE_NAME = "com.rain.sdk.turnkey.pending_passkey_signup"
        private const val KEY = "registration"

        internal fun encode(registration: PasskeyRegistration): String = JSONObject()
            .put("challenge", registration.challenge)
            .put("credentialId", registration.attestation.credentialId)
            .put("clientDataJson", registration.attestation.clientDataJson)
            .put("attestationObject", registration.attestation.attestationObject)
            .put("transports", JSONArray(registration.attestation.transports.map { it.name }))
            .toString()

        @Suppress("SwallowedException")
        internal fun decode(json: String): PasskeyRegistration? = try {
            val stored = JSONObject(json)
            val transports = stored.getJSONArray("transports")
            PasskeyRegistration(
                challenge = stored.getString("challenge"),
                attestation = V1Attestation(
                    attestationObject = stored.getString("attestationObject"),
                    clientDataJson = stored.getString("clientDataJson"),
                    credentialId = stored.getString("credentialId"),
                    transports = List(transports.length()) { V1AuthenticatorTransport.valueOf(transports.getString(it)) },
                ),
            )
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
