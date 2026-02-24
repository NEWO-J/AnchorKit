package io.anchorkit.sdk

import android.util.Base64
import io.anchorkit.sdk.models.PortableProof
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Verifies a [PortableProof] without trusting the AnchorKit API.
 *
 * Two independent checks are performed:
 *
 * 1. **Local Merkle math** — walks the proof path with SHA-256 and confirms
 *    the recomputed root equals [PortableProof.merkle_root].  No network call.
 *
 * 2. **On-chain root lookup** — calls a public Solana JSON-RPC endpoint to
 *    read the [PortableProof.solana_registry_pda] account and finds the
 *    Merkle root recorded for [PortableProof.day]. Compares it to
 *    [PortableProof.merkle_root].
 *
 * No AnchorKit server is contacted during either step.
 *
 * ### On-chain account layout (borsh, after 8-byte Anchor discriminator)
 * ```
 * u16   chunk_index
 * u16   entries_count
 * [u8; 32]  authority
 * u8    has_next_chunk (0 or 1)
 * [u8; 32]  next_chunk  (present only when has_next_chunk == 1)
 * entries_count × {
 *   [u8; 32]  merkle_root
 *   u32       date_len
 *   [u8; date_len]  date (UTF-8, e.g. "2025-11-07")
 *   i64       timestamp
 * }
 * ```
 */
object SolanaVerifier {

    private val SOLANA_RPCS = mapOf(
        "devnet"   to "https://api.devnet.solana.com",
        "mainnet"  to "https://api.mainnet-beta.solana.com",
        "localnet" to "http://localhost:8899",
    )

    private val lenientJson = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    data class LocalVerificationResult(
        val valid: Boolean,
        /** Human-readable explanation when [valid] is false. */
        val reason: String? = null,
    )

    /**
     * Full three-step verification:
     *
     * 1. Local Merkle proof math (no network).
     * 2. PDA integrity check — re-derive the expected registry account address
     *    from [PortableProof.solana_program] + [PortableProof.solana_chunk_index]
     *    and confirm it matches [PortableProof.solana_registry_pda].  This
     *    prevents a tampered proof bundle from redirecting the on-chain lookup to
     *    an attacker-controlled Solana account.
     * 3. Direct Solana on-chain root lookup.
     *
     * @param proof   A bundle previously obtained from [AnchorKitClient.downloadProof].
     * @param network Override the network; defaults to [PortableProof.chain].
     */
    suspend fun verify(
        proof: PortableProof,
        network: String = proof.chain,
    ): LocalVerificationResult = withContext(Dispatchers.IO) {

        // Step 1 — Merkle proof math (pure local, no network)
        if (!verifyMerkleProof(proof.hash, proof.merkle_proof, proof.merkle_root)) {
            return@withContext LocalVerificationResult(
                valid = false,
                reason = "Merkle proof math failed — the proof is inconsistent with the claimed root",
            )
        }

        // Step 2 — PDA integrity: re-derive the expected account address from the
        //           program ID and chunk index, then compare to the proof's claim.
        //
        //           Seed: ["merkle_registry", chunk_index as little-endian u16]
        //           This is the same derivation used by the AnchorKit Solana program.
        if (proof.solana_registry_pda.isNullOrBlank()) {
            return@withContext LocalVerificationResult(
                valid = false,
                reason = "Proof bundle does not include solana_registry_pda — cannot perform on-chain lookup",
            )
        }

        if (proof.solana_chunk_index == null) {
            return@withContext LocalVerificationResult(
                valid = false,
                reason = "Proof bundle does not include solana_chunk_index — cannot verify PDA integrity",
            )
        }

        val expectedPda = derivePda(proof.solana_program, proof.solana_chunk_index)
        if (expectedPda == null) {
            return@withContext LocalVerificationResult(
                valid = false,
                reason = "Failed to derive expected PDA from program ${proof.solana_program} " +
                    "and chunk index ${proof.solana_chunk_index}",
            )
        }

        if (expectedPda != proof.solana_registry_pda) {
            return@withContext LocalVerificationResult(
                valid = false,
                reason = "PDA mismatch — proof claims ${proof.solana_registry_pda} but the " +
                    "expected address derived from program + chunk index is $expectedPda. " +
                    "The proof bundle may have been tampered with.",
            )
        }

        // Step 3 — On-chain root lookup
        val rpcUrl = SOLANA_RPCS[network]
            ?: return@withContext LocalVerificationResult(
                valid = false,
                reason = "Unknown network: $network",
            )

        val chainRoot = fetchRootForDate(
            pda = proof.solana_registry_pda,
            date = proof.day,
            rpcUrl = rpcUrl,
        ) ?: return@withContext LocalVerificationResult(
            valid = false,
            reason = "Merkle root for ${proof.day} not found in on-chain registry account ${proof.solana_registry_pda}",
        )

        // Step 4 — Compare
        val proofRoot = proof.merkle_root.removePrefix("0x").lowercase()
        val onChainRoot = chainRoot.removePrefix("0x").lowercase()

        if (proofRoot != onChainRoot) {
            return@withContext LocalVerificationResult(
                valid = false,
                reason = "Root mismatch — proof claims $proofRoot but on-chain registry has $onChainRoot",
            )
        }

        LocalVerificationResult(valid = true)
    }

    /**
     * Derive the on-chain PDA for a MerkleRootRegistry chunk.
     *
     * Seed: ``["merkle_registry", chunkIndex.toLeBytes()]``
     * Program: [programId]
     *
     * This mirrors the derivation in the Anchor program:
     * ```rust
     * seeds = [b"merkle_registry", chunk_index.to_le_bytes().as_ref()], bump
     * ```
     *
     * The derivation uses the standard Solana find_program_address algorithm:
     * try bump 255 down to 0; for each bump compute
     * SHA256("ProgramDerivedAddress" || seeds... || bump_byte || program_id)
     * and return the first result that is NOT a valid Ed25519 point.
     *
     * @return Base-58 PDA string, or null if derivation fails.
     */
    fun derivePda(programId: String, chunkIndex: Int): String? {
        return try {
            val programIdBytes = base58Decode(programId)
            val chunkIndexBytes = byteArrayOf(
                (chunkIndex and 0xFF).toByte(),
                ((chunkIndex shr 8) and 0xFF).toByte()
            )
            val seeds = listOf("merkle_registry".toByteArray(Charsets.UTF_8), chunkIndexBytes)
            findProgramAddress(seeds, programIdBytes)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Solana find_program_address: iterate bump from 255 down to 0, computing
     * SHA256("ProgramDerivedAddress" || seed0 || seed1 || ... || bump || programId)
     * for each bump, and return the first result that is not a valid curve point.
     */
    private fun findProgramAddress(seeds: List<ByteArray>, programId: ByteArray): String? {
        val marker = "ProgramDerivedAddress".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        for (bump in 255 downTo 0) {
            digest.reset()
            for (seed in seeds) digest.update(seed)
            digest.update(byteArrayOf(bump.toByte()))
            digest.update(programId)
            digest.update(marker)
            val candidate = digest.digest()
            if (!isOnEd25519Curve(candidate)) {
                return base58Encode(candidate)
            }
        }
        return null  // No valid PDA found (should not happen in practice)
    }

    /**
     * Check whether a 32-byte value is a valid compressed Ed25519 y-coordinate
     * (i.e. lies on the curve).  PDAs must NOT be on the curve so that no
     * private key corresponds to them.
     *
     * Uses the standard Ed25519 field equation: x² = (y² - 1) / (d·y² + 1) mod p
     * and checks whether x² has a square root mod p.  If it does, the point is
     * on the curve and cannot be a PDA.
     */
    private fun isOnEd25519Curve(point: ByteArray): Boolean {
        if (point.size != 32) return false
        // Ed25519 field prime p = 2^255 - 19
        val p = java.math.BigInteger.TWO.pow(255).subtract(java.math.BigInteger.valueOf(19))
        val d = java.math.BigInteger(
            "-4513249062541557337682894930092624173785641285191125241628941591882900924598840740",
            10
        )
        // Decode little-endian y coordinate (clear sign bit)
        val yBytes = point.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        val y = java.math.BigInteger(1, yBytes.reversedArray())
        val y2 = y.multiply(y).mod(p)
        val num = y2.subtract(java.math.BigInteger.ONE).mod(p)
        val den = d.multiply(y2).add(java.math.BigInteger.ONE).mod(p)
        val denInv = den.modPow(p.subtract(java.math.BigInteger.TWO), p)
        val x2 = num.multiply(denInv).mod(p)
        // x² has a square root mod p iff x²^((p-1)/2) ≡ 1 (mod p)
        val exp = p.subtract(java.math.BigInteger.ONE).divide(java.math.BigInteger.TWO)
        return x2.modPow(exp, p) == java.math.BigInteger.ONE
    }

    /**
     * Verify the Merkle proof path locally — no network required.
     *
     * Applies the same domain-separation scheme used by the server-side Rust
     * Merkle tree (RFC 6962 style):
     *
     *   leaf node:     SHA256(0x00 || leaf_utf8_bytes)
     *   internal node: SHA256(0x01 || left_utf8_bytes || right_utf8_bytes)
     *
     * Domain separation prevents a second-preimage attack where a crafted
     * internal-node hash collides with a leaf hash, enabling proof forgery.
     *
     * @param leafHash SHA-256 hex hash of the image (lowercase, no 0x prefix).
     * @param proof    List of `[sibling_hash, "left"|"right"]` steps.
     * @param root     Expected Merkle root (may carry "0x" prefix).
     */
    fun verifyMerkleProof(
        leafHash: String,
        proof: List<List<String>>,
        root: String,
    ): Boolean {
        // Apply leaf domain tag first (matches hash_leaf in lib.rs).
        var current = hashLeaf(leafHash.lowercase())
        for (step in proof) {
            if (step.size != 2) return false
            val sibling = step[0]
            val position = step[1]
            // Apply internal node domain tag (matches hash_node in lib.rs).
            current = if (position == "left") hashNode(sibling, current) else hashNode(current, sibling)
        }
        return current == root.lowercase().removePrefix("0x")
    }

    // -------------------------------------------------------------------------
    // On-chain fetch
    // -------------------------------------------------------------------------

    /**
     * Fetch the on-chain Merkle root for [date] from the Solana registry account at [pda].
     *
     * @return "0x"-prefixed hex root, or null if the account is unreachable or the
     *         date is not present in the account.
     */
    private fun fetchRootForDate(pda: String, date: String, rpcUrl: String): String? {
        val accountData = getAccountInfo(pda, rpcUrl) ?: return null
        return parseRegistryForDate(accountData, date)
    }

    /**
     * Call `getAccountInfo` on the Solana JSON-RPC and return the raw account data bytes,
     * or null on any error.
     */
    private fun getAccountInfo(pubkey: String, rpcUrl: String): ByteArray? {
        val body = """{"jsonrpc":"2.0","id":1,"method":"getAccountInfo",""" +
            """"params":["$pubkey",{"encoding":"base64","commitment":"confirmed"}]}"""

        val conn = (URL(rpcUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
        }

        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) return null
            val responseText = conn.inputStream.bufferedReader().readText()
            val root = lenientJson.parseToJsonElement(responseText).jsonObject
            val dataArr = root["result"]
                ?.jsonObject?.get("value")
                ?.jsonObject?.get("data")
                ?.jsonArray ?: return null
            Base64.decode(dataArr[0].jsonPrimitive.content, Base64.DEFAULT)
        } catch (_: IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Parse a borsh-encoded [MerkleRootRegistry] account and return the
     * "0x"-prefixed hex Merkle root for [date], or null if not found.
     *
     * See the class-level KDoc for the exact binary layout.
     */
    private fun parseRegistryForDate(data: ByteArray, date: String): String? {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Skip 8-byte Anchor discriminator
        if (buf.remaining() < 8) return null
        buf.position(8)

        if (buf.remaining() < 4) return null
        @Suppress("UNUSED_VARIABLE") val chunkIndex = buf.short.toInt() and 0xFFFF
        val entriesCount = buf.short.toInt() and 0xFFFF

        // Skip 32-byte authority pubkey
        if (buf.remaining() < 32) return null
        buf.position(buf.position() + 32)

        // Option<Pubkey> next_chunk: 1 tag byte + optional 32 bytes
        if (buf.remaining() < 1) return null
        val hasNext = buf.get().toInt() and 0xFF
        if (hasNext == 1) {
            if (buf.remaining() < 32) return null
            buf.position(buf.position() + 32)
        }

        for (i in 0 until entriesCount) {
            if (buf.remaining() < 32) return null
            val rootBytes = ByteArray(32).also { buf.get(it) }

            if (buf.remaining() < 4) return null
            val dateLen = buf.int
            if (dateLen < 0 || dateLen > 64 || buf.remaining() < dateLen) return null
            val entryDate = ByteArray(dateLen).also { buf.get(it) }.toString(Charsets.UTF_8)

            if (buf.remaining() < 8) return null
            buf.long  // timestamp — not needed here

            if (entryDate == date) {
                return "0x" + rootBytes.toHex()
            }
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Crypto helpers
    // -------------------------------------------------------------------------

    /** SHA256(0x00 || leaf) — domain-separated leaf hash (RFC 6962 style). */
    private fun hashLeaf(leaf: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(0x00.toByte())
        digest.update(leaf.toByteArray(Charsets.UTF_8))
        return digest.digest().toHex()
    }

    /** SHA256(0x01 || left || right) — domain-separated internal node hash. */
    private fun hashNode(left: String, right: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(0x01.toByte())
        digest.update(left.toByteArray(Charsets.UTF_8))
        digest.update(right.toByteArray(Charsets.UTF_8))
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // -------------------------------------------------------------------------
    // Base-58 codec (Bitcoin/Solana alphabet)
    // -------------------------------------------------------------------------

    private val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun base58Encode(input: ByteArray): String {
        var num = java.math.BigInteger(1, input)
        val zero = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(58)
        val sb = StringBuilder()
        while (num > zero) {
            val (quotient, remainder) = num.divideAndRemainder(base)
            sb.append(BASE58_ALPHABET[remainder.toInt()])
            num = quotient
        }
        // Leading zero bytes → '1' characters
        for (b in input) {
            if (b == 0.toByte()) sb.append('1') else break
        }
        return sb.reverse().toString()
    }

    private fun base58Decode(input: String): ByteArray {
        var num = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(58)
        for (ch in input) {
            val idx = BASE58_ALPHABET.indexOf(ch)
            require(idx >= 0) { "Invalid base-58 character: $ch" }
            num = num.multiply(base).add(java.math.BigInteger.valueOf(idx.toLong()))
        }
        var bytes = num.toByteArray()
        // Remove sign byte if present
        if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes = bytes.copyOfRange(1, bytes.size)
        // Prepend zero bytes for leading '1' characters
        val leadingZeros = input.takeWhile { it == '1' }.length
        return ByteArray(leadingZeros) + bytes
    }
}
