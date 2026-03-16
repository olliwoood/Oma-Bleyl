package com.example.voicelauncher.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.voicelauncher.MainActivity

/**
 * Foreground Service der den Alarmton spielt und Vibration auslöst.
 * Wird vom AlarmReceiver gestartet und per dismiss_alarm (Gemini) oder Tap gestoppt.
 */
class AlarmService : Service() {

    companion object {
        private const val CHANNEL_ID = "alarm_channel"
        private const val NOTIFICATION_ID = 42
        private const val AUTO_DISMISS_MS = 5L * 60 * 1000 // 5 Minuten
        
        private var instance: AlarmService? = null
        
        fun dismissAlarm() {
            instance?.stopAlarm()
        }
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private val autoDismissRunnable = Runnable {
        Log.d("AlarmService", "Auto-Dismiss nach 5 Minuten")
        stopAlarm()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(AlarmReceiver.EXTRA_LABEL) ?: "Wecker"
        
        Log.d("AlarmService", "Wecker klingelt: $label")
        
        // Foreground Notification (nötig für Android 8+)
        val notification = buildNotification(label)
        startForeground(NOTIFICATION_ID, notification)
        
        // Alarmton abspielen
        startRingtone()
        
        // Vibration starten
        startVibration()
        
        // State updaten
        AlarmStateHolder.alarmLabel.value = label
        AlarmStateHolder.isAlarmRinging.value = true
        
        // Gemini lautlos starten, damit man "Aus" rufen kann
        AlarmStateHolder.onAlarmTriggered?.invoke(label)
        
        // Auto-Dismiss nach 5 Minuten
        handler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
        
        return START_NOT_STICKY
    }
    
    private fun startRingtone() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            
            ringtone = RingtoneManager.getRingtone(this, alarmUri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }
            Log.d("AlarmService", "Alarmton gestartet")
        } catch (e: Exception) {
            Log.e("AlarmService", "Fehler beim Starten des Alarmtons", e)
        }
    }
    
    private fun startVibration() {
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            
            // Vibrationsmuster: 500ms Pause, 1000ms Vibration, 500ms Pause, ...
            val pattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0)) // 0 = repeat from index 0
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
            Log.d("AlarmService", "Vibration gestartet")
        } catch (e: Exception) {
            Log.e("AlarmService", "Fehler bei Vibration", e)
        }
    }
    
    private fun stopAlarm() {
        Log.d("AlarmService", "Wecker wird ausgeschaltet")
        
        // Ton stoppen
        ringtone?.stop()
        ringtone = null
        
        // Vibration stoppen
        vibrator?.cancel()
        vibrator = null
        
        // Auto-Dismiss Timer stoppen
        handler.removeCallbacks(autoDismissRunnable)
        
        // State zurücksetzen
        AlarmStateHolder.reset()
        
        // Service stoppen
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wecker",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Wecker-Benachrichtigungen"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun buildNotification(label: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("⏰ Wecker")
            .setContentText(label)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        ringtone?.stop()
        vibrator?.cancel()
        handler.removeCallbacks(autoDismissRunnable)
        instance = null
    }
}
