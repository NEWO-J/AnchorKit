package io.framechain.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.framechain.demo.databinding.ActivityCameraBinding
import io.framechain.sdk.Framechain
import io.framechain.sdk.FramechainError
import kotlinx.coroutines.launch

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var framechain: Framechain
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

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

        framechain = Framechain(
            context = this,
            apiKey = BuildConfig.FRAMECHAIN_API_KEY,
            baseUrl = BuildConfig.FRAMECHAIN_BASE_URL
        )

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
        binding.btnShutter.isEnabled = false
        binding.capturingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val result = framechain.captureAndSubmit(this@CameraActivity)

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

            } catch (e: FramechainError.DeviceIntegrityError) {
                returnError("Device integrity check failed: ${e.message}")
            } catch (e: FramechainError.AttestationError) {
                returnError("Attestation error: ${e.message}\n\nThis device may not support hardware-backed keys.")
            } catch (e: FramechainError.NetworkError) {
                returnError("Network error: ${e.message}\n\nCheck your internet connection.")
            } catch (e: FramechainError.ApiError) {
                returnError("API error ${e.statusCode}: ${e.body}")
            } catch (e: Exception) {
                returnError("Unexpected error: ${e.message}")
            }
        }
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
