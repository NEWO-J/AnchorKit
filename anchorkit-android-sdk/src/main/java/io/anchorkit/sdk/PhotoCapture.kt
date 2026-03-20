package io.anchorkit.sdk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PhotoCapture(private val context: Context) {

    /**
     * Capture a single frame from the device camera and return its bytes and
     * SHA-256 hash.
     *
     * The frame is captured entirely in memory — nothing is written to the file
     * system.  This is intentional: keeping the image in memory for the duration
     * of the capture → attest → submit flow means the image bytes that are hashed
     * and submitted are exactly what the camera hardware produced, with no
     * opportunity for modification between capture and hash computation.
     *
     * SECURITY: The hash is computed on the raw JPEG bytes as delivered by the
     * camera hardware.  We deliberately avoid decoding to a Bitmap and re-encoding
     * because JPEG re-encoding is lossy and non-deterministic across Android
     * versions and device OEMs — the same visual image may produce different byte
     * sequences, invalidating the hash.  Re-encoding also strips all EXIF metadata
     * (GPS coordinates, timestamp, device info) that the original capture carries.
     *
     * @param lifecycleOwner Activity or Fragment — used to bind the CameraX lifecycle
     * @param lensFacing Which camera to use (default: back)
     * @return [PhotoResult] containing the raw JPEG bytes, their SHA-256 hash, and
     *         dimensions extracted from EXIF without re-encoding
     */
    suspend fun capturePhoto(
        lifecycleOwner: LifecycleOwner,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK,
        flashMode: Int = ImageCapture.FLASH_MODE_OFF
    ): PhotoResult = suspendCancellableCoroutine { continuation ->

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashMode)
                .build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageCapture
                )

                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            // Read the raw JPEG bytes exactly as the camera delivered them.
                            // CameraX OnImageCapturedCallback always provides planes[0] as the
                            // complete JPEG bitstream when capture mode is JPEG (the default).
                            val buffer = image.planes[0].buffer
                            val photoData = ByteArray(buffer.remaining())
                            buffer.get(photoData)

                            // Hash is computed on the original camera bytes — not a re-encoded
                            // copy.  Any later modification of the bytes would invalidate the hash.
                            val hash = HashUtils.hashPhoto(photoData)

                            // Extract dimensions from EXIF embedded in the JPEG without
                            // decoding the full raster.  This avoids the re-encoding round-trip
                            // while still providing dimension metadata for display purposes.
                            val (width, height) = extractDimensions(photoData)

                            image.close()

                            continuation.resume(
                                PhotoResult(
                                    data = photoData,
                                    hash = hash,
                                    timestamp = System.currentTimeMillis(),
                                    width = width,
                                    height = height
                                )
                            )
                        }

                        override fun onError(exception: ImageCaptureException) {
                            continuation.resumeWithException(exception)
                        }
                    }
                )
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Extract image dimensions from a JPEG byte array by reading the EXIF/SOF
     * headers — without decoding the full raster image.
     *
     * Falls back to (0, 0) if the bytes cannot be parsed as a JPEG with
     * recognizable dimension headers; callers must handle the zero case.
     */
    private fun extractDimensions(jpegBytes: ByteArray): Pair<Int, Int> {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
            val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            if (w > 0 && h > 0) {
                Pair(w, h)
            } else {
                // ExifInterface couldn't find dimension tags — decode just the
                // header (inSampleSize large to avoid loading the full image).
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    inSampleSize = 16
                }
                BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
                Pair(maxOf(opts.outWidth, 0), maxOf(opts.outHeight, 0))
            }
        } catch (_: Exception) {
            Pair(0, 0)
        }
    }
}

/**
 * The result of a camera capture.
 *
 * This class has an [internal] constructor — it can only be produced by the SDK's own
 * [PhotoCapture.capturePhoto] method.  External code (including consuming apps) can read
 * all fields (e.g. save [data] to the gallery or display [hash] to the user), but cannot
 * construct a [PhotoResult] directly.
 */
data class PhotoResult internal constructor(
    val data: ByteArray,
    val hash: String,
    val timestamp: Long,
    val width: Int,
    val height: Int
)
