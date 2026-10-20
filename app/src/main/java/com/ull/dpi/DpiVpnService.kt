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
import android.content.pm.ServiceInfo
import com.ull.dpi.core.CachingResolver
import com.ull.dpi.core.DnsTunnel
import com.ull.dpi.core.LocalProxy
import com.ull.dpi.core.Logbook
import com.ull.dpi.core.Stats
import java.io.FileInputStream
import java.io.FileOutputStream

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

    private val ticker: (Int) -> Unit = { n -> if (n % 2 == 0 && VpnState.running) notifyRunning() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Widget'tan / kutucuktan startForegroundService ile gelinebiliyor:
        // 5 sn içinde ön plana geçmezsek sistem uygulamayı kapatıyor.
        goForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown("durduruldu")
                stopAll()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                if (!VpnState.running) {
                    if (!VpnState.starting) stopAll()
                    return START_NOT_STICKY
                }
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
        VpnState.changed(this)
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
                Ticker.acquire(this, "vpn")
                Ticker.listen(ticker)
                notifyRunning()
            } catch (e: Exception) {
                VpnState.error = e.message ?: e.javaClass.simpleName
                Logbook.add("ERR", "başlatılamadı: ${VpnState.error}")
                shutdown(null)
                stopAll()
            } finally {
                VpnState.starting = false
                VpnState.changed(this)
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
        Ticker.unlisten(ticker)
        Ticker.release("vpn")
        if (wasRunning && reason != null) Logbook.add("SYS", reason)
        VpnState.changed(this)
    }

    private fun stopAll() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onRevoke() {
        // Başka bir VPN açıldı ya da kullanıcı sistem ayarlarından kapattı.
        shutdown("VPN sistem tarafından kapatıldı")
        stopAll()
    }

    override fun onDestroy() {
        shutdown(null)
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "ULL-DPI durumu", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notification(): Notification {
        channel()
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DpiVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent())
        if (VpnState.running) {
            b.setContentTitle("ULL::DPI aktif  ↓ ${Speed.fmt(Speed.down)}  ↑ ${Speed.fmt(Speed.up)} Mbps")
                .setContentText(VpnState.summary + (if (Speed.ping >= 0) " · ${Speed.ping} ms" else ""))
                .addAction(Notification.Action.Builder(null, "DURDUR", stop).build())
        } else {
            b.setContentTitle("ULL::DPI").setContentText("bağlanıyor…")
        }
        return b.build()
    }

    private fun goForeground() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** Bildirimdeki canlı hız; Ticker iki saniyede bir çağırıyor. */
    private fun notifyRunning() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIF_ID, notification()) }
    }

    companion object {
        const val ACTION_START = "com.ull.dpi.START"
        const val ACTION_STOP = "com.ull.dpi.STOP"
        const val ACTION_RELOAD = "com.ull.dpi.RELOAD"
        private const val TUN_ADDR = "10.111.222.1"
        private const val DNS_ADDR = "10.111.222.2"
        private const val CHANNEL = "status"
        private const val NOTIF_ID = 7

        fun intent(ctx: Context, action: String): Intent =
            Intent(ctx, DpiVpnService::class.java).setAction(action)

        fun send(ctx: Context, action: String) {
            val i = intent(ctx, action)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
