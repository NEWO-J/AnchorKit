package io.anchorkit.sdk

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import io.anchorkit.sdk.models.PortableProof
import io.anchorkit.sdk.models.VerificationReceipt
import io.anchorkit.sdk.models.VerificationResult

class AnchorKit(
    private val context: Context,
    apiKey: String,
    baseUrl: String = "https://api.anchorkit.net"
) {
    private val client = AnchorKitClient(apiKey, baseUrl)
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
     * @throws AnchorKitError.DeviceIntegrityError if the device shows signs of
     *         being rooted or having an unlocked bootloader
     * @throws AnchorKitError.AttestationError if the device cannot produce hardware attestation
     * @throws AnchorKitError.NetworkError on connectivity failures
     * @throws AnchorKitError.ApiError on non-2xx API responses
     */
    suspend fun captureAndSubmit(lifecycleOwner: LifecycleOwner): CaptureResult {
        // Reject devices that show signs of being rooted or having an unlocked
        // bootloader before touching the camera or the network.
        // The server enforces the same policy via hardware attestation; this
        // client-side check provides an early, descriptive error.
        DeviceIntegrity.check()?.let { reason ->
            throw AnchorKitError.DeviceIntegrityError(
                "Submission refused: $reason. " +
                "Attested submissions require an unmodified device with a locked bootloader."
            )
        }

        val photo = photoCapture.capturePhoto(lifecycleOwner)

        // Fetch a fresh server-issued nonce immediately before signing.
        // The nonce is bound into the signed payload so the attestation cannot
        // be replayed — the server consumes it on first use.
        val challenge = client.fetchChallenge()

        val metadata = mapOf(
            "timestamp" to photo.timestamp.toString(),
            "dimensions" to "${photo.width}x${photo.height}"
        )

        // Sign: hash + nonce + metadata_hash.
        // Including a SHA-256 of the sorted metadata key=value pairs in the
        // signed payload prevents an in-transit attacker from swapping the
        // metadata (timestamp, dimensions) without invalidating the attestation.
        // The server must verify the signature over the same three-part string.
        val attestation = EnclaveAttestation.sign(photo.hash, challenge.nonce, metadata, context)

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
     * @throws AnchorKitError.NetworkError on connectivity failures
     * @throws AnchorKitError.ApiError on non-2xx API responses
     */
    suspend fun verify(hash: String): VerificationResult {
        return client.verifyHash(hash)
    }

    /**
     * Download a portable, self-contained proof bundle for a hash.
     *
     * Store the returned [PortableProof] in your own database. Once stored,
     * call [verifyLocally] at any future time without needing AnchorKit's servers.
     *
     * @throws AnchorKitError.NetworkError on connectivity failures
     * @throws AnchorKitError.ApiError if the hash has not yet been anchored (HTTP 404/202)
     */
    suspend fun downloadProof(hash: String): PortableProof {
        return client.downloadProof(hash)
    }

    /**
     * Verify a [PortableProof] without contacting the AnchorKit API.
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
     * @throws AnchorKitError.NetworkError on connectivity failures
     * @throws AnchorKitError.ApiError on non-2xx responses (401 = bad key, 422 = invalid email)
     */
    suspend fun subscribeToNotifications(email: String): String {
        return client.subscribeToNotifications(email)
    }

    /**
     * Unsubscribe an email address from nightly batch notifications.
     *
     * @throws AnchorKitError.NetworkError on connectivity failures
     * @throws AnchorKitError.ApiError on non-2xx responses
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
