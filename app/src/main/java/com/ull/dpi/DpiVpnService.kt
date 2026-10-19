package com.ull.dpi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.service.quicksettings.TileService
import android.content.ComponentName
import com.ull.dpi.core.CachingResolver
import com.ull.dpi.core.DnsTunnel
import com.ull.dpi.core.LocalProxy
import com.ull.dpi.core.Logbook
import com.ull.dpi.core.Stats
import java.io.FileInputStream
import java.io.FileOutputStream

/** Arayüzün okuduğu durum. */
object VpnState {
    @Volatile var running = false
    @Volatile var starting = false
    @Volatile var error: String? = null
    @Volatile var since = 0L
    @Volatile var port = -1
    @Volatile var summary = ""
}

/*
 * Root gerektirmeyen yerel "VPN". Gerçek bir VPN sunucusu YOK, trafik hiçbir
 * yere taşınmıyor:
 *
 *   • tünel sadece sahte DNS adresini kapsıyor → DNS zehirlenmesi aşılıyor
 *   • sistemin HTTP proxy'si 127.0.0.1'deki LocalProxy → ilk paket parçalanıyor
 *   • bu uygulamanın kendisi VPN dışında → bizim bağlantılarımız döngüye girmiyor
 */
class DpiVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var proxy: LocalProxy? = null
    private var dns: DnsTunnel? = null
    private var worker: Thread? = null
    @Volatile private var logDns = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown("durduruldu")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                if (!VpnState.running) return START_NOT_STICKY
                val cfg = Config.load(this)
                proxy?.settings = cfg.proxySettings()
                logDns = cfg.logDns
                VpnState.summary = cfg.summary()
                Logbook.add("SYS", "ayarlar uygulandı ▸ ${VpnState.summary}")
                // DNS sunucusu ya da port değiştiyse tüneli baştan kur.
                if (cfg.dns != activeDns || cfg.port != activePort) restart()
                return START_STICKY
            }
        }
        if (!VpnState.running && !VpnState.starting) startVpn()
        return START_STICKY
    }

    private var activeDns = ""
    private var activePort = -1

    private fun restart() {
        shutdown("yeniden başlatılıyor")
        startVpn()
    }

    private fun startVpn() {
        VpnState.starting = true
        VpnState.error = null
        updateTile()
        Thread({
            try {
                val cfg = Config.load(this)
                Stats.reset()
                val resolver: CachingResolver = cfg.resolver()
                val p = LocalProxy(resolver, cfg.proxySettings())
                val port = p.start(cfg.port)
                proxy = p
                Logbook.add("SYS", "proxy ▸ 127.0.0.1:$port")

                val b = Builder()
                    .setSession("ULL-DPI")
                    .setMtu(1500)
                    .addAddress(TUN_ADDR, 24)
                    .addRoute(DNS_ADDR, 32)
                    .addDnsServer(DNS_ADDR)
                    // Bloklayan okuma, kapatınca uyanmıyor ve tüneli açık tutuyor;
                    // bloklamayan mod + poll kullanıyoruz.
                    .setBlocking(false)
                    .setConfigureIntent(openAppIntent())
                runCatching { b.addDisallowedApplication(packageName) }
                if (Build.VERSION.SDK_INT >= 29) {
                    b.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", port))
                    b.setMetered(false)
                } else {
                    Logbook.add("SYS", "Android < 10: Wi-Fi proxy'sini elle 127.0.0.1:$port yap")
                }
                val fd = b.establish() ?: throw IllegalStateException("VPN izni verilmedi")
                tun = fd

                val pollFd = StructPollfd().apply {
                    this.fd = fd.fileDescriptor
                    events = OsConstants.POLLIN.toShort()
                }
                logDns = cfg.logDns
                val t = DnsTunnel(
                    FileInputStream(fd.fileDescriptor), FileOutputStream(fd.fileDescriptor), resolver,
                    logQueries = { logDns },
                    idle = { runCatching { Os.poll(arrayOf(pollFd), 500) } },
                )
                dns = t
                worker = Thread({ t.run() }, "tun").apply { isDaemon = true; start() }

                activeDns = cfg.dns
                activePort = cfg.port
                VpnState.port = port
                VpnState.summary = cfg.summary()
                VpnState.since = System.currentTimeMillis()
                VpnState.running = true
                Logbook.add("SYS", "AKTİF ▸ ${VpnState.summary}")
                notifyRunning()
            } catch (e: Exception) {
                VpnState.error = e.message ?: e.javaClass.simpleName
                Logbook.add("ERR", "başlatılamadı: ${VpnState.error}")
                shutdown(null)
                stopSelf()
            } finally {
                VpnState.starting = false
                updateTile()
            }
        }, "vpn-start").start()
    }

    @Synchronized
    private fun shutdown(reason: String?) {
        val wasRunning = VpnState.running
        VpnState.running = false
        dns?.stop()
        runCatching { tun?.close() }
        proxy?.stop()
        worker?.interrupt()
        dns = null; tun = null; proxy = null; worker = null
        VpnState.port = -1
        if (wasRunning && reason != null) Logbook.add("SYS", reason)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        updateTile()
    }

    override fun onRevoke() {
        // Başka bir VPN açıldı ya da kullanıcı sistem ayarlarından kapattı.
        shutdown("VPN sistem tarafından kapatıldı")
        stopSelf()
    }

    override fun onDestroy() {
        shutdown(null)
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notifyRunning() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "ULL-DPI durumu", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DpiVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val n = builder
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("ULL::DPI aktif")
            .setContentText(VpnState.summary)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null, "DURDUR", stop).build())
            .build()
        nm.notify(NOTIF_ID, n)
    }

    private fun updateTile() {
        if (Build.VERSION.SDK_INT >= 24) {
            runCatching { TileService.requestListeningState(this, ComponentName(this, DpiTileService::class.java)) }
        }
    }

    companion object {
        const val ACTION_START = "com.ull.dpi.START"
        const val ACTION_STOP = "com.ull.dpi.STOP"
        const val ACTION_RELOAD = "com.ull.dpi.RELOAD"
        private const val TUN_ADDR = "10.111.222.1"
        private const val DNS_ADDR = "10.111.222.2"
        private const val CHANNEL = "status"
        private const val NOTIF_ID = 7

        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, DpiVpnService::class.java).setAction(action))
        }
    }
}
