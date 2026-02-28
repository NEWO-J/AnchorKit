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
    private val COLOR_NAVY    = Color.parseColor("#0D1B3E")   // frame background & QR modules
    private val COLOR_SURFACE = Color.parseColor("#162347")   // device-pill fill
    private val COLOR_ORANGE  = Color.parseColor("#F97316")   // accent / brand
    private val COLOR_WHITE   = Color.WHITE                   // QR background
    private val COLOR_SUBTLE  = Color.parseColor("#94A3B8")   // secondary text

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Render a QR code encoding [url] into a [sizePx]×[sizePx] [Bitmap].
     *
     * Modules are rendered in navy on a white background for brand consistency.
     * Uses error correction level M (15 % recovery capacity).
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
     * - Navy padding on the top and sides of the photo (matches strip).
     * - Dark-navy bottom strip with no separator line:
     *     - Left: AnchorKit icon + "AnchorKit" brand text; below that a pill
     *       with an orange camera icon and "Captured on: …" when [deviceModel]
     *       is supplied.
     *     - Right: QR code (navy modules on white, AnchorKit icon overlaid in
     *       the centre) with an orange border; "SCAN TO VERIFY" label below.
     *       The top of the QR aligns with the top of the brand icon row.
     *
     * The QR encodes `"$verifyBaseUrl/$hash"` so scanning always reaches the
     * live verification status page.
     *
     * @param context       Context used to load the bundled AnchorKit icon resource.
     * @param photoBitmap   The original photo in display orientation (EXIF already applied).
     * @param hash          SHA-256 hash of the photo, appended to [verifyBaseUrl] in the QR.
     * @param deviceModel   Optional device model string shown in the "Captured on:" pill
     *                      (e.g. `"${Build.MANUFACTURER} ${Build.MODEL}"`).
     * @param isPending     Indicates the hash is known but not yet anchored on-chain.
     *                      The badge visual is identical; status is communicated via
     *                      the QR-linked verification page.
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
        @Suppress("UNUSED_VARIABLE") val _pendingAck = isPending  // status exposed via QR link

        val url = "$verifyBaseUrl/$hash"

        val photoW = photoBitmap.width
        val photoH = photoBitmap.height

        val sidePad = (photoW * 0.03f).toInt().coerceAtLeast(12)
        val topPad  = sidePad
        val stripH  = (photoH * 0.22f).toInt().coerceAtLeast(140)
        val vPad    = (stripH * 0.10f).toInt()

        val frameW   = photoW + sidePad * 2
        val frameH   = photoH + topPad + stripH
        val stripTop = photoH + topPad

        val result = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // ── Navy fills the whole frame; photo sits in the centre window ────────
        canvas.drawColor(COLOR_NAVY)
        canvas.drawBitmap(photoBitmap, sidePad.toFloat(), topPad.toFloat(), null)

        // ── Right column: QR then "SCAN TO VERIFY" ────────────────────────────

        val scanTextSize = (stripH * 0.10f).coerceAtLeast(9f)
        val scanGap      = vPad * 0.5f

        // QR top-edge aligns exactly with the brand icon top-edge (both at stripTop + vPad).
        val qrY    = stripTop + vPad
        val qrSize = (stripH - 2 * vPad - scanTextSize - scanGap).toInt().coerceAtLeast(48)

        val rightColX = (frameW * 0.60f).toInt()
        val rightColW = frameW - rightColX - sidePad
        val qrX = rightColX + (rightColW - qrSize) / 2

        // Build branded QR (navy on white, AnchorKit icon centred, H-level ECC).
        val qrBitmap = buildBrandedQr(url, qrSize, context)

        // Orange border around the QR
        val border = (qrSize * 0.035f).toInt().coerceAtLeast(3)
        canvas.drawRect(
            (qrX - border).toFloat(), (qrY - border).toFloat(),
            (qrX + qrSize + border).toFloat(), (qrY + qrSize + border).toFloat(),
            Paint().apply { color = COLOR_ORANGE }
        )
        canvas.drawBitmap(qrBitmap, qrX.toFloat(), qrY.toFloat(), null)
        qrBitmap.recycle()

        // "SCAN TO VERIFY" — centred below the QR
        val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ORANGE
            textSize = scanTextSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.10f
        }
        val scanLabel  = "SCAN TO VERIFY"
        val scanLabelW = scanPaint.measureText(scanLabel)
        canvas.drawText(
            scanLabel,
            rightColX + (rightColW - scanLabelW) / 2f,
            qrY + qrSize + scanGap + scanTextSize,
            scanPaint
        )

        // ── Left column: brand logo + "Captured on:" pill ─────────────────────

        val leftColRight = rightColX - sidePad

        // AnchorKit icon square
        val iconSize = (stripH * 0.42f).toInt().coerceAtLeast(32)
        val iconX    = (sidePad * 1.5f).toInt()
        val iconY    = stripTop + vPad                          // same Y as qrY

        val iconBitmap: Bitmap? = try {
            val raw = BitmapFactory.decodeResource(context.resources, R.drawable.anchorkit_icon)
            if (raw != null) {
                val s = Bitmap.createScaledBitmap(raw, iconSize, iconSize, true)
                if (s !== raw) raw.recycle()
                s
            } else null
        } catch (_: Exception) { null }

        if (iconBitmap != null) {
            canvas.drawBitmap(iconBitmap, iconX.toFloat(), iconY.toFloat(), null)
            iconBitmap.recycle()
        }

        // "AnchorKit" — vertically centred against the icon
        val brandTextSize = (iconSize * 0.48f).coerceAtLeast(12f)
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ORANGE
            textSize = brandTextSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val brandX = (iconX + iconSize).toFloat() + sidePad * 0.8f
        val brandY = iconY + iconSize / 2f + brandTextSize / 3f
        canvas.save()
        canvas.clipRect(brandX, stripTop.toFloat(), leftColRight.toFloat(), frameH.toFloat())
        canvas.drawText("AnchorKit", brandX, brandY, brandPaint)
        canvas.restore()

        // "Captured on: …" pill — below icon row, with leading camera icon
        if (!deviceModel.isNullOrEmpty()) {
            val pillTextSize = (stripH * 0.115f).coerceAtLeast(10f)
            val camSize      = pillTextSize * 1.15f
            val pillPadH     = pillTextSize * 0.55f
            val pillPadV     = pillTextSize * 0.35f
            val iconTextGap  = pillTextSize * 0.40f

            val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_SUBTLE
                textSize = pillTextSize
                typeface = Typeface.DEFAULT
            }
            val label    = "Captured on: $deviceModel"
            val labelW   = pillTextPaint.measureText(label)
            val maxLabelW = (leftColRight - iconX).toFloat() - 2 * pillPadH - camSize - iconTextGap
            val pillW    = (2 * pillPadH + camSize + iconTextGap + labelW.coerceAtMost(maxLabelW))
            val pillH    = camSize.coerceAtLeast(pillTextSize) + 2 * pillPadV

            val pillLeft = iconX.toFloat()
            val pillTop  = iconY + iconSize + vPad * 0.75f
            val pillBot  = pillTop + pillH

            // Pill background
            val cornerR = pillTextSize * 0.40f
            canvas.drawRoundRect(
                RectF(pillLeft, pillTop, pillLeft + pillW, pillBot),
                cornerR, cornerR,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SURFACE }
            )

            // Camera icon — centred vertically in pill
            val camCX = pillLeft + pillPadH + camSize / 2f
            val camCY = pillTop + pillH / 2f
            drawCameraIcon(canvas, camCX, camCY, camSize, COLOR_ORANGE)

            // Text — clipped so it never overflows the pill
            val textX = camCX + camSize / 2f + iconTextGap
            val textY = camCY + pillTextSize / 3f
            canvas.save()
            canvas.clipRect(textX, pillTop, pillLeft + pillW - pillPadH, pillBot)
            canvas.drawText(label, textX, textY, pillTextPaint)
            canvas.restore()
        }

        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate a [sizePx]×[sizePx] QR bitmap with navy modules on white, then
     * composite the AnchorKit icon over the centre. Error correction is raised
     * to H (30 %) so the overlaid icon does not compromise scannability.
     */
    private fun buildBrandedQr(url: String, sizePx: Int, context: Context): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val qrBitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                qrBitmap.setPixel(x, y, if (matrix[x, y]) COLOR_NAVY else COLOR_WHITE)
            }
        }

        // Overlay the icon (~20 % of QR side) centred on the code.
        val overlayPx = (sizePx * 0.20f).toInt().coerceAtLeast(16)
        try {
            val raw = BitmapFactory.decodeResource(context.resources, R.drawable.anchorkit_icon)
            if (raw != null) {
                val icon = Bitmap.createScaledBitmap(raw, overlayPx, overlayPx, true)
                if (icon !== raw) raw.recycle()

                val ox = (sizePx - overlayPx) / 2
                val oy = (sizePx - overlayPx) / 2
                val pad = overlayPx * 0.12f

                // White backing so QR modules don't bleed through the icon edges.
                Canvas(qrBitmap).also { c ->
                    c.drawRect(ox - pad, oy - pad, ox + overlayPx + pad, oy + overlayPx + pad,
                        Paint().apply { color = COLOR_WHITE })
                    c.drawBitmap(icon, ox.toFloat(), oy.toFloat(), null)
                }
                icon.recycle()
            }
        } catch (_: Exception) { /* icon overlay is best-effort */ }

        return qrBitmap
    }

    /**
     * Draw a minimal camera silhouette centred at ([cx], [cy]), [size] pixels wide,
     * filled with [tint]. Used as the leading icon inside the "Captured on:" pill.
     */
    private fun drawCameraIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, tint: Int) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.FILL }

        val bodyW  = size
        val bodyH  = size * 0.70f
        val cornerR = size * 0.16f
        val bodyL  = cx - bodyW / 2f
        val bodyT  = cy - bodyH / 2f + size * 0.08f

        // Body
        canvas.drawRoundRect(RectF(bodyL, bodyT, bodyL + bodyW, bodyT + bodyH),
            cornerR, cornerR, fill)

        // Viewfinder bump (top-centre)
        val bumpW = size * 0.30f
        val bumpH = size * 0.18f
        canvas.drawRoundRect(
            RectF(cx - bumpW / 2f, bodyT - bumpH + cornerR, cx + bumpW / 2f, bodyT + cornerR),
            cornerR * 0.5f, cornerR * 0.5f, fill
        )

        // Lens aperture (navy circle — reveals strip background through the icon)
        val lensR  = size * 0.21f
        val lensCY = bodyT + bodyH * 0.52f
        canvas.drawCircle(cx, lensCY, lensR,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_NAVY; style = Paint.Style.FILL })

        // Inner lens ring (orange, semi-transparent — gives depth)
        canvas.drawCircle(cx, lensCY, lensR * 0.50f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tint; style = Paint.Style.FILL; alpha = 90
            })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXIF orientation helper
    // ─────────────────────────────────────────────────────────────────────────

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
            ExifInterface.ORIENTATION_ROTATE_90      -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180     -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270     -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f);  matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
