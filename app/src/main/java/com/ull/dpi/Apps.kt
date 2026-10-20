package com.ull.dpi

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.Collator
import java.util.Locale

/** Kapsam → "bu uygulamalarda kullanma" listesi. */
object Apps {
    private var cache: String? = null
    private const val ICON_PX = 72

    @Synchronized
    fun list(ctx: Context): String {
        cache?.let { return it }
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val infos = pm.queryIntentActivities(launcher, 0)
        val seen = HashSet<String>()
        val collator = Collator.getInstance(Locale.forLanguageTag("tr"))
        val rows = infos.mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == ctx.packageName || !seen.add(pkg)) return@mapNotNull null
            Triple(pkg, ri.loadLabel(pm).toString(), ri)
        }.sortedWith { a, b -> collator.compare(a.second, b.second) }

        val arr = JSONArray()
        for ((pkg, name, ri) in rows) {
            val o = JSONObject().put("pkg", pkg).put("name", name)
            runCatching {
                val dr = ri.loadIcon(pm)
                val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
                dr.setBounds(0, 0, ICON_PX, ICON_PX)
                dr.draw(Canvas(bmp))
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                o.put("icon", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            }
            arr.put(o)
        }
        return arr.toString().also { cache = it }
    }
}
