package com.rain.sdk.sample

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.wallet.RainWalletContact
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/** The demo's Rain API client exists exactly while a non-blank Api-Key and user id pair does. */
class RainSessionRainApiTest {

    @Test
    fun `requireRainApi throws the credentials message until a pair is configured`() {
        val session = RainSession()

        assertThat(session.isRainApiConfigured).isFalse()
        val error = assertThrows(IllegalStateException::class.java) { session.requireRainApi() }
        assertThat(error).hasMessageThat().isEqualTo(RainSession.RAIN_API_CREDENTIALS_REQUIRED)

        session.configureRainApi(" key-123 ", " user-abc ")

        assertThat(session.isRainApiConfigured).isTrue()
        assertThat(session.requireRainApi()).isSameInstanceAs(session.rainApi)
    }

    @Test
    fun `a blank key or user id clears the client, and so does clearRainApi`() {
        val session = RainSession()
        session.configureRainApi("key-123", "user-abc")

        session.configureRainApi("", "user-abc")
        assertThat(session.isRainApiConfigured).isFalse()

        session.configureRainApi("key-123", "   ")
        assertThat(session.isRainApiConfigured).isFalse()

        session.configureRainApi("key-123", "user-abc")
        session.clearRainApi()
        assertThat(session.isRainApiConfigured).isFalse()
    }

    @Test
    fun `the wallet auth token provider refuses without a configured client`() {
        val session = RainSession()
        val provider = session.walletAuthTokenProvider()

        // The IllegalStateException the SDK reports as RAIN_102 naming the class; the mint itself is
        // demo code against Rain's endpoint, exercised on the device run.
        val refused = assertThrows(IllegalStateException::class.java) { runBlocking { provider(RainWalletContact.Email("a@b.c")) } }
        assertThat(refused).hasMessageThat().isEqualTo(RainSession.RAIN_API_CREDENTIALS_REQUIRED)
    }
}
