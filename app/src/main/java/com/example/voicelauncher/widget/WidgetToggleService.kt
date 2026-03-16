package com.example.voicelauncher.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.example.voicelauncher.MainActivity
import com.example.voicelauncher.R

/**
 * Foreground Service, der beim Widget-Tipp gestartet wird und die
 * Mikrofon-Privilegien hält, solange die Gemini-Session aktiv ist.
 *
 * Lifecycle:
 *   Widget-Tipp → Service startet → toggleAudioSession() → Session aktiv
 *   Session endet (User oder Gemini) → Service stoppt sich selbst
 *   Widget-Tipp während aktiv → toggleAudioSession() stoppt Session → Service stoppt
 */
class WidgetToggleService : Service() {

    companion object {
        private const val CHANNEL_ID = "widget_toggle_channel"
        private const val NOTIFICATION_ID = 9001
        private const val TAG = "WidgetToggleService"

        fun start(context: Context) {
            val intent = Intent(context, WidgetToggleService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WidgetToggleService::class.java)
            context.stopService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service gestartet, toggle Assistent...")

        // Foreground Notification sofort anzeigen (Pflicht für startForegroundService)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Toggle ausführen
        val callback = VoiceLauncherWidget.onToggleAssistant
        if (callback != null) {
            callback.invoke()
        } else {
            Log.w(TAG, "onToggleAssistant ist null – MainActivity läuft nicht, stoppe Service")
            stopSelf()
        }

        // Service bleibt laufen! Wird von MainActivity gestoppt wenn die Session endet.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Assistent Widget",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Aktiv während der Assistent per Widget läuft"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val pendingTap = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Voice Launcher")
            .setContentText("Assistent aktiv")
            .setContentIntent(pendingTap)
            .setOngoing(true)
            .build()
    }
}
