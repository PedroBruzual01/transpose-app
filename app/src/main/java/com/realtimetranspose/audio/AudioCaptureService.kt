package com.realtimetranspose.audio

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.realtimetranspose.MainActivity

private fun Intent.getParcelableExtraCompat(key: String): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }

/**
 * Owns the foreground-service lifecycle required by Android's MediaProjection
 * APIs. Does not hold audio state itself — that lives in [AudioCaptureManager]
 * — this class exists purely to satisfy "there must be an active foreground
 * service of type mediaProjection while capture is running".
 *
 * Android checks that requirement at [MediaProjectionManager.getMediaProjection]
 * call time, not just when the capture is actually built — so the (resultCode,
 * data) pair from the system consent dialog travels here via plain Intent
 * extras, and we fetch the token ourselves in [onStartCommand], which always
 * runs after [onCreate] has already called [startForeground].
 */
class AudioCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.realtimetranspose.action.START"
        const val ACTION_STOP = "com.realtimetranspose.action.STOP"
        const val EXTRA_RESULT_CODE = "com.realtimetranspose.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.realtimetranspose.extra.RESULT_DATA"

        private const val CHANNEL_ID = "audio_capture_service"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Idle"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtraCompat(EXTRA_RESULT_DATA)
                val projection = if (resultData != null) {
                    getSystemService(MediaProjectionManager::class.java)
                        .getMediaProjection(resultCode, resultData)
                } else {
                    null
                }
                if (projection != null) {
                    projection.registerCallback(
                        object : MediaProjection.Callback() {
                            override fun onStop() {
                                AudioCaptureManager.stop()
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        },
                        null,
                    )
                    AudioCaptureManager.start(projection)
                    updateNotification("Processing")
                } else {
                    AudioCaptureManager.reportPermissionDenied()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                AudioCaptureManager.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AudioCaptureManager.stop()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio Capture",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Real-Time Transpose")
            .setContentText("Capture: $statusText")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(statusText))
    }
}
