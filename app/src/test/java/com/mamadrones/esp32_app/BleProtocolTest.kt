package com.mamadrones.esp32_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleProtocolTest {
    @Test fun stateAcceptsOnlyExactValidPayload() {
        assertEquals(BleProtocol.OutputState(true, 75), BleProtocol.parseState(byteArrayOf(1, 75)))
        assertNull(BleProtocol.parseState(byteArrayOf(1)))
        assertNull(BleProtocol.parseState(byteArrayOf(2, 10)))
        assertNull(BleProtocol.parseState(byteArrayOf(0, 101.toByte())))
    }

    @Test fun batteryRejectsUnknownOrInvalidValue() {
        assertEquals(100, BleProtocol.parseBattery(byteArrayOf(100)))
        assertNull(BleProtocol.parseBattery(byteArrayOf()))
        assertNull(BleProtocol.parseBattery(byteArrayOf(255.toByte())))
    }

    @Test fun fanCommandStaysWithinProtocolRange() {
        assertEquals(0, BleProtocol.fanCommand(-1)[0].toInt())
        assertEquals(100, BleProtocol.fanCommand(150)[0].toInt())
    }

    @Test fun brightnessAcceptsOnlyOneValidPercent() {
        assertEquals(75, BleProtocol.parseBrightness(byteArrayOf(75)))
        assertNull(BleProtocol.parseBrightness(byteArrayOf()))
        assertNull(BleProtocol.parseBrightness(byteArrayOf(0, 1)))
        assertNull(BleProtocol.parseBrightness(byteArrayOf(101.toByte())))
        assertEquals(100, BleProtocol.brightnessCommand(200)[0].toInt())
    }
}
