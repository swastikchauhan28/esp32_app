# ESP32 BLE contract

The phone is the GATT client and the ESP32 is the GATT server. The ESP32 advertises the custom service UUID below. The app scans only for that service and asks the user to select a discovered device. All numeric values are unsigned bytes.

| Item | UUID | Properties | Value |
| --- | --- | --- | --- |
| Control service | `e77c0000-5a4f-4b0b-9f74-3a3b8d0c1000` | Advertised | — |
| LED request | `e77c0001-5a4f-4b0b-9f74-3a3b8d0c1000` | Write with response | `0` off, `1` on |
| Fan request | `e77c0002-5a4f-4b0b-9f74-3a3b8d0c1000` | Write with response | `0` off, `1..100` speed percent |
| Device state | `e77c0003-5a4f-4b0b-9f74-3a3b8d0c1000` | Read, Notify | Byte 0: LED `0/1`; byte 1: fan `0..100` |
| LED brightness | `e77c0004-5a4f-4b0b-9f74-3a3b8d0c1000` | Read, Write with response; optional | One byte `0..100` effective brightness percent; `0` off |
| Battery Service | `0000180f-0000-1000-8000-00805f9b34fb` | Service | — |
| Battery Level | `00002a19-0000-1000-8000-00805f9b34fb` | Read, Notify | `0..100` percent |

The ESP32 should publish state after applying any command, including commands from other clients. A successful GATT write only means the request was delivered, not that an output changed. The app treats state notifications and reads as the source of truth. Battery percentage means reported charge percentage, not estimated hours remaining. If the ESP32 cannot measure charge, it should omit Battery Level and the app will show unavailable.

When the optional LED brightness characteristic exists, `LED request = 0` turns the LED off and `LED request = 1` restores the last nonzero brightness (default 100%). Writing a brightness value changes the effective PWM duty and the first state byte to `1` when nonzero. The app reads brightness after connection and after control writes. Older firmware without this characteristic still supports the on/off switch, and the app hides the brightness slider.

Firmware must require authenticated LE Secure Connections bonding before accepting control writes. The app does not store credentials or battery history. Choose LED and fan drivers, PWM range, battery measurement and protective circuits for the actual hardware; do not connect a fan or bulb directly to an ESP32 GPIO. No live hardware operation is part of this implementation.

## Connection-only test firmware

`../esp32_firmware/ble_connection_test/ble_connection_test.ino` implements the required custom characteristics with RAM-only LED/fan values. Its Bluetooth name is `ESP32-Control-Test`. It omits Battery Service because no battery sensor is connected, so the app should show Battery level unavailable. The app's switches and slider can exercise BLE requests, but they do not operate any physical output. A random pairing code is printed at 115200 baud on each boot; protected characteristics require authenticated pairing. This sketch is an isolated transport test and must not be treated as fan, bulb or battery firmware.

`../esp32_firmware/ble_led_brightness/ble_led_brightness.ino` adds the optional brightness characteristic and PWM output on GPIO2, which the owner physically verified drives a second onboard LED. The red power LED remains independent. Fan values remain RAM-only, and Battery Service is omitted. The code assumes LED brightness increases with PWM duty; physical brightness direction still needs verification.

## Isolated bench fan and battery sketch

`../esp32_firmware/ble_bench_fan_battery/ble_bench_fan_battery.ino` advertises as `ESP32-Bench-Fan` and preserves the custom service UUIDs. The fan request 0..100 maps to 0..100% PWM duty at 5 kHz on GPIO25; the state byte reports commanded duty, **not** measured RPM or airflow. The output starts at 0 on boot. The on/off switch restores the last nonzero command. GPIO2 LED brightness remains as in the owner-tested LED sketch.

VN/GPIO39 measures the nominal 33 kΩ top / 10 kΩ bottom battery divider. The standard Battery Level is a rough 3S LiPo voltage-derived display estimate, linearly clamped between 9.9 V = 0% and 12.6 V = 100%. Those endpoints are display assumptions, not a discharge limit or a safety alarm. Load, cell condition and ADC error can make the estimate wrong. The app labels this bench device's fan value as PWM duty and its battery percentage as estimated. No BMS, airflow sensor, RPM sensor, low-flow alarm or autonomous safety control is implemented.

**Do not upload or operate this sketch until hardware confirms G/D/S wiring, replaces a 100 Ω gate-to-source pull-down with a suitable kΩ value, and checks the 3S pack maximum and divider.** The owner reported 5 kHz PWM for the test fan, but the fan's actual response and MOSFET temperature at 3.3 V GPIO drive are unverified. This is only a bench experiment and must not be worn as a breathing device.
