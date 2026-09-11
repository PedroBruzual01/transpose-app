package com.realtimetranspose.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.IBinder
import com.realtimetranspose.MainActivity

/**
 * Owns the foreground-service lifecycle required by Android's MediaProjection
 * APIs. Does not hold audio state itself — that lives in [AudioCaptureManager]
 * — this class exists purely to satisfy "there must be an active foreground
 * service of type mediaProjection while capture is running".
 *
 * [MediaProjection] is not Parcelable, so it can't travel through an Intent.
 * The activity hands it off via [pendingMediaProjection] immediately before
 * calling startForegroundService(); onCreate() runs (and calls
 * startForeground()) before onStartCommand() picks it up, which keeps the
 * ordering Android requires.
 */
class AudioCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.realtimetranspose.action.START"
        const val ACTION_STOP = "com.realtimetranspose.action.STOP"

        private const val CHANNEL_ID = "audio_capture_service"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var pendingMediaProjection: MediaProjection? = null
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
                val projection = pendingMediaProjection
                pendingMediaProjection = null
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
