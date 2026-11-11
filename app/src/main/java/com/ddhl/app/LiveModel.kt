package com.ddhl.app

import org.json.JSONObject
import java.time.OffsetDateTime

/** One side of a matchup. Record is the category W-L-T for this week. */
data class Side(
    val abbr: String,
    val name: String,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val gamesInProgress: Int,
    val gamesRemaining: Int,
) {
    val record get() = "$wins-$losses-$ties"
    /** 1 leading, 0 tied, -1 trailing */
    fun state() = Integer.signum(wins - losses)
}

data class Participant(val side: Int, val name: String, val action: String, val stats: List<Pair<String, Double>>)

/** A scoring play, goalie result or category lead change, as published by the site. */
data class Moment(
    val id: String,
    val kind: String, // "goal", "goalie", "category"
    val context: String,
    val headline: String?,
    val side: Int?,
    val participants: List<Participant>,
)

data class MyMatchup(
    val side: Int,
    val me: Side,
    val opp: Side,
    val winChance: Double?,
    val moments: List<Moment>, // newest first
)

data class LiveInfo(
    val periodNo: String,
    val periodLabel: String,
    val periodStatus: String,
    val refreshMode: String,
    val nextGameAt: Long?,
    val rank: Int?,
    val points: Int?,
    val mine: MyMatchup?,
) {
    /** The site switches out of "idle" while NHL games are being played. */
    val gamesOn get() = refreshMode.isNotEmpty() && refreshMode != "idle"

    companion object {
        fun parse(j: JSONObject, team: String?): LiveInfo {
            val period = j.optJSONObject("period")
            val refresh = j.optJSONObject("refresh")
            val nextGame = refresh?.optString("nextGameAt")?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

            var rank: Int? = null
            var points: Int? = null
            val standings = j.optJSONObject("standings")?.optJSONArray("teams")
            if (team != null && standings != null) {
                for (i in 0 until standings.length()) {
                    val t = standings.getJSONObject(i)
                    if (t.optString("abbr") == team) {
                        rank = t.optInt("rank").takeIf { it > 0 }
                        points = if (t.has("points")) t.optInt("points") else null
                    }
                }
            }

            return LiveInfo(
                periodNo = period?.optInt("number")?.toString() ?: "",
                periodLabel = period?.optString("label")?.ifEmpty { null } ?: "This week",
                periodStatus = period?.optString("status") ?: "",
                refreshMode = refresh?.optString("mode") ?: "",
                nextGameAt = nextGame,
                rank = rank,
                points = points,
                mine = team?.let { findMine(j, it) },
            )
        }

        private fun side(t: JSONObject): Side {
            val r = t.optJSONObject("record")
            val g = t.optJSONObject("games")
            return Side(
                abbr = t.optString("abbr"),
                name = t.optString("name").ifEmpty { t.optString("abbr") },
                wins = r?.optInt("wins") ?: 0,
                losses = r?.optInt("losses") ?: 0,
                ties = r?.optInt("ties") ?: 0,
                gamesInProgress = g?.optInt("inProgress") ?: 0,
                gamesRemaining = g?.optInt("remaining") ?: 0,
            )
        }

        private fun findMine(j: JSONObject, abbr: String): MyMatchup? {
            val matchups = j.optJSONArray("matchups") ?: return null
            for (i in 0 until matchups.length()) {
                val m = matchups.getJSONObject(i)
                val teams = m.optJSONArray("teams") ?: continue
                if (teams.length() < 2) continue
                for (s in 0..1) {
                    if (teams.getJSONObject(s).optString("abbr") != abbr) continue
                    val win = m.optJSONObject("matchupOdds")?.optJSONArray("win")?.optDouble(s)
                    return MyMatchup(
                        side = s,
                        me = side(teams.getJSONObject(s)),
                        opp = side(teams.getJSONObject(1 - s)),
                        winChance = win?.takeIf { !it.isNaN() },
                        moments = moments(m),
                    )
                }
            }
            return null
        }

        private fun moments(m: JSONObject): List<Moment> {
            val arr = m.optJSONArray("moments") ?: return emptyList()
            val out = ArrayList<Moment>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val parts = mutableListOf<Participant>()
                val pa = o.optJSONArray("participants")
                if (pa != null) for (k in 0 until pa.length()) {
                    val p = pa.optJSONObject(k) ?: continue
                    val stats = mutableListOf<Pair<String, Double>>()
                    p.optJSONObject("stats")?.let { s ->
                        for (key in s.keys()) stats += key to s.optDouble(key)
                    }
                    parts += Participant(p.optInt("side", -1), p.optString("name"), p.optString("action"), stats)
                }
                val id = o.optString("id")
                if (id.isEmpty()) continue
                out += Moment(
                    id = id,
                    kind = o.optString("kind"),
                    context = o.optString("context"),
                    headline = o.optString("headline").ifEmpty { null },
                    side = if (o.has("side")) o.optInt("side") else null,
                    participants = parts,
                )
            }
            return out
        }
    }
}
