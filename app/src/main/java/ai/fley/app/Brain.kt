package ai.fley.app

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Brain(private val cfg: Config, private val api: Api, private val mem: Memory, private val actions: PhoneActions) {
    private val hist = ArrayList<Pair<String, String>>()

    class Result(val reply: String, val ignored: Boolean = false)

    fun clearHistory() = hist.clear()

    private fun system(gate: Boolean): String {
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.append("Ты - FLEY AI, личный голосовой ИИ-помощник на телефоне пользователя. ")
        sb.append("Стиль: спокойный, уверенный, ясный, уважительный. ")
        if (cfg.callMe.isNotEmpty()) sb.append("Обращайся к пользователю: ${cfg.callMe}. ")
        sb.append("Отвечай на языке пользователя (русский, узбекский, английский). Ответы озвучиваются голосом: ")
        sb.append("пиши коротко и разговорно, 1-3 предложения, без markdown, списков, эмодзи и ссылок. ")
        sb.append("Ты честно называешь себя ИИ, не выдаёшь догадки за факты и признаёшь, если чего-то не знаешь. ")
        if (cfg.phoneControl) {
            sb.append("Ты можешь управлять телефоном. Чтобы выполнить действие, добавь в конец ответа тег <act>{json}</act> (можно несколько). Доступные действия: ")
            sb.append("{\"type\":\"open_app\",\"name\":\"Telegram\"}, {\"type\":\"open_url\",\"url\":\"https://...\"}, ")
            sb.append("{\"type\":\"web_search\",\"query\":\"...\"}, {\"type\":\"youtube\",\"query\":\"...\"}, {\"type\":\"maps\",\"query\":\"...\"}, ")
            sb.append("{\"type\":\"call\",\"number\":\"+998...\"}, {\"type\":\"sms\",\"number\":\"...\",\"text\":\"...\"}, ")
            sb.append("{\"type\":\"alarm\",\"hour\":7,\"minute\":30,\"label\":\"...\"}, {\"type\":\"timer\",\"seconds\":300}, ")
            sb.append("{\"type\":\"volume\",\"direction\":\"up|down|max|mute\"}, {\"type\":\"flashlight\",\"on\":true}, ")
            sb.append("{\"type\":\"settings\",\"page\":\"wifi|bluetooth|display|sound\"}, {\"type\":\"status\"} (время и заряд батареи). ")
            sb.append("Звонок и SMS только открываются, пользователь подтверждает сам. Не говори, что выполнил действие, если не добавил тег. ")
        } else {
            sb.append("Сейчас ты умеешь только разговаривать и не управляешь телефоном. ")
        }
        sb.append("Чтобы запомнить факт о пользователе, добавь <act>{\"type\":\"remember\",\"fact\":\"...\"}</act>; ")
        sb.append("чтобы забыть - <act>{\"type\":\"forget\",\"what\":\"...\"}</act>. Запоминай только то, что пользователь просит или явно важное. ")
        val facts = mem.all()
        if (facts.isNotEmpty()) sb.append("Известные факты о пользователе: ").append(facts.joinToString("; ")).append(". ")
        if (gate) {
            sb.append("ВАЖНО: ты постоянно слушаешь речь вокруг. Тебя зовут FLEY (Флей). Реплика ниже могла быть обращена НЕ к тебе ")
            sb.append("(разговор людей, телевизор, музыка). Если это не обращение к тебе и не явная команда ассистенту - ответь ровно одним словом IGNORE. ")
        }
        sb.append("Сейчас $date.")
        return sb.toString()
    }

    /** Частые команды выполняем сразу, без ИИ: быстрее и экономит лимиты. */
    fun fast(raw: String): String? {
        if (!cfg.phoneControl) return null
        val t = Txt.lower(Txt.stripWake(raw, cfg.wake)).trim().trimEnd('.', '!', '?')
        Regex("^(?:включи|найди|поищи|запусти)\\s+(?:на\\s+ютубе|в\\s+ютубе|на\\s+youtube)\\s+(.+)$").find(t)?.let {
            val e = actions.run(JSONObject().put("type", "youtube").put("query", it.groupValues[1]))
            return e.ifEmpty { "Ищу на ютубе." }
        }
        Regex("^(?:открой|запусти|включи)\\s+(?:приложение\\s+)?(.+)$").find(t)?.let {
            val name = it.groupValues[1]
            if (name.split(' ').size <= 3) {
                val e = actions.openApp(name)
                return e.ifEmpty { "Открываю." }
            }
        }
        if (Regex("^(?:сделай\\s+)?(?:громче|прибавь звук|погромче)$").matches(t)) { actions.volume("up"); return "Громче." }
        if (Regex("^(?:сделай\\s+)?(?:тише|убавь звук|потише)$").matches(t)) { actions.volume("down"); return "Тише." }
        if (Regex("^включи фонарик$").matches(t)) return actions.flashlight(true).ifEmpty { "Фонарик включён." }
        if (Regex("^выключи фонарик$").matches(t)) return actions.flashlight(false).ifEmpty { "Фонарик выключен." }
        if (Regex("^(?:который час|сколько времени|сколько заряда|заряд батареи)$").matches(t)) return actions.status()
        return null
    }

    fun respond(text: String, gate: Boolean): Result {
        val msgs = ArrayList<Pair<String, String>>()
        msgs.add("system" to system(gate))
        msgs.addAll(hist.takeLast(10))
        msgs.add("user" to text)
        val raw = api.chat(msgs)
        if (gate && Txt.isIgnore(raw)) return Result("", true)

        var reply = raw
        val notes = ArrayList<String>()
        for (m in Regex("<act>(.*?)</act>", RegexOption.DOT_MATCHES_ALL).findAll(raw)) {
            reply = reply.replace(m.value, "")
            try {
                val a = JSONObject(m.groupValues[1])
                when (a.optString("type")) {
                    "remember" -> mem.add(a.optString("fact"))
                    "forget" -> mem.forget(a.optString("what"))
                    else -> if (cfg.phoneControl) {
                        val n = actions.run(a)
                        if (n.isNotEmpty()) notes.add(n)
                    }
                }
            } catch (e: Exception) {
                notes.add("Не удалось выполнить действие.")
            }
        }
        reply = reply.replace(Regex("[*_`#>]+"), "").trim()
        if (notes.isNotEmpty()) reply = (reply + " " + notes.joinToString(" ")).trim()
        if (reply.isEmpty()) reply = "Готово."
        hist.add("user" to text)
        hist.add("assistant" to reply)
        while (hist.size > 16) hist.removeAt(0)
        return Result(reply)
    }
}
