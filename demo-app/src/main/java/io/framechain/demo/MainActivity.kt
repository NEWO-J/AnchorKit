package io.framechain.demo

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import io.framechain.demo.databinding.ActivityMainBinding
import io.framechain.sdk.Framechain
import io.framechain.sdk.FramechainError
import io.framechain.sdk.HashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var framechain: Framechain

    // Hash computed from the last picked photo — non-null once a photo is selected.
    private var pickedPhotoHash: String? = null

    // Temp file written by the system camera app.
    private var capturePhotoFile: File? = null

    // Requests CAMERA (and WRITE_EXTERNAL_STORAGE on API < 29) then opens the camera.
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            launchCamera()
        } else {
            showResult("Camera permission is required to capture photos.")
        }
    }

    // Opens the system camera app (full preview + capture button).
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            val file = capturePhotoFile ?: return@registerForActivityResult
            submitCapturedFile(file)
        }
    }

    // Image picker launcher — opens the system gallery.
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

        binding.btnCapture.setOnClickListener { onCaptureClicked() }
        binding.btnPickPhoto.setOnClickListener { photoPickerLauncher.launch("image/*") }
        binding.btnVerify.setOnClickListener { onVerifyClicked() }
    }

    // -------------------------------------------------------------------------
    // Button handlers
    // -------------------------------------------------------------------------

    private fun onCaptureClicked() {
        // Build the list of permissions that still need to be granted.
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.CAMERA)

            // WRITE_EXTERNAL_STORAGE is only required below Android 10 (API 29).
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (needed.isEmpty()) launchCamera()
        else permissionsLauncher.launch(needed.toTypedArray())
    }

    private fun launchCamera() {
        val photoFile = File(cacheDir, "photos/capture_${System.currentTimeMillis()}.jpg")
            .also { it.parentFile?.mkdirs() }
        capturePhotoFile = photoFile
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
        takePictureLauncher.launch(uri)
    }

    private fun onVerifyClicked() {
        val hash = pickedPhotoHash ?: return
        verifyHash(hash)
    }

    // -------------------------------------------------------------------------
    // Photo picker result
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

                // Show thumbnail
                binding.ivPickedPhoto.setImageURI(uri)
                binding.ivPickedPhoto.visibility = View.VISIBLE

                // Show hash
                binding.tvPickedHash.text = getString(R.string.hash_label) + "\n" + hash
                binding.tvPickedHash.visibility = View.VISIBLE

                // Enable the verify button now that we have a hash
                binding.btnVerify.isEnabled = true

            } catch (e: Exception) {
                showResult("Could not read photo: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // SDK calls (run in lifecycleScope — cancelled automatically on destroy)
    // -------------------------------------------------------------------------

    private fun submitCapturedFile(file: File) {
        setLoading(true)
        showResult("")

        lifecycleScope.launch {
            try {
                // Save to the user's photo gallery before submitting to the blockchain.
                withContext(Dispatchers.IO) { saveToGallery(file) }

                val result = framechain.submitFile(file)
                showResult(
                    buildString {
                        appendLine("Photo submitted successfully!")
                        appendLine()
                        appendLine("Hash:    ${result.photo.hash}")
                        appendLine("Day:     ${result.receipt.day}")
                        appendLine("Hash ID: ${result.receipt.hash_id}")
                        appendLine("Table:   ${result.receipt.table}")
                        appendLine()
                        appendLine("The hash will be anchored to the Solana blockchain tonight.")
                    }
                )
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
                file.delete()
            }
        }
    }

    /**
     * Copies [file] into the public photo gallery via MediaStore so it appears
     * in the device's Photos/Gallery app alongside other pictures.
     *
     * On API 29+ no extra permission is needed. On API 24–28 the caller must
     * have already obtained WRITE_EXTERNAL_STORAGE.
     */
    private fun saveToGallery(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return

        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }

        // Flip IS_PENDING to 0 so the image becomes visible to other apps.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    private fun verifyHash(hash: String) {
        setLoading(true)
        showResult("")

        lifecycleScope.launch {
            try {
                val result = framechain.verify(hash)
                if (result.verified) {
                    showResult(
                        buildString {
                            appendLine("Hash VERIFIED on blockchain!")
                            appendLine()
                            appendLine("Hash:      ${result.hash}")
                            appendLine("Day:       ${result.day}")
                            appendLine("Timestamp: ${result.timestamp}")
                            appendLine("Hash ID:   ${result.hash_id}")
                            if (result.solana_tx != null) {
                                appendLine()
                                appendLine("Solana TX: ${result.solana_tx}")
                            }
                        }
                    )
                } else {
                    showResult(
                        buildString {
                            appendLine("Hash not yet verified.")
                            appendLine()
                            appendLine("Hash: ${result.hash}")
                            if (!result.day.isNullOrEmpty()) {
                                appendLine("Recorded on: ${result.day}")
                                appendLine("Anchor is pending — check back tomorrow.")
                            } else {
                                appendLine(result.message ?: "Hash not found in the system.")
                            }
                        }
                    )
                }
            } catch (e: FramechainError.NetworkError) {
                showResult("Network error: ${e.message}")
            } catch (e: FramechainError.ApiError) {
                showResult("API error ${e.statusCode}: ${e.body}")
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
        // btnVerify stays disabled until a photo has been picked; restore state after load
        if (loading) binding.btnVerify.isEnabled = false
        else binding.btnVerify.isEnabled = pickedPhotoHash != null
    }

    private fun showResult(text: String) {
        binding.tvResult.text = text
        binding.cardResult.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
    }
}
