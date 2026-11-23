package com.rain.sdk.internal.abi

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.rain.sdk.internal.network.chainreader.ERC20Selectors
import com.rain.sdk.internal.network.chainreader.Erc20Calldata
import com.rain.sdk.models.RainTokenAllowance
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Cross-platform goldens for the ERC-20 `approve` calldata sent by `approveTokenAllowance`.
 * The full hex is pinned byte-for-byte; any change here must land on both platforms.
 */
class Erc20ApproveCalldataGoldenTest {

    /** Rain's sandbox operator — the Auth Pull spender. */
    private val spender = "0x5a6E6b0d5Ea051CfFF9b3dcC2Aa8Dac226458f29"
    private val wallet = "0x1111111111111111111111111111111111111111"
    private val paddedSpender =
        "0000000000000000000000005a6e6b0d5ea051cfff9b3dcc2aa8dac226458f29"

    @Test
    fun `approve calldata is selector plus padded spender and amount`() {
        // 250 USDC at 6 decimals = 250_000_000 base units.
        val data = Erc20Abi.encodeApprove(spender, BigInteger.valueOf(250_000_000))

        assertThat(data).isEqualTo(
            "0x095ea7b3" +
                paddedSpender +
                "000000000000000000000000000000000000000000000000000000000ee6b280"
        )
    }

    @Test
    fun `an unlimited approval encodes uint256 max`() {
        val data = Erc20Abi.encodeApprove(spender, RainTokenAllowance.UNLIMITED_RAW_AMOUNT)

        assertThat(data).isEqualTo(
            "0x095ea7b3" + paddedSpender + "f".repeat(64)
        )
    }

    @Test
    fun `a revoke encodes an all-zero amount word`() {
        val data = Erc20Abi.encodeApprove(spender, BigInteger.ZERO)

        assertThat(data).isEqualTo(
            "0x095ea7b3" + paddedSpender + "0".repeat(64)
        )
    }

    @Test
    fun `decimal overload converts amount to base units before encoding`() {
        val fromDecimal = Erc20Abi.encodeApprove(spender, BigDecimal("1.5"), 6)
        val fromBaseUnits = Erc20Abi.encodeApprove(spender, BigInteger.valueOf(1_500_000))

        assertThat(fromDecimal).isEqualTo(fromBaseUnits)
    }

    @Test
    fun `decimal overload rejects an amount finer than the token`() {
        val ex = assertThrows(RainError.InvalidAmount::class.java) {
            Erc20Abi.encodeApprove(spender, BigDecimal("1.2345678"), 6)
        }

        assertThat(ex.errorCode.code).isEqualTo("RAIN_406")
    }

    @Test
    fun `an amount past uint256 max is rejected before encoding`() {
        // The ABI word would silently truncate it into a completely different allowance.
        val ex = assertThrows(RainError.InvalidAmount::class.java) {
            Erc20Abi.encodeApprove(
                spender,
                RainTokenAllowance.UNLIMITED_RAW_AMOUNT.add(BigInteger.ONE)
            )
        }

        assertThat(ex.errorCode.code).isEqualTo("RAIN_406")
    }

    @Test
    fun `a negative base-unit amount is rejected before encoding`() {
        val ex = assertThrows(RainError.InvalidAmount::class.java) {
            Erc20Abi.encodeApprove(spender, BigInteger.valueOf(-1))
        }

        assertThat(ex.errorCode.code).isEqualTo("RAIN_406")
    }

    /**
     * web3j's Address left-zero-pads a short hex string, so an unguarded truncated spender would
     * encode as a different-but-legal address. Erc20Abi is public for the adapter modules, so
     * the manager's own validation does not cover every caller.
     */
    @Test
    fun `a truncated spender is rejected instead of zero-padded`() {
        val ex = assertThrows(RainError.InvalidRecipient::class.java) {
            Erc20Abi.encodeApprove("0x5a6E6b0d", BigInteger.valueOf(250_000_000))
        }
        assertThat(ex.address).isEqualTo("0x5a6E6b0d")
    }

    @Test
    fun `an over-long spender is rejected as a typed error`() {
        assertThrows(RainError.InvalidRecipient::class.java) {
            Erc20Abi.encodeApprove(spender + "00", BigInteger.ONE)
        }
    }

    @Test
    fun `a non-hex spender is rejected`() {
        assertThrows(RainError.InvalidRecipient::class.java) {
            Erc20Abi.encodeApprove(spender.dropLast(1) + "z", BigInteger.ONE)
        }
    }

    @Test
    fun `the decimal overload applies the same spender guard`() {
        assertThrows(RainError.InvalidRecipient::class.java) {
            Erc20Abi.encodeApprove("0x5a6E6b0d", BigDecimal("1.5"), 6)
        }
    }

    @Test
    fun `unlimitedRawAmount is exactly uint256 max`() {
        assertThat(RainTokenAllowance.UNLIMITED_RAW_AMOUNT)
            .isEqualTo(BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE))
        assertThat(RainTokenAllowance.UNLIMITED_RAW_AMOUNT.toString()).isEqualTo(
            "115792089237316195423570985008687907853269984665640564039457584007913129639935"
        )
    }

    /** The approve selector is pinned above through the real encoder output; only this one is a literal. */
    @Test
    fun `the ERC-20 allowance selector is pinned`() {
        assertThat(ERC20Selectors.ALLOWANCE).isEqualTo("dd62ed3e")
    }

    @Test
    fun `allowance calldata is selector plus padded owner and spender`() {
        val data = Erc20Calldata.allowance(owner = wallet, spender = spender)

        assertThat(data).isEqualTo(
            "0xdd62ed3e" +
                "0000000000000000000000001111111111111111111111111111111111111111" +
                paddedSpender
        )
    }
}
