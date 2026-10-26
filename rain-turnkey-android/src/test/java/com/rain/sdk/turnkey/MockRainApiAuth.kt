package com.rain.sdk.turnkey

/**
 * The Rain client's seam for the managed-auth suites: every call is recorded with the token it
 * carried, and each answers through a replaceable function, so a test queues refusals ([refusal])
 * and answers in the order the flow makes the calls. No HTTP here; the wire contract has its own
 * suite on a mock server ([RainApiAuthClientTest]).
 */
internal class MockRainApiAuth : RainApiAuthClientProtocol {

    data class VerifyOtpCall(val token: String, val otpId: String, val encryptedOtpBundle: String)

    data class SignupCall(val token: String, val verificationToken: String, val clientSignature: RainApiClientSignature)

    data class LoginCall(
        val token: String,
        val verificationToken: String,
        val clientSignature: RainApiClientSignature,
        val subOrganizationId: String?,
    )

    data class SignupPasskeyCall(val token: String, val challenge: String, val attestation: RainApiPasskeyAttestation, val publicKey: String)

    val initOtpCalls = mutableListOf<String>()
    val verifyOtpCalls = mutableListOf<VerifyOtpCall>()
    val signupCalls = mutableListOf<SignupCall>()
    val loginCalls = mutableListOf<LoginCall>()
    val signupPasskeyCalls = mutableListOf<SignupPasskeyCall>()

    /** What each call answers, in the order the calls arrive; the default answers are the happy path. */
    var initOtp: suspend (token: String) -> RainApiOtpChallenge = { RainApiOtpChallenge(DEFAULT_OTP_ID, DEFAULT_BUNDLE) }
    var verifyOtp: suspend (VerifyOtpCall) -> RainApiVerification = { RainApiVerification(DEFAULT_VERIFICATION_TOKEN) }
    var signup: suspend (SignupCall) -> RainApiSignup = { RainApiSignup(DEFAULT_SUB_ORGANIZATION_ID) }
    var login: suspend (LoginCall) -> RainApiLogin = { defaultLogin() }
    var signupPasskey: suspend (SignupPasskeyCall) -> RainApiSignup = { RainApiSignup(DEFAULT_SUB_ORGANIZATION_ID) }

    override suspend fun initOtp(token: String): RainApiOtpChallenge {
        initOtpCalls += token
        return initOtp.invoke(token)
    }

    override suspend fun verifyOtp(token: String, otpId: String, encryptedOtpBundle: String): RainApiVerification {
        val call = VerifyOtpCall(token, otpId, encryptedOtpBundle)
        verifyOtpCalls += call
        return verifyOtp.invoke(call)
    }

    override suspend fun signup(token: String, verificationToken: String, clientSignature: RainApiClientSignature): RainApiSignup {
        val call = SignupCall(token, verificationToken, clientSignature)
        signupCalls += call
        return signup.invoke(call)
    }

    override suspend fun login(
        token: String,
        verificationToken: String,
        clientSignature: RainApiClientSignature,
        subOrganizationId: String?,
    ): RainApiLogin {
        val call = LoginCall(token, verificationToken, clientSignature, subOrganizationId)
        loginCalls += call
        return login.invoke(call)
    }

    override suspend fun signupPasskey(
        token: String,
        challenge: String,
        attestation: RainApiPasskeyAttestation,
        publicKey: String,
    ): RainApiSignup {
        val call = SignupPasskeyCall(token, challenge, attestation, publicKey)
        signupPasskeyCalls += call
        return signupPasskey.invoke(call)
    }

    /** Every token any call carried, in order: what the device sent as the bearer. */
    val tokensSent: List<String>
        get() = initOtpCalls + verifyOtpCalls.map { it.token } + signupCalls.map { it.token } +
            loginCalls.map { it.token } + signupPasskeyCalls.map { it.token }

    /** Every string any call carried: the place a contact must never appear. */
    val everythingSent: List<String>
        get() = initOtpCalls +
            verifyOtpCalls.flatMap { listOf(it.token, it.otpId, it.encryptedOtpBundle) } +
            signupCalls.flatMap { listOf(it.token, it.verificationToken) + it.clientSignature.strings() } +
            loginCalls.flatMap { listOf(it.token, it.verificationToken, it.subOrganizationId.orEmpty()) + it.clientSignature.strings() } +
            signupPasskeyCalls.flatMap { listOf(it.token, it.challenge, it.publicKey) + it.attestation.strings() }

    private fun RainApiClientSignature.strings() = listOf(publicKey, message, signature)

    private fun RainApiPasskeyAttestation.strings() = listOf(credentialId, clientDataJson, attestationObject) + transports

    companion object {
        const val DEFAULT_OTP_ID = "otp-id"
        const val DEFAULT_BUNDLE = "bundle"
        const val DEFAULT_VERIFICATION_TOKEN = "verification-token"
        const val DEFAULT_SUB_ORGANIZATION_ID = "sub-org-id"
        const val DEFAULT_SESSION_JWT = "session-jwt"
        const val LOGIN_PATH = "/v1/issuing/embedded-wallets/login"
        const val VERIFY_PATH = "/v1/issuing/embedded-wallets/otp/verify"
        const val INIT_PATH = "/v1/issuing/embedded-wallets/otp/init"
        const val SIGNUP_PATH = "/v1/issuing/embedded-wallets/signup"
        const val SIGNUP_PASSKEY_PATH = "/v1/issuing/embedded-wallets/signup/passkey"

        fun defaultLogin(subOrganizationId: String = DEFAULT_SUB_ORGANIZATION_ID) =
            RainApiLogin(sessionToken = DEFAULT_SESSION_JWT, providerUserId = subOrganizationId)

        /** The client's refusal for one status on one path, with the kind the client would read off it. */
        fun refusal(kind: RainApiAuthFailure.Kind, status: Int, path: String = LOGIN_PATH) = RainApiAuthFailure(kind, status, path)

        /** `POST /otp/verify`'s refusal of a wrong, expired or locked code. */
        fun wrongCode() = refusal(RainApiAuthFailure.Kind.InvalidCode, 400, VERIFY_PATH)

        /** `POST /login`'s 404: no wallet for this contact in this tenant. */
        fun noWallet() = refusal(RainApiAuthFailure.Kind.NoWallet, 404, LOGIN_PATH)

        /** A 401 for an expired, revoked or unknown token. */
        fun tokenRejected(path: String) = refusal(RainApiAuthFailure.Kind.TokenRejected, 401, path)

        /** A 401 "Invalid tenant" or a 403: a new token does not help. */
        fun forbidden(path: String, status: Int = 403) = refusal(RainApiAuthFailure.Kind.Forbidden, status, path)

        /**
         * Answers in order, the last one sticky: each entry either returns a value or throws, so a
         * test can say "a refusal, then the happy answer".
         */
        fun <I, O> answers(vararg steps: (I) -> O): suspend (I) -> O {
            var index = 0
            return { input ->
                val step = steps[minOf(index, steps.lastIndex)]
                index++
                step(input)
            }
        }
    }
}
