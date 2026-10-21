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
 * Escuta contínua com Janela Deslizante (Rolling Flush) de 6.5 segundos para transmissões de TV ao vivo.
 * Não depende de silêncio para processar a fala de apresentadores que falam sem parar.
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

    private var accumulatedSpeech = ""
    private var speechTimeoutRunnable: Runnable? = null

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
        cancelSpeechTimer()
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
                        onStatusChanged("🎙️ Captando fala da TV...")
                        startSpeechFlushTimer()
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        cancelSpeechTimer()
                        val pendingText = accumulatedSpeech.trim()
                        accumulatedSpeech = ""

                        // Se já tínhamos capturado uma frase antes do evento de término, despacha imediatamente
                        if (pendingText.length >= 8) {
                            Log.i("SpeechRecognizer", "Despachando fala acumulada no encerramento: \"$pendingText\"")
                            val preview = if (pendingText.length > 24) pendingText.take(24) + "..." else pendingText
                            onStatusChanged("⚡ Checando: \"$preview\"")
                            onTranscriptRecognized(pendingText)
                        }

                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                onStatusChanged("🎙️ Ouvindo TV...")
                                restartListeningLoop(delayMs = 200)
                            }
                            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                                Log.w("SpeechRecognizer", "Código STT: $error (recriando recognizer...)")
                                onStatusChanged("🎙️ Sincronizando áudio...")
                                mainHandler.postDelayed({
                                    if (isListening) {
                                        initRecognizer()
                                        beginListeningLoop()
                                    }
                                }, 1200)
                            }
                            else -> {
                                Log.w("SpeechRecognizer", "STT Evento código $error. Reiniciando ciclo...")
                                onStatusChanged("🎙️ Ouvindo TV...")
                                restartListeningLoop(delayMs = 800)
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        cancelSpeechTimer()
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = if (!matches.isNullOrEmpty()) matches[0].trim() else accumulatedSpeech.trim()
                        accumulatedSpeech = ""

                        if (text.length >= 7) {
                            val preview = if (text.length > 24) text.take(24) + "..." else text
                            onStatusChanged("⚡ Checando: \"$preview\"")
                            Log.i("SpeechRecognizer", "FALA RECONHECIDA NA TV (onResults): \"$text\"")
                            onTranscriptRecognized(text)
                        }
                        restartListeningLoop(delayMs = 200)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val partial = matches[0].trim()
                            if (partial.isNotBlank()) {
                                accumulatedSpeech = partial
                                val preview = if (partial.length > 24) partial.take(24) + "..." else partial
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

    /**
     * Janela Deslizante de 6.5 segundos: em telejornais ao vivo (GloboNews), os apresentadores
     * falam sem fazer pausas de silêncio. Este timer garante que cada trecho de 6.5s de fala contínua
     * seja despachado para checagem imediatamente.
     */
    private fun startSpeechFlushTimer() {
        cancelSpeechTimer()
        speechTimeoutRunnable = Runnable {
            if (!isListening) return@Runnable

            val textToDispatch = accumulatedSpeech.trim()
            if (textToDispatch.length >= 8) {
                accumulatedSpeech = ""
                Log.i("SpeechRecognizer", "Janela de 6.5s atingida (fala contínua). Despachando: \"$textToDispatch\"")
                val preview = if (textToDispatch.length > 24) textToDispatch.take(24) + "..." else textToDispatch
                onStatusChanged("⚡ Checando: \"$preview\"")
                onTranscriptRecognized(textToDispatch)

                // Reinicia a escuta para capturar a frase seguinte
                try {
                    speechRecognizer?.stopListening()
                } catch (e: Exception) {
                    Log.e("SpeechRecognizer", "Erro ao parar ciclo: ${e.message}")
                }
                restartListeningLoop(delayMs = 250)
            } else {
                // Se ainda foi muito curto, aguarda mais um pouco
                startSpeechFlushTimer()
            }
        }
        mainHandler.postDelayed(speechTimeoutRunnable!!, 6500L)
    }

    private fun cancelSpeechTimer() {
        speechTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        speechTimeoutRunnable = null
    }

    private fun beginListeningLoop() {
        if (!isListening) return

        try {
            accumulatedSpeech = ""
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
        cancelSpeechTimer()
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
