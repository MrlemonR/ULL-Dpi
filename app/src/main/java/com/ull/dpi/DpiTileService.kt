package com.ull.dpi

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Bildirim panelindeki hızlı ayar kutucuğu: tek dokunuşla aç/kapa. */
class DpiTileService : TileService() {

    // Panel açıkken servis durumu değişirse kutucuk hemen güncellensin.
    private val onChange: () -> Unit = { refresh() }

    override fun onStartListening() {
        VpnState.listen(onChange)
        refresh()
    }

    override fun onStopListening() = VpnState.unlisten(onChange)

    override fun onClick() {
        if (VpnState.running || VpnState.starting) {
            send(DpiVpnService.ACTION_STOP)
            // Servis birazdan durumu bildirecek; o ana kadar kapalı göster.
            refresh(forceOn = false)
        } else if (VpnService.prepare(this) != null) {
            openApp() // VPN izni henüz verilmemiş: uygulamada sor
        } else if (send(DpiVpnService.ACTION_START)) {
            refresh(forceOn = true)
        } else {
            openApp() // arka plandan servis başlatılamadı
        }
    }

    private fun send(action: String): Boolean = try {
        DpiVpnService.send(this, action)
        true
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    /** Açıkken STATE_ACTIVE (beyaz/parlak), kapalıyken STATE_INACTIVE. */
    private fun refresh(forceOn: Boolean? = null) {
        val t = qsTile ?: return
        val on = forceOn ?: (VpnState.running || VpnState.starting)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "ULL-DPI"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = when {
            forceOn == true || VpnState.starting -> "bağlanıyor"
            on -> "aktif"
            else -> "kapalı"
        }
        t.updateTile()
    }
}
