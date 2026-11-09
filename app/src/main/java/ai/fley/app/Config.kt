package ai.fley.app

import android.content.Context
import org.json.JSONArray

/** Настройки: assets/defaults.env (вшито при сборке) < то, что пользователь сохранил в НАСТРОЙКАХ. */
class Config(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("fley", Context.MODE_PRIVATE)
    private val defaults: Map<String, String> = try {
        parseEnv(ctx.assets.open("defaults.env").bufferedReader(Charsets.UTF_8).use { it.readText() })
    } catch (e: Exception) {
        emptyMap()
    }

    fun get(k: String, def: String = ""): String {
        val v = prefs.getString(k, null)
        if (!v.isNullOrBlank()) return v.trim()
        val d = defaults[k]
        return if (!d.isNullOrBlank()) d.trim() else def
    }

    fun set(k: String, v: String) {
        prefs.edit().putString(k, v.trim()).apply()
    }

    fun resetAll() {
        prefs.edit().clear().apply()
    }

    val key: String
        get() = get("FLEY_API_KEY", get("GROQ_API_KEY", get("OPENAI_API_KEY", get("OPENROUTER_API_KEY"))))
    val groq: Boolean get() = key.startsWith("gsk_")
    val openrouter: Boolean get() = key.startsWith("sk-or-")
    val baseUrl: String
        get() = get(
            "FLEY_BASE_URL",
            if (groq) "https://api.groq.com/openai/v1" else if (openrouter) "https://openrouter.ai/api/v1" else "https://api.openai.com/v1"
        ).trimEnd('/')
    val defaultModel: String get() = if (groq) "openai/gpt-oss-120b" else if (openrouter) "openai/gpt-4o-mini" else "gpt-4o-mini"
    val defaultStt: String get() = if (groq) "whisper-large-v3" else if (openrouter) "openai/whisper-large-v3" else "whisper-1"
    val model: String get() = get("FLEY_MODEL", defaultModel)
    val stt: String get() = get("FLEY_STT_MODEL", defaultStt)
    val sttLang: String get() = get("FLEY_STT_LANG", "").let { if (it == "auto") "" else it }
    val mode: Int
        get() = when (get("FLEY_MODE", "smart").lowercase()) {
            "name", "0" -> 0
            "always", "all", "2" -> 2
            else -> 1
        }
    val wake: String get() = get("FLEY_WAKE_WORDS", "флей,флэй,флай,фляй,флея,fley,flay")
    val silence: Double get() = (get("FLEY_SILENCE_SECONDS", "2.5").toDoubleOrNull() ?: 2.5).coerceIn(0.8, 15.0)
    val followup: Double get() = (get("FLEY_FOLLOWUP_SECONDS", "25").toDoubleOrNull() ?: 25.0).coerceIn(0.0, 300.0)
    val threshold: Double get() = (get("FLEY_VOICE_THRESHOLD", "0").toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
    val phoneControl: Boolean get() = get("FLEY_PHONE_CONTROL", "1") != "0"
    val callMe: String get() = get("FLEY_CALL_ME", "")
    val pitch: Float get() = (get("FLEY_VOICE_PITCH", "0.88").toFloatOrNull() ?: 0.88f).coerceIn(0.5f, 2f)
    val rate: Float get() = (get("FLEY_VOICE_RATE", "1.0").toFloatOrNull() ?: 1.0f).coerceIn(0.5f, 2f)

    companion object {
        fun parseEnv(text: String): Map<String, String> {
            val m = HashMap<String, String>()
            for (raw in text.removePrefix("\uFEFF").lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || !line.contains('=')) continue
                val i = line.indexOf('=')
                m[line.substring(0, i).trim()] = line.substring(i + 1).trim().trim('"', '\'')
            }
            return m
        }
    }
}

/** Память: факты о пользователе, которые FLEY запоминает между запусками. */
class Memory(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("fley_mem", Context.MODE_PRIVATE)

    fun all(): List<String> {
        val s = prefs.getString("facts", "[]") ?: "[]"
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<String>) {
        prefs.edit().putString("facts", JSONArray(list.takeLast(40)).toString()).apply()
    }

    fun add(fact: String) {
        val f = fact.trim().take(200)
        if (f.isEmpty()) return
        val l = all().toMutableList()
        if (l.none { it.equals(f, true) }) l.add(f)
        save(l)
    }

    fun forget(what: String): Int {
        val w = what.trim().lowercase()
        if (w.isEmpty()) return 0
        val l = all()
        val keep = l.filter { !it.lowercase().contains(w) }
        save(keep)
        return l.size - keep.size
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
