package com.factcheck.tv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.factcheck.tv.MainActivity
import com.factcheck.tv.R
import com.factcheck.tv.api.FactCheckResult
import com.factcheck.tv.api.MiniMaxClient
import com.factcheck.tv.audio.SpeechRecognitionManager
import com.factcheck.tv.audio.StreamingAudioRecordManager
import com.factcheck.tv.overlay.FactCheckOverlayManager
import com.factcheck.tv.sources.FactCheckSourcesManager
import com.factcheck.tv.sources.MancheteScannerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FactCheckForegroundService : LifecycleService() {

    private lateinit var overlayManager: FactCheckOverlayManager
    private lateinit var miniMaxClient: MiniMaxClient
    private lateinit var sourcesManager: FactCheckSourcesManager
    private lateinit var mancheteScanner: MancheteScannerManager
    private var streamingAudioManager: StreamingAudioRecordManager? = null
    private var speechRecognitionManager: SpeechRecognitionManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var headlineJob: kotlinx.coroutines.Job? = null

    companion object {
        const val CHANNEL_ID = "tv_fact_check_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.factcheck.tv.START"
        const val ACTION_STOP = "com.factcheck.tv.STOP"
        const val ACTION_TRIGGER_TEST = "com.factcheck.tv.TRIGGER_TEST"
        const val ACTION_TRIGGER_POLITICAL_TEST = "com.factcheck.tv.TRIGGER_POLITICAL_TEST"
        const val ACTION_CAPTURE_MANCHETE = "com.factcheck.tv.CAPTURE_MANCHETE"

        const val EXTRA_API_KEY = "extra_api_key"

        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        overlayManager = FactCheckOverlayManager(this)
        miniMaxClient = MiniMaxClient()
        sourcesManager = FactCheckSourcesManager()
        mancheteScanner = MancheteScannerManager()
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
            ACTION_TRIGGER_POLITICAL_TEST -> {
                showPoliticalDebateMock()
            }
            ACTION_CAPTURE_MANCHETE -> {
                captureAndCheckManchete()
            }
        }

        return START_STICKY
    }

    private fun captureAndCheckManchete() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                overlayManager.updateAmbientStatus("📺 Lendo Manchete da TV...")
                val headline = mancheteScanner.getTopBreakingHeadline()
                if (!headline.isNullOrBlank()) {
                    Log.i("FactCheckService", "MANCHETE CAPTURADA DA TV: \"$headline\"")
                    overlayManager.updateAmbientStatus("⚡ Checando: \"${headline.take(22)}...\"")
                    processLiveTranscript("MANCHETE EXIBIDA NA TELA DA TV: $headline")
                } else {
                    overlayManager.updateAmbientStatus("⚠️ Nenhuma manchete no momento")
                    delay(3000)
                    overlayManager.updateAmbientStatus("🎙️ Ouvindo TV...")
                }
            } catch (e: Exception) {
                Log.e("FactCheckService", "Erro ao capturar manchete: ${e.message}")
                overlayManager.updateAmbientStatus("🎙️ Ouvindo TV...")
            }
        }
    }

    private fun startForegroundMonitoring() {
        if (isRunning) return

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVFactChecker::WakeLock").apply {
                acquire(24 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e("FactCheckService", "Erro ao obter WakeLock: ${e.message}")
        }

        val notification = buildPersistentNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true
        Log.i("FactCheckService", "Monitoramento em segundo plano iniciado na TV")

        // Exibe a pílula de monitoramento no canto da tela (sobreposta na TV aberta / uniTV)
        overlayManager.showAmbientPill()

        // Pré-carrega checagens de agências oficiais (Aos Fatos, Lupa, etc.) em segundo plano
        lifecycleScope.launch(Dispatchers.IO) {
            sourcesManager.refreshSourcesIfStale()
        }

        // Monitoramento automático das MANCHETES e TARJAS da TV ao vivo em segundo plano (GloboNews / G1)
        headlineJob?.cancel()
        headlineJob = lifecycleScope.launch(Dispatchers.IO) {
            val seenHeadlines = mutableSetOf<String>()
            delay(12_000L) // Aguarda inicialização do app

            while (isRunning) {
                try {
                    val headline = mancheteScanner.getTopBreakingHeadline()
                    if (!headline.isNullOrBlank() && !seenHeadlines.contains(headline)) {
                        seenHeadlines.add(headline)
                        if (seenHeadlines.size > 50) {
                            seenHeadlines.clear()
                            seenHeadlines.add(headline)
                        }
                        Log.i("FactCheckService", "MANCHETE CAPTURADA DA TV EM TEMPO REAL: \"$headline\"")
                        processLiveTranscript("MANCHETE EXIBIDA NA TELA DA TV: $headline")
                    }
                } catch (e: Exception) {
                    Log.w("FactCheckService", "Erro na captura periódica de manchete: ${e.message}")
                }
                delay(35_000L)
            }
        }

        // 1. Inicializa o Motor de Streaming de Áudio Contínuo (PCM 16kHz ininterrupto via AudioRecord)
        streamingAudioManager = StreamingAudioRecordManager(
            context = this,
            miniMaxClient = miniMaxClient,
            onStatusChanged = { statusText ->
                overlayManager.updateAmbientStatus(statusText)
            },
            onTranscriptRecognized = { spokenText ->
                processLiveTranscript(spokenText)
            }
        )
        streamingAudioManager?.startStreaming()
    }

    /**
     * Processa estritamente o áudio ouvido da TV em tempo real.
     * Cruza com as agências de checagem (Aos Fatos, Agência Lupa, G1 Fato ou Fake, TSE, IBGE) e exibe o veredito.
     */
    private fun processLiveTranscript(spokenText: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i("FactCheckService", "ÁUDIO OUVIDO NA TV: \"$spokenText\"")
                overlayManager.updateAmbientStatus("⚡ Checando: \"${spokenText.take(20)}...\"")

                // 1. Busca evidências nas maiores agências de fact-checking do Brasil (Aos Fatos, Agência Lupa)
                val evidence = sourcesManager.findRelevantEvidence(spokenText)

                // 2. Envia para o motor neural confrontar a declaração com as agências e bases oficiais
                val result = miniMaxClient.checkTranscript(spokenText, evidence)
                if (result != null && result.hasClaim) {
                    Log.i("FactCheckService", "VEREDITO: ${result.verdict} | Fonte: ${result.source}")
                    overlayManager.showFactCheckAlert(result)
                } else {
                    overlayManager.updateAmbientStatus("✓ Sem alegações falsas")
                    delay(3000)
                    overlayManager.updateAmbientStatus("🎙️ Ouvindo TV...")
                }
            } catch (e: Exception) {
                Log.e("FactCheckService", "Erro ao processar transcrição: ${e.message}")
                overlayManager.updateAmbientStatus("🎙️ Ouvindo TV...")
            }
        }
    }

    private fun showPoliticalDebateMock() {
        val debateResult = FactCheckResult(
            hasClaim = true,
            verdict = "FALSO",
            claimText = "O país atingiu o recorde histórico de falências de empresas no último ano.",
            verifiedFact = "Dados do Serasa Experian apontam que o pico histórico de falências ocorreu na recessão de 2016. Os registros recentes tiveram redução.",
            source = "Aos Fatos / Agência Lupa / Serasa Experian",
            confidence = 0.98f
        )
        overlayManager.showFactCheckAlert(debateResult)
    }

    private fun showMockFactCheck() {
        val mockResult = FactCheckResult(
            hasClaim = true,
            verdict = "VERDADEIRO",
            claimText = "A inflação acumulada nos últimos 12 meses fechou em 4,42%, segundo o IBGE.",
            verifiedFact = "Dados oficiais do Índice de Preços ao Consumidor Amplo (IPCA/IBGE) confirmam a taxa apurada para o período.",
            source = "IBGE / G1 Fato ou Fake",
            confidence = 0.99f
        )
        overlayManager.showFactCheckAlert(mockResult)
    }

    private fun stopForegroundMonitoring() {
        isRunning = false

        headlineJob?.cancel()
        headlineJob = null

        streamingAudioManager?.stopStreaming()
        streamingAudioManager = null

        speechRecognitionManager?.stopListening()
        speechRecognitionManager = null

        overlayManager.hideAll()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e("FactCheckService", "Erro ao liberar WakeLock: ${e.message}")
        }
        wakeLock = null

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
