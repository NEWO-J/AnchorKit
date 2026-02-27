package io.anchorkit.sdk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A draggable, semi-transparent QR code overlay for camera screens.
 *
 * Drop this view into your camera layout on top of a `PreviewView`. It renders a 50%-opaque
 * QR code silhouette that the user can drag to their preferred position before taking a photo.
 * At shutter time, read [getNormalizedPosition] and pass it (or use [AnchorBadgeOptions.Draggable])
 * to apply the badge to the captured image at the same relative position.
 *
 * The view generates its QR bitmap lazily on first draw using [AnchorBadge.generateQrBitmap].
 *
 * Usage in XML:
 * ```xml
 * <io.anchorkit.sdk.AnchorBadgeOverlayView
 *     android:id="@+id/anchorBadgeOverlay"
 *     android:layout_width="80dp"
 *     android:layout_height="80dp"
 *     android:layout_gravity="bottom|end"
 *     android:layout_marginEnd="24dp"
 *     android:layout_marginBottom="180dp" />
 * ```
 *
 * Usage in code:
 * ```kotlin
 * // At shutter time:
 * val pos = binding.anchorBadgeOverlay.getNormalizedPosition()
 * val badgedBytes = AnchorBadge.applyBadgeToJpeg(photoData, pos.x, pos.y, AnchorBadgeOptions.Fixed(pos.x, pos.y))
 * ```
 * Or simply pass the view to [AnchorBadgeOptions.Draggable] when calling
 * [AnchorKit.captureAndSubmit]:
 * ```kotlin
 * anchorKit.captureAndSubmit(this, anchorBadge = AnchorBadgeOptions.Draggable(binding.anchorBadgeOverlay))
 * ```
 */
class AnchorBadgeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var qrBitmap: Bitmap? = null
    private val transparentPaint = Paint().apply { alpha = 128 }

    // Touch drag state
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    init {
        // Allow touch events to be intercepted by this view
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            // Generate at the larger dimension to keep the QR sharp on non-square views
            val sizePx = maxOf(w, h)
            qrBitmap?.recycle()
            qrBitmap = AnchorBadge.generateQrBitmap(AnchorBadgeOptions.DEFAULT_URL, sizePx)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val qr = qrBitmap ?: return
        // Scale the pre-generated bitmap to exactly fill this view's bounds
        canvas.drawBitmap(
            qr,
            android.graphics.Rect(0, 0, qr.width, qr.height),
            android.graphics.Rect(0, 0, width, height),
            transparentPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val parentView = parent as? View ?: return super.onTouchEvent(event)

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Record the offset between the touch point and the view's current position
                dragOffsetX = event.rawX - x
                dragOffsetY = event.rawY - y
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val newX = (event.rawX - dragOffsetX)
                    .coerceIn(0f, (parentView.width  - width ).toFloat())
                val newY = (event.rawY - dragOffsetY)
                    .coerceIn(0f, (parentView.height - height).toFloat())
                x = newX
                y = newY
                true
            }
            else -> super.onTouchEvent(event)
        }
    }

    /**
     * Returns the badge's current position as normalized coordinates relative to the parent view.
     *
     * Both components are in [0.0, 1.0]. The value represents the top-left corner of the
     * badge as a fraction of the parent's width/height — the same coordinate system used
     * by [AnchorBadge.applyBadgeToJpeg] and [AnchorBadgeOptions.Fixed].
     *
     * Returns `PointF(0f, 0f)` if the view is not yet attached to a parent.
     */
    fun getNormalizedPosition(): PointF {
        val parentView = parent as? View ?: return PointF(0f, 0f)
        val parentWidth  = parentView.width.toFloat()
        val parentHeight = parentView.height.toFloat()
        if (parentWidth == 0f || parentHeight == 0f) return PointF(0f, 0f)
        return PointF(
            x.coerceIn(0f, parentWidth  - width)  / parentWidth,
            y.coerceIn(0f, parentHeight - height) / parentHeight
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        qrBitmap?.recycle()
        qrBitmap = null
    }
}
