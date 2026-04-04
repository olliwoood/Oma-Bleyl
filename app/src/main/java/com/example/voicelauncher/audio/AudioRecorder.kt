package com.example.voicelauncher.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    
    // Gemini Live API expects 16kHz, 16-bit, Mono PCM
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    fun startRecording(onAudioData: (ByteArray) -> Unit) {
        // Atomar: Nur ein Thread kann die Aufnahme starten
        if (!isRecording.compareAndSet(false, true)) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.w("AudioRecorder", "VOICE_COMMUNICATION failed, falling back to MIC")
                audioRecord?.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.w("AudioRecorder", "MIC failed, falling back to DEFAULT")
                audioRecord?.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioRecorder", "Failed to initialize AudioRecord entirely")
                isRecording.set(false)
                return
            }

            // Echo-Unterdrückung aktivieren (verhindert, dass Gemini sich selbst hört)
            val sessionId = audioRecord!!.audioSessionId
            attachEchoCanceler(sessionId)
            attachNoiseSuppressor(sessionId)

        } catch (e: Exception) {
            Log.e("AudioRecorder", "Exception initializing AudioRecord", e)
            isRecording.set(false)
            return
        }

        audioRecord?.startRecording()

        recordThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            // Live API präferiert exakte Chunks, oft 1024 oder 2048 für flottes Streaming
            val chunkSize = 2048
            val buffer = ByteArray(chunkSize)
            var chunkCount = 0
            while (isRecording.get()) {
                val readStatus = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (readStatus > 0) {
                    val actualData = buffer.copyOfRange(0, readStatus)
                    chunkCount++
                    if (chunkCount % 50 == 0) {
                        Log.d("AudioRecorder", "Captured and sending $chunkCount chunks of audio... (${actualData.size} bytes)")
                    }
                    onAudioData(actualData)
                } else if (readStatus < 0) {
                    Log.e("AudioRecorder", "Error reading audio: $readStatus")
                }
            }
        }.apply {
            name = "AudioRecordThread"
            start()
        }
    }

    fun stopRecording() {
        if (!isRecording.getAndSet(false)) return
        
        // Thread zuerst beenden lassen, bevor AudioRecord released wird
        try {
            recordThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.w("AudioRecorder", "RecordThread join interrupted")
        }
        recordThread = null
        
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w("AudioRecorder", "Fehler beim Stoppen von AudioRecord", e)
        }
        audioRecord?.release()
        audioRecord = null
        releaseAudioEffects()
    }
    
    private fun attachEchoCanceler(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)
                echoCanceler?.enabled = true
                Log.d("AudioRecorder", "AcousticEchoCanceler aktiviert (Session $sessionId)")
            } else {
                Log.w("AudioRecorder", "AcousticEchoCanceler nicht verfügbar auf diesem Gerät")
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Fehler beim Aktivieren des Echo Cancelers", e)
        }
    }
    
    private fun attachNoiseSuppressor(sessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
                Log.d("AudioRecorder", "NoiseSuppressor aktiviert (Session $sessionId)")
            } else {
                Log.w("AudioRecorder", "NoiseSuppressor nicht verfügbar auf diesem Gerät")
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Fehler beim Aktivieren des Noise Suppressors", e)
        }
    }
    
    private fun releaseAudioEffects() {
        try {
            echoCanceler?.release()
            echoCanceler = null
            noiseSuppressor?.release()
            noiseSuppressor = null
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Fehler beim Freigeben der Audio-Effekte", e)
        }
    }
}
