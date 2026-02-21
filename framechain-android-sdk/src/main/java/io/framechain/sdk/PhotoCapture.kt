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
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PhotoCapture(private val context: Context) {
    
    /**
     * Capture photo from camera
     * @param lifecycleOwner Activity or Fragment
     * @return PhotoResult with image data and hash
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
                
                // Capture image
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
    
    /**
     * Hash an existing image file.
     *
     * The timestamp in the returned [PhotoResult] reflects the actual moment
     * the photo was taken, read from EXIF [ExifInterface.TAG_DATETIME_ORIGINAL]
     * (or [ExifInterface.TAG_DATETIME] as a fallback). If neither tag is present
     * the current wall-clock time is used instead.
     *
     * @param file Image file (JPEG, PNG, or any format supported by ExifInterface)
     * @return PhotoResult with image data, hash, and capture timestamp
     */
    fun hashFile(file: File): PhotoResult {
        val photoData = file.readBytes()
        val hash = HashUtils.hashPhoto(photoData)

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)

        // Prefer the EXIF capture time so gallery photos show when they were taken,
        // not when they were submitted. Fall back to System.currentTimeMillis() for
        // formats that carry no EXIF metadata (PNG, screenshots, etc.).
        val timestamp = readExifTimestamp(file) ?: System.currentTimeMillis()

        return PhotoResult(
            data = photoData,
            hash = hash,
            timestamp = timestamp,
            width = options.outWidth,
            height = options.outHeight
        )
    }

    /**
     * Returns the epoch-millisecond timestamp stored in the EXIF header of [file],
     * or null if no recognised date tag is present or parsing fails.
     */
    private fun readExifTimestamp(file: File): Long? {
        val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        return try {
            val exif = ExifInterface(file.absolutePath)
            val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            dateStr?.let { exifDateFormat.parse(it)?.time }
        } catch (_: Exception) {
            null
        }
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