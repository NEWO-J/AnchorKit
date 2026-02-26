package io.anchorkit.sdk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class VideoRecorder(private val context: Context) {

    /**
     * Start recording video and return a [VideoRecordingSession] immediately.
     *
     * The video is written to a temp file in the app cache directory. Recording
     * continues until [VideoRecordingSession.stop] is called, at which point the
     * encoder finalizes the file and [VideoRecordingSession.awaitResult] resolves
     * with the SHA-256 hash of the raw MP4 bytes.
     *
     * If [previewSurfaceProvider] is supplied, [Preview] and [VideoCapture] are
     * bound together so the viewfinder stays live during recording. Omit it when
     * no preview is needed (e.g. background recording).
     *
     * Audio is included automatically if [Manifest.permission.RECORD_AUDIO] is
     * already granted; otherwise the recording proceeds silently.
     */
    suspend fun startRecording(
        lifecycleOwner: LifecycleOwner,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK,
        previewSurfaceProvider: Preview.SurfaceProvider? = null,
        cameraSelector: CameraSelector? = null,
        cacheDir: File
    ): VideoRecordingSession = suspendCancellableCoroutine { continuation ->

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()

            val videoCaptureUseCase = VideoCapture.withOutput(recorder)

            // Use the caller-provided selector (same physical camera as the preview) so
            // that binding VideoCapture doesn't switch to a different rear lens and cause
            // a visible field-of-view shift at recording start.
            val resolvedCameraSelector = cameraSelector ?: CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            val tempFile = File(cacheDir, "anchorkit_video_${System.currentTimeMillis()}.mp4")

            try {
                // Add VideoCapture alongside the already-running Preview use case.
                // Do NOT call unbindAll() — that tears down the Preview session, causing
                // a visible black flash and resetting camera zoom to the device default.
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner, resolvedCameraSelector, videoCaptureUseCase
                )
                // Re-apply minimum zoom so the viewfinder stays fully zoomed out.
                camera.cameraControl.setLinearZoom(0f)

                val finalizedDeferred = CompletableDeferred<VideoResult>()
                var startTimestamp = System.currentTimeMillis()

                val fileOutputOptions = FileOutputOptions.Builder(tempFile).build()
                val prepareRecording = videoCaptureUseCase.output
                    .prepareRecording(context, fileOutputOptions)

                // Enable audio only when the permission is already granted — silently
                // skip it otherwise so the recording still proceeds without audio.
                val recording = if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    prepareRecording.withAudioEnabled()
                } else {
                    prepareRecording
                }.start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            startTimestamp = System.currentTimeMillis()
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (!event.hasError()) {
                                val hash = try {
                                    HashUtils.hashFile(tempFile)
                                } catch (e: Exception) {
                                    finalizedDeferred.completeExceptionally(
                                        AnchorKitError.HashError("Failed to hash video: ${e.message}")
                                    )
                                    return@start
                                }
                                val durationMs =
                                    event.recordingStats.recordedDurationNanos / 1_000_000L
                                finalizedDeferred.complete(
                                    VideoResult(
                                        file = tempFile,
                                        hash = hash,
                                        timestamp = startTimestamp,
                                        durationMs = durationMs
                                    )
                                )
                            } else {
                                finalizedDeferred.completeExceptionally(
                                    AnchorKitError.HashError(
                                        "Video recording finalized with error code ${event.error}"
                                    )
                                )
                            }
                        }
                        else -> {}
                    }
                }

                continuation.resume(
                    VideoRecordingSession(
                        recording = recording,
                        finalizedDeferred = finalizedDeferred
                    )
                )
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}

/**
 * An active recording returned by [AnchorKit.startVideoRecording].
 *
 * Call [stop] when the user signals end-of-recording, then pass this session to
 * [AnchorKit.stopVideoAndSubmit] which awaits finalization, hashes the file,
 * and submits the hash to the API.
 */
class VideoRecordingSession internal constructor(
    private val recording: Recording,
    internal val finalizedDeferred: CompletableDeferred<VideoResult>
) {
    /** Signal the camera encoder to stop. The file is not yet ready — call [awaitResult]. */
    fun stop() {
        recording.stop()
    }

    /** Suspend until the encoder has written and closed the file and the hash is ready. */
    suspend fun awaitResult(): VideoResult = finalizedDeferred.await()
}

/** Result of a completed video recording, returned by [AnchorKit.stopVideoAndSubmit]. */
data class VideoResult(
    /** Temp file containing the raw MP4 bytes. Deleted after gallery save. */
    val file: File,
    /** SHA-256 hex hash of the raw MP4 bytes — this is what gets anchored. */
    val hash: String,
    /** Unix epoch ms when recording started (set on [VideoRecordEvent.Start]). */
    val timestamp: Long,
    /** Duration of the recorded clip in milliseconds. */
    val durationMs: Long
)
