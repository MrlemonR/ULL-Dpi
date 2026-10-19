package com.ull.dpi.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Arayüzdeki terminal için halka tampon. */
object Logbook {
    private const val MAX = 600

    class Line(val id: Long, val time: String, val tag: String, val text: String)

    private val lines = ArrayDeque<Line>()
    private var next = 1L
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    @Synchronized
    fun add(tag: String, text: String) {
        lines.addLast(Line(next++, fmt.format(Date()), tag, text))
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun since(id: Long): List<Line> = lines.filter { it.id > id }

    @Synchronized
    fun clear() = lines.clear()
}

/** Sayaçlar — arayüz saniyede bir okuyor. */
object Stats {
    val active = AtomicInteger()
    val total = AtomicLong()
    val desynced = AtomicLong()
    val failed = AtomicLong()
    val up = AtomicLong()
    val down = AtomicLong()
    val dnsQueries = AtomicLong()
    val dnsCached = AtomicLong()
    val dnsFailed = AtomicLong()

    fun reset() {
        active.set(0)
        listOf(total, desynced, failed, up, down, dnsQueries, dnsCached, dnsFailed).forEach { it.set(0) }
    }
}
