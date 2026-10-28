package com.rain.sdk.internal.network

import com.rain.sdk.internal.RainAdapterApi
import com.rain.sdk.internal.constants.RainConstants
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The one OkHttp client behind the SDK's own HTTP calls, as variants sharing its dispatcher and
 * connection pool: [rpc] for JSON-RPC reads, [rainApi] for Rain's embedded-wallet endpoints and the
 * stamped login, [web3j] for the withdrawal reads. The wallet vendors' contexts keep their own clients.
 *
 * Process-wide, because the managed login runs before any `RainSdk` exists. Never shut down: idle
 * threads and connections expire on their own, and a shutdown would break a second `RainSdk`.
 */
@RainAdapterApi
object RainHttp {
    /** The [rpc] bound on a whole call; OkHttp's 10 s connect, read and write apply beneath it. */
    const val RPC_CALL_TIMEOUT_SECONDS: Long = 10L

    /** The [rainApi] bound on each read and on the whole call. */
    internal const val RAIN_API_TIMEOUT_SECONDS: Long = 45L

    /** The [web3j] bound on each of connect, read and write. */
    internal const val WEB3J_PHASE_TIMEOUT_SECONDS: Long = RainConstants.NETWORK_TIMEOUT_SECONDS

    /** The [web3j] bound on a whole call, retries included. */
    internal const val WEB3J_CALL_TIMEOUT_SECONDS: Long = 60L

    /** Above core's widest fan-out (three metadata reads per token); OkHttp's default of five would queue the rest. */
    internal const val MAX_REQUESTS_PER_HOST: Int = 16

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST })
            .build()
    }

    /**
     * JSON-RPC reads and simulations: [RPC_CALL_TIMEOUT_SECONDS] on the whole call, retries and
     * redirects on. Nothing broadcasts through it, which is what makes the retries safe.
     */
    @RainAdapterApi
    val rpc: OkHttpClient by lazy { rpcVariant(RPC_CALL_TIMEOUT_SECONDS) }

    /**
     * Rain's embedded-wallet endpoints and the stamped login: [RAIN_API_TIMEOUT_SECONDS] per read and
     * per call, above rain-api's slowest leg. No redirects and no connection retry, since every leg
     * carries a bearer or a stamp and has a side effect; the cost is that a stale pooled connection
     * fails one leg (`RAIN_301`) instead of reconnecting.
     */
    @RainAdapterApi
    val rainApi: OkHttpClient by lazy {
        base.newBuilder()
            .readTimeout(RAIN_API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(RAIN_API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * The withdrawal reads through web3j: [WEB3J_PHASE_TIMEOUT_SECONDS] per phase,
     * [WEB3J_CALL_TIMEOUT_SECONDS] on the whole call, retries on.
     */
    internal val web3j: OkHttpClient by lazy {
        base.newBuilder()
            .connectTimeout(WEB3J_PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(WEB3J_PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WEB3J_PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(WEB3J_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** [rpc] with another bound on the whole call, same dispatcher and pool; the default bound answers [rpc] itself. */
    @RainAdapterApi
    fun rpcWithCallTimeout(callTimeoutSeconds: Long): OkHttpClient =
        if (callTimeoutSeconds == RPC_CALL_TIMEOUT_SECONDS) rpc else rpcVariant(callTimeoutSeconds)

    private fun rpcVariant(callTimeoutSeconds: Long): OkHttpClient =
        base.newBuilder().callTimeout(callTimeoutSeconds, TimeUnit.SECONDS).build()
}
