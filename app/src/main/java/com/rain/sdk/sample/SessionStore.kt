package com.rain.sdk.sample

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Encrypted copy of the last working credentials, read once at launch to resume the session. */
@Suppress("DEPRECATION")
class SessionStore(context: Context) {

    enum class Provider { Portal, RainWallet, Turnkey, Privy }

    // Unavailable store (broken keystore) behaves like a first run instead of crashing.
    private val prefs: SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create(
            context,
            "rain_sample_session",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure { SampleLog.w("SessionStore", "unavailable: ${it.message}") }.getOrNull()

    init {
        // One-time migration, marked by storeVersion. An earlier sample ran the managed one-time-code
        // flow under the Turnkey name; that record now belongs to the Rain Wallet card, and the
        // Turnkey name means bring-your-own, so the owner moves over and the managed ids go.
        prefs?.let { p ->
            if (!p.contains("storeVersion")) {
                val edit = p.edit()
                if (p.getString("provider", null) == "Turnkey") {
                    edit.putString("provider", Provider.RainWallet.name)
                    p.getString("turnkeyEmail", null)?.let { edit.putString("rainWalletEmail", it) }
                    p.getString("turnkeyPhone", null)?.let { edit.putString("rainWalletPhone", it) }
                    p.getString("turnkeyChannel", null)?.let { edit.putString("rainWalletChannel", it) }
                    edit.remove("turnkeyEmail").remove("turnkeyOrgId").remove("turnkeyAuthProxyConfigId")
                }
                edit.remove("turnkeyPhone").remove("turnkeyChannel").putInt("storeVersion", STORE_VERSION).apply()
            }
        }
    }

    var provider: Provider?
        get() = prefs?.getString("provider", null)?.let { name -> Provider.entries.firstOrNull { it.name == name } }
        set(value) { prefs?.edit()?.putString("provider", value?.name)?.apply() }

    var rainApiKey: String by string("rainApiKey")
    var rainUserId: String by string("rainUserId")
    var portalSessionToken: String by string("portalSessionToken")
    var turnkeyOrgId: String by string("turnkeyOrgId")
    var turnkeyAuthProxyConfigId: String by string("turnkeyAuthProxyConfigId")
    var turnkeyEmail: String by string("turnkeyEmail")

    /**
     * The bring-your-own Turnkey tab's phone contact and the channel of its last login; a blank
     * channel reads as email. Values a pre-version-2 record held under these names belonged to the
     * managed flow and are dropped by the migration above.
     */
    var turnkeyPhone: String by string("turnkeyPhone")
    var turnkeyChannel: String by string("turnkeyChannel")
    var rainWalletEmail: String by string("rainWalletEmail")
    var rainWalletPhone: String by string("rainWalletPhone")

    /** Name of the channel the recorded Rain Wallet session owner logged in on; blank means none recorded. */
    var rainWalletChannel: String by string("rainWalletChannel")

    /**
     * True when the recorded Rain Wallet session owner signed in or up with a passkey and so has no
     * login contact of its own; the contact slots then hold what was attached since. Additive to
     * the record, so a store from before this key reads false.
     */
    var rainWalletPasskeyOwner: Boolean by boolean("rainWalletPasskeyOwner")
    var privyAppId: String by string("privyAppId")
    var privyAppClientId: String by string("privyAppClientId")
    var privyEmail: String by string("privyEmail")

    private companion object {
        /** Bumped when a key changes meaning; the init block migrates once per bump. */
        const val STORE_VERSION = 2
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }

    private fun string(key: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String =
            prefs?.getString(key, "") ?: ""

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            prefs?.edit()?.putString(key, value)?.apply()
        }
    }

    private fun boolean(key: String) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean =
            prefs?.getBoolean(key, false) == true

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            prefs?.edit()?.putBoolean(key, value)?.apply()
        }
    }
}
