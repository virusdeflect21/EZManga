package app.ezmanga.tv.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.common.BitMatrix

fun BitMatrix.toAndroidBitmap(): Bitmap {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[y * width + x] = if (this[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
