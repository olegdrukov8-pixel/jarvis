package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Path
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Сервис специальных возможностей: видит экран,
 * нажимает кнопки и выполняет свайпы в любом приложении.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: JarvisAccessibilityService? = null
        private val handler = Handler(Looper.getMainLooper())

        fun isEnabled(): Boolean = instance != null

        /** Свайп вверх — следующее видео (TikTok, Shorts, Reels). */
        fun swipeNext() { instance?.swipe(0.5f, 0.72f, 0.5f, 0.28f, 320) }

        /** Свайп вниз — предыдущее видео. */
        fun swipePrev() { instance?.swipe(0.5f, 0.28f, 0.5f, 0.72f, 320) }

        fun goHome() { instance?.performGlobalAction(GLOBAL_ACTION_HOME) }
        fun goBack() { instance?.performGlobalAction(GLOBAL_ACTION_BACK) }

        /**
         * Ставит лайк: сначала ищет кнопку по тексту (Like/Нравится/лайк),
         * если не нашёл — тапает по типичным координатам кнопки лайка TikTok.
         */
        fun like() {
            val s = instance ?: return
            handler.post {
                val clicked = s.clickByKeywords(listOf("like", "нравится", "лайк"))
                if (!clicked) {
                    handler.postDelayed({ s.tap(0.90f, 0.63f) }, 300)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun tap(fx: Float, fy: Float) {
        swipe(fx, fy, fx, fy, 80)
    }

    private fun swipe(fx1: Float, fy1: Float, fx2: Float, fy2: Float, durationMs: Long) {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(w * fx1, h * fy1)
            lineTo(w * fx2, h * fy2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** Ищет на экране элемент, чей текст содержит одно из слов, и нажимает его. */
    private fun clickByKeywords(keywords: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val node = findNode(root, keywords)
            val clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            if (!clicked) {
                tap(0.90f, 0.63f)
                true
            } else true
        } catch (e: Exception) {
            false
        }
    }

    private fun findNode(node: AccessibilityNodeInfo?, keywords: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val label = (node.text ?: node.contentDescription)?.toString()?.lowercase()
        if (label != null && keywords.any { label.contains(it) }) {
            var target: AccessibilityNodeInfo? = node
            var hops = 0
            while (target != null && !target.isClickable && hops < 6) {
                target = target.parent
                hops++
            }
            if (target != null && target.isClickable) return target
        }
        for (i in 0 until node.childCount) {
            val found = findNode(node.getChild(i), keywords)
            if (found != null) return found
        }
        return null
    }
}

/**
 * Разбирает распознанную команду и выполняет действие.
 */
object CommandProcessor {

    private val handler = Handler(Looper.getMainLooper())

    private val apps = linkedMapOf(
        "тик ток" to "com.zhiliaoapp.musically",
        "тикток" to "com.zhiliaoapp.musically",
        "ютуб" to "com.google.android.youtube",
        "ютубе" to "com.google.android.youtube",
        "youtube" to "com.google.android.youtube",
        "телеграм" to "org.telegram.messenger",
        "whatsapp" to "com.whatsapp",
        "ватсап" to "com.whatsapp",
        "инстаграм" to "com.instagram.android",
        "вконтакте" to "com.vkontakte.android",
        "вк" to "com.vkontakte.android",
        "хром" to "com.android.chrome",
        "chrome" to "com.android.chrome",
        "спотифай" to "com.spotify.music",
        "калькулятор" to "com.google.android.calculator",
        "настройки" to "com.android.settings",
        "камера" to "com.android.camera2",
        "календарь" to "com.google.android.calendar",
        "почта" to "com.google.android.gm",
        "gmail" to "com.google.android.gm",
        "карты" to "com.google.android.apps.maps",
        "музыка" to "com.google.android.apps.youtube.music"
    )

    fun execute(context: android.content.Context, rawText: String) {
        val t = rawText.lowercase()
            .replace("джарвис", "")
            .replace("пожалуйста", "")
            .trim()

        when {
            t.contains("лайк") || t.contains("нравится") || t.contains("like") -> {
                JarvisAccessibilityService.like()
                if (t.contains("листай") || t.contains("следующ") || t.contains("дальше") || t.contains("вперёд") || t.contains("вперед")) {
                    handler.postDelayed({ JarvisAccessibilityService.swipeNext() }, 900)
                }
            }

            t.contains("следующ") || t.contains("листай") ||
                t.contains("дальше") || t.contains("вперёд") || t.contains("вперед") ||
                t.contains("скролл") || t.contains("листни") -> {
                JarvisAccessibilityService.swipeNext()
            }

            t.contains("предыдущ") || t.contains("вернись") -> {
                JarvisAccessibilityService.swipePrev()
            }

            t.contains("домой") || t.contains("на главный") || t.contains("на главную") ||
                t.contains("дом") -> {
                JarvisAccessibilityService.goHome()
            }

            t.contains("назад") -> {
                JarvisAccessibilityService.goBack()
            }

            t.contains("открой") || t.contains("запусти") || t.contains("включи") -> {
                openApp(context, t)
            }

            else -> {
                openApp(context, t)
            }
        }
    }

    private fun openApp(context: android.content.Context, command: String) {
        val entry = apps.entries.firstOrNull { command.contains(it.key) } ?: return
        val launchIntent = context.packageManager.getLaunchIntentForPackage(entry.value)
        launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchIntent?.let { context.startActivity(it) }
    }
}

/**
 * Фоновый сервис: постоянно слушает микрофон.
 * Услышал "джарвис" -> режим команды -> выполнил -> снова слушает.
 */
class VoiceService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_channel"
        const val NOTIF_ID = 1
        const val ACTION_START = "com.jarvis.assistant.START"
        const val ACTION_STOP = "com.jarvis.assistant.STOP"
        private const val STATE_WAKE = 0
        private const val STATE_COMMAND = 1

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var recognizer: SpeechRecognizer
    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())
    private var state = STATE_WAKE
    private var running = false
    private var tone: ToneGenerator? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale("ru")
                }
            }
        } catch (e: Exception) {
            tts = null
        }
        tone = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        } catch (e: Exception) {
            null
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            isRunning = true
            val notification = buildNotification("Джарвис слушает...")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            handler.postDelayed({ startListening() }, 400)
        }
        return START_STICKY
    }

    private fun startListening() {
        if (!running) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
        }
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            restart(1500)
        }
    }

    private fun restart(delayMs: Long) {
        handler.postDelayed({ startListening() }, delayMs)
    }

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (state == STATE_COMMAND && error == SpeechRecognizer.ERROR_NO_MATCH) {
                state = STATE_WAKE
                updateNotification("Джарвис слушает...")
            }
            restart(700)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (state != STATE_WAKE) return
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: return
            val heardJarvis = matches.any { it.lowercase().contains("джарвис") }
            if (heardJarvis) {
                state = STATE_COMMAND
                tone?.startTone(ToneGenerator.TONE_PROP_ACK, 180)
                updateNotification("Говорите команду...")
                try {
                    recognizer.stopListening()
                } catch (e: Exception) {
                }
                restart(250)
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (state == STATE_COMMAND && !matches.isNullOrEmpty()) {
                val command = matches[0]
                updateNotification("Команда: " + command)
                CommandProcessor.execute(this@VoiceService, command)
                try {
                    tts?.speak("Выполняю", TextToSpeech.QUEUE_FLUSH, null, "jarvis_ok")
                } catch (e: Exception) {
                }
                state = STATE_WAKE
                updateNotification("Джарвис слушает...")
            }
            restart(500)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Джарвис",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Джарвис активен")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        try {
            recognizer.destroy()
        } catch (e: Exception) {
        }
        tts?.stop()
        tts?.shutdown()
        tone?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
