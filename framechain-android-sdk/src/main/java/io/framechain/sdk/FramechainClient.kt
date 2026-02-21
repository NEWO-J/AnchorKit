package io.framechain.sdk

import io.framechain.sdk.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class AttestationChallenge(
    val nonce: String,
    val expires_at: Long
)



class FramechainClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.framechain.net"
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetch a single-use attestation challenge nonce from the server.
     *
     * Must be called immediately before [submitHash]. The nonce is embedded
     * in the data signed by the hardware key so that each submission is
     * bound to a fresh, server-issued challenge — preventing replay of a
     * previously captured attestation.
     *
     * @return [AttestationChallenge] containing the nonce and its expiry time
     * @throws FramechainError.NetworkError on I/O failures
     * @throws FramechainError.ApiError on non-2xx responses
     */
    suspend fun fetchChallenge(): AttestationChallenge = withContext(Dispatchers.IO) {
        val connection = openConnection("$baseUrl/api/attestation-challenge", "GET")
        try {
            readResponse<AttestationChallenge>(connection)
        } catch (e: FramechainError) {
            throw e
        } catch (e: IOException) {
            throw FramechainError.NetworkError("Failed to fetch attestation challenge: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Submit a photo hash for blockchain verification.
     *
     * @param hash SHA-256 hex hash of the photo (64 characters, lowercase)
     * @param nonce Single-use challenge nonce obtained from [fetchChallenge]
     * @param enclaveSignature Base64-encoded ECDSA signature over hash:nonce from the hardware key
     * @param deviceAttestation Base64-encoded certificate chain proving hardware origin
     * @param metadata Optional caller-supplied key/value pairs stored alongside the hash
     * @return [VerificationReceipt] confirming the hash was stored
     * @throws FramechainError.NetworkError on I/O failures
     * @throws FramechainError.ApiError on non-2xx responses
     */
    suspend fun submitHash(
        hash: String,
        nonce: String,
        enclaveSignature: String,
        deviceAttestation: String,
        metadata: Map<String, String> = emptyMap()
    ): VerificationReceipt = withContext(Dispatchers.IO) {
        val request = SubmitRequest(
            hash = hash.lowercase(),
            api_key = apiKey,
            nonce = nonce,
            enclave_signature = enclaveSignature,
            device_attestation = deviceAttestation,
            metadata = metadata + mapOf("platform" to "android")
        )

        val connection = openConnection("$baseUrl/api/submit-hash", "POST")
        try {
            connection.outputStream.use { it.write(json.encodeToString(request).toByteArray()) }
            readResponse<VerificationReceipt>(connection)
        } catch (e: FramechainError) {
            throw e
        } catch (e: IOException) {
            throw FramechainError.NetworkError("Network request failed: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Verify a photo hash against the blockchain.
     *
     * @param hash SHA-256 hex hash to look up
     * @return [VerificationResult] with blockchain confirmation status
     * @throws FramechainError.NetworkError on I/O failures
     * @throws FramechainError.ApiError on non-2xx responses
     */
    suspend fun verifyHash(hash: String): VerificationResult = withContext(Dispatchers.IO) {
        val connection = openConnection("$baseUrl/api/verify-hash/${hash.lowercase()}", "GET")
        try {
            readResponse<VerificationResult>(connection)
        } catch (e: FramechainError) {
            throw e
        } catch (e: IOException) {
            throw FramechainError.NetworkError("Network request failed: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Subscribe an email address to receive nightly batch notification emails.
     *
     * Emails include the batch date, number of hashes archived, Merkle root,
     * and Solana transaction ID. Every email contains a one-click unsubscribe link.
     *
     * @param email Email address to subscribe
     * @throws FramechainError.NetworkError on I/O failures
     * @throws FramechainError.ApiError on non-2xx responses (401 = bad API key, 422 = invalid email)
     */
    suspend fun subscribeToNotifications(email: String): Unit = withContext(Dispatchers.IO) {
        @Serializable
        data class SubscribeRequest(val email: String, val api_key: String)

        val connection = openConnection("$baseUrl/api/notifications/subscribe", "POST")
        try {
            val body = json.encodeToString(SubscribeRequest(email = email, api_key = apiKey))
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.readText() ?: ""
                }.getOrDefault("")
                throw FramechainError.ApiError(code, errorBody)
            }
        } catch (e: FramechainError) {
            throw e
        } catch (e: IOException) {
            throw FramechainError.NetworkError("Failed to subscribe: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Unsubscribe an email address from nightly batch notifications.
     *
     * @param email Email address to unsubscribe
     * @throws FramechainError.NetworkError on I/O failures
     * @throws FramechainError.ApiError on non-2xx responses (401 = bad API key, 422 = invalid email)
     */
    suspend fun unsubscribeFromNotifications(email: String): Unit = withContext(Dispatchers.IO) {
        @Serializable
        data class UnsubscribeRequest(val email: String, val api_key: String)

        val connection = openConnection("$baseUrl/api/notifications/unsubscribe-api", "POST")
        try {
            val body = json.encodeToString(UnsubscribeRequest(email = email, api_key = apiKey))
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.readText() ?: ""
                }.getOrDefault("")
                throw FramechainError.ApiError(code, errorBody)
            }
        } catch (e: FramechainError) {
            throw e
        } catch (e: IOException) {
            throw FramechainError.NetworkError("Failed to unsubscribe: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Download a portable, self-contained proof bundle for a hash.
     *
     * The returned [PortableProof] can be stored locally and later verified
     * without any Framechain server via [SolanaVerifier.verify].
     *
     * @param hash SHA-256 hex hash to fetch a proof for
     * @return [PortableProof] containing the Merkle proof and on-chain references
     * @throws FramechainError.NetworkError on I/O failures
     * @throws FramechainError.ApiError on non-2xx responses (404 = not yet anchored)
     */
    suspend fun downloadProof(hash: String): PortableProof = withContext(Dispatchers.IO) {
        val connection = openConnection("$baseUrl/api/proof/${hash.lowercase()}", "GET")
        try {
            readResponse<PortableProof>(connection)
        } catch (e: FramechainError) {
            throw e
        } catch (e: IOException) {
            throw FramechainError.NetworkError("Network request failed: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun openConnection(urlString: String, method: String): HttpURLConnection {
        return (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-API-Key", apiKey)
            connectTimeout = 30_000
            readTimeout = 30_000
            if (method == "POST") doOutput = true
        }
    }

    private inline fun <reified T> readResponse(connection: HttpURLConnection): T {
        val code = connection.responseCode
        if (code in 200..299) {
            val body = connection.inputStream.bufferedReader().readText()
            return json.decodeFromString(body)
        }
        val errorBody = runCatching {
            connection.errorStream?.bufferedReader()?.readText() ?: ""
        }.getOrDefault("")
        throw FramechainError.ApiError(code, errorBody)
    }
}
