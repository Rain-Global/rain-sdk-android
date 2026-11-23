package com.rain.sdk.portal

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import io.portalhq.android.Portal
import io.portalhq.android.exceptions.PortalException
import io.portalhq.android.mpc.data.FeatureFlags
import io.portalhq.android.provider.data.PortalProviderResult
import io.portalhq.android.provider.data.PortalProviderRpcResponse
import io.portalhq.android.provider.data.PortalRequestMethod
import io.portalhq.android.provider.data.RequestOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.IOException

/**
 * Hash resolution inside [PortalManager.sendTransaction]: a hash the chain can answer for is
 * returned untouched, a UserOperation hash resolves through the EntryPoint's
 * `UserOperationEvent` to the transaction it was mined in, a reverted operation surfaces
 * as [RainError.TransactionSimulationFailed] rather than as a mined transaction, and an
 * unresolved one is [RainError.TransactionPending], never a hash no node can answer for.
 * Also the pre-flight `eth_call`: what it reports must say whether the send is worth retrying.
 */
class PortalManagerSendTransactionTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    private val chainId = 43114
    private val submittedHash = "0x" + "a".repeat(64)
    private val minedHash = "0x" + "b".repeat(64)

    private fun managerWith(portal: Portal, retryIntervalMs: Long = 0): PortalManager {
        // Zero retry interval by default: the full scan window and every retry run instantly.
        val manager = spyk(PortalManager(retryIntervalMs = retryIntervalMs))
        every { manager.createPortal(any(), any(), any(), any(), any()) } returns portal
        manager.initialize(
            apiKey = "session-token",
            legacyEthChainId = chainId,
            rpcConfig = mapOf("eip155:$chainId" to "https://rpc.test"),
            featureFlags = FeatureFlags(isMultiBackupEnabled = true),
            autoApprove = true
        )
        return manager
    }

    private fun rpc(result: Any?) = PortalProviderResult(
        id = "id",
        result = PortalProviderRpcResponse(jsonrpc = "2.0", result = result)
    )

    /** UserOperationEvent data: nonce, success, actualGasCost, actualGasUsed. */
    private fun eventData(succeeded: Boolean): String {
        val zeroWord = "0".repeat(64)
        val successWord = "0".repeat(63) + if (succeeded) "1" else "0"
        return "0x" + zeroWord + successWord + zeroWord + zeroWord
    }

    private fun portalForSend(): Portal {
        val portal = mockk<Portal>(relaxed = true)
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_call, any(), null as RequestOptions?)
        } returns rpc("0x")
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_blockNumber, any(), null as RequestOptions?)
        } returns rpc("0x10")
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_sendTransaction, any(), null as RequestOptions?)
        } returns PortalProviderResult(id = "id", result = submittedHash)
        return portal
    }

    private fun send(manager: PortalManager): String = runBlocking {
        manager.sendTransaction(
            chainId = chainId,
            from = TestFixtures.WALLET_ADDRESS,
            to = TestFixtures.CONTRACT_ADDRESS,
            data = "0x095ea7b3deadbeef"
        )
    }

    @Test
    fun `a hash the chain knows is returned without scanning EntryPoint logs`() {
        val portal = portalForSend()
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getTransactionByHash, any(), null as RequestOptions?)
        } returns rpc(mapOf("hash" to submittedHash))

        assertThat(send(managerWith(portal))).isEqualTo(submittedHash)
        coVerify(exactly = 0) {
            portal.request(any(), PortalRequestMethod.eth_getLogs, any(), null as RequestOptions?)
        }
    }

    @Test
    fun `a UserOperation hash resolves to the transaction it was mined in`() {
        val portal = portalForSend()
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getTransactionByHash, any(), null as RequestOptions?)
        } returns rpc(null)
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getLogs, any(), null as RequestOptions?)
        } returns rpc(listOf(mapOf("transactionHash" to minedHash, "data" to eventData(succeeded = true))))

        assertThat(send(managerWith(portal))).isEqualTo(minedHash)
    }

    /**
     * Handing the raw UserOperation hash on would send the caller's receipt poll after a hash no
     * node knows: an 80-second false timeout for an approval a slow bundler still mines.
     */
    @Test
    fun `a UserOperation that does not resolve within the scan window is pending, carrying its hash`() {
        val portal = portalForSend()
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getTransactionByHash, any(), null as RequestOptions?)
        } returns rpc(null)
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getLogs, any(), null as RequestOptions?)
        } returns rpc(emptyList<Any>())

        val error = runCatching { send(managerWith(portal)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.TransactionPending::class.java)
        assertThat((error as RainError.TransactionPending).statusId).isEqualTo(submittedHash)
        // The whole scan window was spent before giving up.
        coVerify(exactly = 20) {
            portal.request(any(), PortalRequestMethod.eth_getLogs, any(), null as RequestOptions?)
        }
    }

    /**
     * A lookup that fails every time is indistinguishable from "not mined yet" to the caller, and
     * must end the same way: pending with the hash, after the whole window, never a raw throw.
     */
    @Test
    fun `lookups that keep failing still end as pending after the full window`() {
        val portal = portalForSend()
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getTransactionByHash, any(), null as RequestOptions?)
        } throws IOException("connection reset")
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getLogs, any(), null as RequestOptions?)
        } throws IOException("connection reset")

        val error = runCatching { send(managerWith(portal)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.TransactionPending::class.java)
        assertThat((error as RainError.TransactionPending).statusId).isEqualTo(submittedHash)
        coVerify(exactly = 20) {
            portal.request(any(), PortalRequestMethod.eth_getLogs, any(), null as RequestOptions?)
        }
    }

    /**
     * The scan's catch blocks rethrow CancellationException before anything else. A host that
     * cancels a send mid-scan must see the cancellation, never a TransactionPending built from it.
     */
    @Test
    fun `cancelling a send mid-scan propagates the cancellation, not a pending result`(): Unit =
        runBlocking {
            val portal = portalForSend()
            coEvery {
                portal.request(any(), PortalRequestMethod.eth_getTransactionByHash, any(), null as RequestOptions?)
            } returns rpc(null)
            var logScans = 0
            coEvery {
                portal.request(any(), PortalRequestMethod.eth_getLogs, any(), null as RequestOptions?)
            } answers {
                logScans++
                rpc(emptyList<Any>())
            }
            val manager = managerWith(portal, retryIntervalMs = 50)

            var outcome: Throwable? = null
            val job = launch {
                outcome = runCatching {
                    manager.sendTransaction(
                        chainId = chainId,
                        from = TestFixtures.WALLET_ADDRESS,
                        to = TestFixtures.CONTRACT_ADDRESS,
                        data = "0x095ea7b3deadbeef"
                    )
                }.exceptionOrNull()
            }
            // Past the first scan and parked in the interval delay.
            while (logScans == 0) delay(1)
            job.cancelAndJoin()

            assertThat(outcome).isInstanceOf(CancellationException::class.java)
            assertThat(logScans).isLessThan(20)
        }

    /** The pre-submit block read has no side effects, so one failure must not switch the scan off. */
    @Test
    fun `a failed block-number read is retried before the scan is given up`() {
        val portal = portalForSend()
        var blockNumberCalls = 0
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_blockNumber, any(), null as RequestOptions?)
        } answers {
            if (++blockNumberCalls == 1) throw IOException("connection reset") else rpc("0x10")
        }
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getTransactionByHash, any(), null as RequestOptions?)
        } returns rpc(mapOf("hash" to submittedHash))

        assertThat(send(managerWith(portal))).isEqualTo(submittedHash)
        assertThat(blockNumberCalls).isEqualTo(2)
    }

    /**
     * Retries exhausted: there is no lower bound to scan from, but a hash the chain already knows
     * needs no scan, so a plain transaction still goes through.
     */
    @Test
    fun `when every block-number read fails a hash the chain knows is still returned`() {
        val portal = portalForSend()
        var blockNumberCalls = 0
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_blockNumber, any(), null as RequestOptions?)
        } answers {
            blockNumberCalls++
            throw IOException("connection reset")
        }
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getTransactionByHash, any(), null as RequestOptions?)
        } returns rpc(mapOf("hash" to submittedHash))

        assertThat(send(managerWith(portal))).isEqualTo(submittedHash)
        assertThat(blockNumberCalls).isEqualTo(3)
        coVerify(exactly = 0) {
            portal.request(any(), PortalRequestMethod.eth_getLogs, any(), null as RequestOptions?)
        }
    }

    /**
     * Retries exhausted and the chain does not know the hash: on an AA chain that is a
     * UserOperation hash, and handing it on would burn the caller's receipt window against
     * something no node can answer for. Same outcome as the scan window expiring.
     */
    @Test
    fun `when every block-number read fails an unknown hash is pending, not handed on`() {
        val portal = portalForSend()
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_blockNumber, any(), null as RequestOptions?)
        } throws IOException("connection reset")
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getTransactionByHash, any(), null as RequestOptions?)
        } returns rpc(null)

        val error = runCatching { send(managerWith(portal)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.TransactionPending::class.java)
        assertThat((error as RainError.TransactionPending).statusId).isEqualTo(submittedHash)
        coVerify(exactly = 0) {
            portal.request(any(), PortalRequestMethod.eth_getLogs, any(), null as RequestOptions?)
        }
    }

    /** A block-number response that does not parse leaves no lower bound either, and ends the same way. */
    @Test
    fun `an unparseable block-number response is treated like a failed read`() {
        val portal = portalForSend()
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_blockNumber, any(), null as RequestOptions?)
        } returns rpc("not-a-block")
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getTransactionByHash, any(), null as RequestOptions?)
        } returns rpc(null)

        val error = runCatching { send(managerWith(portal)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.TransactionPending::class.java)
        assertThat((error as RainError.TransactionPending).statusId).isEqualTo(submittedHash)
        coVerify(exactly = 0) {
            portal.request(any(), PortalRequestMethod.eth_getLogs, any(), null as RequestOptions?)
        }
    }

    // ---- the pre-flight eth_call -----------------------------------------------------------

    /**
     * A network failure is retryable and nothing was broadcast; reporting it as a failed
     * simulation would tell the withdrawal flow the send reverted and must not be retried.
     */
    @Test
    fun `a network failure on the pre-flight is NetworkError, not a failed simulation`() {
        val portal = portalForSend()
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_call, any(), null as RequestOptions?)
        } throws IOException("connection reset")

        val error = runCatching { send(managerWith(portal)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.NetworkError::class.java)
        coVerify(exactly = 0) {
            portal.request(any(), PortalRequestMethod.eth_sendTransaction, any(), null as RequestOptions?)
        }
    }

    @Test
    fun `a RainError raised on the pre-flight passes through unchanged`() {
        val portal = portalForSend()
        val classified = RainError.NetworkError("already classified upstream")
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_call, any(), null as RequestOptions?)
        } throws classified

        val error = runCatching { send(managerWith(portal)) }.exceptionOrNull()

        assertThat(error).isSameInstanceAs(classified)
    }

    @Test
    fun `a revert on the pre-flight is still a failed simulation`() {
        val portal = portalForSend()
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_call, any(), null as RequestOptions?)
        } throws PortalException.Api.RpcError(code = 3, message = "execution reverted")

        val error = runCatching { send(managerWith(portal)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
    }

    @Test
    fun `a reverted UserOperation throws rather than reporting a mined transaction`() {
        val portal = portalForSend()
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getTransactionByHash, any(), null as RequestOptions?)
        } returns rpc(null)
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getLogs, any(), null as RequestOptions?)
        } returns rpc(listOf(mapOf("transactionHash" to minedHash, "data" to eventData(succeeded = false))))

        val error = runCatching { send(managerWith(portal)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
    }
}
