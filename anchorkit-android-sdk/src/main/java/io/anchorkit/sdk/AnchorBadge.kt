package io.anchorkit.sdk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
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
 * bitmap and the SHA-256 hash of that photo, it produces a branded framed
 * image whose bottom strip contains a scannable QR code that links to the
 * per-photo verification page.
 *
 * The frame is generated entirely on-device; no image data is uploaded to any server.
 */
object AnchorBadge {

    // ── Brand colours ─────────────────────────────────────────────────────────
    private val COLOR_NAVY   = Color.parseColor("#0D1B3E")   // strip background
    private val COLOR_SURFACE = Color.parseColor("#162347")  // device-box fill
    private val COLOR_ORANGE = Color.parseColor("#F97316")   // accent / brand
    private val COLOR_WHITE  = Color.WHITE
    private val COLOR_SUBTLE = Color.parseColor("#94A3B8")   // secondary text

    /**
     * Render a QR code encoding [url] into a [sizePx]×[sizePx] [Bitmap].
     *
     * The returned bitmap has a white background with black QR modules.
     * Uses error correction level M (15% recovery capacity).
     *
     * @param url    The string to encode.
     * @param sizePx Side length of the output bitmap in pixels.
     */
    fun generateQrBitmap(url: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) COLOR_NAVY else COLOR_WHITE)
            }
        }
        return bitmap
    }

    /**
     * Wrap [photoBitmap] in a branded AnchorKit verification frame and return the result.
     *
     * Frame layout:
     * - Thin white padding on the top and sides of the photo.
     * - Dark-navy bottom strip containing:
     *     - Left: AnchorKit icon + "AnchorKit" brand text
     *       and, if [deviceModel] is supplied, a "Captured on: …" pill.
     *     - Right: "SCAN TO VERIFY" label and a QR code with an orange border.
     * - A thin orange accent line between the photo and the strip.
     *
     * The QR code encodes `"$verifyBaseUrl/$hash"`, giving each framed photo a
     * unique link to its blockchain verification status.
     *
     * @param context       Context used to load the bundled AnchorKit icon resource.
     * @param photoBitmap   The original photo in display orientation (EXIF already applied).
     * @param hash          SHA-256 hash of the photo, appended to [verifyBaseUrl] in the QR.
     * @param deviceModel   Optional device model string shown in the "Captured on:" pill
     *                      (e.g. `"${Build.MANUFACTURER} ${Build.MODEL}"`).
     * @param isPending     When `true` the photo's hash is known to the system but not yet
     *                      anchored on-chain. The QR code still encodes the verify URL so
     *                      the live status is always reachable; the label above the QR reads
     *                      "ANCHOR PENDING" in amber instead of "SCAN TO VERIFY" in orange,
     *                      and the accent line uses amber to signal the in-progress state.
     * @param verifyBaseUrl Base URL for the verification page.
     *                      Defaults to `"https://api.framechain.net/verify"`.
     * @return              A new [Bitmap] containing the photo inside the frame.
     */
    fun createVerificationFrame(
        context: Context,
        photoBitmap: Bitmap,
        hash: String,
        deviceModel: String? = null,
        isPending: Boolean = false,
        verifyBaseUrl: String = "https://api.framechain.net/verify"
    ): Bitmap {
        val url = "$verifyBaseUrl/$hash"

        val photoW = photoBitmap.width
        val photoH = photoBitmap.height

        val sidePad = (photoW * 0.03f).toInt().coerceAtLeast(12)
        val topPad  = sidePad
        val stripH  = (photoH * 0.22f).toInt().coerceAtLeast(140)
        val vPad    = (stripH * 0.12f).toInt()

        val frameW = photoW + sidePad * 2
        val frameH = photoH + topPad + stripH
        val stripTop = photoH + topPad

        val result = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // ── White frame background + photo ────────────────────────────────────
        canvas.drawColor(COLOR_WHITE)
        canvas.drawBitmap(photoBitmap, sidePad.toFloat(), topPad.toFloat(), null)

        // ── Dark-navy strip ───────────────────────────────────────────────────
        canvas.drawRect(0f, stripTop.toFloat(), frameW.toFloat(), frameH.toFloat(),
            Paint().apply { color = COLOR_NAVY })

        // ── Accent line at photo / strip boundary ────────────────────────────
        // Orange when verified; amber when the anchor is still pending.
        val accentColor = if (isPending) Color.parseColor("#F59E0B") else COLOR_ORANGE
        val accentH = (photoH * 0.004f).coerceAtLeast(3f)
        canvas.drawRect(0f, stripTop.toFloat(), frameW.toFloat(), stripTop + accentH,
            Paint().apply { color = accentColor })

        // ── QR code (right column) ────────────────────────────────────────────
        val scanTextSize = (stripH * 0.11f).coerceAtLeast(9f)
        val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = scanTextSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.10f
        }
        val scanLabel = if (isPending) "ANCHOR PENDING" else "SCAN TO VERIFY"
        val scanLabelW = scanPaint.measureText(scanLabel)

        // QR fills the remaining height after the "SCAN TO VERIFY" label.
        val qrSize = (stripH - 2 * vPad - scanTextSize - sidePad / 2).toInt().coerceAtLeast(48)
        val qrBitmap = generateQrBitmap(url, qrSize)

        // Right column starts at ~60% of the frame width.
        val rightColX = (frameW * 0.60f).toInt()
        val rightColW = frameW - rightColX - sidePad

        // "SCAN TO VERIFY" — horizontally centered over the QR.
        val scanLabelX = rightColX + (rightColW - scanLabelW) / 2f
        val scanLabelY = stripTop + vPad + accentH + scanTextSize
        canvas.drawText(scanLabel, scanLabelX, scanLabelY, scanPaint)

        // QR — centered below the label, with a thin orange border.
        val qrX = rightColX + (rightColW - qrSize) / 2
        val qrY = (scanLabelY + sidePad / 2f).toInt()
        val border = (qrSize * 0.035f).toInt().coerceAtLeast(3)
        canvas.drawRect(
            (qrX - border).toFloat(), (qrY - border).toFloat(),
            (qrX + qrSize + border).toFloat(), (qrY + qrSize + border).toFloat(),
            Paint().apply { color = accentColor }
        )
        canvas.drawBitmap(qrBitmap, qrX.toFloat(), qrY.toFloat(), null)
        qrBitmap.recycle()

        // ── Left column: icon + brand text + device pill ──────────────────────
        val leftColRight = rightColX - sidePad

        // Anchor icon (from SDK resources).
        val iconSize = (stripH * 0.40f).toInt().coerceAtLeast(32)
        val iconBitmap: Bitmap? = try {
            val raw = BitmapFactory.decodeResource(context.resources, R.drawable.anchorkit_icon)
            if (raw != null) {
                val scaled = Bitmap.createScaledBitmap(raw, iconSize, iconSize, true)
                if (scaled !== raw) raw.recycle()
                scaled
            } else null
        } catch (_: Exception) { null }

        val iconX = (sidePad * 1.5f).toInt()
        val iconY = stripTop + vPad + accentH.toInt()
        if (iconBitmap != null) {
            canvas.drawBitmap(iconBitmap, iconX.toFloat(), iconY.toFloat(), null)
            iconBitmap.recycle()
        }

        // "AnchorKit" — vertically centred against the icon.
        val brandTextSize = (iconSize * 0.48f).coerceAtLeast(12f)
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ORANGE
            textSize = brandTextSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val brandX = (iconX + iconSize + sidePad * 0.8f)
        val brandY  = iconY + iconSize / 2f + brandTextSize / 3f

        canvas.save()
        canvas.clipRect(brandX, stripTop.toFloat(), leftColRight.toFloat(), frameH.toFloat())
        canvas.drawText("AnchorKit", brandX, brandY, brandPaint)
        canvas.restore()

        // "Captured on: …" pill — below the icon row.
        if (!deviceModel.isNullOrEmpty()) {
            val pillTextSize = (stripH * 0.095f).coerceAtLeast(9f)
            val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_SUBTLE
                textSize = pillTextSize
                typeface = Typeface.DEFAULT
            }
            val pillText  = "Captured on: $deviceModel"
            val maxPillW  = (leftColRight - iconX).toFloat()
            val rawPillW  = pillTextPaint.measureText(pillText)
            val pillPadH  = (pillTextSize * 0.45f)
            val pillPadV  = (pillTextSize * 0.30f)
            val pillW     = (rawPillW + 2 * pillPadH).coerceAtMost(maxPillW)
            val pillLeft  = iconX.toFloat()
            val pillTop   = iconY + iconSize + (vPad * 0.4f)
            val pillRight = pillLeft + pillW
            val pillBot   = pillTop + pillTextSize + 2 * pillPadV

            val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SURFACE }
            val cornerR = pillTextSize * 0.40f
            canvas.drawRoundRect(
                RectF(pillLeft, pillTop, pillRight, pillBot),
                cornerR, cornerR, pillBgPaint
            )

            // Clip text to pill interior so it never overflows.
            canvas.save()
            canvas.clipRect(
                pillLeft + pillPadH, pillTop,
                pillRight - pillPadH, pillBot
            )
            canvas.drawText(pillText, pillLeft + pillPadH, pillBot - pillPadV, pillTextPaint)
            canvas.restore()
        }

        return result
    }

    // ── EXIF orientation helper ───────────────────────────────────────────────

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
            else -> return bitmap
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
