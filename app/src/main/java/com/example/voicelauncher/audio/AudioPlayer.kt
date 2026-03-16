package com.example.voicelauncher.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue

class AudioPlayer {
    private var audioTrack: AudioTrack? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var playThread: Thread? = null
    @Volatile private var isPlaying = false

    // Gemini Live API returns 24kHz, 16-bit, Mono PCM
    private val sampleRate = 24000
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // Pre-buffering: Erst abspielen wenn genug Daten da sind, verhindert Underruns
    private val PRE_BUFFER_CHUNKS = 5

    init {
        openAudioTrack()
    }

    @Synchronized
    private fun openAudioTrack() {
        if (isPlaying || playThread != null) {
            stopPlayThread()
        }

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 12) // Sehr großer Buffer verhindert Underruns
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            
        try {
            audioTrack?.play()
            startPlayThread()
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Error starting AudioTrack", e)
        }
    }

    private fun startPlayThread() {
        isPlaying = true
        audioQueue.clear() // Clear any stale audio
        playThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var preBuffering = true
            while (isPlaying) {
                try {
                    // Pre-buffering: Warte bis genug Chunks da sind bevor Wiedergabe startet
                    if (preBuffering) {
                        if (audioQueue.size >= PRE_BUFFER_CHUNKS) {
                            preBuffering = false
                            Log.d("AudioPlayer", "Pre-buffer gefüllt, starte Wiedergabe")
                        } else {
                            Thread.sleep(10)
                            continue
                        }
                    }

                    // Poll queue with a timeout so thread can exit cleanly when isPlaying becomes false
                    val pcmData = audioQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (pcmData != null && audioTrack != null) {
                        try {
                            if (audioTrack?.state == AudioTrack.STATE_UNINITIALIZED) {
                                Log.w("AudioPlayer", "AudioTrack invalid, recreating in thread...")
                                recreateAudioTrack()
                            }
                            audioTrack?.write(pcmData, 0, pcmData.size)
                        } catch (e: IllegalStateException) {
                            Log.w("AudioPlayer", "AudioTrack invalid (e.g. after phone call), recreating...", e)
                            recreateAudioTrack()
                            try {
                                audioTrack?.write(pcmData, 0, pcmData.size)
                            } catch (e2: Exception) {
                                Log.e("AudioPlayer", "Failed to write audio even after recreating AudioTrack", e2)
                            }
                        } catch (e: Exception) {
                            Log.e("AudioPlayer", "Error writing audio data", e)
                        }
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.d("AudioPlayer", "PlayThread interrupted")
                    break
                }
            }
        }.apply {
            name = "AudioPlaybackThread"
            start()
        }
    }

    @Synchronized
    private fun recreateAudioTrack() {
        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
           Log.e("AudioPlayer", "Error releasing old AudioTrack in recreate", e)
        }
        
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 12)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Error starting recreated AudioTrack", e)
        }
    }

    private fun stopPlayThread() {
        isPlaying = false
        playThread?.interrupt()
        playThread = null
        audioQueue.clear()
    }

    fun playAudio(pcmData: ByteArray) {
        if (audioTrack == null || audioTrack?.state == AudioTrack.STATE_UNINITIALIZED) {
            openAudioTrack()
        }
        audioQueue.offer(pcmData)
    }

    @Synchronized
    fun stopAndRelease() {
        stopPlayThread()
        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    flush() // Restliche Samples sauber ausgeben
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
             Log.e("AudioPlayer", "Error stopping AudioTrack", e)
        } finally {
            audioTrack = null
        }
    }
}
