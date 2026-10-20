package com.ull.dpi

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.RemoteViews

/*
 * Ana ekran widget'ları (2x2). Dört çeşit:
 *
 *   FULL  ↓ indirme + ↑ yükleme + ping + başlat/durdur
 *   DOWN  büyük ↓ indirme + başlat/durdur
 *   UP    büyük ↑ yükleme + başlat/durdur
 *   DUAL  ↓ + ↑, düğmesiz (dokununca uygulama açılır)
 *
 * Widget'ın kendi zamanlayıcısı yok: VPN açıkken (ya da uygulama ekrandayken)
 * Ticker saniyede bir hepsini yeniliyor. VPN kapalıyken son durum kalıyor.
 */
open class SpeedWidget(private val kind: Widgets.Kind) : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, Widgets.build(ctx, kind))
    }
}

class WidgetFull : SpeedWidget(Widgets.Kind.FULL)
class WidgetDown : SpeedWidget(Widgets.Kind.DOWN)
class WidgetUp : SpeedWidget(Widgets.Kind.UP)
class WidgetDual : SpeedWidget(Widgets.Kind.DUAL)

object Widgets {
    enum class Kind(val layout: Int, val provider: Class<*>, val button: Boolean) {
        FULL(R.layout.widget_full, WidgetFull::class.java, true),
        DOWN(R.layout.widget_down, WidgetDown::class.java, true),
        UP(R.layout.widget_up, WidgetUp::class.java, true),
        DUAL(R.layout.widget_dual, WidgetDual::class.java, false),
    }

    private const val RED = 0xFFFF2A3D.toInt()
    private const val GREY = 0xFF8C8C8C.toInt()

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        for (k in Kind.values()) {
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(ctx, k.provider)) }.getOrNull()
            if (ids == null || ids.isEmpty()) continue
            runCatching { mgr.updateAppWidget(ids, build(ctx, k)) }
        }
    }

    fun build(ctx: Context, k: Kind): RemoteViews {
        val v = RemoteViews(ctx.packageName, k.layout)
        val on = VpnState.running
        val wait = VpnState.starting
        // Ölçüm sadece Ticker çalışırken taze; değilse çizgi göster.
        val live = Ticker.active

        val brand = SpannableString("ULL::DPI")
        brand.setSpan(ForegroundColorSpan(RED), 3, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.setTextViewText(R.id.w_brand, brand)

        v.setTextViewText(R.id.w_state, if (wait) "◌ BAĞLANIYOR" else if (on) "● AKTİF" else "○ KAPALI")
        v.setTextColor(R.id.w_state, if (on) RED else GREY)

        val down = if (live) Speed.fmt(Speed.down) else "—"
        val up = if (live) Speed.fmt(Speed.up) else "—"
        if (k != Kind.UP) v.setTextViewText(R.id.w_down, down)
        if (k != Kind.DOWN) v.setTextViewText(R.id.w_up, up)
        v.setTextViewText(R.id.w_ping, "PING " + (if (live && Speed.ping >= 0) "${Speed.ping}" else "—") + " ms")

        val open = PendingIntent.getActivity(
            ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        v.setOnClickPendingIntent(R.id.w_root, open)

        if (k.button) {
            v.setTextViewText(R.id.w_btn, if (wait) "[ . . . ]" else if (on) "[ DURDUR ]" else "[ BAŞLAT ]")
            v.setTextColor(R.id.w_btn, if (on) Color.WHITE else 0xFFF0F0F0.toInt())
            v.setInt(R.id.w_btn, "setBackgroundResource", if (on) R.drawable.widget_btn_on else R.drawable.widget_btn_off)
            v.setOnClickPendingIntent(R.id.w_btn, toggle(ctx, on || wait))
        }
        return v
    }

    /** Başlat/durdur. VPN izni hiç verilmemişse önce uygulama açılıp izin istenir. */
    private fun toggle(ctx: Context, stop: Boolean): PendingIntent {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        if (!stop && VpnService.prepare(ctx) != null) {
            val i = Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(ctx, 11, i, flags)
        }
        val i = DpiVpnService.intent(ctx, if (stop) DpiVpnService.ACTION_STOP else DpiVpnService.ACTION_START)
        // Widget'a dokunmak, arka plandan ön plan servisi başlatma iznini veriyor.
        return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(ctx, 12, i, flags)
        else PendingIntent.getService(ctx, 12, i, flags)
    }
}
