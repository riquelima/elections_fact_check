package com.factcheck.tv.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.factcheck.tv.R
import com.factcheck.tv.api.FactCheckResult

class FactCheckOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var dismissTimer: CountDownTimer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Shows a real-time fact-check alert card in the top-right corner of the TV screen.
     */
    fun showFactCheckAlert(result: FactCheckResult) {
        if (!canDrawOverlays()) {
            Log.w("OverlayManager", "Permissão SYSTEM_ALERT_WINDOW não concedida")
            return
        }

        mainHandler.post {
            hideAlert() // Limpa qualquer alerta anterior

            try {
                val inflater = LayoutInflater.from(context)
                val view = inflater.inflate(R.layout.overlay_fact_check, null)
                overlayView = view

                val tvBadge = view.findViewById<TextView>(R.id.tvVerdictBadge)
                val tvTimer = view.findViewById<TextView>(R.id.tvTimer)
                val tvClaim = view.findViewById<TextView>(R.id.tvClaimText)
                val tvFact = view.findViewById<TextView>(R.id.tvFactText)
                val tvSource = view.findViewById<TextView>(R.id.tvSourceText)

                // Popula textos
                tvClaim.text = "\"${result.claimText}\""
                tvFact.text = result.verifiedFact
                tvSource.text = "Fonte: ${result.source}"

                // Aplica cores dinâmicas no Badge
                val (badgeText, badgeBgColor) = when (result.verdict.uppercase()) {
                    "FALSO" -> Pair("🔴 FALSO", Color.parseColor("#EF4444"))
                    "ENGANOSO" -> Pair("🟡 ENGANOSO / DISTORCIDO", Color.parseColor("#F59E0B"))
                    "VERDADEIRO" -> Pair("🟢 VERDADEIRO", Color.parseColor("#10B981"))
                    else -> Pair("ℹ️ FATO CHECADO", Color.parseColor("#3B82F6"))
                }
                tvBadge.text = badgeText
                (tvBadge.background as? GradientDrawable)?.setColor(badgeBgColor)
                    ?: run { tvBadge.setBackgroundColor(badgeBgColor) }

                // Parâmetros de janela flutuante no Android TV
                val layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = 48 // Margem de segurança de tela da TV (Overscan safe margin)
                    y = 48
                }

                windowManager.addView(view, params)

                // Timer regressivo de 10 segundos para fechar automaticamente
                dismissTimer = object : CountDownTimer(10000, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        tvTimer.text = "${millisUntilFinished / 1000}s"
                    }

                    override fun onFinish() {
                        hideAlert()
                    }
                }.start()

                // Toque ou clique no alerta fecha na hora
                view.setOnClickListener {
                    hideAlert()
                }

            } catch (e: Exception) {
                Log.e("OverlayManager", "Erro ao exibir overlay: ${e.message}", e)
            }
        }
    }

    fun hideAlert() {
        mainHandler.post {
            dismissTimer?.cancel()
            dismissTimer = null
            overlayView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.e("OverlayManager", "Erro ao remover view: ${e.message}")
                }
            }
            overlayView = null
        }
    }
}
