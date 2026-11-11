package com.ddhl.app

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Game-night live mode: while your players are on the ice, checks the site every 2 minutes,
 * keeps a live score pinned in the notification shade, and sends scoring/category alerts
 * right away instead of every 15 minutes. Stops by itself 15 minutes after your last game
 * ends (or after 6 hours), or when you tap Stop.
 */
class LiveModeService : Service() {

    companion object {
        private const val TAG = "DdhlLiveMode"
        const val ACTION_STOP = "com.ddhl.app.STOP_LIVE_MODE"
        private val POLL_MS = TimeUnit.MINUTES.toMillis(2)
        private val IDLE_STOP_MS = TimeUnit.MINUTES.toMillis(15)
        private val MAX_MS = TimeUnit.HOURS.toMillis(6)

        @Volatile
        var running = false
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, LiveModeService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, LiveModeService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            GameNight.snoozeUntilMorning(Prefs(this))
            if (!running) {
                // Started only to deliver Stop: satisfy Android's foreground rule, then exit.
                goForeground(build("Stopping live mode…", "", null))
            }
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground(build("Game-night live mode", "Loading your matchup…", null))
        if (job == null) {
            running = true
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ddhl:livemode")
                .apply { acquire(MAX_MS) }
            job = scope.launch { loop() }
            Widget.render(this)
        }
        return START_NOT_STICKY
    }

    private suspend fun loop() {
        val started = System.currentTimeMillis()
        var idleSince: Long? = null
        while (scope.isActive) {
            val p = Prefs(this)
            val team = p.myTeam ?: break
            try {
                val info = DdhlApi.live(team)
                val m = info.mine ?: break
                LiveAlerts.process(this, p, info)
                Widget.save(this, info)
                Notifier.post(this, Notifier.ID_LIVE_MODE, ongoing(info, m))

                val now = System.currentTimeMillis()
                if (m.me.gamesInProgress > 0) {
                    idleSince = null
                } else {
                    val since = idleSince ?: now
                    idleSince = since
                    if (now - since > IDLE_STOP_MS) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "poll failed", e)
            }
            if (System.currentTimeMillis() - started > MAX_MS) break
            delay(POLL_MS)
        }
        stopSelf()
    }

    private fun ongoing(info: LiveInfo, m: MyMatchup): android.app.Notification {
        val lead = when (m.me.state()) { 1 -> "Leading"; -1 -> "Trailing"; else -> "Tied" }
        val title = "${m.me.abbr} ${m.me.record} vs ${m.opp.abbr} · $lead"
        val parts = mutableListOf<String>()
        m.winChance?.let { parts += "Win chance ${(it * 100).roundToInt()}%" }
        parts += when (m.me.gamesInProgress) {
            0 -> "No games on right now"
            1 -> "1 of your games live"
            else -> "${m.me.gamesInProgress} of your games live"
        }
        parts += "Updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        return build(title, parts.joinToString(" · "), info)
    }

    private fun build(title: String, text: String, info: LiveInfo?): android.app.Notification {
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, LiveModeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Notifier.CH_LIVE_MODE)
            .setSmallIcon(R.drawable.ic_stat_ddhl)
            .setColor(0xFFF2C230.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(info?.periodLabel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(Notifier.openPage(this, Notifier.ID_LIVE_MODE, Ddhl.LIVE))
            .addAction(0, "Stop for tonight", stopPi)
            .build()
    }

    private fun goForeground(n: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.ID_LIVE_MODE, n, type)
    }

    override fun onDestroy() {
        running = false
        job?.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Widget.render(this)
        super.onDestroy()
    }
}
