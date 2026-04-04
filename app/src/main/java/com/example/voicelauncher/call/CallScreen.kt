package com.example.voicelauncher.call

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background

import androidx.compose.foundation.combinedClickable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Fullscreen Call-UI – optimiert für blinde Nutzer.
 * Große Schrift, klare Farben, haptisches Feedback, gesamter Bildschirm anklickbar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallScreen() {
    val state = CallStateHolder.callState.value
    val callerName = CallStateHolder.callerName.value
    val callerNumber = CallStateHolder.callerNumber.value

    val haptic = LocalHapticFeedback.current
    
    // Anrufdauer Timer
    var callDurationSeconds by remember { mutableStateOf(0L) }
    val callStartTime = CallStateHolder.callStartTimeMs.value
    
    LaunchedEffect(state) {
        if (state == CallStateHolder.State.ACTIVE) {
            while (true) {
                callDurationSeconds = (System.currentTimeMillis() - callStartTime) / 1000
                delay(1000)
            }
        } else {
            callDurationSeconds = 0
        }
    }
    
    // Einheitlicher heller Hintergrund
    val bgColor = Color(0xFFFAFAFA)
    
    val indicatorColor = when (state) {
        CallStateHolder.State.RINGING -> Color(0xFF22C55E) // Sattes Grün
        CallStateHolder.State.ACTIVE -> Color(0xFF3B82F6) // Sattes Blau
        CallStateHolder.State.DIALING -> Color(0xFF0EA5E9) // Hellblau
        CallStateHolder.State.DISCONNECTED -> Color(0xFFEF4444) // Rot
        else -> Color(0xFF757575)
    }
    
    // Pulsierender Ring-Effekt bei eingehendem Anruf
    val infiniteTransition = rememberInfiniteTransition(label = "call_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == CallStateHolder.State.RINGING) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "call_scale"
    )
    
    // Anzeigename bestimmen
    val displayName = if (callerName.isNotBlank()) callerName else callerNumber.ifBlank { "Unbekannt" }
    
    // Accessibility-Text
    val accessibilityText = when (state) {
        CallStateHolder.State.RINGING -> "Eingehender Anruf von $displayName. Tippen zum Annehmen. Lange drücken zum Ablehnen."
        CallStateHolder.State.DIALING -> "Anruf an $displayName wird aufgebaut. Lange drücken zum Auflegen."
        CallStateHolder.State.ACTIVE -> "Aktives Gespräch mit $displayName. Lange drücken zum Auflegen."
        CallStateHolder.State.DISCONNECTED -> "Anruf beendet."
        else -> ""
    }
    
    val timeString = if (state == CallStateHolder.State.ACTIVE) {
        val minutes = callDurationSeconds / 60
        val seconds = callDurationSeconds % 60
        String.format("\n%02d:%02d", minutes, seconds)
    } else ""
    
    val mainText = when (state) {
        CallStateHolder.State.RINGING -> "Anruf von\n$displayName"
        CallStateHolder.State.DIALING -> "Wählt...\n$displayName"
        CallStateHolder.State.ACTIVE -> "$displayName$timeString"
        CallStateHolder.State.DISCONNECTED -> "Anruf beendet"
        else -> ""
    }
    
    val subText = when (state) {
        CallStateHolder.State.RINGING -> "Tippen zum Annehmen\nLange drücken zum Ablehnen"
        CallStateHolder.State.DIALING, CallStateHolder.State.ACTIVE -> "Lange drücken zum Auflegen"
        else -> ""
    }
    
    val iconEmoji = when (state) {
        CallStateHolder.State.DISCONNECTED -> "❌"
        else -> "📞"
    }
    
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = accessibilityText }
            .combinedClickable(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    when (state) {
                        // Tippen: Annehmen bei eingehendem Anruf
                        CallStateHolder.State.RINGING -> CallService.answerCall()
                        // Tippen bei aktivem Anruf: Nichts tun (versehentliches Auflegen verhindern)
                        else -> {}
                    }
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    when (state) {
                        // Lange drücken: Ablehnen bei eingehendem Anruf
                        CallStateHolder.State.RINGING -> CallService.hangupCall()
                        // Lange drücken: Auflegen bei aktivem/wählendem Anruf
                        CallStateHolder.State.DIALING, CallStateHolder.State.ACTIVE -> CallService.hangupCall()
                        else -> {}
                    }
                }
            ),
        color = bgColor
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Hauptinhalt zentriert
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .scale(if (state == CallStateHolder.State.RINGING) pulseScale else 1f)
                        .background(
                            color = indicatorColor,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = iconEmoji,
                        fontSize = 120.sp
                    )
                }
                Spacer(modifier = Modifier.height(64.dp))
                Text(
                    text = mainText,
                    color = Color(0xFF1E293B), // Dunkles Grau/Schwarz
                    fontSize = 40.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.displaySmall
                )
                if (subText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = subText,
                        color = Color(0xFF475569), // Mittleres/Dunkles Grau
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            // Lautsprecher-Button entfernt – Lautsprecher ist immer an (für blinde Nutzerin)
        }
    }
}
