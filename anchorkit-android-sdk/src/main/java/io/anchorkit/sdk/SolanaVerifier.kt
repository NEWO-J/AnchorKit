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
     * Full two-step verification:
     *
     * 1. Local Merkle proof math (no network).
     * 2. Direct Solana on-chain root lookup (uses [PortableProof.solana_registry_pda]).
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

        // Step 2 — On-chain root lookup
        val rpcUrl = SOLANA_RPCS[network]
            ?: return@withContext LocalVerificationResult(
                valid = false,
                reason = "Unknown network: $network",
            )

        if (proof.solana_registry_pda.isNullOrBlank()) {
            return@withContext LocalVerificationResult(
                valid = false,
                reason = "Proof bundle does not include solana_registry_pda — cannot perform on-chain lookup",
            )
        }

        val chainRoot = fetchRootForDate(
            pda = proof.solana_registry_pda,
            date = proof.day,
            rpcUrl = rpcUrl,
        ) ?: return@withContext LocalVerificationResult(
            valid = false,
            reason = "Merkle root for ${proof.day} not found in on-chain registry account ${proof.solana_registry_pda}",
        )

        // Step 3 — Compare
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
     * Verify the Merkle proof path locally — no network required.
     *
     * Starting from [leafHash], each step of [proof] concatenates the current
     * hash with its sibling (order determined by "left"/"right") and SHA-256s
     * the result. The final value must equal [root].
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
        var current = leafHash.lowercase()
        for (step in proof) {
            if (step.size != 2) return false
            val sibling = step[0]
            val position = step[1]
            val combined = if (position == "left") "$sibling$current" else "$current$sibling"
            current = sha256Hex(combined)
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

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
