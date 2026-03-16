package com.example.voicelauncher.alarm

import androidx.compose.runtime.mutableStateOf

/**
 * Singleton das den aktuellen Wecker-Zustand hält.
 * Wird von AlarmService gesetzt, von der UI gelesen.
 */
object AlarmStateHolder {

    /** Ob gerade ein Wecker klingelt */
    var isAlarmRinging = mutableStateOf(false)

    /** Anzeige-Label, z.B. "7:00 Uhr" */
    var alarmLabel = mutableStateOf("")

    /** Callback wenn der Wecker klingelt, um Gemini ins Stille Zuhören zu versetzen */
    var onAlarmTriggered: ((String) -> Unit)? = null

    /** Callback wenn der Wecker durch den Nutzer am Bildschirm ausgeschaltet wurde */
    var onAlarmDismissedByUser: (() -> Unit)? = null

    fun reset() {
        isAlarmRinging.value = false
        alarmLabel.value = ""
    }
}
