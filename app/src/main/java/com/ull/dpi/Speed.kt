package com.ull.dpi

import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Canlı internet hızı: telefonun TOPLAM indirme/yükleme hızı (TrafficStats,
 * tüm uygulamalar) ve bir TCP el sıkışmasının süresi (ping). Loopback
 * (uygulama ↔ yerel proxy) trafiği sayılmıyor, yani proxy'den geçen veri iki
 * kez sayılmıyor.
 */
object Speed {
    /** bit/sn */
    @Volatile var down = 0.0
    @Volatile var up = 0.0
    @Volatile var ping = -1

    const val HISTORY = 40
    private val histDown = DoubleArray(HISTORY)
    private val histUp = DoubleArray(HISTORY)
    private var head = 0

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastT = 0L

    @Synchronized
    fun sample() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val t = SystemClock.elapsedRealtime()
        if (rx < 0 || tx < 0) return // cihaz desteklemiyor
        if (lastRx >= 0 && t > lastT) {
            val dt = (t - lastT) / 1000.0
            val d = maxOf(0L, rx - lastRx) * 8 / dt
            val u = maxOf(0L, tx - lastTx) * 8 / dt
            // Hafif yumuşatma: ibre titremesin ama gecikmesin.
            down = down * 0.35 + d * 0.65
            up = up * 0.35 + u * 0.65
            histDown[head] = down
            histUp[head] = up
            head = (head + 1) % HISTORY
        }
        lastRx = rx; lastTx = tx; lastT = t
    }

    /** Eskiden yeniye. */
    @Synchronized
    fun history(): Pair<DoubleArray, DoubleArray> {
        val d = DoubleArray(HISTORY) { histDown[(head + it) % HISTORY] }
        val u = DoubleArray(HISTORY) { histUp[(head + it) % HISTORY] }
        return d to u
    }

    /** Bu uygulama VPN dışında olduğu için ölçüm doğrudan hattan. */
    fun measurePing(host: String = "1.1.1.1", port: Int = 443): Int = try {
        Socket(Proxy.NO_PROXY).use { s ->
            val t0 = SystemClock.elapsedRealtimeNanos()
            s.connect(InetSocketAddress(host, port), 1500)
            ((SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000).toInt()
        }
    } catch (_: IOException) {
        -1
    }

    /** Mbps, widget ve bildirim için: "0.4", "12.4", "245". */
    fun fmt(bits: Double): String {
        val m = bits / 1e6
        return when {
            m < 10 -> String.format(Locale.ROOT, "%.1f", m)
            else -> String.format(Locale.ROOT, "%.0f", m)
        }
    }
}

/**
 * Saniyede bir ölçüm alan zamanlayıcı. Biri ihtiyaç duyduğu sürece (açık
 * arayüz, çalışan VPN) çalışıyor; kimse istemeyince duruyor → pil harcamıyor.
 */
object Ticker {
    private val holders = HashSet<String>()
    @Volatile private var exec: ScheduledExecutorService? = null
    private val listeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val pinging = AtomicBoolean(false)
    private var n = 0

    /** Ölçümler taze mi? */
    val active: Boolean get() = exec != null

    fun listen(l: (Int) -> Unit) { listeners += l }
    fun unlisten(l: (Int) -> Unit) { listeners -= l }

    @Synchronized
    fun acquire(ctx: Context, who: String) {
        holders += who
        if (exec != null) return
        val app = ctx.applicationContext
        exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ticker").apply { isDaemon = true } }
            .also { it.scheduleAtFixedRate({ tick(app) }, 0, 1, TimeUnit.SECONDS) }
    }

    @Synchronized
    fun release(who: String) {
        holders -= who
        if (holders.isEmpty()) {
            exec?.shutdownNow()
            exec = null
        }
    }

    private fun tick(ctx: Context) {
        try {
            n++
            Speed.sample()
            if (n % 3 == 1 && pinging.compareAndSet(false, true)) {
                thread(isDaemon = true, name = "ping") {
                    try { Speed.ping = Speed.measurePing() } finally { pinging.set(false) }
                }
            }
            Widgets.updateAll(ctx)
            listeners.forEach { it(n) }
        } catch (_: Exception) {
            // Zamanlayıcı bir hatada sessizce ölmesin.
        }
    }
}

/**
 * Speedtest by Ookla gibi gerçek hız testi: ping → indirme → yükleme.
 * Cloudflare'in hız testi sunucusunu kullanıyor (speed.cloudflare.com).
 */
object SpeedTest {
    @Volatile var phase = "idle" // idle | ping | down | up | done | error
    @Volatile var ping = -1
    @Volatile var down = 0.0 // bit/sn (anlık, test sırasında)
    @Volatile var up = 0.0
    @Volatile var progress = 0.0 // 0..1 o aşama için
    @Volatile var error: String? = null
    @Volatile var finishedAt = 0L

    private const val HOST = "speed.cloudflare.com"
    private const val DOWN_SECS = 8.0
    private const val UP_SECS = 7.0
    private const val STREAMS = 4

    val running get() = phase == "ping" || phase == "down" || phase == "up"

    @Synchronized
    fun start() {
        if (running) return
        ping = -1; down = 0.0; up = 0.0; progress = 0.0; error = null
        phase = "ping"
        thread(isDaemon = true, name = "speedtest") {
            try {
                runPing()
                phase = "down"; progress = 0.0
                down = measure(DOWN_SECS) { counter, stop -> downloadLoop(counter, stop) }
                phase = "up"; progress = 0.0
                up = measure(UP_SECS) { counter, stop -> uploadLoop(counter, stop) }
                phase = "done"
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                phase = "error"
            } finally {
                finishedAt = System.currentTimeMillis()
            }
        }
    }

    private fun runPing() {
        val samples = (0 until 6).map { Speed.measurePing(HOST, 443) }.filter { it >= 0 }
        if (samples.isEmpty()) throw IOException("sunucuya ulaşılamadı")
        // İlk bağlantı DNS'i de içeriyor; en iyi üçün ortalaması.
        ping = samples.sorted().take(3).average().toInt()
    }

    /**
     * Paralel akışları [secs] saniye çalıştırır. İlk saniye (TCP ısınması)
     * sonuca katılmıyor. Test sırasında anlık hız [down]/[up]'a yazılıyor.
     */
    private fun measure(secs: Double, worker: (AtomicLong, AtomicBoolean) -> Unit): Double {
        val counter = AtomicLong()
        val stop = AtomicBoolean(false)
        val threads = (0 until STREAMS).map { thread(isDaemon = true) { runCatching { worker(counter, stop) } } }
        val t0 = SystemClock.elapsedRealtime()
        var lastBytes = 0L
        var lastT = t0
        var warmBytes = -1L
        var warmT = 0L
        var live = 0.0
        while (true) {
            Thread.sleep(200)
            val now = SystemClock.elapsedRealtime()
            val bytes = counter.get()
            val inst = (bytes - lastBytes) * 8.0 / ((now - lastT) / 1000.0)
            live = if (live == 0.0) inst else live * 0.6 + inst * 0.4
            if (phase == "down") down = live else up = live
            lastBytes = bytes; lastT = now
            val elapsed = (now - t0) / 1000.0
            progress = (elapsed / secs).coerceAtMost(1.0)
            if (warmBytes < 0 && elapsed >= 1.0) { warmBytes = bytes; warmT = now }
            if (elapsed >= secs) break
        }
        stop.set(true)
        threads.forEach { it.join(1500) }
        val dt = (lastT - warmT) / 1000.0
        if (warmBytes < 0 || dt <= 0 || lastBytes <= warmBytes) throw IOException("veri akmadı")
        return (lastBytes - warmBytes) * 8.0 / dt
    }

    private fun open(path: String): HttpURLConnection =
        (URI("https://$HOST$path").toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            useCaches = false
        }

    private fun downloadLoop(counter: AtomicLong, stop: AtomicBoolean) {
        val buf = ByteArray(64 * 1024)
        while (!stop.get()) {
            val c = open("/__down?bytes=50000000")
            try {
                c.inputStream.use { inp ->
                    while (!stop.get()) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        counter.addAndGet(n.toLong())
                    }
                }
            } finally {
                c.disconnect()
            }
        }
    }

    private fun uploadLoop(counter: AtomicLong, stop: AtomicBoolean) {
        val chunk = ByteArray(64 * 1024)
        val size = 20_000_000
        while (!stop.get()) {
            val c = open("/__up")
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setFixedLengthStreamingMode(size)
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.outputStream.use { out ->
                    var sent = 0
                    while (sent < size && !stop.get()) {
                        val n = minOf(chunk.size, size - sent)
                        out.write(chunk, 0, n)
                        sent += n
                        counter.addAndGet(n.toLong())
                    }
                }
                if (!stop.get()) c.responseCode
            } catch (_: IOException) {
                // Durdururken bağlantıyı yarıda kesiyoruz; beklenen.
            } finally {
                c.disconnect()
            }
        }
    }
}
