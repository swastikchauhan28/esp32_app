# Handover

## 2026-11-18 — owner BLE LED test

- Done: Owner reported that the updated BLE LED firmware, Android app, connection and brightness control are all working on their phone and ESP32 board. This is a user-reported physical test; Codex did not observe device behavior independently. The upload log showed the firmware verified on COM14 and reset successfully.
- In progress: None for the connection and built-in LED brightness experiment.
- Broken or risky: Fan output and battery sensing are not implemented; the fan value in test firmware is virtual. The GPIO2 LED brightness direction and exact observed range were not separately recorded. This experiment is not a production release.
- Next: When hardware is available, identify the fan driver, bulb and battery measurement circuit before extending firmware or claiming physical control.
- Do not touch: Preserve the working BLE LED firmware and Android APK as the current user-tested experiment. Do not wire a fan, bulb or battery directly to ESP32 GPIO.

## 2026-11-18 — BLE LED brightness

- Done: Owner confirmed a second onboard LED faded with the GPIO2 probe. Added optional BLE brightness characteristic, Android LED slider, protocol test, and a separate GPIO2 PWM BLE sketch; preserved the earlier connection-test sketch. Fresh `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` succeeded (4 protocol tests passed, lint 0 errors/15 warnings). Fresh Arduino CLI compile for `esp32:esp32:esp32` core 3.3.13 succeeded (1,125,405 bytes program, 41,680 bytes global RAM). New debug APK is in `app/build/outputs/apk/debug/app-debug.apk`.
- In progress: User installation and physical acceptance. No new firmware upload or phone end-to-end test has run; ADB found no attached Android device.
- Broken or risky: GPIO2 LED polarity and brightness direction are not physically verified with the new firmware. The new sketch's fan remains virtual and battery is unavailable. Android may cache old GATT services after reflashing; forget the old bond if the brightness slider is missing.
- Next: User uploads `esp32_firmware/ble_led_brightness/ble_led_brightness.ino`, installs the latest APK, pairs with `ESP32-LED`, and checks on/off, 0–100% brightness and disconnect behavior. Capture results and adjust polarity if needed.
- Do not touch: The red USB power LED is separate; no fan, external lamp or battery driver is implemented. No live upload was performed by Codex.

## 2026-11-18 — onboard LED request

- Done: Owner requested phone control and brightness for the built-in LED and reported only a red LED visible. Added a separate GPIO2 PWM probe sketch. Fresh Arduino CLI compile for `esp32:esp32:esp32` with core 3.3.13 succeeded (285,142 bytes program, 22,376 bytes global RAM).
- In progress: Physical identification of an onboard GPIO LED. No probe upload or LED observation has occurred.
- Broken or risky: The red LED may be a USB power indicator and not GPIO-controllable. The current Android app and BLE test sketch do not implement LED brightness. Uploading the probe temporarily replaces the BLE test sketch.
- Next: Owner can upload the GPIO2 probe and report whether any second LED fades while the red power LED stays steady. If GPIO2 is confirmed, extend BLE firmware and Android UI for brightness; otherwise use a separate LED with a resistor or identify another board with a controllable LED.
- Do not touch: Do not claim the red power LED is GPIO-controllable without physical or schematic evidence.

## 2026-11-18

- Done: Owner confirmed the CP2102 driver and Arduino IDE setup, with ESP32 Dev Module on COM14. Added a connection-only ESP32 sketch that matches the app's GATT contract, uses RAM-only virtual LED/fan values, requires authenticated BLE bonding and omits Battery Service. Fresh Arduino CLI compile for `esp32:esp32:esp32` with Espressif core 3.3.13 succeeded (1,115,728 bytes program storage, 41,460 bytes global RAM).
- In progress: Owner can choose to upload the sketch and test app-to-board pairing/connection. No upload or physical test has occurred.
- Broken or risky: Upload replaces current board firmware; current sketch is unknown. Pairing behavior and Android end-to-end path remain unverified. Fan, bulb and battery circuitry remain unspecified. The test UI values are virtual only.
- Next: If owner accepts replacing the current sketch, open and upload `esp32_firmware/ble_connection_test/ble_connection_test.ino` via Arduino IDE, then read the random pairing code in Serial Monitor at 115200 and scan with the Android app. Capture actual results.
- Do not touch: No GPIO, external output or battery is connected or controlled by the test firmware.

## 2026-11-16

- Done: Inspected the starter Compose app; owner selected BLE. Implemented the BLE client, permission flow, device selector, LED/fan controls, battery display, protocol parser tests and contract. No Git repository exists here.
- In progress: Real-device integration and visual acceptance. Owner approved Core 1.17.0 and Lifecycle 2.9.4; those pins are applied.
- Broken or risky: Fresh unit tests, lint and debug APK build succeeded, but no Android device/emulator was attached, and no ESP32 firmware or circuit details are available. Battery sensing and secure bonding must be implemented on the ESP32. Lint has 15 warnings and no errors.
- Next: Match ESP32 firmware to `BLE_PROTOCOL.md`, install the debug APK on an Android phone, test the full connection/control/disconnect path and independently verify physical LED/fan/battery behavior.
- Do not touch: No existing hardware pins, firmware or live device has been identified.
