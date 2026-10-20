package com.ull.dpi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * Widget içeriğini bitmap olarak çiziyoruz: başlatıcılar uygulamanın kendi
 * yazı tipini (Doto) widget'ta göstermiyor, ibreli göstergeyi de RemoteViews
 * çizemiyor. Bitmap sadece ölçüm geldiğinde (5-10 sn) yenileniyor.
 */
object WidgetArt {
    private const val WHITE = 0xFFF2F2F2.toInt()
    private const val GREY = 0xFF8C8C8C.toInt()
    private const val DIM = 0xFF5A5A5A.toInt()
    private const val TRACK = 0xFF3A3A3A.toInt()
    private const val RED = 0xFFFF2A3D.toInt()

    private var doto: Typeface? = null
    private val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val monoReg: Typeface = Typeface.MONOSPACE

    private fun doto(ctx: Context): Typeface {
        doto?.let { return it }
        val t = if (Build.VERSION.SDK_INT >= 26) runCatching { ctx.resources.getFont(R.font.doto) }.getOrNull() else null
        return (t ?: mono).also { doto = it }
    }

    class Data(
        val on: Boolean,
        val wait: Boolean,
        val down: Double, // bit/sn, <0 = yok
        val up: Double,
        val ping: Int,
        /** Mobil veride ölçüm durduysa ping satırında bunu yaz. */
        val paused: Boolean = false,
    )

    @Synchronized
    fun draw(ctx: Context, kind: Widgets.Kind, w: Int, h: Int, d: Float, data: Data): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        pausedNow = data.paused
        val top = header(c, p, w, d, data)
        when (kind) {
            Widgets.Kind.FULL -> rows(ctx, c, p, w, h, d, top, data)
            Widgets.Kind.DOWN -> big(ctx, c, p, w, h, d, top, data.down, "↓", "İNDİRME · Mbps", RED, data.ping)
            Widgets.Kind.UP -> big(ctx, c, p, w, h, d, top, data.up, "↑", "YÜKLEME · Mbps", WHITE, data.ping)
            Widgets.Kind.DUAL -> dual(ctx, c, p, w, h, d, top, data)
            Widgets.Kind.METER -> meter(ctx, c, p, w, h, d, top, data)
        }
        return bmp
    }

    private fun fmt(bits: Double) = if (bits < 0) "—" else Speed.fmt(bits)
    private var pausedNow = false
    private fun pingText(ping: Int) =
        if (pausedNow) "MOBİL VERİ · DURDU" else "PING " + (if (ping >= 0) "$ping" else "—") + " ms"

    /** "ULL::DPI   ● AKTİF". Döner: başlığın alt kenarı. */
    private fun header(c: Canvas, p: Paint, w: Int, d: Float, data: Data): Float {
        p.typeface = mono
        p.textSize = 11 * d
        p.letterSpacing = 0.08f
        val y = 11 * d
        p.color = WHITE; c.drawText("ULL", 0f, y, p)
        var x = p.measureText("ULL")
        p.color = RED; c.drawText("::", x, y, p)
        x += p.measureText("::")
        p.color = WHITE; c.drawText("DPI", x, y, p)
        p.letterSpacing = 0f
        p.typeface = monoReg
        p.textSize = 9 * d
        val st = if (data.wait) "◌ BAĞLANIYOR" else if (data.on) "● AKTİF" else "○ KAPALI"
        p.color = if (data.on) RED else GREY
        c.drawText(st, w - p.measureText(st), y, p)
        return y + 6 * d
    }

    /** Yazıyı kutuya sığacak en büyük boyuta getir. */
    private fun fit(p: Paint, text: String, maxW: Float, maxH: Float) {
        p.textSize = maxH
        val tw = p.measureText(text)
        if (tw > maxW) p.textSize = maxH * maxW / tw
    }

    private fun small(c: Canvas, p: Paint, text: String, x: Float, y: Float, d: Float, color: Int = GREY) {
        p.typeface = monoReg
        p.textSize = 9 * d
        p.color = color
        c.drawText(text, x, y, p)
    }

    /** Renkli ok + gri etiket: "↓ İNDİRME · Mbps". */
    private fun label(c: Canvas, p: Paint, arrow: String, text: String, arrowCol: Int, y: Float, d: Float) {
        small(c, p, arrow, 0f, y, d, arrowCol)
        small(c, p, text, p.measureText("$arrow "), y, d, 0xFFB8B8B8.toInt())
    }

    private fun rows(ctx: Context, c: Canvas, p: Paint, w: Int, h: Int, d: Float, top: Float, data: Data) {
        val pingH = 13 * d
        val rowH = (h - top - pingH) / 2
        for ((i, pair) in listOf(Triple("↓", data.down, RED), Triple("↑", data.up, WHITE)).withIndex()) {
            val (arrow, v, col) = pair
            val base = top + rowH * (i + 1) - rowH * 0.18f
            p.typeface = mono; p.textSize = 14 * d; p.color = col
            c.drawText(arrow, 0f, base, p)
            val ax = p.measureText(arrow) + 5 * d
            p.typeface = monoReg; p.textSize = 9 * d
            val unitW = p.measureText("Mbps")
            p.color = GREY
            c.drawText("Mbps", w - unitW, base, p)
            p.typeface = doto(ctx); p.color = WHITE
            fit(p, fmt(v), w - ax - unitW - 6 * d, rowH * 0.8f)
            c.drawText(fmt(v), ax, base, p)
        }
        small(c, p, pingText(data.ping), 0f, h - 2 * d, d)
    }

    private fun big(ctx: Context, c: Canvas, p: Paint, w: Int, h: Int, d: Float, top: Float,
                    v: Double, arrow: String, text: String, arrowCol: Int, ping: Int) {
        val bottom = h - 15 * d - 13 * d
        p.typeface = doto(ctx); p.color = WHITE
        fit(p, fmt(v), w.toFloat(), (bottom - top) * 0.92f)
        c.drawText(fmt(v), 0f, bottom - (bottom - top - p.textSize) / 2, p)
        label(c, p, arrow, text, arrowCol, bottom + 12 * d, d)
        small(c, p, pingText(ping), 0f, h - 2 * d, d)
    }

    private fun dual(ctx: Context, c: Canvas, p: Paint, w: Int, h: Int, d: Float, top: Float, data: Data) {
        val pingH = 13 * d
        val block = (h - top - pingH) / 2
        listOf(Triple(data.down, "↓", "İNDİRME · Mbps"), Triple(data.up, "↑", "YÜKLEME · Mbps"))
            .forEachIndexed { i, (v, arrow, text) ->
                val y0 = top + block * i
                val labelY = y0 + block - 3 * d
                p.typeface = doto(ctx); p.color = WHITE
                fit(p, fmt(v), w.toFloat(), (block - 14 * d) * 0.95f)
                c.drawText(fmt(v), 0f, labelY - 12 * d, p)
                label(c, p, arrow, text, if (i == 0) RED else WHITE, labelY, d)
            }
        small(c, p, pingText(data.ping), 0f, h - 2 * d, d)
    }

    /** Ookla tarzı ibreli gösterge (uygulamadakiyle aynı ölçek). */
    private fun meter(ctx: Context, c: Canvas, p: Paint, w: Int, h: Int, d: Float, top: Float, data: Data) {
        val foot = 13 * d
        val areaH = h - top - foot
        val r = min(w / 2f, areaH / 1.5f) * 0.92f
        val stroke = r * 0.13f
        val cx = w / 2f
        val cy = top + r + stroke / 2 + 2 * d
        val m = if (data.down < 0) 0.0 else data.down / 1e6
        val scale = if (m > 100) listOf(0, 5, 10, 50, 100, 250, 500, 750, 1000) else listOf(0, 1, 5, 10, 20, 30, 50, 75, 100)
        val a0 = 150f
        val sweep = 240f
        fun angle(v: Double): Float {
            val n = scale.size - 1
            if (v >= scale[n]) return a0 + sweep
            var i = 0
            while (i < n - 1 && v >= scale[i + 1]) i++
            val f = ((v - scale[i]) / (scale[i + 1] - scale[i])).coerceIn(0.0, 1.0)
            return a0 + sweep * ((i + f) / n).toFloat()
        }
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = stroke
        p.color = TRACK
        c.drawArc(oval, a0, sweep, false, p)
        val a = angle(m)
        if (a > a0 + 0.5f) {
            p.color = RED
            c.drawArc(oval, a0, a - a0, false, p)
        }
        // Ölçek çizgileri
        p.strokeWidth = 1.5f * d
        scale.forEachIndexed { i, v ->
            val ta = Math.toRadians((a0 + sweep * i / (scale.size - 1)).toDouble())
            val r1 = r - stroke / 2 - 2 * d
            val r2 = r1 - 4 * d
            p.color = if (v <= m) WHITE else DIM
            c.drawLine(cx + r1 * cos(ta).toFloat(), cy + r1 * sin(ta).toFloat(), cx + r2 * cos(ta).toFloat(), cy + r2 * sin(ta).toFloat(), p)
        }
        // İbre: merkezden değil, ortadaki rakamı kapatmasın
        val na = Math.toRadians(a.toDouble())
        p.strokeWidth = 2.5f * d
        p.color = WHITE
        val n1 = r * 0.55f
        val n2 = r - stroke / 2 - 7 * d
        c.drawLine(cx + n1 * cos(na).toFloat(), cy + n1 * sin(na).toFloat(), cx + n2 * cos(na).toFloat(), cy + n2 * sin(na).toFloat(), p)
        p.style = Paint.Style.FILL

        // Değer + birim
        p.typeface = doto(ctx); p.color = WHITE
        val txt = fmt(data.down)
        fit(p, txt, r * 1.0f, r * 0.55f)
        p.textAlign = Paint.Align.CENTER
        c.drawText(txt, cx, cy + p.textSize * 0.3f, p)
        p.typeface = monoReg; p.textSize = 8 * d; p.color = GREY
        c.drawText("↓ Mbps", cx, cy + r * 0.62f, p)
        p.textAlign = Paint.Align.LEFT
        small(c, p, if (data.paused) "MOBİL VERİ · DURDU" else "↑ ${fmt(data.up)} Mbps · ${if (data.ping >= 0) data.ping else "—"} ms", 0f, h - 2 * d, d)
    }
}
