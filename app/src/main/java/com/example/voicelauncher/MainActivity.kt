package com.example.voicelauncher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.example.voicelauncher.call.CallService
import com.example.voicelauncher.widget.VoiceLauncherWidget
import com.example.voicelauncher.widget.WidgetToggleService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.example.voicelauncher.audio.AudioPlayer
import com.example.voicelauncher.audio.AudioRecorder
import com.example.voicelauncher.audio.GeminiLiveClient
import com.example.voicelauncher.audio.ToolGroup
import java.util.concurrent.atomic.AtomicReference
import com.example.voicelauncher.call.CallLogScreen
import com.example.voicelauncher.call.CallScreen
import com.example.voicelauncher.call.CallStateHolder
import com.example.voicelauncher.alarm.AlarmReceiver
import com.example.voicelauncher.alarm.AlarmScreen
import com.example.voicelauncher.alarm.AlarmService
import com.example.voicelauncher.alarm.AlarmStateHolder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

// --- ActionState für temporäre Icons ---
object ActionStateHolder {
    val currentIcon = androidx.compose.runtime.mutableStateOf<String?>(null)
    private var showCounter = 0

    fun showIcon(icon: String, durationMs: Long = 3000L) {
        showCounter++
        val myCounter = showCounter
        currentIcon.value = icon
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (showCounter == myCounter) {
                currentIcon.value = null
            }
        }, durationMs)
    }
}

class MainActivity : ComponentActivity() {

    private val audioRecorder = AudioRecorder()
    private val audioPlayer = AudioPlayer()
    private val geminiClient by lazy { GeminiLiveClient(BuildConfig.GEMINI_API_KEY, this) }
    
    private var isSessionActive by mutableStateOf(false)
    private val pendingClientPrompt = AtomicReference<String?>(null)
    private var lastCallStartedMs = 0L

    // Proximity WakeLock: Bildschirm aus wenn Telefon am Ohr, Lautsprecher bleibt an
    private var proximityWakeLock: android.os.PowerManager.WakeLock? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (recordAudioGranted) {
            setupGeminiAudio()
        } else {
            Log.e("MainActivity", "Mikrofon-Berechtigung verweigert")
        }
        // Nach den normalen Permissions: Standard-Telefon-App anfragen
        requestDefaultDialerRole()
    }

    // Launcher für die "Standard-Telefon-App"-Anfrage
    private val requestDialerRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d("MainActivity", "Voice Launcher ist jetzt die Standard-Telefon-App!")
        } else {
            Log.d("MainActivity", "Nutzer hat die Standard-Telefon-App-Anfrage abgelehnt")
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Bildschirm einschalten und über Sperrbildschirm anzeigen für Anrufe
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        
        // Berechtigungen prüfen & anfordern
        val requiredPermissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG
        )

        val allPermissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allPermissionsGranted) {
             setupGeminiAudio()
             // Prüfen ob wir bereits Standard-Telefon-App sind
             requestDefaultDialerRole()
        } else {
             requestPermissionLauncher.launch(requiredPermissions)
        }
        
        // Auto-Update: Prüfe im Hintergrund auf neue Version bei GitHub
        com.example.voicelauncher.update.AppUpdater(this).checkForUpdate()
        
        // Widget-Callback registrieren (Broadcast statt Activity-Start)
        VoiceLauncherWidget.onToggleAssistant = {
            runOnUiThread { toggleAudioSession() }
        }

        // Falls Activity durch Widget-Service gestartet wurde → sofort togglen
        handleWidgetToggleIntent(intent)

        setContent {
            val provider = GoogleFont.Provider(
                providerAuthority = "com.google.android.gms.fonts",
                providerPackage = "com.google.android.gms",
                certificates = R.array.com_google_android_gms_fonts_certs
            )
            val fontName = GoogleFont("Roboto")
            val fontFamily = FontFamily(
                Font(googleFont = fontName, fontProvider = provider)
            )
            
            val baseline = androidx.compose.material3.Typography()
            
            val appTypography = androidx.compose.material3.Typography(
                displayLarge = baseline.displayLarge.copy(fontFamily = fontFamily),
                displayMedium = baseline.displayMedium.copy(fontFamily = fontFamily),
                displaySmall = baseline.displaySmall.copy(fontFamily = fontFamily),
                headlineLarge = baseline.headlineLarge.copy(fontFamily = fontFamily),
                headlineMedium = baseline.headlineMedium.copy(fontFamily = fontFamily),
                headlineSmall = baseline.headlineSmall.copy(fontFamily = fontFamily),
                titleLarge = baseline.titleLarge.copy(fontFamily = fontFamily),
                titleMedium = baseline.titleMedium.copy(fontFamily = fontFamily),
                titleSmall = baseline.titleSmall.copy(fontFamily = fontFamily),
                bodyLarge = baseline.bodyLarge.copy(fontFamily = fontFamily),
                bodyMedium = baseline.bodyMedium.copy(fontFamily = fontFamily),
                bodySmall = baseline.bodySmall.copy(fontFamily = fontFamily),
                labelLarge = baseline.labelLarge.copy(fontFamily = fontFamily),
                labelMedium = baseline.labelMedium.copy(fontFamily = fontFamily),
                labelSmall = baseline.labelSmall.copy(fontFamily = fontFamily)
            )

            MaterialTheme(typography = appTypography) {

                // Priorität: 1. Anruf, 2. Wecker, 3. Hauptscreen
                if (CallStateHolder.isInCall) {
                    CallScreen()
                } else if (AlarmStateHolder.isAlarmRinging.value) {
                    AlarmScreen()
                } else {

                // Tab-State: 0 = Telefon, 1 = Assistent
                var selectedTab by remember { mutableIntStateOf(1) }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Tab Content
                    Box(modifier = Modifier.weight(1f)) {
                        when (selectedTab) {
                            0 -> CallLogScreen()
                            1 -> VoiceAssistantTab()
                        }
                    }

                    // Bottom Navigation Bar - seniorengerecht (gross, klar)
                    NavigationBar(
                        containerColor = Color(0xFFF1F5F9),
                        tonalElevation = 8.dp,
                        modifier = Modifier.height(80.dp)
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.Phone,
                                    contentDescription = "Telefon",
                                    modifier = Modifier.size(32.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = "Telefon",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF4CAF50),
                                selectedTextColor = Color(0xFF4CAF50),
                                unselectedIconColor = Color(0xFF94A3B8),
                                unselectedTextColor = Color(0xFF94A3B8),
                                indicatorColor = Color(0xFFE8F5E9)
                            )
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Assistent",
                                    modifier = Modifier.size(32.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = "Assistent",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF3B82F6),
                                selectedTextColor = Color(0xFF3B82F6),
                                unselectedIconColor = Color(0xFF94A3B8),
                                unselectedTextColor = Color(0xFF94A3B8),
                                indicatorColor = Color(0xFFE3F2FD)
                            )
                        )
                    }
                }

                } // Ende else (kein aktiver Anruf)
            }
        }
    }

    @Composable
    private fun VoiceAssistantTab() {
        // --- ActionState für temporäre Icons ---
        val currentIcon = ActionStateHolder.currentIcon.value

        // Always Bright White for background so visually impaired users see the screen is on
        val bgColor = Color(0xFFFAFAFA)

        val circleColor by animateColorAsState(
            targetValue = if (isSessionActive) Color(0xFF4CAF50) else Color(0xFF3B82F6),
            animationSpec = tween(500),
            label = "circleColorAnim"
        )

        val haptic = LocalHapticFeedback.current
        val interactionSource = remember { MutableInteractionSource() }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = if (isSessionActive) "Mikrofon ist an. Tippen, um Zuhören zu beenden." else "Mikrofon ist aus. Tippen, um mit dem Assistenten zu sprechen."
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    toggleAudioSession()
                },
            color = bgColor
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Oberes Drittel für Text/Uhr
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 48.dp, start = 24.dp, end = 24.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    var currentTime by remember { mutableStateOf(LocalTime.now()) }
                    var lastTimeSpoken by remember { mutableLongStateOf(0L) }
                    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm") }

                    LaunchedEffect(Unit) {
                        while (true) {
                            currentTime = LocalTime.now()
                            delay(1000)
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        val now = System.currentTimeMillis()
                                        if (now - lastTimeSpoken > 5000L) { // 5 Sekunden Cooldown
                                            lastTimeSpoken = now
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            val dateFormat = java.text.SimpleDateFormat("EEEE, dd. MMMM", java.util.Locale.GERMANY).also { it.timeZone = java.util.TimeZone.getTimeZone("Europe/Berlin") }
                                            val currentDate = dateFormat.format(java.util.Date())
                                            val timeString = currentTime.format(formatter)
                                            val prompt = "Sage NUR folgenden Satz vor: 'Es ist $timeString Uhr, $currentDate.' Keine Einleitung, kein 'Gerne' und keine sonstigen Wörter hinzufügen!"
                                            if (!isSessionActive) {
                                                startSessionWithoutMic(prompt)
                                            } else {
                                                geminiClient.sendClientContent(prompt)
                                            }
                                        }
                                    }
                                )
                            },
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0xFFF1F5F9),
                    ) {
                        Text(
                            text = currentTime.format(formatter),
                            color = Color(0xFF1E293B),
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontFamily = FontFamily(Font(GoogleFont("Roboto"), GoogleFont.Provider("com.google.android.gms.fonts", "com.google.android.gms", R.array.com_google_android_gms_fonts_certs)))
                            )
                        )
                    }
                }

                // Mittleres Drittel für den starr fixierten Buttonbereich
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .background(
                                color = circleColor,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = currentIcon ?: if (isSessionActive) "🎤" else "🎙️",
                            label = "iconAnim"
                        ) { icon ->
                            Text(
                                text = icon,
                                fontSize = 120.sp
                            )
                        }
                    }
                }

                // Unteres Drittel als leerer Platzhalter
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Neuen Intent speichern (wichtig für singleTask)
        handleWidgetToggleIntent(intent)
        
        // Wenn der Intent von CallService kommt: Lockscreen entfernen
        if (intent.getBooleanExtra("SHOW_CALL_SCREEN", false)) {
            Log.d("MainActivity", "SHOW_CALL_SCREEN Intent empfangen, bringe App in den Vordergrund")
            dismissKeyguard()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Prüfe, ob ein eingehender Anruf-Prompt gespeichert wurde
        // (z.B. wenn die Activity noch nicht bereit war als der Anruf einging)
        val pendingPrompt = CallStateHolder.pendingIncomingPrompt
        if (pendingPrompt != null && CallStateHolder.isInCall) {
            CallStateHolder.pendingIncomingPrompt = null
            Log.d("MainActivity", "Verarbeite zwischengespeicherten IncomingCall-Prompt")
            boostAssistantVolume()
            startSessionWithPrompt(pendingPrompt, setOf(ToolGroup.CORE, ToolGroup.COMMUNICATION))
        }
    }
    
    /**
     * Entfernt den Lockscreen, damit die App sichtbar wird (z.B. bei eingehendem Anruf).
     */
    private fun dismissKeyguard() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                val km = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                km.requestDismissKeyguard(this, null)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "dismissKeyguard fehlgeschlagen", e)
        }
    }
    
    /**
     * Dreht die Medienlautstärke (für Geminis Sprachausgabe) auf Maximum,
     * damit sie neben dem Klingelton hörbar ist.
     * Merkt sich die vorherige Lautstärke und stellt sie nach dem Anruf wieder her.
     */
    private var savedMediaVolume = -1
    
    private fun boostAssistantVolume() {
        try {
            val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            savedMediaVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            
            // Klingellautstärke als Prozentsatz auslesen und auf Medienlautstärke übertragen
            val ringerVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_RING)
            val ringerMax = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_RING)
            val mediaMax = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            
            val targetVol = if (ringerMax > 0) {
                // 30% lauter als der Klingelton, damit Gemini trotz Klingeln hörbar ist
                ((ringerVol.toFloat() / ringerMax) * mediaMax * 1.3f).toInt().coerceIn(1, mediaMax)
            } else {
                mediaMax
            }
            
            audioManager.setStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                targetVol,
                0 // Keine UI-Anzeige
            )
            Log.d("MainActivity", "Medien-Lautstärke an Klingelton angepasst: $savedMediaVolume → $targetVol (Ringer: $ringerVol/$ringerMax, Media-Max: $mediaMax)")
        } catch (e: Exception) {
            Log.w("MainActivity", "Medien-Lautstärke anpassen fehlgeschlagen", e)
        }
    }
    
    private fun restoreAssistantVolume() {
        if (savedMediaVolume >= 0) {
            try {
                val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                audioManager.setStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    savedMediaVolume,
                    0
                )
                Log.d("MainActivity", "Medien-Lautstärke wiederhergestellt: $savedMediaVolume")
                savedMediaVolume = -1
            } catch (e: Exception) {
                Log.w("MainActivity", "Medien-Lautstärke wiederherstellen fehlgeschlagen", e)
            }
        }
    }

    private fun handleWidgetToggleIntent(intent: android.content.Intent?) {
        if (intent?.action == WidgetToggleService.ACTION_TOGGLE_FROM_WIDGET) {
            Log.d("MainActivity", "Widget-Toggle Intent empfangen, starte Session...")
            // Intent-Action zurücksetzen, damit es nicht bei Config-Change nochmal triggert
            intent.action = null
            // Kurz verzögern, damit onCreate/setupGeminiAudio fertig ist
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                toggleAudioSession()
            }, 300)
        }
    }

    private fun setupGeminiAudio() {
        // Callback für Anruf-Zusammenfassung registrieren
        CallStateHolder.onCallSummaryReady = { summaryText ->
            runOnUiThread {
                releaseProximityWakeLock()
                restoreAssistantVolume()
                // Nur loggen, keine Session starten nach Anruf
                Log.d("MainActivity", "Anruf beendet: $summaryText")
                com.example.voicelauncher.data.SessionLog.addEvent(applicationContext, summaryText)
            }
        }
        
        // Callback für eingehenden Anruf: Gemini-Session starten für Sprachsteuerung
        CallStateHolder.onIncomingCallReady = { prompt ->
            runOnUiThread {
                acquireProximityWakeLock()
                // Gemini-Lautstärke hochdrehen, damit Ansage neben Klingelton hörbar ist
                boostAssistantVolume()
                startSessionWithPrompt(prompt, setOf(ToolGroup.CORE, ToolGroup.COMMUNICATION))
            }
        }
        
        // Callback für Lautsprecher-Toggle im Call-Screen
        CallStateHolder.onToggleSpeaker = { enabled ->
            CallService.setSpeakerRoute(enabled)
            Log.d("MainActivity", "Lautsprecher umgeschaltet: $enabled")
        }
        
        // Callback für Wecker: Gemini startet, wenn der Wecker klingelt
        AlarmStateHolder.onAlarmTriggered = { label ->
            runOnUiThread {
                startSessionWithPrompt(
                    "Der Wecker ($label) klingelt in diesem Moment laut. Frage GANZ KURZ und sympathisch: 'Soll ich den Wecker ausstellen?' und warte auf die Antwort. " +
                    "Wenn der Nutzer mit Ja, Aus, Stopp, etc. antwortet, nutze sofort das Tool 'dismiss_alarm'.",
                    setOf(ToolGroup.CORE, ToolGroup.ALARM)
                )
            }
        }
        
        // Callback für Wecker: Gemini-Session NACH dem Ausschalten starten für kontextbezogene Begrüßung
        AlarmStateHolder.onAlarmDismissedByUser = {
            runOnUiThread {
                Log.d("MainActivity", "Wecker durch Tippen aus, starte Gemini-Begrüßung.")
                com.example.voicelauncher.data.SessionLog.addEvent(applicationContext, "Wecker abgestellt durch Tippen auf Bildschirm")
                
                // Kurze Verzögerung, damit der Wecker-Ton wirklich aus ist und der UI-Wechsel (Zurück zum Mikro) flüssig wirkt
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startSessionWithPrompt(
                        "Du bist gerade aufgewacht, der klingelnde Wecker wurde soeben durch Tippen abgestellt. " +
                        "Begrüße die Nutzerin freundlich und kontextbezogen (z.B. ein natürliches 'Guten Morgen, gut geschlafen?'). " +
                        "Schau in dein Session-Log (dein Kurzzeitgedächtnis) - oft steht da, warum sie den Wecker gestellt hat. Erwähne es passend, aber halte es kurz und menschlich."
                    )
                }, 1000)
            }
        }

        geminiClient.onAudioReceived = { pcmData ->
            // Audio von Gemini direkt abspielen
            audioPlayer.playAudio(pcmData)
        }

        // Mikro erst freigeben wenn AudioPlayer-Queue leer ist
        geminiClient.isAudioPlaybackActive = { audioPlayer.hasBufferedAudio() }

        geminiClient.onSetupComplete = {
            // Wenn wir eine Session _mit_ Mikro wollen, starten wir die Aufnahme
            if (isSessionActive) {
                audioRecorder.startRecording { audioChunk ->
                    geminiClient.sendAudio(audioChunk)
                }
            }
            
            // Falls wir einen offenen Prompt haben (z.B. vom beendeten Anruf oder Uhrzeit-Klick), jetzt schicken
            // getAndSet(null) liest und löscht atomar → kein doppeltes Senden
            pendingClientPrompt.getAndSet(null)?.let { prompt ->
                geminiClient.sendClientContent(prompt)
            }
        }

        geminiClient.onDisconnected = {
            // Verbindung verloren: Immer aufräumen.
            // Falls ein Auto-Reconnect in GeminiLiveClient läuft, wird onDisconnected
            // erst aufgerufen wenn ALLE Versuche gescheitert sind.
            Log.d("MainActivity", "Verbindung zu Gemini getrennt! pendingClientPrompt=${pendingClientPrompt.get()}")
            audioRecorder.stopRecording()
            pendingClientPrompt.set(null)
            runOnUiThread {
                isSessionActive = false
                VoiceLauncherWidget.updateWidget(this@MainActivity, false)
                WidgetToggleService.stop(this@MainActivity)
            }
        }
        
        

        geminiClient.onToolCallReceived = { name, args ->
            when (name) {
                "get_current_time" -> {
                    val dateFormat = java.text.SimpleDateFormat("EEEE, dd. MMMM yyyy, HH:mm", java.util.Locale.GERMANY).also { it.timeZone = java.util.TimeZone.getTimeZone("Europe/Berlin") }
                    val currentTimeString = dateFormat.format(java.util.Date())
                    Log.d("MainActivity", "Tool Called: get_current_time -> $currentTimeString")
                    
                    buildJsonObject {
                        put("currentTime", JsonPrimitive(currentTimeString))
                    }
                }
                "handle_memory" -> {
                    val action = args["action"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
                    val content = args["content"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
                    
                    val database = (application as VoiceLauncherApp).database
                    var resultString = "Unbekannte Aktion"

                    // Synchrone Blockierung für die Datenbank (in Produktion besser über Flow/Coroutines lösen)
                    runBlocking {
                        if (action == "save") {
                            database.memoryDao().insertMemory(
                                com.example.voicelauncher.data.MemoryEntry(
                                    content = content,
                                    timestampMs = System.currentTimeMillis()
                                )
                            )
                            resultString = "OK"
                        } else if (action == "search") {
                            val allMemories = database.memoryDao().getAllMemories()
                            // Simple Text-Suche (MVP)
                            val found = allMemories.filter { it.content.contains(content, ignoreCase = true) }
                            if (found.isEmpty()) {
                                resultString = "Keine Erinnerungen gefunden."
                            } else {
                                val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.GERMANY)
                                resultString = found.joinToString(" | ") { 
                                    "[Am ${sdf.format(java.util.Date(it.timestampMs))}]: ${it.content}" 
                                }
                            }
                        } else if (action == "delete") {
                            val allMemories = database.memoryDao().getAllMemories()
                            val found = allMemories.filter { it.content.contains(content, ignoreCase = true) }
                            if (found.isEmpty()) {
                                resultString = "Löschen fehlgeschlagen: Nichts Passendes zum Löschen gefunden."
                            } else {
                                for (memory in found) {
                                    database.memoryDao().deleteMemory(memory)
                                }
                                resultString = "${found.size} gelöscht."
                            }
                        }
                    }

                    Log.d("MainActivity", "Tool Called: handle_memory -> Action: $action, Result: $resultString")

                    buildJsonObject {
                        put("result", JsonPrimitive(resultString))
                    }
                }
                "manage_contacts" -> {
                    val action = args["action"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
                    val name = args["name"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
                    val newName = args["newName"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
                    val bDayRaw = args["birthdayDay"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
                    val bDay = bDayRaw?.toIntOrNull() ?: bDayRaw?.toDoubleOrNull()?.toInt()
                    val bMonthRaw = args["birthdayMonth"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
                    val bMonth = bMonthRaw?.toIntOrNull() ?: bMonthRaw?.toDoubleOrNull()?.toInt()

                    var resultString = "Fehler: Unbekannte Aktion."

                    when (action) {
                        "create" -> {
                            resultString = "Neue Kontakte erstellen ist nicht erlaubt. Alle Kontakte sind bereits im Adressbuch."
                        }
                        "update" -> {
                            try {
                                val contactId = findNativeContactId(name)
                                if (contactId == null) {
                                    resultString = "Kontakt '$name' nicht gefunden. Neue Kontakte können nicht erstellt werden."
                                } else {
                                    val rawContactId = getRawContactId(contactId)
                                    if (rawContactId == null) {
                                        resultString = "Fehler: Konnte den Kontakt intern nicht auflösen."
                                    } else {
                                        // Name aktualisieren
                                        if (newName != null) {
                                            val nameWhere = "${android.provider.ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${android.provider.ContactsContract.Data.MIMETYPE} = ?"
                                            val nameArgs = arrayOf(rawContactId.toString(), android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                                            val nameValues = android.content.ContentValues().apply {
                                                put(android.provider.ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, newName)
                                            }
                                            contentResolver.update(android.provider.ContactsContract.Data.CONTENT_URI, nameValues, nameWhere, nameArgs)
                                        }
                                        
                                        // Geburtstag aktualisieren
                                        if (bDay != null && bMonth != null) {
                                            val bdayStr = String.format("--%02d-%02d", bMonth, bDay)
                                            val bdayWhere = "${android.provider.ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${android.provider.ContactsContract.Data.MIMETYPE} = ? AND ${android.provider.ContactsContract.CommonDataKinds.Event.TYPE} = ?"
                                            val bdayArgs = arrayOf(rawContactId.toString(), android.provider.ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, android.provider.ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString())
                                            val bdayValues = android.content.ContentValues().apply {
                                                put(android.provider.ContactsContract.CommonDataKinds.Event.START_DATE, bdayStr)
                                                put(android.provider.ContactsContract.CommonDataKinds.Event.TYPE, android.provider.ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
                                            }
                                            val updated = contentResolver.update(android.provider.ContactsContract.Data.CONTENT_URI, bdayValues, bdayWhere, bdayArgs)
                                            if (updated == 0) {
                                                // Kein bestehender Geburtstag, neu einfügen
                                                val bdayInsert = android.content.ContentValues().apply {
                                                    put(android.provider.ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                                                    put(android.provider.ContactsContract.Data.MIMETYPE, android.provider.ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                                                    put(android.provider.ContactsContract.CommonDataKinds.Event.START_DATE, bdayStr)
                                                    put(android.provider.ContactsContract.CommonDataKinds.Event.TYPE, android.provider.ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
                                                }
                                                contentResolver.insert(android.provider.ContactsContract.Data.CONTENT_URI, bdayInsert)
                                            }
                                        }
                                        
                                        ActionStateHolder.showIcon("👤")
                                        resultString = "OK, '${newName ?: name}' aktualisiert."
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "manage_contacts update fehlgeschlagen", e)
                                resultString = "Fehler beim Aktualisieren des Kontakts: ${e.message}"
                            }
                        }
                        "delete" -> {
                            resultString = "Das Löschen von Kontakten ist aus Sicherheitsgründen nicht erlaubt."
                        }
                        "merge" -> {
                            resultString = "Das Zusammenführen von Kontakten wird nicht mehr unterstützt."
                        }
                    }
                    Log.d("MainActivity", "Tool Called: manage_contacts -> action:$action name:$name result:$resultString")
                    buildJsonObject { put("result", JsonPrimitive(resultString)) }
                }
                "get_birthdays" -> {
                    val name = args["name"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
                    var resultString = "Keine Geburtstage gefunden."

                    try {
                        val birthdays = getNativeBirthdays()
                        if (name.isNullOrEmpty()) {
                            if (birthdays.isEmpty()) {
                                resultString = "Es sind keine Geburtstage im Adressbuch gespeichert."
                            } else {
                                resultString = "Gespeicherte Geburtstage:\n" + birthdays.joinToString("\n") {
                                    "- ${it.first}: ${it.second}"
                                }
                            }
                        } else {
                            Log.d("MainActivity", "get_birthdays: Suche nach '$name', ${birthdays.size} Geburtstage vorhanden: ${birthdays.map { it.first }}")
                            val normalizedSearch = normalizeUmlauts(name)
                            val match = birthdays.find { 
                                val normalizedContact = normalizeUmlauts(it.first)
                                normalizedContact.contains(normalizedSearch, ignoreCase = true) || normalizedSearch.contains(normalizedContact, ignoreCase = true)
                            }
                            if (match != null) {
                                resultString = "${match.first} hat am ${match.second} Geburtstag."
                            } else {
                                resultString = "Ich habe keinen Geburtstag für '$name' gefunden."
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "get_birthdays fehlgeschlagen", e)
                        resultString = "Fehler beim Lesen der Geburtstage: ${e.message}"
                    }
                    Log.d("MainActivity", "Tool Called: get_birthdays -> $resultString")
                    buildJsonObject { put("result", JsonPrimitive(resultString)) }
                }
                "manage_calendar" -> {
                    val action = args["action"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
                    val title = args["title"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
                    var resultString = "Fehler: Unbekannte Aktion."

                    // Hilfsfunktion: Gemini schickt Zahlen manchmal als "15.0" statt "15"
                    fun safeInt(key: String): Int? {
                        val raw = args[key]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: return null
                        return raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
                    }

                    Log.d("MainActivity", "manage_calendar: action=$action title=$title args=$args")

                    runBlocking {
                        try {
                            when (action) {
                                "add" -> {
                                    val day = safeInt("day")
                                    val month = safeInt("month")
                                    val year = safeInt("year")
                                    val hour = safeInt("hour")
                                    val minute = safeInt("minute")
                                    val durationMinutes = safeInt("durationMinutes") ?: 60

                                    Log.d("MainActivity", "manage_calendar add: day=$day month=$month year=$year hour=$hour minute=$minute duration=$durationMinutes")

                                    if (day != null && month != null && year != null) {
                                        val isAllDay = (hour == null || minute == null)

                                        // Kalender-ID finden: Einfach den ersten sichtbaren Kalender nehmen
                                        var calId: Long? = null
                                        val calProjection = arrayOf(
                                            android.provider.CalendarContract.Calendars._ID,
                                            android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                                            android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
                                        )
                                        val calCursor = contentResolver.query(
                                            android.provider.CalendarContract.Calendars.CONTENT_URI,
                                            calProjection,
                                            "${android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${android.provider.CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                                            null,
                                            null
                                        )
                                        calCursor?.use { cc ->
                                            val idIdx = cc.getColumnIndex(android.provider.CalendarContract.Calendars._ID)
                                            val nameIdx = cc.getColumnIndex(android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                                            while (cc.moveToNext()) {
                                                val cId = cc.getLong(idIdx)
                                                val cName = cc.getString(nameIdx) ?: "?"
                                                Log.d("MainActivity", "manage_calendar: Gefundener Kalender: id=$cId name=$cName")
                                                if (calId == null) {
                                                    calId = cId // Den ersten verfügbaren nehmen
                                                }
                                            }
                                        }

                                        Log.d("MainActivity", "manage_calendar: Gewählte calId=$calId")

                                        if (calId != null) {
                                            val values = android.content.ContentValues()
                                            values.put(android.provider.CalendarContract.Events.TITLE, title)
                                            values.put(android.provider.CalendarContract.Events.CALENDAR_ID, calId)

                                            if (isAllDay) {
                                                // Ganztägiges Event: UTC-Mitternacht verwenden
                                                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                                                cal.set(year, month - 1, day, 0, 0, 0)
                                                cal.set(java.util.Calendar.MILLISECOND, 0)
                                                val startMillis = cal.timeInMillis
                                                cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                                                val endMillis = cal.timeInMillis

                                                values.put(android.provider.CalendarContract.Events.DTSTART, startMillis)
                                                values.put(android.provider.CalendarContract.Events.DTEND, endMillis)
                                                values.put(android.provider.CalendarContract.Events.ALL_DAY, 1)
                                                values.put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                                            } else {
                                                val cal = java.util.Calendar.getInstance()
                                                cal.set(year, month - 1, day, hour!!, minute!!, 0)
                                                cal.set(java.util.Calendar.MILLISECOND, 0)
                                                val startMillis = cal.timeInMillis
                                                val endMillis = startMillis + durationMinutes * 60 * 1000L

                                                values.put(android.provider.CalendarContract.Events.DTSTART, startMillis)
                                                values.put(android.provider.CalendarContract.Events.DTEND, endMillis)
                                                values.put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                                            }

                                            Log.d("MainActivity", "manage_calendar: insert values=$values allDay=$isAllDay")
                                            val uri = contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
                                            Log.d("MainActivity", "manage_calendar: insert result uri=$uri")
                                            if (uri != null) {
                                                ActionStateHolder.showIcon("📅")
                                                if (isAllDay) {
                                                    resultString = "OK, '$title' am $day.$month.$year als Ganztagesereignis gespeichert."
                                                } else {
                                                    val minStr = minute!!.toString().padStart(2, '0')
                                                    resultString = "OK, '$title' am $day.$month.$year um $hour:$minStr gespeichert."
                                                }
                                            } else {
                                                resultString = "Fehler: Konnte den Termin nicht im Kalender speichern (insert returned null)."
                                            }
                                        } else {
                                            resultString = "Fehler: Kein beschreibbarer Kalender auf dem Gerät gefunden. Bitte prüfen, ob ein Google-Konto verknüpft ist."
                                        }
                                    } else {
                                        resultString = "Fehler: Es fehlen Datumsangaben für den Termin. Parsed: day=$day month=$month year=$year"
                                    }
                                }
                                "list" -> {
                                    val now = System.currentTimeMillis()
                                    val next30Days = now + 30L * 24 * 60 * 60 * 1000L

                                    val builder = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
                                    android.content.ContentUris.appendId(builder, now)
                                    android.content.ContentUris.appendId(builder, next30Days)

                                    val projection = arrayOf(
                                        android.provider.CalendarContract.Instances.TITLE,
                                        android.provider.CalendarContract.Instances.BEGIN,
                                        android.provider.CalendarContract.Instances.ALL_DAY
                                    )

                                    val cursor = contentResolver.query(builder.build(), projection, null, null, "${android.provider.CalendarContract.Instances.BEGIN} ASC")
                                    val events = mutableListOf<String>()
                                    if (cursor != null) {
                                        val sdfDateTime = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.GERMANY)
                                        val sdfDateOnly = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY)
                                        val titleIdx = cursor.getColumnIndex(android.provider.CalendarContract.Instances.TITLE)
                                        val beginIdx = cursor.getColumnIndex(android.provider.CalendarContract.Instances.BEGIN)
                                        val allDayIdx = cursor.getColumnIndex(android.provider.CalendarContract.Instances.ALL_DAY)
                                        var count = 0
                                        while (cursor.moveToNext() && count < 10) {
                                            val eTitle = cursor.getString(titleIdx) ?: "Ohne Titel"
                                            val eBegin = cursor.getLong(beginIdx)
                                            val isAllDay = allDayIdx >= 0 && cursor.getInt(allDayIdx) == 1
                                            if (isAllDay) {
                                                events.add("- ${sdfDateOnly.format(java.util.Date(eBegin))}: $eTitle (ganztägig)")
                                            } else {
                                                events.add("- ${sdfDateTime.format(java.util.Date(eBegin))} Uhr: $eTitle")
                                            }
                                            count++
                                        }
                                        cursor.close()
                                    }

                                    if (events.isEmpty()) {
                                        resultString = "Es stehen momentan keine zukünftigen Termine im Kalender."
                                    } else {
                                        resultString = "Zukünftige Termine:\n" + events.joinToString("\n")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "manage_calendar fehlgeschlagen", e)
                            resultString = "Fehler beim Zugriff auf den Google-Kalender: ${e.message}"
                        }
                    }
                    Log.d("MainActivity", "Tool Called: manage_calendar -> action:$action title:$title result:$resultString")
                    buildJsonObject { put("result", JsonPrimitive(resultString)) }
                }
                "delete_calendar_event" -> {
                    val title = args["title"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
                    var resultString = ""
                    
                    val safeInt: (String) -> Int? = { key ->
                        val raw = args[key]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: null
                        raw?.toIntOrNull() ?: raw?.toDoubleOrNull()?.toInt()
                    }

                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                        resultString = "Fehler: Keine Berechtigung für den Kalender."
                    } else {
                        try {
                            val targetDay = safeInt("day")
                            val targetMonth = safeInt("month")
                            val targetYear = safeInt("year")

                            val projection = arrayOf(
                                android.provider.CalendarContract.Events._ID,
                                android.provider.CalendarContract.Events.DTSTART,
                                android.provider.CalendarContract.Events.TITLE
                            )
                            
                            val selection: String?
                            val selectionArgs: Array<String>?
                            
                            if (title.isNotEmpty()) {
                                selection = "${android.provider.CalendarContract.Events.TITLE} LIKE ?"
                                selectionArgs = arrayOf("%$title%")
                            } else {
                                selection = null
                                selectionArgs = null
                            }

                            val cursor = contentResolver.query(
                                android.provider.CalendarContract.Events.CONTENT_URI,
                                projection, selection, selectionArgs,
                                "${android.provider.CalendarContract.Events.DTSTART} ASC"
                            )

                            val idsToDelete = mutableListOf<Long>()
                            if (cursor != null) {
                                while (cursor.moveToNext()) {
                                    val eventId = cursor.getLong(0)
                                    val startMillis = cursor.getLong(1)
                                    
                                    if (targetDay != null && targetMonth != null && targetYear != null) {
                                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = startMillis }
                                        val eDay = cal.get(java.util.Calendar.DAY_OF_MONTH)
                                        val eMonth = cal.get(java.util.Calendar.MONTH) + 1
                                        val eYear = cal.get(java.util.Calendar.YEAR)
                                        if (eDay == targetDay && eMonth == targetMonth && eYear == targetYear) {
                                            idsToDelete.add(eventId)
                                        }
                                    } else if (title.isNotEmpty()) {
                                        // Ohne Datum nur NACH der Vergangenheit liegende Termine löschen (aber nur EINEN, den nächsten gefundenen)
                                        if (startMillis >= System.currentTimeMillis() - 24 * 60 * 60 * 1000L) {
                                            idsToDelete.add(eventId)
                                            break
                                        }
                                    }
                                }
                                cursor.close()
                            }

                            if (idsToDelete.isNotEmpty()) {
                                var deletedCount = 0
                                for (id in idsToDelete) {
                                    val uriToDelete = android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, id)
                                    deletedCount += contentResolver.delete(uriToDelete, null, null)
                                }
                                if (title.isEmpty()) {
                                    resultString = "Alle $deletedCount Termin(e) am $targetDay.$targetMonth.$targetYear erfolgreich abgesagt."
                                } else {
                                    resultString = "OK, '$title' abgesagt."
                                }
                                ActionStateHolder.showIcon("🗑️")
                            } else {
                                if (title.isEmpty()) {
                                    resultString = "Fehler: Ich konnte keine Termine am $targetDay.$targetMonth.$targetYear finden."
                                } else {
                                    resultString = "Fehler: Ich konnte keinen anstehenden Termin namens '$title' finden" + (if (targetDay != null) " an diesem Datum." else ".")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "delete_calendar_event fehlgeschlagen", e)
                            resultString = "Fehler beim Absagen des Termins: ${e.message}"
                        }
                    }
                    Log.d("MainActivity", "Tool Called: delete_calendar_event -> title:$title result:$resultString")
                    buildJsonObject { put("result", JsonPrimitive(resultString)) }
                }
                "reschedule_calendar_event" -> {
                    val title = args["title"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
                    var resultString = ""
                    
                    val safeInt: (String) -> Int? = { key ->
                        val raw = args[key]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: null
                        raw?.toIntOrNull() ?: raw?.toDoubleOrNull()?.toInt()
                    }

                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                        resultString = "Fehler: Keine Berechtigung für den Kalender."
                    } else {
                        try {
                            val newDay = safeInt("day")
                            val newMonth = safeInt("month")
                            val newYear = safeInt("year")
                            val newHour = safeInt("hour")
                            val newMinute = safeInt("minute")
                            val newDurationMinutes = safeInt("durationMinutes")

                            // Finde den Event zuerst
                            val projection = arrayOf(
                                android.provider.CalendarContract.Events._ID,
                                android.provider.CalendarContract.Events.DTSTART,
                                android.provider.CalendarContract.Events.DTEND,
                                android.provider.CalendarContract.Events.CALENDAR_ID
                            )
                            val selection = "${android.provider.CalendarContract.Events.TITLE} LIKE ?"
                            val selectionArgs = arrayOf("%$title%")
                            // Wir nehmen das nächste/erste gefundene Event in der Zukunft (oder alle, aber sortiert nach Startzeit)
                            val cursor = contentResolver.query(
                                android.provider.CalendarContract.Events.CONTENT_URI,
                                projection, selection, selectionArgs,
                                "${android.provider.CalendarContract.Events.DTSTART} ASC"
                            )

                            cursor?.use { c ->
                                if (c.moveToFirst()) {
                                    val eventId = c.getLong(0)
                                    val oldStart = c.getLong(1)
                                    val oldEnd = c.getLong(2)
                                    val calId = c.getLong(3)

                                    val calStart = java.util.Calendar.getInstance().apply { timeInMillis = oldStart }
                                    val oldDurationMinutes = ((oldEnd - oldStart) / (60 * 1000L)).toInt()

                                    // Falls Werte übergeben wurden, überschreiben wir die alten:
                                    val finalYear = newYear ?: calStart.get(java.util.Calendar.YEAR)
                                    val finalMonth = newMonth ?: (calStart.get(java.util.Calendar.MONTH) + 1)
                                    val finalDay = newDay ?: calStart.get(java.util.Calendar.DAY_OF_MONTH)
                                    val finalHour = newHour ?: calStart.get(java.util.Calendar.HOUR_OF_DAY)
                                    val finalMinute = newMinute ?: calStart.get(java.util.Calendar.MINUTE)
                                    val finalDuration = newDurationMinutes ?: oldDurationMinutes

                                    val newCal = java.util.Calendar.getInstance()
                                    // Achtung: Monat ist 0-basiert in Calendar
                                    newCal.set(finalYear, finalMonth - 1, finalDay, finalHour, finalMinute, 0)
                                    newCal.set(java.util.Calendar.MILLISECOND, 0)
                                    
                                    val newStartMillis = newCal.timeInMillis
                                    val newEndMillis = newStartMillis + (finalDuration * 60 * 1000L)

                                    // Altes Event löschen
                                    val uriToDelete = android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, eventId)
                                    contentResolver.delete(uriToDelete, null, null)

                                    // Neues Event einfügen
                                    val values = android.content.ContentValues().apply {
                                        put(android.provider.CalendarContract.Events.DTSTART, newStartMillis)
                                        put(android.provider.CalendarContract.Events.DTEND, newEndMillis)
                                        put(android.provider.CalendarContract.Events.TITLE, title)
                                        put(android.provider.CalendarContract.Events.CALENDAR_ID, calId)
                                        put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                                    }
                                    val uri = contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
                                    
                                    if (uri != null) {
                                        ActionStateHolder.showIcon("📅")
                                        val minStr = finalMinute.toString().padStart(2, '0')
                                        resultString = "OK, '$title' verschoben auf $finalDay.$finalMonth.$finalYear um $finalHour:$minStr."
                                    } else {
                                        resultString = "Fehler: Alter Termin gelöscht, aber neuer konnte nicht gespeichert werden!"
                                    }
                                } else {
                                    resultString = "Fehler: Es konnte kein bestehender Termin mit Titel '$title' gefunden werden."
                                }
                            } ?: run {
                                resultString = "Fehler: Kalenderabfrage fehlgeschlagen."
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "reschedule_calendar_event fehlgeschlagen", e)
                            resultString = "Fehler beim Verschieben des Termins: ${e.message}"
                        }
                    }
                    Log.d("MainActivity", "Tool Called: reschedule_calendar_event -> title:$title result:$resultString")
                    buildJsonObject { put("result", JsonPrimitive(resultString)) }
                }
                "get_contact_list" -> {
                    var resultString = "Keine Kontakte im Telefonbuch gefunden."
                    val contactsList = mutableListOf<String>()
                    
                    try {
                        // Kontakte mit Nummern laden
                        val processedNames = mutableSetOf<String>()
                        val cursor = contentResolver.query(
                            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(
                                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                            ),
                            null, null,
                            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                        )
                        cursor?.use {
                            val nameIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                            val idIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                            while (it.moveToNext()) {
                                val name = it.getString(nameIndex) ?: continue
                                if (processedNames.contains(name)) continue
                                processedNames.add(name)
                                
                                val contactId = it.getLong(idIndex)
                                val rawId = getRawContactId(contactId)
                                val notesText = if (rawId != null) readContactNotes(rawId) else null
                                val pureNotes = if (notesText != null) extractPureNotes(notesText) else null
                                val aliasStr = if (notesText != null) extractAliasesFromNotes(notesText) else null
                                
                                var line = name
                                if (!pureNotes.isNullOrEmpty()) line += " [Notizen: $pureNotes]"
                                if (!aliasStr.isNullOrEmpty()) line += " {Alias: $aliasStr}"
                                contactsList.add(line)
                            }
                        }

                        if (contactsList.isNotEmpty()) {
                            resultString = "Verfügbare Kontakte:\n" + contactsList.joinToString("\n")
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Fehler beim Lesen der Kontakte", e)
                        resultString = "Fehler beim Lesen der Kontakte. Hast du die Berechtigung?"
                    }
                    
                    Log.d("MainActivity", "Tool Called: get_contact_list -> Found ${contactsList.size} contacts")
                    
                    buildJsonObject {
                        put("result", JsonPrimitive(resultString))
                    }
                }
                "make_phone_call" -> {
                    val queryStr = args["query"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
                    val searchStr = queryStr.trim { it <= ' ' || it == '.' || it == ',' || it == '?' || it == '!' || it == '"' || it == '\'' }
                    var resultString = "Ich konnte leider keinen Kontakt für '$searchStr' im Telefonbuch finden."
                    
                    Log.d("MainActivity", "Tool Called: make_phone_call -> Query: '$queryStr', Bereinigt: '$searchStr'")
                    
                    // Debounce: Doppelte Anrufe innerhalb von 10 Sekunden verhindern
                    val now = System.currentTimeMillis()
                    if (now - lastCallStartedMs < 10_000) {
                        Log.w("MainActivity", "make_phone_call ignoriert – Anruf wurde bereits vor ${now - lastCallStartedMs}ms gestartet")
                        resultString = "Ein Anruf wurde bereits gestartet. Bitte warte einen Moment."
                    } else if (searchStr.isNotEmpty()) {
                        val result = findNativeContactWithPhone(searchStr)

                        if (result != null && result.first == "AMBIGUOUS") {
                            // Mehrere Kontakte gefunden → Gemini soll nachfragen
                            resultString = "Mehrere Kontakte gefunden: ${result.second}. Frage die Nutzerin, welchen sie meint."
                        } else if (result != null) {
                            try {
                                val telecomManager = getSystemService(android.telecom.TelecomManager::class.java)
                                val callUri = android.net.Uri.parse("tel:${result.second}")
                                
                                if (androidx.core.app.ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                                    lastCallStartedMs = now
                                    ActionStateHolder.showIcon("📞")
                                    resultString = "done"
                                    com.example.voicelauncher.data.SessionLog.addEvent(applicationContext, "Ausgehender Anruf an ${result.first} gestartet")
                                    
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        acquireProximityWakeLock()
                                        audioRecorder.stopRecording()
                                        isSessionActive = false
                                        CallStateHolder.callerName.value = result.first
                                        telecomManager?.placeCall(callUri, null)
                                    }, 4000)
                                } else {
                                    resultString = "Der Anruf konnte nicht gestartet werden. Ruf-Berechtigung fehlt."
                                }
                            } catch (e: Exception) {
                                resultString = "Der Anruf konnte leider beim System nicht registriert werden."
                                Log.e("MainActivity", "Call failed", e)
                            }
                        } else {
                            resultString = "Fehler: Kein Kontakt für '$searchStr' gefunden. Bitte frage genauer oder nutze 'get_contact_list', um nachzusehen, wie der Name im echten Telefonbuch geschrieben wird."
                        }
                    } else {
                        resultString = "Bitte nenne einen Namen, den ich anrufen soll."
                    }

                    Log.d("MainActivity", "make_phone_call Result: $resultString")

                    buildJsonObject {
                        put("result", JsonPrimitive(resultString))
                    }
                }
                "answer_call" -> {
                    Log.d("MainActivity", "Tool Called: answer_call")
                    CallStateHolder.callHandledByGemini = true
                    // Gemini-Session stoppen bevor der Anruf angenommen wird
                    // (Telecom übernimmt Audio-Fokus, Session würde sonst sterben)
                    // Nach dem Anruf wird in onCallSummaryReady eine neue Session gestartet.
                    android.os.Handler(mainLooper).postDelayed({
                        audioRecorder.stopRecording()
                        isSessionActive = false
                        geminiClient.disconnect()
                        audioPlayer.stopAndRelease()
                        CallService.answerCall()
                    }, 2000)
                    com.example.voicelauncher.data.SessionLog.addEvent(applicationContext, "Aktiven eigehenden Anruf angenommen")
                    buildJsonObject {
                        put("result", JsonPrimitive("done"))
                    }
                }
                "reject_call" -> {
                    Log.d("MainActivity", "Tool Called: reject_call")
                    CallStateHolder.callHandledByGemini = true
                    CallService.hangupCall()
                    com.example.voicelauncher.data.SessionLog.addEvent(applicationContext, "Aktiven eingehenden Anruf abgelehnt")
                    buildJsonObject {
                        put("result", JsonPrimitive("done"))
                    }
                }
                "toggle_speakerphone" -> {
                    val enabled = args["enabled"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toBooleanStrictOrNull() ?: true
                    Log.d("MainActivity", "Tool Called: toggle_speakerphone -> enabled=$enabled")
                    val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                    CallService.setSpeakerRoute(enabled)
                    audioManager.isSpeakerphoneOn = enabled // Fallback für nicht-Telecom Audio
                    CallStateHolder.isSpeakerOn.value = enabled
                    val statusText = if (enabled) "Lautsprecher eingeschaltet" else "Lautsprecher ausgeschaltet"
                    com.example.voicelauncher.data.SessionLog.addEvent(applicationContext, statusText)
                    buildJsonObject {
                        put("result", JsonPrimitive("done"))
                    }
                }
                "set_alarm" -> {
                    val hour = args["hour"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toIntOrNull() ?: -1
                    val minute = args["minute"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toIntOrNull() ?: 0
                    val label = args["label"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: String.format("%02d:%02d Uhr", hour, minute)
                    
                    Log.d("MainActivity", "Tool Called: set_alarm -> $hour:$minute ($label)")
                    
                    if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                        buildJsonObject {
                            put("result", JsonPrimitive("Fehler: Ungültige Uhrzeit. Stunde muss 0-23 sein, Minute 0-59."))
                        }
                    } else {
                        try {
                            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Berlin")).apply {
                                set(java.util.Calendar.HOUR_OF_DAY, hour)
                                set(java.util.Calendar.MINUTE, minute)
                                set(java.util.Calendar.SECOND, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                                // Falls die gewählte Zeit schon vorbei ist, auf morgen setzen
                                if (timeInMillis <= System.currentTimeMillis()) {
                                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                                }
                            }
                            
                            val alarmManager = getSystemService(android.app.AlarmManager::class.java)
                            val alarmIntent = android.content.Intent(this@MainActivity, AlarmReceiver::class.java).apply {
                                putExtra(AlarmReceiver.EXTRA_LABEL, label)
                            }
                            val pendingIntent = android.app.PendingIntent.getBroadcast(
                                this@MainActivity,
                                label.hashCode(), // Unique ID per Label
                                alarmIntent,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                            )
                            
                            alarmManager.setAlarmClock(
                                android.app.AlarmManager.AlarmClockInfo(calendar.timeInMillis, pendingIntent),
                                pendingIntent
                            )
                            
                            Log.d("MainActivity", "Wecker gestellt auf: ${calendar.time}")
                            ActionStateHolder.showIcon("⏰")
                            com.example.voicelauncher.data.SessionLog.addEvent(applicationContext, "Wecker gestellt auf $label")
                            
                            buildJsonObject {
                                put("result", JsonPrimitive("done"))
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Fehler beim Stellen des Weckers", e)
                            buildJsonObject {
                                put("result", JsonPrimitive("Fehler: Der Wecker konnte nicht gestellt werden."))
                            }
                        }
                    }
                }
                "dismiss_alarm" -> {
                    Log.d("MainActivity", "Tool Called: dismiss_alarm")
                    AlarmService.dismissAlarm()
                    com.example.voicelauncher.data.SessionLog.addEvent(applicationContext, "Klingelnden Wecker per Sprache abgestellt")
                    buildJsonObject {
                        put("result", JsonPrimitive("done"))
                    }
                }
                "delete_alarm" -> {
                    val label = args["label"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
                    Log.d("MainActivity", "Tool Called: delete_alarm -> label: $label")
                    
                    val alarmManager = getSystemService(android.app.AlarmManager::class.java)
                    val alarmIntent = android.content.Intent(this@MainActivity, AlarmReceiver::class.java).apply {
                        putExtra(AlarmReceiver.EXTRA_LABEL, label)
                    }
                    val pendingIntent = android.app.PendingIntent.getBroadcast(
                        this@MainActivity,
                        label.hashCode(),
                        alarmIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                    
                    com.example.voicelauncher.data.SessionLog.addEvent(applicationContext, "Wecker '$label' gelöscht")
                    
                    buildJsonObject {
                        put("result", JsonPrimitive("done"))
                    }
                }
                "stop_audio_session" -> {
                    Log.d("MainActivity", "Tool Called: stop_audio_session")
                    
                    // Nach einem Anruf soll die Session NICHT beendet werden,
                    // damit die Nutzerin Folgeanweisungen geben kann.
                    val callJustEnded = CallStateHolder.callState.value == CallStateHolder.State.DISCONNECTED
                            || CallStateHolder.isInCall
                    if (callJustEnded) {
                        Log.d("MainActivity", "stop_audio_session BLOCKIERT – Anruf gerade beendet, Session bleibt offen")
                        buildJsonObject {
                            put("result", JsonPrimitive("NEIN, beende die Session jetzt NICHT! Der Anruf wurde gerade erst beendet. Frag die Nutzerin ob sie noch etwas braucht und WARTE auf ihre Antwort."))
                        }
                    } else {
                        com.example.voicelauncher.data.SessionLog.addEvent(applicationContext, "Gespräch durch Nutzer beendet")
                    
                        // Mikrofon sofort abschalten, Gemini hört ab jetzt nichts mehr.
                        audioRecorder.stopRecording()
                    
                        // 5 Sekunden warten, damit Gemini sein "Tschüss" zu Ende sprechen kann.
                        // Das Audio wird fertig gestreamt, danach trennen wir die Verbindung und die UI wird blau.
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (isSessionActive) {
                                toggleAudioSession()
                            }
                        }, 5000)
                    
                        buildJsonObject {
                            put("result", JsonPrimitive("OK, Mikrofon aus. Verabschiede dich jetzt kurz, Leitung wird in 4 Sek getrennt."))
                        }
                    }
                }
                "get_latest_sms" -> {
                    val limit = args["limit"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toIntOrNull() ?: 5
                    var resultString = ""
                    val reader = com.example.voicelauncher.data.SmsReader(applicationContext)
                    val messages = reader.getLatestSms(limit)

                    if (messages.isEmpty()) {
                        resultString = "Es wurden keine SMS im Posteingang gefunden oder die Berechtigung fehlt."
                    } else {
                        val sdf = java.text.SimpleDateFormat("EEEE, HH:mm", java.util.Locale.GERMANY)
                        resultString = "Die letzten $limit Nachrichten:\n" + messages.joinToString("\n") { msg ->
                            val timeStr = sdf.format(java.util.Date(msg.timestamp))
                            "- Von ${msg.sender} ($timeStr): ${msg.text}"
                        }
                    }
                    Log.d("MainActivity", "Tool Called: get_latest_sms -> $resultString")
                    buildJsonObject { put("result", JsonPrimitive(resultString)) }
                }
                "get_sms_history_with_contact" -> {
                    val contactName = args["contact_name"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
                    val limit = args["limit"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toIntOrNull() ?: 5
                    var resultString = ""
                    
                    val result = findNativeContactWithPhone(contactName)
                    
                    if (result != null && result.first == "AMBIGUOUS") {
                        resultString = "Mehrere Kontakte gefunden: ${result.second}. Frage die Nutzerin, welchen sie meint."
                    } else if (result != null) {
                        val reader = com.example.voicelauncher.data.SmsReader(applicationContext)
                        val messages = reader.getSmsHistoryWithPhone(result.second, limit)
                        
                        if (messages.isEmpty()) {
                            resultString = "Ich habe keine SMS gefunden, die mit ${result.first} (${result.second}) ausgetauscht wurden."
                        } else {
                            val sdf = java.text.SimpleDateFormat("dd.MM., HH:mm", java.util.Locale.GERMANY)
                            resultString = "SMS-Verlauf mit ${result.first}:\n" + messages.joinToString("\n") { msg ->
                                val actualSender = if (msg.sender == "Ich (Omi)") "Ich" else result.first
                                val timeStr = sdf.format(java.util.Date(msg.timestamp))
                                "[$timeStr] $actualSender schrieb: ${msg.text}"
                            }
                        }
                    } else {
                         resultString = "Fehler: Ich konnte im Telefonbuch keine Handynummer für '$contactName' finden."
                    }
                    
                    Log.d("MainActivity", "Tool Called: get_sms_history_with_contact -> $resultString")
                    buildJsonObject { put("result", JsonPrimitive(resultString)) }
                }

                "get_weather" -> {
                    val city = args["city"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: "Oer-Erkenschwick"
                    Log.d("MainActivity", "Tool Called: get_weather -> City: $city")
                    
                    // Zeige das Wetter Icon an
                    ActionStateHolder.showIcon("🌤️")
                    
                    var weatherResult = ""
                    
                    if (city == "CURRENT_LOCATION") {
                        Log.d("MainActivity", "Wetter-Tool: Standortbasiert angefragt")
                        if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this@MainActivity)
                            
                            var lat = 0.0
                            var lon = 0.0
                            var locationFound = false
                            
                            try {
                                val location = com.google.android.gms.tasks.Tasks.await(fusedLocationClient.lastLocation, 3, java.util.concurrent.TimeUnit.SECONDS)
                                if (location != null) {
                                    lat = location.latitude
                                    lon = location.longitude
                                    locationFound = true
                                    Log.d("MainActivity", "GPS Koordinaten: lat=$lat, lon=$lon")
                                }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Fehler beim Abrufen der Location", e)
                            }
                            
                            if (locationFound) {
                                weatherResult = com.example.voicelauncher.weather.WeatherService.getWeatherByCoordinates(lat, lon)
                            } else {
                                weatherResult = "Dein Standort konnte leider nicht ermittelt werden. Bitte nenne eine Stadt."
                            }
                        } else {
                            weatherResult = "Ich habe leider keine Berechtigung, um deinen Standort für das Wetter abzufragen."
                        }
                    } else {
                        weatherResult = com.example.voicelauncher.weather.WeatherService.getWeather(city)
                    }
                    
                    Log.d("MainActivity", "get_weather Result: $weatherResult")
                    com.example.voicelauncher.data.SessionLog.addEvent(applicationContext, "Wetter abgefragt (Ziel: $city)")
                    
                    buildJsonObject {
                        put("result", JsonPrimitive(weatherResult))
                    }
                }
                "get_session_logs" -> {
                    val lines = args["lines"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toIntOrNull() ?: 20
                    Log.d("MainActivity", "Tool Called: get_session_logs -> lines: $lines")
                    val logs = com.example.voicelauncher.data.SessionLog.getRecentLog(applicationContext, lines)
                    val resultString = if (logs.isNotEmpty()) {
                        "Das sind die letzten $lines Einträge im Logbuch:\n$logs"
                    } else {
                        "Das Logbuch ist bisher leer."
                    }
                    buildJsonObject {
                        put("result", JsonPrimitive(resultString))
                    }
                }
                else -> {
                    buildJsonObject {
                        put("error", JsonPrimitive("Unknown function"))
                    }
                }
            }
        }

        // Pre-Connect: Gemini-Session sofort aufbauen, damit beim Widget-Tap
        // kein Cold-Start-Delay entsteht. Die Session bleibt idle bis der User spricht.
        if (!geminiClient.isSetupComplete && !isSessionActive) {
            Log.d("MainActivity", "Pre-Connect: Baue Gemini-Verbindung im Hintergrund auf")
            geminiClient.connect(buildSystemPrompt(), allToolGroups)
        }
    }

    // ========== Native Contact Helper Functions ==========

    /**
     * Findet eine Contact-ID anhand des Namens (exakte Suche, case-insensitive).
     */
    /**
     * Normalisiert Umlaute für Vergleiche (ö→oe, ä→ae, ü→ue, ß→ss).
     */
    private fun normalizeUmlauts(text: String): String {
        return text
            .replace("ö", "oe").replace("Ö", "Oe")
            .replace("ä", "ae").replace("Ä", "Ae")
            .replace("ü", "ue").replace("Ü", "Ue")
            .replace("ß", "ss")
    }

    private fun findNativeContactId(name: String): Long? {
        val cursor = contentResolver.query(
            android.provider.ContactsContract.Contacts.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.Contacts._ID, android.provider.ContactsContract.Contacts.DISPLAY_NAME),
            null, null, null
        )
        cursor?.use {
            val idIndex = it.getColumnIndex(android.provider.ContactsContract.Contacts._ID)
            val nameIndex = it.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME)
            while (it.moveToNext()) {
                val cName = it.getString(nameIndex) ?: continue
                if (cName.equals(name, ignoreCase = true) || normalizeUmlauts(cName).equals(normalizeUmlauts(name), ignoreCase = true)) {
                    return it.getLong(idIndex)
                }
            }
        }
        return null
    }

    /**
     * Findet die erste RawContact-ID für eine gegebene Contact-ID.
     */
    private fun getRawContactId(contactId: Long): Long? {
        val cursor = contentResolver.query(
            android.provider.ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.RawContacts._ID),
            "${android.provider.ContactsContract.RawContacts.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getLong(it.getColumnIndex(android.provider.ContactsContract.RawContacts._ID))
            }
        }
        return null
    }

    /**
     * Liest die Notizen eines Kontakts (via RawContact-ID).
     */
    private fun readContactNotes(rawContactId: Long): String? {
        val cursor = contentResolver.query(
            android.provider.ContactsContract.Data.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Note.NOTE),
            "${android.provider.ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${android.provider.ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), android.provider.ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getString(it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Note.NOTE))
            }
        }
        return null
    }

    /**
     * Extrahiert die Aliase aus dem Notizen-Text.
     */
    private fun extractAliasesFromNotes(notes: String?): String? {
        if (notes == null) return null
        val line = notes.lines().find { it.startsWith("ALIASE:") }
        return line?.removePrefix("ALIASE:")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Extrahiert die reinen Notizen (ohne Aliase) aus dem Notizen-Text.
     */
    private fun extractPureNotes(notes: String?): String? {
        if (notes == null) return null
        val line = notes.lines().find { it.startsWith("NOTIZEN:") }
        return line?.removePrefix("NOTIZEN:")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Liest alle Geburtstage aus den nativen Kontakten.
     * Gibt Paare von (Name, "Tag.Monat.") zurück.
     */
    private fun getNativeBirthdays(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val cursor = contentResolver.query(
            android.provider.ContactsContract.Data.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Event.CONTACT_ID,
                android.provider.ContactsContract.CommonDataKinds.Event.START_DATE,
                android.provider.ContactsContract.Contacts.DISPLAY_NAME
            ),
            "${android.provider.ContactsContract.Data.MIMETYPE} = ? AND ${android.provider.ContactsContract.CommonDataKinds.Event.TYPE} = ?",
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                android.provider.ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()
            ),
            null
        )
        cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME)
            val dateIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Event.START_DATE)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                val dateStr = it.getString(dateIndex) ?: continue
                // Format: "--MM-DD" oder "YYYY-MM-DD"
                Log.d("MainActivity", "getNativeBirthdays: name=$name dateStr=$dateStr")
                val parts = dateStr.replace("--", "").split("-").filter { it.isNotEmpty() }
                val formattedDate = when {
                    parts.size >= 2 -> "${parts.last().toIntOrNull() ?: parts.last()}.${parts[parts.size - 2].toIntOrNull() ?: parts[parts.size - 2]}."
                    else -> dateStr
                }
                result.add(Pair(name, formattedDate))
            }
        }
        Log.d("MainActivity", "getNativeBirthdays: ${result.size} Geburtstage gefunden")
        return result
    }

    /**
     * Sucht einen Kontakt in den nativen Kontakten mit Fuzzy-Matching auf Name, Aliase und Notizen.
     * Gibt Pair(Name, Telefonnummer) zurück oder null.
     */
    private fun findNativeContactWithPhone(searchQuery: String): Pair<String, String>? {
        val searchLower = searchQuery.lowercase()
        
        // Alle Kontakte mit Telefonnummern laden (inkl. Notizen für Alias/Notes-Suche)
        val cursor = contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID
            ),
            null, null, null
        )
        
        val matches = mutableListOf<Triple<String, String, Int>>() // name, number, score (lower = better)
        val processed = mutableSetOf<String>()
        
        cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
            val idIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            
            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                val number = it.getString(numberIndex) ?: continue
                if (processed.contains(name)) continue
                
                val nameLower = name.lowercase()
                val dist = com.example.voicelauncher.utils.Levenshtein.distance(nameLower, searchLower)
                
                // Name-basiertes Matching
                when {
                    nameLower == searchLower -> {
                        processed.add(name)
                        matches.add(Triple(name, number, 0))
                    }
                    nameLower.contains(searchLower) || searchLower.contains(nameLower) -> {
                        processed.add(name)
                        matches.add(Triple(name, number, 1))
                    }
                    dist <= 2 && searchLower.length > 3 -> {
                        processed.add(name)
                        matches.add(Triple(name, number, 2))
                    }
                    else -> {
                        // Aliase und Notizen aus nativen Kontakten prüfen
                        val contactId = it.getLong(idIndex)
                        val rawId = getRawContactId(contactId)
                        if (rawId != null) {
                            val notesText = readContactNotes(rawId)
                            if (notesText != null) {
                                val aliasesStr = extractAliasesFromNotes(notesText)
                                val pureNotes = extractPureNotes(notesText)
                                
                                val aliasMatch = aliasesStr?.split(",")?.any { alias ->
                                    alias.trim().lowercase() == searchLower
                                } ?: false
                                
                                val notesMatch = pureNotes?.lowercase()?.contains(searchLower) ?: false
                                
                                if (aliasMatch) {
                                    processed.add(name)
                                    matches.add(Triple(name, number, 1))
                                } else if (notesMatch) {
                                    processed.add(name)
                                    matches.add(Triple(name, number, 3))
                                }
                            }
                        }
                    }
                }
            }
        }
        
        if (matches.isEmpty()) return null
        
        // Bei mehreren unterschiedlichen Namen mit gleichem Score: Ergebnis mit AMBIGUOUS-Marker zurückgeben
        val bestScore = matches.minOf { it.third }
        val bestMatches = matches.filter { it.third == bestScore }
        val uniqueNames = bestMatches.map { it.first }.distinct()
        
        if (uniqueNames.size > 1) {
            Log.d("MainActivity", "findNativeContactWithPhone: Mehrdeutig für '$searchQuery': ${uniqueNames.joinToString(", ")}")
            // Statt null: speziellen Marker zurückgeben, damit der Caller die Namen anzeigen kann
            return Pair("AMBIGUOUS", uniqueNames.joinToString(", "))
        }
        
        val best = bestMatches.first()
        return Pair(best.first, best.second)
    }

    /**
     * System-Prompt mit Persönlichkeit, Regeln UND Kontext (Memories, Kalender, Geburtstage).
     * Kontext wird direkt mitgeschickt statt über ein Tool, um Tool-Call-Probleme zu vermeiden.
     */
    private fun buildSystemPrompt(): String {
        val dateFormat = java.text.SimpleDateFormat("EEEE, dd. MMMM yyyy, HH:mm", java.util.Locale.GERMANY).also { it.timeZone = java.util.TimeZone.getTimeZone("Europe/Berlin") }
        val currentDate = dateFormat.format(java.util.Date())

        var systemPrompt = """Du bist ein freundlicher, geduldiger junger Mann, der Veronica Bleyl, einer älteren, blinden Dame, im Alltag hilft. Warmherzig, locker, respektvoll. Du siezt sie ('Sie'/'Ihnen'), herzlich und natürlich. Sprich LANGSAM und deutlich – die Nutzerin ist älter und blind. Mach kurze Pausen zwischen Sätzen. Kurze, klare Sätze – lange Schachtelsätze sind schwer zu folgen. Lebendiger Tonfall, abwechslungsreich, keine Standardfloskeln. Keine Emojis, keine *Sternchen-Handlungen*. Bei Tool-Nutzung kurz und natürlich auf Deutsch erklären. NIE englische Systemmeldungen aussprechen. Die App kann KEINE SMS senden, KEINE Kontakte löschen und KEINE Telefonnummern bearbeiten. Es ist gerade $currentDate.""".trimIndent()

        systemPrompt += " REGELN: 1) Nutze gesunden Menschenverstand! Bei einfachen Dingen (Wetter, Uhrzeit) direkt handeln. Bei wichtigen Aktionen (Termine anlegen, Kontakte ändern) kurz die Details bestätigen lassen. 2) Kurz erwähnen, was du tust. 3) Nie stumm bleiben. 4) WICHTIG – AUTOMATISCH MERKEN: Wenn die Nutzerin persönliche Informationen erwähnt (Familie, Beziehungen, Vorlieben, Gewohnheiten, wichtige Fakten), SOFORT mit handle_memory speichern – OHNE nachzufragen! Beispiele: 'Jörg ist mein Sohn' → speichere 'Sohn = Jörg'. 'Ich trinke gern Kaffee' → speichere 'Trinkt gern Kaffee'. 'Meine Schwester heißt Ingrid' → speichere 'Schwester = Ingrid'. So kannst du bei 'ruf meinen Sohn an' sofort Jörg anrufen. 5) VERKNÜPFUNG: Wenn die Nutzerin nach Infos über eine Person fragt (z.B. 'Wann hat mein Sohn Geburtstag?'), nutze dein Wissen aus den Erinnerungen (z.B. Sohn = Jörg) UND schlage im Kontakt nach (manage_contacts mit action=get), um Geburtstag etc. zu finden. 6) FLEXIBLE KONTAKTSUCHE: Kontakte können unter verschiedenen Namen gespeichert sein! Wenn du jemanden nicht findest, probiere Varianten: Mutter→Mama/Mutti, Vater→Papa/Papi, Schwester→Vorname, etc. Nutze im Zweifel get_contact_list, um alle Kontakte zu sehen und den richtigen zu finden. manage_contacts ist NUR für Name und Geburtstag. 7) SESSION BEENDEN: Wenn die Aufgabe erledigt ist und die Nutzerin nichts weiteres braucht, verabschiede dich herzlich und rufe stop_audio_session auf. Wenn du fragst ob noch etwas ist, warte auf die Antwort bevor du beendest. 8) ANRUFE: Bevor du jemanden anrufst, frage IMMER freundlich nach, ob du den Namen richtig verstanden hast (z.B. 'Soll ich [Name] für Sie anrufen?') und warte auf Bestätigung oder Korrektur. Erst danach make_phone_call nutzen."

        // Kontext direkt einbauen (kein Tool-Call nötig!)
        try {
            val context = buildContextResponse()
            if (context != "Kein Kontext vorhanden.") {
                systemPrompt += " AKTUELLER KONTEXT: $context"
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Fehler beim Erstellen des Kontexts für System-Prompt", e)
        }

        // Ungelesene Notification – zeitkritisch
        NotificationReaderService.latestNotification?.let { msg ->
           systemPrompt += " Sie hat eine ungelesene Nachricht: '$msg'. Erwähne das beiläufig."
           NotificationReaderService.latestNotification = null
        }

        return systemPrompt
    }

    /**
     * Baut den Kontext: Memories, Logs, Kalender, Geburtstage.
     * Wird direkt in den System-Prompt eingebaut.
     */
    private fun buildContextResponse(): String {
        val parts = mutableListOf<String>()

        // Memories
        val database = (application as VoiceLauncherApp).database
        val existingMemories = runBlocking { database.memoryDao().getAllMemories().takeLast(10) }
        if (existingMemories.isNotEmpty()) {
            val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.GERMANY).also { it.timeZone = java.util.TimeZone.getTimeZone("Europe/Berlin") }
            parts.add("ERINNERUNGEN:\n" + existingMemories.joinToString("\n") {
                "- [${sdf.format(java.util.Date(it.timestampMs))}]: ${it.content}"
            })
        }

        // Session Logs
        val recentLogs = com.example.voicelauncher.data.SessionLog.getRecentLog(applicationContext, 5)
        if (recentLogs.isNotEmpty()) {
            parts.add("LETZTE EREIGNISSE:\n$recentLogs")
        }

        // Geburtstage – prüfe die nächsten 3 Tage (auch über Monatsgrenzen hinweg)
        try {
            val birthdays = getNativeBirthdays()
            // Erzeuge die nächsten 4 Tage als (Tag, Monat)-Paare
            val upcomingDays = (0..3).map { offset ->
                val c = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_MONTH, offset) }
                Pair(c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.MONTH) + 1)
            }
            val birthdaysText = birthdays.filter { (_, dateStr) ->
                val dateParts = dateStr.removeSuffix(".").split(".")
                val bDay = dateParts.getOrNull(0)?.toIntOrNull()
                val bMonth = dateParts.getOrNull(1)?.toIntOrNull()
                bDay != null && bMonth != null && upcomingDays.any { it.first == bDay && it.second == bMonth }
            }.joinToString(", ") { "${it.first} (am ${it.second})" }
            if (birthdaysText.isNotEmpty()) {
                parts.add("GEBURTSTAGE bald: $birthdaysText. Wenn heute, sofort erinnern!")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Fehler beim Lesen der Geburtstage", e)
        }

        // Kalender
        val now = System.currentTimeMillis()
        val next24h = now + 24 * 60 * 60 * 1000L
        try {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                val builder = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
                android.content.ContentUris.appendId(builder, now)
                android.content.ContentUris.appendId(builder, next24h)
                val projection = arrayOf(
                    android.provider.CalendarContract.Instances.TITLE,
                    android.provider.CalendarContract.Instances.BEGIN,
                    android.provider.CalendarContract.Instances.ALL_DAY
                )
                val cursor = contentResolver.query(builder.build(), projection, null, null, "${android.provider.CalendarContract.Instances.BEGIN} ASC")
                if (cursor != null) {
                    val events = mutableListOf<String>()
                    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).also { it.timeZone = java.util.TimeZone.getTimeZone("Europe/Berlin") }
                    val titleIdx = cursor.getColumnIndex(android.provider.CalendarContract.Instances.TITLE)
                    val beginIdx = cursor.getColumnIndex(android.provider.CalendarContract.Instances.BEGIN)
                    val allDayIdx = cursor.getColumnIndex(android.provider.CalendarContract.Instances.ALL_DAY)
                    while (cursor.moveToNext()) {
                        val eTitle = cursor.getString(titleIdx) ?: "Ohne Titel"
                        val eBegin = cursor.getLong(beginIdx)
                        val isAllDay = allDayIdx >= 0 && cursor.getInt(allDayIdx) == 1
                        if (isAllDay) {
                            events.add("$eTitle (ganztägig)")
                        } else {
                            events.add("$eTitle um ${sdf.format(java.util.Date(eBegin))} Uhr")
                        }
                    }
                    cursor.close()
                    if (events.isNotEmpty()) {
                        parts.add("TERMINE nächste 24h: " + events.joinToString(", "))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Fehler beim Lesen des Kalenders", e)
        }

        return if (parts.isEmpty()) "Kein Kontext vorhanden." else parts.joinToString("\n\n")
    }

    // Alle Tool-Gruppen für normale User-Sessions (Omi tippt auf den Bildschirm)
    private val allToolGroups = ToolGroup.entries.toSet()

    private fun toggleAudioSession() {
        if (isSessionActive) {
            // STOP
            isSessionActive = false
            audioRecorder.stopRecording()
            geminiClient.disconnect()
            audioPlayer.stopAndRelease()
            WidgetToggleService.stop(this)

            // Haptisches Feedback: Einzelner kurzer Impuls = Session beendet
            try {
                val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val vm = getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                }
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (e: Exception) {
                Log.w("MainActivity", "Vibration fehlgeschlagen", e)
            }

            Log.d("MainActivity", "Session Stopped")
        } else {
            // START
            isSessionActive = true

            // Haptisches Feedback: Doppel-Vibration damit die blinde Nutzerin spürt, dass der Assistent startet
            try {
                val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val vm = getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                }
                // Muster: 0ms warten, 100ms vibrieren, 80ms Pause, 100ms vibrieren
                val pattern = longArrayOf(0, 100, 80, 100)
                vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
            } catch (e: Exception) {
                Log.w("MainActivity", "Vibration fehlgeschlagen", e)
            }

            if (geminiClient.isSetupComplete) {
                // Eine Verbindung besteht bereits (z.B. durch Uhrzeit-Klick).
                // Wir schalten nur das Mikrofon ein und nutzen die bestehende Session!
                audioRecorder.startRecording { audioChunk ->
                    geminiClient.sendAudio(audioChunk)
                }
                Log.d("MainActivity", "Mikrofon zu bestehender Session (ohne Reconnect) hinzugeschaltet")
                VoiceLauncherWidget.updateWidget(this, true)
                return
            }

            // Mikro sofort starten, damit es warm ist wenn setupComplete kommt.
            // sendAudio() verwirft Chunks solange !isSetupComplete – kein Problem.
            audioRecorder.startRecording { audioChunk ->
                geminiClient.sendAudio(audioChunk)
            }

            // User-initiierte Session: Alle Tools laden
            geminiClient.connect(buildSystemPrompt(), allToolGroups)

            Log.d("MainActivity", "Session Started (alle Tool-Gruppen)")
        }
        // Widget-Status aktualisieren
        VoiceLauncherWidget.updateWidget(this, isSessionActive)
    }

    private fun startSessionWithPrompt(prompt: String, toolGroups: Set<ToolGroup> = allToolGroups) {
        if (isSessionActive || geminiClient.isSetupComplete) {
            isSessionActive = true // Sicherstellen, dass die UI den aktiven Status anzeigt
            geminiClient.sendClientContent(prompt)
            pendingClientPrompt.set(null) // Erfolgreich gesendet → Backup löschen
            // Sicherstellen, dass das Mikro läuft (könnte durch Anruf gestoppt worden sein)
            audioRecorder.startRecording { audioChunk ->
                geminiClient.sendAudio(audioChunk)
            }
            Log.d("MainActivity", "Reusing existing session for prompt: $prompt")
            VoiceLauncherWidget.updateWidget(this, true)
            WidgetToggleService.startKeepAlive(this)
            return
        }
        
        pendingClientPrompt.set(prompt)
        isSessionActive = true

        geminiClient.connect(buildSystemPrompt(), toolGroups)
        VoiceLauncherWidget.updateWidget(this, true)
        WidgetToggleService.startKeepAlive(this)
        Log.d("MainActivity", "Session Started Automatically with Prompt (${toolGroups.size} groups)")
    }

    private fun startSessionWithoutMic(prompt: String, toolGroups: Set<ToolGroup> = setOf(ToolGroup.CORE)) {
        if (isSessionActive) {
            geminiClient.sendClientContent(prompt)
            return
        }
        
        pendingClientPrompt.set(prompt)
        // Wir setzen isSessionActive absichtlich NICHT auf true, um das UI nicht zu verändern
        // und das Mikrofon nicht zu starten!

        geminiClient.connect(buildSystemPrompt(), toolGroups)
        Log.d("MainActivity", "Session Started Without Mic (${toolGroups.size} groups)")  
    }

    @Suppress("DEPRECATION")
    private fun acquireProximityWakeLock() {
        if (proximityWakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            proximityWakeLock = pm.newWakeLock(
                android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "voicelauncher:proximity"
            )
            proximityWakeLock?.acquire()
            Log.d("MainActivity", "Proximity WakeLock aktiviert")
        } catch (e: Exception) {
            Log.w("MainActivity", "Proximity WakeLock fehlgeschlagen", e)
        }
    }

    private fun releaseProximityWakeLock() {
        try {
            if (proximityWakeLock?.isHeld == true) {
                proximityWakeLock?.release()
                Log.d("MainActivity", "Proximity WakeLock freigegeben")
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Proximity WakeLock release fehlgeschlagen", e)
        }
        proximityWakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseProximityWakeLock()
        audioRecorder.stopRecording()
        audioPlayer.stopAndRelease()
        geminiClient.disconnect()
        
        // Singleton-Callbacks aufräumen, damit keine tote Activity referenziert wird
        CallStateHolder.onCallSummaryReady = null
        CallStateHolder.onIncomingCallReady = null
        CallStateHolder.onToggleSpeaker = null
        AlarmStateHolder.onAlarmTriggered = null
        AlarmStateHolder.onAlarmDismissedByUser = null
        VoiceLauncherWidget.onToggleAssistant = null
    }

    private fun requestDefaultDialerRole() {
        // Auf Android 10+ MÜSSEN wir RoleManager verwenden
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(android.app.role.RoleManager::class.java)
            if (roleManager?.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER) == true) {
                if (!roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER)) {
                    try {
                        val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER)
                        requestDialerRoleLauncher.launch(intent)
                        Log.d("MainActivity", "Dialog für Standard-Telefon-App per RoleManager angefordert")
                    } catch (e: Exception) {
                        Log.e("MainActivity", "RoleManager Intent Fehler", e)
                        requestDefaultDialerFallback()
                    }
                } else {
                    Log.d("MainActivity", "Sind bereits Standard-Telefon-App (RoleManager)")
                }
            } else {
                Log.w("MainActivity", "ROLE_DIALER nicht verfügbar")
                requestDefaultDialerFallback()
            }
        } else {
            // Fallback für Android 9 und älter
            requestDefaultDialerFallback()
        }
    }

    private fun requestDefaultDialerFallback() {
        try {
            val telecomManager = getSystemService(android.telecom.TelecomManager::class.java)
            if (telecomManager?.defaultDialerPackage != packageName) {
                val intent = android.content.Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    .putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                startActivity(intent)
                Log.d("MainActivity", "Dialog für Standard-Telefon-App per TelecomManager angefordert")
            } else {
                Log.d("MainActivity", "Sind bereits Standard-Telefon-App (TelecomManager)")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Fallback Fehler", e)
        }
    }


}
