package com.example.gamehud

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private lateinit var keyInput: EditText

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        box.addView(TextView(this).apply {
            text = "Game HUD AI\n\n1) Cole sua chave da API Gemini (grátis em aistudio.google.com)\n2) Toque em Iniciar e conceda as permissões\n3) Abra o jogo e toque no botão 🤖 para analisar a tela\n\nSegure o botão 🤖 por 1s para encerrar."
        })
        keyInput = EditText(this).apply {
            hint = "Chave da API"
            setText(getSharedPreferences("cfg", 0).getString("key", ""))
        }
        box.addView(keyInput)
        box.addView(Button(this).apply { text = "Iniciar HUD"; setOnClickListener { start() } })
        setContentView(box)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun start() {
        getSharedPreferences("cfg", 0).edit().putString("key", keyInput.text.toString().trim()).apply()
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
