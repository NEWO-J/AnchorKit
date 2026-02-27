package io.anchorkit.demo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.anchorkit.demo.databinding.ActivityMainBinding
import io.anchorkit.sdk.AnchorKit
import io.anchorkit.sdk.AnchorKitError
import io.anchorkit.sdk.HashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var anchorkit: AnchorKit
    private var isTabSwitching = false

    // Hash computed from the last picked photo — non-null once a photo is selected.
    private var pickedPhotoHash: String? = null

    // Requests CAMERA permission then opens CameraActivity.
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraActivity()
        } else {
            showResult("Camera permission is required to capture photos.")
        }
    }

    // Receives the capture result (hash + receipt fields) back from CameraActivity.
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

        anchorkit = AnchorKit(
            context = this,
            apiKey = BuildConfig.ANCHORKIT_API_KEY,
            baseUrl = BuildConfig.ANCHORKIT_BASE_URL
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

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (isTabSwitching) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_home -> { showTab(Tab.HOME); true }
                R.id.nav_result -> { showTab(Tab.RESULT); true }
                R.id.nav_settings -> { showTab(Tab.SETTINGS); true }
                else -> false
            }
        }
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
        cameraActivityLauncher.launch(Intent(this, CameraActivity::class.java))
    }

    private fun showCaptureResult(data: Intent) {
        val mediaType = data.getStringExtra(CameraActivity.EXTRA_MEDIA_TYPE) ?: CameraActivity.MEDIA_TYPE_PHOTO
        val isVideo = mediaType == CameraActivity.MEDIA_TYPE_VIDEO
        val hash = data.getStringExtra(CameraActivity.EXTRA_HASH) ?: return
        val timestampMs = data.getLongExtra(CameraActivity.EXTRA_TIMESTAMP_MS, 0L)

        val captureTimeFmt = SimpleDateFormat("MMM d, yyyy 'at' h:mm:ss a", Locale.getDefault())
        val serverTimeFmt = SimpleDateFormat("MMM d, yyyy 'at' h:mm:ss a z", Locale.getDefault())
        val capturedAt = captureTimeFmt.format(Date(timestampMs))

        if (!isVideo && data.getBooleanExtra(CameraActivity.EXTRA_SUBMISSION_PENDING, false)) {
            // ── Photo fast-path: camera closed immediately, submit from here ──
            val width = data.getIntExtra(CameraActivity.EXTRA_PHOTO_WIDTH, 0)
            val height = data.getIntExtra(CameraActivity.EXTRA_PHOTO_HEIGHT, 0)

            showTab(Tab.RESULT)
            setLoading(true)
            showResult("")

            lifecycleScope.launch {
                try {
                    val receipt = anchorkit.submitPhoto(hash, timestampMs, width, height)

                    val receiptTs = receipt.timestamp
                    val receivedAt = if (receiptTs != null && receiptTs > 0)
                        serverTimeFmt.format(Date(receiptTs * 1000L))
                    else capturedAt

                    showStructuredResult(
                        headline = "Photo Submitted",
                        headlineColor = ContextCompat.getColor(this@MainActivity, R.color.success),
                        iconRes = R.drawable.ic_check_circle,
                        fields = buildList {
                            add(Triple("Hash", hash, true))
                            add(Triple("Captured", capturedAt, false))
                            add(Triple("Received", receivedAt, false))
                            add(Triple("Batch Day", receipt.day, false))
                            add(Triple("Hash ID", receipt.hash_id.toString(), false))
                            add(Triple("Table", receipt.table, false))
                        },
                        attestation = if (receipt.attestation_verified == true) Triple(
                            receipt.cert_fingerprint,
                            receipt.cert_valid_from?.take(10),
                            receipt.cert_valid_until?.take(10)
                        ) else null,
                        footnote = "Hash will be anchored to the Solana blockchain tonight."
                    )
                } catch (e: AnchorKitError.AttestationError) {
                    showResult("Attestation error: ${e.message}\n\nThis device may not support hardware-backed keys.")
                } catch (e: AnchorKitError.ApiError) {
                    showResult("Submission failed — API error ${e.statusCode}: ${e.body}")
                } catch (e: AnchorKitError.NetworkError) {
                    showResult("Submission failed — Network error: ${e.message}")
                } catch (e: Exception) {
                    showResult("Submission failed — Unexpected error: ${e.message}")
                } finally {
                    setLoading(false)
                }
            }
            return
        }

        // ── Video (or legacy photo): receipt data is already in the Intent ──
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
            footnote = "Hash will be anchored to the Solana blockchain tonight."
        )
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
        showTab(Tab.RESULT)
        setLoading(true)
        showResult("")

        lifecycleScope.launch {
            try {
                val result = anchorkit.verify(hash)

                val attestation = if (result.attestation_verified == true) Triple(
                    result.cert_fingerprint,
                    result.cert_valid_from?.take(10),
                    result.cert_valid_until?.take(10)
                ) else null

                if (result.verified) {
                    val tsMillis = result.timestamp?.let { it * 1000L }
                    val tsFormatted = tsMillis?.let {
                        SimpleDateFormat("MMM d, yyyy 'at' h:mm:ss a z", Locale.getDefault()).format(Date(it))
                    } ?: "—"

                    showStructuredResult(
                        headline = "Verified on Blockchain",
                        headlineColor = ContextCompat.getColor(this@MainActivity, R.color.success),
                        iconRes = R.drawable.ic_check_circle,
                        fields = buildList {
                            add(Triple("Hash", result.hash, true))
                            add(Triple("Timestamp", tsFormatted, false))
                            if (!result.day.isNullOrEmpty()) add(Triple("Batch Day", result.day!!, false))
                            if (result.hash_id != null) add(Triple("Hash ID", result.hash_id.toString(), false))
                            if (result.solana_tx != null) add(Triple("Solana TX", result.solana_tx!!, true))
                        },
                        attestation = attestation
                    )

                } else {
                    val hasRecord = !result.day.isNullOrEmpty() || result.hash_id != null

                    if (hasRecord) {
                        val tsMillis = result.timestamp?.let { it * 1000L }
                        val tsFormatted = tsMillis?.let {
                            SimpleDateFormat("MMM d, yyyy 'at' h:mm:ss a z", Locale.getDefault()).format(Date(it))
                        }

                        showStructuredResult(
                            headline = "Recorded — Pending Anchor",
                            headlineColor = ContextCompat.getColor(this@MainActivity, R.color.warning),
                            iconRes = R.drawable.ic_hourglass,
                            fields = buildList {
                                add(Triple("Hash", result.hash, true))
                                if (!result.day.isNullOrEmpty()) add(Triple("Batch Day", result.day!!, false))
                                if (result.hash_id != null) add(Triple("Hash ID", result.hash_id.toString(), false))
                                if (tsFormatted != null) add(Triple("Recorded", tsFormatted, false))
                            },
                            attestation = attestation,
                            footnote = "Blockchain anchor is pending — check back tomorrow."
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
                val message = withContext(Dispatchers.IO) { anchorkit.subscribeToNotifications(email) }
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
                withContext(Dispatchers.IO) { anchorkit.unsubscribeFromNotifications(email) }
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
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnCapture.isEnabled = !loading
        binding.btnPickPhoto.isEnabled = !loading
        if (loading) binding.btnVerify.isEnabled = false
        else binding.btnVerify.isEnabled = pickedPhotoHash != null
    }

    /** Simple plain-text result — used for errors and short informational messages. */
    private fun showResult(text: String) {
        // Hide the structured views
        binding.llResultHeader.visibility = View.GONE
        binding.vResultDivider.visibility = View.GONE
        binding.llResultFields.visibility = View.GONE
        binding.llResultAttestation.visibility = View.GONE
        binding.tvResultNote.visibility = View.GONE

        binding.tvResult.text = text
        binding.tvResult.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE

        binding.cardResult.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tvResultEmpty.visibility = if (text.isEmpty()) View.VISIBLE else View.GONE
        if (text.isNotEmpty()) showTab(Tab.RESULT)
    }

    /**
     * Structured result card: colored status banner, labeled field rows,
     * optional attestation badge, and an optional footnote.
     *
     * @param fields List of (label, value, isMonospace)
     * @param attestation Triple of (fingerprint, validFrom, validUntil), or null
     */
    private fun showStructuredResult(
        headline: String,
        headlineColor: Int,
        iconRes: Int,
        fields: List<Triple<String, String, Boolean>>,
        attestation: Triple<String?, String?, String?>? = null,
        footnote: String? = null,
    ) {
        // Header
        binding.tvResultHeadline.text = headline
        binding.tvResultHeadline.setTextColor(headlineColor)
        binding.ivResultIcon.setImageResource(iconRes)
        binding.ivResultIcon.imageTintList = ColorStateList.valueOf(headlineColor)
        binding.llResultHeader.visibility = View.VISIBLE
        binding.vResultDivider.visibility = View.VISIBLE

        // Field rows
        binding.llResultFields.removeAllViews()
        for ((label, value, mono) in fields) {
            addFieldRow(label, value, mono)
        }
        binding.llResultFields.visibility = View.VISIBLE

        // Hide plain-text fallback
        binding.tvResult.visibility = View.GONE

        // Attestation badge
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
            binding.llResultAttestation.visibility = View.VISIBLE
        } else {
            binding.llResultAttestation.visibility = View.GONE
        }

        // Footnote
        if (footnote != null) {
            binding.tvResultNote.text = footnote
            binding.tvResultNote.visibility = View.VISIBLE
        } else {
            binding.tvResultNote.visibility = View.GONE
        }

        binding.cardResult.visibility = View.VISIBLE
        binding.tvResultEmpty.visibility = View.GONE
        showTab(Tab.RESULT)
    }

    /** Appends a label-above-value row to the fields container. */
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
}
