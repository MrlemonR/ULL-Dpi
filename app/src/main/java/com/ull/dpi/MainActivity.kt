package com.ull.dpi

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.ull.dpi.core.Logbook
import com.ull.dpi.core.Stats
import org.json.JSONArray
import org.json.JSONObject

/*
 * Arayüz ULL-Bot'unki gibi HTML/CSS (assets/web). Bu aktivite onu bir
 * WebView'da açıp `window.DPI` köprüsüyle servise bağlıyor.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#050505")) }
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#050505"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(Bridge(), "DPI")
            webViewClient = object : WebViewClient() {
                // Arayüz dışına gidilmesin.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    !request.url.toString().startsWith("file:///android_asset/")
            }
        }
        root.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Android 15 kenardan kenara çiziyor: durum/gezinme çubuğu kadar boşluk bırak.
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        web.loadUrl("file:///android_asset/web/index.html")
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        web.evaluateJavascript("window.onBack ? window.onBack() : false") { handled ->
            if (handled != "true") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun requestStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        val ask = VpnService.prepare(this)
        if (ask != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(ask, REQ_VPN)
        } else {
            DpiVpnService.send(this, DpiVpnService.ACTION_START)
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VPN) return
        if (resultCode == RESULT_OK) {
            DpiVpnService.send(this, DpiVpnService.ACTION_START)
        } else {
            VpnState.error = "VPN izni verilmedi"
            Logbook.add("ERR", "VPN izni reddedildi")
        }
    }

    override fun onResume() {
        super.onResume()
        // Hızlı ayar kutucuğundan "izin gerekiyor" diye açıldıysak başlat.
        if (intent?.getBooleanExtra(EXTRA_START, false) == true) {
            intent.removeExtra(EXTRA_START)
            requestStart()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /** JS'ten çağrılıyor (WebView'un kendi iş parçacığında). */
    inner class Bridge {
        @JavascriptInterface
        fun start() = runOnUiThread { requestStart() }

        @JavascriptInterface
        fun stop() = DpiVpnService.send(this@MainActivity, DpiVpnService.ACTION_STOP)

        @JavascriptInterface
        fun getConfig(): String = Config.load(this@MainActivity).toJson().toString()

        @JavascriptInterface
        fun saveConfig(json: String): String = try {
            val c = Config.fromJson(JSONObject(json))
            Config.save(this@MainActivity, c)
            if (VpnState.running) DpiVpnService.send(this@MainActivity, DpiVpnService.ACTION_RELOAD)
            "ok"
        } catch (e: Exception) {
            e.message ?: "hata"
        }

        @JavascriptInterface
        fun status(): String = JSONObject()
            .put("running", VpnState.running)
            .put("starting", VpnState.starting)
            .put("error", VpnState.error ?: JSONObject.NULL)
            .put("since", VpnState.since)
            .put("port", VpnState.port)
            .put("summary", VpnState.summary)
            .put("active", Stats.active.get())
            .put("total", Stats.total.get())
            .put("desynced", Stats.desynced.get())
            .put("failed", Stats.failed.get())
            .put("up", Stats.up.get())
            .put("down", Stats.down.get())
            .put("dns", Stats.dnsQueries.get())
            .put("dnsCached", Stats.dnsCached.get())
            .put("dnsFailed", Stats.dnsFailed.get())
            .put("proxyApi", Build.VERSION.SDK_INT >= 29)
            .toString()

        @JavascriptInterface
        fun logs(since: Double): String {
            val arr = JSONArray()
            for (l in Logbook.since(since.toLong())) {
                arr.put(JSONObject().put("id", l.id).put("t", l.time).put("tag", l.tag).put("msg", l.text))
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun clearLogs() = Logbook.clear()

        @JavascriptInterface
        fun version(): String = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    companion object {
        const val EXTRA_START = "start"
        private const val REQ_VPN = 1
        private const val REQ_NOTIF = 2
    }
}
