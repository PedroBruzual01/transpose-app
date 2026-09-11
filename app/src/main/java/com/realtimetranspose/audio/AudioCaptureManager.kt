package com.realtimetranspose.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CaptureStatus(
    val isCapturing: Boolean = false,
    val audioReceived: Boolean = false,
    val rmsLevel: Float = 0f,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val framesRead: Long = 0,
    val lastError: String? = null,
    val state: CapturePhase = CapturePhase.IDLE,
)

enum class CapturePhase {
    IDLE,
    CAPTURE_UNAVAILABLE,
    WAITING_FOR_AUDIO,
    PROCESSING,
    AUDIO_LOST,
}

/**
 * Phase 1 proof-of-concept: capture only, no pitch shifting.
 *
 * Singleton because there is only ever one capture stream in this app, and both
 * the UI (reading [status]) and the foreground service (driving start/stop) need
 * to reach the same instance without a bound-service round trip.
 *
 * The capture loop runs on a plain [Thread], not a coroutine — it's a tight,
 * blocking [AudioRecord.read] loop, and this keeps it off any shared dispatcher.
 */
object AudioCaptureManager {

    private const val TAG = "AudioCaptureManager"
    private const val SAMPLE_RATE = 44_100
    private const val SILENCE_RMS_THRESHOLD = 0.0005f
    private const val SILENT_READS_BEFORE_AUDIO_LOST = 20

    private val _status = MutableStateFlow(CaptureStatus())
    val status: StateFlow<CaptureStatus> = _status

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile private var keepRunning = false

    /** Call when the user denies (or cancels) the system MediaProjection consent dialog. */
    fun reportPermissionDenied() {
        _status.value = _status.value.copy(
            state = CapturePhase.CAPTURE_UNAVAILABLE,
            lastError = "User denied the system capture permission",
        )
    }

    fun start(mediaProjection: MediaProjection) {
        stop()

        val captureConfig = try {
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } catch (e: Exception) {
            fail("Failed to build capture config: ${e.message}")
            return
        }

        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(channelMask)
            .build()

        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            fail("getMinBufferSize returned $minBufferBytes")
            return
        }
        val bufferBytes = minBufferBytes * 4

        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferBytes)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            fail("Failed to build AudioRecord: ${e.message}")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            fail("AudioRecord failed to initialize (state=${record.state})")
            record.release()
            return
        }

        audioRecord = record
        val channelCount = if (channelMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1

        _status.value = CaptureStatus(
            isCapturing = true,
            sampleRate = record.sampleRate,
            channelCount = channelCount,
            state = CapturePhase.WAITING_FOR_AUDIO,
        )

        record.startRecording()
        keepRunning = true
        captureThread = Thread({ runCaptureLoop(record, bufferBytes) }, "AudioCaptureThread").apply {
            start()
        }
    }

    fun stop() {
        keepRunning = false
        captureThread?.join(500)
        captureThread = null

        audioRecord?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop() called on a non-recording AudioRecord", e)
            }
            it.release()
        }
        audioRecord = null

        _status.value = _status.value.copy(isCapturing = false, state = CapturePhase.IDLE)
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        _status.value = _status.value.copy(
            isCapturing = false,
            state = CapturePhase.CAPTURE_UNAVAILABLE,
            lastError = message,
        )
    }

    private fun runCaptureLoop(record: AudioRecord, bufferBytes: Int) {
        // Allocated once, reused for every read — no per-callback allocation.
        val shortBuffer = ShortArray(bufferBytes / 2)
        var silentReadStreak = 0

        while (keepRunning) {
            val samplesRead = record.read(shortBuffer, 0, shortBuffer.size)
            if (samplesRead <= 0) continue

            var sumSquares = 0.0
            for (i in 0 until samplesRead) {
                val sample = shortBuffer[i] / 32768.0
                sumSquares += sample * sample
            }
            val rms = sqrt(sumSquares / samplesRead).toFloat()
            val hasAudio = rms > SILENCE_RMS_THRESHOLD
            silentReadStreak = if (hasAudio) 0 else silentReadStreak + 1

            val current = _status.value
            val nextPhase = when {
                hasAudio -> CapturePhase.PROCESSING
                silentReadStreak > SILENT_READS_BEFORE_AUDIO_LOST && current.audioReceived ->
                    CapturePhase.AUDIO_LOST
                else -> CapturePhase.WAITING_FOR_AUDIO
            }

            _status.value = current.copy(
                audioReceived = current.audioReceived || hasAudio,
                rmsLevel = rms,
                framesRead = current.framesRead + samplesRead,
                state = nextPhase,
            )
        }
    }
}
