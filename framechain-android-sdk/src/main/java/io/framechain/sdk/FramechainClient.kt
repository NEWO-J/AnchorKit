package io.framechain.sdk

import android.content.Context
import io.framechain.sdk.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.net.HttpURLConnection
import java.net.URL

class FramechainClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.framechain.io"
) {
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Submit a photo hash for blockchain verification
     * @param hash SHA-256 hash of the photo
     * @param metadata Optional metadata
     * @return VerificationReceipt
     */
    suspend fun submitHash(
        hash: String,
        metadata: Map<String, String> = emptyMap()
    ): VerificationReceipt = withContext(Dispatchers.IO) {
        val request = SubmitRequest(
            hash = hash.lowercase(),
            api_key = apiKey,
            metadata = metadata + mapOf("platform" to "android")
        )
        
        val url = URL("$baseUrl/api/submit-hash")
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-API-Key", apiKey)
                doOutput = true
                connectTimeout = 30000
                readTimeout = 30000
            }
            
            // Send request
            connection.outputStream.use { output ->
                output.write(json.encodeToString(request).toByteArray())
            }
            
            // Read response
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                json.decodeFromString<VerificationReceipt>(response)
            } else {
                val error = connection.errorStream.bufferedReader().readText()
                throw Exception("API Error ($responseCode): $error")
            }
        } finally {
            connection.disconnect()
        }
    }
    
    /**
     * Verify a photo hash against the blockchain
     * @param hash SHA-256 hash to verify
     * @return VerificationResult
     */
    suspend fun verifyHash(hash: String): VerificationResult = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl/api/verify-hash/${hash.lowercase()}")
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("X-API-Key", apiKey)
                connectTimeout = 30000
                readTimeout = 30000
            }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                json.decodeFromString<VerificationResult>(response)
            } else {
                throw Exception("Verification failed: $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }
}