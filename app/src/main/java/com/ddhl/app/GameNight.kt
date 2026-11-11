package com.ddhl.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Decides when game-night live mode should switch on. Android only lets an app start a
 * long-running service from the background in a few situations; an exact alarm firing is one,
 * so we set an alarm for game time (or "right now" if your players are already on the ice).
 */
object GameNight {
    private const val TAG = "DdhlGameNight"

    fun plan(ctx: Context, p: Prefs, info: LiveInfo?) {
        if (!p.liveModeAuto || p.myTeam == null || info == null || LiveModeService.running) return
        val now = System.currentTimeMillis()
        if (now < p.liveModeSnoozeUntil) return

        val mine = info.mine
        if (mine != null && mine.me.gamesInProgress > 0) {
            scheduleAt(ctx, now + 2_000)
            return
        }
        val next = info.nextGameAt ?: return
        if (next > now && next - now < TimeUnit.HOURS.toMillis(24)) {
            // A few minutes after puck drop, so there's something to show.
            scheduleAt(ctx, next + TimeUnit.MINUTES.toMillis(5))
        }
    }

    private fun scheduleAt(ctx: Context, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, GameAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        Log.i(TAG, "live mode alarm set for $at")
    }

    /** After you stop live mode yourself, leave it off until tomorrow morning. */
    fun snoozeUntilMorning(p: Prefs) {
        val zone = ZoneId.systemDefault()
        val sixAm = LocalDate.now(zone).let { d ->
            if (LocalTime.now(zone).hour < 6) d else d.plusDays(1)
        }.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
        p.liveModeSnoozeUntil = sixAm
    }
}

class GameAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        if (!p.liveModeAuto || p.myTeam == null) return
        if (System.currentTimeMillis() < p.liveModeSnoozeUntil) return
        try {
            LiveModeService.start(ctx)
        } catch (e: Exception) {
            Log.w("DdhlGameNight", "couldn't start live mode", e)
        }
    }
}
