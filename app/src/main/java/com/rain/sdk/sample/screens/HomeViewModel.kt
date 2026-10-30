package com.rain.sdk.sample.screens

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.sample.ContactChannel
import com.rain.sdk.sample.PrivyAuthSample
import com.rain.sdk.sample.RainSampleApp
import com.rain.sdk.sample.RainSession
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.SessionHealth
import com.rain.sdk.sample.SessionStore
import com.rain.sdk.sample.TurnkeyAuthSample
import com.rain.sdk.sample.WalletChain
import com.rain.sdk.sample.WalletSessionStatus
import com.rain.sdk.wallet.RainProvider
import com.rain.sdk.wallet.RainWalletAuthState
import com.rain.sdk.wallet.RainWalletContact
import com.rain.sdk.wallet.RainWalletContactType
import com.rain.sdk.wallet.RainWalletKeyAccount
import com.rain.sdk.wallet.RainWalletLoginOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** The provider tabs, in display order. */
enum class WalletMode { RainWallet, Turnkey, Portal, Privy }

/** The values the export card can reveal, one at a time. */
enum class RainWalletExportKind(val label: String) {
    RecoveryPhrase("Recovery phrase"),
    EthereumKey("Ethereum private key"),
    SolanaKey("Solana private key"),
}

/** The passkey call whose sheet or request is running, one at a time. */
enum class RainWalletPasskeyAction { Login, SignUp, AddPasskey }

/** A revealed export value. `toString` hides the value, so state logging and diffs never print it. */
data class RevealedSecret(val kind: RainWalletExportKind, val value: String) {
    override fun toString(): String = "RevealedSecret(kind=$kind)"
}

@Suppress("LargeClass") // one sample ViewModel for four providers' flows; a split is a sample-only refactor
class HomeViewModel(
    private val app: RainSampleApp
) : ViewModel() {

    private val session: RainSession get() = app.session
    private val store: SessionStore get() = app.store

    // Fields start from the last working values; empty on first run.
    private val _state = MutableStateFlow(seededState(store.provider?.toMode() ?: WalletMode.RainWallet))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private fun seededState(mode: WalletMode) = HomeUiState(
        mode = mode,
        sessionToken = store.portalSessionToken,
        rainApiKey = store.rainApiKey,
        userId = store.rainUserId,
        rainWalletChannel = ContactChannel.fromRecordOrEmail(store.rainWalletChannel),
        rainWalletEmail = store.rainWalletEmail,
        rainWalletPhone = store.rainWalletPhone,
        rainWalletPasskeySession = store.rainWalletPasskeyOwner,
        turnkeyOrgId = store.turnkeyOrgId,
        turnkeyAuthProxyConfigId = store.turnkeyAuthProxyConfigId,
        turnkeyChannel = ContactChannel.fromRecordOrEmail(store.turnkeyChannel),
        turnkeyEmail = store.turnkeyEmail,
        turnkeyPhone = store.turnkeyPhone,
        privyAppId = store.privyAppId,
        privyAppClientId = store.privyAppClientId,
        privyEmail = store.privyEmail,
    )

    init {
        session.configureRainApi(_state.value.rainApiKey, _state.value.userId)

        viewModelScope.launch {
            session.sessionStatus.collect { status ->
                _state.update { current ->
                    // Portal Refreshing→Active means onSessionTokenNeeded consumed the replacement.
                    val consumedReplacement =
                        current.sessionStatus?.health == SessionHealth.Transitional &&
                            status?.health == SessionHealth.Healthy &&
                            current.mode == WalletMode.Portal &&
                            current.replacementPortalToken.isNotBlank()
                    if (consumedReplacement) store.portalSessionToken = current.replacementPortalToken.trim()
                    current.copy(
                        sessionStatus = status,
                        sessionToken = if (consumedReplacement) current.replacementPortalToken.trim() else current.sessionToken,
                        replacementPortalToken = if (consumedReplacement) "" else current.replacementPortalToken,
                    )
                }
            }
        }

        if (session.isInitialized) markResumed() else resumeIfPossible()

        // Expiry hooks emit to the Application-scoped session; this ViewModel, whichever one is
        // current, reacts. Collected after the resume decision, so a death that happened while no
        // ViewModel existed lands on top of "Session resumed", not underneath it. A hook that captured
        // a ViewModel's state would update a dead one after an Activity recreation.
        viewModelScope.launch {
            session.expiredProvider.collect { expired ->
                when (expired) {
                    SessionStore.Provider.RainWallet -> onRainWalletExpired()
                    SessionStore.Provider.Turnkey -> onTurnkeyExpired()
                    else -> Unit
                }
                // Consumed: the next ViewModel must not see this death again.
                session.expiredProvider.resetReplayCache()
            }
        }
        viewModelScope.launch {
            session.rainWalletAuth.collect { auth ->
                if (auth != null) SampleLog.d("RainWallet.auth", "authState=$auth")
            }
        }
    }

    /**
     * The vendor singletons initialize at launch on an HTTP client with no timeouts; the resume
     * path waits a bounded time for them and falls back to the manual screen otherwise.
     */
    private suspend fun awaitVendorInit(): Boolean {
        val job = app.vendorInit ?: return true
        val finished = withTimeoutOrNull(VENDOR_INIT_TIMEOUT_MS) { job.join() } != null
        // The launch wraps the init in runCatching, so a failed init completes the job normally.
        return finished && app.vendorInitFailure == null
    }

    /** The SDK outlived this ViewModel (Activity recreation): reflect its state without re-initializing. */
    private fun markResumed() {
        _state.update {
            it.copy(
                isInitialized = true,
                isRecovered = true,
                rainWalletSessionActive = it.mode == WalletMode.RainWallet,
                turnkeySessionActive = it.mode == WalletMode.Turnkey,
                privySessionActive = it.mode == WalletMode.Privy,
                statusText = "Session resumed"
            )
        }
    }

    /** Replays the saved provider through the same paths the buttons use; falls back to the manual screen. */
    private fun resumeIfPossible() {
        val provider = store.provider ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, statusText = "Resuming ${provider.name} session...") }
            when (provider) {
                SessionStore.Provider.Portal ->
                    if (_state.value.sessionToken.isNotBlank()) initializeSdk() else resumeFallback("Ready")
                SessionStore.Provider.RainWallet -> resumeRainWallet()
                SessionStore.Provider.Turnkey -> {
                    val initialized = awaitVendorInit()
                    // Configured for this launch whether or not the saved session is still usable.
                    if (initialized) _state.update { it.copy(backendOwner = SessionStore.Provider.Turnkey) }
                    if (!initialized) {
                        resumeFallback("Turnkey initialization did not finish or failed — log in again")
                    } else if (!TurnkeyAuthSample.hasActiveSession()) {
                        resumeFallback("Saved Turnkey session expired — log in again")
                    } else if (!turnkeySessionBelongsToSavedContact()) {
                        resumeFallback("Saved Turnkey session belongs to another contact — log in again")
                    } else {
                        _state.update { it.copy(turnkeySessionActive = true) }
                        initializeRainWithTurnkey()
                    }
                }
                SessionStore.Provider.Privy -> {
                    if (!awaitVendorInit()) {
                        resumeFallback("Privy initialization did not finish or failed — log in again")
                    } else if (!PrivyAuthSample.hasActiveSession()) {
                        resumeFallback("Saved Privy session expired — log in again")
                    } else if (!PrivyAuthSample.activeSessionEmail().matches(_state.value.privyEmail)) {
                        resumeFallback("Saved Privy session belongs to another email — log in again")
                    } else {
                        _state.update { it.copy(privySessionActive = true) }
                        initializeRainWithPrivy()
                    }
                }
            }
        }
    }

    /**
     * The Rain wallet configures its backend when the provider is prepared and restores any
     * persisted session; nothing runs at process launch. The restore can throw — a configuration
     * conflict, or the backend failing to initialize on this device — and an uncaught exception here
     * would crash every launch, so it falls back to the manual screen instead. A live session with
     * no recorded owner (a confirm that failed after the login itself went through) is not resumed
     * either: logging in again replaces it safely. Nor is one the backend no longer honours: a
     * login on another device revokes the session without touching the stored copy, so the backend
     * is asked before the session is trusted, as on Send code.
     */
    private suspend fun resumeRainWallet() {
        try {
            val rainWallet = session.prepareRainWallet(app)
            rainWallet.awaitSessionRestore()
            _state.update { it.copy(backendOwner = SessionStore.Provider.RainWallet) }
            when {
                // A restore that merely has not settled yet is not an expired session.
                rainWallet.currentAuthState() == RainWalletAuthState.Loading ->
                    resumeFallback("Still restoring the Rain Wallet session — try again in a moment")
                !rainWallet.hasActiveSession() ->
                    resumeFallback("No active Rain Wallet session — log in again")
                recordedRainWalletOwner() == null ->
                    resumeFallback("Saved Rain Wallet session has no recorded owner — log in again")
                !restoredRainWalletSessionIsLive(rainWallet) ->
                    resumeFallback("Saved Rain Wallet session was signed out on another device — log in again")
                else -> {
                    val passkey = recordedRainWalletOwner() is RainWalletOwner.Passkey
                    _state.update { it.copy(rainWalletSessionActive = true, rainWalletPasskeySession = passkey) }
                    initializeRainWithRainWallet()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SampleLog.e("Resume", "Rain Wallet resume failed: ${e.describe()}")
            resumeFallback("Rain Wallet resume failed — log in again: ${e.message}")
        }
    }

    private fun resumeFallback(message: String) {
        SampleLog.i("Resume", message)
        _state.update { it.copy(isLoading = false, statusText = message) }
    }

    private fun String?.matches(email: String): Boolean =
        this != null && trim().equals(email.trim(), ignoreCase = true)

    fun onReplacementPortalTokenChanged(value: String) {
        _state.update { it.copy(replacementPortalToken = value) }
    }

    fun refreshSession() {
        SampleLog.i("Session", "manual refreshSession() on ${_state.value.mode}")
        _state.update { it.copy(isLoading = true, statusText = "Refreshing session...") }
        viewModelScope.launch {
            try {
                session.refreshSession()
                _state.update { it.copy(isLoading = false, statusText = "Session refreshed") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SampleLog.e("Session", "refresh failed: ${e.message}", e)
                _state.update {
                    it.copy(isLoading = false, statusText = "Session refresh failed: ${e.message}")
                }
            }
        }
    }

    /** Portal only: installs the replacement token in place — no SDK rebuild. */
    fun updatePortalSessionToken() {
        val token = _state.value.replacementPortalToken.trim()
        if (token.isBlank()) return
        SampleLog.i("Portal.session", "updateSessionToken(${SampleLog.maskToken(token)})")
        _state.update { it.copy(isLoading = true, statusText = "Installing new Portal session token...") }
        viewModelScope.launch {
            try {
                session.updatePortalSessionToken(token)
                store.portalSessionToken = token
                _state.update {
                    it.copy(
                        isLoading = false,
                        sessionToken = token,
                        replacementPortalToken = "",
                        statusText = "Portal session token updated"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SampleLog.e("Portal.session", "updateSessionToken failed: ${e.message}", e)
                _state.update {
                    it.copy(isLoading = false, statusText = "Token update failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Every tab is selectable until Rain is initialized. The Rain Wallet and Turnkey tabs share one
     * process-wide backend, configured once per launch; the tab that did not configure it shows a
     * notice ([HomeUiState.sharedBackendNotice]) and its login fails with the SDK's error until a
     * relaunch, instead of the tab being refused here.
     */
    fun onModeChanged(mode: WalletMode) {
        SampleLog.d("Home", "mode changed: $mode")
        _state.update { it.copy(mode = mode) }
    }

    fun onSessionTokenChanged(value: String) {
        _state.update { it.copy(sessionToken = value) }
    }

    fun onRainApiKeyChanged(value: String) {
        _state.update { it.copy(rainApiKey = value) }
        session.configureRainApi(value, _state.value.userId)
    }

    fun onUserIdChanged(value: String) {
        _state.update { it.copy(userId = value) }
        session.configureRainApi(_state.value.rainApiKey, value)
    }

    fun onRainWalletEmailChanged(value: String) {
        _state.update { it.copy(rainWalletEmail = value) }
    }

    fun onRainWalletPhoneChanged(value: String) {
        _state.update { it.copy(rainWalletPhone = value) }
    }

    /** Ignored once a code is out: the contact it went to stays frozen until the flow completes or restarts. */
    fun onRainWalletChannelChanged(channel: ContactChannel) {
        _state.update { if (it.rainWalletOtpSent) it else it.copy(rainWalletChannel = channel) }
    }

    fun onRainWalletOtpCodeChanged(value: String) {
        _state.update { it.copy(rainWalletOtpCode = value) }
    }

    fun onRainWalletSecondCodeChanged(value: String) {
        _state.update { it.copy(rainWalletSecondCode = value) }
    }

    fun onTurnkeyOrgIdChanged(value: String) {
        _state.update { it.copy(turnkeyOrgId = value) }
    }

    fun onTurnkeyAuthProxyConfigIdChanged(value: String) {
        _state.update { it.copy(turnkeyAuthProxyConfigId = value) }
    }

    fun onTurnkeyEmailChanged(value: String) {
        _state.update { it.copy(turnkeyEmail = value) }
    }

    fun onTurnkeyPhoneChanged(value: String) {
        _state.update { it.copy(turnkeyPhone = value) }
    }

    /** Ignored once a code is out: the contact it went to stays frozen until the flow completes or restarts. */
    fun onTurnkeyChannelChanged(channel: ContactChannel) {
        _state.update { if (it.turnkeyOtpSent) it else it.copy(turnkeyChannel = channel) }
    }

    fun onTurnkeyOtpCodeChanged(value: String) {
        _state.update { it.copy(turnkeyOtpCode = value) }
    }

    fun onPrivyAppIdChanged(value: String) {
        _state.update { it.copy(privyAppId = value) }
    }

    fun onPrivyAppClientIdChanged(value: String) {
        _state.update { it.copy(privyAppClientId = value) }
    }

    fun onPrivyEmailChanged(value: String) {
        _state.update { it.copy(privyEmail = value) }
    }

    fun onPrivyOtpCodeChanged(value: String) {
        _state.update { it.copy(privyOtpCode = value) }
    }

    fun initializeSdk() {
        if (_state.value.sessionToken.isBlank()) return

        val tokenMask = SampleLog.maskToken(_state.value.sessionToken)
        SampleLog.i(
            "Portal.init",
            "calling initializePortal sessionToken=$tokenMask chainId=${RainChain.AVALANCHE_TESTNET}"
        )
        _state.update { it.copy(isLoading = true, statusText = "Initializing Portal...") }

        // Hooks outlive this call: capture the state holder, not the ViewModel.
        val uiState = _state
        viewModelScope.launch {
            try {
                // Initialize with every EVM chain's RPC, the collateral-only chains included, so
                // the chain dropdown and the Rain collateral screens both work; the screens pick
                // the active chain via `selectedChain`.
                val rpcConfig = WalletChain.evmRpcEndpoints

                session.initializePortal(
                    sessionToken = _state.value.sessionToken,
                    rpcEndpoints = rpcConfig,
                    chainId = RainChain.AVALANCHE_TESTNET,
                    // A host mints a new token for the SAME Portal client here; this sample has no
                    // backend, so it returns the typed replacement or null (declines).
                    onSessionTokenNeeded = {
                        val replacement = uiState.value.replacementPortalToken.trim()
                        if (replacement.isBlank()) {
                            SampleLog.w("Portal.session", "onSessionTokenNeeded: no replacement token, declining")
                            null
                        } else {
                            SampleLog.i("Portal.session", "onSessionTokenNeeded: supplying replacement token")
                            replacement
                        }
                    },
                    // Recovery is "Update token" or Clear Session; the feature grid hides meanwhile.
                    onSessionExpired = {
                        SampleLog.w("Portal.session", "Portal session token rejected, new token required")
                        uiState.update {
                            it.copy(statusText = "Portal session expired — enter a replacement token")
                        }
                    }
                )

                // A freshly-created Portal client has no wallet; generate one before any screen
                // asks for an address. MPC keygen takes a few seconds on first run.
                _state.update { it.copy(statusText = "Setting up wallet…") }
                val createdWallet = session.ensurePortalWallet()

                SampleLog.i(
                    "Portal.init",
                    "success — isInitialized=${session.isInitialized} createdWallet=$createdWallet"
                )
                // Recovery (Portal backup share) is no longer available via the Rain API, so a
                // successful init goes straight to the feature grid instead of gating on recovery.
                persistRainCredentials(SessionStore.Provider.Portal)
                store.portalSessionToken = _state.value.sessionToken.trim()
                _state.update {
                    it.copy(
                        isLoading = false,
                        isInitialized = session.isInitialized,
                        statusText = "SDK initialized",
                        isRecovered = true
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SampleLog.e("Portal.init", "failed: ${e.message}", e)
                // Nothing usable survives a failed init: tear the half-built SDK down so its
                // session watcher stops reporting, and drop the stale status with it.
                session.reset()
                _state.update {
                    it.copy(
                        isLoading = false,
                        statusText = "Error: ${e.message}",
                        isInitialized = false,
                        sessionStatus = null
                    )
                }
            }
        }
    }

    /** The Rain wallet's session died and could not be refreshed: back to the code step. A deliberate logout never lands here. */
    private fun onRainWalletExpired() {
        SampleLog.w("RainWallet.session", "Rain Wallet session expired, re-auth required")
        _state.update {
            it.withoutRainWalletCodes().copy(
                rainWalletSessionActive = false,
                rainWalletPasskeySession = false,
                rainWalletRevealedSecret = null,
                rainWalletAttachCodeSent = false,
                rainWalletAttachCode = "",
                statusText = "Rain Wallet session expired — log in again"
            )
        }
    }

    fun sendRainWalletOtp(app: Application) {
        val s = _state.value
        val channel = s.rainWalletChannel
        if (s.rainWalletContact.isBlank()) {
            _state.update { it.copy(statusText = "${channel.fieldLabel} is required") }
            return
        }
        val resend = s.rainWalletOtpSent

        SampleLog.i(
            "RainWallet.otpInit",
            (if (resend) "requesting a new login code" else "starting login-code flow") + " channel=${channel.name}"
        )
        // The provider is recorded before it is prepared so a relaunch resumes it. The contact is
        // saved only after a successful confirm.
        store.provider = SessionStore.Provider.RainWallet
        _state.update {
            it.copy(
                isLoading = true,
                statusText = if (resend) "Requesting a new login code..." else "Initializing wallet backend..."
            )
        }
        viewModelScope.launch {
            try {
                // Resolved inside the try so a telephony or parsing failure is reported like every
                // other failure of this flow instead of escaping the click handler.
                val contact = resolveContact(app, channel, s.rainWalletEmail, s.rainWalletPhone)
                SampleLog.i("RainWallet.otpInit", "contact=${channel.mask(contact)}")
                // A resend reuses the prepared provider; the SDK replaces the pending challenge with
                // the new one.
                val provider = session.rainWalletProvider?.takeIf { resend }
                    ?: session.prepareRainWallet(app)
                provider.awaitSessionRestore()
                _state.update { it.copy(backendOwner = SessionStore.Provider.RainWallet) }

                // The SDK restores a valid session from secure storage. Reuse it only when it
                // belongs to the contact being logged in: the last successful login on this device
                // recorded its channel and contact, and logout clears them, so that is the
                // session's owner. Any other contact, the same person on the other channel
                // included, runs the full code flow, which logs in under a fresh session and
                // leaves the current one untouched until the switch succeeds.
                // restoredRainWalletSessionIsLive also asks the backend whether it still honours
                // the restored session.
                if (provider.hasActiveSession() &&
                    isRecordedRainWalletOwner(channel, contact) &&
                    restoredRainWalletSessionIsLive(provider)
                ) {
                    SampleLog.i("RainWallet.otpInit", "existing session restored for this contact — skipping the code")
                    _state.update {
                        it.copy(
                            isLoading = false,
                            rainWalletSessionActive = true,
                            statusText = "Existing session restored — initialize Rain to continue"
                        )
                    }
                    return@launch
                }

                _state.update { it.copy(statusText = "Sending login code to ${channel.mask(contact)}...") }
                provider.sendLoginCode(channel.toRainWalletContact(contact))
                SampleLog.i("RainWallet.otpInit", if (resend) "new login code sent" else "login code sent")
                _state.update {
                    // The code went to this contact on this channel: pin both (the field stays
                    // editable while the send is in flight; a converted phone number shows its
                    // E.164 form) and stop treating any live session as this contact's — the
                    // confirm decides whose session it is.
                    it.withRainWalletContact(channel, contact).withoutRainWalletCodes().copy(
                        isLoading = false,
                        rainWalletSessionActive = false,
                        rainWalletOtpSent = true,
                        statusText = (if (resend) "New login code sent — " else "Login code sent — ") + channel.inboxHint
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SampleLog.e("RainWallet.otpInit", "failed: ${e.describe()}")
                _state.update {
                    it.copy(
                        isLoading = false,
                        statusText = rainWalletAuthFailureText("Rain Wallet login failed", e)
                    )
                }
            }
        }
    }

    /**
     * The status line for a failed Rain wallet authentication call: the three failures the demo
     * can explain get a hint a tester can act on, every other failure shows the SDK's message. A
     * mint the demo could not make offline arrives as the same `RAIN_301` as Rain being unreachable.
     */
    private fun rainWalletAuthFailureText(label: String, e: Exception): String = when {
        e is RainError.Unauthorized -> "$label: Rain refused the wallet auth token or this tenant (${e.code}); check the Api-Key's scope"
        e is RainError.NetworkError -> "$label: Rain or the demo's token mint could not be reached (${e.code}); check the connection and try again"
        e is RainError.InvalidConfig && e.message.orEmpty().contains("authTokenProvider") ->
            "$label: the demo could not mint a wallet auth token (${e.code}); enter the sandbox Api-Key and user ID on Home"
        else -> "$label: ${e.message}"
    }

    fun verifyRainWalletOtp() {
        val s = _state.value
        val provider = session.rainWalletProvider
        if (!s.rainWalletOtpSent || provider == null) {
            _state.update { it.copy(statusText = "Send a login code first") }
            return
        }
        if (s.rainWalletOtpCode.isBlank()) {
            _state.update { it.copy(statusText = "Login code required") }
            return
        }

        SampleLog.i("RainWallet.otpVerify", "confirming login code")
        _state.update { it.copy(isLoading = true, statusText = "Confirming login code...") }
        viewModelScope.launch {
            val channel = s.rainWalletChannel
            val contact = s.rainWalletContact.trim()
            val previousOwner = snapshotRainWalletOwner()
            val hadSession = provider.hasActiveSession()
            try {
                // A confirm may switch the device's session to this contact and then fail before it
                // returns, so the owner recorded for the previous session is forgotten first and
                // the new one is written only on success: the restored-session checks trust it.
                forgetRainWalletOwner()
                // Sign-up or login, plus EVM + Solana account provisioning — all inside the SDK.
                when (val outcome = provider.confirmLoginCode(s.rainWalletOtpCode.trim())) {
                    is RainWalletLoginOutcome.SecondCodeRequired -> {
                        // Nothing is logged in yet and the live session is untouched, so the
                        // previous owner still stands.
                        restoreRainWalletOwner(previousOwner)
                        startRainWalletSecondCode(provider, outcome)
                    }
                    RainWalletLoginOutcome.SignedIn -> onRainWalletCodeLoginSucceeded(channel, contact)
                }
            } catch (e: RainError.InvalidLoginCode) {
                SampleLog.w("RainWallet.otpVerify", "login code rejected", e)
                // The SDK guarantees a rejected code leaves the live session untouched, so the
                // previous owner is still the owner.
                restoreRainWalletOwner(previousOwner)
                _state.update {
                    it.copy(
                        isLoading = false,
                        rainWalletOtpCode = "",
                        statusText = "That code was not accepted — check it and try again"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onRainWalletConfirmFailed(provider, channel, contact, hadSession, e)
            }
        }
    }

    /** A code login completed: the owner is recorded and Rain initializes right away, as for a resumed session. */
    private fun onRainWalletCodeLoginSucceeded(channel: ContactChannel, contact: String) {
        recordRainWalletOwner(channel, contact)
        SampleLog.i("RainWallet.otpVerify", "session active")
        _state.update {
            it.copy(
                isLoading = false,
                rainWalletSessionActive = true,
                rainWalletPasskeySession = false,
                rainWalletSecondCodeTo = null,
                rainWalletSecondCodeVia = null,
                rainWalletSecondCodeSent = false,
                rainWalletSecondCode = "",
                statusText = "Session active — initializing Rain..."
            )
        }
        // No manual step. A failure there resets the flow to "Send code", as every init failure does.
        initializeRainWithRainWallet()
    }

    /**
     * The first code passed and Rain wants the code sent to the account's other contact too. The
     * second code is sent right away; "Resend second code" sends another. A send that fails before
     * Rain answered (`RAIN_301`, `RAIN_502`) leaves the step for another try; any other failure
     * means the SDK ended the attempt, so the flow restarts from "Send code".
     */
    private suspend fun startRainWalletSecondCode(provider: RainProvider, outcome: RainWalletLoginOutcome.SecondCodeRequired) {
        SampleLog.i("RainWallet.otpVerify", "second code required, by ${outcome.contact.type.name.lowercase()}")
        _state.update {
            it.copy(
                rainWalletSecondCodeTo = outcome.contact.masked,
                rainWalletSecondCodeVia = outcome.contact.type.viaLabel,
                rainWalletSecondCodeSent = false,
                rainWalletSecondCode = "",
            )
        }
        sendRainWalletSecondCode(provider, resend = false)
    }

    private val RainWalletContactType.viaLabel: String
        get() = if (this == RainWalletContactType.EMAIL) "email" else "SMS"

    fun sendRainWalletSecondCode() {
        val provider = session.rainWalletProvider
        if (_state.value.rainWalletSecondCodeTo == null || provider == null) {
            _state.update { it.copy(statusText = "Confirm the first code first") }
            return
        }
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch { sendRainWalletSecondCode(provider, resend = true) }
    }

    private suspend fun sendRainWalletSecondCode(provider: RainProvider, resend: Boolean) {
        val to = _state.value.rainWalletSecondCodeTo ?: return
        _state.update { it.copy(isLoading = true, statusText = "Sending second code to $to...") }
        try {
            provider.sendSecondLoginCode()
            SampleLog.i("RainWallet.otpInit", if (resend) "new second code sent" else "second code sent")
            _state.update {
                it.copy(
                    isLoading = false,
                    rainWalletSecondCodeSent = true,
                    rainWalletSecondCode = "",
                    statusText = (if (resend) "New second code sent to " else "First code accepted — second code sent to ") + to
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RainError.NetworkError) {
            onRainWalletSecondCodeSendFailed(e, attemptKept = true)
        } catch (e: RainError.InternalError) {
            onRainWalletSecondCodeSendFailed(e, attemptKept = true)
        } catch (e: Exception) {
            onRainWalletSecondCodeSendFailed(e, attemptKept = false)
        }
    }

    /** The SDK keeps a two-code attempt only when Rain never answered the send, so only then does the step stay. */
    private fun onRainWalletSecondCodeSendFailed(e: Exception, attemptKept: Boolean) {
        SampleLog.e("RainWallet.otpInit", "second code failed: ${e.describe()}")
        _state.update {
            val base = if (attemptKept) it else it.withoutRainWalletCodes()
            val label = if (attemptKept) "Second code failed" else "Second code failed, request a new code"
            base.copy(isLoading = false, statusText = rainWalletAuthFailureText(label, e))
        }
    }

    fun verifyRainWalletSecondCode() {
        val s = _state.value
        val provider = session.rainWalletProvider
        if (s.rainWalletSecondCodeTo == null || !s.rainWalletSecondCodeSent || provider == null) {
            _state.update { it.copy(statusText = "Send the second code first") }
            return
        }
        if (s.rainWalletSecondCode.isBlank()) {
            _state.update { it.copy(statusText = "Second code required") }
            return
        }

        SampleLog.i("RainWallet.otpVerify", "confirming second code")
        _state.update { it.copy(isLoading = true, statusText = "Confirming second code...") }
        viewModelScope.launch {
            val channel = s.rainWalletChannel
            val contact = s.rainWalletContact.trim()
            val previousOwner = snapshotRainWalletOwner()
            val hadSession = provider.hasActiveSession()
            try {
                forgetRainWalletOwner()
                provider.confirmSecondLoginCode(s.rainWalletSecondCode.trim())
                onRainWalletCodeLoginSucceeded(channel, contact)
            } catch (e: RainError.InvalidLoginCode) {
                SampleLog.w("RainWallet.otpVerify", "second code rejected", e)
                restoreRainWalletOwner(previousOwner)
                _state.update {
                    it.copy(isLoading = false, rainWalletSecondCode = "", statusText = "That code was not accepted — check it and try again")
                }
            } catch (e: RainError.NetworkError) {
                SampleLog.w("RainWallet.otpVerify", "second code confirm did not reach Rain", e)
                restoreRainWalletOwner(previousOwner)
                _state.update { it.copy(isLoading = false, statusText = rainWalletAuthFailureText("Second code failed", e)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onRainWalletConfirmFailed(provider, channel, contact, hadSession, e)
            }
        }
    }

    /**
     * A confirm failure other than a rejected code. When the login itself went through and a later
     * step (account provisioning) failed, the session is live and resolving Rain re-runs
     * provisioning, so the sample carries on signed in. Otherwise whose session is live is unknown
     * (a switch may or may not have happened), so the owner stays unrecorded and the flow restarts
     * from "Send code" — that path always works; a challenge the SDK kept is simply replaced.
     */
    private fun onRainWalletConfirmFailed(
        provider: RainProvider,
        channel: ContactChannel,
        contact: String,
        hadSession: Boolean,
        e: Exception,
    ) {
        SampleLog.e("RainWallet.otpVerify", "failed: ${e.message}", e)
        if (!hadSession && provider.hasActiveSession()) {
            recordRainWalletOwner(channel, contact)
            _state.update {
                it.copy(
                    isLoading = false,
                    rainWalletSessionActive = true,
                    statusText = "Signed in — wallet setup finishes when Rain initializes (${e.message})"
                )
            }
        } else {
            _state.update {
                it.withoutRainWalletCodes().copy(
                    isLoading = false,
                    statusText = "Login failed — request a new code: ${e.message}"
                )
            }
        }
    }

    /**
     * Who the device's Rain Wallet session belongs to, as the sample records it: a passkey login or
     * sign-up, or the channel of the last successful code login with the contact in that channel's
     * slot. The slots double as the prefill for the fields and hold the contacts attached to the
     * account since. An install from before the phone channel recorded only the email, so a blank
     * channel reads as an email owner.
     */
    private sealed interface RainWalletOwner {
        data class Contact(val channel: ContactChannel, val contact: String) : RainWalletOwner
        data object Passkey : RainWalletOwner
    }

    /** The recorded owner, or null when nothing is recorded. */
    private fun recordedRainWalletOwner(): RainWalletOwner? {
        if (store.rainWalletPasskeyOwner) return RainWalletOwner.Passkey
        val channel = ContactChannel.fromRecordOrEmail(store.rainWalletChannel)
        val contact = rainWalletContactSlot(channel).trim()
        return if (contact.isBlank()) null else RainWalletOwner.Contact(channel, contact)
    }

    /**
     * Whether the backend still honours the restored session. `hasActiveSession()` reads the stored
     * session, and a login on another device revokes it without touching that copy, so skipping the
     * code on it alone would hand the user a session every wallet call then rejects. A forced refresh
     * asks the backend. A refused refresh clears the stored session, fires the expiry hook and throws
     * `RAIN_201`, and the caller goes to the code step instead: Send code sends a fresh code, the
     * launch resume falls back to the manual screen. A refresh that failed for another reason
     * surfaces the same way today and is treated the same, since a fresh code works whatever state
     * the session is in.
     */
    private suspend fun restoredRainWalletSessionIsLive(provider: RainProvider): Boolean = try {
        provider.refreshSession()
        true
    } catch (e: RainError.TokenExpired) {
        SampleLog.w("RainWallet.otpInit", "restored session refused by the backend (${e.code}) — sending a code")
        false
    }

    /**
     * True when the recorded owner's account signs in with [contact] on [channel]: the contact it
     * logged in with, or one attached to it since, which is what that channel's slot holds. Email
     * compares ignoring case; a phone number compares on its `+` and digits only.
     */
    private fun isRecordedRainWalletOwner(channel: ContactChannel, contact: String): Boolean =
        recordedRainWalletOwner() != null && channel.sameContact(rainWalletContactSlot(channel), contact)

    private fun rainWalletContactSlot(channel: ContactChannel): String = when (channel) {
        ContactChannel.Email -> store.rainWalletEmail
        ContactChannel.Phone -> store.rainWalletPhone
    }

    private fun writeRainWalletContactSlot(channel: ContactChannel, contact: String) {
        when (channel) {
            ContactChannel.Email -> store.rainWalletEmail = contact
            ContactChannel.Phone -> store.rainWalletPhone = contact
        }
    }

    private fun recordRainWalletOwner(channel: ContactChannel, contact: String) {
        store.rainWalletPasskeyOwner = false
        store.rainWalletChannel = channel.name
        writeRainWalletContactSlot(channel, contact)
    }

    /** A passkey owner has no login contact; the slots fill as contacts are attached. */
    private fun recordRainWalletPasskeyOwner() {
        store.rainWalletPasskeyOwner = true
        store.rainWalletChannel = ""
    }

    /** Both slots go blank too, so a blank channel cannot read as a legacy email owner. */
    private fun forgetRainWalletOwner() {
        store.rainWalletPasskeyOwner = false
        store.rainWalletChannel = ""
        store.rainWalletEmail = ""
        store.rainWalletPhone = ""
    }

    private data class RainWalletOwnerSnapshot(
        val passkey: Boolean,
        val channel: String,
        val email: String,
        val phone: String,
    )

    private fun snapshotRainWalletOwner() = RainWalletOwnerSnapshot(
        passkey = store.rainWalletPasskeyOwner,
        channel = store.rainWalletChannel,
        email = store.rainWalletEmail,
        phone = store.rainWalletPhone,
    )

    private fun restoreRainWalletOwner(snapshot: RainWalletOwnerSnapshot) {
        store.rainWalletPasskeyOwner = snapshot.passkey
        store.rainWalletChannel = snapshot.channel
        store.rainWalletEmail = snapshot.email
        store.rainWalletPhone = snapshot.phone
    }

    /**
     * Converts a number typed without a country code to E.164 with the device's region, the way a
     * host app would before calling the SDK; a number that already starts with `+` goes through
     * untouched for the SDK's own check. When the framework cannot parse the input, the raw string
     * is sent so the SDK's message explains the problem.
     */
    private fun normalizePhoneNumber(app: Application, raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("+")) return trimmed
        val telephony = app.getSystemService(TelephonyManager::class.java)
        // The SIM's home country first: the network's country is wherever the device is roaming.
        val region = listOfNotNull(telephony?.simCountryIso, telephony?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { it.isNotBlank() }
            ?.uppercase(Locale.ROOT)
        return region?.let { PhoneNumberUtils.formatNumberToE164(trimmed, it) } ?: trimmed
    }

    /** The string [channel] sends to, trimmed; a phone number is converted to E.164 first. */
    private fun resolveContact(app: Application, channel: ContactChannel, email: String, phone: String): String =
        when (channel) {
            ContactChannel.Email -> email.trim()
            ContactChannel.Phone -> normalizePhoneNumber(app, phone)
        }

    fun initializeRainWithRainWallet() {
        if (!_state.value.rainWalletSessionActive) {
            _state.update { it.copy(statusText = "Confirm the login code first") }
            return
        }
        SampleLog.i("RainWallet.rainInit", "initializing Rain with the Rain wallet (EVM + Solana)")
        _state.update { it.copy(isLoading = true, statusText = "Initializing Rain with the Rain wallet...") }
        viewModelScope.launch {
            try {
                // Accounts were provisioned inside confirmLoginCode (and are re-checked at
                // resolution); the expiry handler was installed when the provider was prepared.
                // Every supported chain's RPC goes in so the dropdown can switch between the EVM
                // and Solana wallets without re-initializing.
                session.initializeRainWallet(rpcEndpoints = WalletChain.rpcEndpoints)
                val evmAddress = runCatching { session.client?.getWalletAddress(WalletChain.EVM.chainId) }.getOrNull()
                val solAddress = runCatching {
                    session.client?.getWalletAddress(
                        WalletChain.SOLANA.chainId
                    )
                }.getOrNull()
                SampleLog.i(
                    "RainWallet.rainInit",
                    "success — isInitialized=${session.isInitialized} evm=$evmAddress sol=$solAddress"
                )
                persistRainCredentials(SessionStore.Provider.RainWallet)
                _state.update {
                    it.copy(
                        isLoading = false,
                        isInitialized = session.isInitialized,
                        isRecovered = true,
                        statusText = "Rain initialized with the Rain wallet — wallet ready"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SampleLog.e("RainWallet.rainInit", "failed: ${e.message}", e)
                hideRainWalletSecret(clearClipboard = true)
                // reset() closes the prepared provider, so the flow restarts from "Send code".
                session.reset()
                _state.update {
                    it.withoutRainWalletCodes().copy(
                        isLoading = false,
                        isInitialized = false,
                        sessionStatus = null,
                        rainWalletSessionActive = false,
                        statusText = "Rain wallet init failed: ${e.message}"
                    )
                }
            }
        }
    }

    // ---------- Rain Wallet passkeys and contact attach ----------

    /** Whether the screen has an Activity to present the passkey sheet from; a preview has none. */
    fun onRainWalletActivityAvailable(available: Boolean) {
        if (_state.value.rainWalletActivityAvailable != available) {
            _state.update { it.copy(rainWalletActivityAvailable = available) }
        }
    }

    /**
     * Signs in with a passkey through the system sheet. The Activity is used for this one call and
     * never stored, the rule the `Application` parameters already follow.
     */
    fun loginWithRainWalletPasskey(activity: Activity) {
        runRainWalletPasskey(RainWalletPasskeyAction.Login, "RainWallet.passkeyLogin", "Passkey sign-in") {
            it.loginWithPasskey(activity)
        }
    }

    /** Creates a new account with a passkey as its only login; a returning user signs in instead. */
    fun signUpWithRainWalletPasskey(activity: Activity) {
        runRainWalletPasskey(RainWalletPasskeyAction.SignUp, "RainWallet.passkeySignup", "Passkey sign-up") {
            it.signUpWithPasskey(activity)
        }
    }

    /**
     * The shared passkey path: the provider is recorded and prepared as for a code login, the
     * previous owner is forgotten before the SDK call and the passkey owner recorded after it, then
     * Rain initializes as it does after a confirmed code. A two-code login between its codes is
     * abandoned by the SDK as the call starts, so both code steps are cleared here first. Failures
     * log and show the code and the class only, because an auth-proxy failure can echo the contact.
     */
    private fun runRainWalletPasskey(
        action: RainWalletPasskeyAction,
        area: String,
        label: String,
        call: suspend (RainProvider) -> Unit,
    ) {
        if (_state.value.rainWalletPasskeyInFlight != null || _state.value.isLoading) return
        SampleLog.i(area, "starting")
        // Recorded before the provider is prepared so a relaunch resumes it, as for a code login.
        store.provider = SessionStore.Provider.RainWallet
        _state.update {
            val base = if (it.rainWalletSecondCodeTo != null) it.withoutRainWalletCodes() else it
            base.copy(rainWalletPasskeyInFlight = action, statusText = "$label: waiting for the passkey sheet...")
        }
        viewModelScope.launch {
            var provider: RainProvider? = null
            val previousOwner = snapshotRainWalletOwner()
            var hadSession = false
            try {
                val prepared = session.rainWalletProvider ?: session.prepareRainWallet(app)
                provider = prepared
                prepared.awaitSessionRestore()
                _state.update { it.copy(backendOwner = SessionStore.Provider.RainWallet) }
                hadSession = prepared.hasActiveSession()
                // The call may switch the device's session and then fail before it returns, so the
                // owner of the previous session is forgotten first and the new one written on success.
                forgetRainWalletOwner()
                call(prepared)
                recordRainWalletPasskeyOwner()
                SampleLog.i(area, "session active")
                _state.update {
                    it.withoutRainWalletCodes().copy(
                        rainWalletSessionActive = true,
                        rainWalletPasskeySession = true,
                        statusText = "$label succeeded; initializing Rain...",
                    )
                }
                initializeRainWithRainWallet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RainError) {
                SampleLog.w(area, "failed ${e.describe()}")
                onRainWalletPasskeyFailed(provider, action, label, previousOwner, hadSession, e)
            } finally {
                _state.update { it.copy(rainWalletPasskeyInFlight = null) }
            }
        }
    }

    /**
     * A passkey flow's failure. A closed sheet or a refused request never reached a login, so the
     * previous owner still owns whatever session is live; on a sign-up the closed sheet may have
     * been the sign-in sheet that opens after Rain answered, when the account holds no key for
     * this device, which the error cannot tell from the ceremony's sheet, so the status points at
     * *Sign in with passkey*. When the login went through and a later step failed, the session is
     * live and the sample carries on signed in, as after a code. Otherwise whose session is live
     * is unknown, so the owner stays unrecorded and the user signs in again; a sign-up whose
     * answer was lost, unreadable or refused after the account was created may have left one, so
     * that status points at *Sign in with passkey* too. When Rain's answer to the sign-up was lost
     * or unreadable, the registration is kept and the next sign-up resends it instead of minting a
     * second account; a failure after Rain answered with the account leaves it settled, so a
     * second sign-up then mints another.
     */
    @Suppress("LongParameterList") // one value per thing the status depends on; the flow's kind is the sixth
    private fun onRainWalletPasskeyFailed(
        provider: RainProvider?,
        action: RainWalletPasskeyAction,
        label: String,
        previousOwner: RainWalletOwnerSnapshot,
        hadSession: Boolean,
        e: RainError,
    ) {
        val code = e.code
        val signUp = action == RainWalletPasskeyAction.SignUp
        when {
            e is RainError.UserRejected || e is RainError.InvalidConfig -> {
                restoreRainWalletOwner(previousOwner)
                val status = when {
                    e !is RainError.UserRejected -> "$label refused ($code)"
                    signUp -> "Passkey sheet closed ($code); if Rain answered the sign-up, the account exists and Sign in with passkey reaches it"
                    else -> "Passkey sheet closed ($code), no passkey used"
                }
                _state.update { it.copy(statusText = status) }
            }
            !hadSession && provider?.hasActiveSession() == true -> {
                recordRainWalletPasskeyOwner()
                _state.update {
                    it.copy(
                        rainWalletSessionActive = true,
                        rainWalletPasskeySession = true,
                        statusText = "$label signed in; wallet setup finishes when Rain initializes ($code)",
                    )
                }
            }
            // A refused token or tenant (RAIN_202) is the one failure that certainly left no account.
            signUp && e !is RainError.Unauthorized -> _state.update {
                it.copy(statusText = "$label failed ($code); the account may exist, so try Sign in with passkey before creating another")
            }
            else -> _state.update { it.copy(statusText = "$label failed ($code)") }
        }
    }

    /** The prepared provider while a Rain Wallet session is active, or null with the status set. */
    private fun signedInRainWalletProviderOrNull(): RainProvider? {
        val provider = session.rainWalletProvider?.takeIf { _state.value.rainWalletSessionActive }
        if (provider == null) _state.update { it.copy(statusText = "Sign in with the Rain wallet first") }
        return provider
    }

    /** Registers a passkey on the signed-in account, so the next sign-in can use it. */
    fun addRainWalletPasskey(activity: Activity) {
        val provider = signedInRainWalletProviderOrNull() ?: return
        if (_state.value.rainWalletPasskeyInFlight != null) return
        SampleLog.i("RainWallet.addPasskey", "starting")
        _state.update {
            it.copy(
                rainWalletPasskeyInFlight = RainWalletPasskeyAction.AddPasskey,
                statusText = "Waiting for the passkey sheet...",
            )
        }
        viewModelScope.launch {
            try {
                provider.addPasskey(activity)
                SampleLog.i("RainWallet.addPasskey", "registered")
                _state.update { it.copy(statusText = "Passkey added; the next sign-in can use it") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RainError.UserRejected) {
                SampleLog.w("RainWallet.addPasskey", "sheet closed ${e.code}")
                _state.update { it.copy(statusText = "Passkey sheet closed (${e.code}), no passkey added") }
            } catch (e: RainError) {
                SampleLog.w("RainWallet.addPasskey", "failed ${e.describe()}")
                _state.update { it.copy(statusText = "Add passkey failed (${e.code})") }
            } finally {
                _state.update { it.copy(rainWalletPasskeyInFlight = null) }
            }
        }
    }

    fun onRainWalletAttachChannelChanged(channel: ContactChannel) {
        _state.update { it.copy(rainWalletAttachChannel = channel) }
    }

    fun onRainWalletAttachEmailChanged(value: String) {
        _state.update { it.copy(rainWalletAttachEmail = value) }
    }

    fun onRainWalletAttachPhoneChanged(value: String) {
        _state.update { it.copy(rainWalletAttachPhone = value) }
    }

    fun onRainWalletAttachCodeChanged(value: String) {
        _state.update { it.copy(rainWalletAttachCode = value) }
    }

    /**
     * The provider for an attach call, or null with the status set: no live session, a blank
     * contact, or on confirm no code out or a blank code. An attach call already in flight also
     * yields null, silently.
     */
    private fun rainWalletAttachProviderOrNull(s: HomeUiState, confirming: Boolean): RainProvider? {
        val provider = signedInRainWalletProviderOrNull() ?: return null
        val refusal = when {
            confirming && !s.rainWalletAttachCodeSent -> "Send a verification code first"
            confirming && s.rainWalletAttachCode.isBlank() -> "Verification code required"
            !confirming && s.rainWalletAttachContact.isBlank() -> "${s.rainWalletAttachChannel.fieldLabel} is required"
            else -> null
        }
        if (refusal != null) _state.update { it.copy(statusText = refusal) }
        return if (refusal != null || s.rainWalletAttachInFlight) null else provider
    }

    /**
     * Sends a verification code to the contact to attach to the signed-in account; distinct from
     * the login code. A second tap requests a new code for the pinned contact.
     */
    fun sendRainWalletAttachCode(app: Application) {
        val s = _state.value
        val provider = rainWalletAttachProviderOrNull(s, confirming = false) ?: return
        val channel = s.rainWalletAttachChannel
        val resend = s.rainWalletAttachCodeSent
        SampleLog.i(
            "RainWallet.attach",
            (if (resend) "requesting a new verification code" else "starting contact attach") + " channel=${channel.name}"
        )
        _state.update { it.copy(rainWalletAttachInFlight = true, statusText = "Sending verification code...") }
        viewModelScope.launch {
            try {
                val contact = resolveContact(app, channel, s.rainWalletAttachEmail, s.rainWalletAttachPhone)
                SampleLog.i("RainWallet.attach", "contact=${channel.mask(contact)}")
                provider.sendContactVerificationCode(channel.toRainWalletContact(contact))
                SampleLog.i("RainWallet.attach", if (resend) "new verification code sent" else "verification code sent")
                _state.update {
                    // Pinned to what the code went to; a converted phone number shows its E.164 form.
                    it.withRainWalletAttachContact(channel, contact).copy(
                        rainWalletAttachCodeSent = true,
                        rainWalletAttachCode = "",
                        statusText = "Verification code sent; ${channel.inboxHint}",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RainError) {
                SampleLog.w("RainWallet.attach", "send failed ${e.describe()}")
                _state.update { it.copy(statusText = rainWalletAuthFailureText("Verification code failed", e)) }
            } finally {
                _state.update { it.copy(rainWalletAttachInFlight = false) }
            }
        }
    }

    /**
     * Confirms the verification code and attaches the contact, which can then sign in to this
     * account by code; the contact goes into its owner slot, so a code login for it reuses the live
     * session. A rejected code keeps the challenge; a refused update leaves the slot as it was; any
     * other failure restarts from "Send verification code", which replaces a challenge the SDK
     * kept. A contact another account signs in with is refused (`RAIN_202`) and the status shows
     * the reason.
     */
    fun confirmRainWalletAttach() {
        val s = _state.value
        val provider = rainWalletAttachProviderOrNull(s, confirming = true) ?: return
        SampleLog.i("RainWallet.attach", "confirming verification code")
        _state.update { it.copy(rainWalletAttachInFlight = true, statusText = "Confirming verification code...") }
        viewModelScope.launch {
            try {
                provider.confirmContactVerification(s.rainWalletAttachCode.trim())
                writeRainWalletContactSlot(s.rainWalletAttachChannel, s.rainWalletAttachContact.trim())
                SampleLog.i("RainWallet.attach", "contact attached")
                _state.update {
                    it.copy(
                        rainWalletAttachCodeSent = false,
                        rainWalletAttachCode = "",
                        statusText = "Contact attached; it can sign in to this account by code",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RainError.InvalidLoginCode) {
                SampleLog.w("RainWallet.attach", "verification code rejected (${e.code})")
                _state.update {
                    it.copy(rainWalletAttachCode = "", statusText = "That code was not accepted; check it and try again")
                }
            } catch (e: RainError.Unauthorized) {
                // Rain refused the token or the tenant, the contact signs in to another account, or
                // the backend refused the update. The code is spent either way, and the slot is untouched.
                SampleLog.w("RainWallet.attach", "attach refused ${e.describe()}")
                _state.update {
                    it.copy(
                        rainWalletAttachCodeSent = false,
                        rainWalletAttachCode = "",
                        statusText = "Contact attach refused (${e.code}): ${e.details()}",
                    )
                }
            } catch (e: RainError) {
                SampleLog.w("RainWallet.attach", "confirm failed ${e.describe()}")
                _state.update {
                    it.copy(
                        rainWalletAttachCodeSent = false,
                        rainWalletAttachCode = "",
                        statusText = "Contact attach failed (${e.code}); request a new verification code",
                    )
                }
            } finally {
                _state.update { it.copy(rainWalletAttachInFlight = false) }
            }
        }
    }

    // ---------- Turnkey, bring-your-own: the sample drives the Turnkey SDK (TurnkeyAuthSample) ----------

    /** The pending one-time code's id and encryption bundle; consumed by [verifyTurnkeyOtp]. */
    private var turnkeyOtpId: String? = null
    private var turnkeyOtpEncryptionBundle: String? = null

    fun sendTurnkeyOtp(app: Application) {
        val s = _state.value
        val channel = s.turnkeyChannel
        if (s.turnkeyOrgId.isBlank() || s.turnkeyAuthProxyConfigId.isBlank() || s.turnkeyContact.isBlank()) {
            _state.update {
                it.copy(statusText = "Organization ID, Auth Proxy Config ID, and ${channel.fieldLabel} are required")
            }
            return
        }
        SampleLog.i("Turnkey.otpInit", "starting one-time-code flow channel=${channel.name}")
        _state.update { it.copy(isLoading = true, statusText = "Initializing Turnkey...") }
        viewModelScope.launch {
            try {
                // Resolved inside the try so a telephony or parsing failure is reported like every
                // other failure of this flow instead of escaping the click handler.
                val contact = resolveContact(app, channel, s.turnkeyEmail, s.turnkeyPhone)
                TurnkeyAuthSample.init(app, s.turnkeyOrgId.trim(), s.turnkeyAuthProxyConfigId.trim())
                _state.update { it.copy(backendOwner = SessionStore.Provider.Turnkey) }
                if (reuseRestoredTurnkeySession(contact, channel)) {
                    persistTurnkeyChoice(s, contact, channel)
                    return@launch
                }
                _state.update { it.copy(statusText = "Sending code to ${channel.mask(contact)}...") }
                val otpResult = TurnkeyAuthSample.sendOtp(contact, channel)
                // Persisted only once the code went out: a failed attempt must not make the next launch
                // configure the shared backend with these ids.
                persistTurnkeyChoice(s, contact, channel)
                turnkeyOtpId = otpResult.otpId
                turnkeyOtpEncryptionBundle = otpResult.otpEncryptionTargetBundle
                SampleLog.i("Turnkey.otpInit", "code sent")
                _state.update {
                    // The code went to this contact on this channel: pin both, as the Rain Wallet card does.
                    it.withTurnkeyContact(channel, contact).copy(
                        isLoading = false,
                        turnkeyOtpSent = true,
                        turnkeyOtpCode = "",
                        statusText = "Code sent — ${channel.inboxHint}",
                    )
                }
            } catch (e: CancellationException) {
                reportUnlessCancelled(e, "Turnkey.otpInit", TURNKEY_INIT_INTERRUPTED)
            } catch (e: Exception) {
                SampleLog.e("Turnkey.otpInit", "failed: ${e.describe()}")
                _state.update { it.copy(isLoading = false, statusText = "Turnkey OTP init failed: ${e.message}") }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun persistTurnkeyChoice(s: HomeUiState, contact: String, channel: ContactChannel) {
        store.provider = SessionStore.Provider.Turnkey
        store.turnkeyOrgId = s.turnkeyOrgId.trim()
        store.turnkeyAuthProxyConfigId = s.turnkeyAuthProxyConfigId.trim()
        store.turnkeyChannel = channel.name
        when (channel) {
            ContactChannel.Email -> store.turnkeyEmail = contact
            ContactChannel.Phone -> store.turnkeyPhone = contact
        }
    }

    /**
     * Turnkey restores a valid session from secure storage during init. It is reused only when it
     * provably belongs to [contact] on [channel]; any other owner is logged out so the code flow
     * runs as [contact].
     */
    private suspend fun reuseRestoredTurnkeySession(contact: String, channel: ContactChannel): Boolean {
        if (!TurnkeyAuthSample.hasActiveSession()) return false
        val sessionContact = TurnkeyAuthSample.activeSessionContact(channel)
        val sameOwner = sessionContact != null && channel.sameContact(sessionContact, contact)
        if (sameOwner) {
            SampleLog.i("Turnkey.otpInit", "existing session restored for this contact — skipping the code")
            _state.update {
                it.copy(
                    isLoading = false,
                    turnkeySessionActive = true,
                    statusText = "Existing Turnkey session restored — initialize Rain to continue",
                )
            }
        } else {
            val owner = sessionContact?.let { channel.mask(it) } ?: "<unknown>"
            val requested = channel.mask(contact)
            SampleLog.w("Turnkey.otpInit", "restored session belongs to $owner, not $requested — logging out")
            TurnkeyAuthSample.logout()
        }
        return sameOwner
    }

    /** Whether the restored Turnkey session belongs to the contact the store recorded, on its channel. */
    private suspend fun turnkeySessionBelongsToSavedContact(): Boolean {
        val s = _state.value
        val owner = TurnkeyAuthSample.activeSessionContact(s.turnkeyChannel) ?: return false
        return s.turnkeyChannel.sameContact(owner, s.turnkeyContact)
    }

    fun verifyTurnkeyOtp() {
        val s = _state.value
        val otpId = turnkeyOtpId
        val bundle = turnkeyOtpEncryptionBundle
        if (!s.turnkeyOtpSent || otpId.isNullOrBlank() || bundle.isNullOrBlank()) {
            _state.update { it.copy(statusText = "Send OTP first") }
            return
        }
        if (s.turnkeyOtpCode.isBlank()) {
            _state.update { it.copy(statusText = "OTP code required") }
            return
        }
        SampleLog.i("Turnkey.otpVerify", "verifying OTP")
        _state.update { it.copy(isLoading = true, statusText = "Verifying OTP...") }
        viewModelScope.launch {
            try {
                TurnkeyAuthSample.verifyOtp(otpId, s.turnkeyOtpCode.trim(), bundle, s.turnkeyContact.trim(), s.turnkeyChannel)
                turnkeyOtpId = null
                turnkeyOtpEncryptionBundle = null
                SampleLog.i(
                    "Turnkey.otpVerify",
                    "session active subOrgId=${SampleLog.maskToken(TurnkeyAuthSample.subOrganizationId)}",
                )
                // The process-wide backend session is this login's now; the Rain Wallet tab's
                // owner record described the previous one and must not vouch for this one.
                forgetRainWalletOwner()
                _state.update {
                    it.copy(
                        isLoading = false,
                        turnkeySessionActive = true,
                        statusText = "Turnkey session active — initialize Rain to continue",
                    )
                }
            } catch (e: CancellationException) {
                reportUnlessCancelled(e, "Turnkey.otpVerify", TURNKEY_INIT_INTERRUPTED)
            } catch (e: Exception) {
                SampleLog.e("Turnkey.otpVerify", "failed: ${e.describe()}")
                _state.update { it.copy(isLoading = false, statusText = "OTP verification failed: ${e.message}") }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun initializeRainWithTurnkey() {
        if (!_state.value.turnkeySessionActive) {
            _state.update { it.copy(statusText = "Verify OTP first") }
            return
        }
        SampleLog.i("Turnkey.rainInit", "initializing Rain with a host-authenticated Turnkey context")
        _state.update { it.copy(isLoading = true, statusText = "Initializing Rain with Turnkey...") }
        viewModelScope.launch {
            try {
                if (TurnkeyAuthSample.ensureWallets()) {
                    _state.update { it.copy(statusText = "Provisioned the Turnkey wallet, initializing Rain...") }
                }
                // Every supported chain's RPC goes in so the dropdown can switch between the EVM
                // and Solana wallets without re-initializing.
                session.initializeTurnkey(
                    turnkey = TurnkeyAuthSample.context,
                    rpcEndpoints = WalletChain.rpcEndpoints,
                )
                SampleLog.i("Turnkey.rainInit", "success — isInitialized=${session.isInitialized}")
                persistRainCredentials(SessionStore.Provider.Turnkey)
                _state.update {
                    it.copy(
                        isLoading = false,
                        isInitialized = session.isInitialized,
                        isRecovered = true,
                        statusText = "Rain initialized with Turnkey — wallet ready",
                    )
                }
            } catch (e: CancellationException) {
                reportUnlessCancelled(e, "Turnkey.rainInit", TURNKEY_INIT_INTERRUPTED)
            } catch (e: Exception) {
                SampleLog.e("Turnkey.rainInit", "failed: ${e.describe()}")
                session.reset()
                _state.update {
                    it.copy(
                        isLoading = false,
                        isInitialized = false,
                        sessionStatus = null,
                        statusText = "Rain Turnkey init failed: ${e.message}",
                    )
                }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * The Turnkey session died: restart the code flow. A fresh login revives the provider (it
     * watches the process-wide Turnkey singleton), so Rain is not re-initialized.
     */
    private fun onTurnkeyExpired() {
        SampleLog.w("Turnkey.session", "Turnkey session expired, re-auth required")
        _state.update {
            it.copy(
                turnkeySessionActive = false,
                turnkeyOtpSent = false,
                turnkeyOtpCode = "",
                statusText = "Turnkey session expired — log in again",
            )
        }
    }

    /**
     * A `CancellationException` inside a `viewModelScope` coroutine is normally this coroutine's
     * own cancellation and must propagate. The Turnkey singleton, though, replays a cancelled first
     * initialization to every later caller, so a cancellation that arrives while this coroutine is
     * still active is a failure to report, not a signal to stop.
     */
    private suspend fun reportUnlessCancelled(e: CancellationException, area: String, status: String) {
        currentCoroutineContext().ensureActive()
        SampleLog.e(area, "$status (${e.javaClass.simpleName})")
        _state.update { it.copy(isLoading = false, statusText = status) }
    }

    /** The code and class of a failure, never its prose: an auth-proxy failure can echo the contact. */
    private fun Throwable.describe(): String =
        (this as? RainError)?.let { "${it.code} ${javaClass.simpleName}" } ?: javaClass.simpleName

    /** A RainError's own text, without the `RainSDK Error [code]: ` prefix the base class puts in front. */
    private fun RainError.details(): String = message.orEmpty().removePrefix("RainSDK Error [$code]: ")

    fun sendPrivyOtp(app: Application) {
        val s = _state.value
        if (s.privyAppId.isBlank() || s.privyAppClientId.isBlank() || s.privyEmail.isBlank()) {
            _state.update { it.copy(statusText = "App ID, App Client ID, and Email are required") }
            return
        }

        SampleLog.i("Privy.otpInit", "starting email-OTP flow email=${SampleLog.maskEmail(s.privyEmail)}")
        store.provider = SessionStore.Provider.Privy
        store.privyAppId = s.privyAppId.trim()
        store.privyAppClientId = s.privyAppClientId.trim()
        store.privyEmail = s.privyEmail.trim()
        _state.update { it.copy(isLoading = true, statusText = "Initializing Privy...") }
        viewModelScope.launch {
            try {
                PrivyAuthSample.init(app, s.privyAppId, s.privyAppClientId)

                // Privy restores a prior authenticated session during init. Only reuse it when
                // it provably belongs to the email being logged in — otherwise entering a
                // different email would silently continue as the previous user. On mismatch
                // (or when the owner can't be determined) log out and run the full OTP flow.
                if (PrivyAuthSample.hasActiveSession()) {
                    val sessionEmail = PrivyAuthSample.activeSessionEmail()
                    if (sessionEmail != null && sessionEmail.trim().equals(s.privyEmail.trim(), ignoreCase = true)) {
                        SampleLog.i("Privy.otpInit", "existing session restored for this email — skipping OTP")
                        _state.update {
                            it.copy(
                                isLoading = false,
                                privySessionActive = true,
                                statusText = "Existing Privy session restored — initialize Rain to continue"
                            )
                        }
                        return@launch
                    }
                    SampleLog.w(
                        "Privy.otpInit",
                        "restored session belongs to ${SampleLog.maskEmail(sessionEmail)}, " +
                            "not ${SampleLog.maskEmail(s.privyEmail)} — logging out"
                    )
                    PrivyAuthSample.logout()
                }

                _state.update { it.copy(statusText = "Sending OTP to ${s.privyEmail}...") }
                PrivyAuthSample.sendEmailOtp(s.privyEmail)

                SampleLog.i("Privy.otpInit", "OTP sent")
                _state.update {
                    it.copy(
                        isLoading = false,
                        privyOtpSent = true,
                        statusText = "OTP sent — check your email"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SampleLog.e("Privy.otpInit", "failed: ${e.message}", e)
                _state.update {
                    it.copy(isLoading = false, statusText = "Privy OTP init failed: ${e.message}")
                }
            }
        }
    }

    fun verifyPrivyOtp() {
        val s = _state.value
        if (!s.privyOtpSent) {
            _state.update { it.copy(statusText = "Send OTP first") }
            return
        }
        if (s.privyOtpCode.isBlank()) {
            _state.update { it.copy(statusText = "OTP code required") }
            return
        }

        SampleLog.i("Privy.otpVerify", "verifying OTP")
        _state.update { it.copy(isLoading = true, statusText = "Verifying OTP...") }
        viewModelScope.launch {
            try {
                PrivyAuthSample.verifyEmailOtp(s.privyOtpCode, s.privyEmail)
                SampleLog.i("Privy.otpVerify", "session active")
                _state.update {
                    it.copy(
                        isLoading = false,
                        privySessionActive = true,
                        statusText = "Privy session active — initialize Rain to continue"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SampleLog.e("Privy.otpVerify", "failed: ${e.message}", e)
                _state.update {
                    it.copy(isLoading = false, statusText = "OTP verification failed: ${e.message}")
                }
            }
        }
    }

    fun initializeRainWithPrivy() {
        if (!_state.value.privySessionActive) {
            _state.update { it.copy(statusText = "Verify OTP first") }
            return
        }
        SampleLog.i("Privy.rainInit", "initializing Rain w/ Privy")
        _state.update { it.copy(isLoading = true, statusText = "Initializing Rain with Privy...") }
        val uiState = _state
        viewModelScope.launch {
            try {
                val createdEvm = PrivyAuthSample.ensureEthereumWallet()
                val createdSolana = PrivyAuthSample.ensureSolanaWallet()
                if (createdEvm || createdSolana) {
                    _state.update { it.copy(statusText = "Provisioned Privy wallets, initializing Rain...") }
                }

                // Initialize with every supported chain's RPC (as on the Rain wallet) so the dropdown can
                // switch between the EVM and Solana wallets without re-initializing.
                session.initializePrivy(
                    privy = PrivyAuthSample.privy,
                    rpcEndpoints = WalletChain.rpcEndpoints,
                    walletAddress = null,
                    // Restart the OTP flow; a fresh login revives the provider (it watches the
                    // process-wide Privy singleton), so Rain is not re-initialized.
                    onSessionExpired = {
                        SampleLog.w("Privy.session", "Privy session expired, re-auth required")
                        uiState.update {
                            it.copy(
                                privySessionActive = false,
                                privyOtpSent = false,
                                privyOtpCode = "",
                                statusText = "Privy session expired — log in again"
                            )
                        }
                    }
                )
                val evmAddress = runCatching { session.client?.getWalletAddress(WalletChain.EVM.chainId) }.getOrNull()
                val solAddress = runCatching {
                    session.client?.getWalletAddress(
                        WalletChain.SOLANA.chainId
                    )
                }.getOrNull()
                SampleLog.i(
                    "Privy.rainInit",
                    "success — isInitialized=${session.isInitialized} evm=$evmAddress sol=$solAddress"
                )
                persistRainCredentials(SessionStore.Provider.Privy)
                _state.update {
                    it.copy(
                        isLoading = false,
                        isInitialized = session.isInitialized,
                        isRecovered = true,
                        statusText = "Rain initialized with Privy — wallet ready"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SampleLog.e("Privy.rainInit", "failed: ${e.message}", e)
                session.reset()
                _state.update {
                    it.copy(
                        isLoading = false,
                        isInitialized = false,
                        sessionStatus = null,
                        statusText = "Rain Privy init failed: ${e.message}"
                    )
                }
            }
        }
    }

    private fun persistRainCredentials(provider: SessionStore.Provider) {
        store.provider = provider
        store.rainApiKey = _state.value.rainApiKey.trim()
        store.rainUserId = _state.value.userId.trim()
    }

    // ---------- Rain Wallet key export ----------

    /**
     * When the sample last put an exported value on the clipboard, on the monotonic clock, or null.
     * The 60 second timer in [copySensitiveToClipboard] empties the clipboard without telling the
     * view model, so a clear here happens only inside that window; afterwards the clipboard holds
     * whatever the user copied since, which is theirs to keep.
     */
    private var clipboardLoadedAtMs: Long? = null

    fun revealRainWalletSecret(kind: RainWalletExportKind) {
        val provider = signedInRainWalletProviderOrNull() ?: return
        if (_state.value.rainWalletExportInFlight != null) return
        _state.update { it.copy(rainWalletExportInFlight = kind) }
        viewModelScope.launch {
            try {
                val value = when (kind) {
                    RainWalletExportKind.RecoveryPhrase -> provider.exportRecoveryPhrase()
                    RainWalletExportKind.EthereumKey -> provider.exportPrivateKey(RainWalletKeyAccount.ETHEREUM)
                    RainWalletExportKind.SolanaKey -> provider.exportPrivateKey(RainWalletKeyAccount.SOLANA)
                }
                // The kind only, so the value never reaches a log line.
                SampleLog.i("RainWallet.export", "revealed ${kind.name}")
                _state.update {
                    it.copy(
                        rainWalletRevealedSecret = RevealedSecret(kind, value),
                        statusText = "${kind.label} revealed",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RainError) {
                SampleLog.w("RainWallet.export", "export failed ${e.code} (${e.javaClass.simpleName})")
                _state.update { it.copy(statusText = "Export failed (${e.code})") }
            } finally {
                _state.update { it.copy(rainWalletExportInFlight = null) }
            }
        }
    }

    /**
     * Drops the revealed value. With [clearClipboard] it also empties the clipboard when the sample
     * loaded it within the last 60 seconds: the Hide button, Clear session and a failed Rain
     * initialization pass true. Leaving the screen or backgrounding the app passes false, because
     * pasting into another wallet app is what Copy is for, and the 60 second timer clears the
     * clipboard either way. The session-expiry hook cannot reach this method and relies on that
     * timer.
     */
    fun hideRainWalletSecret(clearClipboard: Boolean = false) {
        if (_state.value.rainWalletRevealedSecret != null) {
            _state.update { it.copy(rainWalletRevealedSecret = null) }
        }
        if (!clearClipboard) return
        val loadedAt = clipboardLoadedAtMs ?: return
        clipboardLoadedAtMs = null
        if (SystemClock.elapsedRealtime() - loadedAt < CLIPBOARD_CLEAR_MS) clearClipboard(app)
    }

    fun copyRainWalletSecret(context: Context) {
        val secret = _state.value.rainWalletRevealedSecret ?: return
        copySensitiveToClipboard(context.applicationContext, "Rain wallet secret", secret.value)
        clipboardLoadedAtMs = SystemClock.elapsedRealtime()
        _state.update { it.copy(statusText = "Copied. The clipboard clears itself in 60 s") }
    }

    fun clearSession() {
        SampleLog.i("Home", "clearing session (provider logout + SDK reset + UI reset)")
        hideRainWalletSecret(clearClipboard = true)
        viewModelScope.launch {
            // The program key is independent of the wallet session, so it goes first, whatever the
            // logout below decides: out of memory, out of the store, off the card.
            session.clearRainApi()
            store.rainApiKey = ""
            store.rainUserId = ""
            _state.update { it.copy(rainApiKey = "", userId = "") }
            // Managed Rain Wallet logout runs next, while the provider is still open: it clears the
            // stored session without firing the re-auth hook. A refused logout keeps the wallet
            // session's identity: it is still on this device, so the app must not forget whose it is.
            if (!session.logoutRainWallet()) {
                _state.update {
                    it.copy(statusText = "Rain Wallet logout failed — the session is still on this device; try again")
                }
                return@launch
            }
            session.reset()
            store.clear()
            // Real logout so the next run requires fresh auth (and resume detects no session).
            TurnkeyAuthSample.logout()
            PrivyAuthSample.logout()
            // The backend owner survives: the singleton stays configured until a relaunch, and so does
            // the Activity flag, which only the screen's composition writes.
            _state.update {
                seededState(it.mode).copy(
                    backendOwner = it.backendOwner,
                    rainWalletActivityAvailable = it.rainWalletActivityAvailable,
                    statusText = "Session cleared",
                )
            }
        }
    }
}

data class HomeUiState(
    val mode: WalletMode = WalletMode.RainWallet,
    val sessionToken: String = "",
    val rainApiKey: String = "",
    val userId: String = "",
    val rainWalletChannel: ContactChannel = ContactChannel.Email,
    val rainWalletEmail: String = "",
    val rainWalletPhone: String = "",
    val rainWalletOtpSent: Boolean = false,
    val rainWalletOtpCode: String = "",
    /** A two-code login between its codes: the masked contact the second code goes to, as Rain sent it; null otherwise. */
    val rainWalletSecondCodeTo: String? = null,
    /** How the second code travels, "email" or "SMS", shown beside the masked contact. */
    val rainWalletSecondCodeVia: String? = null,
    val rainWalletSecondCodeSent: Boolean = false,
    val rainWalletSecondCode: String = "",
    val rainWalletSessionActive: Boolean = false,
    /** The export value on screen, one at a time; never written to saved state. */
    val rainWalletRevealedSecret: RevealedSecret? = null,
    /** The export row whose reveal is running; the other rows disable meanwhile. */
    val rainWalletExportInFlight: RainWalletExportKind? = null,
    /** The session was established with a passkey; the header then names no contact. */
    val rainWalletPasskeySession: Boolean = false,
    /** The passkey call whose sheet or request is running; the passkey buttons disable meanwhile. */
    val rainWalletPasskeyInFlight: RainWalletPasskeyAction? = null,
    /** True while the screen has an Activity to present the passkey sheet from; false in previews. */
    val rainWalletActivityAvailable: Boolean = false,
    /** The attach step: a contact to add to the signed-in account as a login method. */
    val rainWalletAttachChannel: ContactChannel = ContactChannel.Email,
    val rainWalletAttachEmail: String = "",
    val rainWalletAttachPhone: String = "",
    val rainWalletAttachCode: String = "",
    val rainWalletAttachCodeSent: Boolean = false,
    val rainWalletAttachInFlight: Boolean = false,
    val turnkeyOrgId: String = "",
    val turnkeyAuthProxyConfigId: String = "",
    val turnkeyChannel: ContactChannel = ContactChannel.Email,
    val turnkeyEmail: String = "",
    val turnkeyPhone: String = "",
    val turnkeyOtpSent: Boolean = false,
    val turnkeyOtpCode: String = "",
    val turnkeySessionActive: Boolean = false,
    val privyAppId: String = "",
    val privyAppClientId: String = "",
    val privyEmail: String = "",
    val privyOtpSent: Boolean = false,
    val privyOtpCode: String = "",
    val privySessionActive: Boolean = false,
    /** Which tab configured the shared wallet backend this launch; null until one did. Never cleared by logout. */
    val backendOwner: SessionStore.Provider? = null,
    val isInitialized: Boolean = false,
    val isRecovered: Boolean = false,
    val isLoading: Boolean = false,
    val statusText: String = "Ready",
    val sessionStatus: WalletSessionStatus? = null,
    /** Portal only: installed by "Update token" or handed to `onSessionTokenNeeded`. */
    val replacementPortalToken: String = "",
) {
    /**
     * The provider picker is enabled until Rain is initialized or a login is in flight; "Clear
     * session" unlocks it. A tab with a live session stays switchable: each tab's login checks whose
     * session the shared backend holds before reusing it.
     */
    val providerPickerEnabled: Boolean
        get() = !isInitialized && !isLoading

    /**
     * Shown on the Rain Wallet and Turnkey cards when the other of the two configured the shared
     * wallet backend this launch: a login here fails with the SDK's error until the app relaunches.
     */
    val sharedBackendNotice: String?
        get() = when {
            mode == WalletMode.RainWallet && backendOwner == SessionStore.Provider.Turnkey ->
                "The Turnkey tab configured the shared wallet backend this launch — relaunch the app to log in here"
            mode == WalletMode.Turnkey && backendOwner == SessionStore.Provider.RainWallet ->
                "The Rain Wallet tab configured the shared wallet backend this launch — relaunch the app to log in here"
            else -> null
        }

    /**
     * Whether a code login, a passkey sign-up or a contact attach can start: the demo mints the
     * wallet auth token with the sandbox Api-Key and user ID on Home. A passkey sign-in and an added
     * passkey need no token.
     */
    val rainWalletAuthAvailable: Boolean
        get() = rainApiKey.isNotBlank() && userId.isNotBlank()

    /** Why [rainWalletAuthAvailable] is false, for the Rain Wallet card; null when it is true. */
    val rainWalletAuthNotice: String?
        get() = if (rainWalletAuthAvailable) {
            null
        } else {
            "Enter the sandbox Rain Api-Key and user ID on the Home card first: the demo mints the wallet auth token with them"
        }

    /** The contact the selected Rain Wallet channel sends to. */
    val rainWalletContact: String
        get() = when (rainWalletChannel) {
            ContactChannel.Email -> rainWalletEmail
            ContactChannel.Phone -> rainWalletPhone
        }

    /** Both code steps cleared, as when the flow restarts from "Send code". */
    fun withoutRainWalletCodes(): HomeUiState = copy(
        rainWalletOtpSent = false,
        rainWalletOtpCode = "",
        rainWalletSecondCodeTo = null,
        rainWalletSecondCodeVia = null,
        rainWalletSecondCodeSent = false,
        rainWalletSecondCode = "",
    )

    /**
     * Pins the channel and its field to what the code went to. The channel is pinned too because
     * the switch can be flipped while a send is in flight; confirm reads both from this state.
     */
    fun withRainWalletContact(channel: ContactChannel, contact: String): HomeUiState = when (channel) {
        ContactChannel.Email -> copy(rainWalletChannel = channel, rainWalletEmail = contact)
        ContactChannel.Phone -> copy(rainWalletChannel = channel, rainWalletPhone = contact)
    }

    /** The contact the attach step's selected channel sends to. */
    val rainWalletAttachContact: String
        get() = when (rainWalletAttachChannel) {
            ContactChannel.Email -> rainWalletAttachEmail
            ContactChannel.Phone -> rainWalletAttachPhone
        }

    /** Pins the attach channel and its field to what the verification code went to; see [withRainWalletContact]. */
    fun withRainWalletAttachContact(channel: ContactChannel, contact: String): HomeUiState = when (channel) {
        ContactChannel.Email -> copy(rainWalletAttachChannel = channel, rainWalletAttachEmail = contact)
        ContactChannel.Phone -> copy(rainWalletAttachChannel = channel, rainWalletAttachPhone = contact)
    }

    /** The contact the selected Turnkey channel sends to. */
    val turnkeyContact: String
        get() = when (turnkeyChannel) {
            ContactChannel.Email -> turnkeyEmail
            ContactChannel.Phone -> turnkeyPhone
        }

    /** Pins the Turnkey channel and its field to what the code went to; see [withRainWalletContact]. */
    fun withTurnkeyContact(channel: ContactChannel, contact: String): HomeUiState = when (channel) {
        ContactChannel.Email -> copy(turnkeyChannel = channel, turnkeyEmail = contact)
        ContactChannel.Phone -> copy(turnkeyChannel = channel, turnkeyPhone = contact)
    }

    /** The Rain Wallet card's contact entry, as one value for the shared fields. */
    val rainWalletContactInput: ContactInput
        get() = ContactInput(rainWalletChannel, rainWalletEmail, rainWalletPhone)

    /** The Turnkey card's contact entry, as one value for the shared fields. */
    val turnkeyContactInput: ContactInput
        get() = ContactInput(turnkeyChannel, turnkeyEmail, turnkeyPhone)

    /** The attach step's contact entry, as one value for the shared fields. */
    val rainWalletAttachInput: ContactInput
        get() = ContactInput(rainWalletAttachChannel, rainWalletAttachEmail, rainWalletAttachPhone)
}

/** One tab's contact entry: the channel switch plus the two per-channel fields. */
data class ContactInput(val channel: ContactChannel, val email: String, val phone: String) {
    /** The account for the header: an email as typed; a phone number masked, since the header shows up in screenshots. */
    fun headline(): String = when (channel) {
        ContactChannel.Email -> email
        ContactChannel.Phone -> if (phone.isBlank()) "" else SampleLog.maskPhone(phone)
    }
}

/** The typed contact for the SDK, on this channel. */
private fun ContactChannel.toRainWalletContact(contact: String): RainWalletContact = when (this) {
    ContactChannel.Email -> RainWalletContact.Email(contact)
    ContactChannel.Phone -> RainWalletContact.Phone(contact)
}

private fun SessionStore.Provider.toMode(): WalletMode = when (this) {
    SessionStore.Provider.Portal -> WalletMode.Portal
    SessionStore.Provider.RainWallet -> WalletMode.RainWallet
    SessionStore.Provider.Turnkey -> WalletMode.Turnkey
    SessionStore.Provider.Privy -> WalletMode.Privy
}

/** How long the resume path waits for a vendor singleton to finish initializing. */
private const val VENDOR_INIT_TIMEOUT_MS = 15_000L

/** Shown when the Turnkey singleton replays a cancelled first initialization; only a relaunch clears it. */
private const val TURNKEY_INIT_INTERRUPTED = "Turnkey initialization was interrupted earlier in this launch — relaunch the app"

class HomeViewModelFactory(
    private val app: RainSampleApp
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            return HomeViewModel(app) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
