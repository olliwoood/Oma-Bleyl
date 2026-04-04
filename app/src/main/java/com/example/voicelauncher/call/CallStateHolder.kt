package com.example.voicelauncher.call

import androidx.compose.runtime.mutableStateOf

/**
 * Singleton das den aktuellen Anruf-Zustand hält.
 * Wird von CallService gesetzt, von der UI gelesen.
 */
object CallStateHolder {
    
    enum class State {
        NONE,       // Kein Anruf
        RINGING,    // Eingehender Anruf klingelt
        DIALING,    // Ausgehender Anruf wird aufgebaut
        ACTIVE,     // Anruf ist aktiv (Gespräch läuft)
        DISCONNECTED // Anruf wurde beendet
    }
    
    var callState = mutableStateOf(State.NONE)
    var callerName = mutableStateOf("")
    var callerNumber = mutableStateOf("")
    var isIncoming = mutableStateOf(false)
    var callStartTimeMs = mutableStateOf(0L)
    
    // Freisprechfunktion – immer an (Standardmäßig Lautsprecher)
    var isSpeakerOn = mutableStateOf(true)
    
    // Zeitstempel wann der letzte Anruf endete (für Post-Call-Schutz)
    var callEndedAtMs: Long = 0L
    
    // Flag: Wurde der Anruf bereits von Gemini bearbeitet (angenommen/abgelehnt)?
    // Wenn ja, wird keine erneute Zusammenfassung gesendet.
    var callHandledByGemini = false
    
    // Direkter Callback für Anruf-Zusammenfassung (kein Compose-State, feuert garantiert nur 1x)
    var onCallSummaryReady: ((String) -> Unit)? = null
    
    // Callback wenn eingehender Anruf klingelt (für Gemini-Session mit Sprachsteuerung)
    var onIncomingCallReady: ((String) -> Unit)? = null
    
    // Fallback: Wenn der Callback null ist (Activity noch nicht bereit),
    // wird der Prompt hier zwischengespeichert und von der Activity bei onResume abgeholt.
    var pendingIncomingPrompt: String? = null
    
    // Callback zum Umschalten des Lautsprechers (wird von MainActivity gesetzt)
    var onToggleSpeaker: ((Boolean) -> Unit)? = null
    
    val isInCall: Boolean
        get() = callState.value != State.NONE && callState.value != State.DISCONNECTED
    
    fun reset() {
        callState.value = State.NONE
        callerName.value = ""
        callerNumber.value = ""
        isIncoming.value = false
        callStartTimeMs.value = 0L
        isSpeakerOn.value = true  // Bleibt immer an
        // callEndedAtMs wird NICHT zurückgesetzt, damit der Post-Call-Guard funktioniert
        callHandledByGemini = false
        pendingIncomingPrompt = null
    }
}
