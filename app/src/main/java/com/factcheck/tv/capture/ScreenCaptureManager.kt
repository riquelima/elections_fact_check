package com.factcheck.tv.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Capturador de Tela em Alta Resolução (720p HD) para Smart TVs e TV Boxes.
 * Tira prints contínuos da tela da TV para que a IA analise com nitidez absoluta
 * as tarjas, o GC (gerador de caracteres), números e gráficos das emissoras de jornal.
 */
class ScreenCaptureManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Resolução HD 720p (1280x720) para máxima legibilidade do texto na TV
    private var width = 1280
    private var height = 720
    private var density = 160

    var isInitialized = false
        private set

    val currentMediaProjection: MediaProjection?
        get() = mediaProjection

    @Volatile
    private var lastCapturedBase64: String? = null
    @Volatile
    private var lastProcessedTime = 0L
    private val THROTTLE_MS = 1200L // Atualiza no máximo 1 print a cada 1.2s para manter performance leve

    fun initialize(resultCode: Int, data: Intent) {
        try {
            release()

            backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)

            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            width = 1280
            height = 720
            density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val now = System.currentTimeMillis()
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    if (now - lastProcessedTime < THROTTLE_MS) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    lastProcessedTime = now
                    processImageToJpegBase64(image)
                } catch (e: Exception) {
                    // Ignora frames transitórios
                }
            }, backgroundHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "TVFactCheckScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, backgroundHandler
            )
            isInitialized = true
            Log.i("ScreenCapture", "Captura de tela HD 720p inicializada com sucesso ($width x $height).")
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Erro ao inicializar captura de tela: ${e.message}")
            isInitialized = false
        }
    }

    private fun processImageToJpegBase64(image: Image) {
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)

            val cleanBitmap = if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, width, height)
            } else {
                bitmap
            }

            val baos = ByteArrayOutputStream()
            // Compressão JPEG a 70% preserva todas as letras das manchetes com arquivo leve (~90 KB)
            cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            val jpegBytes = baos.toByteArray()
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            lastCapturedBase64 = "data:image/jpeg;base64,$b64"

            if (cleanBitmap != bitmap) {
                bitmap.recycle()
            }
            cleanBitmap.recycle()
        } catch (e: Exception) {
            Log.w("ScreenCapture", "Falha ao processar frame de tela: ${e.message}")
        } finally {
            image.close()
        }
    }

    /**
     * Retorna o print mais recente da tela da TV em base64 (data:image/jpeg;base64,...).
     */
    fun getLatestScreenshotBase64(): String? {
        return lastCapturedBase64
    }

    fun release() {
        isInitialized = false
        lastCapturedBase64 = null
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            backgroundThread?.quitSafely()
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Erro ao liberar recursos de captura de tela: ${e.message}")
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        backgroundThread = null
        backgroundHandler = null
    }
}
