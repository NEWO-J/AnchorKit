package io.framechain.sdk

/**
 * Typed errors returned by the Framechain SDK.
 *
 * Catch [FramechainError] to handle all SDK errors, or catch individual
 * subclasses for specific failure modes.
 */
sealed class FramechainError(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** The device could not generate or use a hardware-backed attestation key. */
    class AttestationError(message: String, cause: Throwable? = null) :
        FramechainError(message, cause)

    /** A network request failed (timeout, no connectivity, TLS error, etc.). */
    class NetworkError(message: String, cause: Throwable? = null) :
        FramechainError(message, cause)

    /** The server returned a non-2xx HTTP response. */
    class ApiError(
        val statusCode: Int,
        val body: String,
        cause: Throwable? = null
    ) : FramechainError("API error $statusCode: $body", cause)

    /** The camera captured a frame but hashing or encoding it failed. */
    class HashError(message: String, cause: Throwable? = null) :
        FramechainError(message, cause)

    /**
     * The device failed a client-side integrity check (rooted device, unlocked
     * bootloader, or test-key build detected).
     *
     * Note: these client-side checks are defense-in-depth and can be bypassed by
     * a sufficiently motivated attacker. The server-side hardware attestation
     * (verifiedBootState = Verified in the Android Keystore extension) is the
     * authoritative, cryptographically enforced gate — a jailbroken device will
     * also be rejected there.
     */
    class DeviceIntegrityError(message: String, cause: Throwable? = null) :
        FramechainError(message, cause)
}
