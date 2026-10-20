package app.ezmanga.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodeEncoder {
    fun encode(content: String, size: Int = 640): BitMatrix {
        require(size >= 128) { "QR code size is too small" }
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    }
}
