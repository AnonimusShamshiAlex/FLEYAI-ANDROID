package ai.fley.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Все настройки внутри приложения: никаких файлов, всё сохраняется на телефоне. */
class SettingsActivity : Activity() {
    private lateinit var cfg: Config
    private lateinit var box: LinearLayout
    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var modeSpin: Spinner
    private lateinit var phoneSw: Switch

    private val cyan = Color.rgb(0, 229, 255)
    private val gray = Color.rgb(120, 145, 165)
    private val modeKeys = listOf("smart", "name", "always")
    private val modeNames = listOf("СМАРТ: по имени и по командам", "ТОЛЬКО ПО ИМЕНИ", "ОТВЕЧАТЬ НА ВСЁ")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Config(this)
        window.statusBarColor = Color.rgb(3, 9, 18)
        window.navigationBarColor = Color.rgb(3, 9, 18)
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.rgb(3, 9, 18))
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(16), dp(16), dp(24))
        sv.addView(box)
        setContentView(sv)
        build()
    }

    private fun label(t: String, hint: String = "") {
        val l = TextView(this)
        l.text = t
        l.setTextColor(cyan)
        l.textSize = 13f
        l.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        l.setPadding(0, dp(14), 0, 0)
        box.addView(l)
        if (hint.isNotEmpty()) {
            val h = TextView(this)
            h.text = hint
            h.setTextColor(gray)
            h.textSize = 11f
            box.addView(h)
        }
    }

    private fun field(key: String, title: String, value: String, hint: String = "", number: Boolean = false) {
        label(title, hint)
        val e = EditText(this)
        e.setText(value)
        e.setTextColor(Color.WHITE)
        e.typeface = Typeface.MONOSPACE
        e.setBackgroundColor(Color.rgb(2, 14, 26))
        e.setPadding(dp(12), dp(10), dp(12), dp(10))
        e.setSingleLine(true)
        if (number) e.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        box.addView(e)
        fields[key] = e
    }

    private fun build() {
        val t = TextView(this)
        t.text = "НАСТРОЙКИ"
        t.setTextColor(cyan)
        t.textSize = 22f
        t.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        box.addView(t)

        field("FLEY_API_KEY", "API-КЛЮЧ", cfg.key, "Groq (gsk_...) бесплатно: console.groq.com  |  OpenRouter  |  OpenAI")
        field("FLEY_MODEL", "МОДЕЛЬ ОТВЕТОВ", cfg.model, "не меняйте, если не уверены")
        field("FLEY_STT_MODEL", "МОДЕЛЬ РАСПОЗНАВАНИЯ РЕЧИ", cfg.stt)
        field("FLEY_STT_LANG", "ЯЗЫК РЕЧИ", cfg.sttLang, "ru, en, uz или пусто = определять автоматически")
        field("FLEY_CALL_ME", "КАК К ВАМ ОБРАЩАТЬСЯ", cfg.callMe, "например: сэр, Али")

        label("РЕЖИМ РЕАКЦИИ", "как FLEY решает, что вы говорите именно ему")
        modeSpin = Spinner(this)
        modeSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modeNames)
        modeSpin.setSelection(cfg.mode.let { if (it == 0) 1 else if (it == 2) 2 else 0 })
        modeSpin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                (v as? TextView)?.setTextColor(Color.WHITE)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        box.addView(modeSpin)

        field("FLEY_WAKE_WORDS", "СЛОВА-ОБРАЩЕНИЯ", cfg.wake, "через запятую: флей, fley, джарвис")
        field("FLEY_SILENCE_SECONDS", "ПАУЗА ПОСЛЕ РЕЧИ, СЕК", cfg.silence.toString(), "через сколько секунд тишины FLEY отвечает", true)
        field("FLEY_FOLLOWUP_SECONDS", "ДИАЛОГ БЕЗ ИМЕНИ, СЕК", cfg.followup.toString(), "после ответа можно продолжать без слова «Флей»", true)
        field("FLEY_VOICE_THRESHOLD", "ЧУВСТВИТЕЛЬНОСТЬ МИКРОФОНА", cfg.threshold.toString(), "0 = авто; если шумно, поставьте 800-1500", true)
        field("FLEY_VOICE_PITCH", "ВЫСОТА ГОЛОСА", cfg.pitch.toString(), "0.5-2.0, ниже = глубже", true)
        field("FLEY_VOICE_RATE", "СКОРОСТЬ ГОЛОСА", cfg.rate.toString(), "0.5-2.0", true)

        label("УПРАВЛЕНИЕ ТЕЛЕФОНОМ", "открывать приложения, ютуб, звонки, будильник, фонарик...")
        phoneSw = Switch(this)
        phoneSw.text = "включено"
        phoneSw.setTextColor(Color.WHITE)
        phoneSw.isChecked = cfg.phoneControl
        box.addView(phoneSw)

        val save = button("СОХРАНИТЬ", Color.rgb(70, 255, 170)) { save() }
        val clearMem = button("ЗАБЫТЬ ВСЁ, ЧТО Я ЗАПОМНИЛ", Color.rgb(255, 176, 32)) {
            Memory(this).clear()
            Toast.makeText(this, "Память очищена", Toast.LENGTH_SHORT).show()
        }
        val reset = button("СБРОС К УМОЛЧАНИЯМ", Color.rgb(255, 84, 84)) {
            cfg.resetAll()
            Toast.makeText(this, "Сброшено", Toast.LENGTH_SHORT).show()
            finish()
        }
        box.addView(save)
        box.addView(clearMem)
        box.addView(reset)
    }

    private fun button(text: String, color: Int, click: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setTextColor(color)
        b.setBackgroundColor(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)))
        b.typeface = Typeface.MONOSPACE
        b.setOnClickListener { click() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(14)
        b.layoutParams = lp
        return b
    }

    private fun save() {
        val newKey = fields["FLEY_API_KEY"]!!.text.toString().trim()
        cfg.set("FLEY_API_KEY", newKey)
        // модели храним, только если они отличаются от умолчаний для этого ключа
        for ((k, e) in fields) {
            if (k == "FLEY_API_KEY") continue
            var v = e.text.toString().trim()
            if (k == "FLEY_MODEL" && v == cfg.defaultModel) v = ""
            if (k == "FLEY_STT_MODEL" && v == cfg.defaultStt) v = ""
            cfg.set(k, v)
        }
        cfg.set("FLEY_MODE", modeKeys[modeSpin.selectedItemPosition])
        cfg.set("FLEY_PHONE_CONTROL", if (phoneSw.isChecked) "1" else "0")
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        finish()
    }
}
