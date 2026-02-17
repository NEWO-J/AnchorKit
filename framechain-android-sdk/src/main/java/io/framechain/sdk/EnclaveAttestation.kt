package io.framechain.sdk

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import org.json.JSONArray
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Handles hardware-backed key attestation using the Android Keystore.
 *
 * On devices with a StrongBox (dedicated security chip), the key is generated
 * and stored there. On all other devices it falls back to the Trusted Execution
 * Environment (TEE), which is still hardware-backed but shares silicon with the
 * main processor.
 *
 * The key is generated once per app installation. The attestation certificate
 * chain rooted at a Google CA proves to the server that the private key never
 * left hardware.
 */
object EnclaveAttestation {

    private const val KEY_ALIAS = "framechain_attestation_key"
    private const val PREFS_NAME = "framechain_prefs"
    private const val PREF_CHALLENGE = "attestation_challenge"
    private const val ALGORITHM = "SHA256withECDSA"

    data class AttestationResult(
        /** Base64-encoded DER ECDSA signature over the submitted data. */
        val enclaveSignature: String,
        /**
         * Base64-encoded JSON array of Base64-encoded DER X.509 certificates.
         * Index 0 is the leaf (device) cert; the chain terminates at a Google root.
         */
        val deviceAttestation: String
    )

    /**
     * Sign [data] with the hardware-backed key and return both the signature
     * and the attestation certificate chain.
     *
     * Generates the key on first call; subsequent calls reuse the existing key.
     */
    fun sign(data: ByteArray, context: Context): AttestationResult {
        ensureKeyExists(context)

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val certChain = keyStore.getCertificateChain(KEY_ALIAS)
            ?: throw FramechainError.AttestationError("Certificate chain unavailable — key may not be hardware-backed")

        val signatureBytes = Signature.getInstance(ALGORITHM).run {
            initSign(privateKey)
            update(data)
            sign()
        }

        // Encode cert chain as a JSON array of Base64 DER strings, then Base64 the whole thing
        val certArray = JSONArray().apply {
            certChain.forEach { cert ->
                put(Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
            }
        }
        val attestationB64 = Base64.encodeToString(
            certArray.toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        return AttestationResult(
            enclaveSignature = Base64.encodeToString(signatureBytes, Base64.NO_WRAP),
            deviceAttestation = attestationB64
        )
    }

    /** Delete the key (e.g. on sign-out). A new key will be generated on next [sign] call. */
    fun deleteKey() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun ensureKeyExists(context: Context) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            generateKey(context)
        }
    }

    private fun generateKey(context: Context) {
        val challenge = getOrCreateChallenge(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                buildKey(challenge, strongBox = true)
                return
            } catch (_: StrongBoxUnavailableException) {
                // Device has no StrongBox; fall through to TEE
            } catch (_: Exception) {
                // Some OEMs throw other exceptions for missing StrongBox
            }
        }

        buildKey(challenge, strongBox = false)
    }

    private fun buildKey(challenge: ByteArray, strongBox: Boolean) {
        val specBuilder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)

        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            specBuilder.setIsStrongBoxBacked(true)
        }

        KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore"
        ).apply {
            initialize(specBuilder.build())
            generateKeyPair()
        }
    }

    private fun getOrCreateChallenge(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(PREF_CHALLENGE, null)
        if (existing != null) {
            return Base64.decode(existing, Base64.NO_WRAP)
        }
        val challenge = UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)
        prefs.edit().putString(
            PREF_CHALLENGE,
            Base64.encodeToString(challenge, Base64.NO_WRAP)
        ).apply()
        return challenge
    }
}
