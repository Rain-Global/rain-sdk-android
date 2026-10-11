package com.rain.sdk.sample.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rain.sdk.error.RainError
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.sample.SampleEnvironment
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

/**
 * Drives the Auth Pull prerequisite: approve Rain's operator to spend USDC from this wallet, and
 * inspect the allowance before and after.
 *
 * The pull itself is Rain's — once the allowance is in place, an authorization moves the funds
 * with no further app involvement.
 */
class AuthPullViewModel(
    private val rainClient: RainClient
) : ViewModel() {

    private val _state = MutableStateFlow(AuthPullUiState())
    val state: StateFlow<AuthPullUiState> = _state.asStateFlow()

    private var seededChain: WalletChain? = null
    private var readJob: Job? = null
    private var estimateJob: Job? = null
    private var approvalJob: Job? = null
    private var generation: Long = 0

    /**
     * Owns the in-flight allowance read, separately from [generation]: an allowance read depends
     * on the chain and wallet, not the amount fields, so a form edit (which bumps [generation])
     * must not orphan its result — gating on [generation] stranded [AuthPullUiState.isLoadingAllowance]
     * true forever. Only a newer read, a chain switch, or an approval may supersede a read.
     */
    private var readRun: Long = 0

    /**
     * Whether the built SDK will accept an approval on [chain].
     *
     * Read off the client rather than from [WalletChain.supportsAuthPull], which answers for the
     * environment: the picker has to answer before an SDK exists, but every screen holding a live
     * client should gate on what that client actually enforces.
     */
    fun supportsAuthPull(chain: WalletChain): Boolean =
        chain.chainId in rainClient.authPullChainIds

    fun onAmountChanged(value: String) {
        generation += 1
        estimateJob?.cancel()
        _state.update { it.copy(amount = value, estimatedFee = null, errorText = null) }
    }

    fun onUnlimitedChanged(value: Boolean) {
        generation += 1
        estimateJob?.cancel()
        _state.update { it.copy(isUnlimited = value, estimatedFee = null, errorText = null) }
    }

    /**
     * Seeds the form for [chain] and reads the current allowance. Token and operator are
     * environment-specific, so switching chains re-fills them. No-op when already seeded.
     */
    fun onChainChanged(chain: WalletChain) {
        if (chain == seededChain) return
        seededChain = chain
        generation += 1
        // Retire any in-flight read now: if the new chain does not support Auth Pull no fresh
        // read will start, and the old chain's answer must not land on this chain's blank card.
        readRun += 1
        readJob?.cancel()
        estimateJob?.cancel()
        approvalJob?.cancel()
        _state.update {
            it.copy(
                operatorAddress = chain.defaultAuthPullOperator,
                tokenAddress = chain.defaultTokenAddress,
                allowanceText = null,
                isUnlimitedAllowance = false,
                isRevokedAllowance = false,
                estimatedFee = null,
                txHash = null,
                approvalStatus = null,
                errorText = null,
                isApproving = false,
                // The cancelled read rethrows before clearing this, so reset it here or the
                // spinner sticks.
                isLoadingAllowance = false
            )
        }
        if (supportsAuthPull(chain)) refreshAllowance(chain)
    }

    /**
     * Reads `allowance(wallet, operator)` on chain. Cheap and free — call it on entry, and again
     * after an approval is mined.
     */
    fun refreshAllowance(chain: WalletChain) {
        val current = _state.value
        if (!validate(chain, current)) return

        _state.update { it.copy(isLoadingAllowance = true, errorText = null) }

        readJob?.cancel()
        readRun += 1
        val requestRun = readRun
        readJob = viewModelScope.launch {
            try {
                val allowance = rainClient.getTokenAllowance(
                    chainId = chain.chainId,
                    contractAddress = current.tokenAddress,
                    spender = current.operatorAddress
                )
                SampleLog.i(
                    "AuthPull.allowance",
                    "raw=${allowance.rawAmount} decimals=${allowance.decimals} " +
                        "unlimited=${allowance.isUnlimited}"
                )
                if (requestRun != readRun) return@launch
                _state.update {
                    it.copy(
                        isLoadingAllowance = false,
                        isUnlimitedAllowance = allowance.isUnlimited,
                        isRevokedAllowance = allowance.isZero,
                        // An unlimited allowance formats to ~1.16e71 USDC, which is noise —
                        // label it instead.
                        allowanceText =
                        if (allowance.isUnlimited) "Unlimited" else allowance.formatted
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SampleLog.e("AuthPull.allowance", "failed: ${e.message}", e)
                if (requestRun != readRun) return@launch
                _state.update {
                    it.copy(
                        isLoadingAllowance = false,
                        allowanceText = null,
                        isUnlimitedAllowance = false,
                        isRevokedAllowance = false,
                        errorText = "Could not read allowance: ${e.message}"
                    )
                }
            }
        }
    }

    /** Prices the approval without broadcasting it or prompting for a signature. */
    fun estimateFee(chain: WalletChain) {
        val current = _state.value
        if (!validate(chain, current)) return
        val amount = when (val selection = resolvedAmount(current)) {
            AmountSelection.Invalid -> return
            AmountSelection.Unlimited -> null
            is AmountSelection.Exact -> selection.value
        }

        _state.update { it.copy(errorText = null, estimatedFee = null) }

        estimateJob?.cancel()
        val requestGeneration = generation
        estimateJob = viewModelScope.launch {
            try {
                val fee = rainClient.estimateApprovalFee(
                    chainId = chain.chainId,
                    contractAddress = current.tokenAddress,
                    spender = current.operatorAddress,
                    amount = amount
                )
                // Plain notation: a fee is small enough that BigDecimal's toString would render it
                // as 2.15172E-7.
                val display = "${fee.stripTrailingZeros().toPlainString()} ${chain.nativeSymbol}"
                SampleLog.i("AuthPull.fee", "estimate=$display")
                if (requestGeneration == generation) {
                    _state.update { it.copy(estimatedFee = display) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SampleLog.e("AuthPull.fee", "failed: ${e.message}", e)
                if (requestGeneration == generation) {
                    _state.update { it.copy(errorText = "Fee estimate failed: ${e.message}") }
                }
            }
        }
    }

    /**
     * Submits the approval, then re-reads the allowance so the screen reflects what actually
     * landed rather than what was requested.
     */
    fun approve(chain: WalletChain) {
        val current = _state.value
        if (!validate(chain, current)) return
        val amount = when (val selection = resolvedAmount(current)) {
            AmountSelection.Invalid -> return
            AmountSelection.Unlimited -> null
            is AmountSelection.Exact -> selection.value
        }

        SampleLog.i(
            "AuthPull.approve",
            "token=${current.tokenAddress} spender=${current.operatorAddress} " +
                "amount=${amount?.toPlainString() ?: "unlimited"}"
        )
        generation += 1
        // An approval supersedes any in-flight read; the confirmed allowance is published below.
        // The superseded read no longer owns the spinner, so clear it here or it never clears.
        readRun += 1
        readJob?.cancel()
        estimateJob?.cancel()
        approvalJob?.cancel()
        val requestGeneration = generation
        _state.update {
            it.copy(
                isApproving = true,
                isLoadingAllowance = false,
                errorText = null,
                txHash = null,
                approvalStatus = "Submitting approval"
            )
        }

        approvalJob = viewModelScope.launch {
            try {
                val result = rainClient.approveTokenAllowance(
                    chainId = chain.chainId,
                    contractAddress = current.tokenAddress,
                    spender = current.operatorAddress,
                    amount = amount
                )
                SampleLog.i("AuthPull.approve", "success txHash=${result.transactionHash}")
                if (requestGeneration != generation) return@launch
                _state.update {
                    it.copy(
                        txHash = result.transactionHash,
                        approvalStatus = "Pending network confirmation"
                    )
                }
                val confirmed = rainClient.confirmTokenAllowance(
                    transactionHash = result.transactionHash,
                    chainId = chain.chainId,
                    contractAddress = current.tokenAddress,
                    spender = current.operatorAddress,
                    amount = amount
                )
                if (requestGeneration != generation) return@launch
                _state.update {
                    it.copy(
                        isApproving = false,
                        approvalStatus = "Confirmed onchain",
                        allowanceText = if (confirmed.isUnlimited) {
                            "Unlimited"
                        } else {
                            confirmed.formatted
                        },
                        isUnlimitedAllowance = confirmed.isUnlimited,
                        isRevokedAllowance = confirmed.isZero
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RainError.TransactionPending) {
                // Not a failure: the approval is out and may still mine. Keep the hash (statusId
                // when approve itself timed out) and point at the allowance read — re-approving
                // here would double-spend gas.
                SampleLog.w("AuthPull.approve", "not confirmed within the window txHash=${e.statusId}")
                if (requestGeneration != generation) return@launch
                _state.update {
                    it.copy(
                        isApproving = false,
                        txHash = it.txHash ?: e.statusId,
                        approvalStatus = "Submitted, not yet confirmed",
                        errorText = "Not confirmed yet. Tap Refresh to re-read the allowance; do not re-approve."
                    )
                }
            } catch (e: RainError.TransactionOutcomeUnknown) {
                // Not a settled failure either: the backend may have accepted the approval before its
                // answer was lost, and there is no hash or status id to resume from. The allowance read
                // says whether it landed; re-approving here would double-spend gas or set it twice.
                SampleLog.w("AuthPull.approve", "outcome unknown: ${e.message}")
                if (requestGeneration != generation) return@launch
                _state.update {
                    it.copy(
                        isApproving = false,
                        approvalStatus = "Submitted, outcome unknown",
                        errorText = "The approval may still land. Tap Refresh to re-read the allowance; do not re-approve."
                    )
                }
            } catch (e: Exception) {
                SampleLog.e("AuthPull.approve", "failed: ${e.message}", e)
                if (requestGeneration != generation) return@launch
                _state.update {
                    it.copy(
                        isApproving = false,
                        approvalStatus = "Approval not confirmed",
                        errorText = "Approval failed or was not confirmed: ${e.message}"
                    )
                }
            }
        }
    }

    /** Revokes by approving zero — the same call, with an explicit zero amount. */
    fun revoke(chain: WalletChain) {
        _state.update { it.copy(isUnlimited = false, amount = "0") }
        approve(chain)
    }

    // ---- helpers ----------------------------------------------------------------

    /**
     * What to approve. Kotlin has no nested nullability — `BigDecimal??` collapses to
     * `BigDecimal?` — so "unlimited" and "invalid input" need distinct cases rather than two
     * flavours of null.
     */
    private sealed interface AmountSelection {
        object Invalid : AmountSelection
        object Unlimited : AmountSelection
        data class Exact(val value: BigDecimal) : AmountSelection
    }

    private fun resolvedAmount(current: AuthPullUiState): AmountSelection {
        if (current.isUnlimited) return AmountSelection.Unlimited
        val parsed = runCatching { BigDecimal(current.amount) }.getOrNull()
        if (parsed == null || parsed.signum() < 0) {
            _state.update { it.copy(errorText = "Invalid amount") }
            return AmountSelection.Invalid
        }
        return AmountSelection.Exact(parsed)
    }

    private fun validate(chain: WalletChain, current: AuthPullUiState): Boolean {
        if (!supportsAuthPull(chain)) {
            _state.update { it.copy(errorText = "Auth Pull is not available on ${chain.displayName}") }
            return false
        }
        if (!chain.isValidAddress(current.tokenAddress)) {
            _state.update {
                it.copy(errorText = "Token address is not a valid ${chain.displayName} address")
            }
            return false
        }
        if (!chain.isValidAddress(current.operatorAddress)) {
            _state.update {
                it.copy(errorText = "Operator address is not a valid ${chain.displayName} address")
            }
            return false
        }
        return true
    }
}

/** Form and result state for the Auth Pull screen. */
data class AuthPullUiState(
    val operatorAddress: String = SampleEnvironment.authPullOperator,
    val tokenAddress: String = WalletChain.firstAuthPullChain?.defaultTokenAddress ?: "",
    val amount: String = "250",
    val isUnlimited: Boolean = false,
    val allowanceText: String? = null,
    val isUnlimitedAllowance: Boolean = false,
    /** Nothing is approved: either never approved, or revoked. */
    val isRevokedAllowance: Boolean = false,
    val isLoadingAllowance: Boolean = false,
    val isApproving: Boolean = false,
    val estimatedFee: String? = null,
    val txHash: String? = null,
    val approvalStatus: String? = null,
    val errorText: String? = null
)

class AuthPullViewModelFactory(
    private val rainClient: RainClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AuthPullViewModel::class.java)) {
            return AuthPullViewModel(rainClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
