package com.factcheck.tv

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.factcheck.tv.databinding.ActivityMainBinding
import com.factcheck.tv.service.FactCheckForegroundService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        // Chave oficial embutida de forma oculta no APK
        const val EMBEDDED_MINIMAX_KEY = "sk-cp-meaN0PHZdGi3-5gZffia9b6PyDIh27vyk54LwG6gw965dFLWoIHowFo19rTqoHdbxhaQezJlMMBgTEYhNni51sJnMWCcPHIKtCg4GRY-pGMmrXarNIxxGQA"
    }

    private val pendingMediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resultCode = result.resultCode
        val data = result.data
        if (resultCode == RESULT_OK && data != null) {
            startMonitoringService(resultCode, data)
        } else {
            startMonitoringService(null, null)
        }
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            requestScreenCapturePermission()
        } else {
            Toast.makeText(this, "Permissão de microfone necessária para ouvir a TV", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTvFocusAnimations()
        setupListeners()
        updateServiceUI()

        // Garante que o primeiro botão já comece focado para navegação imediata do controle remoto
        binding.btnToggleService.post {
            binding.btnToggleService.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceUI()
    }

    /**
     * Adiciona animação de zoom e destaque imediato para o controle remoto da Android TV.
     */
    private fun setupTvFocusAnimations() {
        val tvFocusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(140).start()
                view.elevation = 20f
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140).start()
                view.elevation = 2f
            }
        }

        binding.btnToggleService.onFocusChangeListener = tvFocusListener
        binding.btnTestOverlay.onFocusChangeListener = tvFocusListener
        binding.btnTestPolitical.onFocusChangeListener = tvFocusListener
        binding.btnCaptureManchete.onFocusChangeListener = tvFocusListener
        binding.btnGrantOverlay.onFocusChangeListener = tvFocusListener
    }

    private fun setupListeners() {
        binding.btnToggleService.setOnClickListener {
            if (FactCheckForegroundService.isRunning) {
                stopMonitoringService()
            } else {
                checkPermissionsAndStart()
            }
        }

        binding.btnTestOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
                return@setOnClickListener
            }

            // Dispara teste visual de notificação flutuante na TV
            val intent = Intent(this, FactCheckForegroundService::class.java).apply {
                action = FactCheckForegroundService.ACTION_TRIGGER_TEST
            }
            startService(intent)
            Toast.makeText(this, "Exibindo card de simulação na TV...", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestPolitical.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
                return@setOnClickListener
            }

            // Dispara teste de debate político (Lula x Flávio Bolsonaro)
            val intent = Intent(this, FactCheckForegroundService::class.java).apply {
                action = FactCheckForegroundService.ACTION_TRIGGER_POLITICAL_TEST
            }
            startService(intent)
            Toast.makeText(this, "Exibindo checagem de debate político na TV...", Toast.LENGTH_SHORT).show()
        }

        binding.btnCaptureManchete.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
                return@setOnClickListener
            }

            // Dispara leitura e checagem da manchete da TV
            val intent = Intent(this, FactCheckForegroundService::class.java).apply {
                action = FactCheckForegroundService.ACTION_CAPTURE_MANCHETE
            }
            startService(intent)
            Toast.makeText(this, "📺 Lendo e checando manchete da TV...", Toast.LENGTH_SHORT).show()
        }

        binding.btnGrantOverlay.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "✓ Permissão de sobreposição já concedida e ativa!", Toast.LENGTH_SHORT).show()
            } else {
                requestOverlayPermission()
            }
        }
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Conceda permissão de sobreposição para exibir alertas sobre a TV aberta e uniTV", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestScreenCapturePermission()
        }
    }

    private fun requestScreenCapturePermission() {
        try {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            pendingMediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            startMonitoringService(null, null)
        }
    }

    private fun startMonitoringService(projectionResultCode: Int? = null, projectionData: Intent? = null) {
        val intent = Intent(this, FactCheckForegroundService::class.java).apply {
            action = FactCheckForegroundService.ACTION_START
            putExtra(FactCheckForegroundService.EXTRA_API_KEY, EMBEDDED_MINIMAX_KEY)
            if (projectionResultCode != null && projectionData != null) {
                putExtra(FactCheckForegroundService.EXTRA_PROJECTION_RESULT_CODE, projectionResultCode)
                putExtra(FactCheckForegroundService.EXTRA_PROJECTION_DATA, projectionData)
            }
        }
        startForegroundService(intent)
        binding.root.postDelayed({ updateServiceUI() }, 300)
    }

    private fun stopMonitoringService() {
        val intent = Intent(this, FactCheckForegroundService::class.java).apply {
            action = FactCheckForegroundService.ACTION_STOP
        }
        startService(intent)
        binding.root.postDelayed({ updateServiceUI() }, 300)
    }

    private fun updateServiceUI() {
        val isRunning = FactCheckForegroundService.isRunning
        if (isRunning) {
            binding.tvStatus.text = getString(R.string.status_service_running)
            binding.tvStatus.setTextColor(Color.parseColor("#10B981"))
            binding.btnToggleService.text = getString(R.string.btn_stop_service)
        } else {
            binding.tvStatus.text = getString(R.string.status_service_stopped)
            binding.tvStatus.setTextColor(Color.parseColor("#EF4444"))
            binding.btnToggleService.text = getString(R.string.btn_start_service)
        }

        if (Settings.canDrawOverlays(this)) {
            binding.btnGrantOverlay.text = "✓ Permissão de Sobreposição Ativa"
            binding.btnGrantOverlay.isEnabled = true
            binding.btnGrantOverlay.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.btnGrantOverlay.text = getString(R.string.btn_permission_overlay)
            binding.btnGrantOverlay.isEnabled = true
            binding.btnGrantOverlay.setTextColor(Color.parseColor("#FFFFFF"))
        }
    }

    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            startActivity(intent)
        }
    }
}
