package com.example.voicelauncher.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Ein rotierendes Logbuch für das Kurzzeit-/Langzeitgedächtnis des Sprachassistenten.
 * Speichert Ereignisse permanent in einer Textdatei, damit die KI Kontext über
 * vorherige Interaktionen (Anrufe, Wecker, etc.) behält.
 */
object SessionLog {
    private const val FILE_NAME = "sessions.log"
    // Maximales Dateigrößen-Limit (z.B. 1MB wegschneiden wenn erreicht, für den Anfang lassen wir es aber simpel wachsen)
    
    private const val MAX_LINES = 100

    @Synchronized
    fun addEvent(context: Context, description: String) {
        try {
            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).also { 
                it.timeZone = TimeZone.getTimeZone("Europe/Berlin") 
            }
            val timeString = dateFormat.format(Date())
            val logEntry = "[$timeString Uhr]: $description\n"
            
            val file = File(context.filesDir, FILE_NAME)
            file.appendText(logEntry)
            
            // Rotation: Älteste Zeilen abschneiden wenn über MAX_LINES
            val allLines = file.readLines()
            if (allLines.size > MAX_LINES) {
                val trimmed = allLines.takeLast(MAX_LINES)
                file.writeText(trimmed.joinToString("\n") + "\n")
                Log.d("SessionLog", "Log rotiert: ${allLines.size} -> $MAX_LINES Zeilen")
            }
            
            Log.d("SessionLog", "Ereignis gespeichert: $logEntry")
        } catch (e: Exception) {
            Log.e("SessionLog", "Fehler beim Speichern des Session-Logs", e)
        }
    }

    @Synchronized
    fun getRecentLog(context: Context, lines: Int = 5): String {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return ""
            
            // Lese alle Zeilen und nehme die letzten N, dann füge sie zusammen
            val allLines = file.readLines()
            allLines.takeLast(lines).joinToString("\n")
        } catch (e: Exception) {
            Log.e("SessionLog", "Fehler beim Lesen des Session-Logs", e)
            ""
        }
    }
    
    @Synchronized
    fun clear(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e("SessionLog", "Fehler beim Löschen des Logs", e)
        }
    }
}
