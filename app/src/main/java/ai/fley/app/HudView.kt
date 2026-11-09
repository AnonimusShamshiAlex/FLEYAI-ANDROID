package ai.fley.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Реактор в стиле HUD: кольца, шкала и «эквалайзер», реагирующий на голос. Нажатие включает/выключает прослушивание. */
class HudView(ctx: Context) : View(ctx) {
    companion object {
        const val OFF = 0
        const val STANDBY = 1
        const val REC = 2
        const val THINK = 3
        const val SPEAK = 4
    }

    @Volatile var state = OFF
    @Volatile var level = 0f
    var onOrb: (() -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var lv = 0f

    private fun accent(): Int = when (state) {
        REC -> Color.rgb(70, 255, 170)
        THINK -> Color.rgb(255, 176, 32)
        SPEAK -> Color.rgb(120, 190, 255)
        STANDBY -> Color.rgb(0, 229, 255)
        else -> Color.rgb(90, 125, 155)
    }

    private fun a(c: Int, alpha: Int): Int = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = SystemClock.uptimeMillis() / 1000f
        c.drawColor(Color.rgb(3, 9, 18))
        val acc = accent()

        // сетка
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = a(Color.rgb(0, 229, 255), 14)
        val step = w / 9f
        var x = 0f
        while (x < w) { c.drawLine(x, 0f, x, h, p); x += step }
        var y = 0f
        while (y < h) { c.drawLine(0f, y, w, y, p); y += step }

        val target = when (state) {
            REC, STANDBY -> level
            SPEAK -> 0.35f + 0.25f * sin(t * 9f) + 0.15f * sin(t * 5.3f)
            THINK -> 0.15f + 0.05f * sin(t * 6f)
            else -> 0.03f + 0.02f * sin(t * 2f)
        }
        lv = min(0.9f, lv * 0.7f + maxOf(0f, target) * 0.3f)

        val cx = w / 2f
        val cy = h / 2f
        val R = min(w, h) * 0.2f
        val think = state == THINK

        p.style = Paint.Style.FILL
        for (i in 5 downTo 1) {  // свечение
            val r = R + i * R * 0.18f + lv * R * 0.2f
            p.color = a(acc, 6 + (6 - i) * 2)
            c.drawCircle(cx, cy, r, p)
        }

        // шкала
        val rot = t * 6f
        for (i in 0 until 72) {
            val ang = Math.toRadians((i * 5f + rot).toDouble())
            val major = i % 6 == 0
            val r1 = R * 1.7f
            val r2 = r1 + R * (if (major) 0.1f else 0.05f)
            p.style = Paint.Style.STROKE
            p.strokeWidth = if (major) 3f else 1.5f
            p.color = a(acc, if (major) 170 else 70)
            c.drawLine(cx + cos(ang).toFloat() * r1, cy + sin(ang).toFloat() * r1, cx + cos(ang).toFloat() * r2, cy + sin(ang).toFloat() * r2, p)
        }

        // пунктирное кольцо
        p.strokeWidth = 5f
        p.strokeCap = Paint.Cap.ROUND
        p.color = a(acc, 210)
        var r = R * 1.5f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        val rot2 = -t * (if (think) 90f else 22f)
        for (k in 0 until 6) c.drawArc(rect, rot2 + k * 60f, 36f, false, p)

        p.strokeWidth = 10f
        p.color = a(acc, 150)
        r = R * 1.28f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        val rot3 = t * (if (think) 240f else 40f)
        for (k in 0 until 3) c.drawArc(rect, rot3 + k * 120f, 80f, false, p)

        // «эквалайзер»
        p.strokeWidth = 4f
        p.color = a(acc, 215)
        for (i in 0 until 48) {
            val ang = Math.toRadians((i * 360.0 / 48))
            val amp = 0.45f + 0.55f * abs(sin(i * 0.6f + t * 5f))
            val len = R * 0.05f + lv * R * 0.5f * amp
            val r1 = R * 1.03f
            val r2 = r1 + len
            c.drawLine(cx + cos(ang).toFloat() * r1, cy + sin(ang).toFloat() * r1, cx + cos(ang).toFloat() * r2, cy + sin(ang).toFloat() * r2, p)
        }

        // ядро
        p.style = Paint.Style.FILL
        p.color = a(acc, 28 + (lv * 40).toInt())
        c.drawCircle(cx, cy, R * 0.82f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = a(acc, 230)
        c.drawCircle(cx, cy, R * 0.82f, p)

        // подпись состояния
        val label = when (state) {
            STANDBY -> "STANDBY"
            REC -> "REC"
            THINK -> "THINK"
            SPEAK -> "SPEAK"
            else -> "OFFLINE"
        }
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        p.textSize = R * 0.28f
        p.color = acc
        c.drawText(label, cx, cy + R * 0.1f, p)
        p.typeface = Typeface.MONOSPACE
        p.textSize = R * 0.14f
        p.color = Color.rgb(120, 145, 165)
        c.drawText(if (state == OFF) "нажмите" else if (state == SPEAK) "прервать" else "", cx, cy + R * 0.38f, p)

        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action == MotionEvent.ACTION_UP) {
            val dx = e.x - width / 2f
            val dy = e.y - height / 2f
            if (dx * dx + dy * dy < Math.pow(min(width, height) * 0.45, 2.0)) {
                performClick()
                onOrb?.invoke()
            }
            return true
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
