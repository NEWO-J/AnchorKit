package io.anchorkit.sdk.models

import kotlinx.serialization.Serializable

/**
 * A self-contained proof bundle returned by [io.anchorkit.sdk.AnchorKitClient.downloadProof].
 *
 * Verification without any AnchorKit server:
 *
 * 1. **Local math** — call [io.anchorkit.sdk.SolanaVerifier.verifyMerkleProof] with
 *    [hash], [merkle_proof], and [merkle_root]. This is pure SHA-256; no network needed.
 *
 * 2. **On-chain root lookup** — call [io.anchorkit.sdk.SolanaVerifier.verify], which
 *    fetches [solana_registry_pda] from a public Solana RPC and confirms the on-chain
 *    root matches [merkle_root].
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
    /** Merkle root that commits to all hashes in this day's batch (0x-prefixed hex). */
    val merkle_root: String,
    /**
     * Merkle proof path: list of [sibling_hash, "left"|"right"] steps.
     * Apply each step with SHA-256 to walk from [hash] up to [merkle_root].
     */
    val merkle_proof: List<List<String>>,
    /** Solana program ID that owns the on-chain registry. */
    val solana_program: String,
    /**
     * Base-58 address of the on-chain MerkleRootRegistry account for this day.
     * Pass directly to Solana's getAccountInfo RPC — no PDA derivation needed.
     */
    val solana_registry_pda: String? = null,
    /** Solana transaction signature that wrote this root (for audit trail). */
    val solana_tx: String? = null,
    /** Network the root was anchored on ("devnet" or "mainnet"). */
    val chain: String
)
