package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.WalletChain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TransactionHistoryViewModel(
    private val rainClient: RainClient
) : ViewModel() {

    private val _state = MutableStateFlow(TransactionHistoryUiState())
    val state: StateFlow<TransactionHistoryUiState> = _state.asStateFlow()

    fun fetchTransactions(chain: WalletChain = WalletChain.EVM) {
        SampleLog.i("History.fetch", "fetching transactions chain=${chain.displayName} limit=20 order=DESC")
        // Clear the previous chain's list so switching chains doesn't show stale rows.
        _state.update { it.copy(isLoading = true, errorText = null, transactions = emptyList()) }

        viewModelScope.launch {
            try {
                val address = try {
                    rainClient.getWalletAddress(chain.chainId)
                } catch (e: Exception) {
                    SampleLog.w("History.fetch", "getAddress failed (continuing): ${e.message}", e)
                    null
                }

                val result = rainClient.getTransactions(
                    chainId = chain.chainId,
                    limit = 20,
                    order = RainTransactionOrder.DESC
                )
                SampleLog.i("History.fetch", "success — count=${result.size}")
                result.forEach { tx ->
                    SampleLog.d(
                        "History.fetch",
                        "tx hash=${tx.hash} from=${tx.from} to=${tx.to} value=${tx.value} asset=${tx.asset} " +
                            "block=${tx.blockNumber} time=${tx.timestamp} meta=${tx.metadata}"
                    )
                }
                _state.update {
                    it.copy(
                        transactions = result,
                        walletAddress = address,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("History.fetch", "failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorText = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }
}

data class TransactionHistoryUiState(
    val transactions: List<RainTransaction> = emptyList(),
    val walletAddress: String? = null,
    val isLoading: Boolean = false,
    val errorText: String? = null
)

class TransactionHistoryViewModelFactory(
    private val rainClient: RainClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TransactionHistoryViewModel::class.java)) {
            return TransactionHistoryViewModel(rainClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
