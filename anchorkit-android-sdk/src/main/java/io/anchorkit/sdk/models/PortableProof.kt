package io.anchorkit.sdk.models

import kotlinx.serialization.Serializable

/**
 * A self-contained proof bundle returned by [io.anchorkit.sdk.AnchorKitClient.downloadProof].
 *
 * Verification without any AnchorKit server:
 *
 * 1. **Derive PDA** — call [io.anchorkit.sdk.SolanaVerifier.derivePda] with
 *    [solana_program] and [solana_chunk_index] to compute the on-chain registry address.
 *
 * 2. **On-chain root lookup** — call [io.anchorkit.sdk.SolanaVerifier.verify], which
 *    fetches the derived PDA from a public Solana RPC and reads the root recorded for [day].
 *
 * 3. **Local Merkle math** — walk [merkle_proof] from [hash] with SHA-256 and confirm
 *    the computed root matches the on-chain root. No network needed for this step.
 *
 * Store this bundle in your own database. Once obtained, it is permanently verifiable
 * even if AnchorKit's servers are unavailable.
 */
@Serializable
data class PortableProof(
    val schema_version: Int = 1,
    /** SHA-256 hex hash of the image (64 lowercase hex characters). */
    val hash: String,
    /** Calendar date the hash was batched (YYYY-MM-DD). */
    val day: String,
    /** Unix timestamp (seconds) when the hash was submitted. */
    val timestamp: Long,
    /** Position of this hash in the daily batch (0-indexed). */
    val hash_id: Int,
    /**
     * Merkle proof path: list of [sibling_hash, "left"|"right"] steps.
     * Walk from [hash] up to the Merkle root using SHA-256 at each step.
     * The root is not stored — it is computed and compared directly to the on-chain value.
     */
    val merkle_proof: List<List<String>>,
    /** Solana program ID that owns the on-chain registry. */
    val solana_program: String,
    /**
     * On-chain chunk index used to derive the MerkleRootRegistry PDA.
     *
     * The PDA seed is ``["merkle_registry", chunk_index_le_u16]``.
     * [io.anchorkit.sdk.SolanaVerifier] derives the registry address from this index
     * and [solana_program] — no stored PDA address is needed or trusted.
     */
    val solana_chunk_index: Int? = null,
    /** Solana transaction signature that wrote this root (for audit trail). */
    val solana_tx: String? = null,
    /** Network the root was anchored on ("devnet" or "mainnet"). */
    val chain: String
)
