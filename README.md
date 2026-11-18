# ESP32 Control

Android app and ESP32 Arduino sketches for Bluetooth Low Energy control. The current working experiment connects a phone to a 30-pin ESP32 development board and controls its GPIO2 onboard LED with on/off and 0–100% brightness. The owner reported this flow working on their phone and board; Codex has not independently observed the physical test.

## What is implemented

- Android Compose app: BLE scan, device selection, pairing through Android, connection status, LED on/off and brightness, fan controls and battery display.
- `esp32_firmware/ble_led_brightness/`: BLE sketch for the physically verified GPIO2 user LED. Fan commands update RAM-only test values; no fan output exists. Battery Service is omitted, so the app shows battery unavailable.
- `esp32_firmware/ble_connection_test/`: BLE-only sketch with virtual LED/fan values and no GPIO output.
- `esp32_firmware/gpio2_led_probe/`: standalone PWM probe used to identify the onboard LED.

The red USB power LED is separate from the GPIO2 user LED. Do not connect a fan, bulb or battery directly to an ESP32 GPIO. Physical fan control and battery sensing require suitable circuitry and further firmware work.

## Build and use

1. Open this directory in Android Studio. Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` (Windows: `gradlew.bat`). The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk` and is intentionally not committed.
2. Open `esp32_firmware/ble_led_brightness/ble_led_brightness.ino` in Arduino IDE with Espressif's `esp32` core 3.3.13. The tested board target was **ESP32 Dev Module** and the owner's USB port was **COM14**. Port numbers vary by computer. Uploading replaces the board's current sketch.
3. Open Serial Monitor at 115200 baud and press EN. The sketch prints a random six-digit pairing code and advertises as `ESP32-LED`.
4. Install the debug APK on an Android phone. In the app, grant Nearby devices permission, scan for `ESP32-LED`, pair if prompted, and test the onboard LED switch and brightness slider. The fan card is still virtual and battery should say unavailable.

See [the BLE protocol](docs/BLE_PROTOCOL.md), [implementation and verification notes](docs/IMPLEMENTATION.md), and [current handover](docs/HANDOVER.md). The latest verified builds and remaining physical checks are recorded there.
