package io.framechain.sdk.models

import kotlinx.serialization.Serializable

@Serializable
data class VerificationReceipt(
    val hash: String,
    val day: String,
    val hash_id: Int,
    val table: String
)
