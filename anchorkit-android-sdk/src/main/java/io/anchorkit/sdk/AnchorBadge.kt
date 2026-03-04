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
 * Produces branded AnchorKit verification frames for photos captured by the SDK.
 *
 * The only public entry point is [create], which accepts a [PhotoResult] returned
 * by [AnchorKit.capturePhoto] or [AnchorKit.captureAndSubmit].  Tying the input
 * to [PhotoResult] ensures that:
 *  - the QR code always encodes the hash that was submitted to the API,
 *  - EXIF orientation correction is applied consistently, and
 *  - the "Captured on:" device label is read directly from the photo's EXIF
 *    metadata and omitted automatically when that information is absent.
 *
 * All rendering happens on-device; no image data leaves the device.
 */
object AnchorBadge {

    // ── Brand colours ─────────────────────────────────────────────────────────
    private val COLOR_NAVY    = Color.parseColor("#0D1B3E")   // frame background & QR modules
    private val COLOR_SURFACE = Color.parseColor("#162347")   // device-pill fill
    private val COLOR_ORANGE  = Color.parseColor("#F97316")   // accent / brand
    private val COLOR_WHITE   = Color.WHITE                   // QR background
    private val COLOR_SUBTLE  = Color.parseColor("#94A3B8")   // secondary text

    private const val VERIFY_BASE_URL = "https://anchorkit.net/verify"

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Wrap [photo] in a branded AnchorKit verification frame and return the result.
     *
     * The QR code in the frame encodes a verification URL derived from
     * [PhotoResult.hash], so scanning it always reaches the live verification
     * page for that specific photo.  Because the hash comes directly from the
     * [PhotoResult] produced by the SDK's capture pipeline, it is guaranteed to
     * match the hash that was submitted to the API.
     *
     * EXIF orientation and the "Captured on:" device label are both resolved
     * automatically from the photo's embedded EXIF metadata.  If the EXIF does
     * not contain device information the pill is omitted from the badge entirely.
     *
     * @param context Context used to load the bundled AnchorKit icon resource.
     * @param photo   The [PhotoResult] returned by [AnchorKit.capturePhoto] or
     *                [AnchorKit.captureAndSubmit].
     * @return        A new [Bitmap] containing the photo inside the branded frame.
     */
    fun create(
        context: Context,
        photo: PhotoResult
    ): Bitmap {
        val rawBitmap = BitmapFactory.decodeByteArray(photo.data, 0, photo.data.size)
        val orientedBitmap = applyExifOrientation(rawBitmap, photo.data)
        if (orientedBitmap !== rawBitmap) rawBitmap.recycle()

        val deviceModel = extractDeviceModel(photo.data)
        val framed = buildFrame(context, orientedBitmap, photo.hash, deviceModel)
        orientedBitmap.recycle()
        return framed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers (used by other SDK modules; not part of the public API)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Read the EXIF orientation tag from raw JPEG bytes and return the corrected
     * display-orientation [Bitmap].
     *
     * Returns the original bitmap unchanged if the orientation is normal or
     * cannot be read.  A new rotated bitmap is created only when a transform is
     * required; in that case the original is NOT recycled — the caller is
     * responsible for recycling it when no longer needed.
     */
    internal fun applyExifOrientation(bitmap: Bitmap, jpegBytes: ByteArray): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(jpegBytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90       -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180      -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270      -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE  -> { matrix.postRotate(90f);  matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private — frame rendering
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract a human-readable device label from the EXIF Make/Model tags
     * embedded in [jpegBytes].  Returns null if neither tag is present, which
     * causes the "Captured on:" pill to be omitted from the badge.
     *
     * Combines Make and Model when both are available (e.g. "Google Pixel 9a");
     * falls back to whichever single tag is present.
     */
    private fun extractDeviceModel(jpegBytes: ByteArray): String? = try {
        val exif  = ExifInterface(ByteArrayInputStream(jpegBytes))
        val make  = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() }
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() }
        when {
            make != null && model != null -> "$make $model"
            model != null                -> model
            make  != null                -> make
            else                         -> null
        }
    } catch (_: Exception) { null }

    private fun buildFrame(
        context: Context,
        photoBitmap: Bitmap,
        hash: String,
        deviceModel: String?
    ): Bitmap {
        val url = "$VERIFY_BASE_URL?hash=$hash"

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

        // Thin light blue-gray outline at the photo edge, extending into the navy frame.
        // Drawn before the photo so the photo itself covers any inner anti-aliasing bleed.
        val outlineSW = (sidePad * 0.35f).coerceAtLeast(2f)
        val half = outlineSW / 2f
        canvas.drawRect(
            sidePad - half, topPad - half,
            sidePad + photoW + half, topPad + photoH + half,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#8FAABB")
                style = Paint.Style.STROKE
                strokeWidth = outlineSW
            }
        )

        canvas.drawBitmap(photoBitmap, sidePad.toFloat(), topPad.toFloat(), null)

        // ── Right column: QR then "SCAN TO VERIFY" ────────────────────────────

        val scanTextSize = (stripH * 0.10f).coerceAtLeast(9f)
        val scanGap      = vPad * 0.5f

        val qrY    = stripTop + vPad
        val qrSize = (stripH - 2 * vPad - scanTextSize - scanGap).toInt().coerceAtLeast(48)

        val rightColX = (frameW * 0.60f).toInt()
        val rightColW = frameW - rightColX - sidePad
        val qrX = rightColX + (rightColW - qrSize) / 2

        val qrBitmap = buildBrandedQr(url, qrSize, context)

        val border = (qrSize * 0.035f).toInt().coerceAtLeast(3)
        canvas.drawRect(
            (qrX - border).toFloat(), (qrY - border).toFloat(),
            (qrX + qrSize + border).toFloat(), (qrY + qrSize + border).toFloat(),
            Paint().apply { color = COLOR_ORANGE }
        )
        canvas.drawBitmap(qrBitmap, qrX.toFloat(), qrY.toFloat(), null)
        qrBitmap.recycle()

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

        val iconSize = (stripH * 0.42f).toInt().coerceAtLeast(32)
        val iconX    = (sidePad * 1.5f).toInt()
        val iconY    = stripTop + vPad

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

        if (!deviceModel.isNullOrEmpty()) {
            val pillTextSize = (stripH * 0.115f).coerceAtLeast(10f) * 1.15f
            val camSize      = pillTextSize * 1.15f
            val pillPadH     = pillTextSize * 0.55f
            val pillPadV     = pillTextSize * 0.35f
            val iconTextGap  = pillTextSize * 0.40f

            val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_SUBTLE
                textSize = pillTextSize
                typeface = Typeface.DEFAULT
            }
            val label     = "Captured on: $deviceModel"
            val labelW    = pillTextPaint.measureText(label)
            val maxLabelW = (leftColRight - iconX).toFloat() - pillPadH - camSize - iconTextGap
            val pillW     = (2 * pillPadH + camSize + iconTextGap + labelW.coerceAtMost(maxLabelW))
            val pillH     = camSize.coerceAtLeast(pillTextSize) + 2 * pillPadV

            val pillLeft = iconX.toFloat() - pillPadH
            val pillTop  = iconY + iconSize + vPad * 0.75f
            val pillBot  = pillTop + pillH

            val cornerR = pillTextSize * 0.40f
            canvas.drawRoundRect(
                RectF(pillLeft, pillTop, pillLeft + pillW, pillBot),
                cornerR, cornerR,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SURFACE }
            )

            val camCX = pillLeft + pillPadH + camSize / 2f
            val camCY = pillTop + pillH / 2f
            drawCameraIcon(canvas, camCX, camCY, camSize, COLOR_ORANGE, COLOR_SURFACE)

            val textX = camCX + camSize / 2f + iconTextGap
            val textY = camCY + pillTextSize / 3f
            canvas.save()
            canvas.clipRect(textX, pillTop, pillLeft + pillW - pillPadH, pillBot)
            canvas.drawText(label, textX, textY, pillTextPaint)
            canvas.restore()
        }

        return result
    }

    /**
     * Generate a [sizePx]×[sizePx] QR bitmap with navy modules on white, then
     * composite the AnchorKit icon over the centre.  Error correction is raised
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

        val overlayPx = (sizePx * 0.20f).toInt().coerceAtLeast(16)
        try {
            val raw = BitmapFactory.decodeResource(context.resources, R.drawable.anchorkit_icon)
            if (raw != null) {
                val icon = Bitmap.createScaledBitmap(raw, overlayPx, overlayPx, true)
                if (icon !== raw) raw.recycle()

                val ox  = (sizePx - overlayPx) / 2
                val oy  = (sizePx - overlayPx) / 2
                val pad = overlayPx * 0.12f

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
     * Draw the Material camera icon centred at ([cx], [cy]), scaled to [size] pixels,
     * filled with [tint].  The aperture ring is rendered by drawing a [bgColor] circle
     * (r=5) over the solid body and then the lens disc (r=3.2) in [tint] on top.
     */
    private fun drawCameraIcon(
        canvas: Canvas, cx: Float, cy: Float, size: Float, tint: Int, bgColor: Int
    ) {
        val s  = size / 24f
        val ox = cx - 12f * s
        val oy = cy - 12f * s
        fun fx(x: Float) = ox + x * s
        fun fy(y: Float) = oy + y * s

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.FILL }

        val bodyPath = android.graphics.Path().apply {
            moveTo(fx(9f),     fy(2f))
            lineTo(fx(7.17f),  fy(4f))
            lineTo(fx(4f),     fy(4f))
            cubicTo(fx(2.9f),  fy(4f),    fx(2f),    fy(4.9f),  fx(2f),   fy(6f))
            lineTo(fx(2f),     fy(18f))
            cubicTo(fx(2f),    fy(19.1f), fx(2.9f),  fy(20f),   fx(4f),   fy(20f))
            lineTo(fx(20f),    fy(20f))
            cubicTo(fx(21.1f), fy(20f),   fx(22f),   fy(19.1f), fx(22f),  fy(18f))
            lineTo(fx(22f),    fy(6f))
            cubicTo(fx(22f),   fy(4.9f),  fx(21.1f), fy(4f),    fx(20f),  fy(4f))
            lineTo(fx(16.83f), fy(4f))
            lineTo(fx(15f),    fy(2f))
            close()
        }
        canvas.drawPath(bodyPath, paint)

        canvas.drawCircle(cx, cy, 5f * s,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor; style = Paint.Style.FILL })

        canvas.drawCircle(cx, cy, 3.2f * s, paint)
    }
}
