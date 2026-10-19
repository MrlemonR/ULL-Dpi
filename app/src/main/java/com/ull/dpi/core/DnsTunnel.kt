package com.ull.dpi.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/*
 * VPN tüneli YALNIZCA sahte DNS adresini (10.111.222.2) yönlendiriyor; başka
 * hiçbir trafik tünele girmiyor, internet doğrudan operatörden akmaya devam
 * ediyor. Telefonun DNS soruları buraya düşüyor, biz de zehirlenmemiş
 * sunucuya sorup cevabı tünele geri yazıyoruz.
 */
class DnsTunnel(
    private val input: InputStream,
    private val output: OutputStream,
    private val resolver: Resolver,
    private val logQueries: () -> Boolean = { false },
    /** Tünel bloklamayan modda: veri gelene kadar beklet (Android'de poll). */
    private val idle: () -> Unit = { Thread.sleep(20) },
) {
    private val pool = Executors.newFixedThreadPool(6) { r -> Thread(r, "dns").apply { isDaemon = true } }

    @Volatile private var running = true

    /** Tünel kapanana kadar bloklar. */
    fun run() {
        val buf = ByteArray(32 * 1024)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) { idle(); continue }
                val pkt = Udp4.parse(buf, n) ?: continue
                if (pkt.dstPort != 53) continue
                pool.execute { answer(pkt) }
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } catch (_: RejectedExecutionException) {
        } finally {
            stop()
        }
    }

    fun stop() {
        running = false
        pool.shutdownNow()
    }

    private fun answer(q: Udp4) {
        Stats.dnsQueries.incrementAndGet()
        val resp = try {
            resolver.exchange(q.payload).also {
                if (logQueries()) DnsMessage.questionName(q.payload)?.let { n -> Logbook.add("DNS", n) }
            }
        } catch (e: Exception) {
            Stats.dnsFailed.incrementAndGet()
            Logbook.add("DNS", "${DnsMessage.questionName(q.payload) ?: "?"} ✗ ${e.javaClass.simpleName}")
            DnsMessage.servfail(q.payload)
        }
        val out = Udp4.build(q.dst, q.dstPort, q.src, q.srcPort, resp)
        try {
            synchronized(output) { output.write(out) }
        } catch (_: IOException) {
        }
    }
}
