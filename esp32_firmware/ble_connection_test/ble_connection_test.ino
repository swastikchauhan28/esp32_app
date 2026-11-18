// BLE transport test only. No GPIO, fan, lamp or battery is controlled.
// Matches docs/BLE_PROTOCOL.md so the Android app can connect and read state.
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

BLECharacteristic *stateCharacteristic = nullptr;
uint8_t virtualLed = 0;
uint8_t virtualFan = 0;

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
      virtualLed = command;
    } else if (characteristic->getUUID().equals(BLEUUID(FAN_UUID))) {
      if (command > 100) return;
      virtualFan = command;
    } else {
      return;
    }
    // These values exist only in RAM. No output pin is touched.
    publishState();
    Serial.printf("Virtual state: LED=%u fan=%u%%\n", virtualLed, virtualFan);
  }
};

void setup() {
  Serial.begin(115200);
  delay(400);
  Serial.println("ESP32 BLE connection test; no GPIO output");

  if (!BLEDevice::init("ESP32-Control-Test")) {
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
  led->setAccessPermissions(ESP_GATT_PERM_WRITE_ENC_MITM);
  fan->setAccessPermissions(ESP_GATT_PERM_WRITE_ENC_MITM);
  static TestWriteCallbacks writeCallbacks;
  led->setCallbacks(&writeCallbacks);
  fan->setCallbacks(&writeCallbacks);

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
  Serial.println("Advertising ESP32-Control-Test");
}

void loop() {
  delay(1000);
}
