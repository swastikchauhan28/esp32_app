package com.mamadrones.esp32_app

import java.util.UUID

internal object BleProtocol {
    val controlService: UUID = UUID.fromString("e77c0000-5a4f-4b0b-9f74-3a3b8d0c1000")
    val ledRequest: UUID = UUID.fromString("e77c0001-5a4f-4b0b-9f74-3a3b8d0c1000")
    val fanRequest: UUID = UUID.fromString("e77c0002-5a4f-4b0b-9f74-3a3b8d0c1000")
    val deviceState: UUID = UUID.fromString("e77c0003-5a4f-4b0b-9f74-3a3b8d0c1000")
    val ledBrightness: UUID = UUID.fromString("e77c0004-5a4f-4b0b-9f74-3a3b8d0c1000")
    val batteryService: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val batteryLevel: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    data class OutputState(val ledOn: Boolean, val fanPercent: Int)

    fun parseState(bytes: ByteArray): OutputState? {
        if (bytes.size != 2) return null
        val led = bytes[0].toInt() and 0xff
        val fan = bytes[1].toInt() and 0xff
        return if (led <= 1 && fan <= 100) OutputState(led == 1, fan) else null
    }

    fun parseBattery(bytes: ByteArray): Int? =
        bytes.singleOrNull()?.let { it.toInt() and 0xff }?.takeIf { it <= 100 }

    fun parseBrightness(bytes: ByteArray): Int? = parseBattery(bytes)

    fun ledCommand(on: Boolean): ByteArray = byteArrayOf(if (on) 1.toByte() else 0.toByte())

    fun brightnessCommand(percent: Int): ByteArray = byteArrayOf(percent.coerceIn(0, 100).toByte())

    fun fanCommand(percent: Int): ByteArray = byteArrayOf(percent.coerceIn(0, 100).toByte())
}
