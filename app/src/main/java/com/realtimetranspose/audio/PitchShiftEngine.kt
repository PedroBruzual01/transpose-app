package com.realtimetranspose.audio

import com.breakfastquay.rubberband.RubberBandStretcher
import kotlin.math.pow

/**
 * Convenience layer over [RubberBandStretcher]: semitone/speed inputs instead
 * of raw ratios, and a fixed option set tuned for "play a file back with
 * live-adjustable pitch/speed" (dynamic changes, natural-sounding vocals)
 * rather than one-shot offline stretching.
 */
class PitchShiftEngine(sampleRate: Int, channels: Int) {

    private val stretcher = RubberBandStretcher(
        sampleRate,
        channels,
        RubberBandStretcher.Options.PROCESS_REALTIME or
            RubberBandStretcher.Options.ENGINE_FINER or
            RubberBandStretcher.Options.PITCH_HIGH_CONSISTENCY or
            RubberBandStretcher.Options.FORMANT_PRESERVED,
    )

    var semitones: Float = 0f
        set(value) {
            field = value
            stretcher.setPitchScale(2.0.pow(value.toDouble() / 12.0))
        }

    var speed: Float = 1f
        set(value) {
            field = value
            // Rubber Band's timeRatio is "output length / input length" — the
            // inverse of playback speed (2x speed = half the output length).
            stretcher.setTimeRatio(1.0 / value)
        }

    fun process(input: Array<FloatArray>, frameCount: Int, final: Boolean) {
        stretcher.process(input, 0, frameCount, final)
    }

    fun available(): Int = stretcher.available()

    fun retrieve(output: Array<FloatArray>, frameCount: Int): Int =
        stretcher.retrieve(output, 0, frameCount)

    fun reset() = stretcher.reset()

    fun destroy() = stretcher.dispose()
}
