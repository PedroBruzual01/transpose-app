package com.realtimetranspose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.realtimetranspose.audio.PlayerState
import com.realtimetranspose.audio.TransposePlayer
import com.realtimetranspose.ui.components.CenteredValueSlider
import com.realtimetranspose.ui.components.CircleIconButton
import com.realtimetranspose.ui.components.ErrorCard
import com.realtimetranspose.ui.components.GhostTextButton
import com.realtimetranspose.ui.components.LoopMarkButton
import com.realtimetranspose.ui.components.ProgressSlider
import com.realtimetranspose.ui.components.SectionLabel
import com.realtimetranspose.ui.components.ValueMapping
import com.realtimetranspose.ui.theme.NocturneColors
import com.realtimetranspose.ui.theme.NocturneSpacing
import com.realtimetranspose.ui.theme.NocturneType
import com.realtimetranspose.ui.theme.accentGlow
import java.util.Locale
import kotlin.math.roundToInt

private const val SPEED_MIN = 0.25f
private const val SPEED_MAX = 4f

@Composable
fun FilePlayerScreen(onPickFile: () -> Unit) {
    val state by TransposePlayer.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(NocturneColors.bg)) {
        if (state.isLoaded) {
            LoadedFileScreen(state = state, onPickFile = onPickFile)
        } else {
            EmptyFileScreen(state = state, onPickFile = onPickFile)
        }
    }
}

@Composable
private fun EmptyFileScreen(state: PlayerState, onPickFile: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(NocturneSpacing.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(78.dp)
                .accentGlow(glowRadius = 79.dp, alpha = 0.16f)
                .border(1.dp, NocturneColors.accent700, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = NocturneColors.accent, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(NocturneSpacing.blockGap))
        Text("No file loaded", style = NocturneType.emptyTitle, color = NocturneColors.text)
        Spacer(Modifier.height(NocturneSpacing.blockInnerGap))
        Text(
            "Choose an mp3, m4a, wav or flac file from your device to start practicing.",
            style = NocturneType.body12_5,
            color = NocturneColors.textMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(NocturneSpacing.blockGap))
        OutlinePrimaryButton(text = "Choose file", onClick = onPickFile)

        state.error?.let { error ->
            Spacer(Modifier.height(NocturneSpacing.blockGap))
            ErrorCard(title = "Couldn't open the file", detail = error)
        }
    }
}

@Composable
private fun LoadedFileScreen(state: PlayerState, onPickFile: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NocturneSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(NocturneSpacing.blockGap),
    ) {
        FileCard(state = state, onPickFile = onPickFile)

        state.error?.let { error ->
            ErrorCard(title = "Playback error", detail = error)
        }

        PlaybackBlock(state)
        LoopBlock(state)
        PitchBlock(state)
        SpeedBlock(state)
    }
}

@Composable
private fun FileCard(state: PlayerState, onPickFile: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NocturneSpacing.radiusMd))
            .background(NocturneColors.surface)
            .border(1.dp, NocturneColors.divider, RoundedCornerShape(NocturneSpacing.radiusMd))
            .padding(horizontal = 11.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(NocturneSpacing.radiusSm))
                .background(NocturneColors.accent900),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = NocturneColors.accent400, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                state.fileName ?: "",
                style = NocturneType.body13,
                color = NocturneColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(fileMetadata(state), style = NocturneType.metadata, color = NocturneColors.textMuted)
        }
        GhostTextButton(text = "Change", onClick = onPickFile)
    }
}

private fun fileMetadata(state: PlayerState): String {
    val ext = state.fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    val duration = formatMs(state.durationMs)
    val khz = if (state.sampleRate > 0) "${state.sampleRate / 1000} kHz" else "-"
    val channels = when (state.channelCount) {
        1 -> "mono"
        2 -> "stereo"
        0 -> ""
        else -> "${state.channelCount} channels"
    }
    return listOf(ext, duration, "$khz $channels".trim()).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun PlaybackBlock(state: PlayerState) {
    Column(verticalArrangement = Arrangement.spacedBy(NocturneSpacing.blockInnerGap)) {
        val durationF = maxOf(state.durationMs.toFloat(), 1f)
        ProgressSlider(
            fraction = state.positionMs / durationF,
            onFractionChange = { TransposePlayer.seekTo((it * durationF).roundToInt().toLong()) },
            loopStartFraction = state.loopStartMs?.let { it / durationF },
            loopEndFraction = state.loopEndMs?.let { it / durationF },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(state.positionMs), style = NocturneType.mono12, color = NocturneColors.textMuted)
            Text(formatMs(state.durationMs), style = NocturneType.mono12, color = NocturneColors.textMuted)
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TransportButton(isPlaying = state.isPlaying, onClick = { if (state.isPlaying) TransposePlayer.pause() else TransposePlayer.play() })
        }
    }
}

@Composable
private fun TransportButton(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(66.dp)
            .accentGlow(glowRadius = 61.dp, alpha = 0.22f)
            .clip(CircleShape)
            .background(NocturneColors.bg)
            .border(1.dp, NocturneColors.accent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = NocturneColors.accent,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun LoopBlock(state: PlayerState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LoopMarkButton(label = "A", marked = state.loopStartMs != null, onClick = { TransposePlayer.markLoopStart() })
            LoopMarkButton(label = "B", marked = state.loopEndMs != null, onClick = { TransposePlayer.markLoopEnd() })
            CircleIconButton(
                icon = Icons.Outlined.Delete,
                onClick = { TransposePlayer.clearLoop() },
                diameter = 34.dp,
                iconSize = 16.dp,
                enabled = state.loopStartMs != null || state.loopEndMs != null,
                contentDescription = "Clear loop",
            )
        }
        Text(
            loopStatusText(state),
            style = NocturneType.mono12,
            color = if (state.isLoopActive) NocturneColors.accent300 else NocturneColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Visible,
        )
    }
}

private fun loopStatusText(state: PlayerState): String = when {
    state.isLoopActive -> "${formatMs(state.loopStartMs!!)} → ${formatMs(state.loopEndMs!!)}"
    state.loopStartMs != null -> "A=${formatMs(state.loopStartMs)} · missing B"
    state.loopEndMs != null -> "B=${formatMs(state.loopEndMs)} · missing A"
    else -> "off"
}

@Composable
private fun PitchBlock(state: PlayerState) {
    // state.pitchSemitones is the single total sent straight to Rubber Band
    // (it accepts fractional semitones natively, see PitchShiftEngine) — the
    // coarse semitone step and the fine cents offset below are just two
    // different-sized controls over the same value, decomposed via
    // truncation so e.g. -3.05 reads as coarse -3 / fine -5 cents, matching
    // how they'd recombine (coarse + fineCents / 100f).
    val coarse = state.pitchSemitones.toInt()
    val fineCents = ((state.pitchSemitones - coarse) * 100).roundToInt()

    Column(verticalArrangement = Arrangement.spacedBy(NocturneSpacing.blockInnerGap)) {
        SectionLabel(
            text = "Pitch",
            trailing = {
                GhostTextButton(text = "Reset", onClick = { TransposePlayer.setPitchSemitones(0f) }, enabled = state.pitchSemitones != 0f)
            },
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            CircleIconButton(
                icon = Icons.Outlined.Remove,
                onClick = { TransposePlayer.setPitchSemitones((coarse - 1).coerceAtLeast(-12) + fineCents / 100f) },
                enabled = coarse > -12,
                contentDescription = "Pitch down",
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatSigned(coarse), style = NocturneType.valueDisplay, color = NocturneColors.text)
                Text("semitones", style = NocturneType.metadata, color = NocturneColors.textMuted)
            }
            CircleIconButton(
                icon = Icons.Outlined.Add,
                onClick = { TransposePlayer.setPitchSemitones((coarse + 1).coerceAtMost(12) + fineCents / 100f) },
                enabled = coarse < 12,
                contentDescription = "Pitch up",
            )
        }
        CenteredValueSlider(
            fraction = ValueMapping.semitonesToFraction(state.pitchSemitones),
            onFractionChange = { TransposePlayer.setPitchSemitones(ValueMapping.fractionToSemitones(it) + fineCents / 100f) },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(NocturneSpacing.blockInnerGap)) {
        SectionLabel(
            text = "Fine tune",
            trailing = {
                GhostTextButton(text = "Reset", onClick = { TransposePlayer.setPitchSemitones(coarse.toFloat()) }, enabled = fineCents != 0)
            },
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            CircleIconButton(
                icon = Icons.Outlined.Remove,
                onClick = { TransposePlayer.setPitchSemitones(coarse + (fineCents - 1).coerceAtLeast(-50) / 100f) },
                enabled = fineCents > -50,
                contentDescription = "Fine tune down",
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatSigned(fineCents), style = NocturneType.valueDisplay, color = NocturneColors.text)
                Text("cents", style = NocturneType.metadata, color = NocturneColors.textMuted)
            }
            CircleIconButton(
                icon = Icons.Outlined.Add,
                onClick = { TransposePlayer.setPitchSemitones(coarse + (fineCents + 1).coerceAtMost(50) / 100f) },
                enabled = fineCents < 50,
                contentDescription = "Fine tune up",
            )
        }
        CenteredValueSlider(
            fraction = ValueMapping.centsToFraction(fineCents.toFloat()),
            onFractionChange = { TransposePlayer.setPitchSemitones(coarse + ValueMapping.fractionToCents(it) / 100f) },
        )
    }
}

@Composable
private fun SpeedBlock(state: PlayerState) {
    Column(verticalArrangement = Arrangement.spacedBy(NocturneSpacing.blockInnerGap)) {
        SectionLabel(
            text = "Speed",
            trailing = {
                GhostTextButton(text = "Reset", onClick = { TransposePlayer.setSpeed(1f) }, enabled = state.speed != 1f)
            },
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            CircleIconButton(
                icon = Icons.Outlined.Remove,
                onClick = { TransposePlayer.setSpeed((state.speed - 0.05f).coerceAtLeast(SPEED_MIN)) },
                enabled = state.speed > SPEED_MIN,
                contentDescription = "Speed down",
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(String.format(Locale.US, "%.2f×", state.speed), style = NocturneType.valueDisplay, color = NocturneColors.text)
                Text("0.25× – 4×", style = NocturneType.metadata, color = NocturneColors.textMuted)
            }
            CircleIconButton(
                icon = Icons.Outlined.Add,
                onClick = { TransposePlayer.setSpeed((state.speed + 0.05f).coerceAtMost(SPEED_MAX)) },
                enabled = state.speed < SPEED_MAX,
                contentDescription = "Speed up",
            )
        }
        CenteredValueSlider(
            fraction = ValueMapping.speedToFraction(state.speed, SPEED_MIN, SPEED_MAX),
            onFractionChange = { TransposePlayer.setSpeed(ValueMapping.fractionToSpeed(it, SPEED_MIN, SPEED_MAX)) },
        )
    }
}

@Composable
private fun OutlinePrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(NocturneSpacing.radiusMd))
            .border(1.dp, NocturneColors.accent, RoundedCornerShape(NocturneSpacing.radiusMd))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(text, style = NocturneType.button, color = NocturneColors.accent)
    }
}

private fun formatSigned(value: Int): String = if (value > 0) "+$value" else value.toString()

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
