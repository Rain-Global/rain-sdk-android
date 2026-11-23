package com.rain.sdk.internal.abi

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Byte-level tests for the shared ERC-20 calldata encoder. All three wallet adapters
 * (Portal, Privy, Turnkey) send this exact calldata, so a regression here breaks
 * `sendToken` on every provider at once.
 */
class Erc20AbiTest {

    private val recipient = "0x7f5c764cbc14f9669b88837ca1490cca17c31607"

    @Test
    fun `encodeTransfer produces selector plus padded address and amount`() {
        val data = Erc20Abi.encodeTransfer(recipient, BigInteger.valueOf(1_000_000))

        assertThat(data).isEqualTo(
            "0xa9059cbb" +
                "0000000000000000000000007f5c764cbc14f9669b88837ca1490cca17c31607" +
                "00000000000000000000000000000000000000000000000000000000000f4240"
        )
    }

    @Test
    fun `decimal overload converts amount to base units before encoding`() {
        // 1.5 USDC at 6 decimals = 1_500_000 base units
        val fromDecimal = Erc20Abi.encodeTransfer(recipient, BigDecimal("1.5"), 6)
        val fromBaseUnits = Erc20Abi.encodeTransfer(recipient, BigInteger.valueOf(1_500_000))

        assertThat(fromDecimal).isEqualTo(fromBaseUnits)
    }

    @Test
    fun `decimal overload rejects an amount finer than the token`() {
        // 7 decimal places on a 6-decimal token. Truncating here would silently send
        // 1.234567 USDC instead of failing, so the encoder must reject it.
        val ex = assertThrows(RainError.InvalidAmount::class.java) {
            Erc20Abi.encodeTransfer(recipient, BigDecimal("1.2345678"), 6)
        }

        assertThat(ex.amount).isEqualTo("1.2345678")
        assertThat(ex.errorCode.code).isEqualTo("RAIN_406")
    }

    @Test
    fun `decimal overload rejects a negative amount instead of encoding a huge uint256`() {
        val ex = assertThrows(RainError.InvalidAmount::class.java) {
            Erc20Abi.encodeTransfer(recipient, BigDecimal("-1"), 6)
        }

        assertThat(ex.errorCode.code).isEqualTo("RAIN_406")
    }

    @Test
    fun `decimal overload accepts an amount at the token's full precision`() {
        val atLimit = Erc20Abi.encodeTransfer(recipient, BigDecimal("1.234567"), 6)
        val fromBaseUnits = Erc20Abi.encodeTransfer(recipient, BigInteger.valueOf(1_234_567))

        assertThat(atLimit).isEqualTo(fromBaseUnits)
    }

    @Test
    fun `encodeTransfer rejects a truncated address instead of zero-padding it`() {
        // web3j's Address would silently left-zero-pad this into
        // 0x000000000000000000000000000000007f5c764c — a wrong-but-legal recipient.
        val ex = assertThrows(RainError.InvalidRecipient::class.java) {
            Erc20Abi.encodeTransfer("0x7f5c764c", BigInteger.valueOf(1_000_000))
        }

        assertThat(ex.address).isEqualTo("0x7f5c764c")
    }

    @Test
    fun `encodeTransfer rejects an over-long address as a typed error`() {
        assertThrows(RainError.InvalidRecipient::class.java) {
            Erc20Abi.encodeTransfer(recipient + "07", BigInteger.valueOf(1_000_000))
        }
    }

    @Test
    fun `encodeTransfer rejects a non-hex address`() {
        assertThrows(RainError.InvalidRecipient::class.java) {
            Erc20Abi.encodeTransfer("0x7f5c764cbc14f9669b88837ca1490cca17c3160z", BigInteger.ONE)
        }
    }

    @Test
    fun `encodeTransfer handles zero amount`() {
        val data = Erc20Abi.encodeTransfer(recipient, BigInteger.ZERO)

        assertThat(data).isEqualTo(
            "0xa9059cbb" +
                "0000000000000000000000007f5c764cbc14f9669b88837ca1490cca17c31607" +
                "0000000000000000000000000000000000000000000000000000000000000000"
        )
    }

    @Test
    fun `encodeTransfer handles uint256 max`() {
        val max = BigInteger.TWO.pow(256).subtract(BigInteger.ONE)
        val data = Erc20Abi.encodeTransfer(recipient, max)

        assertThat(data).isEqualTo(
            "0xa9059cbb" +
                "0000000000000000000000007f5c764cbc14f9669b88837ca1490cca17c31607" +
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        )
    }
}
