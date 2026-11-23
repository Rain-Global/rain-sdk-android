package com.rain.sdk.sample

import android.app.Application
import io.privy.auth.AuthState
import io.privy.auth.LinkedAccount
import io.privy.logging.PrivyLogLevel
import io.privy.sdk.Privy
import io.privy.sdk.PrivyConfig

/**
 * Sample-app glue that drives Privy's Android SDK end-to-end (init, email OTP, embedded-wallet
 * provisioning) so the host app can hand a ready `Privy` singleton to
 * `RainSession.initializePrivy(privy = …)`.
 *
 * This file is NOT part of Rain SDK. It is reference code a host app would write itself — Rain SDK
 * intentionally does not own Privy auth. Copy/adapt for your own app.
 */
object PrivyAuthSample {

    // Privy must be a single instance for the app's lifetime; hold it here after init.
    @Volatile
    private var instance: Privy? = null

    /** Hand this to `RainSession.initializePrivy(privy = …)` once auth is complete. */
    val privy: Privy
        get() = instance ?: error("PrivyAuthSample.init(...) not called yet")

    /** Snapshot of the ids Privy was initialized with, to detect edits made afterwards. */
    private var configuredWith: Pair<String, String>? = null

    /**
     * Initializes the Privy singleton. Idempotent; editing the ids afterwards throws, because
     * Privy cannot be reconfigured without relaunching the app. Main thread, Application context.
     */
    fun init(app: Application, appId: String, appClientId: String) {
        val snapshot = appId to appClientId
        configuredWith?.let { existing ->
            check(existing == snapshot) {
                "Privy is already configured with different values this session. Fully kill the " +
                    "app and relaunch to change the App ID or App Client ID."
            }
            return
        }
        configuredWith = snapshot
        SampleLog.d(
            "PrivyAuth",
            "init appId=${SampleLog.maskToken(appId)} clientId=${SampleLog.maskToken(appClientId)}"
        )
        instance = Privy.init(
            context = app,
            config = PrivyConfig(
                appId = appId,
                appClientId = appClientId,
                logLevel = if (app.isDebuggable) PrivyLogLevel.VERBOSE else PrivyLogLevel.NONE
            )
        )
    }

    /** True when Privy restored an authenticated session from a prior run (skips the OTP round-trip). */
    suspend fun hasActiveSession(): Boolean =
        runCatching { privy.getAuthState() is AuthState.Authenticated }.getOrDefault(false)

    /**
     * Email address linked to the restored Privy session, or null if it cannot be determined.
     * Callers deciding whether to reuse a restored session MUST compare this against the email
     * being logged in — a valid session for a *different* email must not be reused, or the
     * caller would silently act as the previous user.
     */
    suspend fun activeSessionEmail(): String? = runCatching {
        if (privy.getAuthState() !is AuthState.Authenticated) return@runCatching null
        privy.getUser()?.linkedAccounts
            ?.filterIsInstance<LinkedAccount.EmailAccount>()
            ?.firstOrNull()
            ?.emailAddress
    }.onFailure {
        SampleLog.w("PrivyAuth", "failed to resolve session owner: ${it.message}")
    }.getOrNull()

    /** Logs the Privy user out. Safe no-op if not authenticated. */
    suspend fun logout() {
        runCatching { instance?.logout() }
            .onFailure { SampleLog.w("PrivyAuth", "logout failed: ${it.message}") }
    }

    /** Sends an email OTP. Throws on failure. */
    suspend fun sendEmailOtp(email: String) {
        SampleLog.d("PrivyAuth", "sendEmailOtp to=${SampleLog.maskEmail(email)}")
        privy.email.sendCode(email).getOrThrow()
    }

    /** Verifies the OTP and creates a Privy session. Throws on failure. */
    suspend fun verifyEmailOtp(code: String, email: String) {
        SampleLog.d("PrivyAuth", "verifyEmailOtp email=${SampleLog.maskEmail(email)}")
        privy.email.loginWithCode(code = code, email = email).getOrThrow()
        SampleLog.d("PrivyAuth", "session active")
    }

    /**
     * Ensures the authenticated user has an embedded Ethereum wallet. Returns true if a new wallet
     * was created, false if one already existed.
     */
    suspend fun ensureEthereumWallet(): Boolean {
        val user = privy.getUser() ?: error("Privy user not authenticated")
        if (user.embeddedEthereumWallets.isNotEmpty()) {
            SampleLog.d("PrivyAuth", "ensureEthereumWallet — wallet already present")
            return false
        }
        val wallet = user.createEthereumWallet(allowAdditional = false).getOrThrow()
        SampleLog.i("PrivyAuth", "created embedded wallet address=${wallet.address}")
        return true
    }

    /**
     * Ensures the authenticated user has an embedded Solana wallet (Rain's Solana operations use
     * the first one). Returns true if a new wallet was created, false if one already existed.
     */
    suspend fun ensureSolanaWallet(): Boolean {
        val user = privy.getUser() ?: error("Privy user not authenticated")
        if (user.embeddedSolanaWallets.isNotEmpty()) {
            SampleLog.d("PrivyAuth", "ensureSolanaWallet — wallet already present")
            return false
        }
        val wallet = user.createSolanaWallet(allowAdditional = false).getOrThrow()
        SampleLog.i("PrivyAuth", "created embedded Solana wallet address=${wallet.address}")
        return true
    }
}
