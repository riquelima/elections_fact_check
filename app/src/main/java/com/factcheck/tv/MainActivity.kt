package com.factcheck.tv

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.factcheck.tv.databinding.ActivityMainBinding
import com.factcheck.tv.service.FactCheckForegroundService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val PREFS_NAME = "tv_fact_check_prefs"
    private val KEY_API_KEY = "minimax_api_key"

    // Default MiniMax Key from environment config
    private val DEFAULT_API_KEY = "sk-cp-meaN0PHZdGi3-5gZffia9b6PyDIh27vyk54LwG6gw965dFLWoIHowFo19rTqoHdbxhaQezJlMMBgTEYhNni51sJnMWCcPHIKtCg4GRY-pGMmrXarNIxxGQA"

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startMonitoringService()
        } else {
            Toast.makeText(this, "Permissão de microfone necessária para ouvir a TV", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSavedApiKey()
        setupListeners()
        updateServiceUI()
    }

    override fun onResume() {
        super.onResume()
        updateServiceUI()
    }

    private fun loadSavedApiKey() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_API_KEY, DEFAULT_API_KEY) ?: DEFAULT_API_KEY
        binding.etApiKey.setText(savedKey)
    }

    private fun saveApiKey(key: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    private fun setupListeners() {
        binding.btnToggleService.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            saveApiKey(key)

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

        binding.btnGrantOverlay.setOnClickListener {
            requestOverlayPermission()
        }
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Conceda permissão de sobreposição para exibir alertas sobre a TV aberta", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startMonitoringService()
        }
    }

    private fun startMonitoringService() {
        val key = binding.etApiKey.text.toString().trim()
        val intent = Intent(this, FactCheckForegroundService::class.java).apply {
            action = FactCheckForegroundService.ACTION_START
            putExtra(FactCheckForegroundService.EXTRA_API_KEY, key)
        }
        startForegroundService(intent)
        updateServiceUI()
    }

    private fun stopMonitoringService() {
        val intent = Intent(this, FactCheckForegroundService::class.java).apply {
            action = FactCheckForegroundService.ACTION_STOP
        }
        startService(intent)
        updateServiceUI()
    }

    private fun updateServiceUI() {
        val isRunning = FactCheckForegroundService.isRunning
        if (isRunning) {
            binding.tvStatus.text = getString(R.string.status_service_running)
            binding.tvStatus.setTextColor(Color.parseColor("#10B981"))
            binding.btnToggleService.text = getString(R.string.btn_stop_service)
            binding.btnToggleService.setBackgroundColor(Color.parseColor("#EF4444"))
        } else {
            binding.tvStatus.text = getString(R.string.status_service_stopped)
            binding.tvStatus.setTextColor(Color.parseColor("#EF4444"))
            binding.btnToggleService.text = getString(R.string.btn_start_service)
            binding.btnToggleService.setBackgroundColor(Color.parseColor("#10B981"))
        }

        if (Settings.canDrawOverlays(this)) {
            binding.btnGrantOverlay.text = "✓ Permissão de Sobreposição Concedida"
            binding.btnGrantOverlay.isEnabled = false
            binding.btnGrantOverlay.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.btnGrantOverlay.text = getString(R.string.btn_permission_overlay)
            binding.btnGrantOverlay.isEnabled = true
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
