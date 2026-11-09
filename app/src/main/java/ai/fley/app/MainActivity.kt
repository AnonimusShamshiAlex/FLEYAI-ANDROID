package ai.fley.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var cfg: Config
    private lateinit var api: Api
    private lateinit var brain: Brain
    private lateinit var speaker: Speaker
    private lateinit var listener: Listener
    private lateinit var hud: HudView
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var micBtn: Button

    private val exec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var ignoreUntil = 0L
    @Volatile private var lastReply = 0L
    @Volatile private var wantListen = false
    private val logText = SpannableStringBuilder()

    private val cyan = Color.rgb(0, 229, 255)
    private val green = Color.rgb(70, 255, 170)
    private val amber = Color.rgb(255, 176, 32)
    private val gray = Color.rgb(120, 145, 165)
    private val red = Color.rgb(255, 84, 84)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Config(this)
        api = Api(cfg)
        val mem = Memory(this)
        brain = Brain(cfg, api, mem, PhoneActions(this))
        speaker = Speaker(this, cfg)
        window.statusBarColor = Color.rgb(3, 9, 18)
        window.navigationBarColor = Color.rgb(3, 9, 18)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.rgb(3, 9, 18))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(14), dp(8), dp(8), dp(4))
        val title = TextView(this)
        title.text = "F L E Y   A I"
        title.setTextColor(cyan)
        title.textSize = 20f
        title.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        top.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val settingsBtn = flatButton("НАСТРОЙКИ") { startActivity(Intent(this, SettingsActivity::class.java)) }
        top.addView(settingsBtn)
        root.addView(top)

        hud = HudView(this)
        hud.onOrb = { onOrbClick() }
        root.addView(hud, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.25f))

        status = TextView(this)
        status.setTextColor(Color.rgb(225, 244, 255))
        status.textSize = 13f
        status.typeface = Typeface.MONOSPACE
        status.gravity = Gravity.CENTER
        status.setPadding(dp(12), dp(2), dp(12), dp(6))
        status.text = "Нажмите на реактор, чтобы включить прослушивание"
        root.addView(status)

        scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.argb(170, 4, 16, 28))
        log = TextView(this)
        log.setTextColor(green)
        log.textSize = 14f
        log.typeface = Typeface.MONOSPACE
        log.setPadding(dp(12), dp(8), dp(12), dp(8))
        scroll.addView(log)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        lp.setMargins(dp(10), 0, dp(10), dp(6))
        root.addView(scroll, lp)

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.setPadding(dp(10), dp(0), dp(10), dp(10))
        input = EditText(this)
        input.hint = "FLEY> команда..."
        input.setHintTextColor(gray)
        input.setTextColor(Color.WHITE)
        input.typeface = Typeface.MONOSPACE
        input.setBackgroundColor(Color.rgb(2, 14, 26))
        input.setPadding(dp(12), dp(10), dp(12), dp(10))
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setSingleLine(true)
        input.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEND || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)) { sendTyped(); true } else false
        }
        bottom.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        micBtn = flatButton("MIC") { onOrbClick() }
        bottom.addView(micBtn)
        root.addView(bottom)
        setContentView(root)

        listener = Listener(
            cfg,
            skip = { busy.get() || SystemClock.elapsedRealtime() < ignoreUntil },
            onLevel = { l, _ -> hud.level = l },
            onPhase = { inSpeech, left -> onPhase(inSpeech, left) },
            onUtterance = { wav -> startJob(wav, null) },
            onError = { msg -> runOnUiThread { setStatus(msg, true); addLog(5, msg); setState(HudView.OFF); wantListen = false } }
        )
        addLog(4, "FLEY AI запущен. Нажмите на реактор, чтобы я слушал. Скажите «Флей, …» или дайте команду.")
        if (cfg.key.isEmpty()) {
            setStatus("Нужен API-ключ: откройте НАСТРОЙКИ (бесплатный ключ: console.groq.com).", true)
        }
    }

    private fun flatButton(text: String, click: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setTextColor(cyan)
        b.setBackgroundColor(Color.argb(40, 0, 229, 255))
        b.typeface = Typeface.MONOSPACE
        b.textSize = 12f
        b.setOnClickListener { click() }
        return b
    }

    // ---------------------------------------------------------------- управление
    private fun onOrbClick() {
        when (hud.state) {
            HudView.SPEAK -> speaker.stop()
            HudView.THINK -> setStatus("Подождите, я думаю…")
            HudView.REC -> listener.finishNow()
            else -> if (listener.running) stopListening() else startListening()
        }
    }

    private fun startListening() {
        if (cfg.key.isEmpty()) {
            setStatus("Нужен API-ключ: откройте НАСТРОЙКИ.", true)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        wantListen = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        listener.start()
        micBtn.text = "STOP"
        setState(HudView.STANDBY)
        setStatus(standbyText())
    }

    private fun stopListening() {
        wantListen = false
        listener.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        micBtn.text = "MIC"
        hud.level = 0f
        if (!busy.get()) setState(HudView.OFF)
        setStatus("Микрофон выключен. Нажмите на реактор, чтобы слушать.")
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (code == 1 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startListening()
        else setStatus("Без доступа к микрофону я не смогу слушать. Разрешите его в настройках телефона.", true)
    }

    override fun onPause() {
        super.onPause()
        // Android не даёт слушать в фоне без отдельной службы: останавливаемся, а при возврате продолжим
        val was = wantListen
        if (listener.running) { listener.stop(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        wantListen = was
    }

    override fun onResume() {
        super.onResume()
        if (wantListen && !listener.running) startListening()
        else if (!wantListen) micBtn.text = "MIC"
    }

    override fun onDestroy() {
        super.onDestroy()
        listener.stop()
        speaker.shutdown()
        exec.shutdownNow()
    }

    private fun standbyText() = when (cfg.mode) {
        0 -> "Слушаю. Обращайтесь по имени: «Флей, …»"
        2 -> "Слушаю. Отвечаю на всё, что вы скажете"
        else -> "Слушаю окружение. Скажите «Флей, …» или дайте команду"
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        if (cfg.key.isEmpty()) { setStatus("Нужен API-ключ: откройте НАСТРОЙКИ.", true); return }
        if (!startJob(null, t)) setStatus("Я ещё занят предыдущей командой, подождите секунду…")
    }

    // ---------------------------------------------------------------- интерфейс
    private fun setState(s: Int) = runOnUiThread { hud.state = s }

    private fun setStatus(t: String, err: Boolean = false) = runOnUiThread {
        status.text = t
        status.setTextColor(if (err) red else Color.rgb(225, 244, 255))
    }

    private var lastPhase = -2

    private fun onPhase(inSpeech: Boolean, left: Int) {
        if (busy.get()) return
        val key = if (!inSpeech) 0 else if (left < 0) 1 else 100 + left
        if (key == lastPhase) return
        lastPhase = key
        runOnUiThread {
            hud.state = if (inSpeech) HudView.REC else HudView.STANDBY
            status.setTextColor(Color.rgb(225, 244, 255))
            status.text = if (key == 0) standbyText() else if (key == 1) "Слышу речь…" else "Пауза… отвечу через $left с (нажмите на реактор - сразу)"
        }
    }

    private fun addLog(role: Int, text: String) = runOnUiThread {
        val tag = when (role) { 0 -> "ВЫ  > "; 1 -> "FLEY: "; 3 -> "(фон) "; 5 -> "ERR : "; 6 -> "CMD > "; else -> "SYS : " }
        val color = when (role) { 0, 6 -> green; 1 -> Color.rgb(150, 238, 255); 3 -> gray; 5 -> red; else -> Color.rgb(90, 125, 155) }
        val start = logText.length
        logText.append(tag).append(text).append("\n\n")
        logText.setSpan(ForegroundColorSpan(color), start, logText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        log.text = logText
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- обработка реплики
    private fun startJob(wav: ByteArray?, typed: String?): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        exec.execute {
            try {
                handle(wav, typed)
            } catch (e: ApiError) {
                setStatus(e.message ?: "Ошибка", true); addLog(5, e.message ?: "Ошибка")
            } catch (e: Exception) {
                setStatus("Ошибка: " + (e.message ?: e.javaClass.simpleName), true)
            } finally {
                ignoreUntil = SystemClock.elapsedRealtime() + 700
                setState(if (listener.running) HudView.STANDBY else HudView.OFF)
                busy.set(false)
            }
        }
        return true
    }

    private fun handle(wav: ByteArray?, typed: String?) {
        var text = typed ?: ""
        var addressed = typed != null
        if (typed == null) {
            setState(HudView.THINK)
            setStatus("Распознаю речь…")
            text = api.stt(wav!!)
            if (text.isBlank() || Txt.isHallucination(text)) return
            val wake = Txt.containsWake(text, cfg.wake)
            val follow = lastReply != 0L && SystemClock.elapsedRealtime() - lastReply < cfg.followup * 1000
            addressed = wake || follow || cfg.mode == 2
            if (!addressed && cfg.mode == 0) { addLog(3, text); return }
            addLog(if (addressed) 0 else 3, text)
        } else addLog(6, text)

        setState(HudView.THINK)
        setStatus("Думаю…")
        var reply = if (addressed) brain.fast(text) else null
        if (reply == null) {
            val r = brain.respond(text, !addressed && cfg.mode == 1)
            if (r.ignored) { setStatus("Это не мне - молчу. " + standbyText()); return }
            reply = r.reply
        }
        addLog(1, reply)

        setState(HudView.SPEAK)
        setStatus("Говорю… (нажмите на реактор - прервать)")
        val done = CountDownLatch(1)
        if (!speaker.speak(reply) { done.countDown() }) {
            setStatus("Текст готов, но озвучить не вышло: в телефоне нет голоса. Настройки телефона - Язык - Синтез речи.", true)
        } else {
            done.await(90, TimeUnit.SECONDS)
        }
        lastReply = SystemClock.elapsedRealtime()
        setStatus(if (listener.running) standbyText() else "Готов. Нажмите на реактор, чтобы слушать.")
    }
}
