package com.mamadrones.esp32_app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mamadrones.esp32_app.ui.theme.Esp32_appTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var bleClient: Esp32BleClient

    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.isNotEmpty() && grants.values.all { it }) startScanIfBluetoothEnabled()
        else bleClient.stopScan("Allow Nearby devices to scan and connect.")
    }

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (bleClient.adapter?.isEnabled == true) bleClient.scan()
        else bleClient.stopScan("Turn on Bluetooth to scan.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleClient = Esp32BleClient(this)
        setContent {
            Esp32_appTheme {
                ControlScreen(
                    state = bleClient.state,
                    devices = bleClient.devices,
                    onScan = ::requestScan,
                    onConnect = bleClient::connect,
                    onDisconnect = bleClient::disconnect,
                    onLed = bleClient::requestLed,
                    onLedBrightness = bleClient::requestLedBrightness,
                    onFan = bleClient::requestFan,
                )
            }
        }
    }

    private fun requestScan() {
        if (bleClient.adapter == null) {
            bleClient.stopScan("This phone does not support Bluetooth.")
            return
        }
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionsLauncher.launch(missing.toTypedArray())
        else startScanIfBluetoothEnabled()
    }

    private fun startScanIfBluetoothEnabled() {
        if (bleClient.adapter?.isEnabled == true) bleClient.scan()
        else enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    override fun onDestroy() {
        bleClient.close()
        super.onDestroy()
    }
}

@Composable
private fun ControlScreen(
    state: BleUiState,
    devices: List<FoundDevice>,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onLed: (Boolean) -> Unit,
    onLedBrightness: (Int) -> Unit,
    onFan: (Int) -> Unit,
) {
    val ready = state.connectedName != null && state.output != null
    val benchFan = state.connectedName?.startsWith("ESP32-Bench-Fan") == true
    val output = state.output
    var speedDraft by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var lastRunningSpeed by remember { mutableIntStateOf(50) }
    var ledBrightnessDraft by remember { mutableFloatStateOf(0f) }
    var draggingLedBrightness by remember { mutableStateOf(false) }
    LaunchedEffect(state.ledBrightnessPercent) {
        if (!draggingLedBrightness) ledBrightnessDraft = (state.ledBrightnessPercent ?: 0).toFloat()
    }
    LaunchedEffect(output?.fanPercent) {
        if (!dragging) speedDraft = (output?.fanPercent ?: 0).toFloat()
        if ((output?.fanPercent ?: 0) > 0) lastRunningSpeed = output!!.fanPercent
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("ESP32 Control", style = MaterialTheme.typography.headlineMedium)
            Text("Control your light and fan over Bluetooth Low Energy.", style = MaterialTheme.typography.bodyMedium)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connection", style = MaterialTheme.typography.titleLarge)
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onScan, enabled = !state.scanning) {
                            Text(if (state.scanning) "Scanning…" else "Scan for ESP32")
                        }
                        if (state.connectedName != null) {
                            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                        }
                    }
                    if (state.connectedName == null && devices.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Nearby devices", style = MaterialTheme.typography.titleMedium)
                        devices.forEach { device ->
                            OutlinedButton(onClick = { onConnect(device.address) }, modifier = Modifier.fillMaxWidth()) {
                                Text("${device.name} · ${device.address}")
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Built-in LED", style = MaterialTheme.typography.titleLarge)
                            Text(if (ready) if (output!!.ledOn) "On" else "Off" else if (state.connectedName != null) "Waiting for device state" else "Connect to control")
                        }
                        Switch(checked = output?.ledOn ?: false, enabled = ready, onCheckedChange = onLed)
                    }
                    if (state.ledBrightnessPercent != null) {
                        Text("Brightness: ${state.ledBrightnessPercent}%", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = ledBrightnessDraft,
                            onValueChange = { ledBrightnessDraft = it; draggingLedBrightness = true },
                            onValueChangeFinished = {
                                draggingLedBrightness = false
                                onLedBrightness(ledBrightnessDraft.roundToInt())
                            },
                            valueRange = 0f..100f,
                            enabled = ready,
                        )
                        Text("0% turns the LED off.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Fan", style = MaterialTheme.typography.titleLarge)
                            Text(if (ready) {
                                if (benchFan) "${output!!.fanPercent}% PWM command"
                                else "${output!!.fanPercent}% speed"
                            } else if (state.connectedName != null) "Waiting for device state" else "Connect to control")
                        }
                        Switch(
                            checked = (output?.fanPercent ?: 0) > 0,
                            enabled = ready,
                            onCheckedChange = { on -> onFan(if (on) lastRunningSpeed else 0) },
                        )
                    }
                    Text(if (benchFan) "PWM duty (bench test)" else "Speed", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = speedDraft,
                        onValueChange = { speedDraft = it; dragging = true },
                        onValueChangeFinished = {
                            dragging = false
                            onFan(speedDraft.roundToInt())
                        },
                        valueRange = 0f..100f,
                        enabled = ready,
                    )
                    Text(if (benchFan) "0% turns the fan off. PWM duty is not measured airflow or RPM."
                         else "Move to 0% to turn the fan off.", style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Battery", style = MaterialTheme.typography.titleLarge)
                    val battery = state.batteryPercent
                    Text(if (battery == null) "Battery level unavailable" else if (benchFan) "$battery% estimated from battery voltage" else "$battery% reported by ESP32")
                    if (benchFan) Text("Bench estimate only; not a low-battery safety warning.", style = MaterialTheme.typography.bodySmall)
                    if (battery != null) LinearProgressIndicator(
                        progress = { battery / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
