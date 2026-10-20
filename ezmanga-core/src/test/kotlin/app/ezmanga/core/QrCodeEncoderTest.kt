package app.ezmanga.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QrCodeEncoderTest {
    @Test
    fun `the generated QR code decodes back to the pairing URI`() {
        val payload = PairingPayload(
            host = "10.0.2.15",
            port = 55123,
            token = "token-0123456789abcdef",
            deviceName = "EZManga TV",
        )
        val content = payload.toUri()
        val matrix = QrCodeEncoder.encode(content, 512)

        val pixels = IntArray(matrix.width * matrix.height) { index ->
            val x = index % matrix.width
            val y = index / matrix.width
            if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)))

        assertEquals(content, decoded.text)
    }
}
