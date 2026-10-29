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
    const val SLEEP = "sleep"
    const val TRAINING = "training"
    const val ALARMS = "alarms"
    const val SETTINGS = "settings"
    fun metric(type: String) = "metric/$type"
    fun settings(category: String) = "settings/$category"
    fun workout(id: Long) = "workout/$id"
    fun alarm(position: Int) = "alarm/$position"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Heute", Icons.Filled.MonitorHeart),
    Tab(Routes.SLEEP, "Schlaf", Icons.Filled.Bedtime),
    Tab(Routes.TRAINING, "Training", Icons.Filled.FitnessCenter),
    Tab(Routes.ALARMS, "Wecker", Icons.Filled.Alarm),
    Tab(Routes.SETTINGS, "Einstellungen", Icons.Filled.Settings),
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
                UpdateDialog(app.updater)
                crashReport?.let { report -> CrashDialog(report) { crashReport = null } }
                if (configured) {
                    AppContent(onForget = {
                        app.repo.controller?.shutdown()
                        app.prefs.forgetDevice()
                        configured = false
                    })
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
    }

    override fun onStop() {
        AppVisibility.inForeground = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
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
private fun AppContent(onForget: () -> Unit) {
    val nav = rememberNavController()
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
            composable(Routes.SLEEP) { SleepScreen(nav) }
            composable(Routes.TRAINING) { TrainingScreen(nav) }
            composable(Routes.ALARMS) { AlarmsScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable("metric/{type}") { MetricScreen(nav, it.arguments?.getString("type").orEmpty()) }
            composable("settings/{category}") { SettingsCategoryScreen(nav, it.arguments?.getString("category").orEmpty(), onForget) }
            composable("workout/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                WorkoutDetailScreen(nav, it.arguments?.getLong("id") ?: 0)
            }
            composable("alarm/{position}", listOf(navArgument("position") { type = NavType.IntType })) {
                AlarmEditScreen(nav, it.arguments?.getInt("position") ?: -1)
            }
        }
    }
}
