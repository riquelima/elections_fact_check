package com.factcheck.tv.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Escuta contínua de reconhecimento de voz em português (pt-BR) para transmissões de TV e debates.
 * Transmite telemetria ao vivo para a pílula flutuante no canto da tela.
 */
class SpeechRecognitionManager(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onTranscriptRecognized: (String) -> Unit
) {

    private val appContext = context.applicationContext
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())

    var isEngineAvailable = false
        private set

    fun startListening() {
        mainHandler.post {
            if (isListening) return@post
            isListening = true

            isEngineAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)
            if (!isEngineAvailable) {
                Log.w("SpeechRecognizer", "Reconhecimento de voz não encontrado pelo sistema do aparelho")
                onStatusChanged("🎙️ Aguardando áudio da TV...")
            }

            initRecognizer()
            beginListeningLoop()
        }
    }

    private fun initRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e("SpeechRecognizer", "Erro ao limpar recognizer: ${e.message}")
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        onStatusChanged("🎙️ Ouvindo TV...")
                    }

                    override fun onBeginningOfSpeech() {
                        onStatusChanged("🎙️ Captando áudio...")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        // Se houver volume alto do apresentador
                        if (rmsdB > 5.0f) {
                            Log.d("SpeechRecognizer", "Volume da TV: $rmsdB dB")
                        }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                // Pausa do apresentador: reinicia escuta rapidamente
                                onStatusChanged("🎙️ Ouvindo TV...")
                                restartListeningLoop(delayMs = 250)
                            }
                            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                                Log.w("SpeechRecognizer", "Código STT: $error (recriando recognizer...)")
                                onStatusChanged("🎙️ Sincronizando áudio...")
                                mainHandler.postDelayed({
                                    if (isListening) {
                                        initRecognizer()
                                        beginListeningLoop()
                                    }
                                }, 1500)
                            }
                            else -> {
                                Log.w("SpeechRecognizer", "STT Evento código $error. Reiniciando ciclo...")
                                onStatusChanged("🎙️ Ouvindo TV...")
                                restartListeningLoop(delayMs = 1200)
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val text = matches[0].trim()
                            if (text.length >= 6) {
                                val preview = if (text.length > 24) text.take(24) + "..." else text
                                onStatusChanged("⚡ Checando: \"$preview\"")
                                Log.i("SpeechRecognizer", "FALA RECONHECIDA NA TV: \"$text\"")
                                onTranscriptRecognized(text)
                            }
                        }
                        restartListeningLoop(delayMs = 200)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val partial = matches[0].trim()
                            if (partial.isNotBlank()) {
                                val preview = if (partial.length > 22) partial.take(22) + "..." else partial
                                onStatusChanged("🎙️ \"$preview\"")
                            }
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            Log.e("SpeechRecognizer", "Falha ao instanciar SpeechRecognizer: ${e.message}")
            onStatusChanged("🎙️ Ouvindo TV...")
        }
    }

    private fun beginListeningLoop() {
        if (!isListening) return

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("SpeechRecognizer", "Erro ao iniciar escuta: ${e.message}")
            restartListeningLoop(delayMs = 1500)
        }
    }

    private fun restartListeningLoop(delayMs: Long) {
        if (!isListening) return
        mainHandler.postDelayed({
            beginListeningLoop()
        }, delayMs)
    }

    fun stopListening() {
        isListening = false
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.e("SpeechRecognizer", "Erro ao parar speechRecognizer: ${e.message}")
            }
            speechRecognizer = null
        }
    }
}
