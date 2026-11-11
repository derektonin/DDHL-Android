package com.ddhl.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Addresses on the league site. Change HOST here if the site gets a domain name. */
object Ddhl {
    const val HOST = "138.197.131.71"
    const val BASE = "https://$HOST/ddhl/"
    const val HOME = BASE
    const val LIVE = BASE + "live/"
    const val RECAP = BASE + "today/"
    const val RANKINGS = BASE + "power-rankings/"

    /**
     * update.json from the newest GitHub release (see README, "Publishing an update").
     * GitHub always redirects "latest/download/..." to the most recent release's file.
     * Leave empty to turn update checks off.
     */
    const val UPDATE_MANIFEST_URL =
        "https://github.com/derektonin/DDHL-Android/releases/latest/download/update.json"
}

data class Team(val abbr: String, val name: String)

/**
 * Reads the same public data the website itself loads. Nothing here needs a login,
 * and nothing is ever sent to the site except plain GET requests.
 */
object DdhlApi {
    fun getText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "DDHL-Android/" + BuildConfig.VERSION_NAME)
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun liveJson(): JSONObject = JSONObject(getText(Ddhl.BASE + "api/live"))

    fun live(team: String?): LiveInfo = LiveInfo.parse(liveJson(), team)

    fun today(): JSONObject =
        JSONObject(getText(Ddhl.BASE + "today/data/today.json?v=" + System.currentTimeMillis() / 60_000))

    fun rankingsHtml(): String = getText(Ddhl.RANKINGS)

    /** All league teams, taken from this week's live matchups. */
    fun teams(): List<Team> {
        val matchups = liveJson().getJSONArray("matchups")
        val out = mutableListOf<Team>()
        for (i in 0 until matchups.length()) {
            val teams = matchups.getJSONObject(i).getJSONArray("teams")
            for (k in 0 until teams.length()) {
                val t = teams.getJSONObject(k)
                out += Team(t.getString("abbr"), t.optString("name", t.getString("abbr")))
            }
        }
        return out.distinctBy { it.abbr }.sortedBy { it.name.lowercase() }
    }
}
