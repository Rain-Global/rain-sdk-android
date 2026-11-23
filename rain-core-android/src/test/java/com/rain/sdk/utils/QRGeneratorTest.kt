package com.rain.sdk.utils

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Covers the pure encoding step behind [QRGenerator.generateQRCode]; the Bitmap rendering on top
 * of it is a thin pixel copy that needs the platform runtime.
 */
class QRGeneratorTest {

    private val address = "0x1234567890abcdef1234567890abcdef12345678"

    @Test
    fun `matrix has the requested dimensions`() {
        val matrix = QRGenerator.encodeToMatrix(address, 200, 200)
        assertThat(matrix.width).isEqualTo(200)
        assertThat(matrix.height).isEqualTo(200)
    }

    @Test
    fun `matrix decodes back to the encoded address`() {
        val matrix = QRGenerator.encodeToMatrix(address, 200, 200)
        assertThat(decode(matrix)).isEqualTo(address)
    }

    @Test
    fun `default size decodes back to the encoded address`() {
        val matrix = QRGenerator.encodeToMatrix(address, 500, 500)
        assertThat(matrix.width).isEqualTo(500)
        assertThat(decode(matrix)).isEqualTo(address)
    }

    @Test
    fun `symbol is dark modules on a light background`() {
        val matrix = QRGenerator.encodeToMatrix(address, 200, 200)

        // The quiet zone must be light: set bits render dark, so the corners stay unset.
        assertThat(matrix[0, 0]).isFalse()
        assertThat(matrix[199, 0]).isFalse()
        assertThat(matrix[0, 199]).isFalse()
        assertThat(matrix[199, 199]).isFalse()

        // The symbol itself is non-trivial and starts with the top-left finder pattern: the
        // first set module row/column is the finder's dark outer ring.
        val symbol = matrix.enclosingRectangle // [left, top, width, height]
        assertThat(symbol[2]).isGreaterThan(20)
        assertThat(symbol[3]).isGreaterThan(20)
        assertThat(matrix[symbol[0], symbol[1]]).isTrue()
    }

    @Test
    fun `empty text is rejected by the encoder`() {
        assertThrows(IllegalArgumentException::class.java) {
            QRGenerator.encodeToMatrix("", 200, 200)
        }
    }

    /** Renders the matrix to pixels and runs the reference decoder over them. */
    private fun decode(matrix: BitMatrix): String {
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }
}
