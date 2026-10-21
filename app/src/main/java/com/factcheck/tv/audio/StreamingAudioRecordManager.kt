package com.factcheck.tv.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
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
 * Motor de Áudio em Tempo Real para TV (TV aberta, uniTV e IPTV).
 * Captura o áudio real da transmissão do canal atual em ciclos ágeis de 16 segundos,
 * aplicando cancelamento de eco acústico e supressão de ruído da sala.
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

    @Volatile
    var isAnalyzing = false

    private var mediaProjection: MediaProjection? = null

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CYCLE_MS = 16_000L // Ciclo de 16 segundos para resposta ágil ao trocar de canal

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

    fun setMediaProjection(mp: MediaProjection?) {
        this.mediaProjection = mp
    }

    @SuppressLint("MissingPermission")
    fun startStreaming() {
        if (isStreaming) return
        isStreaming = true
        isAnalyzing = false

        mainHandler.post { onStatusChanged("Escutando áudio") }

        streamJob = scope.launch {
            try {
                audioRecord = createOptimalAudioRecord()

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("AudioStream", "AudioRecord não pôde ser inicializado pelo hardware")
                    mainHandler.post { onStatusChanged("Escutando áudio") }
                    return@launch
                }

                audioRecord?.startRecording()
                Log.i("AudioStream", "Motor de Escuta em Tempo Real (Ciclos de 16s) INICIADO.")

                val readBuffer = ByteArray(2048)
                val cycleAccumulator = ByteArrayOutputStream()
                var cycleStartTime = System.currentTimeMillis()
                var activeSpeechCount = 0

                while (isStreaming && isActive) {
                    val bytesRead = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: -1
                    if (bytesRead > 0) {
                        val rms = calculateRms(readBuffer, bytesRead)
                        if (rms > 18.0) {
                            activeSpeechCount++
                        }
                        cycleAccumulator.write(readBuffer, 0, bytesRead)

                        val elapsedMs = System.currentTimeMillis() - cycleStartTime

                        // Ao atingir 16 segundos de áudio do canal atual
                        if (elapsedMs >= CYCLE_MS) {
                            val pcmBytes = cycleAccumulator.toByteArray()
                            cycleAccumulator.reset()
                            cycleStartTime = System.currentTimeMillis()
                            val hadSpeech = activeSpeechCount >= 3
                            activeSpeechCount = 0

                            if (hadSpeech && pcmBytes.isNotEmpty()) {
                                launch(Dispatchers.IO) {
                                    try {
                                        mainHandler.post {
                                            if (!isAnalyzing) onStatusChanged("Processando")
                                        }
                                        val wavBytes = pcmToWav(pcmBytes)
                                        val transcript = miniMaxClient.transcribeAudioChunk(wavBytes)
                                        val cleanText = transcript?.trim() ?: ""

                                        Log.i("AudioStream", "ÁUDIO RECONHECIDO NO CANAL ATUAL: \"$cleanText\"")
                                        onTranscriptRecognized(cleanText)
                                    } catch (e: Exception) {
                                        Log.w("AudioStream", "Erro ao transcrever trecho do canal: ${e.message}")
                                        // Mesmo se a transcrição do áudio falhar ou for curta, aciona para ler a tela
                                        onTranscriptRecognized("")
                                    }
                                }
                            } else {
                                // Canal com música, comercial ou fala baixa: aciona checagem visual das tarjas da tela
                                launch(Dispatchers.IO) {
                                    onTranscriptRecognized("")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioStream", "Erro no loop de streaming de áudio: ${e.message}", e)
                mainHandler.post { onStatusChanged("Escutando áudio") }
            } finally {
                releaseAudioRecord()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createOptimalAudioRecord(): AudioRecord? {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = Math.max(minBufferSize * 2, 8192)

        // 1. Tenta captura direta de áudio interno digital do sistema (Android 10+ via MediaProjection)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            try {
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()

                val directRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                if (directRecord.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i("AudioStream", "✓ Captura de áudio digital interno do sistema ativada (IPTV/uniTV/apps)!")
                    return directRecord
                } else {
                    directRecord.release()
                }
            } catch (e: Exception) {
                Log.w("AudioStream", "AudioPlaybackCapture não aceito nesta firmware, usando microfone otimizado: ${e.message}")
            }
        }

        // 2. Fallback: Microfone com VOICE_RECOGNITION + Cancelador de Eco e Supressor de Ruído
        try {
            val micRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (micRecord.state == AudioRecord.STATE_INITIALIZED) {
                applyHardwareAudioEffects(micRecord.audioSessionId)
                Log.i("AudioStream", "✓ Microfone com VOICE_RECOGNITION + Cancelador de Eco ativado.")
                return micRecord
            } else {
                micRecord.release()
            }
        } catch (e: Exception) {
            Log.w("AudioStream", "Falha com VOICE_RECOGNITION: ${e.message}")
        }

        // 3. Fallback genérico: MIC padrão
        return try {
            val defaultRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (defaultRecord.state == AudioRecord.STATE_INITIALIZED) {
                applyHardwareAudioEffects(defaultRecord.audioSessionId)
                defaultRecord
            } else {
                defaultRecord.release()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun applyHardwareAudioEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = true
                    Log.i("AudioStream", "✓ AcousticEchoCanceler ativado para suprimir eco da TV.")
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.apply {
                    enabled = true
                    Log.i("AudioStream", "✓ NoiseSuppressor ativado para filtrar ruído ambiente.")
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.apply {
                    enabled = true
                }
            }
        } catch (e: Exception) {
            Log.w("AudioStream", "Efeitos de áudio de hardware não suportados: ${e.message}")
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
        streamJob?.cancel()
        streamJob = null
        releaseAudioRecord()
        mainHandler.post { onStatusChanged("Monitoramento inativo") }
    }
}
