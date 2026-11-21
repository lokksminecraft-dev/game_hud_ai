package com.example.gamehud

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.*
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MaxHeightScroll(c: Context, private val maxH: Int) : ScrollView(c) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }
}

class HudService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var held: Image? = null
    private var busy = false
    private lateinit var btn: TextView
    private lateinit var panel: LinearLayout
    private lateinit var body: TextView
    private val hideTask = Runnable { panel.visibility = View.GONE }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(intent: Intent?, f: Int, id: Int): Int {
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("hud", "HUD", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "hud").setContentTitle("Assistente de jogo ativa")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(intent.getIntExtra("code", 0), data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, ui)
        setupCapture()
        setupHud()
        return START_NOT_STICKY
    }

    private fun setupCapture() {
        val dm = resources.displayMetrics
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 3)
        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            synchronized(lock) { held?.close(); held = img }
        }, ui)
        display = projection!!.createVirtualDisplay("hud", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
    }

    private fun lp(w: Int, h: Int, gravity: Int, y: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
    ).apply { this.gravity = gravity; this.y = y }

    private fun setupHud() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val alpha = prefs.getInt("opacity", 85) * 255 / 100
        val bgColor = (alpha shl 24) or 0x12141C

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(bgColor); cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0x666C5CE7)
            }
            visibility = View.GONE
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "🤖 Assistente"; setTextColor(0xFFB9B0FF.toInt()); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE); textSize = 18f; setPadding(dp(12), 0, 0, 0)
            setOnClickListener { panel.visibility = View.GONE }
        })
        panel.addView(header)
        body = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = prefs.getInt("tsize", 15).toFloat()
            setPadding(0, dp(6), 0, 0)
        }
        panel.addView(MaxHeightScroll(this, (sh * 0.4).toInt()).apply { addView(body) })

        val atBottom = prefs.getInt("pos", 0) == 1
        wm.addView(panel, lp((sw * 0.92).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            (if (atBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL,
            if (atBottom) dp(80) else dp(48)))

        val size = dp(prefs.getInt("btnsize", 56))
        btn = TextView(this).apply {
            text = "🤖"; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.5f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xE66C5CE7.toInt()); setStroke(dp(2), 0x88FFFFFF.toInt())
            }
        }
        val bp = lp(size, size, Gravity.TOP or Gravity.START, dp(160))
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false; var t0 = 0L
        btn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = bp.x; oy = bp.y; moved = false; t0 = System.currentTimeMillis() }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true
                    if (moved) { bp.x = ox + dx; bp.y = oy + dy; wm.updateViewLayout(btn, bp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (System.currentTimeMillis() - t0 > 1000) stopSelf() else ask()
                }
            }
            true
        }
        wm.addView(btn, bp)
    }

    private fun show(t: String, autoHide: Boolean = true) {
        body.text = t
        panel.visibility = View.VISIBLE
        ui.removeCallbacks(hideTask)
        if (autoHide && prefs.getBoolean("auto", true)) ui.postDelayed(hideTask, 15000)
    }

    private fun grab(): Bitmap? = synchronized(lock) {
        val img = held ?: return null
        val p = img.planes[0]
        val pad = p.rowStride - p.pixelStride * img.width
        val full = Bitmap.createBitmap(img.width + pad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        Bitmap.createBitmap(full, 0, 0, img.width, img.height)
    }

    private fun ask() {
        if (busy) return
        val bmp = grab() ?: return show("Ainda sem imagem. Mexa a tela e tente de novo.")
        busy = true
        btn.text = "⏳"
        show("Analisando a tela...", false)
        Thread {
            val out = try { callGemini(bmp) } catch (e: Exception) { "Não consegui conectar: ${e.message}" }
            ui.post { busy = false; btn.text = "🤖"; show(out) }
        }.start()
    }

    private fun buildPrompt(): String {
        val mode = prefs.getInt("mode", 0).coerceIn(0, Cfg.MODE_PROMPTS.size - 1)
        val len = when (prefs.getInt("len", 0)) {
            0 -> "Responda em no máximo 2 frases."
            1 -> "Responda em no máximo 4 frases."
            else -> "Pode detalhar, em até 8 frases."
        }
        val extra = prefs.getString("extra", "") ?: ""
        return Cfg.MODE_PROMPTS[mode] + " " + len +
            (if (extra.isNotEmpty()) " Instrução do jogador: $extra." else "") +
            " Responda em português, em texto simples, sem markdown. Ignore o botão e o painel de overlay do assistente."
    }

    private fun callGemini(bmp: Bitmap): String {
        val key = prefs.getString("key", "") ?: ""
        if (key.isEmpty()) return "Cole sua chave da API nas configurações do app."
        val model = (prefs.getString("model", Cfg.DEFAULT_MODEL) ?: Cfg.DEFAULT_MODEL).ifEmpty { Cfg.DEFAULT_MODEL }

        val s = 1024f / maxOf(bmp.width, bmp.height)
        val small = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("text", buildPrompt()))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))

        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = c.responseCode
        val txt = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code >= 400) return when (code) {
            404 -> "Esse modelo não está disponível. Abra o app e troque o nome do modelo nas configurações."
            429 -> "Limite grátis atingido. Aguarde um minuto e tente de novo."
            400, 403 -> "Chave inválida ou requisição recusada. Confira a chave no app."
            else -> "Erro $code"
        }
        val out = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        return out.replace("**", "").replace("* ", "• ").trim()
    }

    override fun onDestroy() {
        ui.removeCallbacks(hideTask)
        runCatching { wm.removeView(btn); wm.removeView(panel) }
        synchronized(lock) { held?.close(); held = null }
        display?.release(); reader?.close(); projection?.stop()
        super.onDestroy()
    }
}
