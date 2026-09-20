package com.realtimetranspose.ui.components

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round

/**
 * Maps real units (semitones, a speed ratio) to/from the 0f..1f fraction
 * [CenteredValueSlider] operates on. Speed uses a log scale so that 1.0x
 * (no change) always lands exactly at the visual center of the track,
 * regardless of range — true whenever max == 1/min, which both the Files
 * (0.25x-4x) and Browser (0.5x-2x) ranges satisfy.
 */
object ValueMapping {

    fun semitonesToFraction(semitones: Float, min: Float = -12f, max: Float = 12f): Float =
        ((semitones - min) / (max - min)).coerceIn(0f, 1f)

    fun fractionToSemitones(fraction: Float, min: Float = -12f, max: Float = 12f): Int =
        round(min + fraction.coerceIn(0f, 1f) * (max - min)).toInt()

    fun speedToFraction(speed: Float, min: Float, max: Float): Float {
        val logMin = ln(min)
        val logMax = ln(max)
        return ((ln(speed) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
    }

    fun fractionToSpeed(fraction: Float, min: Float, max: Float, step: Float = 0.05f): Float {
        val logMin = ln(min)
        val logMax = ln(max)
        val raw = exp(logMin + fraction.coerceIn(0f, 1f) * (logMax - logMin))
        return (round(raw / step) * step).coerceIn(min, max)
    }
}
