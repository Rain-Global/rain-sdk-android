package com.rain.sdk.internal.transaction

import android.webkit.URLUtil
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import com.rain.sdk.internal.network.Web3jProvider
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainWithdrawAddresses
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * Cross-platform golden for `withdrawAsset` calldata.
 *
 * The expected hex is pinned byte-for-byte and the identical literal is asserted by
 * the same fixtures. Substring assertions would hide argument
 * reordering, so this pins the whole encoding. Any change here must land on both platforms.
 */
class WithdrawCalldataGoldenTest {

    private val addresses = RainWithdrawAddresses(
        proxyAddress = GoldenWithdrawFixture.PROXY_ADDRESS,
        controllerAddress = GoldenWithdrawFixture.CONTROLLER_ADDRESS,
        tokenAddress = GoldenWithdrawFixture.TOKEN_ADDRESS,
        recipientAddress = GoldenWithdrawFixture.RECIPIENT_ADDRESS
    )

    @Before
    fun setUp() {
        mockkStatic(URLUtil::class)
        every { URLUtil.isValidUrl(any()) } returns true
        Web3jProvider.resetForTest()
    }

    @After
    fun tearDown() {
        Web3jProvider.resetForTest()
        unmockkAll()
    }

    @Test
    fun `withdrawAsset calldata matches the cross-platform golden`() {
        // Pure encoding — no RPC, so the endpoint map is irrelevant here.
        val calldata = RainTransactionBuilderImpl(emptyMap()).buildWithdrawTransactionData(
            addresses = addresses,
            amount = BigDecimal(GoldenWithdrawFixture.AMOUNT),
            decimals = GoldenWithdrawFixture.DECIMALS,
            executorSignature = RainAdminSignature(
                salt = GoldenWithdrawFixture.EXECUTOR_SALT_BASE64,
                signature = GoldenWithdrawFixture.EXECUTOR_SIGNATURE_HEX,
                expiresAt = GoldenWithdrawFixture.EXPIRES_AT
            ),
            walletSalt = GoldenWithdrawFixture.walletSaltBytes(),
            walletSignature = GoldenWithdrawFixture.WALLET_SIGNATURE_HEX
        )

        assertThat(calldata).isEqualTo(GoldenWithdrawFixture.EXPECTED_CALLDATA)
    }
}

/**
 * The pinned inputs and output. Held stable so the
 * two encoders are provably byte-compatible.
 */
object GoldenWithdrawFixture {
    const val PROXY_ADDRESS = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
    const val CONTROLLER_ADDRESS = "0x5555555555555555555555555555555555555555"
    const val TOKEN_ADDRESS = "0x9876543210987654321098765432109876543210"
    const val RECIPIENT_ADDRESS = "0xfedcbafedcbafedcbafedcbafedcbafedcbafedc"

    const val AMOUNT = "100.5"
    const val DECIMALS = 6
    const val EXPIRES_AT = "1735689600"

    /** Rain's executor-publisher salt, base64 as the Rain API returns it (32 × 0x11). */
    const val EXECUTOR_SALT_BASE64 = "ERERERERERERERERERERERERERERERERERERERERERE="

    /** Rain's executor-publisher signature (65 × 0x42). */
    const val EXECUTOR_SIGNATURE_HEX = "0x" +
        "4242424242424242424242424242424242424242424242424242424242424242" +
        "4242424242424242424242424242424242424242424242424242424242424242" +
        "42"

    /** The wallet's own signature (65 × 0xBB), encoded into `_adminSignatures`. */
    const val WALLET_SIGNATURE_HEX = "0x" +
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
        "bb"

    /** The wallet's own EIP-712 salt (32 × 0xAA), encoded into `_adminSalts`. */
    fun walletSaltBytes(): ByteArray = ByteArray(32) { 0xAA.toByte() }

    const val EXPECTED_CALLDATA = "0x4b268241" +
        "000000000000000000000000abcdefabcdefabcdefabcdefabcdefabcdefabcd" +
        "0000000000000000000000009876543210987654321098765432109876543210" +
        "0000000000000000000000000000000000000000000000000000000005fd8220" +
        "000000000000000000000000fedcbafedcbafedcbafedcbafedcbafedcbafedc" +
        "0000000000000000000000000000000000000000000000000000000067748580" +
        "1111111111111111111111111111111111111111111111111111111111111111" +
        "0000000000000000000000000000000000000000000000000000000000000140" +
        "00000000000000000000000000000000000000000000000000000000000001c0" +
        "0000000000000000000000000000000000000000000000000000000000000200" +
        "0000000000000000000000000000000000000000000000000000000000000001" +
        "0000000000000000000000000000000000000000000000000000000000000041" +
        "4242424242424242424242424242424242424242424242424242424242424242" +
        "4242424242424242424242424242424242424242424242424242424242424242" +
        "4200000000000000000000000000000000000000000000000000000000000000" +
        "0000000000000000000000000000000000000000000000000000000000000001" +
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
        "0000000000000000000000000000000000000000000000000000000000000001" +
        "0000000000000000000000000000000000000000000000000000000000000020" +
        "0000000000000000000000000000000000000000000000000000000000000041" +
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
        "bb00000000000000000000000000000000000000000000000000000000000000"
}
