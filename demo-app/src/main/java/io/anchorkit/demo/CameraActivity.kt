package io.anchorkit.demo

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.anchorkit.demo.databinding.ActivityCameraBinding
import io.anchorkit.sdk.AnchorKit
import io.anchorkit.sdk.AnchorKitError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var anchorkit: AnchorKit
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // Only needed for Android 9 (API 28) and below.
    private val writeStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            android.widget.Toast.makeText(
                this,
                "Storage permission is required to save photos to your gallery. " +
                    "Without it the capture cannot complete.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private val scaleGestureDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                camera?.cameraControl?.setZoomRatio(currentZoom * detector.scaleFactor)
                return true
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        anchorkit = AnchorKit(
            context = this,
            apiKey = BuildConfig.ANCHORKIT_API_KEY,
            baseUrl = BuildConfig.ANCHORKIT_BASE_URL
        )

        // Pre-request WRITE_EXTERNAL_STORAGE on Android ≤ 9 so it's granted by
        // the time the user taps the shutter.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
        ) {
            writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        startPreview()

        binding.btnClose.setOnClickListener { finish() }
        binding.btnFlip.setOnClickListener { flipCamera() }
        binding.btnShutter.setOnClickListener { onShutterClicked() }

        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                val point = binding.previewView.meteringPointFactory
                    .createPoint(event.x, event.y)
                camera?.cameraControl?.startFocusAndMetering(
                    FocusMeteringAction.Builder(point).build()
                )
            }
            true
        }
    }

    private fun startPreview() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (e: Exception) {
                returnError("Could not open camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun flipCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT
        else
            CameraSelector.LENS_FACING_BACK
        startPreview()
    }

    private fun onShutterClicked() {
        // On Android 9 and below, storage permission is required to save to gallery.
        // Block the capture and prompt the user if it hasn't been granted yet.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
        ) {
            writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        binding.btnShutter.isEnabled = false
        binding.capturingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val result = anchorkit.captureAndSubmit(this@CameraActivity)

                // Save a copy to the device gallery so the user can verify the photo later.
                // A failed save is a hard error — we do not proceed without it.
                val saved = withContext(Dispatchers.IO) {
                    saveToGallery(result.photo.data, result.photo.timestamp)
                }
                if (!saved) {
                    returnError(
                        "Storage permission is required to save the photo to your gallery.\n\n" +
                            "Please grant the storage permission and try again."
                    )
                    return@launch
                }

                val intent = Intent().apply {
                    putExtra(EXTRA_HASH, result.photo.hash)
                    putExtra(EXTRA_TIMESTAMP_MS, result.photo.timestamp)
                    putExtra(EXTRA_RECEIPT_DAY, result.receipt.day)
                    putExtra(EXTRA_RECEIPT_HASH_ID, result.receipt.hash_id)
                    putExtra(EXTRA_RECEIPT_TABLE, result.receipt.table)
                    result.receipt.timestamp?.let { putExtra(EXTRA_RECEIPT_TIMESTAMP, it) }
                    putExtra(EXTRA_ATTESTATION_VERIFIED, result.receipt.attestation_verified ?: false)
                    putExtra(EXTRA_CERT_FINGERPRINT, result.receipt.cert_fingerprint)
                    putExtra(EXTRA_CERT_VALID_FROM, result.receipt.cert_valid_from)
                    putExtra(EXTRA_CERT_VALID_UNTIL, result.receipt.cert_valid_until)
                }
                setResult(Activity.RESULT_OK, intent)
                finish()

            } catch (e: AnchorKitError.DeviceIntegrityError) {
                returnError("Device integrity check failed: ${e.message}")
            } catch (e: AnchorKitError.AttestationError) {
                returnError("Attestation error: ${e.message}\n\nThis device may not support hardware-backed keys.")
            } catch (e: AnchorKitError.NetworkError) {
                returnError("Network error: ${e.message}\n\nCheck your internet connection.")
            } catch (e: AnchorKitError.ApiError) {
                returnError("API error ${e.statusCode}: ${e.body}")
            } catch (e: Exception) {
                returnError("Unexpected error: ${e.message}")
            }
        }
    }

    /**
     * Write [jpegBytes] into the device's Pictures/AnchorKit album via MediaStore.
     * On Android 10+ no extra permission is required. On Android 9 and below we
     * need WRITE_EXTERNAL_STORAGE, which is requested in onCreate.
     *
     * Returns `true` on success, `false` if the MediaStore insert failed (e.g.
     * WRITE_EXTERNAL_STORAGE was denied on Android 9 and below). Callers must
     * treat a `false` return as a hard error — the photo has not been saved.
     */
    private fun saveToGallery(jpegBytes: ByteArray, timestamp: Long): Boolean {
        val filename = "ANCHORKIT_$timestamp.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, timestamp)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/AnchorKit")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return false

        contentResolver.openOutputStream(uri)?.use { it.write(jpegBytes) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
        return true
    }

    private fun returnError(message: String) {
        val intent = Intent().apply { putExtra(EXTRA_ERROR, message) }
        setResult(Activity.RESULT_CANCELED, intent)
        finish()
    }

    companion object {
        const val EXTRA_HASH = "hash"
        const val EXTRA_TIMESTAMP_MS = "timestamp_ms"
        const val EXTRA_RECEIPT_DAY = "receipt_day"
        const val EXTRA_RECEIPT_HASH_ID = "receipt_hash_id"
        const val EXTRA_RECEIPT_TABLE = "receipt_table"
        const val EXTRA_RECEIPT_TIMESTAMP = "receipt_timestamp"
        const val EXTRA_ATTESTATION_VERIFIED = "attestation_verified"
        const val EXTRA_CERT_FINGERPRINT = "cert_fingerprint"
        const val EXTRA_CERT_VALID_FROM = "cert_valid_from"
        const val EXTRA_CERT_VALID_UNTIL = "cert_valid_until"
        const val EXTRA_ERROR = "error"
    }
}
