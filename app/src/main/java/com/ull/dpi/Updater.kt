package com.ull.dpi

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.ull.dpi.core.Logbook
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import kotlin.concurrent.thread

/*
 * AYAR → GÜNCELLEME. GitHub Releases'taki son sürüme bakar; daha yeniyse
 * APK'yı indirip Android'in kurulum penceresini açar. Her sürüm aynı
 * anahtarla imzalandığı için güncelleme mevcut uygulamanın üstüne kurulur:
 * ayarlar, istisnalar ve ana ekrandaki widget'lar yerinde kalır.
 */
object Updater {
    private const val API = "https://api.github.com/repos/MrlemonR/ULL-Dpi/releases/latest"
    private const val ASSET = "ULL-DPI.apk"

    @Volatile var phase = "idle" // idle | checking | latest | available | downloading | installing | error
    @Volatile var latest = ""
    @Volatile var notes = ""
    @Volatile var progress = 0.0
    @Volatile var error: String? = null
    @Volatile private var apkUrl = ""
    @Volatile private var size = 0L

    fun current(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "0"

    /** "v0.1.12" > "0.1.9" gibi sürümleri sayı sayı karşılaştır. */
    fun newer(tag: String, cur: String): Boolean {
        fun parts(v: String) = v.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(tag)
        val b = parts(cur)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun http(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ULL-DPI")
        }

    fun check(ctx: Context) {
        if (phase == "checking" || phase == "downloading" || phase == "installing") return
        phase = "checking"; error = null
        thread(isDaemon = true, name = "update-check") {
            try {
                val c = http(API)
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val body = try {
                    if (c.responseCode != 200) throw IOException("GitHub HTTP ${c.responseCode}")
                    c.inputStream.bufferedReader().readText()
                } finally {
                    c.disconnect()
                }
                val o = JSONObject(body)
                latest = o.optString("tag_name")
                notes = o.optString("body").lineSequence()
                    .filterNot { it.startsWith("**İndir") }.joinToString("\n").trim()
                val assets = o.optJSONArray("assets")
                apkUrl = ""
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == ASSET) {
                        apkUrl = a.optString("browser_download_url")
                        size = a.optLong("size")
                    }
                }
                phase = if (newer(latest, current(ctx)) && apkUrl.isNotEmpty()) "available" else "latest"
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                phase = "error"
            }
        }
    }

    /** İndir ve kur. Bilinmeyen kaynak izni yoksa önce o ayar açılır. */
    fun install(ctx: Context) {
        if (phase != "available" && phase != "error") return
        if (apkUrl.isEmpty()) return check(ctx)
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            error = "önce 'bu kaynaktan yüklemeye izin ver'i aç, sonra tekrar bas"
            phase = "available"
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        phase = "downloading"; progress = 0.0; error = null
        val app = ctx.applicationContext
        thread(isDaemon = true, name = "update-install") {
            try {
                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(app.packageName) }
                val id = pi.createSession(params)
                pi.openSession(id).use { session ->
                    val c = http(apkUrl)
                    try {
                        if (c.responseCode != 200) throw IOException("indirme HTTP ${c.responseCode}")
                        val total = c.contentLengthLong.takeIf { it > 0 } ?: size
                        session.openWrite(ASSET, 0, total.takeIf { it > 0 } ?: -1).use { out ->
                            c.inputStream.use { inp ->
                                val buf = ByteArray(64 * 1024)
                                var got = 0L
                                while (true) {
                                    val n = inp.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    got += n
                                    if (total > 0) progress = got.toDouble() / total
                                }
                                session.fsync(out)
                            }
                        }
                    } finally {
                        c.disconnect()
                    }
                    phase = "installing"
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val status = PendingIntent.getBroadcast(app, 30, Intent(app, InstallReceiver::class.java), flags)
                    session.commit(status.intentSender)
                }
                Logbook.add("SYS", "güncelleme $latest indirildi ▸ kuruluyor")
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                phase = "error"
            }
        }
    }
}

/** PackageInstaller sonucu: onay gerekiyorsa sistem penceresini aç. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.phase = "latest"
            else -> {
                Updater.error = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "kurulamadı"
                Updater.phase = "error"
            }
        }
    }
}
