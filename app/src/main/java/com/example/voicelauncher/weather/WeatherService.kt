package com.example.voicelauncher.weather

import android.util.Log
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Service zum Abrufen des aktuellen Wetters über die Open-Meteo API.
 * Kein API-Key erforderlich.
 */
object WeatherService {

    private const val TAG = "WeatherService"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Holt das aktuelle Wetter für eine Stadt.
     * @param city Stadtname (z.B. "Berlin", "Oer-Erkenschwick"). Wenn city == "CURRENT_LOCATION", wird eine Fehlermeldung generiert (sollte von MainActivity abgefangen werden).
     * @return Lesbarer deutscher Wetter-String oder eine Fehlermeldung
     */
    fun getWeather(city: String): String {
        if (city == "CURRENT_LOCATION") return "Fehler: Standort konnte nicht ermittelt werden."
        
        return try {
            // 1. Geocoding: Stadt → Koordinaten
            val (lat, lon, resolvedName) = geocodeCity(city)
                ?: return "Ich konnte die Stadt '$city' leider nicht finden."

            getWeatherByCoordinates(lat, lon, resolvedName)
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Abrufen des Wetters für '$city'", e)
            "Es tut mir leid, ich konnte das Wetter gerade nicht abrufen. Bitte versuche es später noch einmal."
        }
    }

    /**
     * Holt das Wetter basierend auf Koordinaten.
     */
    fun getWeatherByCoordinates(lat: Double, lon: Double, locationName: String? = null): String {
        return try {
            // Wetterdaten abrufen (aktuell + 7 Tage Vorhersage)
            val weatherUrl = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&temperature_unit=celsius" +
                "&timezone=Europe/Berlin"

            val request = Request.Builder().url(weatherUrl).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return "Fehler: Keine Antwort vom Wetterdienst."

            Log.d(TAG, "Weather API response for $lat, $lon: ${body.take(500)}")

            val json = Json.parseToJsonElement(body).jsonObject
            val current = json["current"]?.jsonObject
                ?: return "Fehler: Unerwartetes Datenformat vom Wetterdienst (current fehlt)."

            val temp = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull
            val feelsLike = current["apparent_temperature"]?.jsonPrimitive?.doubleOrNull
            val humidity = current["relative_humidity_2m"]?.jsonPrimitive?.intOrNull
            val windSpeed = current["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull
            val weatherCode = current["weather_code"]?.jsonPrimitive?.intOrNull ?: 0

            val weatherDescription = wmoCodeToGerman(weatherCode)
            
            val placeName = locationName ?: "deinem aktuellen Standort"

            // Lesbaren Text zusammenbauen (audio-freundlich, keine Sonderzeichen wie °)
            buildString {
                append("Aktuelles Wetter in $placeName: ")
                append("$weatherDescription. ")
                if (temp != null) append("Temperatur: ${temp.toInt()} Grad. ")
                if (feelsLike != null) append("Gefühlt: ${feelsLike.toInt()} Grad. ")
                if (humidity != null) append("Luftfeuchtigkeit: $humidity Prozent. ")
                if (windSpeed != null) append("Wind: ${windSpeed.toInt()} km pro Stunde. ")
                
                // Vorhersage auslesen
                val daily = json["daily"]?.jsonObject
                if (daily != null) {
                    val times = daily["time"]?.jsonArray
                    val weatherCodes = daily["weather_code"]?.jsonArray
                    val maxTemps = daily["temperature_2m_max"]?.jsonArray
                    val minTemps = daily["temperature_2m_min"]?.jsonArray
                    
                    if (times != null && weatherCodes != null && maxTemps != null && minTemps != null) {
                        // Heutiger Tagesbereich (Index 0) separat anzeigen
                        if (times.isNotEmpty()) {
                            val todayMax = maxTemps[0].jsonPrimitive.doubleOrNull
                            val todayMin = minTemps[0].jsonPrimitive.doubleOrNull
                            if (todayMax != null && todayMin != null) {
                                append("Heute: ${todayMin.toInt()} bis ${todayMax.toInt()} Grad. ")
                            }
                        }
                        
                        append("Vorhersage: ")
                        val sdfIn = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.GERMANY)
                        val sdfOut = java.text.SimpleDateFormat("EEEE", java.util.Locale.GERMANY)
                        
                        // Starte bei Index 1 (morgen), überspringe Index 0 (heute, da bereits oben abgedeckt)
                        for (i in 1 until minOf(times.size, 7)) {
                            val dateStr = times[i].jsonPrimitive.content
                            try {
                                val date = sdfIn.parse(dateStr)
                                val dayName = if (date != null) sdfOut.format(date) else dateStr
                                val wCode = weatherCodes[i].jsonPrimitive.intOrNull ?: 0
                                val maxT = maxTemps[i].jsonPrimitive.doubleOrNull
                                val minT = minTemps[i].jsonPrimitive.doubleOrNull
                                
                                val desc = wmoCodeToGerman(wCode)
                                if (maxT != null && minT != null) {
                                    append("$dayName: $desc, ${minT.toInt()} bis ${maxT.toInt()} Grad. ")
                                } else {
                                    append("$dayName: $desc. ")
                                }
                            } catch (e: Exception) {
                                // Ignore parse error for a specific day
                            }
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Abrufen des Wetters für Koordinaten", e)
            "Es tut mir leid, ich konnte das Wetter an diesem Standort gerade nicht abrufen."
        }
    }

    /**
     * Geocode: Stadtname → (latitude, longitude, resolvedName)
     */
    private fun geocodeCity(city: String): Triple<Double, Double, String>? {
        val encodedCity = java.net.URLEncoder.encode(city, "UTF-8")
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encodedCity&count=1&language=de&format=json"

        val request = Request.Builder().url(geoUrl).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return null

        Log.d(TAG, "Geocoding response for '$city': ${body.take(300)}")

        val json = Json.parseToJsonElement(body).jsonObject
        val results = json["results"]?.jsonArray ?: return null
        if (results.isEmpty()) return null

        val first = results[0].jsonObject
        val lat = first["latitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        val lon = first["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        val name = first["name"]?.jsonPrimitive?.content ?: city

        return Triple(lat, lon, name)
    }

    // Nicht mehr benötigt – wir nutzen jetzt überall toInt() für audio-freundliche Ganzzahlen

    /**
     * WMO Weather Interpretation Code → Deutsche Beschreibung
     * Quelle: https://open-meteo.com/en/docs
     */
    private fun wmoCodeToGerman(code: Int): String {
        return when (code) {
            0 -> "Klarer Himmel"
            1 -> "Überwiegend klar"
            2 -> "Teilweise bewölkt"
            3 -> "Bedeckt"
            45 -> "Nebel"
            48 -> "Gefrierender Nebel"
            51 -> "Leichter Nieselregen"
            53 -> "Mäßiger Nieselregen"
            55 -> "Starker Nieselregen"
            56 -> "Gefrierender leichter Nieselregen"
            57 -> "Gefrierender starker Nieselregen"
            61 -> "Leichter Regen"
            63 -> "Mäßiger Regen"
            65 -> "Starker Regen"
            66 -> "Gefrierender leichter Regen"
            67 -> "Gefrierender starker Regen"
            71 -> "Leichter Schneefall"
            73 -> "Mäßiger Schneefall"
            75 -> "Starker Schneefall"
            77 -> "Schneegriesel"
            80 -> "Leichte Regenschauer"
            81 -> "Mäßige Regenschauer"
            82 -> "Heftige Regenschauer"
            85 -> "Leichte Schneeschauer"
            86 -> "Starke Schneeschauer"
            95 -> "Gewitter"
            96 -> "Gewitter mit leichtem Hagel"
            99 -> "Gewitter mit starkem Hagel"
            else -> "Unbekanntes Wetter (Code $code)"
        }
    }
}
