# BLE control implementation

## Scope and path

Goal: Android app discovers an ESP32 using BLE, lets the user request LED on/off and fan off or 1–100% speed, and displays the battery percentage the device reports. This is app code only; it does not flash or operate hardware.

Path: `MainActivity.requestScan` obtains Android Bluetooth permissions and Bluetooth enablement; `Esp32BleClient.scan` filters advertisements by the custom service; the user chooses a listed device; `connect` discovers GATT services; state and battery are read and notifications requested; `ControlScreen` renders device-reported values; actions queue write-with-response operations; a fresh device-state read confirms each completed write. `BleProtocol` validates the small byte payloads. Android owns the BLE transport; the ESP32 firmware is the external, untrusted input and output boundary.

Data: The in-memory device list maps BLE addresses to Android `BluetoothDevice` objects only for the current scan. The GATT operation queue is an in-memory FIFO with one operation active at a time and a 7-second response timeout. App state contains one output pair (LED boolean, fan percent) and one battery percent; it is not persisted. A 10-second scan and 15-second connection timeout bound those operations. Battery history and runtime estimation are out of scope.

## Assumptions and risks

| Assumption | Status | Evidence / remaining check |
| --- | --- | --- |
| BLE is the requested transport | Verified | Owner selected BLE in this chat. |
| ESP32 firmware matches `BLE_PROTOCOL.md` | Unverified | Firmware not supplied; test against real device required. |
| Battery percentage can be reported accurately | Unverified | Battery and sensing circuit not specified. |
| Fan accepts a linear 0–100% speed request | Unverified | Motor driver and PWM calibration not specified. |
| Control characteristics enforce authenticated bonding | Unverified | Must be configured and verified on ESP32 firmware. |

Nearby devices may advertise the same service UUID. The user selects a visible address, but the UUID itself does not authenticate a device. Firmware must require encrypted, authenticated BLE access before writes. The app requests only Bluetooth permissions and keeps no credentials or device history. A disconnect, invalid state payload, failed state read or operation timeout disables control. Battery omission leaves an unavailable indicator. Android or device Bluetooth outages are shown as status messages. Physical motor and battery safety depends on external circuitry, firmware limits and real hardware verification.

## Verification and release

- Baseline `./gradlew.bat :app:compileDebugKotlin` on 2026-11-16 failed before app compilation: Core 1.19.1 and Lifecycle 2.11.0 require SDK 37 and AGP 9.1, while project uses SDK 36 and AGP 8.13.2. The owner approved changing only these pins to Core 1.17.0 and Lifecycle 2.9.4.
- Fresh `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` on 2026-11-16 succeeded after correcting two Android 13 GATT return-code comparisons found by the first lint run. Protocol test report: `app/build/test-results/testDebugUnitTest/TEST-com.mamadrones.esp32_app.BleProtocolTest.xml` (3 passed, 0 failed). Lint report: `app/build/reports/lint-results-debug.html` (0 errors, 15 warnings). Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
- ADB showed no attached device or emulator. No visual, end-to-end or physical device test has run yet. The manifest and changed-file list were inspected; a targeted secret-pattern scan of changed app code and docs found no matches.
- Real path to test: install app, grant Nearby devices, scan, select a device, observe initial LED/fan/battery values, toggle LED, turn fan on/off, change speed, disconnect and confirm controls disable. Independently verify the fan and LED outputs and battery sensing on actual hardware.
- No Git metadata exists in this project directory, so there is no recorded revision or Git diff/rollback point. Keep a copy of the project before risky hardware or release changes. No commit or push is possible here.

Release remains blocked by missing physical-output firmware, secure bonding evidence on the actual device, circuit details, physical tests, owner explanation/diff review and a verified recovery artifact. The owner should be able to explain the BLE protocol, driver limits, battery measurement and disconnect behavior before real use.

## 2026-11-18 connection-only firmware

`esp32_firmware/ble_connection_test/ble_connection_test.ino` is a standalone Arduino sketch for the generic ESP32 Dev Module. BLE writes enter characteristic callbacks, validate one-byte payloads, update only two RAM bytes, and publish the two-byte state value. No GPIO or Battery Service exists. The service advertises the same 128-bit UUID filtered by the Android app. Random passkey-based authenticated bonding protects control writes and state reads. The passkey is shown in Serial Monitor and not stored in project files.

Fresh verification: Arduino CLI `compile --fqbn esp32:esp32:esp32 --build-path esp32_firmware/build esp32_firmware/ble_connection_test` succeeded with Espressif core 3.3.13. Output: 1,115,728 bytes program storage (85%) and 41,460 bytes global RAM (12%). No upload, pairing, app integration or physical hardware test has run. The successful compile is not evidence that the Android scan and pairing flow works on the owner's phone. The sketch is an isolated experiment; it is not a production controller.

## 2026-11-18 onboard LED probe

Owner later uploaded the BLE connection test successfully (Arduino log reported all flash hashes verified and hard reset); Serial Monitor reported `Advertising ESP32-Control-Test`. This verifies firmware upload and advertising output, not the phone connection. The owner then requested built-in LED brightness and reported only a red LED visible. The board's exact LED circuit is unknown. `esp32_firmware/gpio2_led_probe/gpio2_led_probe.ino` fades GPIO2 only, to test whether there is a separate user LED. Fresh Arduino CLI compile with Espressif core 3.3.13 succeeded (285,142 bytes program, 22,376 bytes global RAM). The probe has not been uploaded or physically observed. App and BLE protocol have not been changed for brightness pending that physical check.

## 2026-11-18 GPIO2 brightness implementation

The owner subsequently confirmed a **second** onboard LED faded with the GPIO2 probe; the red LED is the power indicator. `esp32_firmware/ble_led_brightness/ble_led_brightness.ino` now drives GPIO2 with 5 kHz, 8-bit PWM. The old one-byte LED request retains on/off behavior and restores the last nonzero brightness. An optional `e77c0004-5a4f-4b0b-9f74-3a3b8d0c1000` characteristic reads/writes effective 0–100% brightness. The two-byte state format remains unchanged, so earlier app builds can still use on/off. The new Android app detects the optional characteristic, reads it, shows the slider and refreshes state/brightness after writes. Fan values remain RAM-only; battery service is omitted. Characteristic reads/writes require authenticated bonding. No production credential is stored in the project; the sketch generates a pairing code on boot and prints it to Serial Monitor.

Fresh checks: `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` succeeded (protocol test XML reports 4 tests, 0 failures; lint 0 errors and 15 warnings). Arduino CLI `compile --fqbn esp32:esp32:esp32 --build-path esp32_firmware/led_build esp32_firmware/ble_led_brightness` succeeded with core 3.3.13 (1,125,405 bytes program/85%, 41,680 bytes global RAM/12%). Generated Arduino build files were removed after verification. ADB found no attached Android device. No new firmware upload, visual inspection, BLE pairing or physical brightness test has occurred. The owner must verify which direction of the slider makes the second LED brighter; `LED_ACTIVE_LOW` can be inverted if needed. The earlier BLE test sketch is preserved for recovery. No Git repository/commit/push is present.

Owner follow-up on 2026-11-18: Their Arduino upload log showed the BLE LED image verified on COM14 and reset successfully. The owner then reported that everything is working on the phone and board. Treat this as user-reported end-to-end acceptance for the BLE/onboard LED experiment, not as an independently witnessed physical test. Fan and battery hardware remain outside this acceptance.
