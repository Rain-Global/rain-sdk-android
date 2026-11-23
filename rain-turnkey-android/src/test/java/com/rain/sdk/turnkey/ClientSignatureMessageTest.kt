package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.turnkey.core.internal.utils.ClientSignature
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * The messages the device signs for Rain's sign-up and login, built by the vendor's own
 * `ClientSignature` with the arguments [TurnkeyContextAdapter] passes. Rain hands the sign-up
 * message to Turnkey, which verifies it against the root user it creates: the three lists explicit
 * and empty, the contact under the field its channel names and no other field for it. The vendor's
 * serializer omits a null, so a vendor bump that changed a default would change the signed bytes
 * with every controller test still green; this is the pin a bump re-checks. No keystore is
 * involved, only the vendor's JWT decoder, so the suite runs under the usual JDK gate.
 */
class ClientSignatureMessageTest {

    @Before
    fun setUp() = assumeJdk24()

    /** A verification token in the shape Rain's verify answers: the claims the vendor reads, under a signature nobody checks here. */
    private fun verificationToken(publicKey: String = "04device", tokenId: String = "tok-1"): String {
        val payload = JSONObject()
            .put("contact", "user@example.com")
            .put("exp", 1_700_000_600)
            .put("id", tokenId)
            .put("public_key", publicKey)
            .put("verification_type", "VERIFICATION_TYPE_EMAIL")
            .put("organization_id", "org-1")
        val b64 = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"none"}""", payload.toString(), "sig").joinToString(".") { b64.encodeToString(it.toByteArray()) }
    }

    private fun signUp(email: String?, phoneNumber: String?) = ClientSignature.forSignUp(
        verificationToken = verificationToken(),
        email = email,
        phoneNumber = phoneNumber,
        apiKeys = emptyList(),
        authenticators = emptyList(),
        oauthProviders = emptyList(),
    )

    @Test
    fun `the sign-up message names the contact under its channel's field, with the three lists written as empty arrays`() {
        val byEmail = signUp(email = "user@example.com", phoneNumber = null)
        assertThat(byEmail.clientSignaturePublicKey).isEqualTo("04device")
        val emailMessage = JSONObject(byEmail.message)
        assertThat(emailMessage.getString("type")).isEqualTo("USAGE_TYPE_SIGNUP")
        assertThat(emailMessage.getString("tokenId")).isEqualTo("tok-1")
        assertThat(emailMessage.has("login")).isFalse()
        val emailUsage = emailMessage.getJSONObject("signupV2")
        assertThat(emailUsage.keys().asSequence().toList()).containsExactly("apiKeys", "authenticators", "email", "oauthProviders")
        assertThat(emailUsage.getString("email")).isEqualTo("user@example.com")
        assertThat(emailUsage.has("phoneNumber")).isFalse()
        // The bytes that get signed: literal empty arrays, not omitted fields.
        assertThat(byEmail.message).contains("\"apiKeys\":[]")
        assertThat(byEmail.message).contains("\"authenticators\":[]")
        assertThat(byEmail.message).contains("\"oauthProviders\":[]")

        val byPhone = signUp(email = null, phoneNumber = "+19999999999")
        val phoneUsage = JSONObject(byPhone.message).getJSONObject("signupV2")
        assertThat(phoneUsage.keys().asSequence().toList()).containsExactly("apiKeys", "authenticators", "oauthProviders", "phoneNumber")
        assertThat(phoneUsage.getString("phoneNumber")).isEqualTo("+19999999999")
        assertThat(phoneUsage.has("email")).isFalse()
        assertThat(byPhone.message).doesNotContain("example.com")
    }

    @Test
    fun `the login message names the token's own public key and nothing of a sign-up`() {
        val payload = ClientSignature.forLogin(verificationToken(publicKey = "04pk", tokenId = "tok-7"))
        val message = JSONObject(payload.message)
        assertThat(payload.clientSignaturePublicKey).isEqualTo("04pk")
        assertThat(message.getString("type")).isEqualTo("USAGE_TYPE_LOGIN")
        assertThat(message.getString("tokenId")).isEqualTo("tok-7")
        assertThat(message.getJSONObject("login").getString("publicKey")).isEqualTo("04pk")
        assertThat(message.has("signupV2")).isFalse()
        assertThat(message.has("signup")).isFalse()
    }
}
