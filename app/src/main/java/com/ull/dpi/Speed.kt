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
            if (n % 5 == 1) Config.load(ctx).let {
                Probe.intervalSecs = it.probeSecs
                Probe.continuous = it.continuous
                Probe.mobilePause = it.mobilePause
            }
            if (Probe.due(ctx)) Probe.runAsync { Widgets.updateAll(ctx) }
            listeners.forEach { it(n) }
        } catch (e: Exception) {
            // Zamanlayıcı bir hatada ölmesin; ama hata görünsün.
            report(e)
        }
    }

    private val reported = HashSet<String>()

    /** Aynı hatayı bir kez LOG'a yaz (her saniye tekrar etmesin). */
    fun report(e: Exception) {
        val key = e.javaClass.simpleName + ": " + e.message
        if (synchronized(reported) { reported.add(key) }) com.ull.dpi.core.Logbook.add("ERR", "ölçüm: $key")
    }
}

/**
 * Widget'lar için kısa ölçüm: birkaç saniyede bir küçük bir indirme + yükleme
 * + ping. Telefon o an internet kullanmasa bile hattın hızını gösteriyor.
 *
 * Veri harcadığı için sınırlı: indirme en fazla [DOWN_CAP], yükleme en fazla
 * [UP_CAP] bayt, ekran kapalıyken hiç ölçülmüyor. Harcanan toplam [bytes].
 */
object Probe {
    @Volatile var down = -1.0 // bit/sn, -1 = henüz yok
    @Volatile var up = -1.0
    @Volatile var ping = -1
    @Volatile var at = 0L
    @Volatile var intervalSecs = 10
    /** Uygulamadaki "SÜREKLİ ÖLÇ" açık mı? */
    @Volatile var continuous = false
    /** Mobil veride ölçme (ayar) ve şu an bu yüzden durmuş mu. */
    @Volatile var mobilePause = true
    @Volatile var pausedMobile = false
    val bytes = AtomicLong()
    val busyNow: Boolean get() = busy.get()

    private const val DOWN_CAP = 3_000_000L
    private const val UP_CAP = 768_000L
    private const val DOWN_MS = 1500L
    private const val UP_MS = 1000L
    private val busy = AtomicBoolean(false)
    private var lastStart = 0L

    fun due(ctx: Context): Boolean {
        if (intervalSecs <= 0 || busy.get() || SpeedTest.running) return false
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isInteractive) return false
        if (SystemClock.elapsedRealtime() - lastStart < intervalSecs * 1000L) return false
        // Kimse göstermiyorsa veri harcama.
        if (!continuous && !Widgets.any(ctx)) return false
        val mobile = mobilePause && onMobile(ctx)
        if (mobile != pausedMobile) {
            pausedMobile = mobile
            Widgets.updateAll(ctx) // widget'ta "mobil veri · durdu" yazsın / kalksın
        }
        if (mobile) lastStart = SystemClock.elapsedRealtime() // aralıkla tekrar bak
        return !mobile
    }

    /** İnternet şu an mobil veriden mi? (Bu uygulama VPN dışında: gerçek ağı görür.) */
    fun onMobile(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps != null &&
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    } catch (e: Exception) {
        // Ağ bilgisi okunamazsa ölçümü engelleme (önceden izin eksikti ve
        // buradaki hata ölçümü tamamen durduruyordu).
        Ticker.report(e)
        false
    }

    /** Sonraki ölçüme kalan saniye (arayüzdeki geri sayım için). */
    fun nextIn(): Int {
        if (intervalSecs <= 0) return -1
        val left = intervalSecs * 1000L - (SystemClock.elapsedRealtime() - lastStart)
        return (left / 1000).toInt().coerceAtLeast(0)
    }

    fun runAsync(done: () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        lastStart = SystemClock.elapsedRealtime()
        thread(isDaemon = true, name = "probe") {
            try {
                Speed.measurePing(SpeedTest.HOST, 443).let { if (it >= 0) ping = it }
                down = transfer(true)
                at = System.currentTimeMillis()
                up = try { transfer(false) } catch (_: IOException) { -1.0 }
            } catch (e: Exception) {
                // Bağlantı yoksa son değerler kalsın; sebebi LOG'da görünsün.
                Ticker.report(e)
            } finally {
                busy.set(false)
                done()
            }
        }
    }

    /** İki paralel akış, süre ya da bayt sınırına kadar; hız = bayt / süre. */
    private fun transfer(download: Boolean): Double {
        val cap = if (download) DOWN_CAP else UP_CAP
        val limitMs = if (download) DOWN_MS else UP_MS
        val streams = if (download) 2 else 1
        val counter = AtomicLong()
        val stop = AtomicBoolean(false)
        val t0 = SystemClock.elapsedRealtime()
        val workers = (0 until streams).map {
            thread(isDaemon = true) {
                try {
                    if (download) SpeedTest.downloadOnce(cap / streams, counter, stop)
                    else SpeedTest.uploadOnce(cap.toInt(), counter, stop)
                } catch (_: IOException) {
                    // sayaç 0 kalırsa aşağıda "veri akmadı"
                }
            }
        }
        while (workers.any { it.isAlive } && SystemClock.elapsedRealtime() - t0 < limitMs) Thread.sleep(25)
        stop.set(true)
        val dt = (SystemClock.elapsedRealtime() - t0) / 1000.0
        workers.forEach { it.join(800) }
        val b = counter.get()
        bytes.addAndGet(b)
        if (b <= 0 || dt <= 0) throw IOException("veri akmadı")
        return b * 8 / dt
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

    const val HOST = "speed.cloudflare.com"
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
                up = try {
                    measure(UP_SECS) { counter, stop -> uploadLoop(counter, stop) }
                } catch (e: IOException) {
                    error = "yükleme ölçülemedi"
                    -1.0
                }
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
            .ifEmpty { (0 until 4).map { Speed.measurePing() }.filter { it >= 0 } }
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
        val threads = (0 until STREAMS).map {
            thread(isDaemon = true) {
                try { worker(counter, stop) } catch (e: Exception) { warn(e.message ?: e.javaClass.simpleName) }
            }
        }
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

    // --- sunucular -------------------------------------------------------------
    //
    // Önce Cloudflare. Sık ölçüm onu istek sınırına (HTTP 429) sokabiliyor;
    // o zaman bir süre onu atlayıp büyük test dosyası sunan yedeklere geçiyoruz.

    private val DOWN_ALT = listOf(
        "https://fsn1-speed.hetzner.com/100MB.bin",
        "https://proof.ovh.net/files/100Mb.dat",
        "https://speedtest.frankfurt.linode.com/100MB-frankfurt.bin",
    )
    @Volatile private var cfBlockedUntil = 0L
    @Volatile private var altIdx = 0
    private val warned = HashMap<String, Long>()

    /** Aynı uyarıyı 5 dakikada bir LOG'a yaz. */
    private fun warn(msg: String) {
        val now = SystemClock.elapsedRealtime()
        val first = synchronized(warned) {
            val last = warned[msg]
            if (last != null && now - last < 300_000) false else { warned[msg] = now; true }
        }
        if (first) com.ull.dpi.core.Logbook.add("ERR", "hız: $msg")
    }

    private fun cfBlocked() = SystemClock.elapsedRealtime() < cfBlockedUntil

    private fun cfFailed(code: Int) {
        warn("cloudflare HTTP $code" + if (code == 429) " (istek sınırı, 10 dk yedek sunucu)" else "")
        if (code == 429 || code == 403) cfBlockedUntil = SystemClock.elapsedRealtime() + 600_000
    }

    private fun openUrl(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ULL-DPI")
        }

    private fun open(path: String): HttpURLConnection = openUrl("https://$HOST$path")

    /** 200 dönen ilk indirme kaynağını aç. */
    private fun openDownload(bytes: Long): HttpURLConnection {
        if (!cfBlocked()) {
            val c = open("/__down?bytes=$bytes")
            try {
                val code = c.responseCode
                if (code == 200) return c
                c.disconnect(); cfFailed(code)
            } catch (e: IOException) {
                c.disconnect(); warn("cloudflare: ${e.javaClass.simpleName}")
            }
        }
        for (k in DOWN_ALT.indices) {
            val i = (altIdx + k) % DOWN_ALT.size
            val c = openUrl(DOWN_ALT[i])
            try {
                if (c.responseCode in 200..299) { altIdx = i; return c }
                warn("${URI(DOWN_ALT[i]).host} HTTP ${c.responseCode}")
            } catch (e: IOException) {
                warn("${URI(DOWN_ALT[i]).host}: ${e.javaClass.simpleName}")
            }
            c.disconnect()
        }
        throw IOException("indirme sunucusuna ulaşılamadı")
    }

    /** [bytes] kadar (ya da durdurulana kadar) indir. */
    fun downloadOnce(bytes: Long, counter: AtomicLong, stop: AtomicBoolean) {
        val buf = ByteArray(32 * 1024)
        val c = openDownload(bytes)
        try {
            c.inputStream.use { inp ->
                var got = 0L
                while (!stop.get() && got < bytes) {
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), bytes - got).toInt())
                    if (n < 0) break
                    got += n
                    counter.addAndGet(n.toLong())
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun downloadLoop(counter: AtomicLong, stop: AtomicBoolean) {
        while (!stop.get()) downloadOnce(50_000_000, counter, stop)
    }

    /** Yükleme yalnızca Cloudflare'de; sınırdaysa ölçülemez. */
    fun uploadOnce(size: Int, counter: AtomicLong, stop: AtomicBoolean) {
        if (cfBlocked()) throw IOException("yükleme sunucusu istek sınırında")
        val chunk = ByteArray(16 * 1024)
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
            if (!stop.get()) c.responseCode.let { if (it != 200) cfFailed(it) }
        } catch (_: IOException) {
            // Durdururken bağlantıyı yarıda kesiyoruz; beklenen.
        } finally {
            c.disconnect()
        }
    }

    private fun uploadLoop(counter: AtomicLong, stop: AtomicBoolean) {
        while (!stop.get()) {
            uploadOnce(20_000_000, counter, stop)
            if (cfBlocked()) break
        }
    }
}
