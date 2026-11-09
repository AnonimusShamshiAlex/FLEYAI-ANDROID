package ai.fley.app

import java.io.ByteArrayOutputStream
import java.util.Locale

/** Текстовые помощники: имя помощника, фильтр шумных «галлюцинаций», ответ IGNORE. */
object Txt {
    fun lower(s: String): String = s.lowercase(Locale.ROOT).replace('ё', 'е')

    fun hasCyrillic(s: String): Boolean = s.any { it in '\u0400'..'\u04FF' }

    fun containsWake(text: String, words: String): Boolean {
        val t = lower(text)
        for (w0 in words.split(',', ';')) {
            val w = lower(w0.trim())
            if (w.isEmpty()) continue
            var at = t.indexOf(w)
            while (at >= 0) {
                if (at == 0 || !t[at - 1].isLetterOrDigit()) return true
                at = t.indexOf(w, at + 1)
            }
        }
        return false
    }

    /** Убирает слово-обращение в начале фразы: «Флей, открой ютуб» -> «открой ютуб». */
    fun stripWake(text: String, words: String): String {
        var t = text.trim()
        for (w0 in words.split(',', ';')) {
            val w = w0.trim()
            if (w.isEmpty()) continue
            if (t.lowercase(Locale.ROOT).replace('ё', 'е').startsWith(lower(w))) {
                t = t.substring(minOf(w.length, t.length)).trimStart(',', ' ', '.', '!', '-', ':')
                break
            }
        }
        return t
    }

    private val halluc = listOf(
        "субтитр", "dimatorzok", "продолжение следует", "спасибо за просмотр", "спасибо за внимание",
        "подписывайтесь", "не забудьте подписаться", "thanks for watching", "thank you for watching",
        "amara.org", "редактор субтитров"
    )
    private val exact = setOf("you", "thank you", "bye", "ok", "угу", "хм", "эм", "ммм", "ага")

    fun isHallucination(text: String): Boolean {
        val t = lower(text.trim())
        val core = t.filter { it.isLetterOrDigit() || it == ' ' }.trim()
        if (core.length < 2) return true
        if (halluc.any { t.contains(it) }) return true
        return core in exact
    }

    fun isIgnore(reply: String): Boolean {
        val t = reply.trim().uppercase(Locale.ROOT)
        if (!t.startsWith("IGNORE")) return false
        return t.length == 6 || !t[6].isLetterOrDigit()
    }
}

/** Режет непрерывный поток блоков по 50 мс на реплики (порт из версии для ПК). */
class Segmenter(var pauseS: Double = 2.5, var fixedThr: Double = 0.0) {
    enum class Ev { NONE, STARTED, ENDED }

    private var n = 0
    private var noise = 0.0
    private var run = 0
    private var blocks = 0
    var inSpeech = false
        private set
    var voiced = 0
        private set
    var quiet = 0
        private set

    fun threshold(): Double = if (fixedThr > 0) fixedThr else (noise * 3.0).coerceIn(300.0, 2000.0)
    fun quietS(): Double = quiet * BLOCK
    fun secondsLeft(): Int = Math.ceil(pauseS - quietS()).toInt()

    fun feed(rms: Double): Ev {
        n++
        if (n <= CALIB) {
            noise += rms / CALIB
            return Ev.NONE
        }
        val thr = threshold()
        if (!inSpeech) {
            if (rms < thr) noise = noise * 0.98 + rms * 0.02
            if (rms > thr) {
                if (++run >= 2) {
                    inSpeech = true
                    voiced = run
                    quiet = 0
                    blocks = run
                    return Ev.STARTED
                }
            } else run = 0
            return Ev.NONE
        }
        blocks++
        if (rms > thr) {
            voiced++
            quiet = 0
        } else quiet++
        if (quiet * BLOCK >= pauseS || blocks * BLOCK >= MAX_UTT) return finish()
        return Ev.NONE
    }

    fun forceEnd(): Ev = if (inSpeech) finish() else Ev.NONE

    fun reset() {
        inSpeech = false
        run = 0
        quiet = 0
        voiced = 0
        blocks = 0
    }

    private fun finish(): Ev {
        inSpeech = false
        run = 0
        return Ev.ENDED
    }

    companion object {
        const val BLOCK = 0.05
        const val CALIB = 10
        const val MAX_UTT = 60.0
    }
}

object Wav {
    fun make(pcm: ByteArray, rate: Int = 16000): ByteArray {
        val o = ByteArrayOutputStream(pcm.size + 44)
        fun i32(v: Int) { for (k in 0..3) o.write((v shr (8 * k)) and 0xFF) }
        fun i16(v: Int) { for (k in 0..1) o.write((v shr (8 * k)) and 0xFF) }
        o.write("RIFF".toByteArray()); i32(36 + pcm.size)
        o.write("WAVEfmt ".toByteArray()); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
        o.write("data".toByteArray()); i32(pcm.size)
        o.write(pcm)
        return o.toByteArray()
    }
}
