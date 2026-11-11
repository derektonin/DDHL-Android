package com.ddhl.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "com.ddhl.app.URL"
        const val EXTRA_SHOW_UPDATE = "com.ddhl.app.SHOW_UPDATE"
    }

    private lateinit var web: WebView
    private lateinit var prefs: Prefs

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("Alerts are off. You can allow them later in Android settings.")
            maybeAskForTeam()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        title = "DDHL"

        // Draw our own bars and pad the content so the site always starts below the title bar,
        // on every Android version (Android 15+ otherwise draws apps behind the status bar).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        web = findViewById(R.id.web)
        CookieManager.getInstance().setAcceptCookie(true) // keeps you signed in to the site
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == Ddhl.HOST) return false
                // YouTube, Discord, Google Sheets etc. open in their own apps.
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: ActivityNotFoundException) {
                    toast("No app can open that link")
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                title = view.title?.substringAfter("· ")?.takeIf { it.isNotBlank() } ?: "DDHL"
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: Ddhl.HOME)
        }

        Notifier.createChannels(this)
        CheckWorker.schedule(this)

        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(this)) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            maybeAskForTeam()
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)) checkForUpdate(userAsked = true)
    }

    // Tapping a notification while the app is open loads that page.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_URL)?.let { web.loadUrl(it) }
        if (intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)) checkForUpdate(userAsked = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_live_mode)?.title =
            if (LiveModeService.running) "Stop live mode" else "Start live mode now"
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> web.reload()
            R.id.action_alerts -> showAlertSettings()
            R.id.action_live_mode -> toggleLiveMode()
            R.id.action_battery -> askBatteryExemption()
            R.id.action_update -> checkForUpdate(userAsked = true)
            R.id.action_check_now -> {
                CheckWorker.runNow(this); toast("Checking the site…")
            }
            R.id.action_simulate -> {
                if (!Notifier.canNotify(this)) toast("Allow notifications for DDHL first")
                else {
                    CheckWorker.simulate(this)
                    toast("Test alerts on the way (a few seconds)")
                }
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---- Live mode ----

    private fun toggleLiveMode() {
        if (LiveModeService.running) {
            LiveModeService.stop(this)
            toast("Live mode off until tomorrow")
            return
        }
        if (prefs.myTeam == null) {
            pickTeam(firstRun = false); return
        }
        prefs.liveModeSnoozeUntil = 0
        LiveModeService.start(this)
        toast("Live mode on: score in your notifications")
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Already allowed. Alerts can run in the background.")
            return
        }
        @SuppressLint("BatteryLife")
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---- Updates ----

    private fun checkForUpdate(userAsked: Boolean) {
        if (!Updater.enabled) {
            if (userAsked) toast("Update checking isn't set up yet")
            return
        }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updater.fetchNewer(this@MainActivity) } }
            val release = r.getOrNull()
            when {
                r.isFailure -> if (userAsked) toast("Couldn't check for updates")
                release == null -> if (userAsked) toast("You have the latest version (${BuildConfig.VERSION_NAME})")
                else -> AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update to ${release.versionName}?")
                    .setMessage(release.notes.ifBlank { "A new version of the DDHL app is ready." })
                    .setPositiveButton("Download & install") { _, _ -> downloadAndInstall(release) }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun downloadAndInstall(r: Updater.Release) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Downloading ${r.versionName}…")
            .setMessage("This takes a few seconds.")
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Updater.download(this@MainActivity, r) } }
            progress.dismiss()
            result.onSuccess { apk ->
                if (!Updater.install(this@MainActivity, apk)) {
                    toast("Allow DDHL to install updates, then come back and tap Update again")
                }
            }.onFailure { e ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update failed")
                    .setMessage(e.message ?: "Download failed")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ---- Alert settings ----

    private fun maybeAskForTeam() {
        if (prefs.myTeam == null && !prefs.askedForTeam) {
            prefs.askedForTeam = true
            pickTeam(firstRun = true)
        }
    }

    private fun showAlertSettings() {
        val labels = arrayOf(
            "Daily recap is posted",
            "Power rankings are updated",
            "I take or lose the lead (and final result)",
            "My players score (goals, assists, goalie wins)",
            "A category flips in my matchup",
            "Game-night live mode (switches on automatically)",
        )
        val checked = booleanArrayOf(
            prefs.recapAlerts, prefs.rankingsAlerts, prefs.liveAlerts,
            prefs.playerAlerts, prefs.categoryAlerts, prefs.liveModeAuto,
        )
        val teamLine = prefs.myTeam?.let { "Team: $it" } ?: "Pick team"
        AlertDialog.Builder(this)
            .setTitle("Alert me when…")
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Save") { _, _ ->
                prefs.recapAlerts = checked[0]
                prefs.rankingsAlerts = checked[1]
                prefs.liveAlerts = checked[2]
                prefs.playerAlerts = checked[3]
                prefs.categoryAlerts = checked[4]
                prefs.liveModeAuto = checked[5]
                if (!checked[5] && LiveModeService.running) LiveModeService.stop(this)
                val needsTeam = checked[2] || checked[3] || checked[4] || checked[5]
                if (needsTeam && prefs.myTeam == null) pickTeam(firstRun = false)
                else {
                    prefs.nextDueAt = 0
                    toast("Saved")
                }
            }
            .setNeutralButton(teamLine) { _, _ -> pickTeam(firstRun = false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickTeam(firstRun: Boolean) {
        lifecycleScope.launch {
            val teams = withContext(Dispatchers.IO) { runCatching { DdhlApi.teams() }.getOrNull() }
            if (teams.isNullOrEmpty()) {
                toast("Couldn't load the team list. Try again from Alert settings.")
                return@launch
            }
            val names = teams.map { "${it.name} (${it.abbr})" }.toTypedArray()
            val current = teams.indexOfFirst { it.abbr == prefs.myTeam }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(if (firstRun) "Which team is yours? (for live alerts)" else "Choose your team")
                .setSingleChoiceItems(names, current) { d, i ->
                    prefs.myTeam = teams[i].abbr
                    prefs.clearLive()
                    prefs.widgetState = null
                    CheckWorker.runNow(this@MainActivity) // records a starting score quietly
                    toast("Live alerts set for ${teams[i].name}")
                    d.dismiss()
                }
                .setNegativeButton(if (firstRun) "Skip" else "Cancel", null)
                .show()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
