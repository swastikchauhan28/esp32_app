// Diagnostic only: checks whether this board has an LED connected to GPIO2.
// No fan, battery, external load or Bluetooth is used by this sketch.
#include <Arduino.h>

constexpr uint8_t kCandidateLedPin = 2;

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("GPIO2 onboard LED brightness probe");
  if (!ledcAttach(kCandidateLedPin, 5000, 8)) {
    Serial.println("Could not attach PWM to GPIO2");
  }
}

void loop() {
  for (int duty = 0; duty <= 255; duty += 5) {
    ledcWrite(kCandidateLedPin, duty);
    delay(25);
  }
  for (int duty = 255; duty >= 0; duty -= 5) {
    ledcWrite(kCandidateLedPin, duty);
    delay(25);
  }
  delay(800);
}
