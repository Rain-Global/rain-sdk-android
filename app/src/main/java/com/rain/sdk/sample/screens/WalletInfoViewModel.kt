package com.rain.sdk.sample.screens

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.CollateralContract
import com.rain.sdk.sample.CollateralContractMatch
import com.rain.sdk.sample.RainSession
import com.rain.sdk.sample.SampleLog
import com.rain.sdk.sample.WalletChain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WalletInfoViewModel(
    private val session: RainSession,
    private val rainClient: RainClient
) : ViewModel() {

    private val _state = MutableStateFlow(WalletInfoUiState())
    val state: StateFlow<WalletInfoUiState> = _state.asStateFlow()

    /** The load in flight, so a second call (a chain switch) supersedes the first instead of racing it. */
    private var loadJob: Job? = null

    fun fetchWalletInfo(chain: WalletChain = WalletChain.BASE_SEPOLIA) {
        SampleLog.i("WalletInfo", "fetching wallet info chain=${chain.displayName}")
        _state.update {
            it.copy(
                isLoading = true,
                errorText = null,
                // Clear stale data when switching chains so the previous wallet doesn't linger.
                portalAddress = "",
                portalQrBitmap = null,
                collateralAddress = "",
                collateralQrBitmap = null
            )
        }

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val walletAddress = rainClient.getWalletAddress(chain.chainId)
                SampleLog.d("WalletInfo", "wallet address=$walletAddress")
                val walletQr = rainClient.generateAddressQRCode(walletAddress)

                _state.update {
                    it.copy(
                        portalAddress = walletAddress,
                        portalQrBitmap = walletQr
                    )
                }

                // From the demo's own Rain API client; a host makes this call from its backend.
                // The card reads only addresses, so it takes the raw contracts rather than
                // RainSession.fetchCollateralContract and its token-metadata reads.
                val contracts = session.requireRainApi().fetchCollateralContracts()
                when (val card = depositCard(chain, contracts)) {
                    is DepositCard.NoContract -> showNoContract(chain, card)
                    is DepositCard.Deposit -> showDeposit(chain, card)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                SampleLog.e("WalletInfo", "failed: ${e.message}", e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorText = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    private fun showNoContract(chain: WalletChain, card: DepositCard.NoContract) {
        SampleLog.w(
            "WalletInfo",
            "no collateral contract for ${chain.displayName} (collateral chainIds=${card.collateralChainIds})"
        )
        _state.update {
            it.copy(isLoading = false, errorText = noContractMessage(chain, card.collateralChainIds))
        }
    }

    private suspend fun showDeposit(chain: WalletChain, card: DepositCard.Deposit) {
        SampleLog.d("WalletInfo", "collateral address=${card.address} (chainId=${chain.chainId})")
        val collateralQr = rainClient.generateAddressQRCode(card.address)

        SampleLog.i("WalletInfo", "success")
        _state.update {
            it.copy(collateralAddress = card.address, collateralQrBitmap = collateralQr, isLoading = false)
        }
    }
}

/** What the Wallet & QR deposit card shows for a chain, from the user's collateral contracts. */
internal sealed interface DepositCard {
    /** Rain provisioned a contract on the selected chain; [address] is where a deposit on that chain goes. */
    data class Deposit(val address: String) : DepositCard

    /**
     * No contract on the selected chain. [collateralChainIds] are the chains of this account's
     * contracts in the same family, every one of them, so the card can say where the collateral is
     * instead. Rain lists contracts in no fixed order, so naming only the first would change the
     * message between launches and could hide a chain the picker offers behind one it does not.
     */
    data class NoContract(val collateralChainIds: List<Int>) : DepositCard
}

/**
 * The deposit card for [chain]: the selected chain's contract only ([CollateralContractMatch.EXACT_CHAIN]),
 * with the family siblings listed only to name where the collateral is. Deposits go to the deposit
 * address when Rain sends one, on EVM as on Solana, otherwise to the proxy.
 */
internal fun depositCard(chain: WalletChain, contracts: List<CollateralContract>): DepositCard {
    val exact = chain.collateralContract(contracts, CollateralContractMatch.EXACT_CHAIN)
    if (exact != null) return DepositCard.Deposit(exact.depositAddress ?: exact.proxyAddress)
    val siblings = contracts.filter { chain.ownsCollateralContract(it.chainId) }.map { it.chainId }.distinct()
    return DepositCard.NoContract(siblings)
}

/**
 * The card's message for [DepositCard.NoContract]: the plain "no contract" line, plus where this
 * account's collateral is when sibling chains have it. Chains in [offered] come first, in picker
 * order, then the rest by chain id, so the text does not depend on the API's order; the hint names
 * the offered ones, because only those can be switched to.
 */
internal fun noContractMessage(
    chain: WalletChain,
    collateralChainIds: List<Int>,
    offered: List<WalletChain> = WalletChain.selectable,
): String {
    val none = "No collateral contract on ${chain.displayName}"
    if (collateralChainIds.isEmpty()) return none
    val offeredIds = offered.map { it.chainId }.filter { it in collateralChainIds }
    val otherIds = collateralChainIds.filter { it !in offeredIds }.sorted()
    val where = (offeredIds + otherIds).map(WalletChain::chainLabel).joinNatural()
    return when {
        offeredIds.isEmpty() -> "$none. This account's collateral is on $where, which this app does not offer."
        otherIds.isEmpty() && offeredIds.size == 1 -> "$none. This account's collateral is on $where; switch to it to deposit."
        else -> "$none. This account's collateral is on $where; switch to ${offeredIds.map(WalletChain::chainLabel).joinNatural()} to deposit."
    }
}

/** "A", "A and B", "A, B and C". */
private fun List<String>.joinNatural(): String = when (size) {
    0 -> ""
    1 -> first()
    else -> dropLast(1).joinToString(", ") + " and " + last()
}

data class WalletInfoUiState(
    val portalAddress: String = "",
    val portalQrBitmap: Bitmap? = null,
    val collateralAddress: String = "",
    val collateralQrBitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val errorText: String? = null
)

class WalletInfoViewModelFactory(
    private val session: RainSession,
    private val rainClient: RainClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WalletInfoViewModel::class.java)) {
            return WalletInfoViewModel(session, rainClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
