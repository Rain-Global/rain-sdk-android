package com.rain.sdk.error

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigDecimal

/**
 * Pins the published `RAIN_*` error codes. These are a public contract host apps switch on,
 * so a failure here means the code changed — fix the code, not the test.
 */
class RainErrorCodeParityTest {

    @Test
    fun `error codes match the published contract`() {
        val expected = mapOf(
            RainErrorCode.SDK_NOT_INITIALIZED to "RAIN_101",
            RainErrorCode.INVALID_CONFIG to "RAIN_102",
            RainErrorCode.INVALID_RPC_URL to "RAIN_103",
            RainErrorCode.CHAIN_NOT_SUPPORTED to "RAIN_104",
            RainErrorCode.TOKEN_EXPIRED to "RAIN_201",
            RainErrorCode.UNAUTHORIZED to "RAIN_202",
            RainErrorCode.INVALID_LOGIN_CODE to "RAIN_203",
            RainErrorCode.NETWORK_ERROR to "RAIN_301",
            RainErrorCode.TRANSACTION_PENDING to "RAIN_302",
            RainErrorCode.TRANSACTION_OUTCOME_UNKNOWN to "RAIN_305",
            RainErrorCode.USER_REJECTED to "RAIN_401",
            RainErrorCode.INSUFFICIENT_FUNDS to "RAIN_402",
            RainErrorCode.TRANSACTION_SIMULATION_FAILED to "RAIN_403",
            RainErrorCode.WALLET_UNAVAILABLE to "RAIN_404",
            RainErrorCode.WITHDRAWAL_REVERTED_BY_NETWORK to "RAIN_405",
            RainErrorCode.INVALID_AMOUNT to "RAIN_406",
            RainErrorCode.WALLET_NOT_AUTHORIZED to "RAIN_407",
            RainErrorCode.PROVIDER_ERROR to "RAIN_501",
            RainErrorCode.INTERNAL_ERROR to "RAIN_502",
        )
        expected.forEach { (code, value) -> assertThat(code.code).isEqualTo(value) }
        assertThat(RainErrorCode.entries).hasSize(expected.size)
        // RAIN_105, RAIN_303 and RAIN_304 stay unassigned.
        assertThat(RainErrorCode.entries.map { it.code }).containsNoneOf("RAIN_105", "RAIN_303", "RAIN_304")
    }

    @Test
    fun `every error case maps to its published code`() {
        val underlying = RuntimeException("boom")
        // One instance per case; remapping any case to a different code fails here.
        val cases: List<Pair<RainError, String>> = listOf(
            RainError.SdkNotInitialized() to "RAIN_101",
            RainError.InvalidConfig("x") to "RAIN_102",
            RainError.ProviderNotRegistered("x") to "RAIN_102",
            RainError.InvalidRpcUrl("x") to "RAIN_103",
            RainError.ChainNotSupported(43114, "x") to "RAIN_104",
            RainError.TokenExpired() to "RAIN_201",
            RainError.Unauthorized("x") to "RAIN_202",
            RainError.InvalidLoginCode() to "RAIN_203",
            RainError.NetworkError(cause = underlying) to "RAIN_301",
            RainError.TransactionPending("status-id") to "RAIN_302",
            RainError.TransactionOutcomeUnknown("x") to "RAIN_305",
            RainError.UserRejected() to "RAIN_401",
            RainError.InsufficientFunds() to "RAIN_402",
            RainError.TransactionSimulationFailed(underlying) to "RAIN_403",
            RainError.WalletUnavailable() to "RAIN_404",
            RainError.WithdrawalRevertedByNetwork() to "RAIN_405",
            RainError.InvalidAmount("1.005", "too many decimals") to "RAIN_406",
            RainError.WalletNotAuthorized("0x1", "0x2") to "RAIN_407",
            // Token-transfer failures reuse existing codes on purpose — see RainError.
            RainError.InsufficientTokenBalance(BigDecimal("2"), BigDecimal.ONE, "mint") to "RAIN_402",
            RainError.TokenAccountNotFound("wallet", "mint") to "RAIN_402",
            RainError.TokenNotFound("mint", 103) to "RAIN_102",
            RainError.InvalidRecipient("addr", "because") to "RAIN_102",
            RainError.ProviderError(underlying) to "RAIN_501",
            RainError.InternalError("x") to "RAIN_502",
        )

        cases.forEach { (error, code) ->
            assertThat(error.errorCode.code).isEqualTo(code)
            assertThat(error.code).isEqualTo(code)
        }

        // A case added to the sealed hierarchy but not listed above fails here.
        assertThat(cases.map { it.first::class }.toSet())
            .isEqualTo(RainError::class.sealedSubclasses.toSet())
        assertThat(cases).hasSize(24)
    }
}
