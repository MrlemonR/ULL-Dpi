package com.ull.dpi.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Hangi bağlantılara DPI atlatma uygulanacak. */
class Scope(val all: Boolean, domains: Collection<String>) {
    private val list = domains.map { it.trim().trim('.').lowercase() }.filter { it.isNotEmpty() }.toSet()

    fun matches(host: String?): Boolean {
        if (all) return true
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return list.any { h == it || h.endsWith(".$it") }
    }
}

class ProxySettings(
    val desync: DesyncOptions,
    val scope: Scope,
    /** Parçalar arası bekleme; DPI'ın parçaları birleştirmeye fırsat bulmaması için. */
    val delayMs: Int = 3,
)

/*
 * 127.0.0.1 üzerinde HTTP proxy. VPN, sistemin HTTP proxy'si olarak bunu
 * gösteriyor; tarayıcı, Discord vb. bağlantıları buradan açıyor:
 *
 *   uygulama ──CONNECT discord.com:443──▶ biz ──(parçalanmış ClientHello)──▶ sunucu
 *
 * Alan adını zehirlenmemiş DNS ile biz çözüyoruz, ilk paketi bozup
 * yolluyoruz, sonra iki yönü olduğu gibi birbirine bağlıyoruz.
 */
class LocalProxy(
    private val resolver: CachingResolver,
    @Volatile var settings: ProxySettings,
) {
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    private val open = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())

    val port: Int get() = server?.localPort ?: -1

    /** Verilen porttan başlayıp boş ilk portu dener. */
    fun start(preferredPort: Int): Int {
        var last: IOException? = null
        for (p in preferredPort until preferredPort + 20) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 256)
                server = s
                break
            } catch (e: IOException) {
                last = e
            }
        }
        val s = server ?: throw last ?: IOException("port yok")
        val exec = Executors.newCachedThreadPool { r -> Thread(r, "proxy").apply { isDaemon = true } }
        pool = exec
        exec.execute {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                exec.execute { handle(c) }
            }
        }
        return s.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        open.toList().forEach { runCatching { it.close() } }
        open.clear()
        pool?.shutdownNow()
        pool?.awaitTermination(1, TimeUnit.SECONDS)
        server = null
        pool = null
    }

    private fun handle(client: Socket) {
        open += client
        Stats.active.incrementAndGet()
        Stats.total.incrementAndGet()
        var remote: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 20_000
            val cin = client.getInputStream()
            val cout = client.getOutputStream()
            val head = readHead(cin) ?: return
            val headEnd = HttpTricks.headerEnd(head.buf, head.len)
            val line = String(head.buf, 0, head.buf.indexOfFirst(0, head.len, '\r'.code.toByte()), Charsets.ISO_8859_1)
            val parts = line.split(' ')
            if (parts.size < 3) return reply(cout, "400 Bad Request")
            val method = parts[0].uppercase()
            val extra = head.buf.copyOfRange(headEnd + 4, head.len)

            if (method == "CONNECT") {
                val (host, port) = splitHostPort(parts[1], 443) ?: return reply(cout, "400 Bad Request")
                remote = connect(host, port) ?: return reply(cout, "502 Bad Gateway")
                open += remote
                cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                cout.flush()
                val first = readFirstRecord(client, extra, port)
                val s = settings
                val sni = TlsHello.sniHost(first)
                val target = sni ?: host
                val chunks = when {
                    first.isEmpty() -> emptyList()
                    TlsHello.isHandshake(first) && s.scope.matches(target) -> Desync.tls(first, s.desync)
                    else -> listOf(first)
                }
                if (chunks.size > 1) {
                    Stats.desynced.incrementAndGet()
                    Logbook.add("TLS", "$target:$port ▸ ${chunks.size} parça")
                }
                send(remote, chunks, s.delayMs)
            } else {
                // Düz HTTP: "GET http://site/yol HTTP/1.1"
                val url = parts[1]
                if (!url.startsWith("http://", ignoreCase = true)) return reply(cout, "400 Bad Request")
                val rest = url.substring(7)
                val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
                val (host, port) = splitHostPort(rest.substring(0, slash), 80) ?: return reply(cout, "400 Bad Request")
                val path = rest.substring(slash).ifEmpty { "/" }
                val headers = String(head.buf, line.length, headEnd + 4 - line.length, Charsets.ISO_8859_1)
                    .split("\r\n").filterNot { it.startsWith("Proxy-", ignoreCase = true) }.joinToString("\r\n")
                val req = ("${parts[0]} $path ${parts[2]}" + headers).toByteArray(Charsets.ISO_8859_1) + extra
                remote = connect(host, port) ?: return reply(cout, "502 Bad Gateway")
                open += remote
                val s = settings
                val chunks = if (s.scope.matches(host)) Desync.http(req, s.desync) else listOf(req)
                if (chunks.size > 1 || s.desync.httpTricks) {
                    Stats.desynced.incrementAndGet()
                    Logbook.add("HTTP", "$host:$port ▸ host hilesi")
                }
                send(remote, chunks, s.delayMs)
            }

            client.soTimeout = 0
            relay(client, remote)
        } catch (e: IOException) {
            // Bağlantı kapanması olağan; sessizce geç.
        } finally {
            Stats.active.decrementAndGet()
            close(client)
            remote?.let { close(it) }
        }
    }

    private fun connect(host: String, port: Int): Socket? {
        val addrs = try {
            resolver.lookup(host)
        } catch (e: Exception) {
            Logbook.add("DNS", "$host çözülemedi: ${e.message}")
            emptyList()
        }.ifEmpty {
            // Yedek: sistem çözücüsü
            runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        }
        for (a in addrs.take(3)) {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(a, port), 8000)
                return s
            } catch (_: IOException) {
                close(s)
            }
        }
        Stats.failed.incrementAndGet()
        Logbook.add("ERR", "$host:$port bağlanamadı")
        return null
    }

    private fun send(remote: Socket, chunks: List<ByteArray>, delayMs: Int) {
        val out = remote.getOutputStream()
        chunks.forEachIndexed { i, c ->
            if (i > 0 && delayMs > 0) Thread.sleep(delayMs.toLong())
            out.write(c)
            out.flush()
            Stats.up.addAndGet(c.size.toLong())
        }
    }

    /** İki yönü birbirine bağla; biri kapanınca diğerinin yazma yönünü kapat. */
    private fun relay(client: Socket, remote: Socket) {
        val up = pool?.submit {
            pump(client.getInputStream(), remote.getOutputStream(), true)
            runCatching { remote.shutdownOutput() }
        } ?: return
        pump(remote.getInputStream(), client.getOutputStream(), false)
        runCatching { client.shutdownOutput() }
        runCatching { up.get(30, TimeUnit.SECONDS) }
    }

    private fun pump(inp: InputStream, out: OutputStream, upward: Boolean) {
        val buf = ByteArray(32 * 1024)
        val counter = if (upward) Stats.up else Stats.down
        try {
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
                counter.addAndGet(n.toLong())
            }
        } catch (_: IOException) {
        }
    }

    private class Head(val buf: ByteArray, val len: Int)

    private fun readHead(inp: InputStream): Head? {
        val buf = ByteArray(16 * 1024)
        var len = 0
        while (len < buf.size) {
            val n = inp.read(buf, len, buf.size - len)
            if (n < 0) return null
            len += n
            if (HttpTricks.headerEnd(buf, len) >= 0) return Head(buf, len)
        }
        return null
    }

    /**
     * ClientHello'nun tamamını oku (Chrome'unki 1.5 KB'ı geçebiliyor ve birden
     * fazla okumaya bölünebiliyor). SNI bütün olarak elimizde olmalı.
     * Önce sunucunun konuştuğu protokollerde takılmamak için ilk bekleme kısa.
     */
    private fun readFirstRecord(client: Socket, initial: ByteArray, port: Int): ByteArray {
        var buf = initial.copyOf(maxOf(initial.size, 4096))
        var len = initial.size
        val inp = client.getInputStream()
        try {
            while (true) {
                if (len > 0) {
                    if (!TlsHello.isHandshake(buf, len)) break
                    val need = TlsHello.recordLength(buf, len)
                    if (need in 1..len || need > 64 * 1024) break
                    if (need > buf.size) buf = buf.copyOf(need)
                }
                client.soTimeout = if (len > 0) 3_000 else if (port == 443) 5_000 else 800
                val n = inp.read(buf, len, buf.size - len)
                if (n < 0) break
                len += n
            }
        } catch (_: SocketTimeoutException) {
        }
        return buf.copyOf(len)
    }

    private fun reply(out: OutputStream, status: String) {
        runCatching {
            out.write("HTTP/1.1 $status\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    private fun close(s: Socket) {
        open -= s
        runCatching { s.close() }
    }

    companion object {
        fun splitHostPort(s: String, defaultPort: Int): Pair<String, Int>? {
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 0) return null
                val host = s.substring(1, end)
                val port = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
                return host to port
            }
            val i = s.lastIndexOf(':')
            if (i < 0) return (s.ifEmpty { return null }) to defaultPort
            val port = s.substring(i + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return s.substring(0, i) to port
        }

        private fun ByteArray.indexOfFirst(from: Int, to: Int, b: Byte): Int {
            for (i in from until to) if (this[i] == b) return i
            return to
        }
    }
}
