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
    val day: String,
    val timestamp: Long,
    val hash_id: Int,
    val verified: Boolean
)
