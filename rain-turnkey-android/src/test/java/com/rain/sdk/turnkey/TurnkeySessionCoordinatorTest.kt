package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.turnkey.core.models.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException

class TurnkeySessionCoordinatorTest {

    @Before
    fun requireJdk24() = assumeJdk24()

    private class RecordingDelay {
        val delays = mutableListOf<Long>()
        val fn: suspend (Long) -> Unit = { delays += it }
    }

    private fun coordinator(
        turnkey: MockTurnkey,
        policy: TurnkeySessionPolicy = TurnkeySessionPolicy(),
        onSessionExpired: (() -> Unit)? = null,
        delayRecorder: RecordingDelay = RecordingDelay(),
        now: () -> Double = { System.currentTimeMillis() / 1000.0 },
    ) = TurnkeySessionCoordinator(
        turnkey = turnkey,
        policy = policy,
        onSessionExpired = onSessionExpired,
        nowEpochSeconds = now,
        retryDelay = delayRecorder.fn,
    )

    // ---------- expiry checks ----------

    @Test
    fun `missing session throws TokenExpired without touching the client`() {
        val turnkey = MockTurnkey(session = null)
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        val coordinator = coordinator(turnkey)

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
            }
        }
        assertThat(client.getActivitiesCalls).isEmpty()
    }

    @Test
    fun `expired session throws TokenExpired without a server call when autoRefresh is off`() {
        val turnkey = MockTurnkey(session = MockTurnkey.expiredSession())
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        val coordinator = coordinator(turnkey, policy = TurnkeySessionPolicy(autoRefresh = false))

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
            }
        }
        assertThat(client.getActivitiesCalls).isEmpty()
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
    }

    @Test
    fun `near-expiry session inside the buffer proceeds without refresh when autoRefresh is off`() =
        runBlocking {
            val turnkey = MockTurnkey(session = MockTurnkey.nearExpirySession(remainingSeconds = 10))
            val client = turnkey.turnkeyClient as MockTurnkeyClient
            val coordinator = coordinator(turnkey, policy = TurnkeySessionPolicy(autoRefresh = false))

            coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }

            assertThat(client.getActivitiesCalls).hasSize(1)
            assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
        }

    // ---------- proactive refresh ----------

    @Test
    fun `near-expiry session is refreshed proactively before the call`() = runBlocking {
        val turnkey = MockTurnkey(session = MockTurnkey.nearExpirySession(remainingSeconds = 10))
        turnkey.onRefreshSession = { turnkey.session = MockTurnkey.defaultSession() }
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        val coordinator = coordinator(turnkey)

        coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }

        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
        assertThat(client.getActivitiesCalls).hasSize(1)
    }

    @Test
    fun `expired session is refreshed and the call proceeds`() = runBlocking {
        val turnkey = MockTurnkey(session = MockTurnkey.expiredSession())
        turnkey.onRefreshSession = { turnkey.session = MockTurnkey.defaultSession() }
        val coordinator = coordinator(turnkey)

        coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }

        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
    }

    @Test
    fun `refresh TTL from the policy is passed through to Turnkey`() = runBlocking {
        val turnkey = MockTurnkey(session = MockTurnkey.expiredSession())
        turnkey.onRefreshSession = { turnkey.session = MockTurnkey.defaultSession() }
        val coordinator = coordinator(
            turnkey,
            policy = TurnkeySessionPolicy(refreshExpirationSeconds = 1800L)
        )

        coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }

        assertThat(turnkey.refreshSessionCalls).containsExactly("1800").inOrder()
    }

    @Test
    fun `default policy refreshes with Turnkey's default lifetime`() = runBlocking {
        val turnkey = MockTurnkey(session = MockTurnkey.expiredSession())
        turnkey.onRefreshSession = { turnkey.session = MockTurnkey.defaultSession() }
        val coordinator = coordinator(turnkey)

        coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }

        // A missing value, not the string "null": Turnkey applies its default only when none is sent.
        assertThat(turnkey.refreshSessionCalls).isEqualTo(listOf<String?>(null))
    }

    @Test
    fun `failed refresh surfaces TokenExpired and fires the expiry hook once`() {
        val turnkey = MockTurnkey(session = MockTurnkey.expiredSession())
        turnkey.refreshSessionError = RuntimeException("refresh rejected")
        var hookCalls = 0
        val coordinator = coordinator(turnkey, onSessionExpired = { hookCalls++ })

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
            }
        }
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
            }
        }
        assertThat(hookCalls).isEqualTo(1)
    }

    // ---------- refresh-on-401 retry ----------

    @Test
    fun `401 mid-call refreshes the session and retries once`() = runBlocking {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.getActivitiesError = RuntimeException("HTTP error from /activities: 401")
        turnkey.onRefreshSession = {
            turnkey.session = MockTurnkey.defaultSession()
            client.getActivitiesError = null
        }
        val coordinator = coordinator(turnkey)

        val result = coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }

        assertThat(result.activities).isEmpty()
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
        assertThat(client.getActivitiesCalls).hasSize(2)
    }

    @Test
    fun `persistent 401 surfaces TokenExpired after one refresh-and-retry`() {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.getActivitiesError = RuntimeException("HTTP error from /activities: 401")
        var hookCalls = 0
        val coordinator = coordinator(turnkey, onSessionExpired = { hookCalls++ })

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
            }
        }
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
        assertThat(client.getActivitiesCalls).hasSize(2)
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `401 on a write also refreshes and retries once`() = runBlocking {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.getActivitiesError = RuntimeException("HTTP error from /activities: 401")
        turnkey.onRefreshSession = { client.getActivitiesError = null }
        val coordinator = coordinator(turnkey)

        coordinator.executeWrite { s, c -> c.getActivities(activitiesBody(s.organizationId)) }

        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
        assertThat(client.getActivitiesCalls).hasSize(2)
    }

    @Test
    fun `401 with autoRefresh off surfaces TokenExpired without a refresh attempt`() {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.getActivitiesError = RuntimeException("HTTP error from /activities: 401")
        val coordinator = coordinator(turnkey, policy = TurnkeySessionPolicy(autoRefresh = false))

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
            }
        }
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
        assertThat(client.getActivitiesCalls).hasSize(1)
    }

    // ---------- transient backoff ----------

    @Test
    fun `transient 500 on a read retries with exponential backoff and then succeeds`() = runBlocking {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        var failures = 2
        val flaky = object : TurnkeyClientProtocol by client {
            override suspend fun getActivities(
                input: com.turnkey.types.TGetActivitiesBody
            ): com.turnkey.types.TGetActivitiesResponse {
                if (failures > 0) {
                    failures--
                    throw RuntimeException("HTTP error from /activities: 500")
                }
                return client.getActivities(input)
            }
        }
        turnkey.turnkeyClient = flaky
        val delays = RecordingDelay()
        val coordinator = coordinator(turnkey, delayRecorder = delays)

        val result = coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }

        assertThat(result.activities).isEmpty()
        assertThat(delays.delays).containsExactly(500L, 1000L).inOrder()
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
    }

    @Test
    fun `transient failures beyond maxTransientRetries surface the original error`() {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.getActivitiesError = IOException("connection reset")
        val delays = RecordingDelay()
        val coordinator = coordinator(
            turnkey,
            policy = TurnkeySessionPolicy(maxTransientRetries = 2),
            delayRecorder = delays
        )

        val thrown = assertThrows(RainError.ProviderError::class.java) {
            runBlocking {
                coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
            }
        }
        assertThat(thrown).hasCauseThat().isInstanceOf(IOException::class.java)
        assertThat(thrown).hasCauseThat().hasMessageThat().contains("connection reset")
        assertThat(client.getActivitiesCalls).hasSize(3)
        assertThat(delays.delays).hasSize(2)
    }

    @Test
    fun `backoff delays are capped at maxRetryDelayMs`() {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.getActivitiesError = IOException("flaky")
        val delays = RecordingDelay()
        val coordinator = coordinator(
            turnkey,
            policy = TurnkeySessionPolicy(
                maxTransientRetries = 4,
                initialRetryDelayMs = 500,
                maxRetryDelayMs = 1_000
            ),
            delayRecorder = delays
        )

        assertThrows(RainError.ProviderError::class.java) {
            runBlocking {
                coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
            }
        }
        assertThat(delays.delays).containsExactly(500L, 1000L, 1000L, 1000L).inOrder()
    }

    @Test
    fun `transient failure on a write is not retried`() {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.ethSendTransactionError = IOException("timed out")
        val coordinator = coordinator(turnkey)

        val thrown = assertThrows(RainError.ProviderError::class.java) {
            runBlocking {
                coordinator.executeWrite { s, c ->
                    c.ethSendTransaction(sendBody(s.organizationId))
                }
            }
        }
        assertThat(thrown).hasCauseThat().isInstanceOf(IOException::class.java)
        assertThat(client.ethSendTransactionCalls).hasSize(1)
    }

    @Test
    fun `non-transient non-auth errors are not retried on reads`() {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.getActivitiesError = RuntimeException("HTTP error from /activities: 400")
        val coordinator = coordinator(turnkey)

        assertThrows(RainError.ProviderError::class.java) {
            runBlocking {
                coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
            }
        }
        assertThat(client.getActivitiesCalls).hasSize(1)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
    }

    // ---------- the boundary: nothing leaves as a vendor type ----------

    @Test
    fun `a vendor failure the coordinator does not retry leaves as its Rain error, not raw`() {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        // 403 is neither a session problem (401) nor transient, so it takes the else branch.
        client.getActivitiesError = RuntimeException("HTTP error from /activities: 403")
        val coordinator = coordinator(turnkey)

        assertThrows(RainError.Unauthorized::class.java) {
            runBlocking {
                coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
            }
        }
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
    }

    @Test
    fun `a RainError raised inside the block passes through the boundary untouched`() {
        val turnkey = MockTurnkey()
        val coordinator = coordinator(turnkey)
        val raised = RainError.InvalidConfig("no RPC endpoint")

        val thrown = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { coordinator.executeRead<String> { _, _ -> throw raised } }
        }
        assertThat(thrown).isSameInstanceAs(raised)
    }

    @Test
    fun `a cancellation raised inside the block is rethrown as itself, never mapped`() {
        val turnkey = MockTurnkey()
        val coordinator = coordinator(turnkey)
        val cancel = kotlinx.coroutines.CancellationException("caller went away")

        val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { coordinator.executeRead<String> { _, _ -> throw cancel } }
        }
        assertThat(thrown).isSameInstanceAs(cancel)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
    }

    @Test
    fun `a typed vendor error that is neither auth nor transient leaves with its Rain code`() {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.getActivitiesError = com.turnkey.core.models.errors.TurnkeyKotlinError.ClientNotInitialized()
        val coordinator = coordinator(turnkey)

        assertThrows(RainError.InternalError::class.java) {
            runBlocking {
                coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
            }
        }
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
        assertThat(client.getActivitiesCalls).hasSize(1)
    }

    @Test
    fun `a TokenExpired raised inside the block takes the refresh branch, not the map branch`() = runBlocking {
        val turnkey = MockTurnkey()
        turnkey.onRefreshSession = { turnkey.session = MockTurnkey.defaultSession() }
        val coordinator = coordinator(turnkey)
        var attempts = 0

        val result = coordinator.executeRead<String> { _, _ ->
            if (attempts++ == 0) throw RainError.TokenExpired() else "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(attempts).isEqualTo(2)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
    }

    @Test
    fun `an unmapped failure is logged once at warning level and a RainError not at all`() {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        val raw = RuntimeException("HTTP error from /activities: 403")
        client.getActivitiesError = raw
        val coordinator = coordinator(turnkey)
        val entries = mutableListOf<Triple<Int, Throwable?, String>>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                entries += Triple(priority, t, message)
            }
        }
        timber.log.Timber.plant(tree)
        try {
            runCatching {
                runBlocking { coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) } }
            }
            val vendorEntries = entries.toList()
            entries.clear()
            runCatching {
                runBlocking { coordinator.executeRead<String> { _, _ -> throw RainError.InvalidConfig("ours") } }
            }
            assertThat(vendorEntries).hasSize(1)
            assertThat(vendorEntries.single().first).isEqualTo(android.util.Log.WARN)
            // An HTTP failure's message can carry the response body, so the line gets the status and
            // target and the wrapper's class, never the throwable; the raw exception stays out of the log.
            assertThat(vendorEntries.single().second).isNull()
            assertThat(vendorEntries.single().third).contains("HTTP error from /activities: 403")
            assertThat(vendorEntries.single().third).contains(raw.javaClass.simpleName)
            assertThat(entries).isEmpty()
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    // ---------- concurrency ----------

    @Test
    fun `concurrent near-expiry calls share one refresh`() = runBlocking {
        val turnkey = MockTurnkey(session = MockTurnkey.nearExpirySession(remainingSeconds = 10))
        turnkey.onRefreshSession = { turnkey.session = MockTurnkey.defaultSession() }
        val coordinator = coordinator(turnkey)

        coroutineScope {
            repeat(4) {
                launch {
                    coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
                }
            }
        }

        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
    }

    @Test
    fun `concurrent 401s rotate the session only once`() = runBlocking {
        val turnkey = MockTurnkey()
        val client = turnkey.turnkeyClient as MockTurnkeyClient
        client.getActivitiesError = RuntimeException("HTTP error from /activities: 401")
        turnkey.onRefreshSession = {
            turnkey.session = MockTurnkey.defaultSession().copy(publicKey = "rotated-pubkey")
            client.getActivitiesError = null
        }
        val coordinator = coordinator(turnkey)

        coroutineScope {
            repeat(4) {
                launch {
                    coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
                }
            }
        }

        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
    }

    // ---------- cold start ----------

    @Test
    fun `call during session restore waits for loading to resolve instead of expiring`() = runTest {
        val turnkey = MockTurnkey(session = null)
        turnkey.authStateFlow.value = AuthState.loading
        var hookCalls = 0
        val coordinator = TurnkeySessionCoordinator(
            turnkey = turnkey,
            onSessionExpired = { hookCalls++ },
            retryDelay = { },
        )
        val client = turnkey.turnkeyClient as MockTurnkeyClient

        val call = async {
            coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
        }
        runCurrent()
        assertThat(call.isCompleted).isFalse()

        // The restore finishes: a valid session appears and auth leaves loading.
        turnkey.session = MockTurnkey.defaultSession()
        turnkey.authStateFlow.value = AuthState.authenticated
        runCurrent()

        assertThat(call.await().activities).isEmpty()
        assertThat(client.getActivitiesCalls).hasSize(1)
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `never-logged-in user gets TokenExpired but the expiry hook stays silent`() {
        val turnkey = MockTurnkey(session = null)
        var hookCalls = 0
        val coordinator = coordinator(turnkey, onSessionExpired = { hookCalls++ })

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                coordinator.executeRead { s, c -> c.getActivities(activitiesBody(s.organizationId)) }
            }
        }
        assertThat(hookCalls).isEqualTo(0)
    }

    // ---------- manual refresh ----------

    @Test
    fun `refreshNow refreshes through Turnkey`() = runBlocking {
        val turnkey = MockTurnkey()
        val coordinator = coordinator(turnkey)

        coordinator.refreshNow()

        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
    }

    @Test
    fun `refreshNow surfaces TokenExpired when the refresh fails`() {
        val turnkey = MockTurnkey()
        turnkey.refreshSessionError = RuntimeException("refresh rejected")
        var hookCalls = 0
        val coordinator = coordinator(turnkey, onSessionExpired = { hookCalls++ })

        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { coordinator.refreshNow() }
        }
        assertThat(hookCalls).isEqualTo(1)
    }

    // ---------- session state ----------

    @Test
    fun `currentState reflects auth state and expiry`() {
        val turnkey = MockTurnkey()
        val coordinator = coordinator(turnkey)

        assertThat(coordinator.currentState())
            .isInstanceOf(TurnkeySessionState.Active::class.java)

        turnkey.session = MockTurnkey.expiredSession()
        assertThat(coordinator.currentState()).isEqualTo(TurnkeySessionState.Expired)

        turnkey.session = null
        assertThat(coordinator.currentState()).isEqualTo(TurnkeySessionState.Unauthenticated)

        turnkey.authStateFlow.value = AuthState.loading
        assertThat(coordinator.currentState()).isEqualTo(TurnkeySessionState.Loading)
    }

    @Test
    fun `sessionStates emits Unauthenticated when Turnkey clears the session`() = runBlocking {
        val turnkey = MockTurnkey()
        val coordinator = coordinator(turnkey)

        val first = coordinator.sessionStates.first()
        assertThat(first).isInstanceOf(TurnkeySessionState.Active::class.java)

        turnkey.session = null
        turnkey.authStateFlow.value = AuthState.unauthenticated
        val next = coordinator.sessionStates.first()
        assertThat(next).isEqualTo(TurnkeySessionState.Unauthenticated)
    }

    @Test
    fun `sessionStates emits Expired when an active session passes its expiry`() = runTest {
        // Fixed virtual clock: the session expires 30s from "now".
        var virtualNow = 1_000_000.0
        val turnkey = MockTurnkey(
            session = MockTurnkey.defaultSession().copy(expiry = 1_000_030.0)
        )
        val coordinator = TurnkeySessionCoordinator(
            turnkey = turnkey,
            nowEpochSeconds = { virtualNow },
        )

        val states = mutableListOf<TurnkeySessionState>()
        val job = launch { coordinator.sessionStates.collect { states += it } }
        runCurrent()
        assertThat(states.last()).isInstanceOf(TurnkeySessionState.Active::class.java)

        virtualNow = 1_000_031.0
        advanceTimeBy(31_000)
        runCurrent()
        assertThat(states.last()).isEqualTo(TurnkeySessionState.Expired)
        job.cancelAndJoin()
    }

    @Test
    fun `monitoring fires the expiry hook when an active session dies silently`() = runTest {
        var virtualNow = 1_000_000.0
        val turnkey = MockTurnkey(
            session = MockTurnkey.defaultSession().copy(expiry = 1_000_030.0)
        )
        var hookCalls = 0
        val coordinator = TurnkeySessionCoordinator(
            turnkey = turnkey,
            onSessionExpired = { hookCalls++ },
            nowEpochSeconds = { virtualNow },
        )
        val monitorScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        coordinator.startMonitoring(monitorScope)

        runCurrent()
        assertThat(hookCalls).isEqualTo(0)

        virtualNow = 1_000_031.0
        advanceTimeBy(31_000)
        runCurrent()
        assertThat(hookCalls).isEqualTo(1)

        // A later logout emission must not re-fire for the same death.
        turnkey.session = null
        turnkey.authStateFlow.value = AuthState.unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(1)

        monitorScope.cancel()
    }

    @Test
    fun `hook re-arms after a re-login and fires again for a second death`() = runTest {
        val turnkey = MockTurnkey()
        var hookCalls = 0
        val coordinator = TurnkeySessionCoordinator(
            turnkey = turnkey,
            onSessionExpired = { hookCalls++ },
            retryDelay = { },
        )
        val monitorScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        coordinator.startMonitoring(monitorScope)
        runCurrent()

        turnkey.session = null
        turnkey.authStateFlow.value = AuthState.unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(1)

        // Re-login re-arms; a second death must fire again.
        turnkey.session = MockTurnkey.defaultSession()
        turnkey.authStateFlow.value = AuthState.authenticated
        runCurrent()
        turnkey.session = null
        turnkey.authStateFlow.value = AuthState.unauthenticated
        runCurrent()
        assertThat(hookCalls).isEqualTo(2)
        monitorScope.cancel()
    }

    @Test
    fun `hook fires when an active session dies through a loading transition`() = runTest {
        val turnkey = MockTurnkey()
        var hookCalls = 0
        val coordinator = TurnkeySessionCoordinator(
            turnkey = turnkey,
            onSessionExpired = { hookCalls++ },
            retryDelay = { },
        )
        val monitorScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        coordinator.startMonitoring(monitorScope)
        runCurrent()

        // Turnkey re-enters loading (e.g. a restore cycle) and comes out unauthenticated.
        turnkey.authStateFlow.value = AuthState.loading
        runCurrent()
        turnkey.session = null
        turnkey.authStateFlow.value = AuthState.unauthenticated
        runCurrent()

        assertThat(hookCalls).isEqualTo(1)
        monitorScope.cancel()
    }

    // ---------- fixtures ----------

    private fun activitiesBody(organizationId: String) = com.turnkey.types.TGetActivitiesBody(
        organizationId = organizationId,
        filterByType = emptyList(),
        paginationOptions = null
    )

    private fun sendBody(organizationId: String) = com.turnkey.types.TEthSendTransactionBody(
        organizationId = organizationId,
        caip2 = "eip155:1",
        data = "0x",
        from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
        gasLimit = "21000",
        maxFeePerGas = "1",
        maxPriorityFeePerGas = "1",
        nonce = "1",
        sponsor = false,
        to = MockTurnkey.DEFAULT_WALLET_ADDRESS,
        value = "0"
    )

    @Test
    fun `deathEpoch advances once per session death and on every replacement`() = runBlocking {
        val turnkey = MockTurnkey(session = null)
        val coordinator = TurnkeySessionCoordinator(turnkey = turnkey, retryDelay = { })

        // Never logged in is not a death: nothing was cached under a session.
        assertThrows(RainError.TokenExpired::class.java) { runBlocking { coordinator.refreshNow() } }
        assertThat(coordinator.deathEpoch).isEqualTo(0)

        // A live session that dies counts once, however often the death is observed.
        turnkey.session = MockTurnkey.defaultSession()
        coordinator.refreshNow()
        turnkey.session = null
        assertThrows(RainError.TokenExpired::class.java) { runBlocking { coordinator.refreshNow() } }
        assertThrows(RainError.TokenExpired::class.java) { runBlocking { coordinator.refreshNow() } }
        assertThat(coordinator.deathEpoch).isEqualTo(1)

        // A login over a live session is not a death the watcher sees, so it is counted explicitly.
        turnkey.session = MockTurnkey.defaultSession()
        coordinator.notifySessionReplaced()
        assertThat(coordinator.deathEpoch).isEqualTo(2)
    }
}
