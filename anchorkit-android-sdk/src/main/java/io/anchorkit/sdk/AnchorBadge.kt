package io.anchorkit.sdk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayInputStream

/**
 * Core utility for the AnchorBadges feature.
 *
 * Provides QR code generation and verification-frame compositing. The
 * [createVerificationFrame] function is the primary entry point: given a photo
 * bitmap and the SHA-256 hash of that photo, it produces a Polaroid-style framed
 * image whose bottom strip contains a scannable QR code that links to the
 * per-photo verification page.
 *
 * The frame is generated entirely on-device; no image data is uploaded to any server.
 */
object AnchorBadge {

    /**
     * Render a QR code encoding [url] into a [sizePx]×[sizePx] [Bitmap].
     *
     * The returned bitmap has a white background with black QR modules.
     * Uses error correction level M (15% recovery capacity), which balances
     * module density against scan reliability on printed or screen-displayed badges.
     *
     * @param url    The string to encode.
     * @param sizePx Side length of the output bitmap in pixels.
     */
    fun generateQrBitmap(url: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1  // minimal quiet zone — we control sizing
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
     * Wrap [photoBitmap] in a Polaroid-style verification frame and return the result.
     *
     * Frame layout:
     * - White background
     * - Equal padding on left / right / top (~3% of photo width, minimum 12 px)
     * - Taller bottom strip (~18% of photo height, minimum 80 px) containing:
     *     - "AnchorKit Verified ✓" label in the left portion of the strip
     *     - A scannable QR code in the right portion of the strip
     *
     * The QR code encodes `"$verifyBaseUrl/$hash"`, giving each framed photo a
     * unique link to its blockchain verification status.
     *
     * The returned bitmap is owned by the caller; recycle [photoBitmap] if
     * it is no longer needed after this call.
     *
     * @param photoBitmap   The original photo in display orientation (EXIF already applied).
     * @param hash          SHA-256 hash of the photo, appended to [verifyBaseUrl] in the QR.
     * @param verifyBaseUrl Base URL for the verification page.
     *                      Defaults to `"https://api.framechain.net/verify"`.
     * @return              A new [Bitmap] containing the photo inside the frame.
     */
    fun createVerificationFrame(
        photoBitmap: Bitmap,
        hash: String,
        verifyBaseUrl: String = "https://api.framechain.net/verify"
    ): Bitmap {
        val url = "$verifyBaseUrl/$hash"

        val photoW = photoBitmap.width
        val photoH = photoBitmap.height

        // Padding around the photo — proportional to photo width, with a floor.
        val sidePad   = (photoW * 0.03f).toInt().coerceAtLeast(12)
        val topPad    = sidePad
        val bottomH   = (photoH * 0.18f).toInt().coerceAtLeast(80)

        val frameW = photoW + sidePad * 2
        val frameH = photoH + topPad + bottomH

        // QR occupies 80% of the bottom strip height, vertically centered.
        val qrSize  = (bottomH * 0.80f).toInt().coerceAtLeast(32)
        val qrBitmap = generateQrBitmap(url, qrSize)

        val result  = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
        val canvas  = Canvas(result)

        // White frame background.
        canvas.drawColor(Color.WHITE)

        // Photo at (sidePad, topPad).
        canvas.drawBitmap(photoBitmap, sidePad.toFloat(), topPad.toFloat(), null)

        // Thin light-gray separator line between photo and bottom strip.
        val separatorPaint = Paint().apply {
            color = Color.parseColor("#E0E0E0")
            strokeWidth = 1f
        }
        val sepY = (photoH + topPad).toFloat()
        canvas.drawLine(sidePad.toFloat(), sepY, (frameW - sidePad).toFloat(), sepY, separatorPaint)

        // QR code — right-aligned inside bottom strip, vertically centered.
        val qrX = (frameW - sidePad - qrSize).toFloat()
        val qrY = (photoH + topPad + (bottomH - qrSize) / 2).toFloat()
        canvas.drawBitmap(qrBitmap, qrX, qrY, null)
        qrBitmap.recycle()

        // "AnchorKit Verified ✓" label — left portion of the bottom strip.
        // Two lines: brand name on top, status on bottom.
        val textAreaRight = qrX - sidePad * 2
        val textSize      = (bottomH * 0.20f).coerceAtLeast(10f)
        val stripCenterY  = photoH + topPad + bottomH / 2f

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = Color.parseColor("#1A1A1A")
            textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = Color.parseColor("#2E7D32")  // dark green — verified status
            textSize = textSize * 0.85f
            typeface = Typeface.DEFAULT
        }

        // Vertically center the two-line block in the strip.
        val lineGap   = textSize * 0.25f
        val blockH    = brandPaint.textSize + lineGap + statusPaint.textSize
        val brandY    = stripCenterY - blockH / 2f + brandPaint.textSize
        val statusY   = brandY + lineGap + statusPaint.textSize

        val textX     = (sidePad * 2).toFloat()

        // Clip text to the available horizontal space so it never overlaps the QR.
        canvas.save()
        canvas.clipRect(
            Rect(textX.toInt(), (photoH + topPad), textAreaRight.toInt(), frameH)
        )
        canvas.drawText("AnchorKit", textX, brandY, brandPaint)
        canvas.drawText("Verified \u2713", textX, statusY, statusPaint)
        canvas.restore()

        return result
    }

    // -------------------------------------------------------------------------
    // EXIF orientation helper (used by callers that decode JPEG bytes)
    // -------------------------------------------------------------------------

    /**
     * Read the EXIF orientation tag from raw JPEG bytes and return the corrected
     * display-orientation [Bitmap].
     *
     * Most Android camera APIs deliver JPEG with an `Orientation` EXIF tag rather
     * than physically rotating the pixels. `BitmapFactory.decodeByteArray` does
     * NOT apply this tag automatically — callers must use this function to obtain
     * a bitmap that matches the visual orientation before passing it to
     * [createVerificationFrame].
     *
     * Returns the original bitmap unchanged if orientation is normal or cannot be
     * read. A new rotated bitmap is created only when a transform is required; in
     * that case the original is NOT recycled — the caller should recycle it if no
     * longer needed.
     *
     * @param bitmap     Bitmap decoded from JPEG bytes via `BitmapFactory`.
     * @param jpegBytes  The original JPEG byte array from which [bitmap] was decoded.
     * @return           Bitmap in display orientation.
     */
    fun applyExifOrientation(bitmap: Bitmap, jpegBytes: ByteArray): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(jpegBytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90    -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180   -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270   -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap  // ORIENTATION_NORMAL or unrecognised — no transform
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
