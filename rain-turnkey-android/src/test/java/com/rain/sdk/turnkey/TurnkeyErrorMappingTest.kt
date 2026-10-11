package com.rain.sdk.turnkey

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.DataError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.domerrors.TimeoutError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.error.RainErrorCode
import com.turnkey.core.models.errors.TurnkeyKotlinError
import org.junit.Before
import org.junit.Test

/**
 * Turnkey-error classification tests for [TurnkeyErrorMapping] — covers each `TurnkeyKotlinError`
 * variant it routes (InvalidSession, InvalidParameter, ClientNotInitialized,
 * FailedToSignRawPayload, FailedToCreateWallet, FailedToVerifyOtp, FailedToInitOtp,
 * FailedToExportWallet, FailedToLoginWithPasskey, FailedToSignUpWithPasskey), the passkey and
 * stamper packages' own errors, the wrapper-recurse paths, and the HTTP statuses the vendor reports
 * as plain exceptions.
 *
 * These assert the mapping itself. That the session coordinator applies [TurnkeyErrorMapping.map]
 * to every failure leaving the adapter is covered by [TurnkeySessionCoordinatorTest]; that core
 * passes the result through untouched is covered by `com.rain.sdk.RainSdkAdapterErrorPassthroughTest`.
 *
 * Gated on JDK 24+ because Turnkey's published AAR is compiled to major class version 68
 * (Java 24). See [TurnkeyWalletFlowTest] for the same pattern.
 *
 * IMPORTANT: This file must NOT reference any Turnkey type in a method/field signature.
 * JUnit calls `Class.getDeclaredMethods()` during discovery, which eagerly resolves
 * parameter/return types — and that would trigger a cascading load of
 * `TurnkeyKotlinError` on JDK 21, failing before `assumeTrue` can skip. All Turnkey
 * objects live inside method bodies and are typed as [Any] / [Throwable] at the field level.
 */
class TurnkeyErrorMappingTest {

    private val mapping = TurnkeyErrorMapping

    @Before
    fun requireJdk24() = assumeJdk24()

    @Test
    fun `mapTurnkeyError InvalidSession returns TokenExpired`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidSession()
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `mapTurnkeyError InvalidParameter returns InternalError`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidParameter("bad arg", null)
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `mapTurnkeyError ClientNotInitialized returns InternalError`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.ClientNotInitialized()
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `mapTurnkeyError FailedToSignRawPayload with user-cancel cause maps to UserRejected`() {
        val cause = RuntimeException("User cancelled the operation")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignRawPayload(cause)
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `mapTurnkeyError FailedToSignRawPayload with an insufficient-funds cause maps to InsufficientFunds`() {
        val cause = RuntimeException("insufficient funds for gas * price + value")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignRawPayload(cause)
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.InsufficientFunds::class.java)
    }

    @Test
    fun `mapTurnkeyError FailedToSignRawPayload with a single-word cause stays ProviderError`() {
        val cause = RuntimeException("Transaction cancelled")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignRawPayload(cause)
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `mapTurnkeyError wrapper with an HTTP 401 cause maps to TokenExpired not UserRejected`() {
        // The wrapped cause carries a status and a rejection keyword; the status wins.
        val cause = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\n" +
                "Error: session expired, request cancelled\nCode: 401"
        )
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignRawPayload(cause)
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `mapTurnkeyError unknown variant falls through to ProviderError`() {
        // FailedToCreateWallet wraps a plain Throwable and isn't in the InternalError allowlist.
        val cause = RuntimeException("server 500")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToCreateWallet(cause)
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `classify routes a typed error through mapTurnkeyError`() {
        val turnkeyErr = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidSession()
        val mapped = mapping.classify(turnkeyErr)
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `classify declines a throwable that is not a Turnkey failure`() {
        val mapped = mapping.classify(RuntimeException("something else entirely"))
        assertThat(mapped).isNull()
    }

    @Test
    fun `map floors a throwable nothing recognizes at ProviderError`() {
        val e = RuntimeException("something else entirely")
        val mapped = mapping.map(e)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapped.cause).isSameInstanceAs(e)
    }

    @Test
    fun `map passes a RainError through untouched`() {
        val raised = RainError.InvalidConfig("already mapped")
        assertThat(mapping.map(raised)).isSameInstanceAs(raised)
    }

    @Test
    fun `classify is total for a Turnkey error whose prose reads like a rejection`() {
        // A cause-less vendor error naming no status: mapTurnkeyError still owns it, so core's
        // prose heuristics never see "user rejected" and cannot reclassify it as UserRejected.
        val turnkeyErr = com.turnkey.core.models.errors.TurnkeyKotlinError
            .InvalidResponse("user rejected the request")
        assertThat(mapping.classify(turnkeyErr)).isInstanceOf(RainError.InternalError::class.java)
        // The same holds at map(), which is what the coordinator applies at the boundary.
        assertThat(mapping.map(turnkeyErr)).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `map lets a 401 status beat rejection prose in the same message`() {
        val e = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\n" +
                "Error: user rejected the signing request\nCode: 401"
        )
        assertThat(mapping.map(e)).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `the classifier carries the error code, not just the error class`() {
        val turnkeyErr = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidSession()
        assertThat(mapping.classify(turnkeyErr)?.errorCode).isEqualTo(RainErrorCode.TOKEN_EXPIRED)
    }

    // ---------- managed auth: rejected login codes (RAIN_203) ----------

    @Test
    fun `a rejected login code maps to InvalidLoginCode not TokenExpired`() {
        // The auth proxy answers the verify call with a 4xx and the vendor wraps it twice on the
        // login-or-signup path. A 401 here is a bad code, not a dead session: no session exists yet.
        val http = RuntimeException("HTTP error from /v1/otp_verify_v2: 401")
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(http)
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(verify)
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.InvalidLoginCode::class.java)
        assertThat(mapped.errorCode).isEqualTo(RainErrorCode.INVALID_LOGIN_CODE)
    }

    @Test
    fun `every refused verify status maps to InvalidLoginCode`() {
        listOf(400, 401, 403).forEach { status ->
            val http = RuntimeException("HTTP error from /v1/otp_verify_v2: $status")
            val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(http)
            val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(verify)
            assertThat(mapping.mapTurnkeyError(error)).isInstanceOf(RainError.InvalidLoginCode::class.java)
        }
    }

    @Test
    fun `a proxy 500 on verify is not a rejected code`() {
        // The Kotlin SDK discards the response body, so no embedded upstream status can be
        // recovered from a proxy-wrapped rejection: it stays a provider error and the controller
        // keeps the challenge instead (see isLoginCodeVerifyFailure).
        val http = RuntimeException("HTTP error from /v1/otp_verify_v2: 500")
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(http)
        assertThat(mapping.mapTurnkeyError(verify)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `isLoginCodeVerifyFailure spots the verify step anywhere in the cause chain`() {
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(
            RuntimeException("HTTP error from /v1/otp_verify_v2: 500")
        )
        val wrapped = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(verify)
        val spent = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginOrSignUpWithOtp(
            com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToCreateSession(RuntimeException("io"))
        )
        assertThat(mapping.isLoginCodeVerifyFailure(verify)).isTrue()
        assertThat(mapping.isLoginCodeVerifyFailure(wrapped)).isTrue()
        assertThat(mapping.isLoginCodeVerifyFailure(spent)).isFalse()
        assertThat(mapping.isLoginCodeVerifyFailure(RuntimeException("boom"))).isFalse()
    }

    @Test
    fun `a verify failure without an HTTP status is not a rejected code`() {
        // verifyOtp wraps every failure; a dropped connection must not read as "retype your code".
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(
            java.io.IOException("unreachable")
        )
        val mapped = mapping.mapTurnkeyError(verify)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a rate-limited verify is not a rejected code`() {
        // 429 is "too many attempts" and the repo classifies it as transient elsewhere.
        val http = RuntimeException("HTTP error from /v1/otp_verify_v2: 429")
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(http)
        val mapped = mapping.mapTurnkeyError(verify)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a verify failure wrapping InvalidResponse still maps to InternalError`() {
        val invalid = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidResponse("no verification token")
        val verify = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToVerifyOtp(invalid)
        val mapped = mapping.mapTurnkeyError(verify)
        assertThat(mapped).isInstanceOf(RainError.InternalError::class.java)
    }

    // ---------- managed auth: failed code requests (send path) ----------

    @Test
    fun `a failed code request maps to ProviderError for every status`() {
        // No session exists while a code is being requested, so an init 401 or 403 is not an
        // expired session or a permission problem. The reason (channel disabled, undeliverable
        // number, rate limit) is in the response body the Kotlin SDK drops; the status survives.
        listOf(400, 401, 403, 429, 500).forEach { status ->
            val http = RuntimeException("HTTP error from /v1/otp_init_v2: $status")
            val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToInitOtp(http)
            val mapped = mapping.mapTurnkeyError(error)
            assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
            assertThat(mapped).hasMessageThat().contains(status.toString())
        }
    }

    @Test
    fun `a failed code request without an HTTP status maps to ProviderError`() {
        val dropped = java.io.IOException("unreachable")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToInitOtp(dropped)
        assertThat(mapping.mapTurnkeyError(error)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a failed code request wrapping a vendor setup error keeps that classification`() {
        // The vendor rewraps its own errors on the init path; a client that is not initialized is a
        // setup problem (InternalError), not a request the user can retry.
        val setup = com.turnkey.core.models.errors.TurnkeyKotlinError.ClientNotInitialized()
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToInitOtp(setup)
        assertThat(mapping.mapTurnkeyError(error)).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `a code request that wraps a cancellation while the caller is active is a ProviderError`() {
        // The controller checks the caller's own cancellation before mapping; a vendor wrapper
        // around someone else's cancellation is a vendor failure, not a user rejection.
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToInitOtp(
            java.util.concurrent.CancellationException("inner job")
        )
        assertThat(mapping.mapTurnkeyError(error)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `the status parser reads the init path like the verify path`() {
        val init = RuntimeException("HTTP error from /v1/otp_init_v2: 400")
        val verify = RuntimeException("HTTP error from /v1/otp_verify_v2: 401")
        assertThat(mapping.turnkeyHttpStatus(init)).isEqualTo(400)
        assertThat(mapping.turnkeyHttpStatus(verify)).isEqualTo(401)
    }

    @Test
    fun `mapAuthError never hands the throwable to the log`() {
        // The auth proxy echoes the contact a code was sent to, so the only log line on this path
        // carries the error code and the class name. Captured through a planted tree: Timber folds
        // a throwable's stack trace into the message it hands the tree.
        val seen = StringBuilder()
        val throwables = mutableListOf<Throwable>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                seen.append(message).append('\n')
                if (t != null) throwables += t
            }
        }
        timber.log.Timber.plant(tree)
        try {
            val http = RuntimeException("HTTP error from /v1/otp_init_v2 for someone@example.com: 401")
            // The wrapped shape the vendor produces, and the bare status-carrying shape.
            mapping.mapAuthError(com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToInitOtp(http))
            mapping.mapAuthError(http)
        } finally {
            timber.log.Timber.uproot(tree)
        }
        assertThat(seen.toString()).contains("Authentication error")
        assertThat(seen.toString()).contains(RainErrorCode.TOKEN_EXPIRED.code) // the bare 401 parsed
        assertThat(seen.toString()).doesNotContain("example.com")
        assertThat(throwables).isEmpty()
    }

    @Test
    fun `mapAuthError routes a failed code request to ProviderError`() {
        val http = RuntimeException("HTTP error from /v1/otp_init_v2: 401")
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToInitOtp(http)
        assertThat(mapping.mapAuthError(error)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `mapAuthError on TurnkeyKotlinError routes through mapTurnkeyError`() {
        val turnkeyErr = com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidSession()
        val mapped = mapping.mapAuthError(turnkeyErr)
        assertThat(mapped).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `mapAccountLookupError is ProviderError for every status and passes a RainError through`() {
        // No session stamps the auth proxy's account lookup, so a 401 or 403 inside its failure is
        // neither RAIN_201 nor RAIN_202; the status survives in the message, as on a code request.
        for (status in listOf(400, 401, 403, 429, 500)) {
            val mapped = mapping.mapAccountLookupError(RuntimeException("HTTP error from /v1/account: $status"))
            assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
            assertThat(mapped).hasMessageThat().contains(status.toString())
        }
        assertThat(mapping.mapAccountLookupError(java.io.IOException("offline")))
            .isInstanceOf(RainError.ProviderError::class.java)

        val passthrough = RainError.Unauthorized("refused")
        assertThat(mapping.mapAccountLookupError(passthrough)).isSameInstanceAs(passthrough)
    }

    @Test
    fun `mapAccountLookupError never hands the throwable to the log`() {
        val seen = StringBuilder()
        val throwables = mutableListOf<Throwable>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                seen.append(message).append('\n')
                if (t != null) throwables += t
            }
        }
        timber.log.Timber.plant(tree)
        try {
            mapping.mapAccountLookupError(RuntimeException("HTTP error from /v1/account for someone@example.com: 401"))
        } finally {
            timber.log.Timber.uproot(tree)
        }
        assertThat(seen.toString()).contains("Authentication error")
        assertThat(seen.toString()).contains(RainErrorCode.PROVIDER_ERROR.code)
        assertThat(seen.toString()).doesNotContain("example.com")
        assertThat(throwables).isEmpty()
    }

    // ---------- HTTP statuses the vendor reports as plain exceptions ----------
    //
    // The Turnkey Kotlin SDK throws a plain RuntimeException carrying the status only in the
    // message, in two generated shapes. Both must classify to the same RainError. These moved
    // here from core's ErrorMapperTest when the vendor knowledge left core; the composed cases
    // go through map(), which is what the session coordinator applies at the boundary.

    @Test
    fun `a 401 from the query path maps to TokenExpired`() {
        val e = RuntimeException("HTTP error from /public/v1/query/get_activity: 401")
        assertThat(mapping.classify(e)).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `a 401 from the activity path maps to TokenExpired`() {
        val e = RuntimeException("HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\nError: {}\nCode: 401")
        assertThat(mapping.classify(e)).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `a 403 maps to Unauthorized`() {
        val e = RuntimeException("HTTP error from /public/v1/query/get_activity: 403")
        assertThat(mapping.classify(e)).isInstanceOf(RainError.Unauthorized::class.java)
    }

    @Test
    fun `a 401 whose body contains rejection keywords still maps to TokenExpired`() {
        // The status is the reliable signal; "session expired, request cancelled" is a session
        // problem, and hosts branch on TokenExpired to re-authenticate.
        val e = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\n" +
                "Error: session expired, request cancelled\nCode: 401"
        )
        assertThat(mapping.classify(e)).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `a 403 whose body says permission denied still maps to Unauthorized`() {
        val e = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\n" +
                "Error: permission denied\nCode: 403"
        )
        assertThat(mapping.classify(e)).isInstanceOf(RainError.Unauthorized::class.java)
    }

    @Test
    fun `an unclassified HTTP status falls through to the prose checks`() {
        // 400 is not a session or permission verdict, so classify() declines and map() reads the
        // body: the user declined, and that is what the host must see.
        val e = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\n" +
                "Error: user rejected the signing request\nCode: 400"
        )
        assertThat(mapping.classify(e)).isNull()
        assertThat(mapping.map(e)).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `a vendor HTTP failure that floors at ProviderError keeps its status and target and drops the body`() {
        val update = RuntimeException(
            "HTTP error calling ACTIVITY_TYPE_UPDATE_USER_EMAIL request\nError: {\"email\":\"user@example.com\"}\nCode: 500"
        )
        val mapped = mapping.map(update)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapped).hasMessageThat().contains("500")
        assertThat(mapped).hasMessageThat().contains("ACTIVITY_TYPE_UPDATE_USER_EMAIL")
        assertThat(mapped).hasMessageThat().doesNotContain("example.com")
        assertThat(mapped.cause).isInstanceOf(TurnkeyHttpFailure::class.java)

        val path = RuntimeException("HTTP error from /v1/otp_init_v2 for someone@example.com: 429")
        val init = mapping.map(com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToInitOtp(path))
        assertThat(init).hasMessageThat().contains("429")
        assertThat(init).hasMessageThat().contains("/v1/otp_init_v2")
        assertThat(init).hasMessageThat().doesNotContain("example.com")

        // Not an HTTP failure: the throwable is kept as the cause, message and all.
        val plain = RuntimeException("something else entirely")
        assertThat(mapping.map(plain).cause).isSameInstanceAs(plain)
        assertThat(plain.sanitizedForHost()).isSameInstanceAs(plain)
    }

    @Test
    fun `other HTTP statuses stay ProviderError`() {
        val e = RuntimeException("HTTP error from /public/v1/query/get_activity: 500")
        assertThat(mapping.classify(e)).isNull()
        assertThat(mapping.map(e)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a non-HTTP message ending in digits is not treated as a status`() {
        val e = RuntimeException("Something failed for wallet 401")
        assertThat(mapping.turnkeyHttpStatus(e)).isNull()
        assertThat(mapping.map(e)).isInstanceOf(RainError.ProviderError::class.java)
    }

    // ---- key export: the unlisted FailedToExportWallet variant takes the cause inspection ----

    @Test
    fun `an export failure wrapping HTTP 401 maps to TokenExpired`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet(
            RuntimeException("HTTP error from /public/v1/submit/export_wallet: 401")
        )
        assertThat(mapping.mapTurnkeyError(error)).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `an export failure wrapping HTTP 403 maps to Unauthorized`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet(
            RuntimeException("HTTP error from /public/v1/submit/export_wallet_account: 403")
        )
        assertThat(mapping.mapTurnkeyError(error)).isInstanceOf(RainError.Unauthorized::class.java)
    }

    @Test
    fun `an export failure wrapping HTTP 500 stays ProviderError carrying the status`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet(
            RuntimeException("HTTP error from /public/v1/submit/export_wallet_account: 500")
        )
        val mapped = mapping.mapTurnkeyError(error)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        val chain = mapped.causeChain().toList()
        assertThat(chain.any { it.message?.contains("500") == true }).isTrue()
    }

    @Test
    fun `the export translator's cause-free rejection shape maps to ProviderError`() {
        // TurnkeyExportFailures produces this shape for a vendor crypto error; that it drops the
        // material is pinned in TurnkeyExportFailuresTest, this pins only where the shape lands.
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet(
            IllegalStateException("export bundle rejected: OrgIdMismatch")
        )
        assertThat(mapping.mapTurnkeyError(error)).isInstanceOf(RainError.ProviderError::class.java)
    }

    // ---------- passkey ceremonies (WALL-28) ----------
    //
    // The vendor wraps a login in FailedToLoginWithPasskey over the stamper's and the passkey
    // package's assertion failures, and a sign-up in FailedToSignUpWithPasskey over the package's
    // registration failure; the Credential Manager exception or the HTTP failure sits below those.
    // The chains are built with the real types: they construct on the JVM test launcher, and the
    // mapping reads the innermost cause, not the wrapper's prose.

    /** The login chain the vendor produces around [leaf]. */
    private fun passkeyLoginFailure(leaf: Throwable): Throwable =
        com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginWithPasskey(
            com.turnkey.stamper.utils.TurnkeyStamperError.AssertionFailed(
                com.turnkey.passkey.utils.TurnkeyPasskeyError.AssertionFailed(leaf)
            )
        )

    /** The sign-up chain the vendor produces around [leaf]. */
    private fun passkeySignUpFailure(leaf: Throwable): Throwable =
        com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignUpWithPasskey(
            com.turnkey.passkey.utils.TurnkeyPasskeyError.RegistrationFailed(leaf)
        )

    @Test
    fun `a passkey login the user dismissed maps to UserRejected`() {
        val mapped = mapping.map(passkeyLoginFailure(GetCredentialCancellationException("cancelled")))
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
        assertThat(mapped.errorCode).isEqualTo(RainErrorCode.USER_REJECTED)
    }

    @Test
    fun `a passkey sign-up the user dismissed maps to UserRejected`() {
        val mapped = mapping.map(passkeySignUpFailure(CreateCredentialCancellationException("cancelled")))
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `a NotAllowedError DOM error that says the user cancelled maps to UserRejected`() {
        val leaf = GetPublicKeyCredentialDomException(NotAllowedError(), "The operation was cancelled by the user.")
        assertThat(mapping.map(passkeyLoginFailure(leaf))).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `a NotAllowedError DOM error without a cancel message stays ProviderError`() {
        // WebAuthn's NotAllowedError also covers a time-out, so only the message tells a dismissal apart.
        val leaf = GetPublicKeyCredentialDomException(NotAllowedError(), "The operation either timed out or was not allowed.")
        assertThat(mapping.map(passkeyLoginFailure(leaf))).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a login with no passkey for the domain maps to UserRejected`() {
        val mapped = mapping.map(passkeyLoginFailure(NoCredentialException("No credentials available")))
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `a SecurityError DOM error on sign-up maps to InvalidConfig naming the association file`() {
        val leaf = CreatePublicKeyCredentialDomException(SecurityError(), "The incoming request cannot be validated")
        val mapped = mapping.map(passkeySignUpFailure(leaf))
        assertThat(mapped).isInstanceOf(RainError.InvalidConfig::class.java)
        assertThat(mapped.errorCode).isEqualTo(RainErrorCode.INVALID_CONFIG)
        assertThat(mapped.message).contains("assetlinks.json")
        assertThat(mapped.message).contains("passkeyDomain")
    }

    @Test
    fun `a SecurityError DOM error on login maps to InvalidConfig`() {
        val leaf = GetPublicKeyCredentialDomException(SecurityError(), "The incoming request cannot be validated")
        assertThat(mapping.map(passkeyLoginFailure(leaf))).isInstanceOf(RainError.InvalidConfig::class.java)
    }

    @Test
    fun `a DataError DOM error on sign-up maps to InvalidConfig like a SecurityError`() {
        // Current Play services builds report the association check as DataError 50152, older ones as SecurityError.
        val leaf = CreatePublicKeyCredentialDomException(DataError(), "[50152] RP ID cannot be validated.")
        val mapped = mapping.map(passkeySignUpFailure(leaf))
        assertThat(mapped).isInstanceOf(RainError.InvalidConfig::class.java)
        assertThat(mapped.errorCode).isEqualTo(RainErrorCode.INVALID_CONFIG)
        assertThat(mapped.message).contains("assetlinks.json")
    }

    @Test
    fun `a DataError DOM error on login maps to InvalidConfig`() {
        val leaf = GetPublicKeyCredentialDomException(DataError(), "[50152] RP ID cannot be validated.")
        assertThat(mapping.map(passkeyLoginFailure(leaf))).isInstanceOf(RainError.InvalidConfig::class.java)
    }

    @Test
    fun `an interrupted ceremony maps to ProviderError on both flows`() {
        assertThat(mapping.map(passkeyLoginFailure(GetCredentialInterruptedException("interrupted"))))
            .isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapping.map(passkeySignUpFailure(CreateCredentialInterruptedException("interrupted"))))
            .isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a missing passkey provider maps to ProviderError and the log names the dependency`() {
        val seen = StringBuilder()
        val throwables = mutableListOf<Throwable>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                seen.append(message).append('\n')
                if (t != null) throwables += t
            }
        }
        timber.log.Timber.plant(tree)
        val mapped = try {
            mapping.map(passkeySignUpFailure(CreateCredentialProviderConfigurationException("no provider")))
        } finally {
            timber.log.Timber.uproot(tree)
        }
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(seen.toString()).contains("credentials-play-services-auth")
        assertThat(seen.toString()).contains("CreateCredentialProviderConfigurationException")
        assertThat(throwables).isEmpty()
    }

    @Test
    fun `an unsupported device or a provider with no create option maps to ProviderError`() {
        assertThat(mapping.map(passkeyLoginFailure(GetCredentialUnsupportedException("unsupported"))))
            .isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapping.map(passkeySignUpFailure(CreateCredentialNoCreateOptionException("no option"))))
            .isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a TimeoutError DOM error maps to ProviderError`() {
        val leaf = CreatePublicKeyCredentialDomException(TimeoutError(), "The operation timed out.")
        assertThat(mapping.map(passkeySignUpFailure(leaf))).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a sign-up refused by the auth proxy with 401 is ProviderError not TokenExpired`() {
        // No session exists during a passkey sign-up, so a 401 from /v1/signup_v2 cannot mean an
        // expired session; the proxy's body is discarded by the vendor, so only the status is left.
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignUpWithPasskey(
            RuntimeException("HTTP error from /v1/signup_v2: 401")
        )
        val mapped = mapping.map(error)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapped.errorCode).isEqualTo(RainErrorCode.PROVIDER_ERROR)
    }

    @Test
    fun `every auth-proxy status on a passkey sign-up is ProviderError`() {
        listOf(400, 403, 429, 500).forEach { status ->
            val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignUpWithPasskey(
                RuntimeException("HTTP error from /v1/signup_v2: $status")
            )
            assertThat(mapping.map(error)).isInstanceOf(RainError.ProviderError::class.java)
        }
    }

    @Test
    fun `a passkey stamp login the backend refuses is ProviderError not TokenExpired or Unauthorized`() {
        listOf(401, 403).forEach { status ->
            val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginWithPasskey(
                RuntimeException("HTTP error calling ACTIVITY_TYPE_STAMP_LOGIN request\nError: {}\nCode: $status")
            )
            assertThat(mapping.map(error)).isInstanceOf(RainError.ProviderError::class.java)
        }
    }

    @Test
    fun `a passkey login that hit an occupied session key maps to InternalError`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginWithPasskey(
            com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToCreateSession(
                com.turnkey.core.models.errors.TurnkeyKotlinError.KeyAlreadyExists("rain-turnkey-x")
            )
        )
        assertThat(mapping.map(error)).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `a passkey sign-up whose stamp login returned no session maps to InternalError`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignUpWithPasskey(
            com.turnkey.core.models.errors.TurnkeyKotlinError.InvalidResponse("No session token returned from stampLogin")
        )
        assertThat(mapping.map(error)).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `a passkey sign-up the proxy answered without an organization id maps to ProviderError`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToSignUpWithPasskey(
            com.turnkey.core.models.errors.TurnkeyKotlinError.SignUpFailed("No organizationId returned")
        )
        assertThat(mapping.map(error)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a bare MissingRpId stays InternalError`() {
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.MissingRpId()
        assertThat(mapping.map(error)).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `a passkey login whose stamper found no domain maps to ProviderError`() {
        // The vendor resolves the rpId inside its try on login, so a missing one arrives wrapped as
        // an IllegalArgumentException, not as MissingRpId. Unreachable through the controller, which
        // passes the domain per call; pinned so the fall-through stays a provider error.
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginWithPasskey(
            IllegalArgumentException("rpId is required. Either pass it explicitly or set a default with Stamper.configure(context, rpId)")
        )
        assertThat(mapping.map(error)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a bare registration failure the user dismissed maps to UserRejected`() {
        // The add-passkey ceremony runs createPasskey without the vendor's sign-up wrapper.
        val error = com.turnkey.passkey.utils.TurnkeyPasskeyError.RegistrationFailed(
            CreateCredentialCancellationException("cancelled")
        )
        assertThat(mapping.map(error)).isInstanceOf(RainError.UserRejected::class.java)
        assertThat(mapping.classify(error)).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `a bare registration failure over a SecurityError maps to InvalidConfig`() {
        val error = com.turnkey.passkey.utils.TurnkeyPasskeyError.RegistrationFailed(
            CreatePublicKeyCredentialDomException(SecurityError(), "The incoming request cannot be validated")
        )
        assertThat(mapping.map(error)).isInstanceOf(RainError.InvalidConfig::class.java)
    }

    @Test
    fun `a bare stamper failure is classified by its cause like a wrapped login failure`() {
        // The stamper's own wrapper without the vendor's login wrapper around it.
        val cancelled = com.turnkey.stamper.utils.TurnkeyStamperError.AssertionFailed(
            com.turnkey.passkey.utils.TurnkeyPasskeyError.AssertionFailed(
                androidx.credentials.exceptions.GetCredentialCancellationException("cancelled")
            )
        )
        assertThat(mapping.classify(cancelled)).isInstanceOf(RainError.UserRejected::class.java)

        val broken = com.turnkey.stamper.utils.TurnkeyStamperError.AssertionFailed(RuntimeException("boom"))
        assertThat(mapping.classify(broken)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `a passkey failure whose only signal is the prose maps to UserRejected`() {
        // No typed leaf in the chain: the shared prose rules read the wrapper's message, which the
        // vendor builds by concatenating every cause's message.
        val mapped = mapping.map(passkeyLoginFailure(RuntimeException("Passkey retrieval was cancelled by the user.")))
        assertThat(mapped).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `a cyclic cause chain under a passkey failure terminates in ProviderError`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val error = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToLoginWithPasskey(a)
        assertThat(mapping.map(error)).isInstanceOf(RainError.ProviderError::class.java)
    }

    @Test
    fun `mapAuthError logs a passkey failure's code and class and never its message`() {
        val seen = StringBuilder()
        val throwables = mutableListOf<Throwable>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                seen.append(message).append('\n')
                if (t != null) throwables += t
            }
        }
        timber.log.Timber.plant(tree)
        try {
            val leaf = GetPublicKeyCredentialDomException(SecurityError(), "request for someone@example.com refused")
            mapping.mapAuthError(passkeyLoginFailure(leaf))
        } finally {
            timber.log.Timber.uproot(tree)
        }
        assertThat(seen.toString()).contains("Authentication error")
        assertThat(seen.toString()).contains(RainErrorCode.INVALID_CONFIG.code)
        assertThat(seen.toString()).contains("FailedToLoginWithPasskey")
        assertThat(seen.toString()).doesNotContain("example.com")
        assertThat(throwables).isEmpty()
    }

    /**
     * The vendor's typed error for an activity not completed after its poll (`com.turnkey:http` 2.2.0).
     * Its message names the status, so a REJECTED activity reads "has status ACTIVITY_STATUS_REJECTED",
     * which must not be mistaken for a user rejecting a signing request: the classifier wants two-word
     * phrases such as "rejected by user". The send paths take the activity out of this error before
     * anything is mapped; anywhere else (export, a registration, a contact update) it is the provider's
     * failure with the status, and no response body, in the message.
     */
    @Test
    fun `map on the vendor's not-completed error for a rejected activity is ProviderError, never UserRejected`() {
        val error = MockTurnkey.activityNotCompleted(
            path = "/public/v1/submit/create_authenticators",
            type = com.turnkey.types.V1ActivityType.ACTIVITY_TYPE_CREATE_AUTHENTICATORS_V2,
            status = com.turnkey.types.V1ActivityStatus.ACTIVITY_STATUS_REJECTED,
            failureMessage = "rejected by policy"
        )
        assertThat(error.message).contains("ACTIVITY_STATUS_REJECTED")

        val mapped = mapping.map(error)

        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapped.errorCode).isEqualTo(RainErrorCode.PROVIDER_ERROR)
        assertThat(mapped.message).contains("ACTIVITY_STATUS_REJECTED")
        assertThat(mapping.turnkeyHttpStatus(error)).isNull()
    }
}
