// RFID-Leser für das JavaFX-Terminal (Issue #29).
//
// Der ESP32 liest eine Karte und schickt pro Scan GENAU EINE JSON-Zeile in
// UTF-8 über USB-Seriell an die JavaFX-Anwendung:
//
//     {"rfidUid":"04A3B2C1","terminalNumber":3}
//
// Kein WLAN, kein NTP, kein TLS. Die gesicherte Verbindung baut allein JavaFX
// zum Server auf. Der ESP32 hängt nur am Kabel.
//
// Es gibt keine Rückmeldung übers Kabel: läuft JavaFX gerade nicht, ist dieser
// Scan weg. So ist es in #29 festgelegt.
//
// Zeilen, die mit "#" beginnen, sind Meldungen für Menschen und werden von
// JavaFX verworfen.
//
// Verkabelung RC522 an ESP32:
//     SDA/SS -> GPIO 5     SCK  -> GPIO 18
//     MOSI   -> GPIO 23    MISO -> GPIO 19
//     RST    -> GPIO 22    3V3 und GND entsprechend
//
// Benötigte Bibliothek: MFRC522 von GithubCommunity.

#include <SPI.h>
#include <MFRC522.h>

// Fest eingebaute Nummer dieses Terminals. Beim Aufspielen anpassen.
const int TERMINAL_NUMBER = 3;

const uint8_t PIN_SS = 5;
const uint8_t PIN_RST = 22;
const long BAUD = 115200;

// Dieselbe Karte soll nicht dauernd melden, solange sie aufliegt.
const unsigned long REPEAT_BLOCK_MS = 1500;

MFRC522 reader(PIN_SS, PIN_RST);

String lastUid = "";
unsigned long lastSeenAt = 0;

void setup() {
  Serial.begin(BAUD);
  while (!Serial) {
    delay(10);
  }

  SPI.begin();
  reader.PCD_Init();

  Serial.println("# RFID-Leser bereit");
}

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
  if (!reader.PICC_IsNewCardPresent() || !reader.PICC_ReadCardSerial()) {
    delay(50);
    return;
  }

  String uid = uidToHex(reader.uid);
  unsigned long jetzt = millis();

  if (uid != lastUid || jetzt - lastSeenAt > REPEAT_BLOCK_MS) {
    // JSON von Hand zusammenbauen. Die UID ist reines Hex, da muss nichts
    // maskiert werden.
    Serial.print("{\"rfidUid\":\"");
    Serial.print(uid);
    Serial.print("\",\"terminalNumber\":");
    Serial.print(TERMINAL_NUMBER);
    Serial.println("}");

    lastUid = uid;
    lastSeenAt = jetzt;
  }

  reader.PICC_HaltA();
  reader.PCD_StopCrypto1();
}
