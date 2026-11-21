package com.example.gamehud

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

object Cfg {
    const val DEFAULT_MODEL = "gemini-3.8-flash"
    val MODE_NAMES = listOf("Dicas gerais", "Estratégia e decisões", "Explicar a tela", "Só minha instrução")
    val MODE_PROMPTS = listOf(
        "Você é uma assistente de jogos. Analise o print da tela do jogador e dê dicas úteis para ele jogar melhor.",
        "Você é uma estrategista de jogos. Analise o print e sugira a melhor decisão ou o próximo movimento.",
        "Explique de forma simples o que está acontecendo na tela do jogo e o que cada elemento importante significa.",
        "Analise o print da tela do jogador."
    )
}

class MainActivity : Activity() {
    private val BG = 0xFF0F1117.toInt()
    private val CARD = 0xFF1A1D27.toInt()
    private val FIELD = 0xFF262A38.toInt()
    private val ACCENT = 0xFF6C5CE7.toInt()
    private val MUTED = 0xFF9AA0B4.toInt()

    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var keyIn: EditText
    private lateinit var modelIn: EditText
    private lateinit var extraIn: EditText
    private lateinit var modeSp: Spinner
    private lateinit var lenSp: Spinner
    private lateinit var posSp: Spinner
    private lateinit var autoSw: Switch
    private val sliders = mutableMapOf<String, Pair<SeekBar, Int>>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, r: Int) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun label(t: String, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun LinearLayout.put(v: View, top: Int = 8) =
        addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })

    private fun card(title: String, build: LinearLayout.() -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(CARD, 16)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        c.addView(label(title, 16f, Color.WHITE, true))
        c.build()
        root.put(c, 12)
    }

    private fun input(hint: String, key: String, def: String, pwd: Boolean = false, multi: Boolean = false) =
        EditText(this).apply {
            setHint(hint); setHintTextColor(MUTED); setTextColor(Color.WHITE); textSize = 14f
            background = round(FIELD, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(prefs.getString(key, def))
            if (pwd) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (multi) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2 }
        }

    private fun spinner(key: String, options: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, options).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setSelection(prefs.getInt(key, 0))
    }

    private fun slider(parent: LinearLayout, title: String, key: String, min: Int, max: Int, def: Int, unit: String) {
        val v = prefs.getInt(key, def)
        val lab = label("$title: $v$unit", 13f, MUTED)
        parent.put(lab, 14)
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = v - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { lab.text = "$title: ${p + min}$unit" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        parent.addView(sb)
        sliders[key] = sb to min
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(32))
        }
        root.addView(label("🤖 Game HUD AI", 26f, Color.WHITE, true))
        root.addView(label("Assistente de IA que lê a tela do seu jogo", 14f, MUTED))

        card("Conexão") {
            put(label("Chave da API Gemini (grátis em aistudio.google.com)", 12f, MUTED))
            keyIn = input("Cole sua chave aqui", "key", "", pwd = true); put(keyIn, 6)
            put(label("Modelo (troque aqui se o Google mudar)", 12f, MUTED), 14)
            modelIn = input(Cfg.DEFAULT_MODEL, "model", Cfg.DEFAULT_MODEL); put(modelIn, 6)
        }

        card("Assistente") {
            put(label("Tipo de ajuda", 12f, MUTED))
            modeSp = spinner("mode", Cfg.MODE_NAMES); put(modeSp, 2)
            put(label("Tamanho da resposta", 12f, MUTED), 12)
            lenSp = spinner("len", listOf("Curta (2 frases)", "Média (4 frases)", "Detalhada")); put(lenSp, 2)
            put(label("Instrução extra (opcional)", 12f, MUTED), 12)
            extraIn = input("Ex.: foque em posicionamento e munição", "extra", "", multi = true); put(extraIn, 6)
        }

        card("Aparência") {
            slider(this, "Tamanho do botão", "btnsize", 40, 96, 56, " dp")
            slider(this, "Tamanho do texto", "tsize", 12, 24, 15, " sp")
            slider(this, "Opacidade do painel", "opacity", 40, 100, 85, "%")
            put(label("Posição do painel", 12f, MUTED), 14)
            posSp = spinner("pos", listOf("Topo da tela", "Parte de baixo")); put(posSp, 2)
            autoSw = Switch(this@MainActivity).apply {
                text = "Fechar painel sozinho (15s)"
                setTextColor(Color.WHITE)
                isChecked = prefs.getBoolean("auto", true)
            }
            put(autoSw, 14)
        }

        val go = Button(this).apply {
            text = "▶  Iniciar HUD"; isAllCaps = false; textSize = 17f
            setTextColor(Color.WHITE); background = round(ACCENT, 14)
            setOnClickListener { start() }
        }
        root.addView(go, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(20) })
        root.addView(label("As configurações valem ao iniciar o HUD. Para encerrar, segure o botão 🤖 por 1 segundo.", 12f, MUTED)
            .apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun save() {
        prefs.edit().apply {
            putString("key", keyIn.text.toString().trim())
            putString("model", modelIn.text.toString().trim().ifEmpty { Cfg.DEFAULT_MODEL })
            putString("extra", extraIn.text.toString().trim())
            putInt("mode", modeSp.selectedItemPosition)
            putInt("len", lenSp.selectedItemPosition)
            putInt("pos", posSp.selectedItemPosition)
            putBoolean("auto", autoSw.isChecked)
            sliders.forEach { (k, p) -> putInt(k, p.first.progress + p.second) }
        }.apply()
    }

    private fun start() {
        save()
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Permita 'exibir sobre outros apps' e volte", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    @Deprecated("simple flow")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 100 && res == RESULT_OK && data != null) {
            startForegroundService(Intent(this, HudService::class.java).putExtra("code", res).putExtra("data", data))
            moveTaskToBack(true)
        }
    }
}
