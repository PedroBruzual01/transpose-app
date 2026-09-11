package com.realtimetranspose

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.realtimetranspose.audio.AudioCaptureManager
import com.realtimetranspose.audio.AudioCaptureService
import com.realtimetranspose.ui.MainScreen

class MainActivity : ComponentActivity() {

    // Capture still works without this permission granted — it only gates
    // whether the persistent foreground-service notification is visible.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* no-op */ }

    // AudioPlaybackCaptureConfiguration is consumed through AudioRecord, and
    // AudioRecord.Builder() throws SecurityException without this — even though
    // we never touch the microphone. Must be granted before requesting the
    // MediaProjection consent, or capture fails for a reason unrelated to
    // whether Spotify/YouTube actually allow themselves to be captured.
    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchProjectionRequest()
        } else {
            AudioCaptureManager.reportPermissionDenied()
        }
    }

    // This launches Android's own system dialog ("Start recording or casting?").
    // We never hide or skip it — the user must see and approve it every time,
    // and starting with Android 15 the OS itself won't let us cache the grant
    // across app restarts.
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            AudioCaptureManager.reportPermissionDenied()
            return@registerForActivityResult
        }
        // Do NOT call MediaProjectionManager.getMediaProjection() here: Android
        // requires an active foreground service of type mediaProjection *at the
        // moment the token is retrieved*, not just when the capture is built.
        // We only have that once AudioCaptureService.onCreate() has run, so hand
        // the raw result off to the service and let it fetch the token itself.
        startForegroundService(
            Intent(this, AudioCaptureService::class.java)
                .setAction(AudioCaptureService.ACTION_START)
                .putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MainScreen(
                onStartCapture = { onStartCaptureClicked() },
                onStopCapture = {
                    startService(
                        Intent(this, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP),
                    )
                },
            )
        }
    }

    private fun onStartCaptureClicked() {
        val hasRecordAudio = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        if (hasRecordAudio) {
            launchProjectionRequest()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchProjectionRequest() {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
