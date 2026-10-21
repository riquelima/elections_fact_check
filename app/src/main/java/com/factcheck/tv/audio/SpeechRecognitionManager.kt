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
    private var speechDurationRunnable: Runnable? = null
    private var resultsWatchdogRunnable: Runnable? = null
    private var cycleLimitRunnable: Runnable? = null

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
        cancelAllTimers()
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
                        // Cancela o watchdog genérico e inicia a contagem exata da fala da TV (4.5 segundos)
                        cycleLimitRunnable?.let { mainHandler.removeCallbacks(it) }
                        cycleLimitRunnable = null

                        speechDurationRunnable?.let { mainHandler.removeCallbacks(it) }
                        speechDurationRunnable = Runnable {
                            if (!isListening) return@Runnable
                            Log.i("SpeechRecognizer", "Janela de 4.5s de fala da TV atingida. Forçando stopListening()...")
                            forceStopAndFlush()
                        }
                        mainHandler.postDelayed(speechDurationRunnable!!, 4500L)
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        cancelAllTimers()
                        val pendingText = accumulatedSpeech.trim()
                        accumulatedSpeech = ""

                        if (pendingText.length >= 6) {
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
                                }, 800)
                            }
                            else -> {
                                Log.w("SpeechRecognizer", "STT Evento código $error. Reiniciando ciclo...")
                                onStatusChanged("🎙️ Ouvindo TV...")
                                restartListeningLoop(delayMs = 400)
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        cancelAllTimers()
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val recognizedText = matches?.firstOrNull()?.trim() ?: ""
                        val text = if (recognizedText.isNotBlank()) recognizedText else accumulatedSpeech.trim()
                        accumulatedSpeech = ""

                        if (text.length >= 6) {
                            val preview = if (text.length > 24) text.take(24) + "..." else text
                            onStatusChanged("⚡ Checando: \"$preview\"")
                            Log.i("SpeechRecognizer", "FALA RECONHECIDA NA TV (onResults): \"$text\"")
                            onTranscriptRecognized(text)
                        } else {
                            onStatusChanged("🎙️ Ouvindo TV...")
                        }
                        restartListeningLoop(delayMs = 200)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim() ?: ""
                        if (partial.isNotBlank()) {
                            accumulatedSpeech = partial
                            val preview = if (partial.length > 24) partial.take(24) + "..." else partial
                            onStatusChanged("🎙️ \"$preview\"")
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
     * Força o encerramento da gravação e comanda o motor a gerar onResults imediatamente
     * com o áudio capturado nos 4.5 segundos da transmissão.
     */
    private fun forceStopAndFlush() {
        cancelAllTimers()
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e("SpeechRecognizer", "Erro ao chamar stopListening: ${e.message}")
        }

        // Se o motor do sistema não responder em 3 segundos, destrava e reinicia
        resultsWatchdogRunnable = Runnable {
            if (!isListening) return@Runnable
            Log.w("SpeechRecognizer", "Watchdog: motor STT não emitiu onResults após 3s. Reiniciando ciclo...")
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {}
            onStatusChanged("🎙️ Ouvindo TV...")
            restartListeningLoop(delayMs = 200)
        }
        mainHandler.postDelayed(resultsWatchdogRunnable!!, 3000L)
    }

    private fun cancelAllTimers() {
        speechDurationRunnable?.let { mainHandler.removeCallbacks(it) }
        speechDurationRunnable = null

        resultsWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        resultsWatchdogRunnable = null

        cycleLimitRunnable?.let { mainHandler.removeCallbacks(it) }
        cycleLimitRunnable = null
    }

    private fun beginListeningLoop() {
        if (!isListening) return
        cancelAllTimers()

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
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
            speechRecognizer?.startListening(intent)

            // Watchdog de ciclo máximo de 7.5s para garantir que nunca fique preso sem responder
            cycleLimitRunnable = Runnable {
                if (!isListening) return@Runnable
                Log.d("SpeechRecognizer", "Watchdog de 7.5s atingido. Forçando finalização de trecho...")
                forceStopAndFlush()
            }
            mainHandler.postDelayed(cycleLimitRunnable!!, 7500L)

        } catch (e: Exception) {
            Log.e("SpeechRecognizer", "Erro ao iniciar escuta: ${e.message}")
            restartListeningLoop(delayMs = 1200)
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
        cancelAllTimers()
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
