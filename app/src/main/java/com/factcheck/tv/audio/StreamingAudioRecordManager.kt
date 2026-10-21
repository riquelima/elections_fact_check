package com.factcheck.tv.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.factcheck.tv.api.MiniMaxClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Motor de Streaming de Áudio com Janela de Contexto de 1 Minuto para Smart TVs e TV Boxes.
 * Lê o áudio ambiente da TV ininterruptamente a 16kHz PCM, transcreve em sub-blocos e
 * acumula 1 minuto completo de fala para gerar uma checagem precisa e contextualizada na IA.
 */
class StreamingAudioRecordManager(
    private val context: Context,
    private val miniMaxClient: MiniMaxClient,
    private val onStatusChanged: (String) -> Unit,
    private val onTranscriptRecognized: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isStreaming = false
    private var streamJob: Job? = null
    private var tickerJob: Job? = null

    @Volatile
    var isAnalyzing = false

    @Volatile
    private var windowStartTime = System.currentTimeMillis()
    private val minuteTranscriptAccumulator = StringBuilder()
    private val accumulatorLock = Any()

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CONTEXT_WINDOW_SECONDS = 60 // 1 minuto de contexto
        const val SUB_CHUNK_MS = 14_000L // Sub-blocos de 14 segundos para transcrição rápida

        /**
         * Empacota o buffer PCM bruto em um arquivo WAV de 16kHz Mono 16-bit com cabeçalho RIFF de 44 bytes.
         */
        fun pcmToWav(pcmData: ByteArray): ByteArray {
            val totalDataLen = pcmData.size + 36
            val byteRate = SAMPLE_RATE * 1 * 2
            val header = ByteArray(44)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            buffer.put("RIFF".toByteArray())
            buffer.putInt(totalDataLen)
            buffer.put("WAVE".toByteArray())
            buffer.put("fmt ".toByteArray())
            buffer.putInt(16)
            buffer.putShort(1.toShort())
            buffer.putShort(1.toShort())
            buffer.putInt(SAMPLE_RATE)
            buffer.putInt(byteRate)
            buffer.putShort(2.toShort())
            buffer.putShort(16.toShort())
            buffer.put("data".toByteArray())
            buffer.putInt(pcmData.size)

            val outputStream = ByteArrayOutputStream(header.size + pcmData.size)
            outputStream.write(header)
            outputStream.write(pcmData)
            return outputStream.toByteArray()
        }
    }

    @SuppressLint("MissingPermission")
    fun startStreaming() {
        if (isStreaming) return
        isStreaming = true
        isAnalyzing = false
        windowStartTime = System.currentTimeMillis()
        synchronized(accumulatorLock) {
            minuteTranscriptAccumulator.setLength(0)
        }

        mainHandler.post { onStatusChanged("🎙️ Ouvindo TV... (0s/60s)") }

        // Ticker suave de progresso a cada segundo
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isStreaming && isActive) {
                delay(1000L)
                if (!isAnalyzing) {
                    val elapsedSec = ((System.currentTimeMillis() - windowStartTime) / 1000).toInt().coerceIn(0, CONTEXT_WINDOW_SECONDS)
                    mainHandler.post {
                        onStatusChanged("🎙️ Ouvindo TV... (${elapsedSec}s/60s)")
                    }
                }
            }
        }

        streamJob = scope.launch {
            try {
                val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                val bufferSize = Math.max(minBufferSize * 2, 4096)

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("AudioStream", "AudioRecord não pôde ser inicializado")
                    mainHandler.post { onStatusChanged("⚠️ Microfone indisponível") }
                    return@launch
                }

                audioRecord?.startRecording()
                Log.i("AudioStream", "Motor de Escuta Contínua com Janela de 1 Minuto INICIADO.")

                val readBuffer = ByteArray(2048)
                val subChunkAccumulator = ByteArrayOutputStream()
                var subChunkStartTime = System.currentTimeMillis()
                var activeSpeechFrames = 0

                while (isStreaming && isActive) {
                    val bytesRead = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: -1
                    if (bytesRead > 0) {
                        val rms = calculateRms(readBuffer, bytesRead)
                        if (rms > 25.0) {
                            activeSpeechFrames++
                        }
                        subChunkAccumulator.write(readBuffer, 0, bytesRead)

                        val subElapsedMs = System.currentTimeMillis() - subChunkStartTime
                        val totalWindowElapsedMs = System.currentTimeMillis() - windowStartTime

                        // 1. Processa sub-bloco de áudio a cada 14 segundos
                        if (subElapsedMs >= SUB_CHUNK_MS) {
                            val pcmBytes = subChunkAccumulator.toByteArray()
                            subChunkAccumulator.reset()
                            subChunkStartTime = System.currentTimeMillis()
                            val hadSpeech = activeSpeechFrames >= 3
                            activeSpeechFrames = 0

                            if (hadSpeech && pcmBytes.isNotEmpty()) {
                                launch(Dispatchers.IO) {
                                    try {
                                        val wavBytes = pcmToWav(pcmBytes)
                                        val transcript = miniMaxClient.transcribeAudioChunk(wavBytes)
                                        if (!transcript.isNullOrBlank() && transcript.trim().length >= 5) {
                                            val cleanText = transcript.trim()
                                            synchronized(accumulatorLock) {
                                                if (minuteTranscriptAccumulator.isNotEmpty()) {
                                                    minuteTranscriptAccumulator.append(" ")
                                                }
                                                minuteTranscriptAccumulator.append(cleanText)
                                            }
                                            Log.d("AudioStream", "Trecho transcrito: \"$cleanText\" | Total acumulado no minuto: ${minuteTranscriptAccumulator.length} chars")
                                        }
                                    } catch (e: Exception) {
                                        Log.w("AudioStream", "Erro ao transcrever sub-bloco: ${e.message}")
                                    }
                                }
                            }
                        }

                        // 2. Quando completa a janela de 60 segundos (1 minuto)
                        if (totalWindowElapsedMs >= CONTEXT_WINDOW_SECONDS * 1000L) {
                            windowStartTime = System.currentTimeMillis()
                            val fullContextText = synchronized(accumulatorLock) {
                                val text = minuteTranscriptAccumulator.toString().trim()
                                minuteTranscriptAccumulator.setLength(0)
                                text
                            }

                            if (fullContextText.length >= 25) {
                                Log.i("AudioStream", "=== 1 MINUTO DE CONTEXTO COLETADO: \"$fullContextText\" ===")
                                isAnalyzing = true
                                mainHandler.post { onStatusChanged("⚡ Analisando contexto de 1 min...") }
                                onTranscriptRecognized(fullContextText)
                            } else {
                                Log.i("AudioStream", "Janela de 1 min com pouca fala (${fullContextText.length} chars). Continuando escuta.")
                                mainHandler.post { onStatusChanged("🎙️ Ouvindo TV... (0s/60s)") }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioStream", "Erro no loop de streaming de áudio: ${e.message}", e)
                mainHandler.post { onStatusChanged("🎙️ Reconectando áudio...") }
            } finally {
                releaseAudioRecord()
            }
        }
    }

    private fun calculateRms(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        val sampleCount = length / 2
        if (sampleCount == 0) return 0.0

        for (i in 0 until length step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val shortVal = sample.toShort()
            sum += (shortVal * shortVal).toDouble()
        }
        val mean = sum / sampleCount
        return Math.sqrt(mean)
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("AudioStream", "Erro ao liberar AudioRecord: ${e.message}")
        }
        audioRecord = null
    }

    fun stopStreaming() {
        isStreaming = false
        isAnalyzing = false
        tickerJob?.cancel()
        tickerJob = null
        streamJob?.cancel()
        streamJob = null
        releaseAudioRecord()
        mainHandler.post { onStatusChanged("Monitoramento inativo") }
    }
}
