package com.rain.sdk.privy

import com.rain.sdk.error.RainError
import io.privy.sdk.Privy
import io.privy.wallet.ethereum.EmbeddedEthereumWallet
import io.privy.wallet.ethereum.EthereumChain
import io.privy.wallet.ethereum.EthereumRpcRequest
import io.privy.wallet.solana.EmbeddedSolanaWallet
import io.privy.wallet.solana.SolanaCluster
import io.privy.wallet.transactions.GetTransactionsParams
import io.privy.wallet.transactions.TransactionChain
import io.privy.wallet.transactions.TransactionsPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import timber.log.Timber

/**
 * Wrapper around the Privy SDK encapsulating all Privy interactions — the custody seam.
 *
 * Privy owns signing and broadcasting via its EIP-1193 embedded-wallet provider; balance/fee reads
 * run through [PrivyRpcClient] instead. Authentication and `Privy.init` happen in the host app
 * (mirroring Turnkey), so this only ever consumes an already-authenticated [Privy] singleton.
 */
internal class PrivyManager(
    private val privy: Privy,
    // Guards every Privy call: auth-state check, transient backoff, typed session death.
    private val sessions: PrivySessionCoordinator = PrivySessionCoordinator(privy),
) {

    /**
     * Serializes [sendTransaction]. Privy's EIP-1193 provider is stateful: [sendTransaction] points
     * it at a chain via `switchChain` and then broadcasts, so two concurrent sends to different
     * chains could interleave the switch of one with the request of the other. Signing takes no
     * chain state, so only sends are guarded.
     */
    private val sendMutex = Mutex()

    /**
     * Resolves the embedded Ethereum wallet to sign with. Uses [addressOverride] when supplied,
     * otherwise the user's first embedded Ethereum wallet.
     */
    suspend fun resolveWallet(addressOverride: String?): EmbeddedEthereumWallet {
        val user = sessions.requireUser()
        val wallets = user.embeddedEthereumWallets
        if (wallets.isEmpty()) {
            throw RainError.WalletUnavailable(
                "Privy user has no embedded Ethereum wallet; call createEthereumWallet() first"
            )
        }
        return if (addressOverride.isNullOrEmpty()) {
            wallets.first()
        } else {
            wallets.firstOrNull { it.address.equals(addressOverride, ignoreCase = true) }
                ?: throw RainError.WalletUnavailable("No Privy wallet matches address $addressOverride")
        }
    }

    /** The signing wallet's address. */
    suspend fun getAddress(addressOverride: String?): String =
        sessions.executeRead { resolveWallet(addressOverride).address }

    /**
     * Signs EIP-712 typed data via `eth_signTypedData_v4`. Returns the 0x-prefixed signature.
     */
    suspend fun signTypedData(
        walletAddress: String,
        typedDataJson: String,
    ): String = sessions.executeSign {
        val wallet = resolveWallet(walletAddress)
        val privyTypedData = toPrivyTypedDataJson(typedDataJson)
        request(wallet, EthereumRpcRequest.ethSignTypedDataV4(wallet.address, privyTypedData))
    }

    /**
     * Rewrites the EIP-712 primary-type key to the shape Privy expects.
     *
     * Privy's `EthereumTypedDataV4` deserializes that field from snake_case `primary_type` (it is
     * annotated `@SerialName("primary_type")`), while the EIP-712 standard — and the JSON Rain core
     * builds — uses camelCase `primaryType`. Without this rename Privy fails with
     * "Field 'primary_type' is required". Turnkey and Portal consume the standard shape unchanged,
     * so the translation is kept adapter-local rather than pushed into core.
     */
    private fun toPrivyTypedDataJson(typedDataJson: String): String {
        val obj = JSONObject(typedDataJson)
        if (obj.has("primaryType") && !obj.has("primary_type")) {
            obj.put("primary_type", obj.remove("primaryType"))
        }
        return obj.toString()
    }

    /**
     * Broadcasts a transaction via `eth_sendTransaction`, returning the tx hash. Points Privy at
     * Rain's configured RPC, [rpcUrl], via [EthereumChain.Custom] first; the wallet fills any
     * omitted gas/nonce. [transactionJson] carries from/to/data/value/chainId.
     */
    suspend fun sendTransaction(
        walletAddress: String,
        rpcUrl: String,
        transactionJson: String,
    ): String = sessions.executeSend {
        val wallet = resolveWallet(walletAddress)
        sendMutex.withLock {
            // Privy's EthereumChain.Custom (privy-core 0.15.0) only carries an RPC URL; no chain
            // variant accepts both a custom RPC URL and a chain id. The chain id still reaches the
            // node because [transactionJson] embeds it, and the named SupportedEthereumChain values
            // are not used since they would route RPC through Privy's endpoints instead of Rain's.
            wallet.provider.switchChain(EthereumChain.Custom(rpcUrl))
            request(wallet, EthereumRpcRequest.ethSendTransaction(transactionJson))
        }
    }

    /**
     * Fetches one page of transaction history for the signing wallet via Privy's indexer.
     *
     * Privy vendor exceptions classify via [PrivyErrorMapping]; anything else bubbles up raw
     * for the same reason as [request]: core's error mapping classifies the underlying error,
     * and pre-wrapping would hide it.
     */
    suspend fun getTransactions(
        walletAddress: String?,
        params: GetTransactionsParams<TransactionChain.Evm>,
    ): TransactionsPage = sessions.executeRead {
        resolveWallet(walletAddress).getTransactions(params).getOrElse { error ->
            Timber.e(error, "Rain SDK: Privy getTransactions failed")
            PrivyErrorMapping.mapOrNull(error)?.let { throw it }
            throw error
        }
    }

    // ---------- Solana ----------

    /**
     * Resolves the embedded Solana wallet to sign with — the user's first one. The provider's
     * address override is an Ethereum address, so it does not participate here.
     */
    suspend fun resolveSolanaWallet(): EmbeddedSolanaWallet {
        val user = sessions.requireUser()
        return user.embeddedSolanaWallets.firstOrNull()
            ?: throw RainError.WalletUnavailable(
                "Privy user has no embedded Solana wallet; call createSolanaWallet() first"
            )
    }

    /** The Solana signing wallet's base58 address. */
    suspend fun getSolanaAddress(): String =
        sessions.executeRead { resolveSolanaWallet().address }

    /**
     * Signs an unsigned Solana transaction and broadcasts it, returning the base58 signature.
     * Broadcast goes through [rpcUrl] — Rain's configured endpoint — rather than Privy's default
     * cluster RPC. Unlike [sendTransaction], no mutex: the Solana provider carries no chain state
     * between calls, so there is nothing to interleave.
     */
    suspend fun signAndSendSolanaTransaction(
        transaction: ByteArray,
        cluster: SolanaCluster,
        rpcUrl: String,
    ): String = sessions.executeSend {
        val wallet = resolveSolanaWallet()
        val result = try {
            wallet.provider.signAndSendTransaction(transaction, cluster, rpcUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Rain SDK: Privy Solana signAndSendTransaction failed")
            PrivyErrorMapping.mapOrNull(e)?.let { throw it }
            throw e
        }
        result.getOrElse { error ->
            Timber.e(error, "Rain SDK: Privy Solana signAndSendTransaction failed")
            PrivyErrorMapping.mapOrNull(error)?.let { throw it }
            throw error
        }
    }

    /** One page of Solana history via Privy's indexer; error handling as in [getTransactions]. */
    suspend fun getSolanaTransactions(
        params: GetTransactionsParams<TransactionChain.Solana>,
    ): TransactionsPage = sessions.executeRead {
        resolveSolanaWallet().getTransactions(params).getOrElse { error ->
            Timber.e(error, "Rain SDK: Privy Solana getTransactions failed")
            PrivyErrorMapping.mapOrNull(error)?.let { throw it }
            throw error
        }
    }

    /**
     * Issues an RPC request through the wallet's provider, unwrapping the [Result] and data.
     *
     * Privy vendor exceptions classify into specific [RainError] cases here via
     * [PrivyErrorMapping] (invalid session, user rejection, insufficient funds, missing wallet).
     * Non-Privy failures bubble up raw (logged here with the operation, and once more by the
     * session coordinator when it maps them) rather than being wrapped: the session coordinator
     * maps them with [PrivyErrorMapping.map] before they leave the adapter, which
     * reads the node's prose first, so a user rejection or funds shortfall keeps its specific
     * code instead of hiding behind a generic `ProviderError`.
     */
    private suspend fun request(
        wallet: EmbeddedEthereumWallet,
        rpcRequest: EthereumRpcRequest,
    ): String {
        val result = try {
            wallet.provider.request(rpcRequest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Rain SDK: Privy RPC request failed for ${rpcRequest.method}")
            PrivyErrorMapping.mapOrNull(e)?.let { throw it }
            throw e
        }
        return result.getOrElse { error ->
            Timber.e(error, "Rain SDK: Privy RPC request failed for ${rpcRequest.method}")
            PrivyErrorMapping.mapOrNull(error)?.let { throw it }
            throw error
        }.data
    }
}
