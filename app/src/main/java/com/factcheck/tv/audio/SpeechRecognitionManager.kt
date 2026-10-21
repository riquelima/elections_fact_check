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
 * Continuous speech-to-text listener using Android TV's speech engine in Portuguese (pt-BR).
 * Provides live telemetry to overlay pill: hearing audio, partial words, analysis.
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
                Log.w("SpeechRecognizer", "Reconhecimento de voz nativo do Android não encontrado neste aparelho")
                onStatusChanged("⚡ Radar Automático Ativo")
                return@post
            }

            initRecognizer()
            beginListeningLoop()
        }
    }

    private fun initRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    onStatusChanged("🎙️ Ouvindo TV...")
                }

                override fun onBeginningOfSpeech() {
                    onStatusChanged("🎙️ Captando áudio...")
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        // Silêncio temporário ou pausa do apresentador da TV: ciclo normal
                        onStatusChanged("🎙️ Ouvindo TV...")
                        restartListeningLoop(delayMs = 400)
                    } else {
                        Log.w("SpeechRecognizer", "STT Evento código $error. Reconectando...")
                        onStatusChanged("🎙️ Monitorando TV...")
                        restartListeningLoop(delayMs = 1500)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0].trim()
                        if (text.length >= 8) {
                            val preview = if (text.length > 22) text.take(22) + "..." else text
                            onStatusChanged("⚡ Checando: \"$preview\"")
                            Log.i("SpeechRecognizer", "Fala reconhecida na TV: \"$text\"")
                            onTranscriptRecognized(text)
                        }
                    }
                    restartListeningLoop(delayMs = 300)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val partial = matches[0].trim()
                        if (partial.isNotBlank()) {
                            val preview = if (partial.length > 20) partial.take(20) + "..." else partial
                            onStatusChanged("🎙️ \"$preview\"")
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun beginListeningLoop() {
        if (!isListening) return

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("SpeechRecognizer", "Erro ao iniciar escuta: ${e.message}")
            restartListeningLoop(delayMs = 2000)
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
