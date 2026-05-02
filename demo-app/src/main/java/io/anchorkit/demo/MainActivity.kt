package io.anchorkit.demo

import android.Manifest
import android.content.ContentValues
import android.os.Build
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.View
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.anchorkit.demo.databinding.ActivityMainBinding
import io.anchorkit.sdk.AnchorKit
import io.anchorkit.sdk.AnchorKitError
import io.anchorkit.sdk.HashUtils
import io.anchorkit.sdk.models.PortableProof
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var anchorkit: AnchorKit? = null
    private var isTabSwitching = false

    private var pickedPhotoHash: String? = null
    private var lastVerifiedHash: String? = null
    private var lastVerificationResult: io.anchorkit.sdk.models.VerificationResult? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraActivity()
        } else {
            showResult("Camera permission is required to capture photos.")
        }
    }

    private val cameraActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { showCaptureResult(it) }
        } else {
            val error = result.data?.getStringExtra(CameraActivity.EXTRA_ERROR)
            if (!error.isNullOrEmpty()) showResult(error)
        }
    }

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) onPhotoPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load saved API key and initialise AnchorKit if present
        val savedKey = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_API_KEY, "").orEmpty()
        if (savedKey.isNotEmpty()) {
            binding.etApiKey.setText(savedKey)
            initAnchorKit(savedKey)
        }

        val orange = ContextCompat.getColor(this, R.color.primary)
        val strokeStates = arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf())
        val strokeColors = intArrayOf(orange, orange)
        binding.tilEmail.setBoxStrokeColorStateList(ColorStateList(strokeStates, strokeColors))
        binding.tilApiKey.setBoxStrokeColorStateList(ColorStateList(strokeStates, strokeColors))

        binding.btnSaveApiKey.setOnClickListener { onSaveApiKeyClicked() }
        binding.btnCapture.setOnClickListener { onCaptureClicked() }
        binding.btnPickPhoto.setOnClickListener { photoPickerLauncher.launch("image/*") }
        binding.btnVerify.setOnClickListener { onVerifyClicked() }
        binding.btnDownloadProof.setOnClickListener { onDownloadProofClicked() }
        binding.btnSubscribe.setOnClickListener { onSubscribeClicked() }
        binding.btnUnsubscribe.setOnClickListener { onUnsubscribeClicked() }

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (isTabSwitching) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_home -> { showTab(Tab.HOME); true }
                R.id.nav_result -> { showTab(Tab.RESULT); true }
                R.id.nav_settings -> { showTab(Tab.SETTINGS); true }
                else -> false
            }
        }

        val signupMsg = SpannableString("Don’t have a key? Sign up at anchorkit.net")
        val linkStart = signupMsg.indexOf("anchorkit.net")
        val linkEnd = linkStart + "anchorkit.net".length
        signupMsg.setSpan(URLSpan("https://anchorkit.net/signup"), linkStart, linkEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.tvApiKeySignupLink.text = signupMsg
        binding.tvApiKeySignupLink.movementMethod = LinkMovementMethod.getInstance()
        binding.tvApiKeySignupLink.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        updateActionButtons()
    }

    // -------------------------------------------------------------------------
    // API key management
    // -------------------------------------------------------------------------

    private fun initAnchorKit(apiKey: String) {
        anchorkit = AnchorKit(
            context = this,
            apiKey = apiKey,
            baseUrl = BuildConfig.ANCHORKIT_BASE_URL
        )
        updateActionButtons()
    }

    private fun onSaveApiKeyClicked() {
        val key = binding.etApiKey.text?.toString().orEmpty().trim()
        if (key.isEmpty()) {
            binding.tilApiKey.error = "Enter an API key"
            return
        }
        binding.tilApiKey.error = null
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_API_KEY, key).apply()
        initAnchorKit(key)
        Toast.makeText(this, "API key saved", Toast.LENGTH_SHORT).show()
    }

    private fun updateActionButtons() {
        val hasKey = anchorkit != null
        binding.btnCapture.isEnabled = hasKey
        binding.btnPickPhoto.isEnabled = hasKey
        binding.btnVerify.isEnabled = hasKey && pickedPhotoHash != null
        val lockVisibility = if (hasKey) View.GONE else View.VISIBLE
        binding.lockIndicatorCapture.visibility = lockVisibility
        binding.lockIndicatorVerify.visibility = lockVisibility
    }

    // -------------------------------------------------------------------------
    // Button handlers
    // -------------------------------------------------------------------------

    private fun onCaptureClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchCameraActivity()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCameraActivity() {
        val apiKey = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_API_KEY, "").orEmpty()
        val intent = Intent(this, CameraActivity::class.java).apply {
            putExtra(CameraActivity.EXTRA_API_KEY, apiKey)
        }
        cameraActivityLauncher.launch(intent)
    }

    private fun showCaptureResult(data: Intent) {
        val mediaType = data.getStringExtra(CameraActivity.EXTRA_MEDIA_TYPE) ?: CameraActivity.MEDIA_TYPE_PHOTO
        val isVideo = mediaType == CameraActivity.MEDIA_TYPE_VIDEO
        val hash = data.getStringExtra(CameraActivity.EXTRA_HASH) ?: return
        val timestampMs = data.getLongExtra(CameraActivity.EXTRA_TIMESTAMP_MS, 0L)

        val captureTimeFmt = SimpleDateFormat("MMM d, yyyy 'at' h:mm:ss a", Locale.getDefault())
        val serverTimeFmt = SimpleDateFormat("MMM d, yyyy 'at' h:mm:ss a z", Locale.getDefault())
        val capturedAt = captureTimeFmt.format(Date(timestampMs))

        val durationMs = data.getLongExtra(CameraActivity.EXTRA_VIDEO_DURATION_MS, 0L)
        val day = data.getStringExtra(CameraActivity.EXTRA_RECEIPT_DAY)
        val hashId = data.getIntExtra(CameraActivity.EXTRA_RECEIPT_HASH_ID, -1).takeIf { it >= 0 }
        val table = data.getStringExtra(CameraActivity.EXTRA_RECEIPT_TABLE)
        val receiptTs = if (data.hasExtra(CameraActivity.EXTRA_RECEIPT_TIMESTAMP))
            data.getLongExtra(CameraActivity.EXTRA_RECEIPT_TIMESTAMP, 0L) else null
        val attestationVerified = data.getBooleanExtra(CameraActivity.EXTRA_ATTESTATION_VERIFIED, false)
        val certFingerprint = data.getStringExtra(CameraActivity.EXTRA_CERT_FINGERPRINT)
        val certValidFrom = data.getStringExtra(CameraActivity.EXTRA_CERT_VALID_FROM)
        val certValidUntil = data.getStringExtra(CameraActivity.EXTRA_CERT_VALID_UNTIL)
        val receivedAt = if (receiptTs != null && receiptTs > 0)
            serverTimeFmt.format(Date(receiptTs * 1000L))
        else capturedAt

        val fields = mutableListOf<Triple<String, String, Boolean>>()
        if (isVideo) {
            val totalSec = durationMs / 1000
            fields.add(Triple("Duration", "${if (totalSec / 60 > 0) "${totalSec / 60}m " else ""}${totalSec % 60}s", false))
        }
        fields.add(Triple("Hash", hash, true))
        fields.add(Triple("Captured", capturedAt, false))
        fields.add(Triple("Received", receivedAt, false))
        if (day != null) fields.add(Triple("Batch Day", day, false))
        if (hashId != null) fields.add(Triple("Hash ID", hashId.toString(), false))
        if (table != null) fields.add(Triple("Table", table, false))

        showStructuredResult(
            headline = if (isVideo) "Video Submitted" else "Photo Submitted",
            headlineColor = ContextCompat.getColor(this, R.color.success),
            iconRes = R.drawable.ic_check_circle,
            fields = fields,
            attestation = if (attestationVerified) Triple(
                certFingerprint, certValidFrom?.take(10), certValidUntil?.take(10)
            ) else null,
            bootloaderLocked = if (attestationVerified) true else null,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            footnote = "Hash will be anchored to the Solana blockchain tonight."
        )
    }

    private fun onVerifyClicked() {
        val hash = pickedPhotoHash ?: return
        verifyHash(hash)
    }

    private fun onDownloadProofClicked() {
        val hash = lastVerifiedHash ?: return
        val kit = anchorkit ?: return
        binding.btnDownloadProof.isEnabled = false
        lifecycleScope.launch {
            try {
                val proof = withContext(Dispatchers.IO) { kit.downloadProof(hash) }
                val json = portableProofToJson(proof)
                saveProofFile(hash, json)
            } catch (e: AnchorKitError.ApiError) {
                Toast.makeText(this@MainActivity, "Download failed (${e.statusCode}): ${e.body}", Toast.LENGTH_LONG).show()
            } catch (e: AnchorKitError.NetworkError) {
                Toast.makeText(this@MainActivity, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnDownloadProof.isEnabled = true
            }
        }
    }

    private suspend fun saveProofFile(hash: String, json: String) {
        val filename = "anchorkit-proof-${hash.take(12)}.json"
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("Could not create file in Downloads")
                contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Proof saved to Downloads/$filename", Toast.LENGTH_LONG).show()
                }
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val file = File(dir, filename)
                file.writeText(json)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Proof saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun portableProofToJson(proof: PortableProof): String {
        val proofArray = JSONArray()
        for (step in proof.merkle_proof) {
            val stepArray = JSONArray()
            for (v in step) stepArray.put(v)
            proofArray.put(stepArray)
        }
        val computedMerkleRoot = io.anchorkit.sdk.SolanaVerifier.computeMerkleRoot(
            proof.hash, proof.merkle_proof
        )
        val computedRegistryPda = proof.solana_chunk_index?.let {
            io.anchorkit.sdk.SolanaVerifier.derivePda(proof.solana_program, it)
        }
        val verifyResult = lastVerificationResult
        val obj = JSONObject().apply {
            put("generated", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                it.timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date()))
            put("schema_version", proof.schema_version)
            put("hash", proof.hash)
            put("verified", true)
            put("day", proof.day)
            put("timestamp", proof.timestamp)
            put("hash_id", proof.hash_id)
            if (computedMerkleRoot != null) put("merkle_root", computedMerkleRoot)
            put("merkle_proof", proofArray)
            put("solana_program", proof.solana_program)
            if (computedRegistryPda != null) put("solana_registry_pda", computedRegistryPda)
            if (proof.solana_chunk_index != null) put("solana_chunk_index", proof.solana_chunk_index)
            if (proof.solana_tx != null) put("solana_tx", proof.solana_tx)
            put("chain", proof.chain)
            if (verifyResult?.attestation_verified != null) put("attestation_verified", verifyResult.attestation_verified)
            if (!verifyResult?.cert_fingerprint.isNullOrEmpty()) put("cert_fingerprint", verifyResult!!.cert_fingerprint)
            if (!verifyResult?.cert_valid_from.isNullOrEmpty()) put("cert_valid_from", verifyResult!!.cert_valid_from)
            if (!verifyResult?.cert_valid_until.isNullOrEmpty()) put("cert_valid_until", verifyResult!!.cert_valid_until)
        }
        return obj.toString(2)
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

                binding.btnVerify.isEnabled = anchorkit != null

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
        val kit = anchorkit ?: return
        showTab(Tab.RESULT)
        setLoading(true)
        showResult("")

        lifecycleScope.launch {
            try {
                val result = kit.verify(hash)

                val attestation = if (result.attestation_verified == true) Triple(
                    result.cert_fingerprint,
                    result.cert_valid_from?.take(10),
                    result.cert_valid_until?.take(10)
                ) else null

                if (result.verified) {
                    lastVerificationResult = result
                    val tsMillis = result.timestamp?.let { it * 1000L }
                    val tsFormatted = tsMillis?.let {
                        SimpleDateFormat("MMM d, yyyy 'at' h:mm:ss a z", Locale.getDefault()).format(Date(it))
                    } ?: "—"

                    showStructuredResult(
                        headline = "Verified & Anchored on Solana",
                        headlineColor = ContextCompat.getColor(this@MainActivity, R.color.success),
                        iconRes = R.drawable.ic_check_circle,
                        fields = buildList {
                            add(Triple("SHA-256 Hash", result.hash, true))
                            if (!result.day.isNullOrEmpty()) add(Triple("Date Submitted", result.day!!, false))
                            add(Triple("Timestamp", tsFormatted, false))
                            if (result.hash_id != null) add(Triple("Position in Daily Batch", "#${result.hash_id}", false))
                            if (result.attestation_verified != null) add(Triple(
                                "Hardware Attestation",
                                if (result.attestation_verified == true) "Verified (Android Secure Enclave)" else "Not verified",
                                false
                            ))
                            if (!result.cert_fingerprint.isNullOrEmpty()) add(Triple(
                                "Device Cert Fingerprint",
                                "${result.cert_fingerprint!!.take(16)}…${result.cert_fingerprint!!.takeLast(8)}",
                                true
                            ))
                            if (result.cert_valid_from != null && result.cert_valid_until != null) add(Triple(
                                "Cert Validity",
                                "${result.cert_valid_from!!.take(10)} → ${result.cert_valid_until!!.take(10)}",
                                false
                            ))
                            val merkleSize = result.merkle_proof?.size ?: 0
                            if (merkleSize > 0) add(Triple("Merkle Proof", "$merkleSize sibling nodes", false))
                        },
                        attestation = null,
                        explorerUrl = result.explorer_url,
                        solanaTx = result.solana_tx,
                        downloadHash = hash,
                    )

                } else {
                    val hasRecord = !result.day.isNullOrEmpty() || result.hash_id != null

                    if (hasRecord) {
                        val tsMillis = result.timestamp?.let { it * 1000L }
                        val tsFormatted = tsMillis?.let {
                            SimpleDateFormat("MMM d, yyyy 'at' h:mm:ss a z", Locale.getDefault()).format(Date(it))
                        }

                        showStructuredResult(
                            headline = "Recorded — Awaiting Blockchain Anchor",
                            headlineColor = ContextCompat.getColor(this@MainActivity, R.color.warning),
                            iconRes = R.drawable.ic_hourglass,
                            fields = buildList {
                                add(Triple("SHA-256 Hash", result.hash, true))
                                if (!result.day.isNullOrEmpty()) add(Triple("Date Submitted", result.day!!, false))
                                if (result.hash_id != null) add(Triple("Position in Daily Batch", "#${result.hash_id}", false))
                                if (tsFormatted != null) add(Triple("Timestamp", tsFormatted, false))
                                if (result.attestation_verified != null) add(Triple(
                                    "Hardware Attestation",
                                    if (result.attestation_verified == true) "Verified (Android Secure Enclave)" else "Not verified",
                                    false
                                ))
                                if (!result.cert_fingerprint.isNullOrEmpty()) add(Triple(
                                    "Device Cert Fingerprint",
                                    "${result.cert_fingerprint!!.take(16)}…${result.cert_fingerprint!!.takeLast(8)}",
                                    true
                                ))
                                if (result.cert_valid_from != null && result.cert_valid_until != null) add(Triple(
                                    "Cert Validity",
                                    "${result.cert_valid_from!!.take(10)} → ${result.cert_valid_until!!.take(10)}",
                                    false
                                ))
                            },
                            attestation = null,
                            footnote = result.message ?: "This file has been recorded and hardware-verified. The blockchain anchor runs nightly at midnight UTC."
                        )
                    } else {
                        showResult(result.message ?: "Hash not found.")
                    }
                }

            } catch (e: AnchorKitError.ApiError) {

                if (e.statusCode == 503 &&
                    e.body.contains("warm storage archive", ignoreCase = true)
                ) {
                    val dayMatch = Regex("""\d{4}-\d{2}-\d{2}""").find(e.body)?.value

                    showStructuredResult(
                        headline = "Recorded — Processing",
                        headlineColor = ContextCompat.getColor(this@MainActivity, R.color.warning),
                        iconRes = R.drawable.ic_hourglass,
                        fields = buildList {
                            add(Triple("Hash", hash, true))
                            if (dayMatch != null) add(Triple("Batch Day", dayMatch, false))
                        },
                        footnote = "Hardware attestation was captured at submission. Submission details are temporarily unavailable while today's archive is being processed — check back tomorrow for the full record and Solana transaction ID."
                    )
                } else {
                    showResult("API error ${e.statusCode}: ${e.body}")
                }

            } catch (e: AnchorKitError.NetworkError) {
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
        val kit = anchorkit ?: return
        val email = binding.etEmail.text?.toString().orEmpty().trim()
        if (email.isEmpty()) {
            binding.tilEmail.error = "Enter an email address"
            return
        }
        binding.tilEmail.error = null
        showTab(Tab.RESULT)
        setLoading(true)

        lifecycleScope.launch {
            try {
                val message = withContext(Dispatchers.IO) { kit.subscribeToNotifications(email) }
                showResult(message)
            } catch (e: AnchorKitError.ApiError) {
                val detail = runCatching {
                    org.json.JSONObject(e.body).getString("detail")
                }.getOrNull()
                showResult(detail ?: "Could not subscribe (${e.statusCode}): ${e.body}")
            } catch (e: AnchorKitError.NetworkError) {
                showResult("Network error: ${e.message}")
            } catch (e: Exception) {
                showResult("Unexpected error: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun onUnsubscribeClicked() {
        val kit = anchorkit ?: return
        val email = binding.etEmail.text?.toString().orEmpty().trim()
        if (email.isEmpty()) {
            binding.tilEmail.error = "Enter the email address to unsubscribe"
            return
        }
        binding.tilEmail.error = null
        showTab(Tab.RESULT)
        setLoading(true)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { kit.unsubscribeFromNotifications(email) }
                showResult("Unsubscribed. $email will no longer receive batch notifications.")
            } catch (e: AnchorKitError.ApiError) {
                showResult("Could not unsubscribe (${e.statusCode}): ${e.body}")
            } catch (e: AnchorKitError.NetworkError) {
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
        val hasKey = anchorkit != null
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnCapture.isEnabled = !loading && hasKey
        binding.btnPickPhoto.isEnabled = !loading && hasKey
        if (loading) binding.btnVerify.isEnabled = false
        else binding.btnVerify.isEnabled = hasKey && pickedPhotoHash != null
    }

    private fun showResult(text: String) {
        binding.llResultHeader.visibility = View.GONE
        binding.vResultDivider.visibility = View.GONE
        binding.llResultFields.visibility = View.GONE
        binding.llResultAttestation.visibility = View.GONE
        binding.tvResultNote.visibility = View.GONE
        binding.btnDownloadProof.visibility = View.GONE
        binding.ivSolanaLogo.visibility = View.GONE
        lastVerifiedHash = null
        lastVerificationResult = null

        binding.tvResult.text = text
        binding.tvResult.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE

        binding.cardResult.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tvResultEmpty.visibility = if (text.isEmpty()) View.VISIBLE else View.GONE
        if (text.isNotEmpty()) showTab(Tab.RESULT)
    }

    private fun showStructuredResult(
        headline: String,
        headlineColor: Int,
        iconRes: Int,
        fields: List<Triple<String, String, Boolean>>,
        attestation: Triple<String?, String?, String?>? = null,
        bootloaderLocked: Boolean? = null,
        deviceModel: String? = null,
        dimensions: String? = null,
        footnote: String? = null,
        solanaTx: String? = null,
        explorerUrl: String? = null,
        downloadHash: String? = null,
    ) {
        binding.tvResultHeadline.text = headline
        binding.tvResultHeadline.setTextColor(headlineColor)
        binding.ivResultIcon.setImageResource(iconRes)
        binding.ivResultIcon.imageTintList = ColorStateList.valueOf(headlineColor)
        binding.ivSolanaLogo.visibility = if (downloadHash != null) View.VISIBLE else View.GONE
        binding.llResultHeader.visibility = View.VISIBLE
        binding.vResultDivider.visibility = View.VISIBLE

        binding.llResultFields.removeAllViews()
        for ((label, value, mono) in fields) {
            addFieldRow(label, value, mono)
        }
        if (solanaTx != null) {
            val display = "${solanaTx.take(16)}…${solanaTx.takeLast(8)}"
            if (explorerUrl != null) {
                addLinkRow("Solana Transaction", display, explorerUrl)
            } else {
                addFieldRow("Solana Transaction", solanaTx, mono = true)
            }
        }
        binding.llResultFields.visibility = View.VISIBLE

        binding.tvResult.visibility = View.GONE

        if (attestation != null) {
            val (fp, from, until) = attestation
            val details = buildString {
                if (!fp.isNullOrEmpty()) appendLine("Key: ${fp.take(16)}…${fp.takeLast(8)}")
                if (from != null && until != null) append("Valid: $from → $until")
            }.trim()
            if (details.isNotEmpty()) {
                binding.tvResultAttestDetails.text = details
                binding.tvResultAttestDetails.visibility = View.VISIBLE
            } else {
                binding.tvResultAttestDetails.visibility = View.GONE
            }

            if (bootloaderLocked != null) {
                binding.tvResultBootloaderLabel.visibility = View.VISIBLE
                binding.tvResultBootloaderValue.text = if (bootloaderLocked) "Locked" else "Unlocked"
                binding.tvResultBootloaderValue.setTextColor(
                    ContextCompat.getColor(
                        this,
                        if (bootloaderLocked) R.color.success else R.color.error
                    )
                )
                binding.tvResultBootloaderValue.visibility = View.VISIBLE
            } else {
                binding.tvResultBootloaderLabel.visibility = View.GONE
                binding.tvResultBootloaderValue.visibility = View.GONE
            }

            binding.llResultAttestation.visibility = View.VISIBLE
        } else {
            binding.llResultAttestation.visibility = View.GONE
        }

        if (deviceModel != null) {
            binding.tvResultMetaDevice.text = deviceModel
            if (dimensions != null) {
                binding.tvResultMetaDimsLabel.visibility = View.VISIBLE
                binding.tvResultMetaDims.text = dimensions
                binding.tvResultMetaDims.visibility = View.VISIBLE
            } else {
                binding.tvResultMetaDimsLabel.visibility = View.GONE
                binding.tvResultMetaDims.visibility = View.GONE
            }
            binding.llResultMetadata.visibility = View.VISIBLE
        } else {
            binding.llResultMetadata.visibility = View.GONE
        }

        if (footnote != null) {
            binding.tvResultNote.text = footnote
            binding.tvResultNote.visibility = View.VISIBLE
        } else {
            binding.tvResultNote.visibility = View.GONE
        }

        lastVerifiedHash = downloadHash
        binding.btnDownloadProof.visibility = if (downloadHash != null) View.VISIBLE else View.GONE

        binding.cardResult.visibility = View.VISIBLE
        binding.tvResultEmpty.visibility = View.GONE
        showTab(Tab.RESULT)
    }

    private fun addLinkRow(label: String, displayValue: String, url: String) {
        val fields = binding.llResultFields
        val density = resources.displayMetrics.density

        if (fields.childCount > 0) {
            val spacer = View(this)
            spacer.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (10 * density).toInt()
            )
            fields.addView(spacer)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val labelTv = TextView(this).apply {
            text = label.uppercase(Locale.getDefault())
            textSize = 10f
            typeface = Typeface.DEFAULT
            letterSpacing = 0.08f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, (2 * density).toInt()) }
        }

        val valueTv = TextView(this).apply {
            text = displayValue
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            }
        }

        row.addView(labelTv)
        row.addView(valueTv)
        fields.addView(row)
    }

    private fun addFieldRow(label: String, value: String, mono: Boolean = false) {
        val fields = binding.llResultFields
        val density = resources.displayMetrics.density

        if (fields.childCount > 0) {
            val spacer = View(this)
            spacer.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (10 * density).toInt()
            )
            fields.addView(spacer)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val labelTv = TextView(this).apply {
            text = label.uppercase(Locale.getDefault())
            textSize = 10f
            typeface = Typeface.DEFAULT
            letterSpacing = 0.08f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, (2 * density).toInt()) }
        }

        val valueTv = TextView(this).apply {
            text = value
            textSize = 13f
            typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(labelTv)
        row.addView(valueTv)
        fields.addView(row)
    }

    // -------------------------------------------------------------------------
    // Tab navigation
    // -------------------------------------------------------------------------

    private enum class Tab { HOME, RESULT, SETTINGS }

    private fun showTab(tab: Tab) {
        binding.tabHome.visibility = if (tab == Tab.HOME) View.VISIBLE else View.GONE
        binding.tabResult.visibility = if (tab == Tab.RESULT) View.VISIBLE else View.GONE
        binding.tabSettings.visibility = if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE
        val itemId = when (tab) {
            Tab.HOME -> R.id.nav_home
            Tab.RESULT -> R.id.nav_result
            Tab.SETTINGS -> R.id.nav_settings
        }
        if (binding.bottomNav.selectedItemId != itemId) {
            isTabSwitching = true
            binding.bottomNav.selectedItemId = itemId
            isTabSwitching = false
        }
    }

    companion object {
        private const val PREFS_NAME = "anchorkit_prefs"
        private const val PREF_API_KEY = "api_key"
    }
}
