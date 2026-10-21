package com.factcheck.tv.overlay

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
    private var overlayRootView: View? = null
    private var dismissTimer: CountDownTimer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pulseAnimator: ObjectAnimator? = null
    private var ambientCycleRunnable: Runnable? = null
    private var cycleIndex = 0

    fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Exibe a pílula flutuante ambiente no canto superior direito da TV.
     * Fica SEMPRE visível enquanto o monitoramento estiver ativo (sobre uniTV, TV aberta, etc.).
     */
    fun showAmbientPill() {
        if (!canDrawOverlays()) {
            Log.w("OverlayManager", "Permissão SYSTEM_ALERT_WINDOW não concedida")
            return
        }

        mainHandler.post {
            if (overlayRootView != null) return@post // Já está visível

            try {
                val inflater = LayoutInflater.from(context)
                val view = inflater.inflate(R.layout.overlay_fact_check, null)
                overlayRootView = view

                val dotPulse = view.findViewById<View>(R.id.dotPulse)
                val tvAmbientStatus = view.findViewById<TextView>(R.id.tvAmbientStatus)
                val expandedCard = view.findViewById<View>(R.id.expandedAlertCard)

                expandedCard.visibility = View.GONE

                // Animação de respiração contínua no ponto verde
                pulseAnimator = ObjectAnimator.ofFloat(dotPulse, "alpha", 1.0f, 0.3f, 1.0f).apply {
                    duration = 1800
                    repeatCount = ObjectAnimator.INFINITE
                    start()
                }

                // Ciclo dinâmico de status a cada 10 segundos
                val statusTexts = listOf("🎙️ Ouvindo TV...", "✓ Proteção Ativa", "⚡ Monitorando Fatos")
                ambientCycleRunnable = object : Runnable {
                    override fun run() {
                        cycleIndex = (cycleIndex + 1) % statusTexts.size
                        tvAmbientStatus.text = statusTexts[cycleIndex]
                        mainHandler.postDelayed(this, 10000)
                    }
                }
                mainHandler.postDelayed(ambientCycleRunnable!!, 10000)

                // Layout flutuante sobreposto no topo direito da tela
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = 48 // Margem de segurança de tela da TV
                    y = 48
                }

                windowManager.addView(view, params)
                Log.i("OverlayManager", "Pílula de monitoramento sobreposta na tela da TV com sucesso.")

            } catch (e: Exception) {
                Log.e("OverlayManager", "Erro ao exibir pílula sobreposta: ${e.message}", e)
            }
        }
    }

    /**
     * Expande o card com o veredito da checagem na TV por 12 segundos e recolhe de volta para a pílula.
     */
    fun showFactCheckAlert(result: FactCheckResult) {
        if (!canDrawOverlays()) return

        mainHandler.post {
            val root = overlayRootView
            if (root == null) {
                showAmbientPill()
            }

            mainHandler.postDelayed({
                val view = overlayRootView ?: return@postDelayed
                val expandedCard = view.findViewById<View>(R.id.expandedAlertCard) ?: return@postDelayed
                val tvBadge = view.findViewById<TextView>(R.id.tvVerdictBadge)
                val tvTimer = view.findViewById<TextView>(R.id.tvTimer)
                val tvClaim = view.findViewById<TextView>(R.id.tvClaimText)
                val tvFact = view.findViewById<TextView>(R.id.tvFactText)
                val tvSource = view.findViewById<TextView>(R.id.tvSourceText)

                tvClaim.text = "\"${result.claimText}\""
                tvFact.text = result.verifiedFact
                tvSource.text = "Fonte: ${result.source}"

                val (badgeText, badgeBgColor) = when (result.verdict.uppercase()) {
                    "FALSO" -> Pair("🔴 FALSO", Color.parseColor("#EF4444"))
                    "ENGANOSO" -> Pair("🟡 ENGANOSO / DISTORCIDO", Color.parseColor("#F59E0B"))
                    "VERDADEIRO" -> Pair("🟢 VERDADEIRO", Color.parseColor("#10B981"))
                    else -> Pair("ℹ️ FATO CHECADO", Color.parseColor("#3B82F6"))
                }
                tvBadge.text = badgeText
                (tvBadge.background as? GradientDrawable)?.setColor(badgeBgColor)
                    ?: run { tvBadge.setBackgroundColor(badgeBgColor) }

                // Mostra o card expandido
                expandedCard.visibility = View.VISIBLE
                expandedCard.alpha = 0.0f
                expandedCard.animate().alpha(1.0f).setDuration(250).start()

                dismissTimer?.cancel()
                dismissTimer = object : CountDownTimer(12000, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        tvTimer.text = "${millisUntilFinished / 1000}s"
                    }

                    override fun onFinish() {
                        // Recolhe de volta para a pílula ambiente
                        expandedCard.animate().alpha(0.0f).setDuration(250).withEndAction {
                            expandedCard.visibility = View.GONE
                        }.start()
                    }
                }.start()

                expandedCard.setOnClickListener {
                    dismissTimer?.cancel()
                    expandedCard.visibility = View.GONE
                }
            }, 100)
        }
    }

    /**
     * Remove todos os elementos da tela ao parar o serviço.
     */
    fun hideAll() {
        mainHandler.post {
            dismissTimer?.cancel()
            dismissTimer = null

            ambientCycleRunnable?.let { mainHandler.removeCallbacks(it) }
            ambientCycleRunnable = null

            pulseAnimator?.cancel()
            pulseAnimator = null

            overlayRootView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.e("OverlayManager", "Erro ao remover view: ${e.message}")
                }
            }
            overlayRootView = null
        }
    }
}
