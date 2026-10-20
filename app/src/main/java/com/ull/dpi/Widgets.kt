package com.ull.dpi

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews

/*
 * Ana ekran widget'ları (2x2). Beş çeşit:
 *
 *   FULL   ↓ indirme + ↑ yükleme + ping + başlat/durdur
 *   DOWN   büyük ↓ indirme + başlat/durdur
 *   UP     büyük ↑ yükleme + başlat/durdur
 *   DUAL   ↓ + ↑, düğmesiz (dokununca uygulama açılır)
 *   METER  Ookla tarzı ibreli gösterge, düğmesiz
 *
 * Değerler Probe'dan geliyor (5-10 sn'de bir küçük ölçüm); telefon o an
 * internet kullanmasa da hattın hızı görünüyor. İçerik bitmap olarak çiziliyor
 * (bkz. WidgetArt), düğme gerçek bir TextView.
 */
open class SpeedWidget(private val kind: Widgets.Kind) : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { Widgets.update(ctx, mgr, kind, it) }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) {
        Widgets.update(ctx, mgr, kind, id) // boyut değişti: yeniden çiz
    }
}

class WidgetFull : SpeedWidget(Widgets.Kind.FULL)
class WidgetDown : SpeedWidget(Widgets.Kind.DOWN)
class WidgetUp : SpeedWidget(Widgets.Kind.UP)
class WidgetDual : SpeedWidget(Widgets.Kind.DUAL)
class WidgetMeter : SpeedWidget(Widgets.Kind.METER)

object Widgets {
    enum class Kind(val provider: Class<*>, val button: Boolean) {
        FULL(WidgetFull::class.java, true),
        DOWN(WidgetDown::class.java, true),
        UP(WidgetUp::class.java, true),
        DUAL(WidgetDual::class.java, false),
        METER(WidgetMeter::class.java, false),
    }

    private const val PAD_DP = 14
    private const val BTN_DP = 36 + 8

    /** Ana ekranda en az bir ULL-DPI widget'ı var mı? */
    fun any(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return false
        return Kind.values().any { k ->
            runCatching { mgr.getAppWidgetIds(ComponentName(ctx, k.provider)).isNotEmpty() }.getOrDefault(false)
        }
    }

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        for (k in Kind.values()) {
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(ctx, k.provider)) }.getOrNull() ?: continue
            ids.forEach { update(ctx, mgr, k, it) }
        }
    }

    fun update(ctx: Context, mgr: AppWidgetManager, k: Kind, id: Int) {
        runCatching { mgr.updateAppWidget(id, build(ctx, mgr, k, id)) }
    }

    private fun build(ctx: Context, mgr: AppWidgetManager, k: Kind, id: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, if (k.button) R.layout.widget_btn else R.layout.widget_img)
        val on = VpnState.running
        val wait = VpnState.starting

        // Widget'ın gerçek boyutu (dp). Dikey ekranda genişlik = min, yükseklik = max.
        val opts = mgr.getAppWidgetOptions(id)
        val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 160
        val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 160
        val imgWDp = wDp - 2 * PAD_DP
        val imgHDp = hDp - 2 * PAD_DP - (if (k.button) BTN_DP else 0)
        // Bitmap yoğunluğu: keskin ama widget IPC sınırını aşmayacak kadar.
        val d = minOf(ctx.resources.displayMetrics.density, 2.5f)
        val data = WidgetArt.Data(on, wait, Probe.down, Probe.up, Probe.ping)
        val bmp = WidgetArt.draw(ctx, k, (imgWDp * d).toInt().coerceAtLeast(80), (imgHDp * d).toInt().coerceAtLeast(60), d, data)
        v.setImageViewBitmap(R.id.w_img, bmp)

        val open = PendingIntent.getActivity(
            ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        v.setOnClickPendingIntent(R.id.w_root, open)

        if (k.button) {
            v.setTextViewText(R.id.w_btn, if (wait) "· · ·" else if (on) "DURDUR" else "BAŞLAT")
            v.setTextColor(R.id.w_btn, if (on) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
            v.setInt(R.id.w_btn, "setBackgroundResource", if (on) R.drawable.widget_pill_stop else R.drawable.widget_pill_start)
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
