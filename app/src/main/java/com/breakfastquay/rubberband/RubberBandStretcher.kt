package com.breakfastquay.rubberband

/**
 * Thin Kotlin binding for upstream Rubber Band's own JNI bridge
 * (`rubberband/src/jni/RubberBandStretcherJNI.cpp`, vendored as-is under
 * `app/src/main/cpp/rubberband/`). Method names, the `handle` field, and this
 * class's fully-qualified name are load-bearing — the native side looks them
 * up by exact JNI signature (`Java_com_breakfastquay_rubberband_RubberBandStretcher_*`).
 * Do not rename anything here without updating the .cpp side too.
 *
 * Semantics match the upstream C++ `RubberBandStretcher` class directly:
 * push audio in via [process] (after an optional [study] analysis pass),
 * pull stretched/shifted audio out via [retrieve] once [available] is
 * non-zero. See the option flags in [Options] for what [initialise] expects.
 */
class RubberBandStretcher(
    sampleRate: Int,
    channels: Int,
    options: Int,
    initialTimeRatio: Double = 1.0,
    initialPitchScale: Double = 1.0,
) {
    // Written by the native side via SetLongField — holds the C++ pointer.
    @Suppress("unused")
    private var handle: Long = 0

    init {
        initialise(sampleRate, channels, options, initialTimeRatio, initialPitchScale)
    }

    private external fun initialise(
        sampleRate: Int,
        channels: Int,
        options: Int,
        initialTimeRatio: Double,
        initialPitchScale: Double,
    )

    external fun dispose()
    external fun reset()
    external fun setTimeRatio(ratio: Double)
    external fun setPitchScale(scale: Double)
    external fun getChannelCount(): Int
    external fun getTimeRatio(): Double
    external fun getPitchScale(): Double
    external fun getLatency(): Int
    external fun setTransientsOption(options: Int)
    external fun setDetectorOption(options: Int)
    external fun setPhaseOption(options: Int)
    external fun setFormantOption(options: Int)
    external fun setPitchOption(options: Int)
    external fun setExpectedInputDuration(duration: Long)
    external fun setMaxProcessSize(size: Int)
    external fun getSamplesRequired(): Int

    /** `data[channel]` holds at least `offset + n` samples. */
    external fun study(data: Array<FloatArray>, offset: Int, n: Int, final: Boolean)

    /** `data[channel]` holds at least `offset + n` samples. */
    external fun process(data: Array<FloatArray>, offset: Int, n: Int, final: Boolean)

    /** Number of sample frames ready to [retrieve], or -1 once truly finished. */
    external fun available(): Int

    /**
     * Fills `output[channel]` starting at `offset` with up to `n` frames.
     * Returns the number of frames actually written.
     */
    external fun retrieve(output: Array<FloatArray>, offset: Int, n: Int): Int

    /** Mirrors `RubberBandStretcher::Option` (RubberBandStretcher.h). Combine with `or`. */
    object Options {
        const val PROCESS_OFFLINE = 0x00000000
        const val PROCESS_REALTIME = 0x00000001
        const val ENGINE_FASTER = 0x00000000
        const val ENGINE_FINER = 0x20000000
        const val PITCH_HIGH_SPEED = 0x00000000
        const val PITCH_HIGH_QUALITY = 0x02000000
        const val PITCH_HIGH_CONSISTENCY = 0x04000000
        const val FORMANT_SHIFTED = 0x00000000
        const val FORMANT_PRESERVED = 0x01000000
    }

    companion object {
        init {
            System.loadLibrary("rubberband-jni")
        }
    }
}
