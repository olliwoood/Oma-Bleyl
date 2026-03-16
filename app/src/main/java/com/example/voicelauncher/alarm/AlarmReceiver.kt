package com.example.voicelauncher.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver der vom AlarmManager aufgerufen wird.
 * Startet den AlarmService als Foreground Service.
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_LABEL = "alarm_label"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AlarmReceiver", "Wecker ausgelöst!")
        
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Wecker"
        
        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            putExtra(EXTRA_LABEL, label)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
