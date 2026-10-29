package de.waschbeken.heliolink.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.service.StrapService

class MainActivity : ComponentActivity() {

    private var configured by mutableStateOf(false)
    private var permissionGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionGranted = hasBluetoothPermission()
        if (configured && permissionGranted) StrapService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configured = app.prefs.isConfigured
        permissionGranted = hasBluetoothPermission()
        requestPermissions()
        setContent {
            HelioTheme {
                if (configured) {
                    MainScreen(onForget = {
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
}

private enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Heute", Icons.Filled.Home),
    SLEEP("Schlaf", Icons.Filled.DateRange),
    WORKOUT("Training", Icons.Filled.Favorite),
    SETTINGS("Strap", Icons.Filled.Settings),
}

@Composable
private fun MainScreen(onForget: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by context.app.repo.state.collectAsState()
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (Tab.entries[tab]) {
                Tab.TODAY -> TodayScreen(state, openSleep = { tab = Tab.SLEEP.ordinal })
                Tab.SLEEP -> SleepScreen(state)
                Tab.WORKOUT -> WorkoutScreen(state)
                Tab.SETTINGS -> SettingsScreen(state, onForget)
            }
        }
    }
}
