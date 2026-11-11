package com.ddhl.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Downloads team logos the widget needs but doesn't have yet, then redraws it.
 * Started by the widget itself when it notices a missing logo (at most every 15 minutes).
 */
class LogoWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val s = Prefs(applicationContext).widgetState?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return@withContext Result.success()
        fun side(abbr: String, name: String) = Side(abbr, name, 0, 0, 0, 0, 0)
        TeamLogos.ensure(
            applicationContext,
            side(s.optString("me"), s.optString("meName")),
            side(s.optString("opp"), s.optString("oppName")),
        )
        Widget.render(applicationContext)
        Result.success()
    }

    companion object {
        private val THROTTLE = TimeUnit.MINUTES.toMillis(15)
        @Volatile private var lastRequest = 0L

        fun request(ctx: Context) {
            val now = System.currentTimeMillis()
            if (now - lastRequest < THROTTLE) return
            lastRequest = now
            val req = OneTimeWorkRequestBuilder<LogoWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("ddhl-logos", ExistingWorkPolicy.KEEP, req)
        }
    }
}

/** Right after the app is updated: refresh everything (alerts state, widget, logos). */
class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        CheckWorker.schedule(ctx)
        CheckWorker.runNow(ctx)
    }
}
