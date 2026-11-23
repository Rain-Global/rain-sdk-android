package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.WalletChain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

class SendTokensViewModel(
    private val rainClient: RainClient
) : ViewModel() {
    private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

    private val _state = MutableStateFlow(SendTokensUiState())
    val state: StateFlow<SendTokensUiState> = _state.asStateFlow()

    private var seededChain: WalletChain? = null

    /** The in-flight send, if any, so a chain switch can cancel it. */
    private var sendJob: Job? = null

    fun onRecipientChanged(value: String) {
        _state.update { it.copy(recipientAddress = value) }
    }

    fun onAmountChanged(value: String) {
        _state.update { it.copy(amount = value) }
    }

    fun onContractAddressChanged(value: String) {
        _state.update { it.copy(contractAddress = value) }
    }

    fun onSendModeChanged(isToken: Boolean) {
        _state.update { it.copy(isTokenMode = isToken, txHash = null, errorText = null) }
    }

    /**
     * Seeds the form for [chain], cancelling any in-flight send from the previous chain.
     * The token address and recipient are chain-specific, so switching chains re-fills them.
     * No-op when [chain] is already seeded, so callers may invoke this on every composition.
     */
    fun onChainChanged(chain: WalletChain) {
        if (chain == seededChain) return
        seededChain = chain
        sendJob?.cancel()
        _state.update {
            it.copy(
                contractAddress = chain.defaultTokenAddress,
                recipientAddress = chain.defaultRecipient,
                isSending = false,
                txHash = null,
                errorText = null
            )
        }
    }

    fun sendNative(chain: WalletChain = WalletChain.BASE_SEPOLIA) {
        val current = _state.value
        val amount = current.amount.toBigDecimalOrNull()
        if (amount == null || amount <= BigDecimal.ZERO) {
            _state.update { it.copy(errorText = "Invalid amount") }
            return
        }
        if (current.recipientAddress.isBlank()) {
            _state.update { it.copy(errorText = "Recipient address is required") }
            return
        }
        if (!chain.isValidAddress(current.recipientAddress)) {
            _state.update { it.copy(errorText = "Recipient is not a valid ${chain.nativeSymbol} address") }
            return
        }

        SampleLog.i("Send.native", "to=${current.recipientAddress} amount=$amount ${chain.nativeSymbol}")
        _state.update { it.copy(isSending = true, errorText = null, txHash = null) }

        sendJob = viewModelScope.launch {
            try {
                val result = rainClient.sendNative(
                    chainId = chain.chainId,
                    to = current.recipientAddress,
                    amount = amount
                )
                SampleLog.i("Send.native", "success txHash=${result.transactionHash}")
                _state.update {
                    it.copy(
                        isSending = false,
                        txHash = result.transactionHash
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SampleLog.e("Send.native", "failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        isSending = false,
                        errorText = "Send failed: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Sends a fungible token: an ERC-20 on EVM chains, an SPL token on Solana. Both go through
     * the same [RainClient.sendToken] call — the SDK routes on the chain id, resolves the token's
     * decimals itself, and on Solana creates the recipient's token account if they lack one.
     */
    fun sendTokenTransfer(chain: WalletChain = WalletChain.BASE_SEPOLIA) {
        val current = _state.value
        val logTag = if (chain.isSolana) "Send.spl" else "Send.erc20"
        val amount = current.amount.toBigDecimalOrNull()
        if (amount == null || amount <= BigDecimal.ZERO) {
            _state.update { it.copy(errorText = "Invalid amount") }
            return
        }
        if (current.contractAddress.isBlank()) {
            _state.update { it.copy(errorText = "${chain.tokenAddressLabel} is required") }
            return
        }
        if (!chain.isValidAddress(current.contractAddress)) {
            _state.update {
                it.copy(errorText = "${chain.tokenAddressLabel} is not a valid ${chain.displayName} address")
            }
            return
        }
        if (current.recipientAddress.isBlank()) {
            _state.update { it.copy(errorText = "Recipient address is required") }
            return
        }
        if (!chain.isValidAddress(current.recipientAddress)) {
            _state.update {
                it.copy(errorText = "Recipient is not a valid ${chain.displayName} address")
            }
            return
        }

        SampleLog.i(
            logTag,
            "token=${current.contractAddress} to=${current.recipientAddress} amount=$amount (decimals resolved by SDK)"
        )
        _state.update { it.copy(isSending = true, errorText = null, txHash = null) }

        sendJob = viewModelScope.launch {
            try {
                // Decimals are resolved by the SDK — from the token registry or an on-chain
                // `decimals()` read on EVM, and from the mint account on Solana.
                val result = rainClient.sendToken(
                    chainId = chain.chainId,
                    contractAddress = current.contractAddress,
                    to = current.recipientAddress,
                    amount = amount
                )
                SampleLog.i(logTag, "success txHash=${result.transactionHash}")
                _state.update {
                    it.copy(
                        isSending = false,
                        txHash = result.transactionHash
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SampleLog.e(logTag, "failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        isSending = false,
                        errorText = "Send failed: ${e.message}"
                    )
                }
            }
        }
    }
}

/**
 * Form state for the send screen. The address defaults are seeded per chain by [onChainChanged]
 * (see [WalletChain.defaultTokenAddress] / [WalletChain.defaultRecipient]), so they no longer
 * have to be swapped by hand when switching networks.
 */
data class SendTokensUiState(
    val isTokenMode: Boolean = false,
    val recipientAddress: String = WalletChain.BASE_SEPOLIA.defaultRecipient,
    val amount: String = "0.001",
    val contractAddress: String = WalletChain.BASE_SEPOLIA.defaultTokenAddress,
    val isSending: Boolean = false,
    val txHash: String? = null,
    val errorText: String? = null
)

class SendTokensViewModelFactory(
    private val rainClient: RainClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SendTokensViewModel::class.java)) {
            return SendTokensViewModel(rainClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
