package com.ull.dpi.core

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/*
 * DNS mesajları ve tünelden gelen IPv4/UDP paketleri için küçük yardımcılar.
 * Bilerek minimal: sadece bizim ihtiyacımız olan kısımlar.
 */

object DnsMessage {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    fun query(id: Int, name: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8); out.write(id)
        out.write(0x01); out.write(0x00) // RD
        out.write(0); out.write(1) // QDCOUNT
        repeat(6) { out.write(0) }
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size); out.write(b)
        }
        out.write(0)
        out.write(type shr 8); out.write(type)
        out.write(0); out.write(1) // IN
        return out.toByteArray()
    }

    class Answer(val addresses: List<InetAddress>, val ttl: Int, val rcode: Int)

    /** Cevaptaki A / AAAA kayıtları ve en küçük TTL. */
    fun parse(msg: ByteArray): Answer {
        if (msg.size < 12) return Answer(emptyList(), 0, 2)
        val rcode = u8(msg, 3) and 0x0F
        val qd = u16(msg, 4)
        val an = u16(msg, 6)
        var p = 12
        repeat(qd) { p = skipName(msg, p) + 4 }
        val addrs = ArrayList<InetAddress>()
        var ttl = Int.MAX_VALUE
        repeat(an) {
            p = skipName(msg, p)
            if (p + 10 > msg.size) return@repeat
            val type = u16(msg, p)
            val rttl = (u16(msg, p + 4) shl 16) or u16(msg, p + 6)
            val len = u16(msg, p + 8)
            p += 10
            if (p + len > msg.size) return@repeat
            if ((type == TYPE_A && len == 4) || (type == TYPE_AAAA && len == 16)) {
                addrs += InetAddress.getByAddress(msg.copyOfRange(p, p + len))
            }
            if (rttl in 0 until ttl) ttl = rttl
            p += len
        }
        return Answer(addrs, if (ttl == Int.MAX_VALUE) 0 else ttl, rcode)
    }

    /** Sorudaki alan adı (log için). */
    fun questionName(msg: ByteArray): String? {
        if (msg.size < 13 || u16(msg, 4) == 0) return null
        val sb = StringBuilder()
        var p = 12
        while (p < msg.size) {
            val n = u8(msg, p)
            if (n == 0) return sb.toString()
            if (n and 0xC0 != 0 || p + 1 + n > msg.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(msg, p + 1, n, Charsets.US_ASCII))
            p += 1 + n
        }
        return null
    }

    /** Sorunun kimliği + sorusu korunarak SERVFAIL cevabı. */
    fun servfail(query: ByteArray): ByteArray {
        if (query.size < 12) return query
        val end = runCatching { skipName(query, 12) + 4 }.getOrDefault(12).coerceAtMost(query.size)
        val out = query.copyOf(end)
        out[2] = (0x80 or (u8(query, 2) and 0x01)).toByte() // QR + RD
        out[3] = 0x82.toByte() // RA + SERVFAIL
        for (i in 6 until 12) out[i] = 0
        if (end == 12) { out[4] = 0; out[5] = 0 }
        return out
    }

    private fun skipName(msg: ByteArray, start: Int): Int {
        var p = start
        while (p < msg.size) {
            val n = u8(msg, p)
            when {
                n == 0 -> return p + 1
                n and 0xC0 == 0xC0 -> return p + 2
                else -> p += 1 + n
            }
        }
        return p
    }
}

/** Tünelden okunan bir IPv4/UDP paketi. */
class Udp4(
    val src: ByteArray,
    val srcPort: Int,
    val dst: ByteArray,
    val dstPort: Int,
    val payload: ByteArray,
) {
    companion object {
        fun parse(pkt: ByteArray, len: Int): Udp4? {
            if (len < 28 || (u8(pkt, 0) shr 4) != 4) return null
            val ihl = (u8(pkt, 0) and 0x0F) * 4
            if (u8(pkt, 9) != 17 || len < ihl + 8) return null
            if (u16(pkt, 6) and 0x3FFF != 0) return null // parçalı IP paketi
            val total = minOf(u16(pkt, 2), len)
            val udpLen = u16(pkt, ihl + 4)
            val end = minOf(ihl + udpLen, total)
            if (end < ihl + 8) return null
            return Udp4(
                pkt.copyOfRange(12, 16), u16(pkt, ihl),
                pkt.copyOfRange(16, 20), u16(pkt, ihl + 2),
                pkt.copyOfRange(ihl + 8, end),
            )
        }

        fun build(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int, payload: ByteArray): ByteArray {
            val udpLen = 8 + payload.size
            val total = 20 + udpLen
            val p = ByteArray(total)
            p[0] = 0x45
            put16(p, 2, total)
            p[6] = 0x40 // DF
            p[8] = 64 // TTL
            p[9] = 17
            src.copyInto(p, 12); dst.copyInto(p, 16)
            put16(p, 10, checksum(p, 0, 20, 0))
            put16(p, 20, srcPort); put16(p, 22, dstPort); put16(p, 24, udpLen)
            payload.copyInto(p, 28)
            // UDP sözde başlığı: kaynak + hedef + protokol + uzunluk
            var pseudo = 17 + udpLen
            for (i in 12 until 20 step 2) pseudo += u16(p, i)
            val sum = checksum(p, 20, udpLen, pseudo)
            put16(p, 26, if (sum == 0) 0xFFFF else sum)
            return p
        }

        private fun put16(b: ByteArray, i: Int, v: Int) {
            b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte()
        }

        internal fun checksum(b: ByteArray, off: Int, len: Int, initial: Int): Int {
            var sum = initial.toLong()
            var i = off
            while (i + 1 < off + len) { sum += u16(b, i); i += 2 }
            if (i < off + len) sum += u8(b, i) shl 8
            while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
            return (sum.inv() and 0xFFFF).toInt()
        }
    }
}
