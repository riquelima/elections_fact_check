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
 * Continuous speech-to-text listener using Android TV's native speech engine in Portuguese (pt-BR).
 */
class SpeechRecognitionManager(
    private val context: Context,
    private val onTranscriptRecognized: (String) -> Unit
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun startListening() {
        mainHandler.post {
            if (isListening) return@post
            isListening = true

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e("SpeechRecognizer", "Reconhecimento de voz do Android não disponível neste dispositivo")
                return@post
            }

            initRecognizer()
            beginListeningLoop()
        }
    }

    private fun initRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d("SpeechRecognizer", "Ouvindo TV...")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    Log.d("SpeechRecognizer", "Código de evento STT: $error (reiniciando ciclo)")
                    restartListeningLoop(delayMs = 1000)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0].trim()
                        if (text.length >= 15) { // Ignora fragmentos curtos/ruído
                            Log.i("SpeechRecognizer", "Fala detectada na TV: \"$text\"")
                            onTranscriptRecognized(text)
                        }
                    }
                    restartListeningLoop(delayMs = 400)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
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
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
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
