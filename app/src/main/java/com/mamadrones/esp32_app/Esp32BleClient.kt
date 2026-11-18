package com.mamadrones.esp32_app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.ArrayDeque

internal data class FoundDevice(val name: String, val address: String)

internal data class BleUiState(
    val message: String = "Scan to find your ESP32",
    val scanning: Boolean = false,
    val connectedName: String? = null,
    val output: BleProtocol.OutputState? = null,
    val ledBrightnessPercent: Int? = null,
    val batteryPercent: Int? = null,
)

/** Owns one GATT link. All observable state and queued operations run on the main thread. */
@SuppressLint("MissingPermission")
internal class Esp32BleClient(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    var state by mutableStateOf(BleUiState())
        private set
    val devices = mutableStateListOf<FoundDevice>()
    private val deviceByAddress = mutableMapOf<String, BluetoothDevice>()

    private var gatt: BluetoothGatt? = null
    private var ledCharacteristic: BluetoothGattCharacteristic? = null
    private var fanCharacteristic: BluetoothGattCharacteristic? = null
    private var stateCharacteristic: BluetoothGattCharacteristic? = null
    private var brightnessCharacteristic: BluetoothGattCharacteristic? = null
    private var batteryCharacteristic: BluetoothGattCharacteristic? = null
    private val operations = ArrayDeque<() -> Boolean>()
    private var inFlight = false
    private var targetName: String? = null

    private val scanTimeout = Runnable { stopScan("Scan finished. Select a device or scan again.") }
    private val connectionTimeout = Runnable { failConnection("Connection timed out. Try again.") }
    private val operationTimeout = Runnable { failConnection("Device stopped responding. Reconnect and try again.") }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (!state.scanning) return@post
                val address = result.device.address
                if (deviceByAddress.containsKey(address)) return@post
                deviceByAddress[address] = result.device
                val name = result.scanRecord?.deviceName ?: "ESP32 device"
                devices.add(FoundDevice(name, address))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post { stopScan("Bluetooth scan failed ($errorCode). Try again.") }
        }
    }

    fun scan() {
        if (adapter?.isEnabled != true) {
            state = state.copy(message = "Turn on Bluetooth to scan.")
            return
        }
        stopScan(null)
        disconnect()
        devices.clear()
        deviceByAddress.clear()
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            state = state.copy(message = "BLE scanning is unavailable on this phone.")
            return
        }
        try {
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleProtocol.controlService))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(listOf(filter), settings, scanCallback)
            state = state.copy(scanning = true, message = "Scanning for ESP32 devices…")
            handler.postDelayed(scanTimeout, 10_000)
        } catch (_: SecurityException) {
            state = state.copy(message = "Nearby devices permission is required.")
        } catch (_: IllegalStateException) {
            state = state.copy(message = "Bluetooth is unavailable. Try again.")
        }
    }

    fun stopScan(message: String? = null) {
        handler.removeCallbacks(scanTimeout)
        if (state.scanning) {
            try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
            catch (_: SecurityException) { }
            catch (_: IllegalStateException) { }
        }
        state = state.copy(scanning = false, message = message ?: state.message)
    }

    fun connect(address: String) {
        val device = deviceByAddress[address] ?: return
        val name = devices.firstOrNull { it.address == address }?.name ?: "ESP32 device"
        stopScan()
        disconnect()
        targetName = name
        state = BleUiState(message = "Connecting to $name…")
        try {
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                failConnection("Could not start the connection.")
            } else {
                handler.postDelayed(connectionTimeout, 15_000)
            }
        } catch (_: SecurityException) {
            failConnection("Nearby devices permission is required.")
        } catch (_: IllegalArgumentException) {
            failConnection("Could not connect to this device.")
        }
    }

    fun requestLed(on: Boolean) = enqueueWrite(ledCharacteristic, BleProtocol.ledCommand(on))

    fun requestLedBrightness(percent: Int) = enqueueWrite(brightnessCharacteristic, BleProtocol.brightnessCommand(percent))

    fun requestFan(percent: Int) = enqueueWrite(fanCharacteristic, BleProtocol.fanCommand(percent))

    private fun enqueueWrite(characteristic: BluetoothGattCharacteristic?, bytes: ByteArray) {
        if (state.output == null || characteristic == null || gatt == null) return
        state = state.copy(output = null, message = "Sending request to device…")
        enqueue {
            writeCharacteristic(characteristic, bytes)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        val client = gatt ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                client.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = bytes
                client.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) { false }
    }

    private fun enqueue(action: () -> Boolean) {
        operations.addLast(action)
        pump()
    }

    private fun pump() {
        if (inFlight || gatt == null) return
        while (operations.isNotEmpty()) {
            val action = operations.removeFirst()
            inFlight = true
            if (action()) {
                handler.postDelayed(operationTimeout, 7_000)
                return
            }
            inFlight = false
            state = state.copy(message = "A Bluetooth operation could not start. Reconnect if controls do not respond.")
        }
    }

    private fun completeOperation() {
        handler.removeCallbacks(operationTimeout)
        inFlight = false
        pump()
    }

    private fun enqueueRead(characteristic: BluetoothGattCharacteristic?) {
        if (characteristic == null) return
        enqueue { try { gatt?.readCharacteristic(characteristic) == true } catch (_: SecurityException) { false } }
    }

    @Suppress("DEPRECATION")
    private fun enqueueNotifications(characteristic: BluetoothGattCharacteristic?) {
        if (characteristic == null || characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) return
        val descriptor = characteristic.getDescriptor(BleProtocol.cccd) ?: return
        enqueue {
            val client = gatt ?: return@enqueue false
            try {
                if (!client.setCharacteristicNotification(characteristic, true)) return@enqueue false
                if (Build.VERSION.SDK_INT >= 33) {
                    client.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    client.writeDescriptor(descriptor)
                }
            } catch (_: SecurityException) { false }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (client !== gatt) return@post
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> failConnection("Connection lost ($status). Reconnect to control the device.")
                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        state = state.copy(message = "Connected. Reading device state…", connectedName = targetName)
                        try {
                            if (!client.discoverServices()) failConnection("Could not discover device services.")
                        } catch (_: SecurityException) { failConnection("Nearby devices permission is required.") }
                    }
                    newState == BluetoothProfile.STATE_DISCONNECTED -> failConnection("Device disconnected. Reconnect to control it.")
                }
            }
        }

        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            handler.post {
                if (client !== gatt) return@post
                handler.removeCallbacks(connectionTimeout)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection("Could not discover device services.")
                    return@post
                }
                val service = client.getService(BleProtocol.controlService)
                ledCharacteristic = service?.getCharacteristic(BleProtocol.ledRequest)
                fanCharacteristic = service?.getCharacteristic(BleProtocol.fanRequest)
                stateCharacteristic = service?.getCharacteristic(BleProtocol.deviceState)
                brightnessCharacteristic = service?.getCharacteristic(BleProtocol.ledBrightness)
                    ?.takeIf { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 &&
                        it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 }
                batteryCharacteristic = client.getService(BleProtocol.batteryService)
                    ?.getCharacteristic(BleProtocol.batteryLevel)
                if (ledCharacteristic?.properties?.and(BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 ||
                    fanCharacteristic?.properties?.and(BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 ||
                    stateCharacteristic?.properties?.and(BluetoothGattCharacteristic.PROPERTY_READ) == 0 ||
                    ledCharacteristic == null || fanCharacteristic == null || stateCharacteristic == null) {
                    failConnection("This device does not support the required control protocol.")
                    return@post
                }
                enqueueNotifications(stateCharacteristic)
                enqueueNotifications(batteryCharacteristic)
                enqueueRead(stateCharacteristic)
                enqueueRead(brightnessCharacteristic)
                enqueueRead(batteryCharacteristic)
            }
        }

        @Deprecated("Used by Android versions below 13")
        override fun onCharacteristicRead(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value?.clone() ?: byteArrayOf()
            handleRead(client, characteristic, bytes, status)
        }

        override fun onCharacteristicRead(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(client, characteristic, value, status)
        }

        private fun handleRead(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray, status: Int) {
            handler.post {
                if (client !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) acceptValue(characteristic, bytes)
                else state = state.copy(
                    output = if (characteristic.uuid == BleProtocol.deviceState) null else state.output,
                    ledBrightnessPercent = if (characteristic.uuid == BleProtocol.ledBrightness) null else state.ledBrightnessPercent,
                    message = "Could not read device data ($status). Reconnect and try again.",
                )
                completeOperation()
            }
        }

        @Deprecated("Used by Android versions below 13")
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value?.clone() ?: byteArrayOf()
            handleNotification(client, characteristic, bytes)
        }

        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(client, characteristic, value)
        }

        private fun handleNotification(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
            handler.post { if (client === gatt) acceptValue(characteristic, bytes) }
        }

        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (client !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) state = state.copy(message = "Live updates are unavailable ($status).")
                completeOperation()
            }
        }

        override fun onCharacteristicWrite(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (client !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    state = state.copy(message = "Control request failed ($status). Check pairing and retry.")
                } else {
                    state = state.copy(message = "Request sent. Waiting for device state…")
                    enqueueRead(stateCharacteristic)
                    enqueueRead(brightnessCharacteristic)
                }
                completeOperation()
            }
        }
    }

    private fun acceptValue(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        when (characteristic.uuid) {
            BleProtocol.deviceState -> {
                val output = BleProtocol.parseState(bytes)
                state = if (output == null) state.copy(output = null, message = "Device sent an invalid state value. Reconnect and try again.")
                else state.copy(output = output, message = "Connected to ${state.connectedName ?: "ESP32"}")
            }
            BleProtocol.batteryLevel -> {
                val percent = BleProtocol.parseBattery(bytes)
                state = if (percent == null) state.copy(batteryPercent = null, message = "Device sent an invalid battery value.")
                else state.copy(batteryPercent = percent)
            }
            BleProtocol.ledBrightness -> {
                val percent = BleProtocol.parseBrightness(bytes)
                state = if (percent == null) state.copy(ledBrightnessPercent = null, message = "Device sent an invalid LED brightness value.")
                else state.copy(ledBrightnessPercent = percent)
            }
        }
    }

    fun disconnect() {
        handler.removeCallbacks(connectionTimeout)
        handler.removeCallbacks(operationTimeout)
        operations.clear()
        inFlight = false
        ledCharacteristic = null
        fanCharacteristic = null
        stateCharacteristic = null
        brightnessCharacteristic = null
        batteryCharacteristic = null
        val old = gatt
        gatt = null
        try { old?.disconnect() } catch (_: SecurityException) { }
        old?.close()
        targetName = null
        state = state.copy(message = "Disconnected. Scan to reconnect.", connectedName = null, output = null, ledBrightnessPercent = null, batteryPercent = null)
    }

    private fun failConnection(message: String) {
        disconnect()
        state = state.copy(message = message)
    }

    fun close() {
        stopScan()
        disconnect()
    }
}
