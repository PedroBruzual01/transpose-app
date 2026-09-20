@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.realtimetranspose.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.realtimetranspose.ui.theme.NocturneColors
import com.realtimetranspose.ui.theme.accentGlow

/**
 * Two custom Slider skins built on Material3's SliderState-based track/thumb
 * slots (keeps platform drag handling, a11y, and min-touch-target logic;
 * only the drawing is custom). Both operate on a plain 0f..1f fraction —
 * callers own the mapping to real units (semitones, a log-scaled speed
 * ratio, playback position), so these components don't need to know about
 * any particular unit.
 *
 * Glow: a radial-gradient Modifier.accentGlow (see Theme.kt) — not
 * Modifier.shadow, which renders a CircleShape's elevation shadow as a
 * visibly faceted polygon on some devices instead of a smooth circle.
 */

private val TRACK_STROKE = 3.dp

@Composable
fun CenteredValueSlider(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 26.dp,
    thumbDiameter: Dp = 13.dp,
    glow: Boolean = true,
) {
    Slider(
        value = fraction.coerceIn(0f, 1f),
        onValueChange = onFractionChange,
        valueRange = 0f..1f,
        modifier = modifier.fillMaxWidth().height(trackHeight),
        track = { state -> CenteredTrack(state = state, height = trackHeight) },
        thumb = { SliderThumb(diameter = thumbDiameter, glow = glow) },
    )
}

@Composable
fun ProgressSlider(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    loopStartFraction: Float? = null,
    loopEndFraction: Float? = null,
    touchHeight: Dp = 44.dp,
) {
    Slider(
        value = fraction.coerceIn(0f, 1f),
        onValueChange = onFractionChange,
        valueRange = 0f..1f,
        modifier = modifier.fillMaxWidth().height(touchHeight),
        track = { state -> ProgressTrack(state, loopStartFraction, loopEndFraction, touchHeight) },
        thumb = { SliderThumb(diameter = 13.dp, glow = true) },
    )
}

@Composable
private fun CenteredTrack(state: SliderState, height: Dp) {
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        val midY = size.height / 2f
        val strokePx = TRACK_STROKE.toPx()

        drawLine(
            color = NocturneColors.text.copy(alpha = 0.14f),
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )

        val centerX = size.width / 2f
        drawLine(
            color = NocturneColors.text.copy(alpha = 0.30f),
            start = Offset(centerX, midY - 7.dp.toPx()),
            end = Offset(centerX, midY + 7.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )

        val valueX = size.width * state.value
        drawLine(
            color = NocturneColors.accent,
            start = Offset(centerX, midY),
            end = Offset(valueX, midY),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ProgressTrack(state: SliderState, loopStart: Float?, loopEnd: Float?, touchHeight: Dp) {
    Canvas(modifier = Modifier.fillMaxWidth().height(touchHeight)) {
        val midY = size.height / 2f
        val strokePx = TRACK_STROKE.toPx()

        if (loopStart != null && loopEnd != null && loopEnd > loopStart) {
            val bandHeight = 13.dp.toPx()
            drawRect(
                color = NocturneColors.accent.copy(alpha = 0.20f),
                topLeft = Offset(size.width * loopStart, midY - bandHeight / 2f),
                size = Size(size.width * (loopEnd - loopStart), bandHeight),
            )
        }

        drawLine(
            color = NocturneColors.text.copy(alpha = 0.14f),
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )

        drawLine(
            color = NocturneColors.accent,
            start = Offset(0f, midY),
            end = Offset(size.width * state.value, midY),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )

        loopStart?.let { drawTickMark(it, midY) }
        loopEnd?.let { drawTickMark(it, midY) }
    }
}

private fun DrawScope.drawTickMark(fraction: Float, midY: Float) {
    val x = size.width * fraction
    val topY = midY - 1.5.dp.toPx()
    drawLine(
        color = NocturneColors.accent400,
        start = Offset(x, topY),
        end = Offset(x, topY - 12.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )
}

@Composable
private fun SliderThumb(diameter: Dp, glow: Boolean) {
    Box(
        modifier = Modifier
            .size(diameter)
            .then(if (glow) Modifier.accentGlow(glowRadius = diameter / 2 + 12.dp, alpha = 0.7f) else Modifier)
            .background(NocturneColors.accent, CircleShape),
    )
}
