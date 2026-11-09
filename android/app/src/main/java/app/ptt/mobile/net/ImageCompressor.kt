package app.ptt.mobile.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

object ImageCompressor {
    fun compress(ctx: Context, uri: Uri, maxSide: Int = 1280, maxBytes: Int = 200 * 1024): ByteArray {
        val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("無法讀取圖片")
        val src = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: throw IllegalArgumentException("無法解析圖片")
        val scale = maxOf(src.width, src.height).toFloat() / maxSide
        val bmp = if (scale > 1f) {
            Bitmap.createScaledBitmap(src, (src.width / scale).toInt().coerceAtLeast(1),
                (src.height / scale).toInt().coerceAtLeast(1), true)
        } else src
        var q = 82
        var out: ByteArray
        do {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, baos)
            out = baos.toByteArray()
            q -= 12
        } while (out.size > maxBytes && q >= 40)
        if (out.size > 220 * 1024) throw IllegalArgumentException("照片仍超過 200KB，請換一張較小的圖")
        return out
    }
}
