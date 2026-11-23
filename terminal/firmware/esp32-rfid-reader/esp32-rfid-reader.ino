// RFID-Leser für das JavaFX-Terminal (Issue #29).
//
// Der ESP32 liest die Kartenkennung und sendet je ausgegebenem Scan eine JSON-Zeile in
// UTF-8 über USB-Seriell an die JavaFX-Anwendung:
//
//     {"rfidUid":"04A3B2C1","terminalNumber":23102003}
//
// Kein WLAN, kein NTP, kein TLS. Die gesicherte Verbindung baut allein JavaFX
// zum Server auf. Der ESP32 hängt nur am Kabel.
//
// JavaFX bestätigt den Empfang nicht über das Kabel. Der ESP32 speichert keine
// offenen Scans und wiederholt keine unbestätigten Übertragungen. Ohne laufenden
// JavaFX-Leser ist deshalb keine spätere Zustellung gewährleistet.
//
// Zeilen, die mit "#" beginnen, sind Meldungen für Menschen und werden von
// JavaFX verworfen.
//
// Verkabelung RC522 an ESP32:
//     SDA/SS -> GPIO 5     SCK  -> GPIO 18
//     MOSI   -> GPIO 23    MISO -> GPIO 19
//     3V3 und GND entsprechend. Reset erfolgt per SPI, ohne ESP32-GPIO.
//
// Benötigte Bibliothek: MFRC522 von GithubCommunity

#include <SPI.h>
#include <MFRC522.h>

// Fest eingebaute Nummer dieses Terminals. Beim Aufspielen anpassen
const int TERMINAL_NUMBER = 23102003;

const uint8_t PIN_SS = 5;
const long BAUD = 115200;

const unsigned long POLL_INTERVAL_MS = 50;
const uint8_t EMPTY_POLLS_TO_REARM = 3;

MFRC522 reader(PIN_SS, MFRC522::UNUSED_PIN);

String lastUid = "";
uint8_t emptyPolls = 0;

void setup() {
  Serial.begin(BAUD);
  while (!Serial) {
    delay(10);
  }

  SPI.begin();
  reader.PCD_Init();

  // Liest das Versionsregister als Verbindungskontrolle, nicht als vollständigen Selbsttest.
  // 0x00 und 0xFF lösen den Hinweis auf eine fehlende Leserantwort aus. Alle anderen
  // Werte werden als Bereitschaft gemeldet. Eine Verkabelungsprüfung erfolgt nicht automatisch
  byte version = reader.PCD_ReadRegister(MFRC522::VersionReg);

  Serial.print("# RC522 VersionReg 0x");
  Serial.println(version, HEX);

  if (version == 0x00 || version == 0xFF) {
    Serial.println("# RFID-Leser antwortet nicht, Verkabelung pruefen");
  } else {
    Serial.println("# RFID-Leser bereit");
  }
}

// Wandelt jedes UID-Byte in genau zwei Hexzeichen um und verwendet Großbuchstaben
String uidToHex(MFRC522::Uid uid) {
  String hex = "";
  for (byte i = 0; i < uid.size; i++) {
    if (uid.uidByte[i] < 0x10) {
      hex += "0";
    }
    hex += String(uid.uidByte[i], HEX);
  }
  hex.toUpperCase();
  return hex;
}

void loop() {
  delay(POLL_INTERVAL_MS);

  // WUPA erreicht auch Karten, die nach dem letzten Lesen im HALT-Zustand sind.
  byte atqa[2];
  byte atqaSize = sizeof(atqa);
  auto status = reader.PICC_WakeupA(atqa, &atqaSize);
  if (status == MFRC522::STATUS_TIMEOUT) {
    // Ein einzelner Lesefehler soll keine zweite Meldung für dieselbe Karte auslösen.
    if (!lastUid.isEmpty() && ++emptyPolls >= EMPTY_POLLS_TO_REARM) {
      lastUid = "";
    }
    return;
  }
  emptyPolls = 0;
  if ((status != MFRC522::STATUS_OK && status != MFRC522::STATUS_COLLISION)
      || !reader.PICC_ReadCardSerial()) {
    return;
  }

  String uid = uidToHex(reader.uid);

  if (uid != lastUid) {
    // JSON von Hand zusammenbauen. Die UID ist reines Hex, da muss nichts
    // maskiert werden
    Serial.print("{\"rfidUid\":\"");
    Serial.print(uid);
    Serial.print("\",\"terminalNumber\":");
    Serial.print(TERMINAL_NUMBER);
    Serial.println("}");

    lastUid = uid;
  }

  reader.PICC_HaltA();
  reader.PCD_StopCrypto1();
}
