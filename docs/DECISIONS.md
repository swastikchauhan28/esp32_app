# Decisions

## 2026-11-16 — BLE control protocol (draft before implementation)

Context: The starter Android app has no device protocol. The owner chose Bluetooth Low Energy. ESP32 firmware, pin assignments, motor driver, battery chemistry and measurement circuit are not yet provided.

Alternatives: Bluetooth Classic serial commands; one combined BLE command characteristic; separate BLE characteristics for each control. Chosen: separate one byte LED and fan control characteristics, a two byte state characteristic, and the standard Battery Service. This keeps the Android interface small and makes displayed state come from the ESP32 rather than assuming a write operated physical hardware.

Cost/risk: The ESP32 firmware must implement the UUIDs and byte format in `BLE_PROTOCOL.md`. A device advertising the UUID can be impersonated; production firmware must require LE Secure Connections bonding for control and state access. Battery percentage accuracy depends on the device's sensing and calibration. Revisit when firmware or hardware details are supplied.

AI model/version: unavailable to project records.

## 2026-11-16 — Restore starter build compatibility

Context: The baseline compile failed because Core 1.19.1 and Lifecycle 2.11.0 require SDK 37/AGP 9.1 while this project uses SDK 36/AGP 8.13.2. Alternatives: upgrade the SDK/plugin, remove the dependencies, or use compatible library versions. Chosen: set Core to 1.17.0 and Lifecycle to 2.9.4. The owner explicitly approved these two pins on 2026-11-16. Cost/risk: older library versions; revisit when the project upgrades its SDK and Android Gradle plugin. No new dependency is introduced.

## 2026-11-18 — Connection-only ESP32 firmware

Context: The owner has a 30-pin ESP32 development board on COM14 and asked to connect it to the Android app over BLE. No fan or battery is connected, and no output driver or bulb details are available. Alternatives: wait for the full hardware design, advertise only a test service (which the app would reject), or implement the full BLE contract with virtual values. Chosen: implement the required custom BLE characteristics with RAM-only LED/fan state, no Battery Service and no GPIO writes. This gives a truthful Bluetooth integration test while preserving the app protocol. A random six-digit passkey printed to Serial Monitor enables authenticated LE Secure Connections bonding on control/state characteristics.

Cost/risk: The app controls will change virtual values even though no physical output exists; this must remain clearly labeled as a connection test. Uploading replaces whatever firmware is currently on the board and requires the owner's separate authorization/action. Revisit when driver, bulb, fan and battery hardware are known. AI model/version: unavailable to project records.

## 2026-11-18 — Verify onboard LED before brightness integration

Context: The owner wants the built-in LED controlled and dimmed from the phone. The photo shows a red illuminated LED near the USB circuitry, likely a power indicator. The 30-pin board may also have a GPIO2 user LED, but this exact board's schematic is unavailable. Alternatives: assume GPIO2 and change app/firmware immediately; require a board schematic; or prepare a small observable GPIO2 PWM probe. Chosen: prepare a separate probe sketch and compile it without uploading. This tests the physical assumption before changing the BLE protocol and UI. Cost/risk: uploading the probe replaces the current BLE test firmware until reuploaded; GPIO2 is a boot strapping pin, so it must not be externally pulled high during flashing. Revisit after the owner reports whether a second LED fades. AI model/version: unavailable to project records.

## 2026-11-18 — GPIO2 LED brightness extension (draft before implementation)

Context: The owner reported an LED faded with the GPIO2 PWM probe and explicitly asked for on/off and brightness from the app. The owner has not yet identified which colored LED faded. Existing BLE state is two bytes and older Android APKs expect that shape. Alternatives: repurpose the old LED on/off byte as a 0–100 brightness value; add a third state byte; or add an optional brightness characteristic. Chosen: add an optional one-byte brightness characteristic with Read and Write, keep the two-byte state and old LED on/off characteristic unchanged, and build a separate GPIO2 firmware sketch. The Android app will show a brightness slider only when the new characteristic is present. This keeps the original connection-test sketch and old app behavior available as recovery paths.

Cost/risk: The new firmware drives GPIO2 with PWM after startup; the exact LED color and active polarity need physical verification. The fan remains RAM-only and battery remains unavailable. App and firmware must both be updated for brightness, though an older app should retain on/off. No automatic upload or physical hardware operation is authorized. Revisit after the owner confirms the LED color, app pairing and brightness direction on the board. AI model/version: unavailable to project records.
