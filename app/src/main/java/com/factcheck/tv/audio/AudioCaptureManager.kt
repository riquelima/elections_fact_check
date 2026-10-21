package com.factcheck.tv.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Manages low-latency audio capture from TV ambient microphone or USB mic.
 */
class AudioCaptureManager(
    private val onSpeechChunkReady: (ByteArray) -> Unit
) {

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var captureJob: Job? = null

    @SuppressLint("MissingPermission")
    fun startCapture(scope: CoroutineScope) {
        if (isRecording) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioCapture", "Falha ao inicializar AudioRecord do Android TV")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            captureJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(minBufferSize)
                val chunkStream = ByteArrayOutputStream()
                var speechFrames = 0
                val framesPerChunk = (sampleRate * 2 * 6) / minBufferSize // ~6 segundos de áudio

                while (isActive && isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val rms = calculateRMS(buffer, read)
                        // Limiar de detecção de fala (descarta silêncio)
                        if (rms > 250) {
                            chunkStream.write(buffer, 0, read)
                            speechFrames++

                            if (speechFrames >= framesPerChunk) {
                                val chunk = chunkStream.toByteArray()
                                chunkStream.reset()
                                speechFrames = 0
                                onSpeechChunkReady(chunk)
                            }
                        } else {
                            if (speechFrames > 0 && chunkStream.size() > (sampleRate * 2 * 2)) {
                                // Pausa após fala de mais de 2s: despacha o trecho
                                val chunk = chunkStream.toByteArray()
                                chunkStream.reset()
                                speechFrames = 0
                                onSpeechChunkReady(chunk)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioCapture", "Erro ao iniciar captura: ${e.message}", e)
        }
    }

    fun stopCapture() {
        isRecording = false
        captureJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("AudioCapture", "Erro ao parar AudioRecord: ${e.message}")
        }
        audioRecord = null
    }

    private fun calculateRMS(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        var i = 0
        while (i < length - 1) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            sum += sample * sample
            i += 2
        }
        return sqrt(sum / (length / 2))
    }
}
