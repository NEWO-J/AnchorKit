package io.anchorkit.sdk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream

/**
 * Core utility for the AnchorBadges feature.
 *
 * Provides QR code bitmap generation and JPEG compositing. These functions are used
 * internally by [AnchorKit.captureAndSubmit] and [AnchorKit.capturePhoto] when an
 * [AnchorBadgeOptions] is supplied, and are also available for apps that manage their
 * own camera lifecycle and need to apply the badge manually.
 */
object AnchorBadge {

    /**
     * Render a QR code encoding [url] into a [sizePx]×[sizePx] [Bitmap].
     *
     * The returned bitmap has a white background with black QR modules.
     * Uses error correction level M (15% recovery capacity), which offers a good
     * balance between density and scan reliability on printed/displayed badges.
     *
     * @param url  The string to encode in the QR code.
     * @param sizePx Side length of the output bitmap in pixels.
     */
    fun generateQrBitmap(url: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1   // minimal quiet zone — we control sizing
        )
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    /**
     * Composite a QR badge onto [jpegBytes] and return the result as a new JPEG byte array.
     *
     * The badge is positioned with its top-left corner at ([normalizedX] × imageWidth,
     * [normalizedY] × imageHeight). Both axes are clamped so the badge never overflows
     * the image boundary. The badge size is [AnchorBadgeOptions.sizeFraction] × imageWidth.
     *
     * The output is re-encoded at JPEG quality 92, which is visually lossless at typical
     * photo resolutions while keeping file size reasonable.
     *
     * Note: this function decodes and re-encodes the JPEG, which changes the raw byte
     * sequence and therefore the SHA-256 hash. This is intentional — the badge is part of
     * the captured moment that gets anchored to the blockchain.
     *
     * @param jpegBytes  Original JPEG bytes from the camera.
     * @param normalizedX  Horizontal position of badge top-left as a fraction of image width (0.0–1.0).
     * @param normalizedY  Vertical position of badge top-left as a fraction of image height (0.0–1.0).
     * @param options  [AnchorBadgeOptions] carrying [url][AnchorBadgeOptions.url] and
     *   [sizeFraction][AnchorBadgeOptions.sizeFraction].
     * @return New JPEG bytes with the QR badge composited in.
     */
    fun applyBadgeToJpeg(
        jpegBytes: ByteArray,
        normalizedX: Float,
        normalizedY: Float,
        options: AnchorBadgeOptions
    ): ByteArray {
        val original = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return jpegBytes  // graceful fallback: return unchanged if decode fails

        val imageWidth  = original.width
        val imageHeight = original.height

        // Badge side length — proportional to image width
        val badgePx = (options.sizeFraction * imageWidth).toInt().coerceAtLeast(32)

        val qrBitmap = generateQrBitmap(options.url, badgePx)

        // Clamp position so badge stays fully inside the image
        val badgeX = (normalizedX * imageWidth).toInt()
            .coerceIn(0, imageWidth  - badgePx)
        val badgeY = (normalizedY * imageHeight).toInt()
            .coerceIn(0, imageHeight - badgePx)

        // Draw QR onto a mutable copy of the original
        val mutable = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas  = Canvas(mutable)
        canvas.drawBitmap(qrBitmap, badgeX.toFloat(), badgeY.toFloat(), Paint())

        qrBitmap.recycle()
        original.recycle()

        val out = ByteArrayOutputStream()
        mutable.compress(Bitmap.CompressFormat.JPEG, 92, out)
        mutable.recycle()

        return out.toByteArray()
    }
}
