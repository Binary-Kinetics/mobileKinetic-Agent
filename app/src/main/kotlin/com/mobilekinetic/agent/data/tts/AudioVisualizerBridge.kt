package com.mobilekinetic.agent.data.tts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.mobilekinetic.agent.data.DiagnosticLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge to Android's native Visualizer API for real-time audio analysis.
 *
 * Uses the Visualizer class to capture FFT data from an audio session,
 * providing perfectly synchronized audio level data for visualization.
 * This eliminates the timing mismatch that occurs when computing audio
 * levels from PCM data before it's played.
 *
 * Requires RECORD_AUDIO permission.
 */
class AudioVisualizerBridge {
    companion object {
        private const val TAG = "AudioVisualizerBridge"
        private const val CAPTURE_SIZE = 128  // FFT size, matches original AudioKinetics
    }

    private var visualizer: Visualizer? = null
    private var isEnabled = false

    // Real-time audio level (0.0 to 1.0) - updated ~20 times per second
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    // Raw FFT magnitude data for advanced visualizations
    private val _fftData = MutableStateFlow(FloatArray(CAPTURE_SIZE / 2))
    val fftData: StateFlow<FloatArray> = _fftData.asStateFlow()

    /**
     * Attach to an audio session and start capturing FFT data.
     *
     * @param audioSessionId The audio session ID from AudioTrack.audioSessionId
     *                       Use 0 for global output mix (requires more permissions)
     * @param context Application context for permission check
     */
    fun attach(audioSessionId: Int, context: Context? = null) {
        // Release any existing visualizer
        release()

        Log.d(TAG, "Attempting to attach visualizer to session: $audioSessionId")

        // Check RECORD_AUDIO permission if context provided
        if (context != null) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                Log.w(TAG, "RECORD_AUDIO permission not granted - cannot attach visualizer")
                return
            }
        }

        try {
            // Use the provided session ID, or 0 for global output mix
            val sessionToUse = if (audioSessionId != 0) audioSessionId else 0

            visualizer = Visualizer(sessionToUse).apply {
                // Set capture size (must be power of 2, 128-1024)
                captureSize = CAPTURE_SIZE

                // Use maximum capture rate for responsive visualization
                val maxRate = Visualizer.getMaxCaptureRate()
                Log.d(TAG, "Visualizer max capture rate: $maxRate")

                // Set up data capture listener
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                            // We use FFT data, not waveform
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            fft?.let {
                                processFftData(it)
                            }
                        }
                    },
                    maxRate,  // Use max capture rate for best responsiveness
                    false,    // Don't capture waveform
                    true      // Capture FFT
                )

                // Enable the visualizer
                enabled = true
                isEnabled = true
                Log.d(TAG, "Visualizer attached to session $sessionToUse, capture size: $captureSize, enabled: $enabled")
                DiagnosticLogger.visualizerAttached(sessionToUse)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Visualizer: ${e.message}", e)
            DiagnosticLogger.logError("VISUALIZER_ATTACH_FAIL", e.message ?: "unknown", e)
            // Common errors:
            // - RECORD_AUDIO permission not granted
            // - Invalid audio session ID
            // - Audio session not active yet
        }
    }

    /**
     * Process raw FFT data from Visualizer.
     * FFT data format: [DC, real0, imag0, real1, imag1, ...]
     */
    private fun processFftData(fft: ByteArray) {
        if (fft.size < 4) return

        val magnitudes = FloatArray(fft.size / 2 - 1)
        var totalEnergy = 0f
        var bassEnergy = 0f

        // Calculate magnitude for each frequency bin
        // Skip DC component (index 0)
        for (i in 1 until fft.size / 2) {
            val real = fft[i * 2].toFloat()
            val imag = fft[i * 2 + 1].toFloat()
            val magnitude = kotlin.math.sqrt(real * real + imag * imag)

            magnitudes[i - 1] = magnitude
            totalEnergy += magnitude

            // Bass frequencies (first 20% of bins)
            if (i < fft.size / 10) {
                bassEnergy += magnitude
            }
        }

        // Compute average magnitude (0-255 range from byte data)
        val avgMagnitude = totalEnergy / magnitudes.size

        // Normalize to 0.0-1.0 range
        // Typical speech has lower energy than music, so use lower normalization ceiling
        val normalizedLevel = (avgMagnitude / 80f).coerceIn(0f, 1f)

        // Update flows
        _audioLevel.value = normalizedLevel
        _fftData.value = magnitudes
    }

    /**
     * Enable or disable the visualizer.
     * Disable when not needed to save battery.
     */
    fun setEnabled(enabled: Boolean) {
        try {
            visualizer?.enabled = enabled
            isEnabled = enabled
            if (!enabled) {
                _audioLevel.value = 0f
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set visualizer enabled state: ${e.message}")
        }
    }

    /**
     * Release the visualizer resources.
     * Call when playback ends or component is disposed.
     */
    fun release() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
            isEnabled = false
            _audioLevel.value = 0f
            Log.d(TAG, "Visualizer released")
            DiagnosticLogger.visualizerReleased()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing visualizer: ${e.message}")
        }
    }

    /**
     * Check if the visualizer is currently attached and enabled.
     */
    fun isActive(): Boolean = isEnabled && visualizer != null
}
