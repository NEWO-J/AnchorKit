package io.framechain.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.framechain.demo.databinding.ActivityMainBinding
import io.framechain.sdk.Framechain
import io.framechain.sdk.FramechainError
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var framechain: Framechain

    // Permission launcher — requests CAMERA and resumes the pending capture on grant.
    private var pendingCaptureOnGrant = false
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingCaptureOnGrant) {
            pendingCaptureOnGrant = false
            captureAndSubmit()
        } else if (!granted) {
            showResult("Camera permission denied. Grant it in Settings to capture photos.")
        }
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
        binding.btnVerify.setOnClickListener { onVerifyClicked() }
    }

    // -------------------------------------------------------------------------
    // Button handlers
    // -------------------------------------------------------------------------

    private fun onCaptureClicked() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> captureAndSubmit()

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                showResult("Camera permission is required to capture photos.\nTap 'Capture' again to request it.")
                pendingCaptureOnGrant = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }

            else -> {
                pendingCaptureOnGrant = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun onVerifyClicked() {
        val hash = binding.etHash.text?.toString()?.trim() ?: ""
        if (hash.length != 64 || !hash.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            showResult("Please enter a valid 64-character hex hash.")
            return
        }
        verifyHash(hash)
    }

    // -------------------------------------------------------------------------
    // SDK calls (run in lifecycleScope — cancelled automatically on destroy)
    // -------------------------------------------------------------------------

    private fun captureAndSubmit() {
        setLoading(true)
        showResult("")

        lifecycleScope.launch {
            try {
                val result = framechain.captureAndSubmit(this@MainActivity)
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
            }
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
                                appendLine("Hash not found in the system.")
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
        binding.btnVerify.isEnabled = !loading
    }

    private fun showResult(text: String) {
        binding.tvResult.text = text
        binding.cardResult.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
    }
}
