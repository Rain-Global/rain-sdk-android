package com.rain.sdk.turnkey

import com.turnkey.core.models.AuthState
import com.turnkey.core.models.Session
import com.turnkey.core.models.Wallet
import com.turnkey.types.Externaldatav1Timestamp
import com.turnkey.types.RpcStatus
import com.turnkey.types.TEthSendTransactionBody
import com.turnkey.types.TEthSendTransactionResponse
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
import com.turnkey.types.V1Activity
import com.turnkey.types.V1ActivityStatus
import com.turnkey.types.V1ActivityType
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1AssetBalance
import com.turnkey.types.V1Curve
import com.turnkey.types.V1EthFailureDetails
import com.turnkey.types.V1EthSendTransactionIntent
import com.turnkey.types.V1EthSendTransactionResult
import com.turnkey.types.V1EthSendTransactionStatus
import com.turnkey.types.V1HashFunction
import com.turnkey.types.V1Intent
import com.turnkey.types.V1PathFormat
import com.turnkey.types.V1PayloadEncoding
import com.turnkey.types.V1Result
import com.turnkey.types.V1RevertChainEntry
import com.turnkey.types.V1SignRawPayloadResult
import com.turnkey.types.V1SolSendTransactionIntent
import com.turnkey.types.V1SolSendTransactionIntentV2
import com.turnkey.types.V1SolSendTransactionResult
import com.turnkey.types.V1SolSendTransactionResultV2
import com.turnkey.types.V1SolanaFailureDetails
import com.turnkey.types.V1SolanaSendTransactionStatus
import com.turnkey.types.V1TxError
import com.turnkey.types.V1WalletAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

internal class MockTurnkeyClient(
    var mockBalances: List<V1AssetBalance> = emptyList(),
    var mockSendTransactionStatusId: String = "send-status-id",
    var mockTransactionHash: String = "0x" + "d".repeat(64),
    var mockActivities: List<V1Activity> = emptyList(),
    var mockSolSendTransactionStatusId: String = "sol-send-status-id"
) : TurnkeyClientProtocol {

    /**
     * Status response fixture for `getSendTransactionStatus`. Use the factory methods on
     * the companion object to produce typical results (broadcasted / pending / failed / reverted).
     * [errorMessage], [revertChain], [ethRevertChain] and [solanaFailure] populate the structured
     * `error` of the response; a fixture with none of them answers `error = null`.
     */
    data class StatusFixture(
        val txHash: String? = null,
        val txStatus: String = "TX_STATUS_BROADCASTED",
        val txError: String? = null,
        val errorMessage: String? = null,
        val solanaSignature: String? = null,
        /** A decoded EVM revert chain on `error.revertChain`: a contract rejecting the transaction. */
        val revertChain: List<V1RevertChainEntry>? = null,
        /** The same chain under `error.eth.revertChain`; an empty list stands for `eth` details without a chain. */
        val ethRevertChain: List<V1RevertChainEntry>? = null,
        /** Decoded Solana failure details on `error.solana`: the program rejecting the transaction. */
        val solanaFailure: V1SolanaFailureDetails? = null
    ) {
        /** The vendor response this fixture stands for. */
        fun toResponse(): TGetSendTransactionStatusResponse {
            val carriesError = errorMessage != null || revertChain != null || ethRevertChain != null || solanaFailure != null
            return TGetSendTransactionStatusResponse(
                error = if (carriesError) {
                    V1TxError(
                        eth = ethRevertChain?.let { V1EthFailureDetails(revertChain = it) },
                        message = errorMessage,
                        revertChain = revertChain,
                        solana = solanaFailure
                    )
                } else {
                    null
                },
                eth = txHash?.let { V1EthSendTransactionStatus(txHash = it) },
                solana = solanaSignature?.let { V1SolanaSendTransactionStatus(signature = it) },
                txError = txError,
                txStatus = txStatus
            )
        }

        companion object {
            fun broadcasted(hash: String) = StatusFixture(txHash = hash, txStatus = "TX_STATUS_BROADCASTED")
            fun pending() = StatusFixture(txHash = null, txStatus = "TX_STATUS_PENDING")

            /** Failed with only the vendor's `txError` string: a broadcast or confirmation failure, nothing decoded. */
            fun failed(message: String = "broadcast failed") =
                StatusFixture(txHash = null, txStatus = "TX_STATUS_FAILED", txError = message)

            /** Failed with a decoded revert chain on `error.revertChain`: what a sponsored send reports when the contract rejects it. */
            fun revertedOnChain(message: String = "execution reverted") = StatusFixture(
                txHash = null,
                txStatus = "TX_STATUS_FAILED",
                errorMessage = message,
                revertChain = listOf(V1RevertChainEntry(displayMessage = message, errorType = "native"))
            )

            /** Failed with the revert chain under `error.eth.revertChain` instead. */
            fun ethRevertedOnChain(message: String = "execution reverted") = StatusFixture(
                txHash = null,
                txStatus = "TX_STATUS_FAILED",
                errorMessage = message,
                ethRevertChain = listOf(V1RevertChainEntry(displayMessage = message, errorType = "native"))
            )

            /** Failed in simulation with a Solana program failure: an `InstructionError` plus the RPC message. */
            fun solanaRevertedOnChain(message: String = "custom program error: 0x1") = StatusFixture(
                txHash = null,
                txStatus = "TX_STATUS_FAILED",
                errorMessage = message,
                solanaFailure = V1SolanaFailureDetails(
                    rpcMessage = message,
                    transactionErrorJson = "{\"InstructionError\":[0,{\"Custom\":1}]}"
                )
            )

            /** Included on chain and reverted there: the hash and the decoded revert chain arrive together. */
            fun includedButReverted(hash: String, message: String = "execution reverted") = StatusFixture(
                txHash = hash,
                txStatus = "TX_STATUS_INCLUDED",
                errorMessage = message,
                revertChain = listOf(V1RevertChainEntry(displayMessage = message, errorType = "native"))
            )
        }
    }

    /**
     * Optional queue of status responses returned sequentially by `getSendTransactionStatus`.
     * When non-empty, each call consumes the next entry; the final entry is reused for
     * subsequent calls. When empty (default), a single BROADCASTED status containing
     * [mockTransactionHash] is returned.
     */
    var sendTransactionStatusQueue: MutableList<StatusFixture> = mutableListOf()

    /** When set, [getWalletAddressBalances] throws this instead of producing a response. */
    var walletAddressBalancesError: Exception? = null

    /** When set, [getWalletAddressBalances] suspends on it after recording the call, so a test can cancel mid-read. */
    var walletAddressBalancesGate: CompletableDeferred<Unit>? = null

    /** When set, [ethSendTransaction] throws this instead of producing a response. */
    var ethSendTransactionError: Exception? = null

    /** When set, [solSendTransaction] throws this instead of producing a response. */
    var solSendTransactionError: Exception? = null

    /**
     * When set, [solSendTransaction] answers with this activity instead of a completed V2-shaped one
     * carrying [mockSolSendTransactionStatusId], or throws from it. The vendor's client returns the
     * activity only once it completed with its V2 result and throws otherwise, so a hook that records
     * the activity Turnkey holds in [mockActivities] and then throws models the vendor; the provider
     * test's `dropActivityAfterSubmit` does that.
     */
    var solSendActivity: ((TSolSendTransactionBody) -> V1Activity)? = null

    /** When set, [getSendTransactionStatus] throws this instead of producing a response. */
    var sendTransactionStatusError: Exception? = null

    /** When set, [getActivities] throws this instead of producing a response. */
    var getActivitiesError: Exception? = null

    /**
     * When set, [getActivities] answers with it instead of filtering [mockActivities]: a page that
     * changes between reads, for example. Checked after [getActivitiesError].
     */
    var getActivitiesAnswer: ((TGetActivitiesBody) -> List<V1Activity>)? = null

    /** When set, [getActivity] throws this instead of producing a response. */
    var getActivityError: Exception? = null

    /**
     * When set, [getActivity] answers with it instead of looking the id up in [mockActivities]: an
     * activity that settles over successive reads, for example. Checked after [getActivityError].
     */
    var getActivityAnswer: ((TGetActivityBody) -> V1Activity)? = null

    /** Gas-station nonce [getNonces] returns when the request asks for one. */
    var mockGasStationNonce: String? = "7"

    /** When set, [getNonces] throws this instead of producing a response. */
    var getNoncesError: Exception? = null

    /**
     * Indexed history fixtures. Null, the default, answers the way Turnkey does for an organization
     * without the transaction history feature: the vendor client's HTTP 403, so suites that do not
     * set a page exercise the activity-log fallback.
     */
    var mockEthHistory: TListEthTransactionHistoryResponse? = null
    var mockSolHistory: TListSolTransactionHistoryResponse? = null

    /** When set, [listEthTransactionHistory] throws this instead of producing a response. */
    var listEthHistoryError: Exception? = null

    /** When set, [listSolTransactionHistory] throws this instead of producing a response. */
    var listSolHistoryError: Exception? = null

    val walletAddressBalanceCalls = mutableListOf<TGetWalletAddressBalancesBody>()
    val ethSendTransactionCalls = mutableListOf<TEthSendTransactionBody>()
    val solSendTransactionCalls = mutableListOf<TSolSendTransactionBody>()
    val sendTransactionStatusCalls = mutableListOf<TGetSendTransactionStatusBody>()
    val getActivitiesCalls = mutableListOf<TGetActivitiesBody>()
    val getActivityCalls = mutableListOf<TGetActivityBody>()
    val getNoncesCalls = mutableListOf<TGetNoncesBody>()
    val listEthHistoryCalls = mutableListOf<TListEthTransactionHistoryBody>()
    val listSolHistoryCalls = mutableListOf<TListSolTransactionHistoryBody>()

    override suspend fun getNonces(input: TGetNoncesBody): TGetNoncesResponse {
        getNoncesCalls += input
        getNoncesError?.let { throw it }
        return TGetNoncesResponse(
            gasStationNonce = if (input.gasStationNonce == true) mockGasStationNonce else null,
            nonce = if (input.nonce == true) "0" else null
        )
    }

    override suspend fun getWalletAddressBalances(
        input: TGetWalletAddressBalancesBody
    ): TGetWalletAddressBalancesResponse {
        walletAddressBalanceCalls += input
        walletAddressBalancesGate?.await()
        walletAddressBalancesError?.let { throw it }
        return TGetWalletAddressBalancesResponse(balances = mockBalances)
    }

    override suspend fun ethSendTransaction(
        input: TEthSendTransactionBody
    ): TEthSendTransactionResponse {
        ethSendTransactionCalls += input
        ethSendTransactionError?.let { throw it }
        return TEthSendTransactionResponse(
            activity = MockTurnkey.makeActivity(
                id = UUID.randomUUID().toString(),
                from = input.from,
                to = input.to,
                caip2 = input.caip2,
                value = input.value,
                data = input.data,
                sendTransactionStatusId = mockSendTransactionStatusId
            ),
            result = V1EthSendTransactionResult(sendTransactionStatusId = mockSendTransactionStatusId)
        )
    }

    override suspend fun solSendTransaction(
        input: TSolSendTransactionBody
    ): V1Activity {
        solSendTransactionCalls += input
        solSendTransactionError?.let { throw it }
        solSendActivity?.let { return it(input) }
        // The shape Turnkey records for the request the SDK posts: the V2 intent and the V2 result.
        return MockTurnkey.makeSolanaActivity(
            id = UUID.randomUUID().toString(),
            signWith = input.signWiths.single(),
            caip2 = input.caip2,
            unsignedTransaction = input.unsignedTransaction,
            sendTransactionStatusId = mockSolSendTransactionStatusId,
            shape = MockTurnkey.SolanaSendShape.V2
        )
    }

    override suspend fun getSendTransactionStatus(
        input: TGetSendTransactionStatusBody
    ): TGetSendTransactionStatusResponse {
        sendTransactionStatusCalls += input
        sendTransactionStatusError?.let { throw it }

        val fixture: StatusFixture = when {
            sendTransactionStatusQueue.isEmpty() -> StatusFixture.broadcasted(mockTransactionHash)
            sendTransactionStatusQueue.size == 1 -> sendTransactionStatusQueue[0]
            else -> sendTransactionStatusQueue.removeAt(0)
        }
        return fixture.toResponse()
    }

    override suspend fun getActivities(
        input: TGetActivitiesBody
    ): TGetActivitiesResponse {
        getActivitiesCalls += input
        getActivitiesError?.let { throw it }
        // The type filter is honoured, as Turnkey's is; a request without one lists everything.
        getActivitiesAnswer?.let { return TGetActivitiesResponse(activities = it(input)) }
        val wanted = input.filterByType.orEmpty()
        return TGetActivitiesResponse(activities = if (wanted.isEmpty()) mockActivities else mockActivities.filter { it.type in wanted })
    }

    override suspend fun getActivity(input: TGetActivityBody): TGetActivityResponse {
        getActivityCalls += input
        getActivityError?.let { throw it }
        getActivityAnswer?.let { return TGetActivityResponse(activity = it(input)) }
        // Turnkey answers an unknown id with an HTTP error, which the vendor's client throws as a bare exception.
        val activity = mockActivities.firstOrNull { it.id == input.activityId }
            ?: throw MockTurnkey.httpError(MockTurnkey.GET_ACTIVITY_PATH, 404)
        return TGetActivityResponse(activity = activity)
    }

    override suspend fun listEthTransactionHistory(
        input: TListEthTransactionHistoryBody
    ): TListEthTransactionHistoryResponse {
        listEthHistoryCalls += input
        listEthHistoryError?.let { throw it }
        return mockEthHistory ?: throw MockTurnkey.httpError(MockTurnkey.ETH_HISTORY_PATH, 403)
    }

    override suspend fun listSolTransactionHistory(
        input: TListSolTransactionHistoryBody
    ): TListSolTransactionHistoryResponse {
        listSolHistoryCalls += input
        listSolHistoryError?.let { throw it }
        return mockSolHistory ?: throw MockTurnkey.httpError(MockTurnkey.SOL_HISTORY_PATH, 403)
    }
}

internal class MockTurnkey(
    override var wallets: List<Wallet> = listOf(defaultWallet()),
    session: Session? = defaultSession(),
    override var turnkeyClient: TurnkeyClientProtocol? = MockTurnkeyClient(),
    var mockSignature: V1SignRawPayloadResult = V1SignRawPayloadResult(
        r = "1".repeat(64),
        s = "2".repeat(64),
        v = "28"
    )
) : TurnkeyContextProtocol {

    data class SignRawPayloadCall(
        val signWith: String,
        val payload: String,
        val encoding: V1PayloadEncoding,
        val hashFunction: V1HashFunction
    )

    /**
     * The two shapes Turnkey records a Solana send under: [V1] with `signWith` and the V1 result,
     * as earlier builds posted it; [V2] with `signWiths` and the V2 result, as the SDK posts it.
     */
    enum class SolanaSendShape { V1, V2 }

    // Backed by flows so coordinator/state tests can observe assignments like production code
    // observes the Turnkey singleton.
    private val sessionStateFlow = MutableStateFlow(session)
    override val sessionFlow: StateFlow<Session?> get() = sessionStateFlow
    override var session: Session?
        get() = sessionStateFlow.value
        set(value) {
            sessionStateFlow.value = value
        }

    val authStateFlow = MutableStateFlow(
        if (session != null) AuthState.authenticated else AuthState.unauthenticated
    )
    override val authState: StateFlow<AuthState> get() = authStateFlow

    // ---- managed auth seams (recorded like the wallet seams above) ----

    data class SendOtpCall(val contact: String, val channel: OtpChannel)

    data class CompleteOtpCall(
        val otpId: String,
        val otpCode: String,
        val otpEncryptionTargetBundle: String,
        val contact: String,
        val channel: OtpChannel,
        val sessionKey: String,
        val signupWallet: TurnkeyWalletSpec
    )

    data class CreateWalletCall(val walletName: String, val accounts: List<TurnkeyAccountSpec>)

    var awaitReadyCallCount: Int = 0
    var awaitReadyError: Exception? = null

    /** When set, [awaitReady] suspends on it — a vendor that never finishes initializing. */
    var awaitReadyGate: CompletableDeferred<Unit>? = null

    val sendOtpCalls = mutableListOf<SendOtpCall>()
    var sendOtpError: Exception? = null

    /** Returned by [sendOtp] with the requested channel stamped on, like the adapter does. */
    var stubbedOtpChallenge = OtpChallenge(
        otpId = "otp-id",
        encryptionTargetBundle = "bundle",
        channel = OtpChannel.EMAIL,
    )

    val completeOtpCalls = mutableListOf<CompleteOtpCall>()
    var completeOtpError: Exception? = null

    /** Runs after a recorded [completeOtp] with the new session key — install the session here. */
    var onCompleteOtp: (suspend (sessionKey: String) -> Unit)? = null

    /** Mirrors the vendor: a live session starts out under its default key. */
    override var selectedSessionKey: String? = if (session != null) DEFAULT_SESSION_KEY else null

    val selectSessionCalls = mutableListOf<String>()
    var selectSessionError: Exception? = null

    /**
     * When true, [selectSession] records the selection and *then* throws [selectSessionError] —
     * the vendor's shape: it persists the selection before its auto-refresh can fail.
     */
    var selectSessionAppliesBeforeThrowing = false

    var clearSelectedSessionCallCount: Int = 0
    var clearSelectedSessionError: Exception? = null

    val clearSessionCalls = mutableListOf<String>()

    /** When set, [clearSession] throws this after recording the call — a stale key that will not clear. */
    var clearSessionError: Exception? = null

    // ---- passkey seams ----

    data class PasskeyLoginCall(val sessionKey: String)

    data class PasskeySignUpCall(
        val sessionKey: String,
        val passkeyName: String,
        val signupWallet: TurnkeyWalletSpec,
    )

    val passkeyLoginCalls = mutableListOf<PasskeyLoginCall>()
    var passkeyLoginError: Exception? = null

    /** Runs after a recorded [completePasskeyLogin] with the new session key — install the session here. */
    var onPasskeyLogin: (suspend (sessionKey: String) -> Unit)? = null

    val passkeySignUpCalls = mutableListOf<PasskeySignUpCall>()
    var passkeySignUpError: Exception? = null

    /** Runs after a recorded [completePasskeySignUp] with the new session key — install the session here. */
    var onPasskeySignUp: (suspend (sessionKey: String) -> Unit)? = null

    /**
     * When true, a failing passkey ceremony stores (and, with nothing selected, selects) its session
     * *before* throwing — the vendor's shape when its key cleanup fails after `createSession`, or when
     * the caller's cancellation lands after the store.
     */
    var passkeyStoresBeforeThrowing = false

    data class CreatePasskeyCall(val rpId: String, val name: String)

    data class RegisterAuthenticatorCall(
        val organizationId: String,
        val userId: String,
        val name: String,
        val registration: PasskeyRegistration,
    )

    val createPasskeyCalls = mutableListOf<CreatePasskeyCall>()
    var createPasskeyError: Exception? = null

    /** What the ceremony hands back: synthetic strings that decode to nothing. */
    var stubbedPasskeyRegistration = PasskeyRegistration(
        challenge = "stub-challenge",
        attestation = com.turnkey.types.V1Attestation(
            attestationObject = "stub-attestation",
            clientDataJson = "stub-client-data",
            credentialId = "stub-credential",
            transports = listOf(com.turnkey.types.V1AuthenticatorTransport.AUTHENTICATOR_TRANSPORT_INTERNAL),
        ),
    )

    val registerAuthenticatorCalls = mutableListOf<RegisterAuthenticatorCall>()
    var registerAuthenticatorError: Exception? = null

    // ---- contact attach seams ----

    data class VerifyOtpTokenCall(val otpId: String, val otpCode: String, val encryptionTargetBundle: String)

    data class SetContactCall(val organizationId: String, val userId: String, val contact: String, val verificationToken: String)

    val verifyOtpTokenCalls = mutableListOf<VerifyOtpTokenCall>()
    var verifyOtpTokenError: Exception? = null
    var stubbedVerificationToken = "stub-verification-token"

    val setUserEmailCalls = mutableListOf<SetContactCall>()
    val setUserPhoneNumberCalls = mutableListOf<SetContactCall>()

    /** When set, both contact setters throw this after recording the call. */
    var setUserContactError: Exception? = null

    data class AccountLookupCall(val channel: OtpChannel, val contact: String, val verificationToken: String)

    val accountLookupCalls = mutableListOf<AccountLookupCall>()

    /** The organization [lookupContactOwner] names as the contact's owner; null, the default, is nobody. */
    var accountLookupResult: String? = null

    /** When set, [lookupContactOwner] throws this after recording the call. */
    var accountLookupError: Exception? = null

    val createWalletCalls = mutableListOf<CreateWalletCall>()
    var createWalletError: Exception? = null

    /** Runs after a recorded [createWallet] — install the created accounts here. */
    var onCreateWallet: (suspend (CreateWalletCall) -> Unit)? = null

    data class CreateWalletAccountsCall(val walletId: String, val accounts: List<TurnkeyAccountSpec>)

    val createWalletAccountsCalls = mutableListOf<CreateWalletAccountsCall>()
    var createWalletAccountsError: Exception? = null

    /** Runs after a recorded [createWalletAccounts] — install the added accounts here. */
    var onCreateWalletAccounts: (suspend (CreateWalletAccountsCall) -> Unit)? = null

    override suspend fun awaitReady() {
        awaitReadyCallCount++
        awaitReadyError?.let { throw it }
        awaitReadyGate?.await()
    }

    override suspend fun sendOtp(contact: String, channel: OtpChannel): OtpChallenge {
        sendOtpCalls += SendOtpCall(contact, channel)
        sendOtpError?.let { throw it }
        return stubbedOtpChallenge.copy(channel = channel)
    }

    override suspend fun completeOtp(
        challenge: OtpChallenge,
        otpCode: String,
        contact: String,
        sessionKey: String,
        signupWallet: TurnkeyWalletSpec
    ) {
        completeOtpCalls += CompleteOtpCall(
            challenge.otpId,
            otpCode,
            challenge.encryptionTargetBundle,
            contact,
            challenge.channel,
            sessionKey,
            signupWallet
        )
        completeOtpError?.let { throw it }
        // Like the vendor's createSession: a first login auto-selects; over a live session it only stores.
        if (selectedSessionKey == null) selectedSessionKey = sessionKey
        onCompleteOtp?.invoke(sessionKey)
    }

    override suspend fun selectSession(sessionKey: String) {
        selectSessionCalls += sessionKey
        if (selectSessionAppliesBeforeThrowing) selectedSessionKey = sessionKey
        selectSessionError?.let { throw it }
        selectedSessionKey = sessionKey
    }

    override suspend fun clearSelectedSession() {
        clearSelectedSessionCallCount++
        clearSelectedSessionError?.let { throw it }
        if (selectedSessionKey == null) return
        resetToUnauthenticated()
    }

    override suspend fun clearSession(sessionKey: String) {
        clearSessionCalls += sessionKey
        clearSessionError?.let { throw it }
        if (sessionKey == selectedSessionKey) resetToUnauthenticated()
    }

    override suspend fun completePasskeyLogin(activity: android.app.Activity, sessionKey: String) {
        passkeyLoginCalls += PasskeyLoginCall(sessionKey)
        storePasskeySession(sessionKey, passkeyLoginError, onPasskeyLogin)
    }

    override suspend fun completePasskeySignUp(
        activity: android.app.Activity,
        sessionKey: String,
        passkeyName: String,
        signupWallet: TurnkeyWalletSpec,
    ) {
        passkeySignUpCalls += PasskeySignUpCall(sessionKey, passkeyName, signupWallet)
        storePasskeySession(sessionKey, passkeySignUpError, onPasskeySignUp)
    }

    /** Runs inside the add-passkey ceremony, after the call is recorded; a gate here holds the sheet open. */
    var onCreatePasskey: (suspend () -> Unit)? = null

    override suspend fun createPasskeyCredential(activity: android.app.Activity, rpId: String, name: String): PasskeyRegistration {
        createPasskeyCalls += CreatePasskeyCall(rpId, name)
        createPasskeyError?.let { throw it }
        onCreatePasskey?.invoke()
        return stubbedPasskeyRegistration
    }

    override suspend fun registerAuthenticator(
        organizationId: String,
        userId: String,
        name: String,
        registration: PasskeyRegistration,
    ) {
        registerAuthenticatorCalls += RegisterAuthenticatorCall(organizationId, userId, name, registration)
        registerAuthenticatorError?.let { throw it }
    }

    override suspend fun verifyOtpToken(challenge: OtpChallenge, otpCode: String): String {
        verifyOtpTokenCalls += VerifyOtpTokenCall(challenge.otpId, otpCode, challenge.encryptionTargetBundle)
        verifyOtpTokenError?.let { throw it }
        return stubbedVerificationToken
    }

    override suspend fun lookupContactOwner(channel: OtpChannel, contact: String, verificationToken: String): String? {
        accountLookupCalls += AccountLookupCall(channel, contact, verificationToken)
        accountLookupError?.let { throw it }
        return accountLookupResult
    }

    override suspend fun setUserEmail(organizationId: String, userId: String, email: String, verificationToken: String) {
        setUserEmailCalls += SetContactCall(organizationId, userId, email, verificationToken)
        setUserContactError?.let { throw it }
    }

    override suspend fun setUserPhoneNumber(
        organizationId: String,
        userId: String,
        phoneNumber: String,
        verificationToken: String,
    ) {
        setUserPhoneNumberCalls += SetContactCall(organizationId, userId, phoneNumber, verificationToken)
        setUserContactError?.let { throw it }
    }

    /** Like the vendor's createSession: a first login auto-selects; over a live session it only stores. */
    private suspend fun storePasskeySession(
        sessionKey: String,
        error: Exception?,
        onStored: (suspend (sessionKey: String) -> Unit)?,
    ) {
        if (error != null && !passkeyStoresBeforeThrowing) throw error
        if (selectedSessionKey == null) selectedSessionKey = sessionKey
        onStored?.invoke(sessionKey)
        error?.let { throw it }
    }

    override suspend fun createWallet(walletName: String, accounts: List<TurnkeyAccountSpec>) {
        val call = CreateWalletCall(walletName, accounts)
        createWalletCalls += call
        createWalletError?.let { throw it }
        onCreateWallet?.invoke(call)
    }

    override suspend fun createWalletAccounts(walletId: String, accounts: List<TurnkeyAccountSpec>) {
        val call = CreateWalletAccountsCall(walletId, accounts)
        createWalletAccountsCalls += call
        createWalletAccountsError?.let { throw it }
        onCreateWalletAccounts?.invoke(call)
    }

    // ---- Key export ----

    /** Twelve tokens that are not BIP-39 words, so a leaked value can never read as a real phrase. */
    var stubbedMnemonic: String = "stub1 stub2 stub3 stub4 stub5 stub6 stub7 stub8 stub9 stub10 stub11 stub12"

    /** The published `0102..1f20` seed, whose ed25519 public key is the address [VECTOR_SOLANA_ADDRESS]. */
    var stubbedKeyHex: String = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"

    val exportMnemonicCalls = mutableListOf<String>()
    val exportAccountKeyCalls = mutableListOf<String>()

    /** When set, both export members throw this after recording the call. */
    var exportError: Exception? = null

    override suspend fun exportWalletMnemonic(walletId: String): String {
        exportMnemonicCalls += walletId
        exportError?.let { throw it }
        return stubbedMnemonic
    }

    override suspend fun exportAccountPrivateKeyHex(address: String): String {
        exportAccountKeyCalls += address
        exportError?.let { throw it }
        return stubbedKeyHex
    }

    /** Installs a live default session, the way a completed login leaves the vendor. */
    fun authenticate() {
        session = defaultSession()
        authStateFlow.value = AuthState.authenticated
    }

    private fun resetToUnauthenticated() {
        selectedSessionKey = null
        session = null
        authStateFlow.value = AuthState.unauthenticated
    }

    var refreshWalletsCallCount: Int = 0
    val signRawPayloadCalls = mutableListOf<SignRawPayloadCall>()

    /** When set, [signRawPayload] throws this instead of returning [mockSignature]. */
    var signRawPayloadError: Exception? = null

    var refreshSessionCallCount: Int = 0

    /** TTLs passed to [refreshSession], null meaning "Turnkey default". */
    val refreshSessionCalls = mutableListOf<String?>()

    /** When set, [refreshSession] throws this. */
    var refreshSessionError: Exception? = null

    /**
     * When set, [refreshSession] suspends on it after recording the call and before failing or
     * completing — a refresh in flight on the network, during which another coroutine can change
     * the session.
     */
    var refreshSessionGate: CompletableDeferred<Unit>? = null

    /** Runs after a recorded [refreshSession] call: install the refreshed session here. */
    var onRefreshSession: (suspend () -> Unit)? = null

    /** Runs after a recorded [refreshWallets] call — install the fetched wallets here. */
    var onRefreshWallets: (suspend () -> Unit)? = null

    override suspend fun refreshWallets() {
        refreshWalletsCallCount++
        onRefreshWallets?.invoke()
    }

    override suspend fun refreshSession(expirationSeconds: String?) {
        refreshSessionCallCount++
        refreshSessionCalls += expirationSeconds
        refreshSessionGate?.await()
        refreshSessionError?.let { throw it }
        onRefreshSession?.invoke()
    }

    override suspend fun signRawPayload(
        signWith: String,
        payload: String,
        encoding: V1PayloadEncoding,
        hashFunction: V1HashFunction
    ): V1SignRawPayloadResult {
        signRawPayloadCalls += SignRawPayloadCall(signWith, payload, encoding, hashFunction)
        signRawPayloadError?.let { throw it }
        return mockSignature
    }

    companion object {
        const val DEFAULT_WALLET_ADDRESS = "0x1234567890123456789012345678901234567890"

        // Valid 32-byte base58 pubkeys (wrapped-SOL mint and USDC mint) reused as test addresses.
        const val DEFAULT_SOLANA_ADDRESS = "So11111111111111111111111111111111111111112"
        const val DEFAULT_SOLANA_RECIPIENT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val DEFAULT_ORG_ID = "org-id"
        const val DEFAULT_SESSION_KEY = "com.turnkey.sdk.session"
        const val ETH_HISTORY_PATH = "/public/v1/query/list_eth_transaction_history"
        const val GET_ACTIVITY_PATH = "/public/v1/query/get_activity"
        const val SOL_HISTORY_PATH = "/public/v1/query/list_sol_transaction_history"

        /**
         * The vendor client's failure for a non-2xx history response: a plain `RuntimeException`
         * whose message carries the status, the shape `TurnkeyErrorMapping.turnkeyHttpStatus` reads.
         * Any vendor query path: the indexed history and the balances query fail the same way.
         */
        fun httpError(path: String, status: Int): RuntimeException =
            RuntimeException("HTTP error from $path: $status")

        fun defaultSession(): Session = Session(
            userId = "user-id",
            organizationId = DEFAULT_ORG_ID,
            expiry = System.currentTimeMillis() / 1000.0 + 3600,
            expirationSeconds = "3600",
            publicKey = "pubkey",
            token = "jwt",
            sessionType = "read_write"
        )

        /** A session whose JWT expiry has already passed. */
        fun expiredSession(): Session =
            defaultSession().copy(expiry = System.currentTimeMillis() / 1000.0 - 60)

        /** A session inside the coordinator's refresh buffer but not yet expired. */
        fun nearExpirySession(remainingSeconds: Long = 10): Session =
            defaultSession().copy(expiry = System.currentTimeMillis() / 1000.0 + remainingSeconds)

        fun defaultWallet(): Wallet = Wallet(
            id = "wallet-id",
            name = "wallet",
            accounts = listOf(
                V1WalletAccount(
                    address = DEFAULT_WALLET_ADDRESS,
                    addressFormat = V1AddressFormat.ADDRESS_FORMAT_ETHEREUM,
                    createdAt = Externaldatav1Timestamp(nanos = "0", seconds = "0"),
                    curve = V1Curve.CURVE_SECP256K1,
                    organizationId = DEFAULT_ORG_ID,
                    path = "m/44'/60'/0'/0/0",
                    pathFormat = V1PathFormat.PATH_FORMAT_BIP32,
                    publicKey = null,
                    updatedAt = Externaldatav1Timestamp(nanos = "0", seconds = "0"),
                    walletAccountId = "wallet-account-id",
                    walletDetails = null,
                    walletId = "wallet-id"
                )
            )
        )

        /** [defaultWallet] re-addressed — stands in for a different user's Turnkey wallet. */
        fun walletWithEthereumAddress(address: String): Wallet = defaultWallet().let { wallet ->
            wallet.copy(accounts = wallet.accounts.map { it.copy(address = address) })
        }

        /** A Turnkey wallet account in Solana (ed25519) format. */
        fun solanaAccount(address: String = DEFAULT_SOLANA_ADDRESS): V1WalletAccount =
            V1WalletAccount(
                address = address,
                addressFormat = V1AddressFormat.ADDRESS_FORMAT_SOLANA,
                createdAt = Externaldatav1Timestamp(nanos = "0", seconds = "0"),
                curve = V1Curve.CURVE_ED25519,
                organizationId = DEFAULT_ORG_ID,
                path = "m/44'/501'/0'/0'",
                pathFormat = V1PathFormat.PATH_FORMAT_BIP32,
                publicKey = null,
                updatedAt = Externaldatav1Timestamp(nanos = "0", seconds = "0"),
                walletAccountId = "wallet-account-id-sol",
                walletDetails = null,
                walletId = "wallet-id"
            )

        /** A wallet holding both an Ethereum and a Solana account, like the demo provisions. */
        fun walletWithEthAndSolana(
            solanaAddress: String = DEFAULT_SOLANA_ADDRESS
        ): Wallet {
            val eth = defaultWallet().accounts
            return Wallet(
                id = "wallet-id",
                name = "wallet",
                accounts = eth + solanaAccount(solanaAddress)
            )
        }

        /** Base58 of the ed25519 public key derived from the stubbed `0102..1f20` seed. */
        const val VECTOR_SOLANA_ADDRESS = "9C6hybhQ6Aycep9jaUnP6uL9ZYvDjUp1aSkFWPUFJtpj"

        /** What `exportPrivateKey(SOLANA)` returns for the stubbed seed: plain Base58 of the seed then the public key. */
        const val VECTOR_SOLANA_KEYPAIR =
            "2Ana1pUpv2ZbMVkwF5FXapYeBEjdxDatLn7nvJkhgTSdZd8hbDHTd21as7EAsg7ypityqfsw2pMQKJcVDVcAEsd"

        /** The Ethereum address the stubbed `0102..1f20` seed controls as a secp256k1 key, computed outside the SDK. */
        const val VECTOR_ETHEREUM_ADDRESS = "0x6370ef2f4db3611d657b90667de398a2cc2a370c"

        /** [defaultWallet] re-addressed to the stubbed seed's Ethereum address; Ethereum only. */
        fun vectorEthereumWallet(): Wallet = walletWithEthereumAddress(VECTOR_ETHEREUM_ADDRESS)

        /** One wallet whose Ethereum and Solana accounts both match [MockTurnkey.stubbedKeyHex]. */
        fun walletWithVectorAccounts(): Wallet = vectorEthereumWallet().let { wallet ->
            wallet.copy(accounts = wallet.accounts + solanaAccount(VECTOR_SOLANA_ADDRESS))
        }

        fun makeActivity(
            id: String,
            from: String,
            to: String,
            caip2: String,
            value: String?,
            data: String?,
            sendTransactionStatusId: String
        ): V1Activity = V1Activity(
            canApprove = false,
            canReject = false,
            createdAt = Externaldatav1Timestamp(nanos = "0", seconds = "1714521600"),
            fingerprint = "fingerprint",
            id = id,
            intent = V1Intent(
                ethSendTransactionIntent = V1EthSendTransactionIntent(
                    caip2 = caip2,
                    data = data,
                    from = from,
                    gasLimit = "21000",
                    gasStationNonce = null,
                    maxFeePerGas = "1000000000",
                    maxPriorityFeePerGas = "1000000000",
                    nonce = "1",
                    sponsor = false,
                    to = to,
                    value = value
                )
            ),
            organizationId = DEFAULT_ORG_ID,
            result = V1Result(
                ethSendTransactionResult = V1EthSendTransactionResult(
                    sendTransactionStatusId = sendTransactionStatusId
                )
            ),
            status = V1ActivityStatus.ACTIVITY_STATUS_COMPLETED,
            type = V1ActivityType.ACTIVITY_TYPE_ETH_SEND_TRANSACTION,
            updatedAt = Externaldatav1Timestamp(nanos = "0", seconds = "1714521600"),
            votes = emptyList()
        )

        /**
         * A `sol_send_transaction` activity: completed with its status id by default, a history
         * fixture or a send's answer; [status], a `null` [sendTransactionStatusId] and [failureMessage]
         * shape the pending, result-less and failed answers the send tests need. [coSignerFirst] puts
         * another signer ahead of [signWith] in a V2 activity's `signWiths`. [resultShape] lets the
         * result take the other shape than the intent, as Turnkey may record it.
         */
        fun makeSolanaActivity(
            id: String,
            signWith: String,
            caip2: String,
            unsignedTransaction: String,
            sendTransactionStatusId: String?,
            createdAtSeconds: String = "1714521600",
            shape: SolanaSendShape = SolanaSendShape.V1,
            status: V1ActivityStatus = V1ActivityStatus.ACTIVITY_STATUS_COMPLETED,
            failureMessage: String? = null,
            coSignerFirst: String? = null,
            resultShape: SolanaSendShape = shape
        ): V1Activity = V1Activity(
            canApprove = false,
            canReject = false,
            createdAt = Externaldatav1Timestamp(nanos = "0", seconds = createdAtSeconds),
            failure = failureMessage?.let { RpcStatus(message = it) },
            fingerprint = "fingerprint",
            id = id,
            intent = when (shape) {
                SolanaSendShape.V1 -> V1Intent(
                    solSendTransactionIntent = V1SolSendTransactionIntent(
                        caip2 = caip2,
                        signWith = signWith,
                        unsignedTransaction = unsignedTransaction
                    )
                )
                SolanaSendShape.V2 -> V1Intent(
                    solSendTransactionIntentV2 = V1SolSendTransactionIntentV2(
                        caip2 = caip2,
                        signWiths = listOfNotNull(coSignerFirst, signWith),
                        unsignedTransaction = unsignedTransaction
                    )
                )
            },
            organizationId = DEFAULT_ORG_ID,
            result = when {
                sendTransactionStatusId == null -> V1Result()
                resultShape == SolanaSendShape.V1 -> V1Result(
                    solSendTransactionResult = V1SolSendTransactionResult(sendTransactionStatusId = sendTransactionStatusId)
                )
                else -> V1Result(
                    solSendTransactionResultV2 = V1SolSendTransactionResultV2(sendTransactionStatusId = sendTransactionStatusId)
                )
            },
            status = status,
            type = when (shape) {
                SolanaSendShape.V1 -> V1ActivityType.ACTIVITY_TYPE_SOL_SEND_TRANSACTION
                SolanaSendShape.V2 -> V1ActivityType.ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2
            },
            updatedAt = Externaldatav1Timestamp(nanos = "0", seconds = createdAtSeconds),
            votes = emptyList()
        )
    }
}
