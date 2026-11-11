package com.ddhl.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Home-screen widget: your matchup score, win chance and standing. Tap to open Live scoring. */
class MatchupWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, Widget.build(ctx))
        if (Prefs(ctx).widgetState == null) CheckWorker.runNow(ctx)
    }
}

object Widget {

    /** Saves the latest numbers and redraws any widgets on the home screen. Background thread only. */
    fun save(ctx: Context, info: LiveInfo) {
        val m = info.mine ?: return
        TeamLogos.ensure(ctx, m.me, m.opp)
        val j = JSONObject()
            .put("me", m.me.abbr).put("meRec", m.me.record)
            .put("opp", m.opp.abbr).put("oppRec", m.opp.record)
            .put("state", m.me.state())
            .put("win", m.winChance ?: -1.0)
            .put("live", m.me.gamesInProgress)
            .put("period", info.periodLabel)
            .put("rank", info.rank ?: 0)
            .put("points", info.points ?: -1)
            .put("updated", System.currentTimeMillis())
        Prefs(ctx).widgetState = j.toString()
        render(ctx)
    }

    fun render(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, MatchupWidget::class.java))
        if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(ctx))
    }

    fun build(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_matchup)
        v.setOnClickPendingIntent(R.id.w_root, Notifier.openPage(ctx, 30, Ddhl.LIVE))
        val s = Prefs(ctx).widgetState?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (s == null) {
            v.setTextViewText(R.id.w_me, "DDHL")
            v.setTextViewText(R.id.w_me_rec, "")
            v.setTextViewText(R.id.w_opp, "")
            v.setTextViewText(R.id.w_opp_rec, "")
            v.setTextViewText(R.id.w_status, "Open the app and pick your team")
            v.setTextViewText(R.id.w_footer, "")
            v.setViewVisibility(R.id.w_live, View.GONE)
            return v
        }
        v.setTextViewText(R.id.w_me, s.optString("me"))
        v.setTextViewText(R.id.w_me_rec, s.optString("meRec"))
        v.setTextViewText(R.id.w_opp, s.optString("opp"))
        v.setTextViewText(R.id.w_opp_rec, s.optString("oppRec"))
        logo(ctx, v, R.id.w_me_logo, s.optString("me"))
        logo(ctx, v, R.id.w_opp_logo, s.optString("opp"))

        val lead = when (s.optInt("state")) { 1 -> "Leading"; -1 -> "Trailing"; else -> "Tied" }
        val win = s.optDouble("win", -1.0)
        val status = if (win >= 0) "$lead · Win chance ${(win * 100).roundToInt()}%" else lead
        v.setTextViewText(R.id.w_status, status)

        val rank = s.optInt("rank")
        val pts = s.optInt("points", -1)
        val standing = if (rank > 0) ordinal(rank) + (if (pts >= 0) " ($pts pts)" else "") else null
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.optLong("updated")))
        v.setTextViewText(
            R.id.w_footer,
            listOfNotNull(s.optString("period").ifEmpty { null }, standing, time).joinToString(" · ")
        )
        val live = LiveModeService.running || s.optInt("live") > 0
        v.setViewVisibility(R.id.w_live, if (live) View.VISIBLE else View.GONE)
        return v
    }

    private fun logo(ctx: Context, v: RemoteViews, viewId: Int, abbr: String) {
        val bmp = if (abbr.isEmpty()) null else TeamLogos.cached(ctx, abbr)
        if (bmp != null) {
            v.setImageViewBitmap(viewId, bmp)
            v.setViewVisibility(viewId, View.VISIBLE)
        } else {
            v.setViewVisibility(viewId, View.GONE)
        }
    }

    private fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }
}
