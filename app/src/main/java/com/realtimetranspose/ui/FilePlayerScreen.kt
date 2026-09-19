package com.realtimetranspose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.realtimetranspose.audio.PlayerState
import com.realtimetranspose.audio.TransposePlayer
import java.util.Locale

@Composable
fun FilePlayerScreen(onPickFile: () -> Unit) {
    val state by TransposePlayer.state.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Transpose", style = MaterialTheme.typography.headlineSmall)

            Button(onClick = onPickFile) {
                Text(if (state.isLoaded) "Cambiar archivo" else "Elegir archivo de audio")
            }

            state.fileName?.let { name ->
                Text(name, style = MaterialTheme.typography.bodyMedium)
            }

            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (state.isLoaded) {
                HorizontalDivider()
                PlaybackControls(state)
                HorizontalDivider()
                PitchSpeedControls(state)
            }
        }
    }
}

@Composable
private fun PlaybackControls(state: PlayerState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { if (state.isPlaying) TransposePlayer.pause() else TransposePlayer.play() }) {
            Text(if (state.isPlaying) "Pause" else "Play")
        }

        Slider(
            value = state.positionMs.toFloat().coerceIn(0f, maxOf(state.durationMs.toFloat(), 1f)),
            valueRange = 0f..maxOf(state.durationMs.toFloat(), 1f),
            onValueChange = { TransposePlayer.seekTo(it.toLong()) },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(state.positionMs), style = MaterialTheme.typography.bodySmall)
            Text(formatMs(state.durationMs), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PitchSpeedControls(state: PlayerState) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Pitch: ${formatSemitones(state.pitchSemitones)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = state.pitchSemitones,
            valueRange = -12f..12f,
            steps = 23,
            onValueChange = { TransposePlayer.setPitchSemitones(it) },
        )

        Text("Speed: ${String.format(Locale.US, "%.2fx", state.speed)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = state.speed,
            valueRange = 0.25f..4f,
            onValueChange = { TransposePlayer.setSpeed(it) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { TransposePlayer.setPitchSemitones(0f) }) { Text("Reset pitch") }
            Button(onClick = { TransposePlayer.setSpeed(1f) }) { Text("Reset speed") }
        }
    }
}

private fun formatSemitones(value: Float): String {
    val rounded = Math.round(value)
    return if (rounded > 0) "+$rounded" else rounded.toString()
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
