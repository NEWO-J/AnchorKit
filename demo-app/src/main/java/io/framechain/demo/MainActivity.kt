package io.framechain.demo

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.view.View
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.framechain.demo.databinding.ActivityMainBinding
import io.framechain.sdk.Framechain
import io.framechain.sdk.FramechainError
import io.framechain.sdk.HashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var framechain: Framechain

    // Hash computed from the last picked photo — non-null once a photo is selected.
    private var pickedPhotoHash: String? = null

    // Requests CAMERA permission then triggers captureAndSubmit.
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            triggerCaptureAndSubmit()
        } else {
            showResult("Camera permission is required to capture photos.")
        }
    }

    // Image picker launcher — opens the system gallery for verification lookups.
    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) onPhotoPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        framechain = Framechain(
            context = this,
            apiKey = BuildConfig.FRAMECHAIN_API_KEY,
            baseUrl = BuildConfig.FRAMECHAIN_BASE_URL
        )

        // Always show the orange outline on the email field, not just when focused.
        val orange = ContextCompat.getColor(this, R.color.primary)
        val strokeStates = arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf())
        val strokeColors = intArrayOf(orange, orange)
        binding.tilEmail.setBoxStrokeColorStateList(ColorStateList(strokeStates, strokeColors))

        binding.btnCapture.setOnClickListener { onCaptureClicked() }
        binding.btnPickPhoto.setOnClickListener { photoPickerLauncher.launch("image/*") }
        binding.btnVerify.setOnClickListener { onVerifyClicked() }
        binding.btnSubscribe.setOnClickListener { onSubscribeClicked() }
        binding.btnUnsubscribe.setOnClickListener { onUnsubscribeClicked() }
    }

    // -------------------------------------------------------------------------
    // Button handlers
    // -------------------------------------------------------------------------

    private fun onCaptureClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            triggerCaptureAndSubmit()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Invoke [Framechain.captureAndSubmit] directly.
     *
     * The SDK uses CameraX to capture a single frame in memory — no temp file
     * is written — then immediately hashes it, signs with the hardware-backed
     * key, and submits to the API.  The entire capture → attest → submit flow
     * is an atomic SDK operation; the app never touches the raw image bytes.
     */
    private fun triggerCaptureAndSubmit() {
        setLoading(true)
        showResult("")

        lifecycleScope.launch {
            try {
                val result = framechain.captureAndSubmit(this@MainActivity)

                val captureTimeFmt = SimpleDateFormat("MMM d, yyyy 'at' h:mm:ss a", Locale.getDefault())
                val capturedAt = captureTimeFmt.format(Date(result.photo.timestamp))

                val serverTs = result.receipt.timestamp
                val serverTimeFmt = SimpleDateFormat("MMM d, yyyy 'at' h:mm:ss a z", Locale.getDefault())
                val receivedAt = if (serverTs != null && serverTs > 0)
                    serverTimeFmt.format(Date(serverTs * 1000L))
                else capturedAt

                showResult(
                    buildString {
                        appendLine("Photo submitted successfully!")
                        appendLine()
                        appendLine("Hash:      ${result.photo.hash}")
                        appendLine("Captured:  $capturedAt")
                        appendLine("Received:  $receivedAt")
                        appendLine("Batch day: ${result.receipt.day}")
                        appendLine("Hash ID:   ${result.receipt.hash_id}")
                        appendLine("Table:     ${result.receipt.table}")

                        if (result.receipt.attestation_verified == true) {
                            appendLine()
                            appendLine("Hardware Attestation: VERIFIED")
                            val fp = result.receipt.cert_fingerprint
                            if (!fp.isNullOrEmpty()) {
                                appendLine("Key fingerprint: ${fp.take(16)}...${fp.takeLast(8)}")
                            }
                            val from = result.receipt.cert_valid_from?.take(10)
                            val until = result.receipt.cert_valid_until?.take(10)
                            if (from != null && until != null) {
                                appendLine("Cert valid: $from → $until")
                            }
                        }

                        appendLine()
                        appendLine("The hash will be anchored to the Solana blockchain tonight.")
                    }
                )

            } catch (e: FramechainError.DeviceIntegrityError) {
                showResult("Device integrity check failed: ${e.message}")
            } catch (e: FramechainError.AttestationError) {
                showResult("Attestation error: ${e.message}\n\nThis device may not support hardware-backed keys.")
            } catch (e: FramechainError.NetworkError) {
                showResult("Network error: ${e.message}\n\nCheck your internet connection.")
            } catch (e: FramechainError.ApiError) {
                showResult("API error ${e.statusCode}: ${e.body}")
            } catch (e: Exception) {
                showResult("Unexpected error: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun onVerifyClicked() {
        val hash = pickedPhotoHash ?: return
        verifyHash(hash)
    }

    // -------------------------------------------------------------------------
    // Photo picker result (verify flow only — no submission)
    // -------------------------------------------------------------------------

    private fun onPhotoPicked(uri: Uri) {
        setLoading(true)
        showResult("")

        lifecycleScope.launch {
            try {
                val hash = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.let { HashUtils.hashPhoto(it) }
                        ?: throw IllegalStateException("Could not read selected image")
                }

                pickedPhotoHash = hash

                binding.ivPickedPhoto.setImageURI(uri)
                binding.ivPickedPhoto.visibility = View.VISIBLE

                binding.tvPickedHash.text = getString(R.string.hash_label) + "\n" + hash
                binding.tvPickedHash.visibility = View.VISIBLE

                binding.btnVerify.isEnabled = true

            } catch (e: Exception) {
                showResult("Could not read photo: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Verify
    // -------------------------------------------------------------------------

    private fun verifyHash(hash: String) {
        setLoading(true)
        showResult("")

        lifecycleScope.launch {
            try {
                val result = framechain.verify(hash)

                fun StringBuilder.appendAttestationBlock() {
                    if (result.attestation_verified == true) {
                        appendLine()
                        appendLine("Hardware Attestation: VERIFIED")

                        val fp = result.cert_fingerprint
                        if (!fp.isNullOrEmpty()) {
                            appendLine("Key fingerprint: ${fp.take(16)}...${fp.takeLast(8)}")
                        }

                        val from = result.cert_valid_from?.take(10)
                        val until = result.cert_valid_until?.take(10)
                        if (from != null && until != null) {
                            appendLine("Cert valid: $from → $until")
                        }
                    }
                }

                if (result.verified) {

                    val tsMillis = result.timestamp?.let { it * 1000L }
                    val tsFormatted = tsMillis?.let {
                        SimpleDateFormat(
                            "MMM d, yyyy 'at' h:mm:ss a z",
                            Locale.getDefault()
                        ).format(Date(it))
                    } ?: "—"

                    showResult(
                        buildString {
                            appendLine("Hash VERIFIED on blockchain!")
                            appendLine()
                            appendLine("Hash:      ${result.hash}")
                            appendLine("Timestamp: $tsFormatted")
                            appendLine("Batch day: ${result.day}")
                            appendLine("Hash ID:   ${result.hash_id}")

                            if (result.solana_tx != null) {
                                appendLine()
                                appendLine("Solana TX: ${result.solana_tx}")
                            }

                            appendAttestationBlock()
                        }
                    )

                } else {

                    val hasRecord = !result.day.isNullOrEmpty() || result.hash_id != null

                    showResult(
                        buildString {
                            if (hasRecord) {
                                appendLine("Photo recorded — not yet anchored to blockchain.")
                                appendLine()
                                appendLine("Hash:      ${result.hash}")

                                if (!result.day.isNullOrEmpty()) {
                                    appendLine("Batch day: ${result.day}")
                                }

                                if (result.hash_id != null) {
                                    appendLine("Hash ID:   ${result.hash_id}")
                                }

                                val tsMillis = result.timestamp?.let { it * 1000L }
                                if (tsMillis != null) {
                                    val tsFormatted = SimpleDateFormat(
                                        "MMM d, yyyy 'at' h:mm:ss a z",
                                        Locale.getDefault()
                                    ).format(Date(tsMillis))
                                    appendLine("Recorded:  $tsFormatted")
                                }

                                appendAttestationBlock()
                                appendLine()
                                appendLine("Blockchain anchor is pending — check back tomorrow.")
                            } else {
                                appendLine(result.message ?: "Hash not found.")
                            }
                        }
                    )
                }

            } catch (e: FramechainError.ApiError) {

                if (e.statusCode == 503 &&
                    e.body.contains("warm storage archive", ignoreCase = true)
                ) {
                    val dayMatch = Regex("""\d{4}-\d{2}-\d{2}""").find(e.body)?.value

                    showResult(
                        buildString {
                            appendLine("Photo recorded — not yet anchored to blockchain.")
                            appendLine()
                            appendLine("Hash:      $hash")

                            if (dayMatch != null) {
                                appendLine("Batch day: $dayMatch")
                            }

                            appendLine()
                            appendLine("Hardware attestation was captured at submission.")
                            appendLine("Submission details are temporarily unavailable while")
                            appendLine("today's archive is being processed — check back tomorrow")
                            appendLine("for the full record and Solana transaction ID.")
                        }
                    )
                } else {
                    showResult("API error ${e.statusCode}: ${e.body}")
                }

            } catch (e: FramechainError.NetworkError) {
                showResult("Network error: ${e.message}")
            } catch (e: Exception) {
                showResult("Unexpected error: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification subscription
    // -------------------------------------------------------------------------

    private fun onSubscribeClicked() {
        val email = binding.etEmail.text?.toString().orEmpty().trim()
        if (email.isEmpty()) {
            binding.tilEmail.error = "Enter an email address"
            return
        }
        binding.tilEmail.error = null
        setLoading(true)

        lifecycleScope.launch {
            try {
                val message = withContext(Dispatchers.IO) { framechain.subscribeToNotifications(email) }
                showResult(message)
            } catch (e: FramechainError.ApiError) {
                val detail = runCatching {
                    org.json.JSONObject(e.body).getString("detail")
                }.getOrNull()
                showResult(detail ?: "Could not subscribe (${e.statusCode}): ${e.body}")
            } catch (e: FramechainError.NetworkError) {
                showResult("Network error: ${e.message}")
            } catch (e: Exception) {
                showResult("Unexpected error: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun onUnsubscribeClicked() {
        val email = binding.etEmail.text?.toString().orEmpty().trim()
        if (email.isEmpty()) {
            binding.tilEmail.error = "Enter the email address to unsubscribe"
            return
        }
        binding.tilEmail.error = null
        setLoading(true)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { framechain.unsubscribeFromNotifications(email) }
                showResult("Unsubscribed. $email will no longer receive batch notifications.")
            } catch (e: FramechainError.ApiError) {
                showResult("Could not unsubscribe (${e.statusCode}): ${e.body}")
            } catch (e: FramechainError.NetworkError) {
                showResult("Network error: ${e.message}")
            } catch (e: Exception) {
                showResult("Unexpected error: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnCapture.isEnabled = !loading
        binding.btnPickPhoto.isEnabled = !loading
        if (loading) binding.btnVerify.isEnabled = false
        else binding.btnVerify.isEnabled = pickedPhotoHash != null
    }

    private fun showResult(text: String) {
        binding.tvResult.text = text
        binding.cardResult.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
    }
}
