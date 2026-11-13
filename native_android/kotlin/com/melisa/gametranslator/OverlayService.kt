package com.melisa.gametranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max

/** Crea un traductor ML Kit a partir de códigos de idioma ("en", "es", ...). */
fun buildTranslator(source: String, target: String): Translator {
    val s = TranslateLanguage.fromLanguageTag(source) ?: TranslateLanguage.ENGLISH
    val t = TranslateLanguage.fromLanguageTag(target) ?: TranslateLanguage.SPANISH
    return Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(s).setTargetLanguage(t).build()
    )
}

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "gametranslator.START"
        const val ACTION_STOP = "gametranslator.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "traductor"
        private const val LABEL_IDLE = "文A"
        private const val LABEL_BUSY = "…"
        private const val LABEL_CLOSE = "✕"

        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var capW = 0
    private var capH = 0

    private var button: TextView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var overlayRoot: FrameLayout? = null

    private var busy = false
    private var showing = false

    private var translator: Translator? = null
    private var translatorKey = ""
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android exige llamar a startForeground antes de usar MediaProjection.
        startAsForeground()
        if (projection != null) return START_NOT_STICKY

        val data = readProjectionData(intent)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, data)
            // Android 14+: el callback debe registrarse antes de crear el VirtualDisplay.
            projection?.registerCallback(projectionCallback, main)
            setupCapture()
            createButton()
            running = true
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo iniciar: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readProjectionData(i: Intent?): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else i?.getParcelableExtra(EXTRA_DATA)

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Traductor", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Traductor activo")
            .setContentText("Toca esta notificación para detenerlo")
            .setContentIntent(stopIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    // ---------------------------------------------------------------- captura

    @Suppress("DEPRECATION")
    private fun readScreen(): IntArray {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        display.getRealMetrics(m)
        return intArrayOf(m.widthPixels, m.heightPixels, m.densityDpi)
    }

    private fun setupCapture() {
        val (w, h, dpi) = readScreen()
        capW = w
        capH = h
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection!!.createVirtualDisplay(
            "traductor", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, main
        )
    }

    /** Si la pantalla giró, ajusta el tamaño de captura. Devuelve true si hubo cambio. */
    private fun ensureCaptureSize(): Boolean {
        val (w, h, dpi) = readScreen()
        if (w == capW && h == capH) return false
        capW = w
        capH = h
        imageReader?.close()
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay?.resize(w, h, dpi)
        virtualDisplay?.surface = imageReader!!.surface
        return true
    }

    private fun imageToBitmap(img: Image): Bitmap {
        val w = img.width
        val h = img.height
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * w
        val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        img.close()
        if (rowPadding == 0) return bmp
        val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
        bmp.recycle()
        return cropped
    }

    // ------------------------------------------------------- botón flotante

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setLabel(label: String) {
        button?.text = label
    }

    private fun createButton() {
        val size = dp(52)
        val tv = TextView(this).apply {
            text = LABEL_IDLE
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xDD6750A4.toInt())
            }
            elevation = dp(6).toFloat()
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(200)
        }

        tv.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                        if (moved) {
                            lp.x = startX + dx
                            lp.y = startY + dy
                            wm.updateViewLayout(v, lp)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) onButtonTap()
                }
                return true
            }
        })

        wm.addView(tv, lp)
        button = tv
        buttonParams = lp
    }

    /** Vuelve a añadir el botón para que quede por encima de la traducción. */
    private fun bringButtonToFront() {
        val b = button ?: return
        val lp = buttonParams ?: return
        try {
            wm.removeView(b)
            wm.addView(b, lp)
        } catch (e: Exception) {
            // ignorar
        }
    }

    private fun onButtonTap() {
        if (busy) return
        if (showing) {
            clearOverlay()
            return
        }
        translateScreen()
    }

    // ------------------------------------------------------ flujo principal

    private fun translateScreen() {
        busy = true
        setLabel(LABEL_BUSY)
        val resized = ensureCaptureSize()
        // Ocultamos el botón para que no salga en la captura.
        button?.visibility = View.INVISIBLE
        main.postDelayed({ grabFrame(0) }, if (resized) 600L else 250L)
    }

    private fun grabFrame(attempt: Int) {
        val img = try {
            imageReader?.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (img == null) {
            if (attempt < 8) {
                main.postDelayed({ grabFrame(attempt + 1) }, 120L)
            } else {
                abort("No se pudo capturar la pantalla (el juego podría bloquear la captura)")
            }
            return
        }
        val bmp = imageToBitmap(img)
        button?.visibility = View.VISIBLE
        recognize(bmp)
    }

    private fun recognize(bmp: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                bmp.recycle()
                val blocks = text.textBlocks.filter { b ->
                    b.boundingBox != null && b.text.length >= 2 && b.text.any { it.isLetter() }
                }
                if (blocks.isEmpty()) {
                    abort("No encontré texto en pantalla")
                } else {
                    translateBlocks(blocks)
                }
            }
            .addOnFailureListener { e ->
                bmp.recycle()
                abort("Error de OCR: ${e.message}")
            }
    }

    private fun translateBlocks(blocks: List<Text.TextBlock>) {
        val p = getSharedPreferences("gt_prefs", MODE_PRIVATE)
        val source = p.getString("source", "en") ?: "en"
        val target = p.getString("target", "es") ?: "es"
        val mode = p.getString("mode", "overlay") ?: "overlay"
        if (source == target) {
            abort("Elige idiomas distintos en la app")
            return
        }
        val key = "$source>$target"
        if (key != translatorKey) {
            translator?.close()
            translator = buildTranslator(source, target)
            translatorKey = key
        }
        val tr = translator ?: return
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                val tasks = blocks.map { tr.translate(it.text.replace("\n", " ")) }
                Tasks.whenAllSuccess<String>(tasks)
                    .addOnSuccessListener { results -> showTranslations(blocks, results, mode) }
                    .addOnFailureListener { e -> abort("Error al traducir: ${e.message}") }
            }
            .addOnFailureListener {
                abort("No se pudo descargar el idioma (¿hay internet?)")
            }
    }

    private fun abort(msg: String) {
        busy = false
        button?.visibility = View.VISIBLE
        setLabel(LABEL_IDLE)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------- traducción

    private fun showTranslations(blocks: List<Text.TextBlock>, results: List<String>, mode: String) {
        clearOverlay()
        val root = FrameLayout(this)
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        // Android 12+ bloquea los toques que atraviesan overlays con alpha > 0.8.
        lp.alpha = 0.8f
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(root, lp)
        overlayRoot = root

        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val loc = IntArray(2)
                root.getLocationOnScreen(loc)
                if (mode == "box") populateBox(root, results)
                else populateOverlay(root, blocks, results, loc[0], loc[1])
            }
        })

        showing = true
        busy = false
        setLabel(LABEL_CLOSE)
        bringButtonToFront()
    }

    private fun populateOverlay(
        root: FrameLayout,
        blocks: List<Text.TextBlock>,
        results: List<String>,
        offX: Int,
        offY: Int
    ) {
        for (i in blocks.indices) {
            val box = blocks[i].boundingBox ?: continue
            val tv = TextView(this).apply {
                text = results.getOrElse(i) { "" }
                setTextColor(Color.WHITE)
                setBackgroundColor(0xF2101010.toInt())
                setPadding(dp(4), dp(2), dp(4), dp(2))
                gravity = Gravity.CENTER_VERTICAL
                if (Build.VERSION.SDK_INT >= 26) {
                    setAutoSizeTextTypeUniformWithConfiguration(8, 22, 1, TypedValue.COMPLEX_UNIT_SP)
                } else {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }
            }
            val params = FrameLayout.LayoutParams(
                max(box.width() + dp(8), dp(60)),
                max(box.height() + dp(4), dp(24))
            )
            params.leftMargin = box.left - offX - dp(4)
            params.topMargin = box.top - offY - dp(2)
            root.addView(tv, params)
        }
    }

    private fun populateBox(root: FrameLayout, results: List<String>) {
        val tv = TextView(this).apply {
            text = results.joinToString("\n\n")
            setTextColor(Color.WHITE)
            setBackgroundColor(0xF2000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            maxLines = 12
            ellipsize = TextUtils.TruncateAt.END
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        params.leftMargin = dp(16)
        params.rightMargin = dp(16)
        params.bottomMargin = dp(24)
        root.addView(tv, params)
    }

    private fun clearOverlay() {
        overlayRoot?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // ignorar
            }
        }
        overlayRoot = null
        showing = false
        setLabel(LABEL_IDLE)
    }

    // ------------------------------------------------------------- cierre

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        clearOverlay()
        button?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // ignorar
            }
        }
        button = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.let {
            try {
                it.unregisterCallback(projectionCallback)
                it.stop()
            } catch (e: Exception) {
                // ignorar
            }
        }
        projection = null
        translator?.close()
        translator = null
        recognizer.close()
        super.onDestroy()
    }
}

