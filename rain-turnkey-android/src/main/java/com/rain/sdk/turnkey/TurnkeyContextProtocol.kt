package com.rain.sdk.turnkey

import android.app.Activity
import com.turnkey.core.TurnkeyContext
import com.turnkey.core.models.AuthState
import com.turnkey.core.models.CreateSubOrgParams
import com.turnkey.core.models.CustomWallet
import com.turnkey.core.models.OtpType
import com.turnkey.core.models.Session
import com.turnkey.core.models.Wallet
import com.turnkey.core.models.errors.TurnkeyKotlinError
import com.turnkey.core.models.otpTypeToFilterTypeMap
import com.turnkey.crypto.decryptExportBundle
import com.turnkey.crypto.generateP256KeyPair
import com.turnkey.crypto.models.KeyFormat
import com.turnkey.http.TurnkeyClient
import com.turnkey.passkey.PasskeyUser
import com.turnkey.passkey.createPasskey
import com.turnkey.types.ProxyTGetAccountBody
import com.turnkey.types.TCreateAuthenticatorsBody
import com.turnkey.types.TCreateWalletAccountsBody
import com.turnkey.types.TEthSendTransactionBody
import com.turnkey.types.TExportWalletAccountBody
import com.turnkey.types.TGetActivitiesBody
import com.turnkey.types.TGetActivitiesResponse
import com.turnkey.types.TGetActivityBody
import com.turnkey.types.TGetActivityResponse
import com.turnkey.types.TGetNoncesBody
import com.turnkey.types.TGetNoncesResponse
import com.turnkey.types.TGetSendTransactionStatusBody
import com.turnkey.types.TGetSendTransactionStatusResponse
import com.turnkey.types.TGetWalletAddressBalancesBody
import com.turnkey.types.TGetWalletAddressBalancesResponse
import com.turnkey.types.TListEthTransactionHistoryBody
import com.turnkey.types.TListEthTransactionHistoryResponse
import com.turnkey.types.TListSolTransactionHistoryBody
import com.turnkey.types.TListSolTransactionHistoryResponse
import com.turnkey.types.TSolSendTransactionBody
import com.turnkey.types.TUpdateUserEmailBody
import com.turnkey.types.TUpdateUserPhoneNumberBody
import com.turnkey.types.V1Activity
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1Attestation
import com.turnkey.types.V1AuthenticatorParamsV2
import com.turnkey.types.V1Curve
import com.turnkey.types.V1HashFunction
import com.turnkey.types.V1PathFormat
import com.turnkey.types.V1PayloadEncoding
import com.turnkey.types.V1SignRawPayloadResult
import com.turnkey.types.V1WalletAccountParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID

/**
 * Narrow internal abstractions over the Turnkey Kotlin SDK so the wallet provider can be
 * unit-tested without standing up a real `TurnkeyContext` singleton. The split is
 * deliberately small: only the methods the provider actually invokes are exposed here, so
 * tests can mock them without re-stating Turnkey's full surface area. The key-export members
 * return raw material, a mnemonic or a hex key, so every check and encoding runs in the exporter,
 * where the unit tests reach.
 */
internal interface TurnkeyClientProtocol {
    suspend fun getWalletAddressBalances(
        input: TGetWalletAddressBalancesBody
    ): TGetWalletAddressBalancesResponse

    /**
     * Submits an EVM send and returns the activity Turnkey recorded for it; the caller reads the
     * status id off it. The vendor's method returns the activity once it completed with its result
     * and, since `com.turnkey:http` 2.2.0, throws `TurnkeyHttpError.ActivityNotCompleted` carrying
     * the activity otherwise (still pending after the vendor's poll, failed or rejected); the manager
     * sorts both, see `TurnkeyManager.sendOutcome`.
     */
    suspend fun ethSendTransaction(
        input: TEthSendTransactionBody
    ): V1Activity

    /** The Solana twin of [ethSendTransaction]: the activity, with the V2 result once completed. */
    suspend fun solSendTransaction(
        input: TSolSendTransactionBody
    ): V1Activity

    suspend fun getSendTransactionStatus(
        input: TGetSendTransactionStatusBody
    ): TGetSendTransactionStatusResponse

    suspend fun getActivities(
        input: TGetActivitiesBody
    ): TGetActivitiesResponse

    /** One activity by id: a send reads its activity again with it while Turnkey is still executing it. */
    suspend fun getActivity(
        input: TGetActivityBody
    ): TGetActivityResponse

    suspend fun getNonces(
        input: TGetNoncesBody
    ): TGetNoncesResponse

    /**
     * Indexed history queries. Both go through the vendor's client, which signs with the key it
     * was built from at login or refresh, so a rotated session key is never read from storage here.
     */
    suspend fun listEthTransactionHistory(
        input: TListEthTransactionHistoryBody
    ): TListEthTransactionHistoryResponse

    suspend fun listSolTransactionHistory(
        input: TListSolTransactionHistoryBody
    ): TListSolTransactionHistoryResponse
}

/**
 * The channel a one-time code travels on. Module-owned so the vendor's enum stays inside the
 * adapter; [toVendorOtpType] is the only mapping.
 */
internal enum class OtpChannel { EMAIL, SMS }

/**
 * An in-flight one-time code: the id and the TEE-signed encryption bundle from `initOtp`, plus the
 * channel the code was issued on, which the completion repeats because the vendor looks the account
 * up by it. Module-owned so test doubles never construct the vendor's result type.
 */
internal data class OtpChallenge(val otpId: String, val encryptionTargetBundle: String, val channel: OtpChannel)

/**
 * A passkey the device just created: the challenge echoed in its client data and the attestation,
 * in the shape the backend's authenticator activity takes them. Module-owned so test doubles never
 * construct the vendor's registration result.
 */
internal data class PasskeyRegistration(val challenge: String, val attestation: V1Attestation)

/** One account to create on a wallet — the module-owned shape of `V1WalletAccountParams`. */
internal data class TurnkeyAccountSpec(
    val addressFormat: V1AddressFormat,
    val curve: V1Curve,
    val path: String,
)

/** One wallet with its accounts — the module-owned shape of the vendor's wallet parameters. */
internal data class TurnkeyWalletSpec(val name: String, val accounts: List<TurnkeyAccountSpec>)

@Suppress("TooManyFunctions") // vendor seam: one member per Turnkey call the SDK makes
internal interface TurnkeyContextProtocol {
    val wallets: List<Wallet>
    val session: Session?
    val turnkeyClient: TurnkeyClientProtocol?
    val authState: StateFlow<AuthState>
    val sessionFlow: StateFlow<Session?>

    suspend fun refreshWallets()

    /** Refreshes the selected session; null [expirationSeconds] uses Turnkey's default TTL. */
    suspend fun refreshSession(expirationSeconds: String?)

    suspend fun signRawPayload(
        signWith: String,
        payload: String,
        encoding: V1PayloadEncoding,
        hashFunction: V1HashFunction
    ): V1SignRawPayloadResult

    // ---- Managed authentication (email or SMS OTP through Turnkey's auth proxy) ----

    /** Suspends until the vendor singleton has initialized and restored any persisted session. */
    suspend fun awaitReady()

    /** Starts a one-time code for [contact] on [channel]; the returned challenge completes it. */
    suspend fun sendOtp(contact: String, channel: OtpChannel): OtpChallenge

    /**
     * Completes the [challenge] from [sendOtp] with the user's code (sign-up or login, the vendor
     * decides) on the channel the challenge was issued on, and stores the new session under
     * [sessionKey]. [contact] must be the string [sendOtp] was given. On the sign-up path [signupWallet]
     * is created inside the same request as the organization, so a new account never exists
     * without its wallet; the login path ignores it. Fixed arity and a distinct name so it cannot
     * collide with the vendor's defaulted overloads.
     */
    suspend fun completeOtp(
        challenge: OtpChallenge,
        otpCode: String,
        contact: String,
        sessionKey: String,
        signupWallet: TurnkeyWalletSpec,
    )

    /** The key of the session the vendor currently treats as selected, or null when none is. */
    val selectedSessionKey: String?

    /** Makes the stored session under [sessionKey] the selected one. */
    suspend fun selectSession(sessionKey: String)

    /** Clears the selected session only — never every stored session. No-op when none is selected. */
    suspend fun clearSelectedSession()

    /** Removes the stored session under [sessionKey], selected or not. */
    suspend fun clearSession(sessionKey: String)

    /** Creates one wallet holding [accounts] on the authenticated organization. */
    suspend fun createWallet(walletName: String, accounts: List<TurnkeyAccountSpec>)

    /** Adds [accounts] to the existing wallet [walletId] — no new wallet, no new mnemonic. */
    suspend fun createWalletAccounts(walletId: String, accounts: List<TurnkeyAccountSpec>)

    // ---- Passkeys (managed mode) ----

    /**
     * Signs an existing user in with a passkey bound to the relying-party domain of the one-shot
     * configuration, through the system passkey sheet
     * presented from [activity], and stores the session under [sessionKey]. Revokes the user's
     * other sessions server-side. The vendor selects the new session itself only when none is
     * selected; over a live session the caller switches. On return, success or failure, the vendor
     * deletes every stored device key no session references. Suspends for the whole ceremony.
     */
    suspend fun completePasskeyLogin(activity: Activity, sessionKey: String)

    /**
     * Creates a new organization whose root user holds a passkey bound to the relying-party domain
     * of the one-shot configuration, named
     * [passkeyName], with [signupWallet] created inside the same request, then logs in and stores
     * the session under [sessionKey]. The vendor swaps its process-wide client for a temporary-key
     * client for the whole call and restores it only by selecting the new session, which it does
     * only when none is selected: never run this while a session is selected. Same key cleanup as
     * [completePasskeyLogin].
     */
    suspend fun completePasskeySignUp(
        activity: Activity,
        sessionKey: String,
        passkeyName: String,
        signupWallet: TurnkeyWalletSpec,
    )

    /**
     * Runs the passkey creation ceremony for [rpId] on [activity], naming the passkey [name] in the
     * credential provider. The domain is explicit here because the vendor's ceremony takes it as a
     * parameter with no configured default; callers pass the one-shot configuration's value. Touches
     * no session and no backend: the result is registered on the
     * account through [registerAuthenticator], so a retry of the registration never re-runs the sheet.
     */
    suspend fun createPasskeyCredential(activity: Activity, rpId: String, name: String): PasskeyRegistration

    /**
     * Registers [registration] as the authenticator [name] on user [userId] of [organizationId],
     * stamped by the selected session. The ids are the caller's (the session it just validated), not
     * a second read of the vendor's state.
     */
    suspend fun registerAuthenticator(organizationId: String, userId: String, name: String, registration: PasskeyRegistration)

    // ---- Contact attach (verify a code without logging in, then set the user's contact) ----

    /**
     * Verifies [otpCode] against [challenge] and returns the verification token, without logging
     * in. Never binds the token to the live session's key: the vendor deletes the bound key on any
     * failure. The throwaway key it binds instead is deleted before this returns.
     */
    suspend fun verifyOtpToken(challenge: OtpChallenge, otpCode: String): String

    /**
     * The sub-organization whose user signs in with [contact] on [channel], or null when none does:
     * the auth proxy's Get Account, `POST /v1/account`, filtered by `EMAIL` or `PHONE_NUMBER`. The
     * proxy requires the [verificationToken] from [verifyOtpToken] to look an email or phone number
     * up, and the token stays usable afterwards (`TurnkeyContext.loginOrSignUpWithOtp` in sdk-kotlin
     * 2.1.0 looks the account up with it and then logs in or signs up with the same token). No
     * session stamps the call.
     */
    suspend fun lookupContactOwner(channel: OtpChannel, contact: String, verificationToken: String): String?

    /** Sets the email of user [userId] on [organizationId]; [verificationToken] marks it verified. */
    suspend fun setUserEmail(organizationId: String, userId: String, email: String, verificationToken: String)

    /** Sets the phone number, E.164, of user [userId] on [organizationId]; [verificationToken] marks it verified. */
    suspend fun setUserPhoneNumber(organizationId: String, userId: String, phoneNumber: String, verificationToken: String)

    // ---- Key export ----

    /**
     * The wallet's mnemonic, decrypted on the device by the vendor. Distinct name so it cannot
     * collide with the vendor's `exportWallet`.
     */
    suspend fun exportWalletMnemonic(walletId: String): String

    /**
     * One account's raw 32-byte private key, decrypted on the device, as the vendor's lowercase
     * hex without `0x`. The caller checks the length and encodes.
     */
    suspend fun exportAccountPrivateKeyHex(address: String): String
}

/** The one place the module's channel meets the vendor's enum. */
internal fun OtpChannel.toVendorOtpType(): OtpType = when (this) {
    OtpChannel.EMAIL -> OtpType.OTP_TYPE_EMAIL
    OtpChannel.SMS -> OtpType.OTP_TYPE_SMS
}

/**
 * Default adapter that bridges the real Turnkey singleton to the test-only interfaces.
 * Production code holds the singleton via this wrapper so the wallet provider doesn't
 * depend on `TurnkeyContext` statics directly. The passkey members hand the caller's `Activity`
 * straight to the vendor, which needs it as the anchor for the system sheet.
 */
@Suppress("TooManyFunctions") // vendor seam: one member per Turnkey call the SDK makes
internal class TurnkeyContextAdapter(
    private val context: TurnkeyContext = TurnkeyContext,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TurnkeyContextProtocol {

    override val wallets: List<Wallet>
        get() = context.wallets.value.orEmpty()

    override val session: Session?
        get() = context.session.value

    override val turnkeyClient: TurnkeyClientProtocol?
        get() = runCatching { TurnkeyClientAdapter(context.client) }.getOrNull()

    override val authState: StateFlow<AuthState>
        get() = context.authState

    override val sessionFlow: StateFlow<Session?>
        get() = context.session

    override suspend fun refreshWallets() {
        context.refreshWallets()
    }

    override suspend fun refreshSession(expirationSeconds: String?) {
        // The vendor generates the new P-256 key pair, reads and rewrites its JWT store and builds
        // the stamper on the calling thread, and the reload below reads the store again; hosts call
        // from the main thread.
        withContext(ioDispatcher) {
            if (expirationSeconds == null) {
                context.refreshSession()
            } else {
                context.refreshSession(expirationSeconds = expirationSeconds)
            }
            // Turnkey's refreshSession (sdk-kotlin up to 2.1.0) rotates the session key pair, deletes the old one and
            // rebuilds its client, but never rewrites its public `session` flow: the flow keeps the
            // pre-refresh session, whose public key no longer has a key pair and whose expiry is the
            // old one. Re-selecting the session is the one public call that reloads the stored
            // session into the flow. It is more than a local swap: with the vendor's default
            // `autoRefreshManagedStates` it also reloads the user and the wallets over the network,
            // and it fires the host's `onSessionSelected` hook, so its failure is handled apart from
            // the refresh itself.
            reloadSelectedSession(context.selectedSessionKey.value) { context.setSelectedSession(it) }
        }
    }

    override suspend fun signRawPayload(
        signWith: String,
        payload: String,
        encoding: V1PayloadEncoding,
        hashFunction: V1HashFunction
    ): V1SignRawPayloadResult {
        return context.signRawPayload(
            signWith = signWith,
            payload = payload,
            encoding = encoding,
            hashFunction = hashFunction
        )
    }

    override suspend fun awaitReady() = context.awaitReady()

    override suspend fun sendOtp(contact: String, channel: OtpChannel): OtpChallenge {
        val result = context.initOtp(otpType = channel.toVendorOtpType(), contact = contact)
        return OtpChallenge(
            otpId = result.otpId,
            encryptionTargetBundle = result.otpEncryptionTargetBundle,
            channel = channel,
        )
    }

    override suspend fun completeOtp(
        challenge: OtpChallenge,
        otpCode: String,
        contact: String,
        sessionKey: String,
        signupWallet: TurnkeyWalletSpec,
    ) {
        context.loginOrSignUpWithOtp(
            otpId = challenge.otpId,
            otpCode = otpCode,
            otpEncryptionTargetBundle = challenge.encryptionTargetBundle,
            contact = contact,
            otpType = challenge.channel.toVendorOtpType(),
            // Revokes this user's other Turnkey sessions server-side on a successful login; a
            // rejected code never reaches this point.
            invalidateExisting = true,
            sessionKey = sessionKey,
            // Sign-up only (the vendor ignores it on login); the vendor fills in the contact and
            // the verification token.
            createSubOrgParams = signupWallet.toSignupParams(),
        )
    }

    override val selectedSessionKey: String?
        get() = context.selectedSessionKey.value

    override suspend fun selectSession(sessionKey: String) {
        context.setSelectedSession(sessionKey)
    }

    override suspend fun clearSelectedSession() {
        // clearSession(null) throws when nothing is selected; resolve the key first.
        val key = context.selectedSessionKey.value ?: return
        context.clearSession(key)
    }

    override suspend fun clearSession(sessionKey: String) {
        context.clearSession(sessionKey)
    }

    override suspend fun createWallet(walletName: String, accounts: List<TurnkeyAccountSpec>) {
        context.createWallet(
            walletName = walletName,
            accounts = accounts.toVendorParams(),
            mnemonicLength = MANAGED_WALLET_MNEMONIC_LENGTH
        )
    }

    override suspend fun createWalletAccounts(walletId: String, accounts: List<TurnkeyAccountSpec>) {
        // The high-level context has no wrapper for this activity; the typed client submits it
        // and polls it to completion like every other activity.
        val organizationId = context.session.value?.organizationId ?: throw TurnkeyKotlinError.InvalidSession()
        context.client.createWalletAccounts(
            TCreateWalletAccountsBody(
                organizationId = organizationId,
                walletId = walletId,
                accounts = accounts.toVendorParams()
            )
        )
    }

    // The vendor's passkey flows generate the session's P-256 key pair, open the Keystore master key
    // and rewrite its stores on the calling thread, as refreshSession's vendor call does, and hosts
    // call from the main thread. The credential sheet is launched through the Activity and needs no
    // main-thread caller.
    override suspend fun completePasskeyLogin(activity: Activity, sessionKey: String) {
        withContext(ioDispatcher) {
            context.loginWithPasskey(
                activity = activity,
                sessionKey = sessionKey,
                // Revokes this user's other Turnkey sessions server-side on a successful login, the
                // same rule as the code login; a refused passkey never reaches this point. The
                // relying party comes from the one-shot configuration's authConfig.
                invalidateExisting = true,
            )
        }
    }

    override suspend fun completePasskeySignUp(
        activity: Activity,
        sessionKey: String,
        passkeyName: String,
        signupWallet: TurnkeyWalletSpec,
    ) {
        withContext(ioDispatcher) {
            context.signUpWithPasskey(
                activity = activity,
                sessionKey = sessionKey,
                passkeyDisplayName = passkeyName,
                // The vendor replaces the authenticators and API keys of these params with the
                // passkey and its temporary key and keeps the wallet. The relying party comes from
                // the one-shot configuration's authConfig.
                createSubOrgParams = signupWallet.toSignupParams(),
                invalidateExisting = true,
            )
        }
    }

    override suspend fun createPasskeyCredential(activity: Activity, rpId: String, name: String): PasskeyRegistration =
        withContext(ioDispatcher) {
            val result = createPasskey(
                activity = activity,
                // A fresh user id per registration, the vendor's own sign-up shape and Turnkey's guidance;
                // the credential provider shows the name, and the vendor sends it as the display name too.
                user = PasskeyUser(id = UUID.randomUUID().toString(), name = name, displayName = name),
                rpId = rpId,
                excludeCredentials = emptyList(),
            )
            PasskeyRegistration(challenge = result.challenge, attestation = result.attestation)
        }

    override suspend fun registerAuthenticator(
        organizationId: String,
        userId: String,
        name: String,
        registration: PasskeyRegistration,
    ) {
        // The high-level context has no wrapper for this activity; the typed client submits it and
        // polls it to completion like every other activity.
        context.client.createAuthenticators(
            TCreateAuthenticatorsBody(
                organizationId = organizationId,
                authenticators = listOf(
                    V1AuthenticatorParamsV2(
                        attestation = registration.attestation,
                        authenticatorName = name,
                        challenge = registration.challenge,
                    )
                ),
                userId = userId,
            )
        )
    }

    @Suppress("TooGenericExceptionCaught") // best-effort cleanup: a key that will not delete is logged, the token is already in hand
    override suspend fun verifyOtpToken(challenge: OtpChallenge, otpCode: String): String = withContext(ioDispatcher) {
        // The throwaway key pair and the code's encryption are generated on the calling thread.
        val result = context.verifyOtp(
            otpId = challenge.otpId,
            otpCode = otpCode,
            otpEncryptionTargetBundle = challenge.encryptionTargetBundle,
            // Never the live session's key: the vendor deletes the bound key on any failure, so a
            // wrong code would destroy the session. The vendor creates a throwaway key instead.
            publicKey = null,
        )
        // Nothing else removes the throwaway key for a user who never runs a passkey ceremony.
        try {
            context.deleteKeyPair(publicKey = result.publicKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Rain SDK: could not delete the verification key pair (%s)", e.javaClass.simpleName)
        }
        result.verificationToken
    }

    override suspend fun lookupContactOwner(channel: OtpChannel, contact: String, verificationToken: String): String? {
        // The vendor's own filter-type map, the one its loginOrSignUpWithOtp hands the same lookup
        // (docs.turnkey.com/api-reference/auth-proxy/account). The proxy answers an unknown contact
        // with an empty organizationId, so that reads as "nobody".
        val response = context.client.proxyGetAccount(
            ProxyTGetAccountBody(
                filterType = otpTypeToFilterTypeMap.getValue(channel.toVendorOtpType()).name,
                filterValue = contact,
                verificationToken = verificationToken,
            )
        )
        return response.organizationId?.takeIf { it.isNotEmpty() }
    }

    override suspend fun setUserEmail(organizationId: String, userId: String, email: String, verificationToken: String) {
        // No wrapper on the high-level context; the typed client submits and polls the activity.
        context.client.updateUserEmail(
            TUpdateUserEmailBody(
                organizationId = organizationId,
                userId = userId,
                userEmail = email,
                verificationToken = verificationToken,
            )
        )
    }

    override suspend fun setUserPhoneNumber(
        organizationId: String,
        userId: String,
        phoneNumber: String,
        verificationToken: String,
    ) {
        context.client.updateUserPhoneNumber(
            TUpdateUserPhoneNumberBody(
                organizationId = organizationId,
                userId = userId,
                userPhoneNumber = phoneNumber,
                verificationToken = verificationToken,
            )
        )
    }

    override suspend fun exportWalletMnemonic(walletId: String): String = exporting {
        context.exportWallet(walletId).mnemonicPhrase
    }

    override suspend fun exportAccountPrivateKeyHex(address: String): String = exporting {
        // The high-level context exports whole wallets only, so the per-account activity is
        // composed the way createWalletAccounts reaches the typed client. Both vendor calls use
        // named arguments, because each takes several Strings and a swapped pair would compile and
        // fail only against the enclave. The ephemeral pair is destructured and never held as an
        // instance, because its toString prints the private scalar.
        val organizationId = context.session.value?.organizationId ?: throw TurnkeyKotlinError.InvalidSession()
        val (targetPublicKey, _, embeddedPrivateKey) = generateP256KeyPair()
        val response = context.client.exportWalletAccount(
            TExportWalletAccountBody(
                organizationId = organizationId,
                address = address,
                targetPublicKey = targetPublicKey
            )
        )
        // The enclave names the account the bundle is for. A bundle for another account is refused
        // before anything is decrypted, with a fixed message that names no address.
        if (!TurnkeyAccounts.sameAddress(response.result.address, address)) {
            throw TurnkeyKotlinError.FailedToExportWallet(IllegalStateException("export bundle is for another account"))
        }
        decryptExportBundle(
            exportBundle = response.result.exportBundle,
            organizationId = organizationId,
            embeddedPrivateKey = embeddedPrivateKey,
            keyFormat = KeyFormat.other,
            returnMnemonic = false
        )
    }

    /**
     * Every export failure leaves as the vendor's `FailedToExportWallet`, so both paths share one
     * type and one mapping, and a cancellation leaves bare even when the vendor wrapped it. The
     * translation lives in [TurnkeyExportFailures], where unit tests reach it.
     */
    @Suppress("TooGenericExceptionCaught") // the vendor's own export wrapper catches Throwable; this one translates
    private inline fun <T> exporting(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw TurnkeyExportFailures.rethrowable(e)
    }

    /**
     * The sign-up request's wallet, created inside the signup request itself so a new organization
     * never exists without it. `CustomWallet` carries no mnemonic length, so the seed gets the
     * vendor's default of 12 words, the length the createWallet fallback pins.
     */
    private fun TurnkeyWalletSpec.toSignupParams(): CreateSubOrgParams = CreateSubOrgParams(
        customWallet = CustomWallet(walletName = name, walletAccounts = accounts.toVendorParams()),
    )

    private fun List<TurnkeyAccountSpec>.toVendorParams(): List<V1WalletAccountParams> = map {
        V1WalletAccountParams(
            addressFormat = it.addressFormat,
            curve = it.curve,
            path = it.path,
            pathFormat = V1PathFormat.PATH_FORMAT_BIP32
        )
    }

    private companion object {
        const val MANAGED_WALLET_MNEMONIC_LENGTH = 12L
    }
}

/**
 * Reloads the stored session under [selectedKey] into the vendor's flows once a refresh has
 * succeeded. The refresh has already rotated the key, server-side and on disk, so a failure of the
 * reload (a user or wallet read that failed, a hook that threw) must not reach the session
 * coordinator, which would treat it as a session death: expiry hook, evicted caches and a
 * `TokenExpired` over a session that is valid. The failure is logged and the flows keep the
 * pre-refresh view until the next reload. A cancellation leaves as itself, bare or wrapped by the
 * vendor. No-op when nothing is selected.
 */
@Suppress("TooGenericExceptionCaught") // the vendor wraps every failure of the reload in one type
internal suspend fun reloadSelectedSession(selectedKey: String?, reload: suspend (String) -> Unit) {
    val key = selectedKey ?: return
    try {
        reload(key)
    } catch (e: Exception) {
        // A cancellation leaves as itself, bare or wrapped in the vendor's failure type.
        e.cancellationInChain()?.let { throw it }
        Timber.w(e, "Rain SDK: wallet session refreshed, but reloading the selected session failed")
    }
}

/** The vendor's typed client behind [TurnkeyClientProtocol]; every call is the vendor's own. */
internal class TurnkeyClientAdapter(
    private val client: TurnkeyClient
) : TurnkeyClientProtocol {

    override suspend fun getWalletAddressBalances(
        input: TGetWalletAddressBalancesBody
    ): TGetWalletAddressBalancesResponse = client.getWalletAddressBalances(input)

    override suspend fun ethSendTransaction(
        input: TEthSendTransactionBody
    ): V1Activity = client.ethSendTransaction(input).activity

    override suspend fun solSendTransaction(
        input: TSolSendTransactionBody
    ): V1Activity = client.solSendTransaction(input).activity

    override suspend fun getSendTransactionStatus(
        input: TGetSendTransactionStatusBody
    ): TGetSendTransactionStatusResponse = client.getSendTransactionStatus(input)

    override suspend fun getActivities(
        input: TGetActivitiesBody
    ): TGetActivitiesResponse = client.getActivities(input)

    override suspend fun getActivity(
        input: TGetActivityBody
    ): TGetActivityResponse = client.getActivity(input)

    override suspend fun getNonces(
        input: TGetNoncesBody
    ): TGetNoncesResponse = client.getNonces(input)

    override suspend fun listEthTransactionHistory(
        input: TListEthTransactionHistoryBody
    ): TListEthTransactionHistoryResponse = client.listEthTransactionHistory(input)

    override suspend fun listSolTransactionHistory(
        input: TListSolTransactionHistoryBody
    ): TListSolTransactionHistoryResponse = client.listSolTransactionHistory(input)
}
