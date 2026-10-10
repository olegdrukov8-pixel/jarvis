package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

/** Озвучка ответов Джарвиса. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false

    fun init(context: Context) {
        if (tts != null) return
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale("ru")
                }
            }
        } catch (e: Exception) {
            tts = null
        }
    }

    fun say(text: String) {
        if (!ready) return
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_" + System.nanoTime())
        } catch (e: Exception) {
        }
    }

    fun shutdown() {
        ready = false
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
    }
}

/**
 * Сервис специальных возможностей: видит экран, нажимает кнопки,
 * вводит текст, открывает чаты и выполняет свайпы в любом приложении.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: JarvisAccessibilityService? = null
        private val handler = Handler(Looper.getMainLooper())

        fun isEnabled(): Boolean = instance != null

        fun swipeNext() {
            instance ?: return
            Speaker.say(pick(swipePhrases))
            instance?.swipe(0.5f, 0.72f, 0.5f, 0.28f, 320)
        }

        fun swipePrev() {
            instance ?: return
            Speaker.say("Возвращаю предыдущее")
            instance?.swipe(0.5f, 0.28f, 0.5f, 0.72f, 320)
        }

        fun goHome() {
            instance ?: return
            Speaker.say("Возвращаюсь на главный экран")
            instance?.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        fun goBack() {
            instance ?: return
            Speaker.say("Назад")
            instance?.performGlobalAction(GLOBAL_ACTION_BACK)
        }

        /** Лайк: ищет кнопку по тексту, иначе тапает по координатам TikTok. */
        fun like() {
            val s = instance ?: return
            Speaker.say(pick(likePhrases))
            handler.post {
                val clicked = s.clickByKeywords(listOf("like", "нравится", "лайк"))
                if (!clicked) {
                    handler.postDelayed({ s.tap(0.90f, 0.63f) }, 300)
                }
            }
        }

        /** Вводит текст в сфокусированное поле ввода. */
        fun typeText(text: String) {
            val s = instance ?: return
            handler.post {
                val root = s.rootInActiveWindow ?: return@post
                val target = s.findFocusedEditable(root)
                if (target != null) {
                    val args = Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                    target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    Speaker.say("Написал: " + text)
                } else {
                    s.tap(0.5f, 0.5f)
                    handler.postDelayed({ typeText(text) }, 500)
                }
            }
        }

        /** Открывает чат по имени. Несколько совпадений -> просит уточнить. */
        fun openChat(name: String) {
            val s = instance ?: return
            val clean = name.trim().lowercase()
            if (clean.isEmpty()) {
                Speaker.say("Не расслышал имя")
                return
            }
            handler.post {
                val root = s.rootInActiveWindow ?: return@post
                val matches = mutableListOf<AccessibilityNodeInfo>()
                s.findNodesByText(root, clean, matches)
                val unique = matches.distinctBy {
                    it.viewIdResourceName + "|" + (it.text ?: it.contentDescription ?: "")
                }
                when {
                    unique.isEmpty() ->
                        Speaker.say("Не нашёл чат с именем " + clean)
                    unique.size > 1 ->
                        Speaker.say("Нашёл несколько чатов с именем " + clean +
                            ". Уточните фамилию или имя")
                    else -> {
                        unique[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Speaker.say("Открываю чат с " + clean)
                    }
                }
            }
        }

        /** Открывает поисковую строку. */
        fun openSearch() {
            clickByLabels(
                listOf("поиск", "search", "найти"),
                "Открываю поиск",
                "Не нашёл строку поиска"
            )
        }

        /** Жмёт кнопку отправки сообщения. */
        fun pressSend() {
            clickByLabels(
                listOf("отправить", "send"),
                "Отправляю",
                "Не нашёл кнопку отправки"
            )
        }

        /** Открывает вкладку (профиль, входящие, главная...). */
        fun openTab(tab: String) {
            val keywords = when {
                tab.contains("входящ") || tab.contains("inbox") ->
                    listOf("входящие", "уведомления", "inbox")
                tab.contains("профил") || tab.contains("profile") ->
                    listOf("профиль", "profile")
                tab.contains("главн") || tab.contains("home") || tab.contains("дом") ->
                    listOf("главная", "home")
                tab.contains("друз") -> listOf("друзья", "friends")
                tab.contains("рекоменда") || tab.contains("интересн") ->
                    listOf("интересное", "discover", "рекомендации")
                else -> listOf(tab)
            }
            clickByLabels(keywords, "Открываю вкладку", "Не нашёл такую вкладку")
        }

        private fun clickByLabels(
            keywords: List<String>,
            doing: String,
            failMsg: String
        ) {
            val s = instance ?: return
            handler.post {
                val root = s.rootInActiveWindow ?: return@post
                for (kw in keywords) {
                    val matches = mutableListOf<AccessibilityNodeInfo>()
                    s.findNodesByText(root, kw, matches)
                    val target = matches.firstOrNull { it.isClickable } ?: matches.firstOrNull()
                    if (target != null) {
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Speaker.say(doing)
                        return@post
                    }
                }
                Speaker.say(failMsg)
            }
        }

        private val swipePhrases = listOf(
            "Листаю", "Следующее", "Прокручиваю ленту"
        )
        private val likePhrases = listOf(
            "Ставлю лайк",
            "Лайк поставлен. Надеюсь, видео того стоило",
            "Отмечаю как понравившееся"
        )

        fun pick(list: List<String>): String {
            return list[kotlin.random.Random.nextInt(list.size)]
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

    private fun findNodesByText(
        node: AccessibilityNodeInfo?,
        text: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        val label = (node.text ?: node.contentDescription)?.toString()?.lowercase()
        if (label != null && label.contains(text)) {
            var target: AccessibilityNodeInfo? = node
            var hops = 0
            while (target != null && !target.isClickable && hops < 6) {
                target = target.parent
                hops++
            }
            if (target != null) out.add(target)
        }
        for (i in 0 until node.childCount) {
            findNodesByText(node.getChild(i), text, out)
        }
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && (node.isEditable ||
                node.className?.toString()?.contains("EditText") == true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findFocusedEditable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }
}

/**
 * Разбирает команды, отвечает фразами, открывает приложения,
 * чаты, вкладки, поиск; ставит лайки и листает ленту.
 */
object CommandProcessor {

    private val handler = Handler(Looper.getMainLooper())

    private val aliases = linkedMapOf(
        "тик ток" to "com.zhiliaoapp.musically",
        "тикток" to "com.zhiliaoapp.musically",
        "тик-ток" to "com.zhiliaoapp.musically",
        "tik tok" to "com.zhiliaoapp.musically",
        "tiktok" to "com.zhiliaoapp.musically",
        "ютуб" to "com.google.android.youtube",
        "ютубе" to "com.google.android.youtube",
        "youtube" to "com.google.android.youtube",
        "you tube" to "com.google.android.youtube",
        "телеграм" to "org.telegram.messenger",
        "telegram" to "org.telegram.messenger",
        "whatsapp" to "com.whatsapp",
        "ватсап" to "com.whatsapp",
        "вотсап" to "com.whatsapp",
        "инстаграм" to "com.instagram.android",
        "instagram" to "com.instagram.android",
        "вконтакте" to "com.vkontakte.android",
        "вк" to "com.vkontakte.android",
        "vk" to "com.vkontakte.android",
        "одноклассники" to "ru.ok.android",
        "хром" to "com.android.chrome",
        "chrome" to "com.android.chrome",
        "браузер" to "com.android.chrome",
        "спотифай" to "com.spotify.music",
        "spotify" to "com.spotify.music",
        "яндекс музыку" to "ru.yandex.music",
        "яндекс музыка" to "ru.yandex.music",
        "калькулятор" to "com.google.android.calculator",
        "настройки" to "com.android.settings",
        "камера" to "com.android.camera2",
        "календарь" to "com.google.android.calendar",
        "почта" to "com.google.android.gm",
        "gmail" to "com.google.android.gm",
        "карты" to "com.google.android.apps.maps",
        "гугл карты" to "com.google.android.apps.maps",
        "яндекс карты" to "ru.yandex.yandexmaps",
        "музыка" to "com.google.android.apps.youtube.music",
        "звонилку" to "com.google.android.dialer",
        "звонки" to "com.google.android.dialer",
        "контакты" to "com.google.android.contacts",
        "галерею" to "com.google.android.apps.photos",
        "галерея" to "com.google.android.apps.photos",
        "часы" to "com.google.android.deskclock",
        "будильник" to "com.google.android.deskclock",
        "плей маркет" to "com.android.vending",
        "play market" to "com.android.vending",
        "гугл диск" to "com.google.android.apps.docs"
    )

    private val fallbackPackages = mapOf(
        "com.zhiliaoapp.musically" to listOf("com.zhiliaoapp.musically.go"),
        "org.telegram.messenger" to listOf(
            "org.telegram.messenger.web",
            "com.telegram.web",
            "org.thunderdog.challegram"
        ),
        "com.android.chrome" to listOf(
            "org.mozilla.firefox", "com.opera.browser", "com.yandex.browser"
        )
    )

    private val openPhrases = listOf(
        "Открываю", "Уже открываю", "Сделано, сэр",
        "К вашим услугам. Открываю", "Секунду — открываю"
    )
    private val greetPhrases = listOf(
        "Привет! Чем займёмся?", "Здравствуйте. Я к вашим услугам",
        "Привет! Слушаю вас"
    )
    private val howAreYou = listOf(
        "Работаю без сбоев, спасибо, что спросили",
        "Все системы в норме. Как ваши дела?",
        "Отлично! Микрофон чистый, память свободна. Можно захватывать мир"
    )
    private val whoAreYou = listOf(
        "Я Джарвис — ваш голосовой помощник. Версия три ноль",
        "Джарвис, к вашим услугам. Ну, почти тот самый из фильма"
    )
    private val youreWelcome = listOf(
        "Всегда пожалуйста", "Обращайтесь", "Не стоит благодарности"
    )
    private val praiseReply = listOf(
        "Стараюсь, сэр", "Спасибо! Это вдохновляет"
    )
    private val byeReply = listOf(
        "До связи", "Всего доброго. Я тут, если что"
    )

    fun execute(context: Context, rawText: String) {
        val t = rawText.lowercase()
            .replace("джарвис", "")
            .replace("пожалуйста", "")
            .replace("ё", "е")
            .trim()

        if (t.isEmpty()) return

        // --- Разговор ---
        val smalltalk = listOf(
            "привет", "здравствуй", "добрый день", "доброе утро",
            "добрый вечер", "как дела", "как ты", "как ваши дела",
            "кто ты", "как тебя зовут", "спасибо", "благодарю",
            "молодец", "молодцы", "пока", "до свидания"
        )
        if (t.length < 35 && smalltalk.any { t.contains(it) }) {
            smalltalkReply(t)
            return
        }

        when {
            // --- Написать текст ---
            t.startsWith("напиши") || t.startsWith("набери") || t.startsWith("напечатай") -> {
                val text = t
                    .removePrefix("напиши").removePrefix("набери").removePrefix("напечатай")
                    .trim()
                if (text.isNotEmpty()) {
                    JarvisAccessibilityService.typeText(text)
                } else {
                    Speaker.say("Не расслышал, что написать")
                }
            }

            // --- Отправить сообщение ---
            t.contains("отправь") || t.contains("отправить") -> {
                JarvisAccessibilityService.pressSend()
            }

            // --- Зайти в чат к кому-то ---
            t.contains("чат") -> {
                var name = afterWord(t, "чат")
                name = name
                    .removePrefix("к ").removePrefix("ко ")
                    .removePrefix("с ").removePrefix("со ")
                    .removePrefix("с именем ").removePrefix("имени ")
                    .trim()
                if (name.isNotEmpty()) {
                    JarvisAccessibilityService.openChat(name)
                } else {
                    Speaker.say("Не расслышал, к кому зайти")
                }
            }

            // --- Поисковая строка ---
            t.contains("поиск") || t.contains("найти") -> {
                JarvisAccessibilityService.openSearch()
            }

            // --- Вкладка ---
            t.contains("вкладк") -> {
                JarvisAccessibilityService.openTab(
                    afterWord(t, "вкладку").ifEmpty { afterWord(t, "вкладка") }
                )
            }

            // --- Лайк ---
            t.contains("лайк") || t.contains("нравится") || t.contains("like") -> {
                JarvisAccessibilityService.like()
                if (t.contains("листай") || t.contains("следующ") ||
                    t.contains("дальше") || t.contains("вперед")) {
                    handler.postDelayed({ JarvisAccessibilityService.swipeNext() }, 900)
                }
            }

            t.contains("следующ") || t.contains("листай") ||
                t.contains("дальше") || t.contains("вперед") ||
                t.contains("скролл") || t.contains("листни") -> {
                JarvisAccessibilityService.swipeNext()
            }

            t.contains("предыдущ") || t.contains("вернись") -> {
                JarvisAccessibilityService.swipePrev()
            }

            t.contains("домой") || t.contains("на главный") ||
                t.contains("на главную") || t == "дом" -> {
                JarvisAccessibilityService.goHome()
            }

            t.contains("назад") -> {
                JarvisAccessibilityService.goBack()
            }

            else -> {
                openApp(context, t)
            }
        }
    }

    private fun smalltalkReply(t: String) {
        when {
            t.contains("как дела") || t.contains("как ты") || t.contains("как ваши дела") ->
                Speaker.say(JarvisAccessibilityService.pick(howAreYou))
            t.contains("кто ты") || t.contains("как тебя зовут") ->
                Speaker.say(JarvisAccessibilityService.pick(whoAreYou))
            t.contains("спасибо") || t.contains("благодарю") ->
                Speaker.say(JarvisAccessibilityService.pick(youreWelcome))
            t.contains("молодец") || t.contains("молодцы") ->
                Speaker.say(JarvisAccessibilityService.pick(praiseReply))
            t.contains("пока") || t.contains("до свидания") ->
                Speaker.say(JarvisAccessibilityService.pick(byeReply))
            else ->
                Speaker.say(JarvisAccessibilityService.pick(greetPhrases))
        }
    }

    private fun afterWord(t: String, word: String): String {
        val i = t.indexOf(word)
        return if (i >= 0) t.substring(i + word.length).trim() else ""
    }

    private fun openApp(context: Context, command: String) {
        val q = command
            .replace("откройте", "").replace("открой", "").replace("открыть", "")
            .replace("запустите", "").replace("запусти", "").replace("запустить", "")
            .replace("включи", "").replace("включить", "")
            .replace("зайди в", "").replace("зайти в", "")
            .trim()

        if (q.isEmpty()) {
            Speaker.say("Не расслышал, что открыть")
            return
        }

        var pkg: String? = aliases.entries.firstOrNull { q.contains(it.key) }?.value
        if (pkg == null) {
            pkg = findByLabel(context, q)
        }
        if (pkg == null) {
            Speaker.say("Не нашёл приложение " + q)
            return
        }

        val pm = context.packageManager
        var launchIntent = pm.getLaunchIntentForPackage(pkg)
        if (launchIntent == null) {
            for (alt in fallbackPackages[pkg].orEmpty()) {
                launchIntent = pm.getLaunchIntentForPackage(alt)
                if (launchIntent != null) break
            }
        }
        if (launchIntent == null) {
            Speaker.say("Приложение " + q + " не установлено")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        Speaker.say(JarvisAccessibilityService.pick(openPhrases))
    }

    private fun findByLabel(context: Context, query: String): String? {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }
        val qWords = query.split(Regex("[^a-zа-я0-9]+"))
            .filter { it.length > 1 }
        if (qWords.isEmpty()) return null

        var best: String? = null
        var bestScore = 0
        for (info in activities) {
            val label = info.loadLabel(pm).toString().lowercase().replace("ё", "е")
            if (label.isEmpty()) continue
            if (label.contains(query)) return info.activityInfo.packageName
            val score = qWords.count { label.contains(it) }
            if (score > bestScore) {
                bestScore = score
                best = info.activityInfo.packageName
            }
        }
        return if (bestScore >= qWords.size) best else null
    }
}

/**
 * Фоновый сервис: слушает микрофон почти непрерывно
 * (паузы между циклами ~0,3 секунды).
 */
class VoiceService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_channel"
        const val NOTIF_ID = 1
        const val ACTION_START = "com.jarvis.assistant.START"
        const val ACTION_STOP = "com.jarvis.assistant.STOP"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var recognizer: SpeechRecognizer
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var tone: ToneGenerator? = null
    private var beeped = false

    override fun onCreate() {
        super.onCreate()
        Speaker.init(this)
        createChannel()
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
            handler.postDelayed({ startListening() }, 300)
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
        }
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            restart(500)
        }
    }

    private fun restart(delayMs: Long) {
        handler.postDelayed({ startListening() }, delayMs)
    }

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {
            beeped = false
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            beeped = false
            restart(300)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (beeped) return
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: return
            if (matches.any { it.lowercase().contains("джарвис") }) {
                beeped = true
                tone?.startTone(ToneGenerator.TONE_PROP_ACK, 180)
                updateNotification("Говорите команду...")
            }
        }

        override fun onResults(results: Bundle?) {
            beeped = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val heard = matches[0].lowercase()
                if (heard.contains("джарвис")) {
                    val command = heard
                        .replace("джарвис", " ")
                        .replace(Regex("[^a-zа-я0-9 ]"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    if (command.isNotEmpty()) {
                        updateNotification("Команда: " + command)
                        CommandProcessor.execute(this@VoiceService, command)
                    }
                }
            }
            updateNotification("Джарвис слушает...")
            restart(300)
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
        Speaker.shutdown()
        tone?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
