// Bench only: GPIO25 5 kHz fan PWM, GPIO39/VN 3S LiPo divider, GPIO2 LED.
// Never use this sketch to supply breathing air. Wiring must be checked before upload.
// Hardware gate: replace a 100 ohm gate-to-source pull-down with ~10 kohm;
// confirm G/D/S wiring, fan PWM response, and MOSFET temperature before use.
#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <BLESecurity.h>

constexpr char SERVICE_UUID[] = "e77c0000-5a4f-4b0b-9f74-3a3b8d0c1000";
constexpr char LED_UUID[] = "e77c0001-5a4f-4b0b-9f74-3a3b8d0c1000";
constexpr char FAN_UUID[] = "e77c0002-5a4f-4b0b-9f74-3a3b8d0c1000";
constexpr char STATE_UUID[] = "e77c0003-5a4f-4b0b-9f74-3a3b8d0c1000";
constexpr char BRIGHTNESS_UUID[] = "e77c0004-5a4f-4b0b-9f74-3a3b8d0c1000";
constexpr char BATTERY_SERVICE_UUID[] = "0000180f-0000-1000-8000-00805f9b34fb";
constexpr char BATTERY_LEVEL_UUID[] = "00002a19-0000-1000-8000-00805f9b34fb";
constexpr uint8_t LED_PIN = 2;
constexpr uint8_t FAN_PIN = 25;
constexpr uint8_t BATTERY_PIN = 39; // VN, ADC1 channel 3.
constexpr bool LED_ACTIVE_LOW = false;
constexpr uint32_t DIVIDER_TOP_OHMS = 33000;
constexpr uint32_t DIVIDER_BOTTOM_OHMS = 10000;
// Bench display scale only. A voltage divider is not a battery fuel gauge.
constexpr int32_t DISPLAY_EMPTY_MV = 9900;
constexpr int32_t DISPLAY_FULL_MV = 12600;

BLECharacteristic *stateCharacteristic = nullptr;
BLECharacteristic *brightnessCharacteristic = nullptr;
BLECharacteristic *batteryCharacteristic = nullptr;
uint8_t virtualLed = 0;
uint8_t fanPercent = 0; // Commanded PWM duty, not verified airflow or RPM.
uint8_t brightnessPercent = 0;
uint8_t lastNonzeroBrightness = 100;
uint8_t batteryPercent = 0;

uint32_t readBatteryMillivolts() {
  uint32_t sum = 0;
  for (uint8_t i = 0; i < 16; ++i) {
    sum += analogReadMilliVolts(BATTERY_PIN);
    delay(2);
  }
  const uint32_t adcMv = (sum + 8) / 16;
  return (adcMv * (DIVIDER_TOP_OHMS + DIVIDER_BOTTOM_OHMS) + DIVIDER_BOTTOM_OHMS / 2) / DIVIDER_BOTTOM_OHMS;
}

void publishBattery() {
  const uint32_t mv = readBatteryMillivolts();
  const int32_t scaled = (static_cast<int32_t>(mv) - DISPLAY_EMPTY_MV) * 100 /
                         (DISPLAY_FULL_MV - DISPLAY_EMPTY_MV);
  batteryPercent = static_cast<uint8_t>(constrain(scaled, 0, 100));
  batteryCharacteristic->setValue(&batteryPercent, 1);
  batteryCharacteristic->notify();
  Serial.printf("Battery=%lu mV, display estimate=%u%%\n", static_cast<unsigned long>(mv), batteryPercent);
}

void applyBrightness(uint8_t percent) {
  brightnessPercent = percent;
  if (percent > 0) lastNonzeroBrightness = percent;
  virtualLed = percent > 0 ? 1 : 0;
  const uint32_t scaled = (static_cast<uint32_t>(percent) * 255 + 50) / 100;
  ledcWrite(LED_PIN, LED_ACTIVE_LOW ? 255 - scaled : scaled);
  brightnessCharacteristic->setValue(&brightnessPercent, 1);
}

void publishState() {
  const uint8_t state[2] = {virtualLed, fanPercent};
  stateCharacteristic->setValue(state, sizeof(state));
  stateCharacteristic->notify();
}

class TestWriteCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    const String value = characteristic->getValue();
    if (value.length() != 1) {
      Serial.println("Ignoring invalid control length");
      return;
    }
    const uint8_t command = static_cast<uint8_t>(value[0]);
    if (characteristic->getUUID().equals(BLEUUID(LED_UUID))) {
      if (command > 1) return;
      applyBrightness(command == 0 ? 0 : lastNonzeroBrightness);
    } else if (characteristic->getUUID().equals(BLEUUID(FAN_UUID))) {
      if (command > 100) return;
      // PWM duty is a bench command, not measured fan speed.
      const uint32_t duty = (static_cast<uint32_t>(command) * 255 + 50) / 100;
      if (!ledcWrite(FAN_PIN, duty)) return;
      fanPercent = command;
    } else if (characteristic->getUUID().equals(BLEUUID(BRIGHTNESS_UUID))) {
      if (command > 100) {
        brightnessCharacteristic->setValue(&brightnessPercent, 1);
        return;
      }
      applyBrightness(command);
    } else {
      return;
    }
    publishState();
    Serial.printf("GPIO2 LED=%u%%, bench fan PWM=%u%%\n", brightnessPercent, fanPercent);
  }
};

void setup() {
  Serial.begin(115200);
  delay(400);
  Serial.println("ESP32 BLE bench fan PWM and battery estimate");

  pinMode(FAN_PIN, OUTPUT);
  digitalWrite(FAN_PIN, LOW); // External gate-to-source pull-down is needed during reset.
  if (!ledcAttach(FAN_PIN, 5000, 8) || !ledcWrite(FAN_PIN, 0)) {
    Serial.println("GPIO25 PWM setup failed; BLE not started");
    return;
  }
  analogSetPinAttenuation(BATTERY_PIN, ADC_11db);

  if (!ledcAttach(LED_PIN, 5000, 8)) {
    Serial.println("GPIO2 PWM setup failed; BLE not started");
    return;
  }
  ledcWrite(LED_PIN, LED_ACTIVE_LOW ? 255 : 0);

  if (!BLEDevice::init("ESP32-Bench-Fan")) {
    Serial.println("BLE initialization failed");
    return;
  }

  BLESecurity security;
  const uint32_t passkey = security.setPassKey(false);
  security.setCapability(ESP_IO_CAP_OUT);
  security.setAuthenticationMode(true, true, true); // Bond, MITM, Secure Connections.
  Serial.printf("Phone pairing code: %06lu\n", static_cast<unsigned long>(passkey));

  BLEServer *server = BLEDevice::createServer();
  server->advertiseOnDisconnect(true);
  BLEService *service = server->createService(SERVICE_UUID);

  const uint32_t writeProperties = BLECharacteristic::PROPERTY_WRITE |
                                   BLECharacteristic::PROPERTY_WRITE_AUTHEN;
  BLECharacteristic *led = service->createCharacteristic(LED_UUID, writeProperties);
  BLECharacteristic *fan = service->createCharacteristic(FAN_UUID, writeProperties);
  const uint32_t brightnessProperties = BLECharacteristic::PROPERTY_READ |
                                        BLECharacteristic::PROPERTY_WRITE |
                                        BLECharacteristic::PROPERTY_READ_AUTHEN |
                                        BLECharacteristic::PROPERTY_WRITE_AUTHEN;
  brightnessCharacteristic = service->createCharacteristic(BRIGHTNESS_UUID, brightnessProperties);
  led->setAccessPermissions(ESP_GATT_PERM_WRITE_ENC_MITM);
  fan->setAccessPermissions(ESP_GATT_PERM_WRITE_ENC_MITM);
  brightnessCharacteristic->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM | ESP_GATT_PERM_WRITE_ENC_MITM);
  brightnessCharacteristic->setValue(&brightnessPercent, 1);
  static TestWriteCallbacks writeCallbacks;
  led->setCallbacks(&writeCallbacks);
  fan->setCallbacks(&writeCallbacks);
  brightnessCharacteristic->setCallbacks(&writeCallbacks);

  const uint32_t stateProperties = BLECharacteristic::PROPERTY_READ |
                                   BLECharacteristic::PROPERTY_NOTIFY |
                                   BLECharacteristic::PROPERTY_READ_AUTHEN;
  stateCharacteristic = service->createCharacteristic(STATE_UUID, stateProperties);
  stateCharacteristic->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM);
#if defined(CONFIG_BLUEDROID_ENABLED)
  stateCharacteristic->addDescriptor(new BLE2902());
#endif
  const uint8_t initialState[2] = {0, 0};
  stateCharacteristic->setValue(initialState, sizeof(initialState));

  BLEService *batteryService = server->createService(BATTERY_SERVICE_UUID);
  const uint32_t batteryProperties = BLECharacteristic::PROPERTY_READ |
                                     BLECharacteristic::PROPERTY_NOTIFY |
                                     BLECharacteristic::PROPERTY_READ_AUTHEN;
  batteryCharacteristic = batteryService->createCharacteristic(BATTERY_LEVEL_UUID, batteryProperties);
  batteryCharacteristic->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM);
#if defined(CONFIG_BLUEDROID_ENABLED)
  batteryCharacteristic->addDescriptor(new BLE2902());
#endif
  publishBattery();

  service->start();
  batteryService->start();
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("Advertising ESP32-Bench-Fan");
}

void loop() {
  delay(5000);
  if (batteryCharacteristic != nullptr) publishBattery();
}
