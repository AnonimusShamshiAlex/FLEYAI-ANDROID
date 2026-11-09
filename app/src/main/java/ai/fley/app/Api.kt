package ai.fley.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiError(msg: String) : Exception(msg)

/** Клиент Groq / OpenRouter / OpenAI (все говорят на одном протоколе). */
class Api(private val cfg: Config) {
    private class Res(val code: Int, val body: String)

    private fun post(path: String, contentType: String, body: ByteArray): Res {
        try {
            val c = URL(cfg.baseUrl + path).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 90000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer " + cfg.key)
            c.setRequestProperty("Content-Type", contentType)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Res(code, text)
        } catch (e: IOException) {
            throw ApiError("Нет связи с сервером. Проверьте интернет.")
        }
    }

    fun stt(wav: ByteArray): String {
        val b = "----FleyBoundary7MA4YWxkTrZu0gW"
        val o = ByteArrayOutputStream()
        fun field(n: String, v: String) {
            o.write("--$b\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray())
        }
        field("model", cfg.stt)
        field("temperature", "0")
        if (cfg.sttLang.isNotEmpty()) field("language", cfg.sttLang)
        o.write("--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        o.write(wav)
        o.write("\r\n--$b--\r\n".toByteArray())
        val r = post("/audio/transcriptions", "multipart/form-data; boundary=$b", o.toByteArray())
        if (r.code !in 200..299) throw ApiError(explain(r.code, r.body))
        return try {
            JSONObject(r.body).optString("text", "").trim()
        } catch (e: Exception) {
            throw ApiError("Сервер вернул непонятный ответ при распознавании речи.")
        }
    }

    /** messages: пары (роль, текст). Если у модели закончился лимит (429), пробуем запасные. */
    fun chat(messages: List<Pair<String, String>>): String {
        val models = ArrayList<String>()
        models.add(cfg.model)
        if (cfg.groq && cfg.get("FLEY_MODEL").isEmpty()) {
            for (m in listOf("openai/gpt-oss-20b", "llama-3.3-70b-versatile", "llama-3.1-8b-instant"))
                if (m !in models) models.add(m)
        }
        var last = ""
        for ((i, m) in models.withIndex()) {
            val arr = JSONArray()
            for ((role, content) in messages) arr.put(JSONObject().put("role", role).put("content", content))
            val req = JSONObject().put("model", m).put("messages", arr)
            req.put(if (cfg.openrouter) "max_tokens" else "max_completion_tokens", 700)
            if (cfg.groq && m.contains("gpt-oss")) req.put("reasoning_effort", "low")
            val r = post("/chat/completions", "application/json", req.toString().toByteArray(Charsets.UTF_8))
            if (r.code in 200..299) {
                return try {
                    JSONObject(r.body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                        .optString("content", "").trim()
                } catch (e: Exception) {
                    throw ApiError("Сервер вернул непонятный ответ.")
                }
            }
            last = explain(r.code, r.body)
            val retry = r.code == 429 || r.code == 404 || r.code == 503
            if (!retry || i == models.size - 1) break
        }
        throw ApiError(last)
    }

    private fun message(body: String): String = try {
        val e = JSONObject(body).opt("error")
        when (e) {
            is JSONObject -> e.optString("message")
            is String -> e
            else -> ""
        }.take(160)
    } catch (ex: Exception) {
        body.take(120)
    }

    private fun explain(code: Int, body: String): String {
        val low = body.lowercase()
        val money = low.contains("quota") || low.contains("billing") || low.contains("insufficient") || low.contains("credits")
        return when (code) {
            401 -> "Неверный API-ключ. Откройте НАСТРОЙКИ и вставьте правильный ключ."
            402 -> "Недостаточно кредитов на аккаунте API."
            403 -> "Нет доступа к модели или региону (попробуйте VPN). " + message(body)
            404 -> "Модель не найдена. Проверьте названия моделей в НАСТРОЙКАХ."
            429 -> if (money) "Ошибка 429: закончились деньги или лимит на аккаунте API."
            else "Лимит запросов исчерпан. Подождите минуту и повторите."
            400 -> "Запрос отклонён: " + message(body)
            else -> if (code >= 500) "Сервер временно недоступен ($code). Попробуйте ещё раз." else "Ошибка HTTP $code: " + message(body)
        }
    }
}
