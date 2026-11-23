package com.rain.sdk.internal.utils

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class RainAmountUtilsTest {

    @Test
    fun `toBaseUnits scales by token decimals`() {
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal("1.5"), 6))
            .isEqualTo(BigInteger("1500000"))
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal("0.000001"), 6))
            .isEqualTo(BigInteger.ONE)
    }

    @Test
    fun `toBaseUnits rejects negative amounts before they can reach uint256 encoding`() {
        val ex = assertThrows(RainError.InvalidAmount::class.java) {
            RainAmountUtils.toBaseUnits(BigDecimal("-1"), 6)
        }
        assertThat(ex.amount).isEqualTo("-1")
        assertThat(ex.message).contains("negative")
    }

    @Test
    fun `toBaseUnits throws InvalidAmount for over-precision amounts`() {
        // 7 decimal places on a 6-decimal token: more precision than the token can represent.
        val ex = assertThrows(RainError.InvalidAmount::class.java) {
            RainAmountUtils.toBaseUnits(BigDecimal("1.2345678"), 6)
        }
        assertThat(ex.amount).isEqualTo("1.2345678")
        assertThat(ex.message).contains("fractional base units")
    }

    @Test
    fun `toBaseUnits converts zero to zero base units`() {
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal.ZERO, 18)).isEqualTo(BigInteger.ZERO)
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal("0.000000"), 6)).isEqualTo(BigInteger.ZERO)
    }

    @Test
    fun `toBaseUnits accepts a scale exactly at the token's decimals`() {
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal("0.123456"), 6))
            .isEqualTo(BigInteger("123456"))
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal("0.000000000000000001"), 18))
            .isEqualTo(BigInteger.ONE)
    }

    @Test
    fun `toBaseUnits stays exact where a double multiply would truncate`() {
        // 16.38 * 1e6 in binary floating point lands just below 16_380_000.
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal("16.38"), 6))
            .isEqualTo(BigInteger("16380000"))
    }

    @Test
    fun `toBaseUnits stays exact beyond 2^53`() {
        // The result needs more integer precision than a double can hold.
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal("1.123456789012345678"), 18))
            .isEqualTo(BigInteger("1123456789012345678"))
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal("0.00019472"), 18))
            .isEqualTo(BigInteger("194720000000000"))
    }

    @Test
    fun `toBaseUnits accepts exponent notation with a negative scale`() {
        // "1E+2" carries scale -2, which passes the precision check and scales exactly.
        val amount = BigDecimal("1E+2")
        assertThat(amount.scale()).isEqualTo(-2)
        assertThat(RainAmountUtils.toBaseUnits(amount, 6)).isEqualTo(BigInteger("100000000"))
    }

    @Test
    fun `toBaseUnits handles very large token decimals`() {
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal.ONE, 36))
            .isEqualTo(BigInteger.TEN.pow(36))
    }

    @Test
    fun `out-of-range decimals throw instead of blowing up the scaling math`() {
        // `decimals` can come from a contract's own `decimals()` read, so an absurd value must
        // surface as a typed error rather than a raw ArithmeticException or a monstrous alloc.
        assertThrows(RainError.InvalidAmount::class.java) {
            RainAmountUtils.toBaseUnits(BigDecimal.ONE, 40_000)
        }
        assertThrows(RainError.InvalidAmount::class.java) {
            RainAmountUtils.toBaseUnits(BigDecimal.ONE, -1)
        }
        // The bounds themselves stay usable.
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal.ONE, 0)).isEqualTo(BigInteger.ONE)
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal.ZERO, 77)).isEqualTo(BigInteger.ZERO)
    }

    @Test
    fun `toBaseUnits with a zero-decimal token passes whole amounts and rejects fractions`() {
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal("5"), 0)).isEqualTo(BigInteger("5"))
        val ex = assertThrows(RainError.InvalidAmount::class.java) {
            RainAmountUtils.toBaseUnits(BigDecimal("5.5"), 0)
        }
        assertThat(ex.amount).isEqualTo("5.5")
    }

    @Test
    fun `representable trailing zeros are accepted`() {
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal("250.0000000"), 6))
            .isEqualTo(BigInteger("250000000"))
        assertThat(RainAmountUtils.toBaseUnits(BigDecimal("0.0000000"), 6))
            .isEqualTo(BigInteger.ZERO)
    }

    @Test
    fun `amount above uint256 max is rejected`() {
        val tooLarge = BigInteger.valueOf(2).pow(256).toBigDecimal()
        assertThrows(RainError.InvalidAmount::class.java) {
            RainAmountUtils.toBaseUnits(tooLarge, 0)
        }
    }
}
