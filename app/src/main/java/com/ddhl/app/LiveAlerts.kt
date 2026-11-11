package com.ddhl.app

import android.content.Context
import kotlin.math.roundToInt

/**
 * Turns a fresh copy of the live feed into alerts: lead changes, final results, your players'
 * scoring plays, and category flips. Used by both the background check and game-night live mode.
 */
object LiveAlerts {

    fun process(ctx: Context, p: Prefs, info: LiveInfo) {
        val m = info.mine ?: return
        leadAndFinal(ctx, p, info, m)
        moments(ctx, p, info, m)
    }

    // ---- Lead changes and final result ----

    private fun leadAndFinal(ctx: Context, p: Prefs, info: LiveInfo, m: MyMatchup) {
        val team = m.me.abbr
        val prevTeam = p.seenLiveTeam
        val prevPeriod = p.seenLivePeriod
        val prevStatus = p.seenLiveStatus
        val prevRecord = p.seenLiveRecord
        val prevOpponent = p.seenLiveOpponent
        p.seenLiveTeam = team
        p.seenLivePeriod = info.periodNo
        p.seenLiveStatus = info.periodStatus
        p.seenLiveRecord = m.me.record
        p.seenLiveOpponent = m.opp.name

        if (prevTeam != team || prevRecord == null) return // new baseline

        if (prevPeriod != info.periodNo) {
            // A new week started before we saw the old one finish.
            val old = stateOf(prevRecord) ?: return
            if (p.liveAlerts) Notifier.show(
                ctx, Notifier.CH_LIVE, Notifier.ID_FINAL,
                "Matchup final: ${outcome(old)} $prevRecord",
                "Your week vs ${prevOpponent ?: "your opponent"} is over.", Ddhl.LIVE
            )
            return
        }
        if (info.periodStatus == "final" && prevStatus != "final") {
            if (p.liveAlerts) Notifier.show(
                ctx, Notifier.CH_LIVE, Notifier.ID_FINAL,
                "${info.periodLabel} final: ${outcome(m.me.state())} ${m.me.record}",
                "Your week vs ${m.opp.name} is over.", Ddhl.LIVE
            )
            return
        }
        if (prevRecord == m.me.record || !p.liveAlerts) return
        val old = stateOf(prevRecord) ?: return
        if (old == m.me.state()) return // category flips cover smaller changes

        val title = when (m.me.state()) {
            1 -> "You took the lead vs ${m.opp.name}!"
            -1 -> "You fell behind vs ${m.opp.name}"
            else -> "All square vs ${m.opp.name}"
        }
        val odds = m.winChance?.let { " · Win chance ${(it * 100).roundToInt()}%" } ?: ""
        Notifier.show(
            ctx, Notifier.CH_LIVE, Notifier.ID_LIVE,
            title, "Now ${m.me.record} (was $prevRecord)$odds", Ddhl.LIVE
        )
    }

    private fun stateOf(record: String): Int? {
        val n = record.split("-").mapNotNull { it.toIntOrNull() }
        return if (n.size == 3) Integer.signum(n[0] - n[1]) else null
    }

    private fun outcome(state: Int) = when (state) { 1 -> "Win"; -1 -> "Loss"; else -> "Tie" }

    // ---- Scoring plays and category flips (the site's "moments" feed) ----

    private fun moments(ctx: Context, p: Prefs, info: LiveInfo, m: MyMatchup) {
        val key = "${m.me.abbr}|${info.periodNo}"
        val ids = m.moments.map { it.id }.toSet()
        var seen: Set<String>? = if (p.seenMomentsKey == key) p.seenMomentIds else null

        if (p.simulateMoments) {
            // Test mode: treat the two newest plays and two newest flips as new.
            p.simulateMoments = false
            val drop = (m.moments.filter { it.kind == "category" }.take(2) +
                m.moments.filter { isMine(it, m) }.take(2)).map { it.id }.toSet()
            seen = ids - drop
        }
        p.seenMomentsKey = key
        p.seenMomentIds = ids
        if (seen == null) return // first look at this week: baseline only

        val fresh = m.moments.filter { it.id !in seen }.reversed() // oldest first
        if (fresh.isEmpty()) return

        if (p.playerAlerts) playerAlert(ctx, p, m, fresh)
        if (p.categoryAlerts) categoryAlert(ctx, p, m, fresh)
    }

    private fun isMine(mo: Moment, m: MyMatchup) =
        mo.kind != "category" && mo.participants.any { it.side == m.side }

    private fun playerAlert(ctx: Context, p: Prefs, m: MyMatchup, fresh: List<Moment>) {
        val plays = fresh.filter { it.kind != "category" }.flatMap { mo ->
            mo.participants.filter { it.side == m.side }.map { it to mo }
        }
        if (plays.isEmpty()) return
        val id = p.nextNotificationId()
        if (plays.size == 1) {
            val (pt, mo) = plays[0]
            val stats = statsText(pt.stats)
            Notifier.show(
                ctx, Notifier.CH_PLAYERS, id,
                "${pt.name}: ${pt.action}",
                listOf(stats, mo.context).filter { it.isNotBlank() }.joinToString(" · "), Ddhl.LIVE
            )
        } else {
            val lines = plays.map { (pt, _) ->
                val s = statsText(pt.stats)
                if (s.isBlank()) "${pt.name}: ${pt.action}" else "${pt.name}: ${pt.action} ($s)"
            }
            Notifier.show(
                ctx, Notifier.CH_PLAYERS, id,
                "${plays.size} new plays for ${m.me.abbr}", lines.first(), Ddhl.LIVE, lines
            )
        }
    }

    private fun categoryAlert(ctx: Context, p: Prefs, m: MyMatchup, fresh: List<Moment>) {
        val flips = fresh.filter { it.kind == "category" }
        if (flips.isEmpty()) return
        val id = p.nextNotificationId()
        fun headline(mo: Moment) = personal(mo.headline ?: "Category change", m.me.abbr)
        if (flips.size == 1) {
            val mo = flips[0]
            Notifier.show(ctx, Notifier.CH_CATEGORIES, id, headline(mo), mo.context, Ddhl.LIVE)
        } else {
            val lines = flips.map { "${headline(it)} (${it.context})" }
            Notifier.show(
                ctx, Notifier.CH_CATEGORIES, id,
                "${flips.size} category changes vs ${m.opp.abbr}", lines.last(), Ddhl.LIVE, lines
            )
        }
    }

    /** "TEMU takes the lead in hits" -> "You take the lead in hits" for your own team. */
    fun personal(headline: String, me: String): String =
        headline.replace(Regex("^" + Regex.escape(me) + " takes\\b"), "You take")

    /** {"G":1,"STP4":1} -> "+1 G, +1 STP4" */
    fun statsText(stats: List<Pair<String, Double>>): String =
        stats.filter { it.second != 0.0 && !it.second.isNaN() }.joinToString(", ") { (k, v) ->
            val n = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(v)
            (if (v > 0) "+" else "") + n + " " + k
        }
}
