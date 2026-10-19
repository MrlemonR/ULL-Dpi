package com.ull.dpi

import android.content.Context
import com.ull.dpi.core.CachingResolver
import com.ull.dpi.core.DesyncOptions
import com.ull.dpi.core.DohResolver
import com.ull.dpi.core.ProxySettings
import com.ull.dpi.core.Resolver
import com.ull.dpi.core.Scope
import com.ull.dpi.core.UdpResolver
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kullanıcı ayarları. Arayüz JSON olarak okuyup yazıyor; servis buradan
 * proxy ve DNS ayarlarını kuruyor.
 */
data class Config(
    val preset: String = "turkey",
    val dns: String = "yandex",
    val tlsrec: Boolean = true,
    val split: Boolean = true,
    val splitAtSni: Boolean = true,
    val firstCut: Int = 2,
    val delayMs: Int = 3,
    val httpTricks: Boolean = true,
    val scopeAll: Boolean = true,
    val domains: List<String> = DEFAULT_DOMAINS,
    val logDns: Boolean = false,
    val port: Int = 18881,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("preset", preset)
        .put("dns", dns)
        .put("tlsrec", tlsrec)
        .put("split", split)
        .put("splitAtSni", splitAtSni)
        .put("firstCut", firstCut)
        .put("delayMs", delayMs)
        .put("httpTricks", httpTricks)
        .put("scopeAll", scopeAll)
        .put("domains", JSONArray(domains))
        .put("logDns", logDns)
        .put("port", port)

    fun proxySettings() = ProxySettings(
        desync = DesyncOptions(
            tlsRecordSplit = tlsrec,
            tcpSplit = split,
            splitAtSni = splitAtSni,
            firstCut = firstCut,
            httpTricks = httpTricks,
        ),
        scope = Scope(scopeAll, domains),
        delayMs = delayMs,
    )

    fun resolver(): CachingResolver = CachingResolver(upstream(dns))

    fun summary(): String {
        val m = buildList {
            if (tlsrec) add("tlsrec")
            if (split) add("split")
            if (httpTricks) add("host")
        }.ifEmpty { listOf("sadece dns") }
        return "${upstream(dns).label} · ${m.joinToString("+")}"
    }

    companion object {
        val DEFAULT_DOMAINS = listOf(
            "discord.com", "discord.gg", "discordapp.com", "discordapp.net",
            "discord.media", "discordcdn.com", "discord.dev", "discord.new",
            "discordstatus.com", "dis.gd", "discord.co",
        )

        fun upstream(id: String): Resolver = when (id) {
            "cloudflare" -> DohResolver("https://1.1.1.1/dns-query")
            "google" -> DohResolver("https://8.8.8.8/dns-query")
            "quad9" -> DohResolver("https://9.9.9.9/dns-query")
            // GoodbyeDPI-Turkey'nin kullandığı: Yandex DNS, standart dışı port
            else -> UdpResolver("77.88.8.8", 1253)
        }

        fun fromJson(o: JSONObject): Config {
            val d = Config()
            val list = o.optJSONArray("domains")?.let { a -> List(a.length()) { a.optString(it) } } ?: d.domains
            return Config(
                preset = o.optString("preset", d.preset),
                dns = o.optString("dns", d.dns),
                tlsrec = o.optBoolean("tlsrec", d.tlsrec),
                split = o.optBoolean("split", d.split),
                splitAtSni = o.optBoolean("splitAtSni", d.splitAtSni),
                firstCut = o.optInt("firstCut", d.firstCut).coerceIn(1, 16),
                delayMs = o.optInt("delayMs", d.delayMs).coerceIn(0, 100),
                httpTricks = o.optBoolean("httpTricks", d.httpTricks),
                scopeAll = o.optBoolean("scopeAll", d.scopeAll),
                domains = list.map { it.trim() }.filter { it.isNotEmpty() },
                logDns = o.optBoolean("logDns", d.logDns),
                port = o.optInt("port", d.port).coerceIn(1024, 65000),
            )
        }

        private const val PREFS = "ull-dpi"
        private const val KEY = "config"

        fun load(ctx: Context): Config {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return Config()
            return runCatching { fromJson(JSONObject(raw)) }.getOrDefault(Config())
        }

        fun save(ctx: Context, c: Config) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, c.toJson().toString()).apply()
        }
    }
}
