package com.ddhl.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Team logos from the league site, cached on the phone. The daily recap data (today.json) lists
 * each team's short name (TEMU, BAR…) and logo file. Downloads happen in the background only;
 * the widget just reads the cached files.
 */
object TeamLogos {
    private const val TAG = "DdhlLogos"
    private const val SIZE = 144 // px; plenty for a ~32dp widget icon, small enough for widgets
    private val MAX_AGE = TimeUnit.DAYS.toMillis(7)

    private fun dir(ctx: Context) = File(ctx.filesDir, "logos").apply { mkdirs() }
    private fun fileFor(ctx: Context, abbr: String) =
        File(dir(ctx), abbr.replace(Regex("[^A-Za-z0-9]"), "_") + ".png")

    /** Cached logo for a team, or null if we don't have it yet. Safe on the main thread. */
    fun cached(ctx: Context, abbr: String): Bitmap? {
        val f = fileFor(ctx, abbr)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    /** Makes sure these teams' logos are cached. Network: call from a background thread. */
    fun ensure(ctx: Context, vararg teams: Side) {
        val missing = teams.filter {
            val f = fileFor(ctx, it.abbr)
            !f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE
        }
        if (missing.isEmpty()) return
        val urls = try {
            logoUrls()
        } catch (e: Exception) {
            Log.w(TAG, "couldn't read logo list", e); return
        }
        for (t in missing) {
            val url = urls[t.abbr] ?: urls[t.name.lowercase()] ?: continue
            try {
                val raw = download(url) ?: continue
                val out = fileFor(ctx, t.abbr)
                out.outputStream().use { round(raw).compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (e: Exception) {
                Log.w(TAG, "logo download failed for ${t.abbr}", e)
            }
        }
    }

    /** Short name (and lower-cased full name) -> absolute logo URL. */
    private fun logoUrls(): Map<String, String> {
        val teams = DdhlApi.today().optJSONArray("teams") ?: return emptyMap()
        val out = HashMap<String, String>()
        for (i in 0 until teams.length()) {
            val t = teams.getJSONObject(i)
            val logo = t.optString("logo").ifEmpty { null } ?: continue
            val url = if (logo.startsWith("http")) logo else Ddhl.RECAP + logo.removePrefix("/")
            t.optString("short").ifEmpty { null }?.let { out[it] = url }
            t.optString("name").ifEmpty { null }?.let { out[it.lowercase()] = url }
        }
        return out
    }

    private fun download(url: String): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        try {
            if (c.responseCode !in 200..299) return null
            val src = c.inputStream.use { BitmapFactory.decodeStream(it) } ?: return null
            return Bitmap.createScaledBitmap(src, SIZE, SIZE, true)
        } finally {
            c.disconnect()
        }
    }

    /** Rounded-square crop so logos look tidy on any background. */
    private fun round(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val r = SIZE * 0.22f
        Canvas(out).drawRoundRect(RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), r, r, paint)
        return out
    }
}
