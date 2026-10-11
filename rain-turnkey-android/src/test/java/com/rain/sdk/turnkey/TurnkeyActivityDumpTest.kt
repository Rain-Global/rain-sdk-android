package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.rain.sdk.error.RainError
import com.turnkey.core.models.errors.TurnkeyKotlinError
import com.turnkey.http.utils.TurnkeyHttpError
import com.turnkey.types.V1ActivityStatus
import com.turnkey.types.V1ActivityType
import com.turnkey.types.V1HashFunction
import com.turnkey.types.V1Intent
import com.turnkey.types.V1PayloadEncoding
import com.turnkey.types.V1SignRawPayloadIntentV2
import com.turnkey.types.V1UpdateUserEmailIntent
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import kotlin.reflect.full.primaryConstructor

/**
 * The mapper's one exit for the activity the typed client's `ActivityNotCompleted` carries (since
 * `com.turnkey:http` 2.2.0). The exception is a data class whose `toString()` prints the whole
 * `V1Activity`, and the activity's intent echoes the request: the email or phone being attached, the
 * EIP-712 payload being signed. Whichever wrapper carries it and whichever path raised it, the host
 * gets the activity's id, type, status and reason, never the activity, in the error's cause chain and
 * in the log. The two send funnels take the exception apart themselves; these are the other paths.
 */
class TurnkeyActivityDumpTest {

    private val mapping = TurnkeyErrorMapping

    @Before
    fun requireJdk24() = assumeJdk24()

    private fun attachingEmail(email: String, token: String) = MockTurnkey.activityNotCompleted(
        path = "/public/v1/submit/update_user_email",
        type = V1ActivityType.ACTIVITY_TYPE_UPDATE_USER_EMAIL,
        status = V1ActivityStatus.ACTIVITY_STATUS_PENDING,
        intent = V1Intent(
            updateUserEmailIntent = V1UpdateUserEmailIntent(userEmail = email, userId = "user-id", verificationToken = token)
        )
    )

    private fun signing(payload: String) = MockTurnkey.activityNotCompleted(
        path = "/public/v1/submit/sign_raw_payload",
        type = V1ActivityType.ACTIVITY_TYPE_SIGN_RAW_PAYLOAD_V2,
        status = V1ActivityStatus.ACTIVITY_STATUS_REJECTED,
        failureMessage = "policy engine denied the request",
        intent = V1Intent(
            signRawPayloadIntentV2 = V1SignRawPayloadIntentV2(
                encoding = V1PayloadEncoding.PAYLOAD_ENCODING_EIP712,
                hashFunction = V1HashFunction.HASH_FUNCTION_NO_OP,
                payload = payload,
                signWith = MockTurnkey.DEFAULT_WALLET_ADDRESS
            )
        )
    )

    /** Everything a host could read off the error: every element's `toString()` and stack trace. */
    private fun RainError.rendered(): List<String> = causeChain().map { it.toString() + "\n" + it.stackTraceToString() }.toList()

    private inline fun <T> capturingLog(block: (MutableList<Triple<Int, Throwable?, String>>) -> T): T {
        val entries = mutableListOf<Triple<Int, Throwable?, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                entries += Triple(priority, t, message)
            }
        }
        Timber.plant(tree)
        return try {
            block(entries)
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `the mapper's exit drops the activity the typed error carries, whichever wrapper brought it`() {
        val email = "attached-email-the-host-must-not-see@example.com"
        val token = "verification-token-the-host-must-not-see"
        val notCompleted = attachingEmail(email, token)
        // The same wrappers the HTTP-body test drives, plus the bare exception.
        val routes = TurnkeyKotlinError::class.sealedSubclasses.mapNotNull { wrapper ->
            val constructor = wrapper.primaryConstructor ?: return@mapNotNull null
            if (constructor.parameters.none { it.type.classifier == Throwable::class }) return@mapNotNull null
            val arguments = constructor.parameters.associateWith { parameter ->
                when (parameter.type.classifier) {
                    Throwable::class -> notCompleted
                    String::class -> "x"
                    else -> error("unexpected parameter ${parameter.name} on ${wrapper.simpleName}")
                }
            }
            wrapper.simpleName to constructor.callBy(arguments)
        } + ("bare" to notCompleted)

        routes.forEach { (name, error) ->
            val mapped = mapping.map(error)
            val rendered = mapped.rendered()
            assertWithMessage(name).that(rendered.none { it.contains(email) || it.contains(token) }).isTrue()
            assertWithMessage(name).that(mapped.causeChain().none { it is TurnkeyHttpError.ActivityNotCompleted }).isTrue()
            if (mapped is RainError.ProviderError || mapped is RainError.InternalError) {
                val slim = mapped.causeChain().filterIsInstance<TurnkeyActivityFailure>().firstOrNull()
                assertWithMessage(name).that(slim).isNotNull()
                assertWithMessage(name).that(slim!!.activityId).isEqualTo(notCompleted.activity.id)
                assertWithMessage(name).that(slim.status).isEqualTo(V1ActivityStatus.ACTIVITY_STATUS_PENDING)
            }
        }
        assertThat(routes.size).isAtLeast(40)
    }

    @Test
    fun `the coordinator logs an activity the vendor could not complete as its id and status, never the activity`() {
        val payload = """{"primaryType":"Withdraw","message":{"recipient":"0xrecipient-the-host-must-not-see","amount":"123456"}}"""
        val turnkey = MockTurnkey()
        turnkey.signRawPayloadError = TurnkeyKotlinError.FailedToSignRawPayload(signing(payload))
        val coordinator = TurnkeySessionCoordinator(turnkey = turnkey, onSessionExpired = {}, retryDelay = { })

        val (thrown, entries) = capturingLog { entries ->
            val thrown = assertThrows(RainError.ProviderError::class.java) {
                runBlocking {
                    coordinator.executeWrite { _, _ ->
                        turnkey.signRawPayload(
                            MockTurnkey.DEFAULT_WALLET_ADDRESS,
                            payload,
                            V1PayloadEncoding.PAYLOAD_ENCODING_EIP712,
                            V1HashFunction.HASH_FUNCTION_NO_OP
                        )
                    }
                }
            }
            thrown to entries.toList()
        }

        assertThat(thrown.rendered().none { it.contains("the-host-must-not-see") }).isTrue()
        assertThat(thrown.message).contains("ACTIVITY_STATUS_REJECTED")
        assertThat(thrown.message).contains("policy engine denied the request")
        val warnings = entries.filter { it.first == android.util.Log.WARN }
        assertThat(warnings).hasSize(1)
        assertThat(warnings.single().second).isNull()
        assertThat(warnings.single().third).doesNotContain("the-host-must-not-see")
        assertThat(warnings.single().third).contains("ACTIVITY_STATUS_REJECTED")
        assertThat(warnings.single().third).contains("FailedToSignRawPayload")
        assertThat(entries.none { entry -> entry.third.contains("the-host-must-not-see") }).isTrue()
    }

    @Test
    fun `confirmContactVerification keeps the contact being attached out of the error and the log`() {
        val email = "attached-email-the-host-must-not-see@example.com"
        val token = "verification-token-the-host-must-not-see"
        val turnkey = MockTurnkey()
        turnkey.setUserContactError = attachingEmail(email, token)
        val controller = TurnkeyManagedAuthController(
            context = turnkey,
            coordinator = TurnkeySessionCoordinator(turnkey = turnkey, onSessionExpired = {}, retryDelay = { }),
            configure = { null },
            passkeyDomain = "passkeys.example.com",
        )

        val (thrown, entries) = capturingLog { entries ->
            val thrown = assertThrows(RainError.ProviderError::class.java) {
                runBlocking {
                    controller.sendContactVerificationCode(LoginContact.Email("user@example.com"))
                    controller.confirmContactVerification("123456")
                }
            }
            thrown to entries.toList()
        }

        assertThat(turnkey.setUserEmailCalls).hasSize(1)
        assertThat(thrown.rendered().none { it.contains(email) || it.contains(token) }).isTrue()
        assertThat(thrown.message).contains("ACTIVITY_STATUS_PENDING")
        assertThat(entries.none { entry -> entry.third.contains(email) || entry.third.contains(token) }).isTrue()
        assertThat(entries.none { entry -> entry.second?.toString()?.contains(email) == true }).isTrue()
    }

    @Test
    fun `signTypedData keeps the typed data out of the error and the log`() {
        val payload = """{"primaryType":"Withdraw","message":{"recipient":"0xrecipient-the-host-must-not-see","amount":"123456"}}"""
        val turnkey = MockTurnkey()
        turnkey.signRawPayloadError = TurnkeyKotlinError.FailedToSignRawPayload(signing(payload))
        val provider = turnkeyWalletProvider(turnkey = turnkey, rpcEndpoints = emptyMap(), httpClient = OkHttpClient())

        val (thrown, entries) = capturingLog { entries ->
            val thrown = assertThrows(RainError.ProviderError::class.java) {
                runBlocking { provider.signTypedData(1, MockTurnkey.DEFAULT_WALLET_ADDRESS, payload) }
            }
            thrown to entries.toList()
        }

        assertThat(turnkey.signRawPayloadCalls).hasSize(1)
        assertThat(thrown.rendered().none { it.contains("the-host-must-not-see") }).isTrue()
        assertThat(thrown.message).contains("policy engine denied the request")
        assertThat(entries.none { entry -> entry.third.contains("the-host-must-not-see") }).isTrue()
        assertThat(entries.none { entry -> entry.second?.toString()?.contains("the-host-must-not-see") == true }).isTrue()
    }
}
