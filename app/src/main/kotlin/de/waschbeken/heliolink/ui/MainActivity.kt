package de.waschbeken.heliolink.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.waschbeken.heliolink.AppVisibility
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.data.CrashReporter
import de.waschbeken.heliolink.service.StrapService
import de.waschbeken.heliolink.ui.alarms.AlarmEditScreen
import de.waschbeken.heliolink.ui.alarms.AlarmsScreen
import de.waschbeken.heliolink.ui.home.HomeScreen
import de.waschbeken.heliolink.ui.metrics.MetricScreen
import de.waschbeken.heliolink.ui.settings.SettingsCategoryScreen
import de.waschbeken.heliolink.ui.settings.SettingsScreen
import de.waschbeken.heliolink.ui.sleep.SleepScreen
import de.waschbeken.heliolink.ui.training.TrainingScreen
import de.waschbeken.heliolink.ui.training.WorkoutDetailScreen
import de.waschbeken.heliolink.update.UpdateState

object Routes {
    const val HOME = "home"
    const val DATA = "data"
    const val RECORDING = "recording"
    const val SLEEP = "sleep"
    const val TRAINING = "training"
    const val ALARMS = "alarms"
    const val SETTINGS = "settings"
    fun metric(type: String) = "metric/$type"
    fun settings(category: String) = "settings/$category"
    fun workout(id: Long) = "workout/$id"
    fun alarm(position: Int) = "alarm/$position"
    fun sleepEdit(day: Long) = "sleep/edit/$day"
    const val TOOLS_BREATHE = "tools/breathe"
    const val TOOLS_HRV = "tools/hrv"
    const val TOOLS_FOCUS = "tools/focus"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Heute", Icons.Filled.MonitorHeart),
    Tab(Routes.DATA, "Daten", Icons.Filled.Insights),
    Tab(Routes.SLEEP, "Schlaf", Icons.Filled.Bedtime),
    Tab(Routes.TRAINING, "Sport", Icons.Filled.FitnessCenter),
    Tab(Routes.ALARMS, "Wecker", Icons.Filled.Alarm),
    Tab(Routes.SETTINGS, "Mehr", Icons.Filled.Settings),
)

fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

class MainActivity : ComponentActivity() {

    private var configured by mutableStateOf(false)
    private var permissionGranted by mutableStateOf(false)
    private var crashReport by mutableStateOf<String?>(null)

    /** Screen a notification asked to open (see [EXTRA_ROUTE]). */
    private var pendingRoute by mutableStateOf<String?>(null)

    /** App lock: the content stays hidden until fingerprint, face or PIN confirm the owner. */
    private var locked by mutableStateOf(false)
    private var lockMessage by mutableStateOf<String?>(null)
    private var authenticating = false
    private var stoppedAt = 0L

    private val keyguardLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        authenticating = false
        if (it.resultCode == RESULT_OK) unlocked() else lockMessage = "Nicht entsperrt."
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionGranted = hasBluetoothPermission()
        if (configured && permissionGranted) StrapService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configured = app.prefs.isConfigured
        permissionGranted = hasBluetoothPermission()
        crashReport = CrashReporter.read(this)
        locked = app.prefs.appLock && savedInstanceState?.getBoolean(STATE_UNLOCKED) != true
        applyRecentsPrivacy()
        requestPermissions()
        handleIntent(intent)
        if (app.updater.manager.isAutoCheckDue()) app.updater.check(silent = true)
        setContent {
            val tick by app.prefs.changes.collectAsStateWithLifecycle()
            val prefs = remember(tick) { app.prefs }
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                app.updater.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
            HelioTheme(prefs.themeMode, prefs.dynamicColor, prefs.accentColor, prefs.fontScale) {
                if (locked && prefs.appLock) {
                    LockScreen(lockMessage, ::unlock)
                    return@HelioTheme
                }
                UpdateDialog(app.updater)
                crashReport?.let { report -> CrashDialog(report) { crashReport = null } }
                if (configured) {
                    AppContent(
                        pendingRoute = pendingRoute,
                        onRouteShown = { pendingRoute = null },
                        onForget = {
                            app.repo.controller?.shutdown()
                            app.prefs.forgetDevice()
                            configured = false
                        },
                    )
                } else {
                    SetupScreen(
                        hasPermission = permissionGranted,
                        requestPermission = ::requestPermissions,
                        onDone = {
                            configured = true
                            if (hasBluetoothPermission()) StrapService.start(this)
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.inForeground = true
        val prefs = app.prefs
        if (prefs.appLock && stoppedAt > 0 && android.os.SystemClock.elapsedRealtime() - stoppedAt >= prefs.appLockDelay * 1000L) {
            locked = true
            lockMessage = null
        }
        stoppedAt = 0
        applyRecentsPrivacy()
        if (configured) de.waschbeken.heliolink.data.AutoBackup.runIfDueInBackground(this)
    }

    override fun onStop() {
        AppVisibility.inForeground = false
        // The fingerprint/PIN screen itself must not lock the app again.
        if (!authenticating && !isChangingConfigurations) stoppedAt = android.os.SystemClock.elapsedRealtime()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_UNLOCKED, !locked)
    }

    private fun unlock() {
        if (authenticating) return
        if (!AppLock.available(this)) {
            // The screen lock was removed: the app lock cannot work any more.
            app.prefs.appLock = false
            unlocked()
            return
        }
        authenticating = true
        AppLock.prompt(
            this,
            onSuccess = {
                authenticating = false
                unlocked()
            },
            onFail = { message ->
                authenticating = false
                lockMessage = message ?: "Nicht entsperrt."
            },
            fallback = {
                val intent = AppLock.keyguardIntent(this)
                if (intent == null) {
                    authenticating = false
                    unlocked()
                } else {
                    keyguardLauncher.launch(intent)
                }
            },
        )
    }

    private fun unlocked() {
        locked = false
        lockMessage = null
    }

    /** With the app lock on, the preview in the list of recent apps stays empty (Android 13+). */
    private fun applyRecentsPrivacy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!app.prefs.appLock)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ROUTE)?.let {
            pendingRoute = it
            intent.removeExtra(EXTRA_ROUTE) // not again after a rotation
        }
        if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATE, false) == true) {
            val s = app.updater.state.value
            if (s is UpdateState.Available || s is UpdateState.Ready) app.updater.showDialog.value = true else app.updater.check()
        }
    }

    override fun onResume() {
        super.onResume()
        permissionGranted = hasBluetoothPermission()
        if (configured && permissionGranted) {
            if (app.repo.controller == null) StrapService.start(this) else app.repo.controller?.refresh()
        }
    }

    private fun requiredPermissions(): List<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.POST_NOTIFICATIONS
        return list
    }

    fun hasBluetoothPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    companion object {
        const val EXTRA_SHOW_UPDATE = "show_update"
        const val EXTRA_ROUTE = "route"
        private const val STATE_UNLOCKED = "unlocked"

        /** Opens the app on [route] (one of [Routes]). */
        fun openIntent(context: android.content.Context, route: String): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_ROUTE, route).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

@Composable
private fun CrashDialog(report: String, onClose: () -> Unit) {
    val context = LocalContext.current
    fun close() {
        CrashReporter.clear(context)
        onClose()
    }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text("HelioLink ist abgestürzt") },
        text = {
            Column {
                Text(
                    "Das tut mir leid. Kopiere den Bericht und schick ihn mit, wenn du den Fehler meldest – " +
                        "dann lässt er sich gezielt beheben. Er steht auch im Daten-Export.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("HelioLink-Absturz", report))
                Toast.makeText(context, "Bericht kopiert", Toast.LENGTH_SHORT).show()
                close()
            }) { Text("Kopieren") }
        },
        dismissButton = { TextButton(onClick = ::close) { Text("Schließen") } },
    )
}

@Composable
private fun AppContent(pendingRoute: String?, onRouteShown: () -> Unit, onForget: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        if (tabs.any { it.route == route }) nav.navigateToTab(route) else nav.navigate(route)
        onRouteShown()
    }
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBar = tabs.any { it.route == current }
    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = { nav.navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val bottom = if (showBar) PaddingValues(bottom = padding.calculateBottomPadding()) else PaddingValues()
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(bottom).consumeWindowInsets(bottom)) {
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.DATA) { de.waschbeken.heliolink.ui.data.DataScreen(nav) }
            composable(Routes.RECORDING) { de.waschbeken.heliolink.ui.record.RecordingScreen(nav) }
            composable(Routes.SLEEP) { SleepScreen(nav) }
            composable(Routes.TRAINING) { TrainingScreen(nav) }
            composable(Routes.ALARMS) { AlarmsScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.TOOLS_BREATHE) { de.waschbeken.heliolink.ui.tools.BreatheScreen(nav) }
            composable(Routes.TOOLS_HRV) { de.waschbeken.heliolink.ui.tools.HrvMeasureScreen(nav) }
            composable(Routes.TOOLS_FOCUS) { de.waschbeken.heliolink.ui.tools.FocusScreen(nav) }
            composable("metric/{type}") { MetricScreen(nav, it.arguments?.getString("type").orEmpty()) }
            composable("settings/{category}") { SettingsCategoryScreen(nav, it.arguments?.getString("category").orEmpty(), onForget) }
            composable("workout/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                WorkoutDetailScreen(nav, it.arguments?.getLong("id") ?: 0)
            }
            composable("sleep/edit/{day}", listOf(navArgument("day") { type = NavType.LongType })) {
                de.waschbeken.heliolink.ui.sleep.SleepEditScreen(nav, it.arguments?.getLong("day") ?: java.time.LocalDate.now().toEpochDay())
            }
            composable("alarm/{position}", listOf(navArgument("position") { type = NavType.IntType })) {
                AlarmEditScreen(nav, it.arguments?.getInt("position") ?: -1)
            }
        }
    }
}
