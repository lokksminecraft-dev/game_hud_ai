package com.example.gamehud

import android.app.*
import android.content.Context
import android.content.Intent
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
import android.view.*
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class HudService : Service() {
    companion object {
        const val MODEL = "gemini-2.5-flash" // troque se o modelo mudar
        const val PROMPT = "Você é uma assistente de jogos. Analise este print da tela do jogador " +
            "e dê dicas curtas e úteis em português (máx. 3 frases). Ignore o HUD de overlay."
    }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var held: Image? = null
    private lateinit var btn: TextView
    private lateinit var panel: TextView

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(intent: Intent?, f: Int, id: Int): Int {
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

    private fun lp(w: Int, gravity: Int, y: Int) = WindowManager.LayoutParams(
        w, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
    ).apply { this.gravity = gravity; this.y = y }

    private fun setupHud() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val sw = resources.displayMetrics.widthPixels

        panel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; setPadding(32, 24, 32, 24)
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = 32f }
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        wm.addView(panel, lp((sw * 0.9).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL, 120))

        btn = TextView(this).apply {
            text = "🤖"; textSize = 26f; gravity = Gravity.CENTER; setPadding(28, 20, 28, 20)
            background = GradientDrawable().apply { setColor(0xCC202020.toInt()); shape = GradientDrawable.OVAL }
        }
        val bp = lp(WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START, 400)
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

    private fun show(t: String) { panel.text = t; panel.visibility = View.VISIBLE }

    private fun grab(): Bitmap? = synchronized(lock) {
        val img = held ?: return null
        val p = img.planes[0]
        val pad = p.rowStride - p.pixelStride * img.width
        val full = Bitmap.createBitmap(img.width + pad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        Bitmap.createBitmap(full, 0, 0, img.width, img.height)
    }

    private fun ask() {
        val bmp = grab() ?: return show("Ainda sem imagem. Mexa a tela e tente de novo.")
        show("Analisando...")
        Thread {
            val out = try { callGemini(bmp) } catch (e: Exception) { "Erro: ${e.message}" }
            ui.post { show(out) }
        }.start()
    }

    private fun callGemini(bmp: Bitmap): String {
        val key = getSharedPreferences("cfg", 0).getString("key", "") ?: ""
        if (key.isEmpty()) return "Configure a chave da API no app."
        val s = 1024f / maxOf(bmp.width, bmp.height)
        val small = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("text", PROMPT))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))

        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
        val txt = stream.bufferedReader().readText()
        if (c.responseCode >= 400) return "Erro ${c.responseCode}: ${txt.take(200)}"
        return JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }

    override fun onDestroy() {
        runCatching { wm.removeView(btn); wm.removeView(panel) }
        synchronized(lock) { held?.close(); held = null }
        display?.release(); reader?.close(); projection?.stop()
        super.onDestroy()
    }
}
