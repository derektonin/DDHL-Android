package com.ddhl.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    const val CH_RECAP = "recap"
    const val CH_RANKINGS = "rankings"
    const val CH_LIVE = "live"
    const val CH_PLAYERS = "players"
    const val CH_CATEGORIES = "categories"
    const val CH_LIVE_MODE = "live_mode"
    const val CH_UPDATES = "updates"

    const val ID_RECAP = 1
    const val ID_RANKINGS = 2
    const val ID_LIVE = 3
    const val ID_FINAL = 4
    const val ID_LIVE_MODE = 10
    const val ID_UPDATE = 20

    private const val YELLOW = 0xFFF2C230.toInt()

    /** One channel per alert type, so each can also be muted from Android's own settings. */
    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: String, importance: Int, desc: String) =
            nm.createNotificationChannel(NotificationChannel(id, name, importance).apply { description = desc })
        ch(CH_RECAP, "Daily recap", NotificationManager.IMPORTANCE_DEFAULT, "When the morning recap is posted")
        ch(CH_RANKINGS, "Power rankings", NotificationManager.IMPORTANCE_DEFAULT, "When new power rankings are published")
        ch(CH_LIVE, "Lead changes", NotificationManager.IMPORTANCE_HIGH, "You take or lose the lead in your matchup, and final results")
        ch(CH_PLAYERS, "My players' points", NotificationManager.IMPORTANCE_HIGH, "Goals, assists and goalie results for your team")
        ch(CH_CATEGORIES, "Category flips", NotificationManager.IMPORTANCE_DEFAULT, "A category in your matchup changes hands")
        ch(CH_LIVE_MODE, "Game-night live mode", NotificationManager.IMPORTANCE_LOW, "The live score shown while your games are on")
        ch(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT, "A new version of the DDHL app is available")
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun openPage(ctx: Context, id: Int, url: String): PendingIntent {
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Shows a notification that opens [url] inside the app when tapped.
     * With [lines], it shows as a list (several plays in one alert).
     */
    fun show(
        ctx: Context, channel: String, id: Int, title: String, text: String, url: String,
        lines: List<String>? = null,
    ) {
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_ddhl)
            .setColor(YELLOW)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPage(ctx, id, url))
            .setAutoCancel(true)
        if (lines != null && lines.size > 1) {
            val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
            lines.take(6).forEach { style.addLine(it) }
            if (lines.size > 6) style.setSummaryText("+${lines.size - 6} more")
            b.setStyle(style)
        } else {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        post(ctx, id, b.build())
    }

    @SuppressLint("MissingPermission")
    fun post(ctx: Context, id: Int, n: Notification) {
        if (!canNotify(ctx)) return
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}
