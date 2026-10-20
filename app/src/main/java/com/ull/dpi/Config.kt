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
    /** Widget ölçüm aralığı (sn), 0 = kapalı. */
    val probeSecs: Int = 10,
    /** Hız panelinde sürekli ölçüm (widget'taki gibi). */
    val continuous: Boolean = false,
    /** Mobil verideyken widget/sürekli ölçüm yapma. */
    val mobilePause: Boolean = true,
    /** VPN'in hiç dokunmayacağı uygulamalar (paket adları). */
    val excludedApps: List<String> = emptyList(),
    /** İstisna siteler: parçalama yok, sistem DNS'i, proxy'siz (bankalar vb.). */
    val bypassDomains: List<String> = DEFAULT_BYPASS,
    /** Parçalamayı reddettiği görülüp otomatik eklenen siteler. */
    val autoBypass: List<String> = emptyList(),
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
        .put("probeSecs", probeSecs)
        .put("continuous", continuous)
        .put("mobilePause", mobilePause)
        .put("excludedApps", JSONArray(excludedApps))
        .put("bypassDomains", JSONArray(bypassDomains))
        .put("autoBypass", JSONArray(autoBypass))

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
        exceptions = Scope(false, allBypass()),
    )

    fun allBypass(): List<String> = (bypassDomains + autoBypass).distinct()

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

        /** Türkiye'de parçalı ClientHello'yu sevmeyen bankalar, kamu ve ödeme siteleri. */
        val DEFAULT_BYPASS = listOf(
            "yapikredi.com.tr", "ykb.com", "garantibbva.com.tr", "garanti.com.tr", "isbank.com.tr", "isbank.com",
            "akbank.com", "ziraatbank.com.tr", "ziraat.com.tr", "halkbank.com.tr", "vakifbank.com.tr",
            "qnb.com.tr", "qnbfinansbank.com", "denizbank.com", "teb.com.tr", "ing.com.tr", "ingbank.com.tr",
            "kuveytturk.com.tr", "albaraka.com.tr", "odeabank.com.tr", "fibabanka.com.tr", "sekerbank.com.tr",
            "anadolubank.com.tr", "enpara.com", "papara.com", "ininal.com", "tosla.com", "param.com.tr",
            "paycell.com.tr", "iyzico.com", "turkiye.gov.tr", "gib.gov.tr", "sgk.gov.tr", "setcard.com.tr",
            "pluxee.com.tr", "multinet.com.tr", "turkcell.com.tr",
        )

        private fun strings(o: JSONObject, key: String): List<String>? =
            o.optJSONArray(key)?.let { a -> List(a.length()) { a.optString(it).trim().lowercase() } }
                ?.filter { it.isNotEmpty() }?.distinct()

        /** Otomatik bulunan istisnayı kalıcı olarak kaydet. */
        @Synchronized
        fun addAutoBypass(ctx: Context, host: String) {
            val c = load(ctx)
            val h = host.lowercase().trimEnd('.')
            if (c.allBypass().any { h == it || h.endsWith(".$it") }) return
            save(ctx, c.copy(autoBypass = c.autoBypass + h))
        }

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
                probeSecs = o.optInt("probeSecs", d.probeSecs).coerceIn(0, 600),
                continuous = o.optBoolean("continuous", d.continuous),
                mobilePause = o.optBoolean("mobilePause", d.mobilePause),
                excludedApps = o.optJSONArray("excludedApps")
                    ?.let { a -> List(a.length()) { a.optString(it) } }
                    ?.filter { it.isNotBlank() }?.distinct() ?: d.excludedApps,
                bypassDomains = strings(o, "bypassDomains") ?: d.bypassDomains,
                autoBypass = strings(o, "autoBypass") ?: d.autoBypass,
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
