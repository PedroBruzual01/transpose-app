package com.realtimetranspose.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PlayerState(
    val isLoaded: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val pitchSemitones: Float = 0f,
    val speed: Float = 1f,
    val fileName: String? = null,
    val error: String? = null,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
) {
    /** Both points set, in the right order — the only case actually looped. */
    val isLoopActive: Boolean
        get() = loopStartMs != null && loopEndMs != null && loopEndMs > loopStartMs
}

/**
 * Owns the whole decode → pitch/tempo-shift → play pipeline for one file at a
 * time. Singleton: only ever one active stream, and the UI reads its state
 * without a bound-service round trip (same reasoning as the old
 * AudioCaptureManager). All decode/process/write work happens on one
 * dedicated thread — [AudioTrack.write] in blocking mode paces it to real
 * time, so no extra sleeping is needed while actively playing.
 *
 * Position tracking is based on frames *fed into the engine* (source time),
 * not frames written to the AudioTrack (played/output time) — those two
 * diverge as soon as speed != 1, since time-stretching changes how many
 * output frames represent one second of source audio.
 */
object TransposePlayer {

    private const val TAG = "TransposePlayer"
    private const val CHUNK_FRAMES = 4096
    private const val RETRIEVE_BUFFER_FRAMES = CHUNK_FRAMES * 4

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private var worker: Thread? = null

    @Volatile private var keepRunning = false

    @Volatile private var playRequested = false

    @Volatile private var pendingSeekUs: Long? = null

    @Volatile private var pitchSemitones = 0f

    @Volatile private var speed = 1f

    @Volatile private var loopStartMs: Long? = null

    @Volatile private var loopEndMs: Long? = null

    fun load(context: Context, uri: Uri, displayName: String?) {
        stop()

        _state.value = PlayerState(fileName = displayName)
        keepRunning = true
        playRequested = false
        pendingSeekUs = null
        loopStartMs = null
        loopEndMs = null

        worker = Thread({ runPipeline(context.applicationContext, uri) }, "TransposePlaybackThread").apply {
            start()
        }
    }

    fun play() {
        playRequested = true
    }

    fun pause() {
        playRequested = false
    }

    fun seekTo(positionMs: Long) {
        pendingSeekUs = positionMs * 1000
    }

    fun setPitchSemitones(value: Float) {
        pitchSemitones = value
        _state.value = _state.value.copy(pitchSemitones = value)
    }

    fun setSpeed(value: Float) {
        val clamped = value.coerceIn(0.25f, 4f)
        speed = clamped
        _state.value = _state.value.copy(speed = clamped)
    }

    /** Marks the current playback position as the loop's start (A) point. */
    fun markLoopStart() {
        val ms = _state.value.positionMs
        loopStartMs = ms
        _state.value = _state.value.copy(loopStartMs = ms)
    }

    /** Marks the current playback position as the loop's end (B) point. */
    fun markLoopEnd() {
        val ms = _state.value.positionMs
        loopEndMs = ms
        _state.value = _state.value.copy(loopEndMs = ms)
    }

    fun clearLoop() {
        loopStartMs = null
        loopEndMs = null
        _state.value = _state.value.copy(loopStartMs = null, loopEndMs = null)
    }

    fun stop() {
        keepRunning = false
        playRequested = false
        worker?.join(1000)
        worker = null
        _state.value = PlayerState()
    }

    private fun runPipeline(context: Context, uri: Uri) {
        val decoder = try {
            AudioFileDecoder(context, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open decoder", e)
            _state.value = _state.value.copy(error = "No se pudo abrir el archivo: ${e.message}")
            return
        }

        if (decoder.channelCount !in 1..2) {
            _state.value = _state.value.copy(
                error = "Solo se soportan archivos mono o estéreo (este tiene ${decoder.channelCount} canales)",
            )
            decoder.close()
            return
        }

        val engine = PitchShiftEngine(decoder.sampleRate, decoder.channelCount)
        engine.semitones = pitchSemitones
        engine.speed = speed

        val channelMask = if (decoder.channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBufferBytes = AudioTrack.getMinBufferSize(
            decoder.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(decoder.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBufferBytes, CHUNK_FRAMES * decoder.channelCount * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        _state.value = _state.value.copy(
            isLoaded = true,
            durationMs = decoder.durationUs / 1000,
        )

        val channels = decoder.channelCount
        val inputBuffers = Array(channels) { FloatArray(CHUNK_FRAMES) }
        val outputBuffers = Array(channels) { FloatArray(RETRIEVE_BUFFER_FRAMES) }
        val interleaved = ShortArray(RETRIEVE_BUFFER_FRAMES * channels)

        var framesFed = 0L
        var reachedEndOfInput = false

        // Track starts in PLAYING state per the platform docs, but we want to
        // begin paused until the user actually presses play.
        audioTrack.play()
        audioTrack.pause()

        try {
            while (keepRunning) {
                pendingSeekUs?.let { seekUs ->
                    pendingSeekUs = null
                    audioTrack.pause()
                    audioTrack.flush()
                    decoder.seekTo(seekUs)
                    engine.reset()
                    framesFed = seekUs * decoder.sampleRate / 1_000_000
                    reachedEndOfInput = false
                    _state.value = _state.value.copy(positionMs = seekUs / 1000)
                }

                if (!playRequested) {
                    if (_state.value.isPlaying) _state.value = _state.value.copy(isPlaying = false)
                    audioTrack.pause()
                    Thread.sleep(20)
                    continue
                }
                if (!_state.value.isPlaying) _state.value = _state.value.copy(isPlaying = true)
                audioTrack.play()

                if (pitchSemitones != engine.semitones) engine.semitones = pitchSemitones
                if (speed != engine.speed) engine.speed = speed

                if (!reachedEndOfInput) {
                    val read = decoder.readChunk(inputBuffers, CHUNK_FRAMES)
                    when {
                        read == -1 -> {
                            reachedEndOfInput = true
                            engine.process(inputBuffers, 0, true)
                        }
                        read > 0 -> {
                            engine.process(inputBuffers, read, false)
                            framesFed += read
                            val newPositionMs = framesFed * 1000 / decoder.sampleRate
                            _state.value = _state.value.copy(positionMs = newPositionMs)

                            // Loop-back check. Granularity is one CHUNK_FRAMES read
                            // (~93ms at 44.1kHz) — not sample-accurate, but the whole
                            // pipeline already buffers well beyond that, so it's not
                            // the limiting factor for a practice-loop feature.
                            val loopStart = loopStartMs
                            val loopEnd = loopEndMs
                            if (loopStart != null && loopEnd != null && loopEnd > loopStart &&
                                newPositionMs >= loopEnd
                            ) {
                                pendingSeekUs = loopStart * 1000
                            }
                        }
                    }
                }

                var available = engine.available()
                if (available == -1) {
                    // Truly finished: input marked final and fully drained.
                    playRequested = false
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        positionMs = _state.value.durationMs,
                    )
                    audioTrack.pause()
                    continue
                }
                while (available > 0) {
                    val toRetrieve = minOf(available, RETRIEVE_BUFFER_FRAMES)
                    val retrieved = engine.retrieve(outputBuffers, toRetrieve)
                    if (retrieved <= 0) break

                    for (f in 0 until retrieved) {
                        for (c in 0 until channels) {
                            val sample = (outputBuffers[c][f] * 32768f)
                                .coerceIn(-32768f, 32767f)
                                .toInt()
                                .toShort()
                            interleaved[f * channels + c] = sample
                        }
                    }
                    audioTrack.write(interleaved, 0, retrieved * channels)
                    available = engine.available()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback pipeline error", e)
            _state.value = _state.value.copy(error = e.message, isPlaying = false)
        } finally {
            audioTrack.stop()
            audioTrack.release()
            engine.destroy()
            decoder.close()
        }
    }
}
