package com.ull.dpi.core

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/*
 * Zehirlenmemiş DNS. Operatörün DNS'i engelli siteler için sahte adres
 * döndürüyor; GoodbyeDPI-Turkey bunu Yandex DNS'in standart dışı 1253
 * portuna giderek aşıyor. Biz de aynısını (ve isteğe bağlı DoH) yapıyoruz.
 */

interface Resolver {
    /** Ham DNS sorgusunu gönderip ham cevabı döndürür. */
    fun exchange(query: ByteArray): ByteArray
    val label: String
}

class UdpResolver(
    private val server: String,
    private val port: Int,
    private val timeoutMs: Int = 2500,
) : Resolver {
    override val label = "$server:$port"

    override fun exchange(query: ByteArray): ByteArray {
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            val addr = InetSocketAddress(InetAddress.getByName(server), port)
            val buf = ByteArray(4096)
            // UDP kaybolabilir: bir kez daha dene.
            repeat(2) { attempt ->
                s.send(DatagramPacket(query, query.size, addr))
                try {
                    val resp = DatagramPacket(buf, buf.size)
                    while (true) {
                        s.receive(resp)
                        if (resp.length >= 2 && buf[0] == query[0] && buf[1] == query[1]) {
                            return buf.copyOf(resp.length)
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    if (attempt == 1) throw e
                }
            }
            error("unreachable")
        }
    }
}

/** DNS-over-HTTPS (RFC 8484). Adres IP olduğu için SNI gitmiyor, engellenmiyor. */
class DohResolver(private val url: String, private val timeoutMs: Int = 4000) : Resolver {
    override val label = url

    override fun exchange(query: ByteArray): ByteArray {
        val c = URI(url).toURL().openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/dns-message")
            c.setRequestProperty("Accept", "application/dns-message")
            c.outputStream.use { it.write(query) }
            if (c.responseCode != 200) error("DoH HTTP ${c.responseCode}")
            return c.inputStream.use { inp ->
                val out = ByteArrayOutputStream()
                inp.copyTo(out)
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Önbellekli çözücü: hem tünelden gelen ham sorgular hem de proxy'nin
 * "bu alan adının IP'si ne" soruları buradan geçiyor.
 */
class CachingResolver(private val upstream: Resolver) : Resolver {
    override val label get() = upstream.label

    private class Entry(val data: ByteArray, val expires: Long)

    private val raw = ConcurrentHashMap<String, Entry>()
    private val ids = AtomicInteger((Math.random() * 65535).toInt())

    override fun exchange(query: ByteArray): ByteArray {
        if (query.size < 12) return upstream.exchange(query)
        val key = String(query, 2, query.size - 2, Charsets.ISO_8859_1)
        val now = System.currentTimeMillis()
        raw[key]?.takeIf { it.expires > now }?.let { e ->
            val out = e.data.copyOf()
            out[0] = query[0]; out[1] = query[1]
            Stats.dnsCached.incrementAndGet()
            return out
        }
        val resp = upstream.exchange(query)
        val ans = DnsMessage.parse(resp)
        if (ans.rcode == 0 || ans.rcode == 3) {
            val ttl = ans.ttl.coerceIn(30, 600)
            raw[key] = Entry(resp, now + ttl * 1000L)
            if (raw.size > 2000) raw.entries.removeIf { it.value.expires <= now }
        }
        return resp
    }

    /** Alan adının adresleri (önce IPv4). */
    fun lookup(host: String): List<InetAddress> {
        literal(host)?.let { return listOf(it) }
        val out = ArrayList<InetAddress>()
        for (type in intArrayOf(DnsMessage.TYPE_A, DnsMessage.TYPE_AAAA)) {
            val q = DnsMessage.query(ids.incrementAndGet() and 0xFFFF, host, type)
            out += DnsMessage.parse(exchange(q)).addresses
            if (out.any { it is Inet4Address }) break
        }
        return out
    }

    companion object {
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        fun literal(host: String): InetAddress? =
            if (IPV4.matches(host) || host.contains(':')) runCatching { InetAddress.getByName(host) }.getOrNull()
            else null
    }
}
