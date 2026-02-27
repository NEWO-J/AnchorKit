package io.anchorkit.sdk

/**
 * Configuration for the AnchorBadge feature — a scannable QR code stamped onto a photo
 * at capture time.
 *
 * Pass an instance of this sealed class to [AnchorKit.captureAndSubmit] or
 * [AnchorKit.capturePhoto] to enable badge placement.
 *
 * Two modes:
 * - [Draggable]: the user drags an [AnchorBadgeOverlayView] to the desired spot; the SDK
 *   reads the view's current position at shutter time.
 * - [Fixed]: the developer specifies exact normalized coordinates at call time; no UI needed.
 *
 * Example usage:
 * ```kotlin
 * // User drags the badge — add AnchorBadgeOverlayView to your layout and pass it here
 * val result = anchorKit.captureAndSubmit(
 *     lifecycleOwner = this,
 *     anchorBadge = AnchorBadgeOptions.Draggable(overlayView = binding.anchorBadgeOverlay)
 * )
 *
 * // Developer hardcodes position (bottom-right quadrant)
 * val result = anchorKit.captureAndSubmit(
 *     lifecycleOwner = this,
 *     anchorBadge = AnchorBadgeOptions.Fixed(normalizedX = 0.80f, normalizedY = 0.80f)
 * )
 * ```
 */
sealed class AnchorBadgeOptions(
    /**
     * The URL encoded in the QR code.
     * Defaults to [AnchorBadgeOptions.DEFAULT_URL].
     */
    val url: String = DEFAULT_URL,
    /**
     * Badge width as a fraction of the captured image width.
     * The badge is always square. Defaults to 0.15 (15% of image width).
     */
    val sizeFraction: Float = DEFAULT_SIZE_FRACTION
) {
    /**
     * The user drags the badge to their preferred position.
     *
     * Drop an [AnchorBadgeOverlayView] into your camera layout (e.g. on top of a
     * `PreviewView`). The view renders a semi-transparent QR silhouette that the user
     * can drag around before taking the photo. Pass the view here — the SDK reads its
     * position at the moment the shutter fires.
     *
     * @param overlayView The [AnchorBadgeOverlayView] already attached to your layout.
     * @param url QR code URL. Defaults to [DEFAULT_URL].
     * @param sizeFraction Badge width as fraction of image width. Defaults to 0.15.
     */
    class Draggable(
        val overlayView: AnchorBadgeOverlayView,
        url: String = DEFAULT_URL,
        sizeFraction: Float = DEFAULT_SIZE_FRACTION
    ) : AnchorBadgeOptions(url, sizeFraction)

    /**
     * The developer specifies position programmatically — no UI required.
     *
     * @param normalizedX Horizontal position of the badge's top-left corner as a
     *   fraction of the image width (0.0 = left edge, 1.0 = right edge).
     * @param normalizedY Vertical position of the badge's top-left corner as a
     *   fraction of the image height (0.0 = top edge, 1.0 = bottom edge).
     * @param url QR code URL. Defaults to [DEFAULT_URL].
     * @param sizeFraction Badge width as fraction of image width. Defaults to 0.15.
     */
    class Fixed(
        val normalizedX: Float,
        val normalizedY: Float,
        url: String = DEFAULT_URL,
        sizeFraction: Float = DEFAULT_SIZE_FRACTION
    ) : AnchorBadgeOptions(url, sizeFraction)

    companion object {
        const val DEFAULT_URL = "https://api.framechain.net"
        const val DEFAULT_SIZE_FRACTION = 0.15f
    }
}
