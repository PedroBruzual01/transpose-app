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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.realtimetranspose.audio.AudioCaptureManager
import com.realtimetranspose.audio.CapturePhase

@Composable
fun MainScreen(
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
) {
    val status by AudioCaptureManager.status.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Audio Capture Test", style = MaterialTheme.typography.headlineSmall)

            if (!status.isCapturing) {
                Button(onClick = onStartCapture) { Text("Start Capture") }
            } else {
                Button(onClick = onStopCapture) { Text("Stop Capture") }
            }

            HorizontalDivider()

            StatusRow("Capture state", status.state.label())
            StatusRow("Audio received", if (status.audioReceived) "TRUE" else "FALSE")
            StatusRow("RMS level", "%.4f".format(status.rmsLevel))
            StatusRow("Sample rate", if (status.sampleRate > 0) "${status.sampleRate} Hz" else "-")
            StatusRow("Channels", if (status.channelCount > 0) status.channelCount.toString() else "-")
            StatusRow("Frames read", status.framesRead.toString())
            status.lastError?.let { StatusRow("Last error", it) }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun CapturePhase.label(): String = when (this) {
    CapturePhase.IDLE -> "Idle"
    CapturePhase.CAPTURE_UNAVAILABLE -> "Capture unavailable"
    CapturePhase.WAITING_FOR_AUDIO -> "Waiting for audio"
    CapturePhase.PROCESSING -> "Processing"
    CapturePhase.AUDIO_LOST -> "Audio lost"
}
