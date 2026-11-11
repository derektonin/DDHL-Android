package com.ddhl.app

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Wakes every 15 minutes (Android's minimum). Smarter timing: it only goes to the site when a
 * check is due. While NHL games are on, that's every wake-up; on quiet days every 30 minutes;
 * overnight (2-8 am) every 2 hours. It always checks right when the site says the next game starts.
 *
 * The first check after install, or after an alert is switched on, only records a starting point,
 * so you don't get a burst of old news.
 */
class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val p = Prefs(ctx)
        val force = inputData.getBoolean(KEY_FORCE, false)
        val now = System.currentTimeMillis()
        if (!force && now < p.nextDueAt - 60_000) return@withContext Result.success()

        step("recap") { if (p.recapAlerts) checkRecap(p) else p.seenRecapNight = null }
        step("rankings") { if (p.rankingsAlerts) checkRankings(p) else p.seenRankingsKey = null }

        var info: LiveInfo? = null
        step("live") {
            val team = p.myTeam
            val fetched = DdhlApi.live(team)
            info = fetched
            if (team == null) {
                p.clearLive()
            } else if (!LiveModeService.running || force) {
                // While live mode runs, it handles alerts itself every couple of minutes.
                LiveAlerts.process(ctx, p, fetched)
                Widget.save(ctx, fetched)
            }
        }
        step("game night") { GameNight.plan(ctx, p, info) }
        step("update") {
            if (force || now - p.lastUpdateCheck > TimeUnit.HOURS.toMillis(20)) {
                p.lastUpdateCheck = now
                Updater.checkAndNotify(ctx)
            }
        }

        p.nextDueAt = nextDue(info, now)
        Result.success()
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$name check failed", e)
        }
    }

    // ---- Daily recap: today.json's "night" changes when a new recap is published ----

    private fun checkRecap(p: Prefs) {
        val j = DdhlApi.today()
        val night = j.optString("night")
        if (night.isEmpty()) return
        val complete = j.optJSONObject("coverage")?.optBoolean("statsComplete", true) ?: true
        if (!complete) return // wait until the recap is finished being built

        val previous = p.seenRecapNight
        p.seenRecapNight = night
        if (previous == null || previous == night) return

        val leaders = j.optJSONObject("leaders")
        val star = leaders?.optJSONObject("playerOfNight")?.optJSONObject("player")?.optString("name")
        val teamId = leaders?.optJSONObject("teamOfNight")?.optString("teamId")
        val teamName = teamId?.let { id -> teamNameById(j, id) }
        val body = listOfNotNull(
            star?.takeIf { it.isNotBlank() }?.let { "Player of the night: $it" },
            teamName?.let { "Team of the night: $it" },
        ).joinToString("\n").ifEmpty { "Tap to read it." }

        Notifier.show(
            applicationContext, Notifier.CH_RECAP, Notifier.ID_RECAP,
            "Daily recap is up: ${prettyDate(night)}", body, Ddhl.RECAP
        )
    }

    private fun teamNameById(j: JSONObject, id: String): String? {
        val teams = j.optJSONArray("teams") ?: return null
        for (i in 0 until teams.length()) {
            val t = teams.getJSONObject(i)
            if (t.optString("id") == id) return t.optString("name").ifEmpty { null }
        }
        return null
    }

    // ---- Power rankings: the page shows its publish date ("November 9, 2026") ----

    private fun checkRankings(p: Prefs) {
        val text = DdhlApi.rankingsHtml()
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
        val date = DATE_RE.find(text)?.value
        // If the date ever disappears from the page, fall back to "did the text change".
        val key = date ?: sha256(text.substringAfter("Power rankings"))

        val previous = p.seenRankingsKey
        p.seenRankingsKey = key
        if (previous == null || previous == key) return

        val body = if (date != null) "New rankings for $date. Tap to see who moved."
        else "The power rankings page was updated. Tap to see who moved."
        Notifier.show(
            applicationContext, Notifier.CH_RANKINGS, Notifier.ID_RANKINGS,
            "Power rankings updated", body, Ddhl.RANKINGS
        )
    }

    companion object {
        private const val TAG = "DdhlCheck"
        private const val PERIODIC = "ddhl-periodic-check"
        private const val ONE_OFF = "ddhl-check-now"
        private const val KEY_FORCE = "force"

        private val DATE_RE = Regex(
            "(January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}, \\d{4}"
        )

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** When the next real check should happen (see class comment). */
        fun nextDue(info: LiveInfo?, now: Long): Long {
            if (info == null) return now // the site didn't answer: try again next wake-up
            if (info.gamesOn) return now
            val hour = LocalTime.now().hour
            var due = now + if (hour in 2..7) TimeUnit.HOURS.toMillis(2) else TimeUnit.MINUTES.toMillis(30)
            info.nextGameAt?.let { if (it > now) due = minOf(due, it) }
            return due
        }

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<CheckWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_FORCE to true))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(ONE_OFF, ExistingWorkPolicy.REPLACE, req)
        }

        /**
         * For demos and testing: pretend the last thing we saw was older, then check right away,
         * so each enabled alert fires once with real data from the site.
         */
        fun simulate(ctx: Context) {
            val p = Prefs(ctx)
            if (p.recapAlerts) p.seenRecapNight = "2000-01-01"
            if (p.rankingsAlerts) p.seenRankingsKey = "simulated-old-rankings"
            val team = p.myTeam
            if (team != null && p.seenLiveTeam == team && p.seenLivePeriod != null) {
                p.seenLiveStatus = "live"
                p.seenLiveRecord = "0-0-12" // reads as a lead change
            }
            p.simulateMoments = true
            runNow(ctx)
        }

        private fun prettyDate(iso: String): String = try {
            LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
        } catch (e: Exception) {
            iso
        }

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
