package com.example.voicelauncher.alarm

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
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

/**
 * Fullscreen Alarm-UI – optimiert für blinde Nutzer.
 * Große Schrift, klare Farben, haptisches Feedback, gesamter Bildschirm anklickbar.
 */
@Composable
fun AlarmScreen() {
    val label = AlarmStateHolder.alarmLabel.value
    val haptic = LocalHapticFeedback.current
    
    // Pulsierender Ring-Effekt
    val infiniteTransition = rememberInfiniteTransition(label = "alarm_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alarm_scale"
    )
    
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = "Wecker klingelt: $label. Tippen oder 'Aus' sagen zum Ausschalten."
            }
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                AlarmService.dismissAlarm()
                AlarmStateHolder.onAlarmDismissedByUser?.invoke()
            },
        color = Color(0xFFFAFAFA) // Einheitlich helles Layout
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .scale(pulseScale)
                        .background(
                            color = Color(0xFFF59E0B), // Sattes Amber/Orange
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⏰",
                        fontSize = 120.sp
                    )
                }
                Spacer(modifier = Modifier.height(64.dp))
                Text(
                    text = "Wecker\n$label",
                    color = Color(0xFF1E293B),
                    fontSize = 40.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.displaySmall
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Sagen Sie \"Aus\" oder tippen Sie",
                    color = Color(0xFF475569),
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
