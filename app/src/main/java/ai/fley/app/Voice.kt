package ai.fley.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.math.sqrt

/** Постоянное прослушивание микрофона: режет поток на реплики и отдаёт их наверх. */
class Listener(
    private val cfg: Config,
    private val skip: () -> Boolean,                    // true: FLEY говорит или думает, не слушаем
    private val onLevel: (Float, Float) -> Unit,        // уровень и порог 0..1
    private val onPhase: (Boolean, Int) -> Unit,        // идёт ли речь, секунд до ответа (или -1)
    private val onUtterance: (ByteArray) -> Boolean,
    private val onError: (String) -> Unit
) {
    @Volatile var running = false
        private set
    @Volatile private var finish = false
    private var thread: Thread? = null

    fun finishNow() { finish = true }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "fley-listen").also { it.start() }
    }

    fun stop() { running = false }

    private fun loop() {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate * 2))
        } catch (e: Exception) {
            running = false
            onError("Не удалось открыть микрофон: " + (e.message ?: ""))
            return
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            running = false
            ar.release()
            onError("Микрофон недоступен. Разрешите доступ к микрофону в настройках телефона.")
            return
        }
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(ar.audioSessionId) } catch (e: Exception) {}
        try { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(ar.audioSessionId) } catch (e: Exception) {}
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(ar.audioSessionId) } catch (e: Exception) {}

        val blk = 800  // 50 мс
        val buf = ShortArray(blk)
        val seg = Segmenter(cfg.silence, cfg.threshold)
        val pre = ArrayDeque<ShortArray>()
        val cur = ArrayList<ShortArray>()
        var inSeg = false
        ar.startRecording()
        try {
            while (running) {
                val n = ar.read(buf, 0, blk)
                if (n <= 0) { SystemClock.sleep(10); continue }
                val b = buf.copyOf(n)
                var acc = 0.0
                for (v in b) acc += v.toDouble() * v
                val rms = sqrt(acc / n)
                onLevel(minOf(1f, (rms / 3000.0).toFloat()), minOf(1f, (seg.threshold() / 3000.0).toFloat()))

                if (skip()) {
                    seg.reset(); pre.clear(); cur.clear(); inSeg = false
                    continue
                }
                seg.pauseS = cfg.silence
                seg.fixedThr = cfg.threshold
                var ev = seg.feed(rms)
                if (finish) {
                    finish = false
                    if (inSeg && ev != Segmenter.Ev.ENDED) ev = seg.forceEnd()
                }
                pre.addLast(b)
                while (pre.size > 8) pre.removeFirst()
                if (ev == Segmenter.Ev.STARTED) {
                    cur.clear(); cur.addAll(pre); inSeg = true
                } else if (inSeg) cur.add(b)

                if (ev == Segmenter.Ev.ENDED) {
                    inSeg = false
                    var tail = seg.quiet
                    while (tail > 10 && cur.isNotEmpty()) { cur.removeAt(cur.size - 1); tail-- }
                    if (seg.voiced >= 6) {
                        var total = 0
                        for (c in cur) total += c.size
                        val bytes = ByteArray(total * 2)
                        var p = 0
                        for (c in cur) for (v in c) {
                            bytes[p++] = (v.toInt() and 0xFF).toByte()
                            bytes[p++] = ((v.toInt() shr 8) and 0xFF).toByte()
                        }
                        onUtterance(Wav.make(bytes, rate))
                    }
                    cur.clear(); pre.clear()
                    onPhase(false, -1)
                    continue
                }
                onPhase(inSeg, if (inSeg && seg.quietS() > 1.0) seg.secondsLeft() else -1)
            }
        } finally {
            try { ar.stop() } catch (e: Exception) {}
            ar.release()
        }
    }
}

/** Озвучка голосом Android (бесплатно, без интернета). */
class Speaker(ctx: Context, private val cfg: Config) {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val callbacks = HashMap<String, () -> Unit>()
    private var counter = 0

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { fire(id) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { fire(id) }
                override fun onError(id: String?, code: Int) { fire(id) }
                override fun onStop(id: String?, interrupted: Boolean) { fire(id) }
            })
        }
    }

    private fun fire(id: String?) {
        val cb = synchronized(callbacks) { callbacks.remove(id) }
        cb?.invoke()
    }

    /** Возвращает false, если озвучить не удалось (нет голоса). onDone вызывается по окончании. */
    fun speak(text: String, onDone: () -> Unit): Boolean {
        val t = tts
        if (t == null || !ready) return false
        val loc = if (Txt.hasCyrillic(text)) Locale("ru", "RU") else Locale.US
        val r = t.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return false
        try {
            val male = t.voices?.filter {
                it.locale.language == loc.language && !it.isNetworkConnectionRequired &&
                    it.name.lowercase().let { n -> n.contains("male") && !n.contains("female") }
            }?.firstOrNull()
            if (male != null) t.voice = male
        } catch (e: Exception) {}
        t.setPitch(cfg.pitch)
        t.setSpeechRate(cfg.rate)
        val id = "u" + (counter++)
        synchronized(callbacks) { callbacks[id] = onDone }
        val ok = t.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (ok != TextToSpeech.SUCCESS) {
            synchronized(callbacks) { callbacks.remove(id) }
            return false
        }
        return true
    }

    fun stop() { tts?.stop() }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
