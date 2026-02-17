package io.framechain.sdk

import io.framechain.sdk.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class FramechainClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.framechain.io"
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Submit a photo hash for blockchain verification.
     *
     * @param hash SHA-256 hex hash of the photo (64 characters, lowercase)
     * @param enclaveSignature Base64-encoded ECDSA signature from the hardware key
     * @param deviceAttestation Base64-encoded certificate chain proving hardware origin
     * @param metadata Optional caller-supplied key/value pairs stored alongside the hash
     * @return [VerificationReceipt] confirming the hash was stored
     * @throws FramechainError.NetworkError on I/O failures
     * @throws FramechainError.ApiError on non-2xx responses
     */
    suspend fun submitHash(
        hash: String,
        enclaveSignature: String,
        deviceAttestation: String,
        metadata: Map<String, String> = emptyMap()
    ): VerificationReceipt = withContext(Dispatchers.IO) {
        val request = SubmitRequest(
            hash = hash.lowercase(),
            api_key = apiKey,
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
