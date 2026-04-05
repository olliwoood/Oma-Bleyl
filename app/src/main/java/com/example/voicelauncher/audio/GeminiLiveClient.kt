package com.example.voicelauncher.audio

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import okhttp3.*
import okio.ByteString
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Tool-Gruppen für dynamisches Laden.
 * Nur CORE wird immer geladen, der Rest on-demand.
 */
enum class ToolGroup {
    CORE,           // handle_memory, get_current_time, stop_audio_session, get_session_logs
    COMMUNICATION,  // make_phone_call, answer_call, reject_call, toggle_speakerphone, get_latest_sms, get_sms_history_with_contact
    CALENDAR,       // manage_calendar, delete_calendar_event, reschedule_calendar_event
    CONTACTS,       // manage_contacts, get_contact_list, get_birthdays
    ALARM,          // set_alarm, dismiss_alarm, delete_alarm
    WEATHER         // get_weather
}

class GeminiLiveClient(private val apiKey: String, private val context: Context? = null) {

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)   // Keepalive gegen Connection-Drops
        .readTimeout(0, TimeUnit.MILLISECONDS) // Kein Read-Timeout für Streaming
        .build()
    private var webSocket: WebSocket? = null
    var isSetupComplete = false
        private set
    private var isUserDisconnect = false
    
    // Für Auto-Reconnect bei unerwartetem Abbruch
    private var lastSystemPrompt: String? = null
    private var lastToolGroups: Set<ToolGroup>? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    
    // Aktuell geladene Tool-Gruppen für die laufende Session
    private var activeToolGroups = mutableSetOf<ToolGroup>()

    // Setup-Timeout: Wenn Server nach 10s kein setupComplete sendet → aufräumen
    private val setupTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val setupTimeoutMs = 10000L
    
    // Verhindert Audio-Senden während Gemini spricht (sonst interrupted-Kaskade)
    @Volatile var isGeminiSpeaking = false
        private set

    // Grace-Period: Nach turnComplete braucht der AudioPlayer noch kurz zum Abspielen
    // des gepufferten Audios. In dieser Zeit bleibt das Mikro blockiert.
    private var speakingEndedAtMs = 0L
    private val postSpeechGraceMs = 1000L
    
    // Callback für einkommende PCM-Audiodaten von Gemini
    var onAudioReceived: ((ByteArray) -> Unit)? = null

    // Callback für Tool Calls (z.B. Gedächtnis speichern oder Telefonieren)
    var onToolCallReceived: ((String, JsonObject) -> JsonObject)? = null
    
    // Callback wenn Setup fertig ist und Audio gestartet werden darf
    var onSetupComplete: (() -> Unit)? = null
    
    // Callback wenn die Verbindung getrennt wird
    var onDisconnected: (() -> Unit)? = null

    // Callback: Prüft ob AudioPlayer noch gepuffertes Audio abspielt
    var isAudioPlaybackActive: (() -> Boolean)? = null
    


    fun connect(systemPrompt: String, toolGroups: Set<ToolGroup> = setOf(ToolGroup.CORE), isReconnect: Boolean = false) {
        // Bereits verbunden → nichts tun
        if (isSetupComplete) {
            Log.d("GeminiLiveClient", "Bereits verbunden, überspringe connect()")
            return
        }
        // Verbindung wird gerade aufgebaut → nicht doppelt verbinden
        if (webSocket != null && !isReconnect) {
            Log.d("GeminiLiveClient", "Verbindung wird bereits aufgebaut, überspringe connect()")
            return
        }

        // Schutz gegen Zombie-Reconnect: Wenn disconnect() schon aufgerufen wurde,
        // aber ein postDelayed-Timer noch connect() auslöst → abbrechen.
        // Gilt NUR für Reconnects – frische Verbindungen sollen immer funktionieren.
        if (isUserDisconnect && isReconnect) {
            Log.w("GeminiLiveClient", "Reconnect abgebrochen – disconnect() wurde bereits aufgerufen")
            return
        }

        // Neuer Connect-Aufruf → Flag zurücksetzen
        isUserDisconnect = false
        
        // Setup-Timeout starten (alten Timer abbrechen)
        setupTimeoutHandler.removeCallbacksAndMessages(null)
        setupTimeoutHandler.postDelayed({
            if (!isSetupComplete && !isUserDisconnect) {
                Log.e("GeminiLiveClient", "Setup-Timeout nach ${setupTimeoutMs}ms – Verbindung wird beendet")
                disconnect()
                onDisconnected?.invoke()
            }
        }, setupTimeoutMs)

        activeToolGroups = toolGroups.toMutableSet()
        lastSystemPrompt = systemPrompt
        lastToolGroups = toolGroups
        if (!isReconnect) reconnectAttempts = 0
        
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        
        val requestBuilder = Request.Builder()
            .url(url)
        
        // Android-Identifikation hinzufügen für API-Key-Einschränkung
        context?.let { ctx ->
            requestBuilder.header("X-Android-Package", ctx.packageName)
            getAppCertFingerprint(ctx)?.let { fingerprint ->
                requestBuilder.header("X-Android-Cert", fingerprint)
            }
        }
        
        val request = requestBuilder.build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("GeminiLiveClient", "WebSocket Connected")
                sendInitialSetup(systemPrompt)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingMessage(bytes.utf8())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("GeminiLiveClient", "WebSocket Closed: Code $code, Reason: $reason")
                isSetupComplete = false
                if (!isUserDisconnect) {
                    onDisconnected?.invoke()
                }
                isUserDisconnect = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("GeminiLiveClient", "WebSocket Error: ${t.message}", t)
                isSetupComplete = false
                isGeminiSpeaking = false
                
                if (!isUserDisconnect && reconnectAttempts < maxReconnectAttempts) {
                    val prompt = lastSystemPrompt
                    val groups = lastToolGroups
                    if (prompt != null && groups != null) {
                        reconnectAttempts++
                        Log.w("GeminiLiveClient", "Verbindung verloren, Reconnect-Versuch $reconnectAttempts/$maxReconnectAttempts...")
                        // Kurz warten, dann neu verbinden
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            connect(prompt, groups, isReconnect = true)
                        }, 3000L * reconnectAttempts) // Progressives Delay: 3s, 6s, 9s, 12s, 15s
                        return
                    }
                }
                
                if (!isUserDisconnect) {
                    if (reconnectAttempts >= maxReconnectAttempts) {
                        Log.e("GeminiLiveClient", "Alle $maxReconnectAttempts Reconnect-Versuche fehlgeschlagen.")
                    }
                    onDisconnected?.invoke()
                }
                isUserDisconnect = false
            }
        })
    }
    
    /**
     * Liest das SHA-1 Zertifikat-Fingerprint der App-Signatur aus.
     * Wird für die API-Key-Einschränkung (X-Android-Cert Header) benötigt.
     */
    private fun getAppCertFingerprint(ctx: Context): String? {
        return try {
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= 28) {
                ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            }
            
            val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }
            
            signatures?.firstOrNull()?.let { signature ->
                val md = MessageDigest.getInstance("SHA-1")
                val digest = md.digest(signature.toByteArray())
                digest.joinToString("") { "%02X".format(it) }
            }
        } catch (e: Exception) {
            Log.e("GeminiLiveClient", "Fehler beim Lesen des App-Zertifikats", e)
            null
        }
    }
    
    private fun sendInitialSetup(systemPrompt: String) {
        val toolDeclarations = buildToolDeclarations(activeToolGroups)

        val setupMessage = buildJsonObject {
            put("setup", buildJsonObject {
                put("model", "models/gemini-3.1-flash-live-preview")
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", systemPrompt) })
                    })
                })
                put("generationConfig", buildJsonObject {
                    put("responseModalities", buildJsonArray { add("AUDIO") })
                    put("speechConfig", buildJsonObject {
                        put("voiceConfig", buildJsonObject {
                            put("prebuiltVoiceConfig", buildJsonObject {
                                put("voiceName", "Puck")
                            })
                        })
                    })
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingLevel", "minimal")
                    })
                })
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("functionDeclarations", toolDeclarations)
                    })
                })
            })
        }
        val msgStr = setupMessage.toString()
        try {
            webSocket?.send(msgStr)
        } catch (e: Exception) {
            Log.e("GeminiLiveClient", "Setup-Nachricht konnte nicht gesendet werden", e)
        }
        Log.d("GeminiLiveClient", "Sent setup (${msgStr.length} chars, ${activeToolGroups.size} groups, prompt=${systemPrompt.length} chars)")
    }
    
    /**
     * Baut die Tool-Deklarationen basierend auf den aktiven Gruppen.
     * Beschreibungen sind minimal gehalten um Tokens zu sparen.
     */
    private fun buildToolDeclarations(groups: Set<ToolGroup>): JsonArray {
        return buildJsonArray {
            // === CORE (immer geladen) ===
            if (ToolGroup.CORE in groups) {
                // Gedächtnis
                add(buildJsonObject {
                    put("name", "handle_memory")
                    put("description", "PROAKTIV nutzen! Speichert/sucht/löscht Fakten. NICHT für Kalendertermine.")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", "STRING")
                                put("description", "'save', 'search' oder 'delete'.")
                            })
                            put("content", buildJsonObject {
                                put("type", "STRING")
                                put("description", "Suchbegriff oder Fakt.")
                            })
                        })
                        put("required", buildJsonArray { add("action"); add("content") })
                    })
                })
                // Zeit
                add(buildJsonObject {
                    put("name", "get_current_time")
                    put("description", "Gibt Systemzeit/Datum.")
                })
                // Session beenden
                add(buildJsonObject {
                    put("name", "stop_audio_session")
                    put("description", "Beendet Sitzung. IMMER bei 'Tschüss' etc. nutzen.")
                })
                // Session Logs
                add(buildJsonObject {
                    put("name", "get_session_logs")
                    put("description", "Vergangene Log-Ereignisse abrufen.")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("lines", buildJsonObject {
                                put("type", "INTEGER")
                                put("description", "Zeilenanzahl (z.B. 20)")
                            })
                        })
                    })
                })
            }
            
            // === KOMMUNIKATION ===
            if (ToolGroup.COMMUNICATION in groups) {
                add(buildJsonObject {
                    put("name", "make_phone_call")
                    put("description", "AUSGEHENDEN Anruf starten – wenn der Nutzer jemanden ANRUFEN will. NICHT für eingehende Anrufe!")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "STRING")
                                put("description", "Name des Kontakts.")
                            })
                        })
                        put("required", buildJsonArray { add("query") })
                    })
                })

                add(buildJsonObject {
                    put("name", "toggle_speakerphone")
                    put("description", "Freisprechfunktion (Lautsprecher) ein-/ausschalten während eines Anrufs.")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("enabled", buildJsonObject {
                                put("type", "BOOLEAN")
                                put("description", "true=Lautsprecher an, false=Lautsprecher aus.")
                            })
                        })
                        put("required", buildJsonArray { add("enabled") })
                    })
                })
                add(buildJsonObject {
                    put("name", "get_latest_sms")
                    put("description", "Holt neueste SMS Inbox Übersicht.")
                })
                add(buildJsonObject {
                    put("name", "get_sms_history_with_contact")
                    put("description", "Holt SMS Chat Historie mit einem Kontakt.")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("contact_name", buildJsonObject {
                                put("type", "STRING")
                            })
                            put("limit", buildJsonObject {
                                put("type", "INTEGER")
                                put("description", "Max 5")
                            })
                        })
                        put("required", buildJsonArray { add("contact_name") })
                    })
                })
            }
            
            // === KALENDER ===
            if (ToolGroup.CALENDAR in groups) {
                add(buildJsonObject {
                    put("name", "manage_calendar")
                    put("description", "Google Kalender Termin hinzufügen oder auflisten.")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", "STRING")
                                put("description", "'add' oder 'list'.")
                            })
                            put("title", buildJsonObject { put("type", "STRING") })
                            put("day", buildJsonObject { put("type", "INTEGER") })
                            put("month", buildJsonObject { put("type", "INTEGER") })
                            put("year", buildJsonObject { put("type", "INTEGER") })
                            put("hour", buildJsonObject { put("type", "INTEGER") })
                            put("minute", buildJsonObject { put("type", "INTEGER") })
                        })
                        put("required", buildJsonArray { add("action") })
                    })
                })
                add(buildJsonObject {
                    put("name", "delete_calendar_event")
                    put("description", "Löscht Termin. Vorher Omi Bescheid geben.")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("title", buildJsonObject { put("type", "STRING") })
                            put("day", buildJsonObject { put("type", "INTEGER") })
                            put("month", buildJsonObject { put("type", "INTEGER") })
                            put("year", buildJsonObject { put("type", "INTEGER") })
                        })
                    })
                })
                add(buildJsonObject {
                    put("name", "reschedule_calendar_event")
                    put("description", "Verschiebt Termin. Vorher Omi Bescheid geben.")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("title", buildJsonObject { put("type", "STRING") })
                            put("day", buildJsonObject { put("type", "INTEGER") })
                            put("month", buildJsonObject { put("type", "INTEGER") })
                            put("year", buildJsonObject { put("type", "INTEGER") })
                            put("hour", buildJsonObject { put("type", "INTEGER") })
                            put("minute", buildJsonObject { put("type", "INTEGER") })
                            put("durationMinutes", buildJsonObject { put("type", "INTEGER") })
                        })
                        put("required", buildJsonArray { add("title") })
                    })
                })
            }
            
            // === KONTAKTE ===
            if (ToolGroup.CONTACTS in groups) {
                add(buildJsonObject {
                    put("name", "manage_contacts")
                    put("description", "Kontakte verwalten (create/update). NUR für Name und Geburtstag. Persönliche Infos/Notizen IMMER über handle_memory speichern!")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("action", buildJsonObject {
                                put("type", "STRING")
                                put("description", "'create' oder 'update'.")
                            })
                            put("name", buildJsonObject { put("type", "STRING") })
                            put("newName", buildJsonObject { put("type", "STRING") })
                            put("birthdayDay", buildJsonObject { put("type", "INTEGER") })
                            put("birthdayMonth", buildJsonObject { put("type", "INTEGER") })
                        })
                        put("required", buildJsonArray { add("action"); add("name") })
                    })
                })
                add(buildJsonObject {
                    put("name", "get_contact_list")
                    put("description", "Gibt Telefonbuch aus.")
                })
                add(buildJsonObject {
                    put("name", "get_birthdays")
                    put("description", "Gibt Geburtstage aus.")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject { put("type", "STRING") })
                        })
                    })
                })
            }
            
            // === WECKER ===
            if (ToolGroup.ALARM in groups) {
                add(buildJsonObject {
                    put("name", "set_alarm")
                    put("description", "Stellt einmaligen Wecker.")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("hour", buildJsonObject {
                                put("type", "INTEGER")
                                put("description", "0-23")
                            })
                            put("minute", buildJsonObject {
                                put("type", "INTEGER")
                                put("description", "0-59")
                            })
                            put("label", buildJsonObject {
                                put("type", "STRING")
                            })
                        })
                        put("required", buildJsonArray { add("hour"); add("minute"); add("label") })
                    })
                })
                add(buildJsonObject {
                    put("name", "dismiss_alarm")
                    put("description", "Schaltet klingelnden Wecker aus.")
                })
                add(buildJsonObject {
                    put("name", "delete_alarm")
                    put("description", "Löscht gestellten Wecker.")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("label", buildJsonObject {
                                put("type", "STRING")
                                put("description", "Label muss exakt matchen.")
                            })
                        })
                        put("required", buildJsonArray { add("label") })
                    })
                })
            }
            
            // === WETTER ===
            if (ToolGroup.WEATHER in groups) {
                add(buildJsonObject {
                    put("name", "get_weather")
                    put("description", "Wetter 7-Tage. Ohne Stadt: 'CURRENT_LOCATION' übergeben.")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("city", buildJsonObject {
                                put("type", "STRING")
                                put("description", "Stadtname oder 'CURRENT_LOCATION'.")
                            })
                        })
                        put("required", buildJsonArray { add("city") })
                    })
                })
            }
        }
    }

    fun sendAudio(pcmData: ByteArray) {
        val ws = webSocket ?: return
        if (!isSetupComplete) return
        
        // Kein Audio senden während Gemini spricht → verhindert interrupted-Kaskade
        if (isGeminiSpeaking) return
        // Grace-Period: AudioPlayer spielt noch gepuffertes Audio ab
        if (System.currentTimeMillis() - speakingEndedAtMs < postSpeechGraceMs) return
        // Warten bis AudioPlayer-Queue leer ist (sonst hört Gemini sich selbst vom Lautsprecher)
        if (isAudioPlaybackActive?.invoke() == true) return
        
        val base64Audio = Base64.encodeToString(pcmData, Base64.NO_WRAP)

        val message = buildJsonObject {
            put("realtimeInput", buildJsonObject {
                put("audio", buildJsonObject {
                    put("data", base64Audio)
                    put("mimeType", "audio/pcm;rate=16000")
                })
            })
        }
        
        try {
            ws.send(message.toString())
        } catch (e: Exception) {
            Log.w("GeminiLiveClient", "sendAudio fehlgeschlagen (WebSocket geschlossen?): ${e.message}")
        }
    }

    fun sendClientContent(text: String) {
        val ws = webSocket ?: return
        if (!isSetupComplete) return

        val message = buildJsonObject {
            put("realtimeInput", buildJsonObject {
                put("text", text)
            })
        }

        try {
            ws.send(message.toString())
            Log.d("GeminiLiveClient", "Sent realtime text: $text")
        } catch (e: Exception) {
            Log.w("GeminiLiveClient", "sendClientContent fehlgeschlagen (WebSocket geschlossen?): ${e.message}")
        }
    }

    private fun handleIncomingMessage(jsonString: String) {
        try {
            val jsonResponse = Json.parseToJsonElement(jsonString).jsonObject
            
            // Logge nur nicht-audio Nachrichten (Audio-Base64 flutet sonst das Logcat)
            if (!jsonString.contains("inlineData")) {
                Log.d("GeminiLiveClient", "MSG: ${jsonString.take(300)}")
            }
            
            if (jsonResponse.containsKey("setupComplete")) {
                isSetupComplete = true
                setupTimeoutHandler.removeCallbacksAndMessages(null)
                Log.d("GeminiLiveClient", "Setup confirmed by server. Audio stream can start.")
                onSetupComplete?.invoke()
                return
            }
            
            // Falls der Server Fehler meldet
            if (jsonResponse.containsKey("error")) {
                 val errorStr = jsonResponse["error"]?.toString() ?: "Unknown error"
                 Log.e("GeminiLiveClient", "Server Error: $errorStr")
            }
            
            // ServerContent enthält Antworten von Gemini
            jsonResponse["serverContent"]?.jsonObject?.let { serverContent ->
                val modelTurn = serverContent["modelTurn"]?.jsonObject
                
                // Audio oder Text Extrahieren
                modelTurn?.get("parts")?.jsonArray?.forEach { part ->
                    val partObj = part.jsonObject
                    
                    // Text-Part (falls er trotz Audio-Anforderung als Text kommt!)
                    partObj["text"]?.jsonPrimitive?.content?.let { text ->
                        Log.d("GeminiLiveClient", "Gemini antwortet (Text): $text")
                    }

                    // Audio-Part
                    partObj["inlineData"]?.jsonObject?.let { inlineData ->
                        if (inlineData["mimeType"]?.jsonPrimitive?.content?.startsWith("audio/pcm") == true) {
                            isGeminiSpeaking = true
                            val base64Data = inlineData["data"]?.jsonPrimitive?.content
                            if (base64Data != null) {
                                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                onAudioReceived?.invoke(decodedBytes)
                            }
                        }
                    }
                }
                
                // Nur turnComplete → Turn ist definitiv vorbei, Mikro darf wieder senden.
                // generationComplete bedeutet nur, dass das Modell fertig generiert hat,
                // aber Audio-Chunks können noch im Stream unterwegs sein.
                if (serverContent.containsKey("turnComplete")) {
                    isGeminiSpeaking = false
                    speakingEndedAtMs = System.currentTimeMillis()
                }
            }
            
            // Top-Level ToolCall abfangen (Gemini sendet diese SEPARAT, nicht in serverContent!)
            jsonResponse["toolCall"]?.jsonObject?.let { toolCall ->
                // NICHT isGeminiSpeaking = false setzen!
                // Gemini spricht nach dem Tool-Response weiter.
                // Erst turnComplete/generationComplete beendet den Turn.
                toolCall["functionCalls"]?.jsonArray?.forEach { fc ->
                    val call = fc.jsonObject
                    val name = call["name"]?.jsonPrimitive?.content
                    val id = call["id"]?.jsonPrimitive?.content
                    val args = call["args"]?.jsonObject
                    
                    if (name != null && id != null && args != null) {
                        Log.d("GeminiLiveClient", "Tool Call empfangen: $name mit args: $args")
                        val responseObj = onToolCallReceived?.invoke(name, args)
                        if (responseObj != null) {
                            sendToolResponse(id, name, responseObj)
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("GeminiLiveClient", "Error parsing message", e)
        }
    }

    private fun sendToolResponse(id: String, name: String, response: JsonObject) {
         val message = buildJsonObject {
            put("toolResponse", buildJsonObject {
                put("functionResponses", buildJsonArray {
                    add(buildJsonObject {
                        put("id", id)
                        put("name", name)
                        put("response", response)
                    })
                })
            })
        }
        try {
            webSocket?.send(message.toString())
        } catch (e: Exception) {
            Log.w("GeminiLiveClient", "Tool-Response konnte nicht gesendet werden", e)
        }
    }

    fun disconnect() {
        isUserDisconnect = true
        reconnectAttempts = maxReconnectAttempts // Verhindert Auto-Reconnect
        setupTimeoutHandler.removeCallbacksAndMessages(null)
        lastSystemPrompt = null
        lastToolGroups = null
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isSetupComplete = false
        isGeminiSpeaking = false
    }
}
