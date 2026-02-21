package io.framechain.sdk.models

import kotlinx.serialization.Serializable

@Serializable
data class SubmitRequest(
    val hash: String,
    val api_key: String,
    val enclave_signature: String,
    val device_attestation: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class VerificationResult(
    val hash: String,
    val verified: Boolean,
    val day: String? = null,
    val timestamp: Long? = null,
    val hash_id: Int? = null,
    val merkle_proof: List<List<String>>? = null,
    val merkle_root: String? = null,
    val solana_tx: String? = null,
    val solana_program: String? = null,
    val chain: String? = null,
    val message: String? = null,
    // Hardware attestation — available from hot storage onward
    val attestation_verified: Boolean? = null,
    val cert_fingerprint: String? = null,
    val cert_valid_from: String? = null,
    val cert_valid_until: String? = null,
)
