package com.factcheck.tv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.factcheck.tv.MainActivity
import com.factcheck.tv.R
import com.factcheck.tv.api.FactCheckResult
import com.factcheck.tv.api.MiniMaxClient
import com.factcheck.tv.audio.AudioCaptureManager
import com.factcheck.tv.overlay.FactCheckOverlayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FactCheckForegroundService : LifecycleService() {

    private lateinit var overlayManager: FactCheckOverlayManager
    private lateinit var miniMaxClient: MiniMaxClient
    private var audioCaptureManager: AudioCaptureManager? = null

    companion object {
        const val CHANNEL_ID = "tv_fact_check_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.factcheck.tv.START"
        const val ACTION_STOP = "com.factcheck.tv.STOP"
        const val ACTION_TRIGGER_TEST = "com.factcheck.tv.TRIGGER_TEST"

        const val EXTRA_API_KEY = "extra_api_key"

        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        overlayManager = FactCheckOverlayManager(this)
        miniMaxClient = MiniMaxClient("")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                val apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""
                miniMaxClient.updateApiKey(apiKey)
                startForegroundMonitoring()
            }
            ACTION_STOP -> {
                stopForegroundMonitoring()
            }
            ACTION_TRIGGER_TEST -> {
                showMockFactCheck()
            }
        }

        return START_STICKY
    }

    private var speechRecognitionManager: com.factcheck.tv.audio.SpeechRecognitionManager? = null

    private fun startForegroundMonitoring() {
        if (isRunning) return

        val notification = buildPersistentNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true
        Log.i("FactCheckService", "Monitoramento em segundo plano iniciado na TV")

        // Inicializa reconhecimento de voz nativo contínuo em português (pt-BR)
        speechRecognitionManager = com.factcheck.tv.audio.SpeechRecognitionManager(this) { spokenText ->
            processLiveTranscript(spokenText)
        }
        speechRecognitionManager?.startListening()
    }

    private fun processLiveTranscript(spokenText: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i("FactCheckService", "Analisando com MiniMax: \"$spokenText\"")
                val result = miniMaxClient.checkTranscript(spokenText)
                if (result != null && result.hasClaim) {
                    val verdictUpper = result.verdict.uppercase()
                    if (verdictUpper == "FALSO" || verdictUpper == "ENGANOSO" || verdictUpper.contains("FALS") || verdictUpper.contains("ENGAN")) {
                        Log.w("FactCheckService", "ALEGAÇÃO IDENTIFICADA COMO ${result.verdict}! Exibindo alerta na TV...")
                        overlayManager.showFactCheckAlert(result)
                    }
                }
            } catch (e: Exception) {
                Log.e("FactCheckService", "Erro ao processar transcrição: ${e.message}")
            }
        }
    }

    private fun showMockFactCheck() {
        val mockResult = FactCheckResult(
            hasClaim = true,
            verdict = "FALSO",
            claimText = "O desemprego no país atingiu 0% no último semestre após nova medida.",
            verifiedFact = "Dados consolidados da PNAD Contínua / IBGE registraram taxa real de desocupação de 6,8% no período.",
            source = "IBGE / Pesquisa Nacional por Amostra de Domicílios",
            confidence = 0.98f
        )
        overlayManager.showFactCheckAlert(mockResult)
    }

    private fun stopForegroundMonitoring() {
        isRunning = false
        speechRecognitionManager?.stopListening()
        speechRecognitionManager = null
        audioCaptureManager?.stopCapture()
        audioCaptureManager = null
        overlayManager.hideAlert()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i("FactCheckService", "Monitoramento parado.")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_desc)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildPersistentNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_desc))
            .setSmallIcon(R.drawable.ic_factcheck_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForegroundMonitoring()
    }
}
