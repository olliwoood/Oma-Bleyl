package com.example.voicelauncher.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

/**
 * InCallService – Android leitet alle Anrufe an diesen Service weiter,
 * wenn die App als Standard-Telefon-App eingestellt ist.
 * Alle Ansagen laufen über Gemini (kein TTS mehr).
 */
class CallService : InCallService() {
    
    companion object {
        private const val CALL_NOTIFICATION_ID = 9002
        
        var currentCall: Call? = null
            private set
        
        fun answerCall() {
            currentCall?.answer(0)
            Log.d("CallService", "Anruf angenommen")
        }
        
        fun hangupCall() {
            currentCall?.disconnect()
            Log.d("CallService", "Anruf beendet")
        }
        
        // Referenz auf die aktive InCallService-Instanz für setAudioRoute()
        var activeInstance: CallService? = null
            private set
        
        fun setSpeakerRoute(speaker: Boolean) {
            val instance = activeInstance
            if (instance != null) {
                try {
                    if (speaker) {
                        instance.setAudioRoute(android.telecom.CallAudioState.ROUTE_SPEAKER)
                    } else {
                        instance.setAudioRoute(android.telecom.CallAudioState.ROUTE_EARPIECE)
                    }
                    Log.d("CallService", "Audio-Route gesetzt: ${if (speaker) "SPEAKER" else "EARPIECE"}")
                } catch (e: Exception) {
                    Log.e("CallService", "setAudioRoute fehlgeschlagen", e)
                }
            } else {
                Log.w("CallService", "Keine aktive InCallService-Instanz für setAudioRoute")
            }
        }
    }
    
    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            Log.d("CallService", "Call state changed: $state")
            updateCallState(call, state)
        }
    }
    
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d("CallService", "Call added")
        
        currentCall = call
        activeInstance = this
        call.registerCallback(callCallback)
        
        // Full-Screen Notification statt direktem startActivity() verwenden,
        // da Android 14+ Background Activity Launches blockiert.
        showCallNotification(call)
        
        val details = call.details
        val handle = details?.handle
        val number = handle?.schemeSpecificPart ?: "Unbekannt"
        val callerIdName = details?.callerDisplayName ?: ""
        val contactName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            details?.contactDisplayName ?: ""
        } else {
            ""
        }
        
        // Immer nachschlagen – contactDisplayName wird von Android asynchron befüllt und ist oft leer
        val lookedUpName = if (number != "Unbekannt") lookupContactByNumber(number) else ""
        
        val displayName = when {
            lookedUpName.isNotBlank() -> lookedUpName
            contactName.isNotBlank() -> contactName
            callerIdName.isNotBlank() -> callerIdName
            else -> number
        }
        
        Log.d("CallService", "Resolved: number='$number', final='$displayName'")
        
        CallStateHolder.callerNumber.value = number
        val existingName = CallStateHolder.callerName.value
        if (existingName.isBlank() || displayName != number) {
            CallStateHolder.callerName.value = displayName
        }
        
        val state = call.state
        // Ein Anruf ist eingehend, wenn er im Zustand RINGING ist
        // Ein Anruf ist ausgehend, wenn er im Zustand DIALING, CONNECTING oder SELECT_PHONE_ACCOUNT ist
        val isIncoming = when (state) {
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> false
            Call.STATE_RINGING -> true
            else -> call.details?.hasProperty(Call.Details.PROPERTY_IS_EXTERNAL_CALL) == true || 
                    (call.details?.intentExtras?.containsKey(android.telecom.TelecomManager.EXTRA_INCOMING_CALL_ADDRESS) == true)
        }
        
        CallStateHolder.isIncoming.value = isIncoming
        updateCallState(call, state)
        
        if (isIncoming) {
            val finalName = CallStateHolder.callerName.value
            val isKnown = finalName != number && finalName != "Unbekannt"
            
            val prompt = if (isKnown) {
                "Sage genau: '$finalName ruft an!'. Wiederhole das noch 2 mal mit kurzer Pause dazwischen. Sage NICHTS anderes, kein Kommentar, keine Einleitung."
            } else {
                "Sage genau: 'Unbekannte Nummer ruft an!'. Wiederhole das noch 2 mal mit kurzer Pause dazwischen. Sage NICHTS anderes, kein Kommentar, keine Einleitung."
            }
            Log.d("CallService", "Starte Gemini für eingehenden Anruf")
            val callback = CallStateHolder.onIncomingCallReady
            if (callback != null) {
                callback.invoke(prompt)
            } else {
                // Activity ist (noch) nicht bereit – Prompt zwischenspeichern.
                // Die Activity holt ihn in onResume() ab.
                Log.w("CallService", "onIncomingCallReady ist null, speichere Prompt für später")
                CallStateHolder.pendingIncomingPrompt = prompt
            }
        }
    }
    
    private fun lookupContactByNumber(number: String): String {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number)
            )
            val cursor = contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
                    it.getString(nameIdx) ?: ""
                } else ""
            } ?: ""
        } catch (e: Exception) {
            Log.e("CallService", "Fehler beim Kontakt-Lookup", e)
            ""
        }
    }
    
    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d("CallService", "Call removed")
        
        call.unregisterCallback(callCallback)
        
        val durationSeconds = if (CallStateHolder.callStartTimeMs.value > 0) {
            (System.currentTimeMillis() - CallStateHolder.callStartTimeMs.value) / 1000
        } else 0
        
        val disconnectCause = call.details?.disconnectCause
        val isMissed = disconnectCause?.code == android.telecom.DisconnectCause.MISSED
        val isRejected = disconnectCause?.code == android.telecom.DisconnectCause.REJECTED
        val isBusy = disconnectCause?.code == android.telecom.DisconnectCause.BUSY
        val isRemote = disconnectCause?.code == android.telecom.DisconnectCause.REMOTE
        val isLocal = disconnectCause?.code == android.telecom.DisconnectCause.LOCAL
        
        val wasConnected = durationSeconds > 0
        val name = CallStateHolder.callerName.value
        val incoming = CallStateHolder.isIncoming.value
        
        // Nur Zusammenfassung senden, wenn Gemini den Anruf NICHT schon VOR dem Verbinden abgelehnt hat
        // (Wenn der Anruf lief und dann aufgelegt wird, wollen wir immer eine Bestätigung)
        if (!CallStateHolder.callHandledByGemini || wasConnected) {
            val durationStr = if (wasConnected) " Dauer: ${durationSeconds}s." else ""
            val summaryText = buildString {
                append("[System] ")
                if (incoming) {
                    append("Eingehender Anruf von $name: ")
                    when {
                        wasConnected && isRemote -> append("$name hat aufgelegt.")
                        wasConnected && isLocal -> append("Nutzerin hat aufgelegt.")
                        wasConnected -> append("Gespräch beendet.")
                        isMissed -> append("Verpasst (nicht rangegangen).")
                        isRejected -> append("Aktiv abgelehnt.")
                        else -> append("Nicht angenommen.")
                    }
                } else {
                    append("Ausgehender Anruf an $name: ")
                    when {
                        wasConnected && isRemote -> append("$name hat aufgelegt.")
                        wasConnected && isLocal -> append("Nutzerin hat aufgelegt.")
                        wasConnected -> append("Gespräch beendet.")
                        isBusy -> append("Besetzt.")
                        else -> append("Niemand hat abgenommen.")
                    }
                }
                append(durationStr)
                append(" Das Mikrofon ist noch aktiv, die Nutzerin hört dich. Fasse den Anruf kurz zusammen und frag ob sie noch etwas braucht. Beende die Session NICHT von dir aus – die Nutzerin könnte Folgeanweisungen haben!")
            }
            
            val logEvent = buildString {
                if (incoming) append("Eingehender Anruf von $name ") else append("Ausgehender Anruf an $name ")
                when {
                    wasConnected && isRemote -> append("beendet (Andere Seite hat aufgelegt).")
                    wasConnected && isLocal -> append("beendet (Nutzerin hat aufgelegt).")
                    wasConnected -> append("beendet.")
                    isMissed -> append("verpasst.")
                    isRejected -> append("abgelehnt.")
                    isBusy -> append("besetzt.")
                    else -> append("nicht rangegangen.")
                }
            }
            com.example.voicelauncher.data.SessionLog.addEvent(applicationContext, logEvent)
            
            // Kurze Verzögerung, damit Android das Telecom-Audio sauber freigegeben hat
            android.os.Handler(mainLooper).postDelayed({
                CallStateHolder.onCallSummaryReady?.invoke(summaryText)
            }, 500)
        } else {
            Log.d("CallService", "Zusammenfassung übersprungen – Gemini hat den Anruf ungelesen abgelehnt")
        }
        currentCall = null
        activeInstance = null
        cancelCallNotification()
        
        CallStateHolder.callEndedAtMs = System.currentTimeMillis()
        CallStateHolder.callState.value = CallStateHolder.State.DISCONNECTED
        android.os.Handler(mainLooper).postDelayed({
            // Nur zurücksetzen, wenn kein neuer Anruf in der Zwischenzeit hereingekommen ist
            if (currentCall == null) {
                CallStateHolder.reset()
            }
            // Zurück zum Homescreen (App minimieren, Widget bleibt sichtbar)
            // Gemini-Session läuft im Hintergrund weiter (Mikro bleibt aktiv)
            try {
                val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_HOME)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
            } catch (e: Exception) {
                Log.e("CallService", "Konnte nach Anruf nicht zum Homescreen zurückkehren", e)
            }
        }, 2000)
    }
    
    private fun updateCallState(call: Call, state: Int) {
        CallStateHolder.callState.value = when (state) {
            Call.STATE_RINGING -> CallStateHolder.State.RINGING
            Call.STATE_DIALING, Call.STATE_CONNECTING -> CallStateHolder.State.DIALING
            Call.STATE_ACTIVE -> {
                CallStateHolder.callStartTimeMs.value = System.currentTimeMillis()
                CallStateHolder.State.ACTIVE
            }
            Call.STATE_DISCONNECTED -> CallStateHolder.State.DISCONNECTED
            else -> CallStateHolder.callState.value
        }
    }
    
    private fun showCallNotification(call: Call) {
        val channelId = "incoming_call_channel"
        val nm = getSystemService(NotificationManager::class.java)
        
        val channel = NotificationChannel(
            channelId, "Eingehende Anrufe", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Zeigt eingehende Anrufe an"
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
        
        // Bildschirm aufwecken (wichtig für eingehende Anrufe bei gesperrtem/ausgeschaltetem Screen)
        wakeUpScreen()
        
        val fullScreenIntent = android.content.Intent(this, com.example.voicelauncher.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("SHOW_CALL_SCREEN", true)
        }
        val fullScreenPi = PendingIntent.getActivity(
            this, 100, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Passenden Text für ein-/ausgehende Anrufe
        val isOutgoing = call.state == Call.STATE_DIALING || 
                         call.state == Call.STATE_CONNECTING || 
                         call.state == Call.STATE_SELECT_PHONE_ACCOUNT
        val notifText = if (isOutgoing) "Ausgehender Anruf..." else "Eingehender Anruf..."
        
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Anruf")
            .setContentText(notifText)
            .setFullScreenIntent(fullScreenPi, true)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .build()
        
        nm.notify(CALL_NOTIFICATION_ID, notification)
        
        // Zusätzlich: Activity direkt in den Vordergrund bringen.
        // InCallService hat eine spezielle Ausnahme von Android's BAL-Restrictions,
        // daher darf startActivity() hier aufgerufen werden.
        try {
            val bringToFrontIntent = android.content.Intent(this, com.example.voicelauncher.MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra("SHOW_CALL_SCREEN", true)
            }
            startActivity(bringToFrontIntent)
            Log.d("CallService", "Activity direkt in den Vordergrund gebracht")
        } catch (e: Exception) {
            Log.w("CallService", "startActivity fehlgeschlagen (BAL?), Notification sollte greifen", e)
        }
    }
    
    /**
     * Weckt den Bildschirm auf, damit die Activity sichtbar wird.
     * Nutzt einen WakeLock mit ACQUIRE_CAUSES_WAKEUP.
     */
    private fun wakeUpScreen() {
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isInteractive) {
                val wakeLock = pm.newWakeLock(
                    android.os.PowerManager.FULL_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                    "VoiceLauncher:IncomingCall"
                )
                wakeLock.acquire(10_000L) // 10 Sekunden, dann automatisch Release
                Log.d("CallService", "Bildschirm aufgeweckt via WakeLock")
            }
        } catch (e: Exception) {
            Log.e("CallService", "WakeLock fehlgeschlagen", e)
        }
    }
    
    private fun cancelCallNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(CALL_NOTIFICATION_ID)
    }
}
