# GPIO2 LED probe

The pictured board resembles a 30-pin ESP32 DevKit variant. Its red light appears to be a USB power indicator. A separate user LED may be connected to GPIO2 (`D2`), but this has not been verified for this particular board.

This diagnostic repeatedly fades PWM on GPIO2. It does not use Bluetooth or connect to the Android app. Uploading it replaces the current BLE connection-test sketch; keep that sketch file so it can be restored. Select **ESP32 Dev Module** and the board's current COM port in Arduino IDE. Press Upload only if replacing the current sketch is acceptable. Watch for a **second LED** that brightens and dims. The steady red power LED is not expected to change.

If a second LED fades, GPIO2 is a viable candidate for the app's LED brightness feature. If no LED changes, stop here: the board may have only a power LED or use a different GPIO. Do not assume GPIO2 controls the red power LED. Report the observed LED color/location before changing the app protocol or BLE firmware.
