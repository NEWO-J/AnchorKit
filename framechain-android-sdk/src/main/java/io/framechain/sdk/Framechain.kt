package io.framechain.sdk

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import io.framechain.sdk.models.PortableProof
import io.framechain.sdk.models.VerificationReceipt
import io.framechain.sdk.models.VerificationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Framechain(
    private val context: Context,
    apiKey: String,
    baseUrl: String = "https://api.framechain.net"
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

        // Fetch a fresh server-issued nonce immediately before signing.
        val challenge = client.fetchChallenge()

        val attestation = EnclaveAttestation.sign(photo.hash, challenge.nonce, context)

        val metadata = mapOf(
            "timestamp" to photo.timestamp.toString(),
            "filename" to file.name
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
