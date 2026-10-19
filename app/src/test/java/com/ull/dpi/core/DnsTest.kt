package com.ull.dpi.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/** Her soruya sabit A kaydıyla cevap veren sahte sunucu. */
class FakeUpstream(private val ip: String = "162.159.135.232", private val ttl: Int = 300) : Resolver {
    val calls = AtomicInteger()
    override val label = "fake"

    override fun exchange(query: ByteArray): ByteArray {
        calls.incrementAndGet()
        val qEnd = run {
            var p = 12
            while (query[p].toInt() != 0) p += 1 + query[p]
            p + 5
        }
        val type = u16(query, qEnd - 4)
        val out = ByteArrayOutputStream()
        out.write(query, 0, 2)
        out.write(0x81); out.write(0x80)
        out.write(0); out.write(1)
        out.write(0); out.write(if (type == DnsMessage.TYPE_A) 1 else 0)
        repeat(4) { out.write(0) }
        out.write(query, 12, qEnd - 12)
        if (type == DnsMessage.TYPE_A) {
            out.write(0xC0); out.write(12) // ad: soruya işaretçi
            out.write(0); out.write(1); out.write(0); out.write(1)
            out.write(ttl ushr 24); out.write(ttl ushr 16); out.write(ttl ushr 8); out.write(ttl)
            out.write(0); out.write(4)
            out.write(InetAddress.getByName(ip).address)
        }
        return out.toByteArray()
    }
}

class DnsTest {

    @Test
    fun queryRoundTrip() {
        val q = DnsMessage.query(0x1234, "discord.com", DnsMessage.TYPE_A)
        assertEquals("discord.com", DnsMessage.questionName(q))
        val ans = DnsMessage.parse(FakeUpstream().exchange(q))
        assertEquals(0, ans.rcode)
        assertEquals(listOf(InetAddress.getByName("162.159.135.232")), ans.addresses)
        assertEquals(300, ans.ttl)
    }

    @Test
    fun cacheAnswersWithNewId() {
        val up = FakeUpstream()
        val r = CachingResolver(up)
        val a = r.exchange(DnsMessage.query(1, "discord.com", DnsMessage.TYPE_A))
        val b = r.exchange(DnsMessage.query(2, "discord.com", DnsMessage.TYPE_A))
        assertEquals(1, up.calls.get())
        assertEquals(1, u16(a, 0))
        assertEquals(2, u16(b, 0))
        assertEquals(r.lookup("discord.com"), listOf(InetAddress.getByName("162.159.135.232")))
        assertEquals(listOf(InetAddress.getByName("1.2.3.4")), r.lookup("1.2.3.4"))
    }

    @Test
    fun servfailKeepsQuestion() {
        val q = DnsMessage.query(0xBEEF, "a.b", DnsMessage.TYPE_A)
        val f = DnsMessage.servfail(q)
        assertEquals(0xBEEF, u16(f, 0))
        assertEquals(2, u8(f, 3) and 0x0F)
        assertTrue(u8(f, 2) and 0x80 != 0)
        assertEquals("a.b", DnsMessage.questionName(f))
    }

    @Test
    fun udpPacketRoundTripAndChecksums() {
        val payload = "hello".toByteArray()
        val src = byteArrayOf(10, 111, 222.toByte(), 1)
        val dst = byteArrayOf(10, 111, 222.toByte(), 2)
        val pkt = Udp4.build(src, 40000, dst, 53, payload)
        // Geçerli IP başlık sağlama toplamı: tümü üzerinden toplam 0 olmalı.
        assertEquals(0, Udp4.checksum(pkt, 0, 20, 0))
        var pseudo = 17 + 8 + payload.size
        for (i in 12 until 20 step 2) pseudo += u16(pkt, i)
        assertEquals(0, Udp4.checksum(pkt, 20, 8 + payload.size, pseudo))
        val back = Udp4.parse(pkt, pkt.size)!!
        assertArrayEquals(src, back.src)
        assertEquals(40000, back.srcPort)
        assertEquals(53, back.dstPort)
        assertArrayEquals(payload, back.payload)
    }

    @Test
    fun tunnelAnswersQueries() {
        val q = DnsMessage.query(7, "discord.gg", DnsMessage.TYPE_A)
        val pkt = Udp4.build(byteArrayOf(10, 111, 222.toByte(), 1), 5555, byteArrayOf(10, 111, 222.toByte(), 2), 53, q)
        val out = ByteArrayOutputStream()
        // Girdi bitince run() dönüyor ama cevap havuzda: biraz bekle.
        val input = object : java.io.InputStream() {
            var sent = false
            override fun read() = -1
            override fun read(b: ByteArray): Int {
                if (sent) { Thread.sleep(300); return -1 }
                sent = true
                pkt.copyInto(b); return pkt.size
            }
        }
        DnsTunnel(input, out, FakeUpstream()).run()
        val resp = Udp4.parse(out.toByteArray(), out.size())!!
        assertEquals(53, resp.srcPort)
        assertEquals(5555, resp.dstPort)
        assertEquals(listOf(InetAddress.getByName("162.159.135.232")), DnsMessage.parse(resp.payload).addresses)
    }
}
