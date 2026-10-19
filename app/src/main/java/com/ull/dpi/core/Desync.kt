package com.ull.dpi.core

/*
 * DPI atlatma — GoodbyeDPI'ın yaptığının root'suz Android karşılığı.
 *
 * Sansür kutusu (DPI) bağlantının İLK paketine bakıp hangi siteye gittiğini
 * okuyor: HTTPS'te TLS ClientHello içindeki SNI (ör. "discord.com"), düz
 * HTTP'de "Host:" başlığı. Biz o ilk paketi kutunun okuyamayacağı ama
 * sunucunun sorunsuz birleştirdiği şekilde bozuyoruz:
 *
 *  - TLS kayıt bölme (tlsrec): tek TLS kaydını, SNI'nin ortasından ikiye
 *    bölüp iki ayrı kayıt yapıyoruz. Sunucu kayıtları birleştirir; kayıt
 *    birleştirmeyen DPI alan adını hiçbir zaman tam görmez.
 *  - TCP parçalama (split): ilk veriyi birkaç küçük TCP segmentine ayırıp
 *    ayrı ayrı yolluyoruz (GoodbyeDPI'daki -f / -e gibi).
 *  - HTTP Host hilesi: "Host: x" → "hoSt:x" ve harf karıştırma (-r -s -m).
 *
 * Yalnızca bağlantının ilk paketi değişiyor; geri kalan her şey olduğu gibi
 * akıyor, bu yüzden hız etkilenmiyor.
 */

data class DesyncOptions(
    val tlsRecordSplit: Boolean = true,
    val tcpSplit: Boolean = true,
    /** TCP parçalarından biri SNI'nin başına denk gelsin. */
    val splitAtSni: Boolean = true,
    /** İlk TCP parçasının boyu (bayt). */
    val firstCut: Int = 2,
    val httpTricks: Boolean = true,
)

object TlsHello {
    const val CONTENT_HANDSHAKE = 0x16

    /** Veri bir TLS el sıkışma kaydıyla mı başlıyor? */
    fun isHandshake(data: ByteArray, len: Int = data.size): Boolean =
        len >= 3 && u8(data, 0) == CONTENT_HANDSHAKE && u8(data, 1) == 3

    /** İlk TLS kaydının toplam boyu (5 bayt başlık dahil) ya da -1. */
    fun recordLength(data: ByteArray, len: Int = data.size): Int =
        if (len >= 5 && isHandshake(data, len)) 5 + u16(data, 3) else -1

    /**
     * ClientHello içindeki SNI alan adının konumu: [başlangıç, bitiş).
     * Bulunamazsa (eksik veri, SNI yok, ClientHello değil) null.
     */
    fun findSni(data: ByteArray, len: Int = data.size): IntRange? {
        try {
            if (!isHandshake(data, len) || len < 9) return null
            if (u8(data, 5) != 1) return null // ClientHello değil
            var p = 9 + 2 + 32 // el sıkışma başlığı + sürüm + random
            p += 1 + u8(data, p).also { need(p, 1, len) } // oturum kimliği
            p += 2 + u16(data, p).also { need(p, 2, len) } // şifre takımları
            p += 1 + u8(data, p).also { need(p, 1, len) } // sıkıştırma
            need(p, 2, len)
            val extEnd = minOf(p + 2 + u16(data, p), len)
            p += 2
            while (p + 4 <= extEnd) {
                val type = u16(data, p)
                val size = u16(data, p + 2)
                p += 4
                if (type == 0x0000) {
                    // server_name: liste boyu(2) · tür(1) · ad boyu(2) · ad
                    need(p, 5, len)
                    if (u8(data, p + 2) != 0) return null
                    val nameLen = u16(data, p + 3)
                    val start = p + 5
                    if (nameLen == 0 || start + nameLen > len) return null
                    return start until start + nameLen
                }
                p += size
            }
        } catch (_: IndexOutOfBoundsException) {
        }
        return null
    }

    fun sniHost(data: ByteArray, len: Int = data.size): String? =
        findSni(data, len)?.let { String(data, it.first, it.last - it.first + 1, Charsets.US_ASCII) }

    private fun need(p: Int, n: Int, len: Int) {
        if (p + n > len) throw IndexOutOfBoundsException()
    }
}

object Desync {
    private const val MAX_PARTS = 6

    /**
     * TLS ilk paketini sırayla yollanacak parçalara çevirir. Parçaların
     * birleşimi sunucu açısından aynı ClientHello'dur.
     */
    fun tls(data: ByteArray, o: DesyncOptions): List<ByteArray> {
        val sni = TlsHello.findSni(data)
        var buf = data
        // Kayıt bölme sonrası, kesim noktasından sonraki her şey 5 bayt kayıyor.
        var shiftFrom = Int.MAX_VALUE

        if (o.tlsRecordSplit && sni != null) {
            val recLen = TlsHello.recordLength(data)
            val at = sni.first + maxOf(1, (sni.last - sni.first + 1) / 2)
            if (recLen in 6..data.size && at in 6 until recLen) {
                buf = splitRecord(data, recLen, at)
                shiftFrom = at
            }
        }

        if (!o.tcpSplit) return listOf(buf)
        val cuts = sortedSetOf<Int>()
        cuts += o.firstCut.coerceIn(1, 64)
        if (o.splitAtSni && sni != null) cuts += map(sni.first + 1, shiftFrom)
        return cut(buf, cuts)
    }

    /**
     * Düz HTTP isteği: Host başlığını DPI'ın aradığı biçimden çıkarıp
     * Host değerinin içinden TCP parçalarına böler.
     */
    fun http(data: ByteArray, o: DesyncOptions): List<ByteArray> {
        val buf = if (o.httpTricks) HttpTricks.apply(data) else data
        if (!o.tcpSplit) return listOf(buf)
        val cuts = sortedSetOf(o.firstCut.coerceIn(1, 64))
        HttpTricks.hostValueStart(buf)?.let { cuts += it + 1 }
        return cut(buf, cuts)
    }

    private fun map(pos: Int, shiftFrom: Int) = if (pos >= shiftFrom) pos + 5 else pos

    /** İlk TLS kaydını payload'ın `at` konumundan iki kayda böler. */
    internal fun splitRecord(data: ByteArray, recLen: Int, at: Int): ByteArray {
        val firstPayload = at - 5
        val secondPayload = recLen - at
        val out = ByteArray(data.size + 5)
        // 1. kayıt: aynı başlık, kısaltılmış boy
        System.arraycopy(data, 0, out, 0, 3)
        out[3] = (firstPayload shr 8).toByte()
        out[4] = firstPayload.toByte()
        System.arraycopy(data, 5, out, 5, firstPayload)
        // 2. kayıt
        val h = at
        System.arraycopy(data, 0, out, h, 3)
        out[h + 3] = (secondPayload shr 8).toByte()
        out[h + 4] = secondPayload.toByte()
        System.arraycopy(data, at, out, h + 5, data.size - at)
        return out
    }

    internal fun cut(buf: ByteArray, cuts: Collection<Int>): List<ByteArray> {
        val parts = ArrayList<ByteArray>()
        var prev = 0
        for (c in cuts) {
            if (c <= prev || c >= buf.size) continue
            if (parts.size == MAX_PARTS - 1) break
            parts += buf.copyOfRange(prev, c)
            prev = c
        }
        parts += buf.copyOfRange(prev, buf.size)
        return parts
    }
}

object HttpTricks {
    private val HOST = "\r\nhost:".toByteArray(Charsets.US_ASCII)

    /** "\r\nHost:" başlığının konumu ('\r' indeksi), yoksa -1. */
    fun findHost(buf: ByteArray): Int {
        val headEnd = headerEnd(buf).let { if (it < 0) buf.size else it }
        outer@ for (i in 0..headEnd - HOST.size) {
            for (j in HOST.indices) {
                val b = buf[i + j].toInt()
                val lower = if (b in 'A'.code..'Z'.code) b + 32 else b
                if (lower != HOST[j].toInt()) continue@outer
            }
            return i
        }
        return -1
    }

    fun hostValueStart(buf: ByteArray): Int? {
        val i = findHost(buf)
        if (i < 0) return null
        var p = i + HOST.size
        while (p < buf.size && buf[p] == ' '.code.toByte()) p++
        return p
    }

    /** GoodbyeDPI -r -s -m: "Host: Example.com" → "hoSt:eXaMpLe.CoM" */
    fun apply(data: ByteArray): ByteArray {
        val i = findHost(data)
        if (i < 0) return data
        val nameAt = i + 2
        var valueAt = i + HOST.size
        var spaces = 0
        while (valueAt + spaces < data.size && data[valueAt + spaces] == ' '.code.toByte()) spaces++
        val out = ByteArray(data.size - spaces)
        System.arraycopy(data, 0, out, 0, valueAt)
        System.arraycopy(data, valueAt + spaces, out, valueAt, data.size - valueAt - spaces)
        "hoSt:".toByteArray(Charsets.US_ASCII).copyInto(out, nameAt)
        // Host değerinde harfleri dönüşümlü büyüt (alan adları büyük/küçük harf duyarsız).
        var p = valueAt
        var k = 0
        while (p < out.size && out[p] != '\r'.code.toByte()) {
            val c = out[p].toInt()
            if (c in 'a'.code..'z'.code && k % 2 == 1) out[p] = (c - 32).toByte()
            else if (c in 'A'.code..'Z'.code && k % 2 == 0) out[p] = (c + 32).toByte()
            p++; k++
        }
        return out
    }

    fun headerEnd(buf: ByteArray, len: Int = buf.size): Int {
        for (i in 0..len - 4) {
            if (buf[i] == '\r'.code.toByte() && buf[i + 1] == '\n'.code.toByte() &&
                buf[i + 2] == '\r'.code.toByte() && buf[i + 3] == '\n'.code.toByte()
            ) return i
        }
        return -1
    }
}

internal fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
internal fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
