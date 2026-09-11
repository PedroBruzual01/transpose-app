package com.realtimetranspose

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.realtimetranspose.audio.AudioCaptureManager
import com.realtimetranspose.audio.AudioCaptureService
import com.realtimetranspose.ui.MainScreen

class MainActivity : ComponentActivity() {

    // Capture still works without this permission granted — it only gates
    // whether the persistent foreground-service notification is visible.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* no-op */ }

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
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(result.resultCode, data)
        AudioCaptureService.pendingMediaProjection = projection
        startForegroundService(
            Intent(this, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_START),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MainScreen(
                onStartCapture = {
                    val projectionManager = getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                },
                onStopCapture = {
                    startService(
                        Intent(this, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP),
                    )
                },
            )
        }
    }
}
