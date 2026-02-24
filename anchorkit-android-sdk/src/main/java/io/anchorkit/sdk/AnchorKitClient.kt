package io.anchorkit.sdk

import android.util.Base64
import io.anchorkit.sdk.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@Serializable
data class AttestationChallenge(
    val nonce: String,
    val expires_at: Long
)

class AnchorKitClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.anchorkit.net"
) {
    private val json = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // TLS Certificate Pinning
    // -------------------------------------------------------------------------
    //
    // The SDK pins to the SPKI (SubjectPublicKeyInfo) SHA-256 of the production
    // API server's certificate chain.  Pinning is applied programmatically here
    // so it is enforced regardless of the consuming app's network_security_config.xml
    // — a developer cannot override it by adding user-installed CAs or cleartext
    // exceptions.
    //
    // How to regenerate pins when rotating certificates:
    //   1. Run against the current server:
    //        openssl s_client -connect api.anchorkit.net:443 -showcerts </dev/null 2>/dev/null \
    //          | openssl x509 -pubkey -noout \
    //          | openssl pkey -pubin -outform der \
    //          | openssl dgst -sha256 -binary \
    //          | base64
    //   2. Add the NEW pin BEFORE rotating the certificate (include old + new).
    //   3. Release the SDK update with both old + new pins.
    //   4. After the old cert is retired, remove the old pin in a follow-up release.
    //
    private val PRODUCTION_HOST = "api.anchorkit.net"
    private val PRODUCTION_PINS: Set<String> = setOf(
        // Leaf cert SPKI SHA-256 — fetched 2026-02-22 from api.anchorkit.net
        "Y83h/Xv5lbyCuY26cBxCb1oAIdYXtn9J0QxHsEFcLYQ=",
        // Intermediate CA SPKI SHA-256 — fetched 2026-02-22 from api.anchorkit.net
        "kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4="
    )

    /**
     * A custom [SSLContext] whose [X509TrustManager] performs two checks:
     *
     *  1. Normal PKIX chain validation delegated to the system trust manager
     *     (validates cert chain integrity, validity dates, and trusted root CA).
     *     This step is NOT weakened.
     *
     *  2. SPKI pinning against [PRODUCTION_PINS].  At least one certificate in
     *     the server's chain must have a public key whose SHA-256 matches a
     *     known pin.  This prevents MITM by any CA, including system-trusted ones.
     *
     * Applied only when connecting to [PRODUCTION_HOST]; custom baseUrl values
     * used for development/staging go through normal system validation only.
     */
    private val pinnedSslContext: SSLContext by lazy {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val systemTrustManager = factory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

        val pinningTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
                systemTrustManager.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                // Step 1 — standard PKIX chain validation.
                systemTrustManager.checkServerTrusted(chain, authType)

                // Step 2 — SPKI pinning.
                if (PRODUCTION_PINS.isEmpty()) {
                    throw CertificateException(
                        "TLS certificate pins not configured for $PRODUCTION_HOST. " +
                        "Populate PRODUCTION_PINS in AnchorKitClient before shipping."
                    )
                }

                val matchesPin = chain.any { cert ->
                    val spkiHash = Base64.encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded),
                        Base64.NO_WRAP
                    )
                    spkiHash in PRODUCTION_PINS
                }

                if (!matchesPin) {
                    throw CertificateException(
                        "TLS certificate pin mismatch for $PRODUCTION_HOST. " +
                        "Possible MITM attack, or the server certificate was rotated without " +
                        "updating the SDK pins. Contact support@anchorkit.io."
                    )
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                systemTrustManager.acceptedIssuers
        }

        SSLContext.getInstance("TLS").also { ctx ->
            ctx.init(null, arrayOf(pinningTrustManager), null)
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetch a single-use attestation challenge nonce from the server.
     *
     * Must be called immediately before [submitHash]. The nonce is embedded
     * in the data signed by the hardware key so that each submission is
     * bound to a fresh, server-issued challenge — preventing replay of a
     * previously captured attestation.
     *
     * @return [AttestationChallenge] containing the nonce and its expiry time
     * @throws AnchorKitError.NetworkError on I/O failures
     * @throws AnchorKitError.ApiError on non-2xx responses
     */
    suspend fun fetchChallenge(): AttestationChallenge = withContext(Dispatchers.IO) {
        val connection = openConnection("$baseUrl/api/attestation-challenge", "GET")
        try {
            readResponse<AttestationChallenge>(connection)
        } catch (e: AnchorKitError) {
            throw e
        } catch (e: IOException) {
            throw AnchorKitError.NetworkError("Failed to fetch attestation challenge: ${e.message}", e)
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
     * @throws AnchorKitError.NetworkError on I/O failures
     * @throws AnchorKitError.ApiError on non-2xx responses
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
        } catch (e: AnchorKitError) {
            throw e
        } catch (e: IOException) {
            throw AnchorKitError.NetworkError("Network request failed: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Verify a photo hash against the blockchain.
     *
     * @param hash SHA-256 hex hash to look up
     * @return [VerificationResult] with blockchain confirmation status
     * @throws AnchorKitError.NetworkError on I/O failures
     * @throws AnchorKitError.ApiError on non-2xx responses
     */
    suspend fun verifyHash(hash: String): VerificationResult = withContext(Dispatchers.IO) {
        val connection = openConnection("$baseUrl/api/verify-hash/${hash.lowercase()}", "GET")
        try {
            readResponse<VerificationResult>(connection)
        } catch (e: AnchorKitError) {
            throw e
        } catch (e: IOException) {
            throw AnchorKitError.NetworkError("Network request failed: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Subscribe an email address to receive nightly batch notification emails.
     *
     * @param email Email address to subscribe
     * @throws AnchorKitError.NetworkError on I/O failures
     * @throws AnchorKitError.ApiError on non-2xx responses (401 = bad API key, 422 = invalid email)
     */
    suspend fun subscribeToNotifications(email: String): String = withContext(Dispatchers.IO) {
        @Serializable
        data class SubscribeRequest(val email: String, val api_key: String)

        @Serializable
        data class SubscribeResponse(val message: String, val email: String)

        val connection = openConnection("$baseUrl/api/notifications/subscribe", "POST")
        try {
            val body = json.encodeToString(SubscribeRequest(email = email, api_key = apiKey))
            connection.outputStream.use { it.write(body.toByteArray()) }
            readResponse<SubscribeResponse>(connection).message
        } catch (e: AnchorKitError) {
            throw e
        } catch (e: IOException) {
            throw AnchorKitError.NetworkError("Failed to subscribe: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Unsubscribe an email address from nightly batch notifications.
     *
     * @param email Email address to unsubscribe
     * @throws AnchorKitError.NetworkError on I/O failures
     * @throws AnchorKitError.ApiError on non-2xx responses (401 = bad API key, 422 = invalid email)
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
                throw AnchorKitError.ApiError(code, errorBody)
            }
        } catch (e: AnchorKitError) {
            throw e
        } catch (e: IOException) {
            throw AnchorKitError.NetworkError("Failed to unsubscribe: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Download a portable, self-contained proof bundle for a hash.
     *
     * @param hash SHA-256 hex hash to fetch a proof for
     * @return [PortableProof] containing the Merkle proof and on-chain references
     * @throws AnchorKitError.NetworkError on I/O failures
     * @throws AnchorKitError.ApiError on non-2xx responses (404 = not yet anchored)
     */
    suspend fun downloadProof(hash: String): PortableProof = withContext(Dispatchers.IO) {
        val connection = openConnection("$baseUrl/api/proof/${hash.lowercase()}", "GET")
        try {
            readResponse<PortableProof>(connection)
        } catch (e: AnchorKitError) {
            throw e
        } catch (e: IOException) {
            throw AnchorKitError.NetworkError("Network request failed: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun openConnection(urlString: String, method: String): HttpURLConnection {
        val url = URL(urlString)
        return (url.openConnection() as HttpURLConnection).apply {
            // Enforce SPKI certificate pinning for the production API host.
            // Applied here in the SDK so it cannot be overridden by the consuming
            // app's network_security_config.xml or by a developer adding a
            // custom trust store.
            if (this is HttpsURLConnection && url.host == PRODUCTION_HOST) {
                sslSocketFactory = pinnedSslContext.socketFactory
            }
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
        throw AnchorKitError.ApiError(code, errorBody)
    }
}
