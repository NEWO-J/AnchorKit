package io.anchorkit.sdk.models

import kotlinx.serialization.Serializable

@Serializable
data class VerificationReceipt(
    val hash: String,
    val day: String,
    val hash_id: Int,
    val table: String,
    /** Server-recorded epoch seconds of when the hash was received and stored. */
    val timestamp: Long? = null,
    // Hardware attestation metadata returned by the server after verification
    val attestation_verified: Boolean? = null,
    val cert_fingerprint: String? = null,
    val cert_valid_from: String? = null,
    val cert_valid_until: String? = null,
)
