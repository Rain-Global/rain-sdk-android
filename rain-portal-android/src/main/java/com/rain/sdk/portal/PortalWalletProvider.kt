package com.rain.sdk.portal

import com.rain.sdk.RainChain
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.abi.Erc20Abi
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.WalletProvider
import com.rain.sdk.utils.EthereumConverter
import java.math.BigDecimal

/**
 * WalletProvider implementation using Portal SDK.
 *
 * Balance reads build rich [Balance] values via [PortalManager], resolving token metadata
 * (decimals / symbol / name) through the shared [tokenStore]. Every call runs through [sessions].
 */
internal class PortalWalletProvider(
    private val portalManager: PortalManager,
    private val tokenStore: TokenMetadataStore,
    private val sessions: PortalSessionCoordinator = PortalSessionCoordinator(),
) : WalletProvider {

    override val id: ProviderId get() = ProviderId.PORTAL

    /** Portal is an EVM MPC signer: it exports/recovers via MPC backups, single EVM chain family. */
    override val capabilities: Set<Capability> get() = setOf(Capability.EXPORT, Capability.RECOVERY)

    /** Portal signs eip155 chains only; a Solana chain ID must never reach its EVM pipeline. */
    private fun requireEvmChain(chainId: Int) {
        if (RainChain.isSolana(chainId)) {
            throw RainError.InvalidConfig(
                "Provider '${id.value}' does not support Solana (chainId $chainId is a Solana chain)"
            )
        }
    }

    override suspend fun getWalletAddress(): String = sessions.executeRead {
        portalManager.getAddress()
    }

    override suspend fun sendNativeToken(
        chainId: Int,
        toAddress: String,
        amountInEth: BigDecimal
    ): String {
        requireEvmChain(chainId)
        val fromAddress = getWalletAddress()
        val decimals = tokenStore.nativeCurrency(chainId).decimals
        val valueWeiHex = EthereumConverter.convertEthToWeiHex(amountInEth, decimals)

        // For native transfers, data is "0x"
        return sessions.executeWrite {
            portalManager.sendTransaction(
                chainId = chainId,
                from = fromAddress,
                to = toAddress,
                data = "0x",
                value = valueWeiHex
            )
        }
    }

    override suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        toAddress: String,
        amount: BigDecimal,
        decimals: Int
    ): String {
        requireEvmChain(chainId)
        val fromAddress = getWalletAddress()
        val data = Erc20Abi.encodeTransfer(toAddress, amount, decimals)

        // For ERC-20 transfers, the "to" is the contract address and value is 0x0
        return sessions.executeWrite {
            portalManager.sendTransaction(
                chainId = chainId,
                from = fromAddress,
                to = contractAddress,
                data = data,
                value = "0x0"
            )
        }
    }

    override suspend fun getBalance(chainId: Int, token: Token): Balance = sessions.executeRead {
        portalManager.getBalance(chainId, token, tokenStore)
    }

    override suspend fun getBalances(chainId: Int): List<Balance> = sessions.executeRead {
        portalManager.getBalances(chainId, tokenStore)
    }

    override suspend fun getTransactions(
        chainId: Int,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?
    ): List<RainTransaction> = sessions.executeRead {
        portalManager.getTransactions(chainId, tokenStore, limit, offset, order)
    }

    override suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String
    ): String {
        requireEvmChain(chainId)
        return sessions.executeWrite {
            portalManager.signTypedData(chainId, walletAddress, typedDataJson)
        }
    }

    override suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): String {
        requireEvmChain(chainId)
        return sessions.executeWrite {
            portalManager.sendTransaction(chainId, from, to, data, value)
        }
    }

    override suspend fun estimateTransactionFee(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String
    ): BigDecimal {
        requireEvmChain(chainId)
        return sessions.executeRead {
            portalManager.estimateTransactionFee(chainId, from, to, data, value)
        }
    }
}
