package com.ddhl.app

import android.content.Context
import androidx.core.content.edit

/** Alert settings plus the "last thing we saw" for each check, stored on the phone. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ddhl", Context.MODE_PRIVATE)

    private fun str(key: String) = sp.getString(key, null)
    private fun putStr(key: String, v: String?) = sp.edit { putString(key, v) }
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = sp.edit { putBoolean(key, v) }
    private fun long(key: String) = sp.getLong(key, 0L)
    private fun putLong(key: String, v: Long) = sp.edit { putLong(key, v) }

    // ---- Settings ----
    var recapAlerts: Boolean
        get() = bool("recapAlerts", true); set(v) = putBool("recapAlerts", v)
    var rankingsAlerts: Boolean
        get() = bool("rankingsAlerts", true); set(v) = putBool("rankingsAlerts", v)
    /** Lead changes and final result in my matchup. */
    var liveAlerts: Boolean
        get() = bool("liveAlerts", true); set(v) = putBool("liveAlerts", v)
    var playerAlerts: Boolean
        get() = bool("playerAlerts", true); set(v) = putBool("playerAlerts", v)
    var categoryAlerts: Boolean
        get() = bool("categoryAlerts", true); set(v) = putBool("categoryAlerts", v)
    var liveModeAuto: Boolean
        get() = bool("liveModeAuto", true); set(v) = putBool("liveModeAuto", v)
    var myTeam: String?
        get() = str("myTeam"); set(v) = putStr("myTeam", v)
    var askedForTeam: Boolean
        get() = bool("askedForTeam", false); set(v) = putBool("askedForTeam", v)

    // ---- Scheduling ----
    var nextDueAt: Long
        get() = long("nextDueAt"); set(v) = putLong("nextDueAt", v)
    var liveModeSnoozeUntil: Long
        get() = long("liveModeSnoozeUntil"); set(v) = putLong("liveModeSnoozeUntil", v)
    var lastUpdateCheck: Long
        get() = long("lastUpdateCheck"); set(v) = putLong("lastUpdateCheck", v)
    var notifiedUpdateVersion: Long
        get() = long("notifiedUpdateVersion"); set(v) = putLong("notifiedUpdateVersion", v)

    // ---- Last-seen state (null = no baseline yet; the next check records one silently) ----
    var seenRecapNight: String?
        get() = str("seenRecapNight"); set(v) = putStr("seenRecapNight", v)
    var seenRankingsKey: String?
        get() = str("seenRankingsKey"); set(v) = putStr("seenRankingsKey", v)
    var seenLiveTeam: String?
        get() = str("seenLiveTeam"); set(v) = putStr("seenLiveTeam", v)
    var seenLivePeriod: String?
        get() = str("seenLivePeriod"); set(v) = putStr("seenLivePeriod", v)
    var seenLiveStatus: String?
        get() = str("seenLiveStatus"); set(v) = putStr("seenLiveStatus", v)
    var seenLiveRecord: String?
        get() = str("seenLiveRecord"); set(v) = putStr("seenLiveRecord", v)
    var seenLiveOpponent: String?
        get() = str("seenLiveOpponent"); set(v) = putStr("seenLiveOpponent", v)
    var seenMomentsKey: String?
        get() = str("seenMomentsKey"); set(v) = putStr("seenMomentsKey", v)
    var seenMomentIds: Set<String>
        get() = str("seenMomentIds")?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        set(v) = putStr("seenMomentIds", v.joinToString("\n"))
    var simulateMoments: Boolean
        get() = bool("simulateMoments", false); set(v) = putBool("simulateMoments", v)

    /** Latest matchup numbers for the home-screen widget (JSON). */
    var widgetState: String?
        get() = str("widgetState"); set(v) = putStr("widgetState", v)

    /** Rotating ids so several scoring alerts in one night don't replace each other. */
    fun nextNotificationId(): Int {
        val n = (sp.getInt("notifCounter", 0) + 1) % 40
        sp.edit { putInt("notifCounter", n) }
        return 100 + n
    }

    fun clearLive() {
        seenLiveTeam = null; seenLivePeriod = null; seenLiveStatus = null
        seenLiveRecord = null; seenLiveOpponent = null
        seenMomentsKey = null; seenMomentIds = emptySet()
    }
}
