package io.framechain.sdk

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
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
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
     * @param lifecycleOwner Activity or Fragment — used to bind the CameraX lifecycle
     * @param lensFacing Which camera to use (default: back)
     * @return [PhotoResult] containing the raw JPEG bytes and their SHA-256 hash
     */
    suspend fun capturePhoto(
        lifecycleOwner: LifecycleOwner,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK
    ): PhotoResult = suspendCancellableCoroutine { continuation ->

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
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
                            val bitmap = imageProxyToBitmap(image)
                            val photoData = bitmapToByteArray(bitmap)
                            val hash = HashUtils.hashPhoto(photoData)

                            image.close()

                            continuation.resume(
                                PhotoResult(
                                    data = photoData,
                                    hash = hash,
                                    timestamp = System.currentTimeMillis(),
                                    width = bitmap.width,
                                    height = bitmap.height
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

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun bitmapToByteArray(bitmap: Bitmap, quality: Int = 95): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}

data class PhotoResult(
    val data: ByteArray,
    val hash: String,
    val timestamp: Long,
    val width: Int,
    val height: Int
)
