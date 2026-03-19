package com.example.voicelauncher.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.widget.RemoteViews
import com.example.voicelauncher.R

class VoiceLauncherWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_ASSISTANT = "com.example.voicelauncher.ACTION_TOGGLE_ASSISTANT"

        var onToggleAssistant: (() -> Unit)? = null
        private var lastKnownActive = false
        private var lastToggleTime = 0L

        fun updateWidget(context: Context, isActive: Boolean) {
            lastKnownActive = isActive
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetComponent = ComponentName(context, VoiceLauncherWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)

            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId, isActive)
            }
        }

        private fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            isActive: Boolean
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // Kreis-Bild wechseln: aktiv = grün, inaktiv = blau
            val circleDrawable = if (isActive) R.drawable.widget_bg_active else R.drawable.widget_bg_inactive
            views.setImageViewResource(R.id.widget_circle, circleDrawable)

            // Icon wechseln
            val icon = if (isActive) "🎤" else "🎙️"
            views.setTextViewText(R.id.widget_icon, icon)

            // Emoji-Größe dynamisch aus Widget-Höhe berechnen
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            // Kreis = 2/3 der Höhe, Emoji ≈ 70 % des Kreisdurchmessers
            val emojiSizeDp = (minH * 2f / 3f * 0.7f).coerceIn(28f, 200f)
            views.setTextViewTextSize(R.id.widget_icon, TypedValue.COMPLEX_UNIT_DIP, emojiSizeDp)

            // Content Description
            val desc = if (isActive) "Assistent ist aktiv. Tippen zum Stoppen." else "Assistent starten"
            views.setContentDescription(R.id.widget_button, desc)

            // PendingIntent: Broadcast an uns selbst
            val intent = Intent(context, VoiceLauncherWidget::class.java).apply {
                action = ACTION_TOGGLE_ASSISTANT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_button, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, isActive = lastKnownActive)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateAppWidget(context, appWidgetManager, appWidgetId, lastKnownActive)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_ASSISTANT) {
            val now = System.currentTimeMillis()
            if (now - lastToggleTime < 1500) {
                Log.d("VoiceLauncherWidget", "Toggle ignoriert (Debounce, ${now - lastToggleTime}ms)")
                return
            }
            lastToggleTime = now
            Log.d("VoiceLauncherWidget", "Toggle-Broadcast empfangen, starte Service...")
            WidgetToggleService.start(context)
        }
    }
}
