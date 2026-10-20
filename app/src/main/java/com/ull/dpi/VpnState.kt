package com.ull.dpi

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Servisin durumu. Arayüz saniyede bir okuyor; kutucuk ve widget'lar ise
 * değişince [changed] ile anında haberdar ediliyor (eskiden kutucuk bir adım
 * geriden geliyordu: açıkken sönük, kapalıyken yanık görünüyordu).
 */
object VpnState {
    @Volatile var running = false
    @Volatile var starting = false
    @Volatile var error: String? = null
    @Volatile var since = 0L
    @Volatile var port = -1
    @Volatile var summary = ""

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }

    fun changed(ctx: Context) {
        val app = ctx.applicationContext
        main.post {
            listeners.forEach { it() }
            Widgets.updateAll(app)
            if (Build.VERSION.SDK_INT >= 24) {
                runCatching { TileService.requestListeningState(app, ComponentName(app, DpiTileService::class.java)) }
            }
        }
    }
}
