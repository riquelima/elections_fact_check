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
import com.factcheck.tv.overlay.FactCheckOverlayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FactCheckForegroundService : LifecycleService() {

    private lateinit var overlayManager: FactCheckOverlayManager
    private lateinit var miniMaxClient: MiniMaxClient
    private var speechRecognitionManager: SpeechRecognitionManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var liveRadarJob: Job? = null
    private var lastSpeechTimestamp = 0L

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

        // Exibe imediatamente a pílula de monitoramento no canto da tela (sobreposta na TV aberta / uniTV)
        overlayManager.showAmbientPill()

        // Inicializa reconhecimento de voz contínuo em português (pt-BR)
        speechRecognitionManager = SpeechRecognitionManager(
            context = this,
            onStatusChanged = { statusText ->
                overlayManager.updateAmbientStatus(statusText)
            },
            onTranscriptRecognized = { spokenText ->
                lastSpeechTimestamp = System.currentTimeMillis()
                processLiveTranscript(spokenText)
            }
        )
        speechRecognitionManager?.startListening()

        // Inicia o Radar Contínuo de Notícias ao Vivo (garante checagens ativas mesmo com mic longe ou mudo)
        startLiveNewsRadar()
    }

    private fun startLiveNewsRadar() {
        liveRadarJob?.cancel()
        liveRadarJob = lifecycleScope.launch(Dispatchers.IO) {
            // Primeiro disparo de radar após 20 segundos se o microfone ainda não tiver captado nada
            delay(20000)
            while (isActive && isRunning) {
                val silenceDuration = System.currentTimeMillis() - lastSpeechTimestamp
                // Se não houve fala reconhecida nos últimos 30 segundos (ex: TV Box sem microfone interno ou TV no mudo)
                if (silenceDuration > 30000) {
                    try {
                        Log.i("FactCheckService", "Disparando Radar de Notícias ao Vivo...")
                        overlayManager.updateAmbientStatus("⚡ Radar: Checando Notícia...")
                        val radarResult = miniMaxClient.fetchLiveNewsClaim()
                        if (radarResult != null && radarResult.hasClaim) {
                            Log.i("FactCheckService", "Radar identificou fato verificado: ${radarResult.verdict} - ${radarResult.claimText}")
                            overlayManager.showFactCheckAlert(radarResult)
                        } else {
                            overlayManager.updateAmbientStatus("🎙️ Ouvindo TV...")
                        }
                    } catch (e: Exception) {
                        Log.e("FactCheckService", "Erro no radar de notícias: ${e.message}")
                        overlayManager.updateAmbientStatus("🎙️ Ouvindo TV...")
                    }
                }
                delay(45000) // Próxima checagem de radar em 45 segundos
            }
        }
    }

    private fun processLiveTranscript(spokenText: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i("FactCheckService", "Analisando áudio da TV: \"$spokenText\"")
                overlayManager.updateAmbientStatus("⚡ Analisando Fato...")
                val result = miniMaxClient.checkTranscript(spokenText)
                if (result != null && result.hasClaim) {
                    Log.i("FactCheckService", "FATO VERIFICADO: ${result.verdict} -> \"${result.claimText}\"")
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
        liveRadarJob?.cancel()
        liveRadarJob = null

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
