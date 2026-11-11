package com.ddhl.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks a small update.json you host anywhere that gives a direct-download link
 * (Dropbox, Google Drive, GitHub...), e.g.
 *   {"versionCode": 3, "versionName": "0.3", "apkUrl": "https://...", "notes": "What's new"}
 * If it lists a newer version, the app offers to download and install it.
 * Android itself refuses any update not signed with the same key as the installed app.
 */
object Updater {

    data class Release(val versionCode: Long, val versionName: String, val apkUrl: String, val notes: String)

    val enabled get() = Ddhl.UPDATE_MANIFEST_URL.isNotBlank()

    fun currentVersion(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    /** Returns the newer release, or null if up to date / not set up. */
    fun fetchNewer(ctx: Context): Release? {
        if (!enabled) return null
        val j = JSONObject(DdhlApi.getText(Ddhl.UPDATE_MANIFEST_URL))
        val r = Release(
            versionCode = j.getLong("versionCode"),
            versionName = j.optString("versionName", j.getLong("versionCode").toString()),
            apkUrl = j.getString("apkUrl"),
            notes = j.optString("notes"),
        )
        return if (r.versionCode > currentVersion(ctx)) r else null
    }

    /** Background: post one notification per new version. */
    fun checkAndNotify(ctx: Context) {
        val r = fetchNewer(ctx) ?: return
        val p = Prefs(ctx)
        if (p.notifiedUpdateVersion == r.versionCode) return
        p.notifiedUpdateVersion = r.versionCode
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SHOW_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, Notifier.ID_UPDATE, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, Notifier.CH_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_ddhl)
            .setColor(0xFFF2C230.toInt())
            .setContentTitle("DDHL app ${r.versionName} is available")
            .setContentText(r.notes.ifBlank { "Tap to update." })
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.notes.ifBlank { "Tap to update." }))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        Notifier.post(ctx, Notifier.ID_UPDATE, n)
    }

    /** Downloads the APK and checks it really is a newer DDHL build. Run off the main thread. */
    fun download(ctx: Context, r: Release): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "ddhl-${r.versionName}.apk")
        val c = URL(r.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        try {
            if (c.responseCode !in 200..299) throw IOException("Download failed (HTTP ${c.responseCode})")
            c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        val info = ctx.packageManager.getPackageArchiveInfo(out.path, 0)
        if (info == null || info.packageName != ctx.packageName) {
            out.delete()
            throw IOException("The downloaded file isn't a DDHL app. Check the apkUrl link is a direct download.")
        }
        if (PackageInfoCompat.getLongVersionCode(info) <= currentVersion(ctx)) {
            out.delete()
            throw IOException("The downloaded app isn't newer than this one.")
        }
        return out
    }

    /** Hands the APK to Android's installer (asks for "install unknown apps" permission first if needed). */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
