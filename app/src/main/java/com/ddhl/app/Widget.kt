package com.ddhl.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Home-screen widget: your matchup score, win chance and standing. Tap to open Live scoring. */
class MatchupWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, Widget.buildFor(ctx, mgr, id))
        if (Prefs(ctx).widgetState == null) CheckWorker.runNow(ctx)
    }

    // Called whenever the widget is resized.
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        mgr.updateAppWidget(id, Widget.buildFor(ctx, mgr, id))
    }
}

/**
 * The widget uses its full size. Three layouts by height (compact 1-row, standard, large),
 * and within each, logos and scores grow with the space available. The large layout adds a
 * "Latest" list of match events, as many lines as fit.
 */
object Widget {
    private const val MAX_EVENTS = 10
    /** Each "Latest" row: container, my-team tag, opponent tag, text. Colors come from the layout,
     *  so they follow the phone's light/dark setting instantly. */
    private class EventRow(val row: Int, val me: Int, val opp: Int, val text: Int)
    private val EVENT_ROWS = listOf(
        EventRow(R.id.w_e1, R.id.w_e1_me, R.id.w_e1_opp, R.id.w_e1_t),
        EventRow(R.id.w_e2, R.id.w_e2_me, R.id.w_e2_opp, R.id.w_e2_t),
        EventRow(R.id.w_e3, R.id.w_e3_me, R.id.w_e3_opp, R.id.w_e3_t),
        EventRow(R.id.w_e4, R.id.w_e4_me, R.id.w_e4_opp, R.id.w_e4_t),
        EventRow(R.id.w_e5, R.id.w_e5_me, R.id.w_e5_opp, R.id.w_e5_t),
        EventRow(R.id.w_e6, R.id.w_e6_me, R.id.w_e6_opp, R.id.w_e6_t),
        EventRow(R.id.w_e7, R.id.w_e7_me, R.id.w_e7_opp, R.id.w_e7_t),
        EventRow(R.id.w_e8, R.id.w_e8_me, R.id.w_e8_opp, R.id.w_e8_t),
        EventRow(R.id.w_e9, R.id.w_e9_me, R.id.w_e9_opp, R.id.w_e9_t),
        EventRow(R.id.w_e10, R.id.w_e10_me, R.id.w_e10_opp, R.id.w_e10_t),
    )
    /** Mid grey for the game context: readable on both the light and dark widget. */
    private const val CONTEXT_GREY = 0xFF8C939E.toInt()

    // ---- Data ----

    /** Saves the latest numbers and redraws any widgets on the home screen. Background thread only. */
    fun save(ctx: Context, info: LiveInfo) {
        val m = info.mine ?: return
        TeamLogos.ensure(ctx, m.me, m.opp)
        val j = JSONObject()
            .put("me", m.me.abbr).put("meRec", m.me.record).put("meName", m.me.name)
            .put("opp", m.opp.abbr).put("oppRec", m.opp.record).put("oppName", m.opp.name)
            .put("state", m.me.state())
            .put("win", m.winChance ?: -1.0)
            .put("live", m.me.gamesInProgress)
            .put("period", info.periodLabel)
            .put("rank", info.rank ?: 0)
            .put("points", info.points ?: -1)
            .put("updated", System.currentTimeMillis())
            .put("events", events(m))
        Prefs(ctx).widgetState = j.toString()
        render(ctx)
    }

    /** Newest match events from both teams: scoring plays, goalie results, category flips. */
    private fun events(m: MyMatchup): JSONArray {
        val out = JSONArray()
        for (mo in m.moments) { // newest first
            if (out.length() >= MAX_EVENTS) break
            if (mo.kind == "category") {
                val text = LiveAlerts.personal(mo.headline ?: "Category change", m.me.abbr)
                val tag = when {
                    text.startsWith("Tied") -> "="
                    mo.side == m.side -> m.me.abbr
                    else -> m.opp.abbr
                }
                out.put(JSONObject().put("tag", tag).put("mine", mo.side == m.side && tag != "=")
                    .put("t", text).put("c", mo.context))
            } else {
                for (pt in mo.participants) {
                    if (out.length() >= MAX_EVENTS) break
                    val stats = LiveAlerts.statsText(pt.stats)
                    val text = "${pt.name}: ${pt.action}" + if (stats.isNotBlank()) " ($stats)" else ""
                    val mine = pt.side == m.side
                    out.put(JSONObject().put("tag", if (mine) m.me.abbr else m.opp.abbr).put("mine", mine)
                        .put("t", text).put("c", mo.context))
                }
            }
        }
        return out
    }

    // ---- Rendering ----

    fun render(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, MatchupWidget::class.java))
        for (id in ids) mgr.updateAppWidget(id, buildFor(ctx, mgr, id))
    }

    /** Builds views for one widget at its current size (every size it may show at, on Android 12+). */
    fun buildFor(ctx: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
        val state = Prefs(ctx).widgetState?.let { runCatching { JSONObject(it) }.getOrNull() }
        // Load each logo once and share it across every size we build.
        val logos = Logos(
            state?.optString("me")?.ifEmpty { null }?.let { TeamLogos.cached(ctx, it) },
            state?.optString("opp")?.ifEmpty { null }?.let { TeamLogos.cached(ctx, it) },
        )
        // Missing a logo (first run, new opponent, app update…): fetch it in the background.
        if (state != null && (logos.me == null || logos.opp == null)) LogoWorker.request(ctx)

        val o = mgr.getAppWidgetOptions(id)
        if (Build.VERSION.SDK_INT >= 31) {
            val sizes = BundleCompat.getParcelableArrayList(o, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            if (!sizes.isNullOrEmpty()) {
                return RemoteViews(sizes.distinct().take(8).associateWith { build(ctx, state, logos, it.width, it.height) })
            }
        }
        // Older Android: portrait size is (min width, max height).
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 110
        return build(ctx, state, logos, w.toFloat(), h.toFloat())
    }

    private class Logos(val me: Bitmap?, val opp: Bitmap?)

    private enum class Tier { COMPACT, STANDARD, LARGE }

    /** [w] and [h] are the widget's size in dp. */
    private fun build(ctx: Context, s: JSONObject?, logos: Logos, w: Float, h: Float): RemoteViews {
        val tier = when {
            h < 100f -> Tier.COMPACT
            h >= 200f && w >= 220f -> Tier.LARGE
            else -> Tier.STANDARD
        }
        val layout = when (tier) {
            Tier.COMPACT -> R.layout.widget_compact
            Tier.STANDARD -> R.layout.widget_matchup
            Tier.LARGE -> R.layout.widget_large
        }
        val v = RemoteViews(ctx.packageName, layout)
        v.setOnClickPendingIntent(R.id.w_root, Notifier.openPage(ctx, 30, Ddhl.LIVE))
        if (s == null) {
            v.setTextViewText(R.id.w_me, "DDHL")
            v.setTextViewText(R.id.w_status, "Open the app and pick your team")
            return v
        }

        // Size logos and scores to the space available.
        val (logoDp, recSp) = when (tier) {
            Tier.COMPACT -> {
                val logo = clamp(h - 12f - 20f, 20f, 44f)
                logo to clamp(min(logo * 0.8f, (w - 2 * logo - 150f) / 6f), 15f, 28f)
            }
            Tier.STANDARD -> {
                val rec = clamp(h * 0.18f, 22f, 34f)
                val logo = clamp(min(h - 24f - 46f - rec * 1.35f, (w - 60f) / 2f - 58f), 22f, 64f)
                logo to rec
            }
            Tier.LARGE -> {
                val logo = clamp(min(w * 0.22f, h * 0.28f), 48f, 96f)
                logo to clamp(logo * 0.5f, 26f, 44f)
            }
        }
        v.setTextViewTextSize(R.id.w_me_rec, TypedValue.COMPLEX_UNIT_SP, recSp)
        v.setTextViewTextSize(R.id.w_opp_rec, TypedValue.COMPLEX_UNIT_SP, recSp)
        if (tier == Tier.LARGE) {
            val abbrSp = clamp(logoDp * 0.22f, 14f, 20f)
            v.setTextViewTextSize(R.id.w_me, TypedValue.COMPLEX_UNIT_SP, abbrSp)
            v.setTextViewTextSize(R.id.w_opp, TypedValue.COMPLEX_UNIT_SP, abbrSp)
        }

        v.setTextViewText(R.id.w_me, s.optString("me"))
        v.setTextViewText(R.id.w_me_rec, s.optString("meRec"))
        v.setTextViewText(R.id.w_opp, s.optString("opp"))
        v.setTextViewText(R.id.w_opp_rec, s.optString("oppRec"))
        logo(v, R.id.w_me_logo, logos.me, logoDp)
        logo(v, R.id.w_opp_logo, logos.opp, logoDp)

        val lead = when (s.optInt("state")) { 1 -> "Leading"; -1 -> "Trailing"; else -> "Tied" }
        val win = s.optDouble("win", -1.0)
        v.setTextViewText(R.id.w_status, if (win >= 0) "$lead · Win chance ${(win * 100).roundToInt()}%" else lead)

        val live = LiveModeService.running || s.optInt("live") > 0
        v.setViewVisibility(R.id.w_live, if (live) View.VISIBLE else View.GONE)

        if (tier != Tier.COMPACT) {
            val rank = s.optInt("rank")
            val pts = s.optInt("points", -1)
            val standing = if (rank > 0) ordinal(rank) + (if (pts >= 0) " ($pts pts)" else "") else null
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.optLong("updated")))
            v.setTextViewText(
                R.id.w_footer,
                listOfNotNull(s.optString("period").ifEmpty { null }, standing, time).joinToString(" · ")
            )
        }

        if (tier == Tier.LARGE) {
            // Top block height (dp): logo + abbr + score + status + footer + padding.
            val top = logoDp + 4f + 24f + recSp * 1.35f + 30f + 20f + 28f
            val room = h - top - 34f // divider + "LATEST" label
            val lines = clamp(room / 23f, 0f, MAX_EVENTS.toFloat()).toInt()
            showEvents(v, s.optJSONArray("events"), lines)
        }
        return v
    }

    private fun showEvents(v: RemoteViews, events: JSONArray?, lines: Int) {
        if (lines <= 0) {
            v.setViewVisibility(R.id.w_events, View.GONE); return
        }
        v.setViewVisibility(R.id.w_events, View.VISIBLE)
        val n = events?.length() ?: 0
        for ((i, r) in EVENT_ROWS.withIndex()) {
            when {
                i >= lines -> v.setViewVisibility(r.row, View.GONE)
                n == 0 && i == 0 -> {
                    v.setViewVisibility(r.me, View.GONE)
                    v.setViewVisibility(r.opp, View.GONE)
                    v.setTextViewText(r.text, "No plays yet this week")
                    v.setViewVisibility(r.row, View.VISIBLE)
                }
                i < n -> {
                    val e = events!!.getJSONObject(i)
                    val mine = e.optBoolean("mine")
                    val tagView = if (mine) r.me else r.opp
                    v.setTextViewText(tagView, e.optString("tag"))
                    v.setViewVisibility(r.me, if (mine) View.VISIBLE else View.GONE)
                    v.setViewVisibility(r.opp, if (mine) View.GONE else View.VISIBLE)
                    v.setTextViewText(r.text, eventText(e))
                    v.setViewVisibility(r.row, View.VISIBLE)
                }
                else -> v.setViewVisibility(r.row, View.GONE)
            }
        }
    }

    /** "Quinn Hughes: PP assist (+1 A)  NJD at NYR · P2 4:11", with the game context smaller and grey. */
    private fun eventText(e: JSONObject): CharSequence {
        val b = SpannableStringBuilder(e.optString("t"))
        val c = e.optString("c")
        if (c.isNotBlank()) {
            val start = b.length
            b.append("  ").append(c)
            b.setSpan(ForegroundColorSpan(CONTEXT_GREY), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.setSpan(RelativeSizeSpan(0.85f), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return b
    }

    private fun logo(v: RemoteViews, viewId: Int, bmp: Bitmap?, sizeDp: Float) {
        if (bmp == null) {
            v.setViewVisibility(viewId, View.GONE); return
        }
        v.setImageViewBitmap(viewId, bmp)
        v.setViewVisibility(viewId, View.VISIBLE)
        if (Build.VERSION.SDK_INT >= 31) {
            v.setViewLayoutWidth(viewId, sizeDp, TypedValue.COMPLEX_UNIT_DIP)
            v.setViewLayoutHeight(viewId, sizeDp, TypedValue.COMPLEX_UNIT_DIP)
        }
    }

    private fun clamp(x: Float, lo: Float, hi: Float) = max(lo, min(hi, x))

    private fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }
}
