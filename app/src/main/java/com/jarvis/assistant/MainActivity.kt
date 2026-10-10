package com.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private val REQ_PERMISSIONS = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (20 * resources.displayMetrics.density).toInt()

        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(pad, pad, pad, pad / 2)
        }

        val accBtn = Button(this).apply { text = "1. Включить службу доступности" }
        val startBtn = Button(this).apply { text = "2. Запустить Джарвиса" }
        val stopBtn = Button(this).apply { text = "Остановить Джарвиса" }

        val helpText = listOf(
            "Как пользоваться",
            "",
            "Скажите «Джарвис», дождитесь сигнала, затем команду:",
            "",
            "• «открой тик ток» — откроет приложение",
            "  (ютуб, телеграм, whatsapp, инстаграм, вконтакте, спотифай, хром, камера, калькулятор, настройки, карты, почта)",
            "• «поставь лайк» — нажмёт кнопку лайка",
            "• «листай дальше» / «следующее видео» — свайп вверх",
            "• «предыдущее видео» — свайп вниз",
            "• «домой» — на главный экран",
            "• «назад» — системная кнопка «назад»",
            "• «поставь лайк и листай дальше» — комбинации работают",
            "",
            "Примечания",
            "• Распознавание речи работает через сервисы Google — нужен интернет и приложение «Google» на телефоне.",
            "• Сервис постоянно слушает микрофон — заметен расход батареи. Останавливайте кнопкой, когда не нужен.",
            "• Лайк в TikTok: сначала ищет кнопку по названию, если не нашёл — тапает по правому краю экрана.",
            "• На экране блокировки и во время звонков распознавание может не работать."
        ).joinToString("\n")

        val help = TextView(this).apply {
            textSize = 15f
            text = helpText
            setPadding(pad, pad / 2, pad, pad)
        }

        accBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        startBtn.setOnClickListener {
            requestPermissionsIfNeeded()
        }
        stopBtn.setOnClickListener {
            startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            refreshStatus()
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            addView(statusView)
            addView(accBtn)
            addView(startBtn)
            addView(stopBtn)
            addView(help)
        }

        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
        } else {
            startJarvis()
        }
    }

    private fun startJarvis() {
        startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_START))
        refreshStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            val micGranted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (micGranted) {
                startJarvis()
            }
            refreshStatus()
        }
    }

    private fun yn(b: Boolean): String = if (b) "OK" else "нет"

    private fun refreshStatus() {
        val micOk = Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val accOk = isAccessibilityEnabled()
        val running = VoiceService.isRunning
        statusView.text = "Микрофон: " + yn(micOk) +
            "   Служба доступности: " + yn(accOk) +
            "   Джарвис работает: " + yn(running)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val serviceId = "com.jarvis.assistant/com.jarvis.assistant.JarvisAccessibilityService"
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id == serviceId }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }
}
