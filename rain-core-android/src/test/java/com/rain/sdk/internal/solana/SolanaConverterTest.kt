package com.rain.sdk.internal.solana

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class SolanaConverterTest {

    @Test
    fun `solToLamports scales by 1e9`() {
        assertThat(SolanaConverter.solToLamports(BigDecimal("1"))).isEqualTo(1_000_000_000L)
        assertThat(SolanaConverter.solToLamports(BigDecimal("0.5"))).isEqualTo(500_000_000L)
        assertThat(SolanaConverter.solToLamports(BigDecimal("2.5"))).isEqualTo(2_500_000_000L)
        assertThat(SolanaConverter.solToLamports(BigDecimal.ZERO)).isEqualTo(0L)
    }

    @Test
    fun `solToLamports is exact for the smallest unit and beyond double precision`() {
        assertThat(SolanaConverter.solToLamports(BigDecimal("0.000000001"))).isEqualTo(1L)
        assertThat(SolanaConverter.solToLamports(BigDecimal("0.1"))).isEqualTo(100_000_000L)
        // More significant digits than a Double can carry.
        assertThat(SolanaConverter.solToLamports(BigDecimal("100000000.000000001")))
            .isEqualTo(100_000_000_000_000_001L)
    }

    @Test
    fun `solToLamports rejects sub-lamport precision instead of truncating`() {
        assertThrows(ArithmeticException::class.java) {
            SolanaConverter.solToLamports(BigDecimal("0.0000000015"))
        }
    }

    @Test
    fun `solToLamports rejects negative amounts`() {
        assertThrows(IllegalArgumentException::class.java) {
            SolanaConverter.solToLamports(BigDecimal("-1"))
        }
    }

    @Test
    fun `lamportsToSol divides by 1e9`() {
        assertThat(SolanaConverter.lamportsToSol(BigInteger.valueOf(2_500_000_000L)).toPlainString())
            .isEqualTo("2.500000000")
        assertThat(SolanaConverter.lamportsToSol(BigInteger.valueOf(1L)).toPlainString())
            .isEqualTo("0.000000001")
    }
}
