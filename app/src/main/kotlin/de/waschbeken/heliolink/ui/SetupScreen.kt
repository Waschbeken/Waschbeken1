package de.waschbeken.heliolink.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.waschbeken.heliolink.app
import de.waschbeken.heliolink.core.parseHex

data class FoundDevice(val name: String, val address: String, val rssi: Int) {
    val isHelio get() = name.contains("Helio", ignoreCase = true)
}

@Composable
fun SetupScreen(hasPermission: Boolean, requestPermission: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    var key by remember { mutableStateOf(prefs.authKeyHex.orEmpty()) }
    var selected by remember { mutableStateOf<FoundDevice?>(null) }
    var manualAddress by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val devices = remember { mutableStateListOf<FoundDevice>() }

    val keyBytes = parseHex(key)
    val keyValid = keyBytes != null && keyBytes.size == 16
    val address = selected?.address ?: manualAddress.trim().uppercase().takeIf { it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("HelioLink einrichten", style = MaterialTheme.typography.headlineMedium)
            Text(
                "HelioLink spricht direkt mit deinem Amazfit Helio Strap – ohne Zepp-Cloud. " +
                    "Dafür braucht die App einmalig den Auth-Key des Straps.",
                style = MaterialTheme.typography.bodyMedium,
            )

            SectionCard("1. Auth-Key") {
                Text(
                    "Der Strap akzeptiert nur Apps, die seinen geheimen Schlüssel kennen. Du bekommst ihn, " +
                        "indem du den Strap einmal mit der Zepp-App koppelst und den Key danach aus deinem " +
                        "Zepp-Konto ausliest (z. B. mit dem Skript „huami-token“, siehe README). " +
                        "Wichtig: Danach den Strap in der Zepp-App NICHT entkoppeln, sonst wird der Key ungültig. " +
                        "Bluetooth in der Zepp-App ausschalten oder die App beenden.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    label = { Text("32 Hex-Zeichen, z. B. 0x1a2b…") },
                    singleLine = true,
                    isError = key.isNotEmpty() && !keyValid,
                    supportingText = {
                        if (key.isNotEmpty() && !keyValid) Text("Der Key muss genau 32 Hex-Zeichen haben")
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard("2. Strap finden") {
                Text(
                    "Lege den Strap an oder auf das Ladegerät, damit er sendet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!hasPermission) {
                    Button(onClick = requestPermission) { Text("Bluetooth-Berechtigung erteilen") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { scanning = !scanning }) { Text(if (scanning) "Suche stoppen" else "Suchen") }
                        if (scanning) CircularProgressIndicator(Modifier.height(24.dp))
                    }
                }
                if (scanning) {
                    BleScanner(onFound = { d ->
                        val idx = devices.indexOfFirst { it.address == d.address }
                        if (idx >= 0) devices[idx] = d else devices += d
                    })
                }
                devices.sortedWith(compareByDescending<FoundDevice> { it.isHelio }.thenByDescending { it.rssi }).forEach { d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = d }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected?.address == d.address, onClick = { selected = d })
                        Column(Modifier.weight(1f)) {
                            Text(d.name + if (d.isHelio) "  ✓" else "")
                            Text("${d.address} · ${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = manualAddress,
                    onValueChange = { manualAddress = it; selected = null },
                    label = { Text("oder MAC-Adresse manuell (AA:BB:CC:DD:EE:FF)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    prefs.authKeyHex = key
                    prefs.deviceAddress = address
                    prefs.deviceName = selected?.name ?: "Amazfit Helio Strap"
                    scanning = false
                    onDone()
                },
                enabled = keyValid && address != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Verbinden") }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BleScanner(onFound: (FoundDevice) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                onFound(FoundDevice(name, result.device.address, result.rssi))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner?.startScan(null, settings, callback)
        } catch (_: SecurityException) {
        }
        onDispose {
            try {
                scanner?.stopScan(callback)
            } catch (_: SecurityException) {
            }
        }
    }
}
