package com.rain.sdk.sample

import android.app.Application
import com.turnkey.core.TurnkeyContext
import com.turnkey.core.models.InitOtpResult
import com.turnkey.core.models.OtpType
import com.turnkey.core.models.TurnkeyConfig
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1Curve
import com.turnkey.types.V1PathFormat
import com.turnkey.types.V1User
import com.turnkey.types.V1WalletAccountParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sample-app glue that drives Turnkey's Kotlin SDK end-to-end (init, one-time code by email or SMS,
 * wallet provisioning) so the host app can hand a ready `TurnkeyContext` to the SDK's bring-your-own
 * `TurnkeyConfig(turnkey = …)`.
 *
 * This file is NOT part of the Rain SDK. It is reference code a host app writes itself when it
 * runs Turnkey on its own terms; the SDK owns none of this in bring-your-own mode (see
 * docs/TURNKEY_SUPPORT.md). An app that wants the SDK to own authentication uses the Rain wallet
 * instead. Both drive one process-wide Turnkey singleton, so a launch that configured one cannot
 * switch to the other without a relaunch.
 */
object TurnkeyAuthSample {

    /** Hand this to `TurnkeyConfig(turnkey = …)` once auth is complete. */
    val context: TurnkeyContext get() = TurnkeyContext

    /** Sub-organization ID minted (or reused) for the authenticated user. Null before login. */
    val subOrganizationId: String?
        get() = TurnkeyContext.session.value?.organizationId

    /** Minimum session lifetime (seconds) still worth resuming. */
    private const val SESSION_MIN_REMAINING_SECONDS = 30.0

    private val ETHEREUM_ACCOUNT = V1WalletAccountParams(
        addressFormat = V1AddressFormat.ADDRESS_FORMAT_ETHEREUM,
        curve = V1Curve.CURVE_SECP256K1,
        path = "m/44'/60'/0'/0/0",
        pathFormat = V1PathFormat.PATH_FORMAT_BIP32,
    )
    private val SOLANA_ACCOUNT = V1WalletAccountParams(
        addressFormat = V1AddressFormat.ADDRESS_FORMAT_SOLANA,
        curve = V1Curve.CURVE_ED25519,
        path = "m/44'/501'/0'/0'",
        pathFormat = V1PathFormat.PATH_FORMAT_BIP32,
    )

    /**
     * True when an authenticated, unexpired Turnkey session is already loaded. The Turnkey SDK
     * restores a previously selected session from secure storage during [init], so after init this
     * reports whether the one-time-code step can be skipped.
     */
    fun hasActiveSession(): Boolean {
        val session = TurnkeyContext.session.value ?: return false
        val nowSeconds = System.currentTimeMillis() / 1000.0
        return session.expiry > nowSeconds + SESSION_MIN_REMAINING_SECONDS
    }

    /**
     * The contact of the user the restored session belongs to, on [channel]: the email address or
     * the phone number, or null if it cannot be determined. Callers deciding whether to reuse a
     * restored session MUST compare this against the contact being logged in: a valid session for
     * another contact must not be reused.
     */
    suspend fun activeSessionContact(channel: ContactChannel): String? {
        if (!hasActiveSession()) return null
        return TurnkeyContext.user.value?.contactOn(channel) ?: refreshedUser()?.contactOn(channel)
    }

    private fun V1User.contactOn(channel: ContactChannel): String? = when (channel) {
        ContactChannel.Email -> userEmail
        ContactChannel.Phone -> userPhoneNumber
    }

    @Suppress("TooGenericExceptionCaught") // the vendor's refresh throws untyped; a failure only means "owner unknown"
    private suspend fun refreshedUser(): V1User? = try {
        TurnkeyContext.refreshUser()
        TurnkeyContext.user.value
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SampleLog.w("TurnkeyAuth", "refreshUser failed while resolving session owner: ${e.javaClass.simpleName}")
        null
    }

    /** Clears all stored Turnkey sessions (full logout). Safe no-op if none exist. */
    @Suppress("TooGenericExceptionCaught") // the vendor's clear throws untyped; logout is best effort
    suspend fun logout() {
        try {
            TurnkeyContext.clearAllSessions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SampleLog.w("TurnkeyAuth", "logout (clearAllSessions) failed: ${e.javaClass.simpleName}")
        }
    }

    /** The ids Turnkey finished initializing with; set only after a successful init. */
    @Volatile
    private var configuredWith: Pair<String, String>? = null

    /** The ids this driver handed to `initSuspend`, whatever the outcome; the vendor keeps the first pair it saw. */
    @Volatile
    private var attemptedWith: Pair<String, String>? = null

    /** How long to wait for the singleton's readiness signal before giving up. */
    private const val READY_TIMEOUT_MS = 15_000L

    /**
     * Initializes the Turnkey singleton. Idempotent once it has succeeded; editing the ids
     * afterwards throws, because Turnkey's `initSuspend` silently ignores a second configuration
     * within the same process. A singleton another provider configured first (the Rain wallet) is
     * refused for the same reason: the vendor would ignore these ids and run against the other
     * configuration. A failed or cancelled attempt is not latched, so the next tap retries and
     * reports the stored failure instead of spinning.
     */
    suspend fun init(app: Application, organizationId: String, authProxyConfigId: String) {
        val snapshot = organizationId to authProxyConfigId
        configuredWith?.let { existing ->
            check(existing == snapshot) {
                "Turnkey is already configured with different values this session. Fully kill the " +
                    "app and relaunch to change the Organization ID or Auth Proxy Config ID."
            }
            awaitReadyBounded()
            return
        }
        attemptedWith?.let { attempted ->
            // A failed or timed-out attempt already configured the vendor with these ids; the vendor
            // ignores any later pair, so only a retry with the same ids can be honest.
            check(attempted == snapshot) {
                "Turnkey was already initialized with different ids in this launch; relaunch the app " +
                    "to change the Organization ID or Auth Proxy Config ID."
            }
        } ?: check(!vendorAlreadyConfigured()) {
            "Turnkey was already configured by another provider in this launch (the Rain wallet); " +
                "relaunch the app to use this tab."
        }
        attemptedWith = snapshot
        SampleLog.d(
            "TurnkeyAuth",
            "init org=${SampleLog.maskToken(organizationId)} proxy=${SampleLog.maskToken(authProxyConfigId)}"
        )
        TurnkeyContext.initSuspend(
            app = app,
            cfg = TurnkeyConfig(organizationId = organizationId, authProxyConfigId = authProxyConfigId),
        )
        awaitReadyBounded()
        configuredWith = snapshot
        SampleLog.d("TurnkeyAuth", "TurnkeyContext ready")
    }

    /** The vendor assigns its public `appContext` only inside `initSuspend`; reading it is the probe the SDK uses too. */
    private fun vendorAlreadyConfigured(): Boolean = runCatching { TurnkeyContext.appContext }.isSuccess

    private suspend fun awaitReadyBounded() {
        val ready = withTimeoutOrNull(READY_TIMEOUT_MS) {
            TurnkeyContext.awaitReady()
            true
        } ?: false
        check(ready) { "Turnkey did not finish initializing in time; relaunch the app." }
    }

    /**
     * Starts the one-time-code flow on [channel]. Returns the [InitOtpResult], whose `otpId` and
     * `otpEncryptionTargetBundle` [verifyOtp] both needs. SMS needs SMS one-time codes enabled on
     * the auth proxy configuration. An email is lowercased before it goes out, and [verifyOtp]
     * lowercases the same way, so the two calls hand the vendor one spelling.
     */
    suspend fun sendOtp(contact: String, channel: ContactChannel): InitOtpResult {
        val canonical = channel.canonical(contact)
        SampleLog.d("TurnkeyAuth", "sendOtp ${channel.name} to=${channel.mask(canonical)}")
        val result = TurnkeyContext.initOtp(otpType = channel.otpType, contact = canonical)
        SampleLog.d("TurnkeyAuth", "OTP sent otpId=${SampleLog.maskToken(result.otpId)}")
        return result
    }

    /** The vendor's name for each channel's code. */
    private val ContactChannel.otpType: OtpType
        get() = when (this) {
            ContactChannel.Email -> OtpType.OTP_TYPE_EMAIL
            ContactChannel.Phone -> OtpType.OTP_TYPE_SMS
        }

    /**
     * Verifies the code and creates a Turnkey session. `loginOrSignUpWithOtp` handles first-time
     * sign-up and returning login transparently; [otpEncryptionTargetBundle] comes from the
     * [sendOtp] result, and [contact] and [channel] are the ones the code went to.
     */
    @Suppress("TooGenericExceptionCaught") // the vendor's clear throws untyped; a failed clear is reported by the login
    suspend fun verifyOtp(
        otpId: String,
        otpCode: String,
        otpEncryptionTargetBundle: String,
        contact: String,
        channel: ContactChannel,
    ) {
        SampleLog.d("TurnkeyAuth", "verifyOtp ${channel.name} otpId=${SampleLog.maskToken(otpId)}")
        // A prior login leaves a persisted session under Turnkey's default key, and createSession
        // throws KeyAlreadyExists rather than overwriting it; clear stored sessions first.
        try {
            TurnkeyContext.clearAllSessions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SampleLog.w("TurnkeyAuth", "clearAllSessions failed (continuing): ${e.javaClass.simpleName}")
        }
        TurnkeyContext.loginOrSignUpWithOtp(
            otpId = otpId,
            otpCode = otpCode,
            otpEncryptionTargetBundle = otpEncryptionTargetBundle,
            contact = channel.canonical(contact),
            otpType = channel.otpType,
        )
        SampleLog.d("TurnkeyAuth", "session active subOrgId=${SampleLog.maskToken(subOrganizationId)}")
    }

    /**
     * Ensures the authenticated sub-organization has an Ethereum and a Solana account. A fresh
     * account gets ONE wallet carrying both, a single seed to back up, which is what the Rain wallet
     * provisions too. An existing wallet that lacks a family is only warned about: deriving extra
     * accounts onto an existing seed needs the raw `create_wallet_accounts` API, which this sample
     * keeps out of scope. Returns true when a wallet was created.
     */
    suspend fun ensureWallets(): Boolean {
        TurnkeyContext.refreshWallets()
        val wallets = TurnkeyContext.wallets.value.orEmpty()
        val formats = wallets.flatMap { it.accounts }.map { it.addressFormat }.toSet()
        SampleLog.d("TurnkeyAuth", "ensureWallets wallets=${wallets.size} formats=${formats.size}")
        val hasEthereum = V1AddressFormat.ADDRESS_FORMAT_ETHEREUM in formats
        val hasSolana = V1AddressFormat.ADDRESS_FORMAT_SOLANA in formats
        return when {
            hasEthereum && hasSolana -> false
            wallets.isNotEmpty() -> {
                SampleLog.w(
                    "TurnkeyAuth",
                    "existing wallet lacks an Ethereum or Solana account; add it in Turnkey (create_wallet_accounts)"
                )
                false
            }
            else -> {
                val created = TurnkeyContext.createWallet(
                    walletName = "Rain SDK Sample Wallet",
                    accounts = listOf(ETHEREUM_ACCOUNT, SOLANA_ACCOUNT),
                    mnemonicLength = 12L,
                )
                SampleLog.i("TurnkeyAuth", "created wallet id=${created.walletId} accounts=${created.addresses.size}")
                true
            }
        }
    }
}
