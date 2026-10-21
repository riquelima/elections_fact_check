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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Motor de Streaming de Áudio Contínuo em Baixo Nível (AudioRecord) para Smart TVs e TV Boxes.
 * Lê o fluxo PCM da TV ininterruptamente a 16kHz, calcula energia sonora (RMS) e
 * despacha blocos de fala diretamente para o motor neural da IA em tempo real.
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

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /**
         * Empacota o buffer PCM bruto em um arquivo WAV de 16kHz Mono 16-bit com cabeçalho RIFF de 44 bytes.
         */
        fun pcmToWav(pcmData: ByteArray): ByteArray {
            val totalDataLen = pcmData.size + 36
            val byteRate = SAMPLE_RATE * 1 * 2 // 16000 * 1 canal * 2 bytes por amostra = 32000
            val header = ByteArray(44)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            buffer.put("RIFF".toByteArray())
            buffer.putInt(totalDataLen)
            buffer.put("WAVE".toByteArray())
            buffer.put("fmt ".toByteArray())
            buffer.putInt(16) // Subchunk1Size (16 para PCM)
            buffer.putShort(1.toShort()) // Formato de áudio 1 = PCM
            buffer.putShort(1.toShort()) // 1 canal (Mono)
            buffer.putInt(SAMPLE_RATE)
            buffer.putInt(byteRate)
            buffer.putShort(2.toShort()) // BlockAlign (canais * bits / 8)
            buffer.putShort(16.toShort()) // 16 bits por amostra
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

        mainHandler.post { onStatusChanged("🎙️ Conectando motor de stream...") }

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
                    Log.e("AudioStream", "AudioRecord não pôde ser inicializado pelo hardware")
                    mainHandler.post { onStatusChanged("⚠️ Microfone indisponível") }
                    return@launch
                }

                audioRecord?.startRecording()
                Log.i("AudioStream", "Motor de Streaming Contínuo INICIADO a 16kHz PCM.")
                mainHandler.post { onStatusChanged("🎙️ Ouvindo TV ao vivo...") }

                val readBuffer = ByteArray(2048)
                val chunkAccumulator = ByteArrayOutputStream()
                var chunkStartTime = System.currentTimeMillis()
                var activeSpeechFrames = 0
                var silenceFrames = 0

                while (isStreaming && isActive) {
                    val bytesRead = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: -1
                    if (bytesRead > 0) {
                        val rms = calculateRms(readBuffer, bytesRead)
                        val isSoundDetected = rms > 25.0 // Limiar para detectar fala/som da TV

                        chunkAccumulator.write(readBuffer, 0, bytesRead)
                        if (isSoundDetected) {
                            activeSpeechFrames++
                            silenceFrames = 0
                        } else {
                            silenceFrames++
                        }

                        val elapsedMs = System.currentTimeMillis() - chunkStartTime

                        // Corta em blocos de 3.8s a 4.5s de fala da TV
                        val shouldFlush = (elapsedMs >= 4200L) || (elapsedMs >= 2600L && silenceFrames >= 4)

                        if (shouldFlush) {
                            val pcmBytes = chunkAccumulator.toByteArray()
                            chunkAccumulator.reset()
                            chunkStartTime = System.currentTimeMillis()
                            val hadSpeech = activeSpeechFrames >= 2
                            activeSpeechFrames = 0
                            silenceFrames = 0

                            if (hadSpeech && pcmBytes.isNotEmpty()) {
                                mainHandler.post { onStatusChanged("⚡ Processando áudio da TV...") }
                                val wavBytes = pcmToWav(pcmBytes)

                                // Envia o bloco para transcrição neural sem travar a escuta ininterrupta
                                launch(Dispatchers.IO) {
                                    try {
                                        val transcript = miniMaxClient.transcribeAudioChunk(wavBytes)
                                        if (!transcript.isNullOrBlank() && transcript.trim().length >= 6) {
                                            val cleanText = transcript.trim()
                                            Log.i("AudioStream", "FALA RECONHECIDA NO STREAM DA TV: \"$cleanText\"")
                                            mainHandler.post {
                                                val preview = if (cleanText.length > 24) cleanText.take(24) + "..." else cleanText
                                                onStatusChanged("⚡ Checando: \"$preview\"")
                                            }
                                            onTranscriptRecognized(cleanText)
                                        } else {
                                            mainHandler.post { onStatusChanged("🎙️ Ouvindo TV...") }
                                        }
                                    } catch (e: Exception) {
                                        Log.w("AudioStream", "Erro ao transcrever trecho: ${e.message}")
                                        mainHandler.post { onStatusChanged("🎙️ Ouvindo TV...") }
                                    }
                                }
                            } else {
                                mainHandler.post { onStatusChanged("🎙️ Ouvindo TV...") }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioStream", "Erro fatal no loop de streaming de áudio: ${e.message}", e)
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
        streamJob?.cancel()
        streamJob = null
        releaseAudioRecord()
        mainHandler.post { onStatusChanged("Monitoramento inativo") }
    }
}
