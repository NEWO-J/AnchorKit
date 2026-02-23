package io.framechain.sdk

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import io.framechain.sdk.models.PortableProof
import io.framechain.sdk.models.VerificationReceipt
import io.framechain.sdk.models.VerificationResult

class Framechain(
    private val context: Context,
    apiKey: String,
    baseUrl: String = "https://api.framechain.net"
) {
    private val client = FramechainClient(apiKey, baseUrl)
    private val photoCapture = PhotoCapture(context)

    /**
     * Capture a photo, sign it with the hardware-backed attestation key, and
     * submit the hash + attestation to the API in one atomic step.
     *
     * This is the **only** public method that produces an attested submission.
     * Accepting an externally-supplied file or pre-computed hash is intentionally
     * not supported: the SDK must be the origin of the image bytes to guarantee
     * the chain of custody required for hardware attestation.
     *
     * IMPORTANT: the calling Activity/Fragment must hold android.permission.CAMERA
     * before invoking this function. The SDK does not request permissions itself.
     *
     * @throws FramechainError.DeviceIntegrityError if the device shows signs of
     *         being rooted or having an unlocked bootloader
     * @throws FramechainError.AttestationError if the device cannot produce hardware attestation
     * @throws FramechainError.NetworkError on connectivity failures
     * @throws FramechainError.ApiError on non-2xx API responses
     */
    suspend fun captureAndSubmit(lifecycleOwner: LifecycleOwner): CaptureResult {
        // Reject devices that show signs of being rooted or having an unlocked
        // bootloader before touching the camera or the network.
        // The server enforces the same policy via hardware attestation; this
        // client-side check provides an early, descriptive error.
        DeviceIntegrity.check()?.let { reason ->
            throw FramechainError.DeviceIntegrityError(
                "Submission refused: $reason. " +
                "Attested submissions require an unmodified device with a locked bootloader."
            )
        }

        val photo = photoCapture.capturePhoto(lifecycleOwner)
        return attestAndSubmit(photo)
    }

    /**
     * Hash the supplied image bytes, sign with the hardware-backed attestation key,
     * and submit to the API.
     *
     * Unlike [captureAndSubmit], the caller supplies the image bytes (e.g. from the
     * system camera intent via [android.provider.MediaStore.ACTION_IMAGE_CAPTURE]).
     * Device integrity and hardware attestation still apply — the submission is
     * cryptographically tied to this device's hardware key — but chain-of-custody
     * for the capture step is the caller's responsibility.
     *
     * @param photoData  Raw image bytes
     * @param timestamp  Capture time in milliseconds since epoch (defaults to now)
     */
    suspend fun submitPhotoBytes(
        photoData: ByteArray,
        timestamp: Long = System.currentTimeMillis()
    ): CaptureResult {
        DeviceIntegrity.check()?.let { reason ->
            throw FramechainError.DeviceIntegrityError(
                "Submission refused: $reason. " +
                "Attested submissions require an unmodified device with a locked bootloader."
            )
        }

        val hash = HashUtils.hashPhoto(photoData)
        val photo = PhotoResult(data = photoData, hash = hash, timestamp = timestamp, width = 0, height = 0)
        return attestAndSubmit(photo)
    }

    private suspend fun attestAndSubmit(photo: PhotoResult): CaptureResult {
        // Fetch a fresh server-issued nonce immediately before signing.
        // The nonce is bound into the signed payload so the attestation cannot
        // be replayed — the server consumes it on first use.
        val challenge = client.fetchChallenge()

        val attestation = EnclaveAttestation.sign(photo.hash, challenge.nonce, context)

        val metadata = mapOf(
            "timestamp" to photo.timestamp.toString(),
            "dimensions" to "${photo.width}x${photo.height}"
        )

        val receipt = client.submitHash(
            hash = photo.hash,
            nonce = challenge.nonce,
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
     * Download a portable, self-contained proof bundle for a hash.
     *
     * Store the returned [PortableProof] in your own database. Once stored,
     * call [verifyLocally] at any future time without needing Framechain's servers.
     *
     * @throws FramechainError.NetworkError on connectivity failures
     * @throws FramechainError.ApiError if the hash has not yet been anchored (HTTP 404/202)
     */
    suspend fun downloadProof(hash: String): PortableProof {
        return client.downloadProof(hash)
    }

    /**
     * Verify a [PortableProof] without contacting the Framechain API.
     *
     * Performs two independent checks:
     * 1. Local SHA-256 Merkle math — no network.
     * 2. Direct Solana JSON-RPC call to confirm the on-chain root.
     *
     * @param proof A bundle previously obtained from [downloadProof].
     * @return [SolanaVerifier.LocalVerificationResult] — check [SolanaVerifier.LocalVerificationResult.valid].
     */
    suspend fun verifyLocally(proof: PortableProof): SolanaVerifier.LocalVerificationResult {
        return SolanaVerifier.verify(proof)
    }

    /**
     * Subscribe an email address to receive a notification email after each
     * nightly batch is archived and anchored to Solana.
     *
     * @throws FramechainError.NetworkError on connectivity failures
     * @throws FramechainError.ApiError on non-2xx responses (401 = bad key, 422 = invalid email)
     */
    suspend fun subscribeToNotifications(email: String): String {
        return client.subscribeToNotifications(email)
    }

    /**
     * Unsubscribe an email address from nightly batch notifications.
     *
     * @throws FramechainError.NetworkError on connectivity failures
     * @throws FramechainError.ApiError on non-2xx responses
     */
    suspend fun unsubscribeFromNotifications(email: String) {
        client.unsubscribeFromNotifications(email)
    }

    /**
     * Hash photo bytes using SHA-256.
     *
     * Internal to the SDK — callers outside this module should use the hash
     * returned by [captureAndSubmit] or compute their own hash if they only
     * need to call [verify].
     */
    internal fun hash(photoData: ByteArray): String {
        return HashUtils.hashPhoto(photoData)
    }
}

data class CaptureResult(
    val photo: PhotoResult,
    val receipt: VerificationReceipt
)
