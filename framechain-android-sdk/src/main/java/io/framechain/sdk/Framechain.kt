package io.framechain.sdk

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import io.framechain.sdk.models.VerificationReceipt
import io.framechain.sdk.models.VerificationResult

class Framechain(
    private val context: Context,
    apiKey: String,
    baseUrl: String = "https://api.framechain.io"
) {
    private val client = FramechainClient(apiKey, baseUrl)
    private val photoCapture = PhotoCapture(context)
    
    /**
     * Capture and submit photo in one step
     * @param lifecycleOwner Activity or Fragment
     * @return CaptureResult with photo and receipt
     */
    suspend fun captureAndSubmit(
        lifecycleOwner: LifecycleOwner
    ): CaptureResult {
        val photo = photoCapture.capturePhoto(lifecycleOwner)
        
        val metadata = mapOf(
            "timestamp" to photo.timestamp.toString(),
            "dimensions" to "${photo.width}x${photo.height}"
        )
        
        val receipt = client.submitHash(photo.hash, metadata)
        
        return CaptureResult(photo, receipt)
    }
    
    /**
     * Submit existing photo file
     * @param file Photo file
     * @return CaptureResult
     */
    suspend fun submitFile(file: java.io.File): CaptureResult {
        val photo = photoCapture.hashFile(file)
        
        val metadata = mapOf(
            "timestamp" to photo.timestamp.toString(),
            "filename" to file.name
        )
        
        val receipt = client.submitHash(photo.hash, metadata)
        
        return CaptureResult(photo, receipt)
    }
    
    /**
     * Verify a photo hash
     * @param hash Hash to verify
     * @return VerificationResult
     */
    suspend fun verify(hash: String): VerificationResult {
        return client.verifyHash(hash)
    }
    
    /**
     * Hash a photo without submitting
     * @param photoData Photo bytes
     * @return SHA-256 hash
     */
    fun hash(photoData: ByteArray): String {
        return HashUtils.hashPhoto(photoData)
    }
}

data class CaptureResult(
    val photo: PhotoResult,
    val receipt: VerificationReceipt
)