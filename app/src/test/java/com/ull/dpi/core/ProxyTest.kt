package com.ull.dpi.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class ProxyTest {
    private val proxy = LocalProxy(
        CachingResolver(FakeUpstream("127.0.0.1")),
        ProxySettings(DesyncOptions(), Scope(true, emptyList()), delayMs = 2),
    )

    @After
    fun tearDown() = proxy.stop()

    /** Gelen her şeyi kaydeden, sonra "pong" diyen sunucu. */
    private fun server(expect: Int, reads: MutableList<Int>, got: ByteArrayOutputStream): ServerSocket {
        val s = ServerSocket(0)
        thread {
            s.accept().use { c ->
                val buf = ByteArray(65536)
                var total = 0
                while (total < expect) {
                    val n = c.getInputStream().read(buf)
                    if (n < 0) break
                    reads += n
                    got.write(buf, 0, n)
                    total += n
                }
                c.getOutputStream().write("pong".toByteArray())
                c.getOutputStream().flush()
            }
            s.close()
        }
        return s
    }

    private fun readHead(c: Socket): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) sb.append(c.getInputStream().read().toChar())
        return sb.toString()
    }

    @Test
    fun connectSplitsClientHelloAndRelays() {
        val port = proxy.start(28881)
        // Gerçek bir ClientHello'ya benzer: elle kurulmuş, SNI'li kayıt.
        val hello = fakeHello("discord.com")
        val reads = mutableListOf<Int>()
        val got = ByteArrayOutputStream()
        val srv = server(hello.size + 5, reads, got)

        Socket("127.0.0.1", port).use { c ->
            c.tcpNoDelay = true
            c.getOutputStream().write("CONNECT discord.com:${srv.localPort} HTTP/1.1\r\nHost: discord.com\r\n\r\n".toByteArray())
            assertTrue(readHead(c).startsWith("HTTP/1.1 200"))
            // ClientHello'yu iki parça hâlinde yolla: proxy tamamını beklemeli.
            c.getOutputStream().write(hello, 0, 40); c.getOutputStream().flush()
            Thread.sleep(50)
            c.getOutputStream().write(hello, 40, hello.size - 40); c.getOutputStream().flush()
            val reply = ByteArray(4)
            var n = 0
            while (n < 4) n += c.getInputStream().read(reply, n, 4 - n)
            assertEquals("pong", String(reply))
        }
        val expected = Desync.tls(hello, DesyncOptions()).reduce { a, b -> a + b }
        assertArrayEquals(expected, got.toByteArray())
        assertTrue("sunucu birden fazla parça görmeli: $reads", reads.size >= 2)
    }

    @Test
    fun plainHttpRewritten() {
        val port = proxy.start(28891)
        val reads = mutableListOf<Int>()
        val got = ByteArrayOutputStream()
        val req0 = "GET / HTTP/1.1\r\nhoSt:eXaMpLe.cOm\r\nAccept: */*\r\n\r\n"
        val srv = server(req0.length, reads, got)
        Socket("127.0.0.1", port).use { c ->
            c.getOutputStream().write(
                "GET http://example.com:${srv.localPort}/ HTTP/1.1\r\nHost: example.com\r\nProxy-Connection: keep-alive\r\nAccept: */*\r\n\r\n".toByteArray()
            )
            val reply = ByteArray(4)
            var n = 0
            while (n < 4) n += c.getInputStream().read(reply, n, 4 - n)
            assertEquals("pong", String(reply))
        }
        assertEquals(req0, got.toString().replace("${srv.localPort}", ""))
    }

    companion object {
        /** En küçük geçerli ClientHello: SNI uzantısıyla. */
        fun fakeHello(host: String): ByteArray {
            val name = host.toByteArray()
            val sni = ByteArrayOutputStream().apply {
                write(0); write(0) // tür: server_name
                val listLen = 3 + name.size
                write((listLen + 2) shr 8); write(listLen + 2)
                write(listLen shr 8); write(listLen)
                write(0); write(name.size shr 8); write(name.size); write(name)
            }.toByteArray()
            val body = ByteArrayOutputStream().apply {
                write(3); write(3) // sürüm
                write(ByteArray(32)) // random
                write(0) // oturum
                write(0); write(2); write(0x13); write(0x01) // şifre
                write(1); write(0) // sıkıştırma
                write(sni.size shr 8); write(sni.size); write(sni)
            }.toByteArray()
            val hs = byteArrayOf(1, 0, (body.size shr 8).toByte(), body.size.toByte()) + body
            return byteArrayOf(0x16, 3, 1, (hs.size shr 8).toByte(), hs.size.toByte()) + hs
        }
    }
}
