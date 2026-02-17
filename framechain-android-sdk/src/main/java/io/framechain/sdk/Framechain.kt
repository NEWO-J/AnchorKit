package io.framechain.sdk

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import io.framechain.sdk.models.VerificationReceipt
import io.framechain.sdk.models.VerificationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Framechain(
    private val context: Context,
    apiKey: String,
    baseUrl: String = "https://api.framechain.io"
) {
    private val client = FramechainClient(apiKey, baseUrl)
    private val photoCapture = PhotoCapture(context)

    /**
     * Capture a photo, sign it with the hardware-backed attestation key, and
     * submit the hash + attestation to the API in one step.
     *
     * IMPORTANT: the calling Activity/Fragment must hold android.permission.CAMERA
     * before invoking this function. The SDK does not request permissions itself.
     *
     * @throws FramechainError.AttestationError if the device cannot produce hardware attestation
     * @throws FramechainError.NetworkError on connectivity failures
     * @throws FramechainError.ApiError on non-2xx API responses
     */
    suspend fun captureAndSubmit(lifecycleOwner: LifecycleOwner): CaptureResult {
        val photo = photoCapture.capturePhoto(lifecycleOwner)

        val attestation = EnclaveAttestation.sign(photo.hash.toByteArray(Charsets.UTF_8), context)

        val metadata = mapOf(
            "timestamp" to photo.timestamp.toString(),
            "dimensions" to "${photo.width}x${photo.height}"
        )

        val receipt = client.submitHash(
            hash = photo.hash,
            enclaveSignature = attestation.enclaveSignature,
            deviceAttestation = attestation.deviceAttestation,
            metadata = metadata
        )

        return CaptureResult(photo, receipt)
    }

    /**
     * Hash an existing file, sign with the hardware-backed attestation key, and
     * submit to the API.
     *
     * File reading is dispatched to [Dispatchers.IO] so it is safe to call from
     * any coroutine context.
     *
     * @throws FramechainError.AttestationError if hardware attestation is unavailable
     * @throws FramechainError.NetworkError on connectivity failures
     * @throws FramechainError.ApiError on non-2xx API responses
     */
    suspend fun submitFile(file: java.io.File): CaptureResult {
        // hashFile reads the file and decodes a Bitmap — both are blocking I/O.
        val photo = withContext(Dispatchers.IO) { photoCapture.hashFile(file) }

        val attestation = EnclaveAttestation.sign(photo.hash.toByteArray(Charsets.UTF_8), context)

        val metadata = mapOf(
            "timestamp" to photo.timestamp.toString(),
            "filename" to file.name
        )

        val receipt = client.submitHash(
            hash = photo.hash,
            enclaveSignature = attestation.enclaveSignature,
            deviceAttestation = attestation.deviceAttestation,
            metadata = metadata
        )

        return CaptureResult(photo, receipt)
    }

    /**
     * Verify whether a hash has been anchored to the blockchain.
     *
     * @throws FramechainError.NetworkError on connectivity failures
     * @throws FramechainError.ApiError on non-2xx API responses
     */
    suspend fun verify(hash: String): VerificationResult {
        return client.verifyHash(hash)
    }

    /**
     * Hash photo bytes without submitting. Useful for pre-computing the hash
     * before deciding whether to submit.
     */
    fun hash(photoData: ByteArray): String {
        return HashUtils.hashPhoto(photoData)
    }
}

data class CaptureResult(
    val photo: PhotoResult,
    val receipt: VerificationReceipt
)
