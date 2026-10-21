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
            updateServiceUI()
            Toast.makeText(this, "Permissão de microfone concedida com sucesso!", Toast.LENGTH_SHORT).show()
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

        // Dá foco automático ao botão de conceder permissões (se faltar alguma) ou ao botão de iniciar
        binding.root.post {
            if (!hasAllPermissions()) {
                binding.btnGrantPermissions.requestFocus()
            } else {
                binding.btnToggleService.requestFocus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceUI()
    }

    private fun hasAllPermissions(): Boolean {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return hasOverlay && hasAudio
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

        binding.btnGrantPermissions.onFocusChangeListener = tvFocusListener
        binding.btnToggleService.onFocusChangeListener = tvFocusListener
    }

    private fun setupListeners() {
        // Botão 1: Conceder todas as permissões da TV (Sobreposição e Áudio)
        binding.btnGrantPermissions.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Ative a permissão de sobreposição para exibir alertas sobre a TV", Toast.LENGTH_LONG).show()
                requestOverlayPermission()
                return@setOnClickListener
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return@setOnClickListener
            }

            Toast.makeText(this, "Todas as permissões da TV já estão ativas e concedidas!", Toast.LENGTH_SHORT).show()
            updateServiceUI()
        }

        // Botão 2: Iniciar ou Parar Monitoramento em Segundo Plano
        binding.btnToggleService.setOnClickListener {
            if (FactCheckForegroundService.isRunning) {
                stopMonitoringService()
            } else {
                checkPermissionsAndStart()
            }
        }
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Conceda permissão de sobreposição para exibir alertas sobre a TV", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        requestScreenCapturePermission()
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

        if (hasAllPermissions()) {
            binding.btnGrantPermissions.text = "✓ Permissões da TV Concedidas"
            binding.btnGrantPermissions.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.btnGrantPermissions.text = "1. Conceder Permissões da TV"
            binding.btnGrantPermissions.setTextColor(Color.parseColor("#FFFFFF"))
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
