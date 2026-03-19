package com.example.voicelauncher

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationReaderService : NotificationListenerService() {

    companion object {
        @Volatile var latestNotification: String? = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { notification ->
            val packageName = notification.packageName
            
            // Relevante Kommunikations-Apps filtern
            if (packageName == "com.whatsapp" || packageName == "com.android.mms") {
                val extras = notification.notification.extras
                val title = extras.getString(android.app.Notification.EXTRA_TITLE)
                val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                
                if (title != null && text != null) {
                    val message = "Neue Nachricht von $title: $text"
                    Log.d("NotificationReader", message)
                    
                    // Speichern der letzten Nachricht im Companion Object (sehr simpel für MVP)
                    latestNotification = message
                    
                    // TODO: Wie weisen wir Gemini proaktiv darauf hin, dass eine Nachricht da ist?
                    // Idee: Das nächste Mal, wenn die Session anläuft, injizieren wir die Variable
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Ignorieren MVP
    }
}
