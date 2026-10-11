package com.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private val REQ_PERMISSIONS = 101

    private val bg = Color.parseColor("#0E1116")
    private val cardBg = Color.parseColor("#1A1F27")
    private val accent = Color.parseColor("#2F6FED")
    private val textMain = Color.parseColor("#E8EAF0")
    private val textGray = Color.parseColor("#9AA3B2")
    private val green = Color.parseColor("#5BD97A")
    private val red = Color.parseColor("#FF6B6B")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }

        val title = TextView(this).apply {
            text = "ДЖАРВИС"
            textSize = 32f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }

        val subtitle = TextView(this).apply {
            text = "Голосовой помощник · v3.1"
            textSize = 14f
            setTextColor(textGray)
            setPadding(0, dp(4), 0, dp(18))
        }

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(textMain)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(cardBg, 16)
        }

        fun makeBtn(label: String, color: Int): Button {
            return Button(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                background = rounded(color, 18)
                val p = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
                )
                p.topMargin = dp(12)
                layoutParams = p
            }
        }

        val accBtn = makeBtn("1. Включить службу доступности", accent)
        val startBtn = makeBtn("2. Запустить Джарвиса", Color.parseColor("#1E7A46"))
        val stopBtn = makeBtn("Остановить", Color.parseColor("#7A2E2E"))

        val help = TextView(this).apply {
            textSize = 13.5f
            setTextColor(textGray)
            setPadding(0, dp(22), 0, 0)
            text = helpText()
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusView)
        root.addView(accBtn)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(help)

        accBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        startBtn.setOnClickListener {
            requestPermissionsIfNeeded()
        }
        stopBtn.setOnClickListener {
            startService(
                Intent(this, VoiceService::class.java)
                    .setAction(VoiceService.ACTION_STOP)
            )
            refreshStatus()
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        })
    }

    private fun helpText(): String = listOf(
        "КОМАНДЫ — говорите одной фразой, начиная со «Джарвис»",
        "",
        "«привет» / «как дела» / «кто ты» — разговор",
        "«открой тик ток» — любое приложение",
        "«поиск рецепт борща» — поиск в Яндексе",
        "«включи музыку <название>» — музыка",
        "«зайди в чат к Антону» — чаты",
        "«напиши привет», затем «отправь» — сообщения",
        "«поставь лайк», «листай дальше», «предыдущее видео»",
        "«открой вкладку профиль / входящие»",
        "«зайди в поисковую строку»",
        "«домой», «назад»",
        "",
        "Требуется: интернет, приложение Google,",
        "служба специальных возможностей.",
        "Постоянное прослушивание расходует батарею —",
        "останавливайте кнопкой «Остановить»."
    ).joinToString("\n")

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
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
        startService(
            Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_START)
        )
        refreshStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startJarvis()
            }
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        val micOk = Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val accOk = isAccessibilityEnabled()
        val running = VoiceService.isRunning

        val b = SpannableStringBuilder()
        fun add(label: String, ok: Boolean) {
            val start = b.length
            b.append(label + ": " + (if (ok) "ок" else "нет") + "    ")
            b.setSpan(
                ForegroundColorSpan(if (ok) green else red),
                start, b.length, 0
            )
        }
        add("Микрофон", micOk)
        add("Служба доступности", accOk)
        add("Джарвис работает", running)
        statusView.text = b
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val serviceId =
            "com.jarvis.assistant/com.jarvis.assistant.JarvisAccessibilityService"
        return am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.id == serviceId }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }
}
 com.jarvis.assistant

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
