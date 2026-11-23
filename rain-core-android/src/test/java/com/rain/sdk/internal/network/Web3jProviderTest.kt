package com.rain.sdk.internal.network

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService

class Web3jProviderTest {

    @Before
    fun setUp() {
        mockkStatic(Web3j::class)
        // Clear cache manually if possible, or assume isolation.
        // Since Web3jProvider is an object, state persists.
        Web3jProvider.resetForTest()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Web3jProvider.resetForTest()
    }

    @Test
    fun `getOrCreate returns same instance for same url`() {
        val mockWeb3j1 = mockk<Web3j>(relaxed = true)
        val url = "https://rpc.com"

        every { Web3j.build(any<HttpService>()) } returns mockWeb3j1

        val instance1 = Web3jProvider.getOrCreate(url)
        val instance2 = Web3jProvider.getOrCreate(url)

        assertThat(instance1).isSameInstanceAs(instance2)
        assertThat(instance1).isEqualTo(mockWeb3j1)

        // Verify build called only once
        verify(exactly = 1) { Web3j.build(any<HttpService>()) }
    }

    @Test
    fun `getOrCreate returns different instances for different urls`() {
        val mockWeb3j1 = mockk<Web3j>(relaxed = true)
        val mockWeb3j2 = mockk<Web3j>(relaxed = true)
        val url1 = "https://rpc1.com"
        val url2 = "https://rpc2.com"

        // Mock build to return different instances based on logic, or just sequence
        // Since HttpService equals is not reliable for matching, we use side effects or sequence.
        // Let's use answer with check

        var callCount = 0
        every { Web3j.build(any<HttpService>()) } answers {
            callCount++
            if (callCount == 1) mockWeb3j1 else mockWeb3j2
        }

        val instance1 = Web3jProvider.getOrCreate(url1)
        val instance2 = Web3jProvider.getOrCreate(url2)

        assertThat(instance1).isNotSameInstanceAs(instance2)
        assertThat(instance1).isEqualTo(mockWeb3j1)
        assertThat(instance2).isEqualTo(mockWeb3j2)
    }

    @Test
    fun `resetForTest shuts down all instances and clears the cache`() {
        val mockWeb3j = mockk<Web3j>(relaxed = true)
        val url = "https://rpc.com"

        every { Web3j.build(any<HttpService>()) } returns mockWeb3j

        Web3jProvider.getOrCreate(url)
        Web3jProvider.resetForTest()

        verify { mockWeb3j.shutdown() }

        // Verify cache cleared by checking if next get creates new one
        // (Reset call count logic or mocks)

        val newMockWeb3j = mockk<Web3j>(relaxed = true)
        every { Web3j.build(any<HttpService>()) } returns newMockWeb3j

        val instanceAfterShutdown = Web3jProvider.getOrCreate(url)
        assertThat(instanceAfterShutdown).isEqualTo(newMockWeb3j)
        assertThat(instanceAfterShutdown).isNotSameInstanceAs(mockWeb3j)
    }
}
