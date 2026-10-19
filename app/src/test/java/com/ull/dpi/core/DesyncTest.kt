package com.ull.dpi.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class DesyncTest {

    /** JDK'nın gerçek bir ClientHello'sunu yakala. */
    private fun clientHello(host: String): ByteArray {
        ServerSocket(0).use { server ->
            var captured = ByteArray(0)
            val t = thread {
                server.accept().use { s ->
                    s.soTimeout = 3000
                    val buf = ByteArray(16384)
                    var len = 0
                    while (true) {
                        val n = s.getInputStream().read(buf, len, buf.size - len)
                        if (n < 0) break
                        len += n
                        val need = TlsHello.recordLength(buf, len)
                        if (need in 1..len) break
                    }
                    captured = buf.copyOf(len)
                }
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, null, null) }
            val sock = ctx.socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket
            sock.sslParameters = SSLParameters().apply { serverNames = listOf(SNIHostName(host)) }
            thread { runCatching { sock.startHandshake() } }
            t.join(5000)
            sock.close()
            return captured
        }
    }

    /** Kayıtları ayrıştırıp payload'ları birleştir (sunucunun yaptığı gibi). */
    private fun payloads(data: ByteArray): Pair<Int, ByteArray> {
        var p = 0
        var count = 0
        val out = java.io.ByteArrayOutputStream()
        while (p + 5 <= data.size) {
            val len = u16(data, p + 3)
            assertEquals(0x16, u8(data, p))
            out.write(data, p + 5, len)
            p += 5 + len
            count++
        }
        assertEquals("artık bayt kalmamalı", data.size, p)
        return count to out.toByteArray()
    }

    @Test
    fun findsSniInRealClientHello() {
        val hello = clientHello("discord.com")
        assertTrue(hello.size > 100)
        assertEquals("discord.com", TlsHello.sniHost(hello))
        assertEquals(hello.size, TlsHello.recordLength(hello))
    }

    @Test
    fun noSniInGarbage() {
        assertNull(TlsHello.findSni(ByteArray(0)))
        assertNull(TlsHello.findSni("GET / HTTP/1.1\r\n\r\n".toByteArray()))
        val hello = clientHello("discord.com")
        // Yarım kalmış ClientHello: SNI'ye ulaşamıyorsa null, çökme yok.
        for (cut in listOf(5, 10, 44, 60, 80)) TlsHello.findSni(hello.copyOf(cut))
    }

    @Test
    fun recordSplitKeepsPayload() {
        val hello = clientHello("gateway.discord.gg")
        val parts = Desync.tls(hello, DesyncOptions(tlsRecordSplit = true, tcpSplit = false))
        assertEquals(1, parts.size)
        val (records, payload) = payloads(parts[0])
        assertEquals(2, records)
        assertArrayEquals(hello.copyOfRange(5, hello.size), payload)
    }

    @Test
    fun sniNeverWholeInOneRecord() {
        val host = "cdn.discordapp.com"
        val hello = clientHello(host)
        val out = Desync.tls(hello, DesyncOptions(tcpSplit = false))[0]
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue("SNI bölünmüş olmalı", !text.contains(host))
    }

    @Test
    fun tcpSplitPartsConcatenateToSameBytes() {
        val hello = clientHello("discord.com")
        val o = DesyncOptions(tlsRecordSplit = true, tcpSplit = true, splitAtSni = true, firstCut = 2)
        val parts = Desync.tls(hello, o)
        assertTrue(parts.size >= 3)
        assertEquals(2, parts[0].size)
        val joined = parts.reduce { a, b -> a + b }
        val (_, payload) = payloads(joined)
        assertArrayEquals(hello.copyOfRange(5, hello.size), payload)
        // Hiçbir parça alan adının tamamını içermiyor.
        parts.forEach { assertTrue(!String(it, Charsets.ISO_8859_1).contains("discord.com")) }
    }

    @Test
    fun plainSplitWithoutSni() {
        val data = ByteArray(100) { it.toByte() }
        val parts = Desync.tls(data, DesyncOptions(firstCut = 3))
        assertEquals(listOf(3, 97), parts.map { it.size })
    }

    @Test
    fun httpHostTrick() {
        val req = "GET / HTTP/1.1\r\nHost: Example.com\r\nAccept: */*\r\n\r\n".toByteArray()
        val out = String(HttpTricks.apply(req), Charsets.US_ASCII)
        assertEquals("GET / HTTP/1.1\r\nhoSt:eXaMpLe.cOm\r\nAccept: */*\r\n\r\n", out)
        val parts = Desync.http(req, DesyncOptions())
        assertTrue(parts.size >= 2)
        assertEquals(out, parts.joinToString("") { String(it, Charsets.US_ASCII) })
    }

    @Test
    fun scopeMatchesSubdomains() {
        val s = Scope(false, listOf("discord.com", " Discord.GG "))
        assertTrue(s.matches("discord.com"))
        assertTrue(s.matches("gateway.discord.gg"))
        assertTrue(!s.matches("notdiscord.com"))
        assertTrue(!s.matches(null))
        assertTrue(Scope(true, emptyList()).matches(null))
    }

    @Test
    fun hostPort() {
        assertEquals("discord.com" to 443, LocalProxy.splitHostPort("discord.com:443", 80))
        assertEquals("::1" to 8443, LocalProxy.splitHostPort("[::1]:8443", 443))
        assertEquals("a.b" to 80, LocalProxy.splitHostPort("a.b", 80))
        assertNull(LocalProxy.splitHostPort("a.b:99999", 80))
        assertNotNull(LocalProxy.splitHostPort("1.2.3.4:1", 80))
    }
}
