// GPIO2 onboard LED over BLE. Fan remains virtual; no battery is connected.
// Matches docs/BLE_PROTOCOL.md. No external load is driven.
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
constexpr uint8_t LED_PIN = 2;
constexpr bool LED_ACTIVE_LOW = false;

BLECharacteristic *stateCharacteristic = nullptr;
BLECharacteristic *brightnessCharacteristic = nullptr;
uint8_t virtualLed = 0;
uint8_t virtualFan = 0;
uint8_t brightnessPercent = 0;
uint8_t lastNonzeroBrightness = 100;

void applyBrightness(uint8_t percent) {
  brightnessPercent = percent;
  if (percent > 0) lastNonzeroBrightness = percent;
  virtualLed = percent > 0 ? 1 : 0;
  const uint32_t scaled = (static_cast<uint32_t>(percent) * 255 + 50) / 100;
  ledcWrite(LED_PIN, LED_ACTIVE_LOW ? 255 - scaled : scaled);
  brightnessCharacteristic->setValue(&brightnessPercent, 1);
}

void publishState() {
  const uint8_t state[2] = {virtualLed, virtualFan};
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
      virtualFan = command;
    } else if (characteristic->getUUID().equals(BLEUUID(BRIGHTNESS_UUID))) {
      if (command > 100) {
        brightnessCharacteristic->setValue(&brightnessPercent, 1);
        return;
      }
      applyBrightness(command);
    } else {
      return;
    }
    // Fan value is RAM-only. Only the verified GPIO2 user LED is driven.
    publishState();
    Serial.printf("GPIO2 LED=%u%%, virtual fan=%u%%\n", brightnessPercent, virtualFan);
  }
};

void setup() {
  Serial.begin(115200);
  delay(400);
  Serial.println("ESP32 BLE GPIO2 LED brightness; fan remains virtual");

  if (!ledcAttach(LED_PIN, 5000, 8)) {
    Serial.println("GPIO2 PWM setup failed; BLE not started");
    return;
  }
  ledcWrite(LED_PIN, LED_ACTIVE_LOW ? 255 : 0);

  if (!BLEDevice::init("ESP32-LED")) {
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

  service->start();
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("Advertising ESP32-LED");
}

void loop() {
  delay(1000);
}
