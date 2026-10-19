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

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (VpnState.running || VpnState.starting) {
            DpiVpnService.send(this, DpiVpnService.ACTION_STOP)
        } else if (VpnService.prepare(this) != null) {
            openApp() // VPN izni henüz verilmemiş: uygulamada sor
        } else {
            try {
                DpiVpnService.send(this, DpiVpnService.ACTION_START)
            } catch (_: IllegalStateException) {
                openApp() // arka plandan servis başlatılamadı
            }
        }
        refresh()
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

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (VpnState.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "ULL-DPI"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = when {
            VpnState.starting -> "bağlanıyor"
            VpnState.running -> "aktif"
            else -> "kapalı"
        }
        t.updateTile()
    }
}
