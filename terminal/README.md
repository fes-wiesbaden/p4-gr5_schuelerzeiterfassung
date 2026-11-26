# RFID-Terminal (JavaFX)

Desktop-Anwendung, an die ein ESP32 mit RFID-Leser per USB hängt. Sie erfasst
Scans, puffert sie auf der Platte und stellt sie per HTTPS an einen Server zu.

## Starten

**In VS Code:** `src/main/java/de/feswiesbaden/terminal/Launcher.java` öffnen und
oben auf *Run* klicken oder einfach `F5`. Die passende Startkonfiguration liegt
in `.vscode/launch.json`.

> Nicht `TerminalApp.java` starten. Die erbt von `javafx.application.Application`
> und verlangt beim Start einen `--module-path`; ohne den bricht sie mit
> „JavaFX-Runtime-Komponenten fehlen" ab. `Launcher` umgeht das.

**Im Terminal:**

```bash
cp terminal.properties.example terminal.properties
mvn javafx:run
```

Ohne Eintrag bei `serial.port` liest die Anwendung Scans **von der Konsole**
statt vom ESP32. Einfach eine Zeile eintippen und Enter:

```
{"rfidUid":"04A3B2C1","terminalNumber":23102003}
```

Die Oberfläche hat bewusst **keinen Knopf** dafür laut Issue #37 darf sie gar
keine Bedienelemente enthalten.

## Release-Paket starten

Das Terminal-ZIP für Linux x64, Windows x64 oder macOS ARM64 entpacken.
Java 21 für dieselbe Architektur installieren. Im entpackten Ordner
`terminal.properties.example` nach `terminal.properties` kopieren und
Serveradresse, seriellen Port und die Pfade zu den eigenen mTLS-Dateien
eintragen. Das Paket enthält die JavaFX-Bibliotheken.

Linux und macOS:

```bash
java -cp 'attendance-terminal.jar:lib/*' de.feswiesbaden.terminal.Launcher
```

Windows:

```powershell
java -cp "attendance-terminal.jar;lib/*" de.feswiesbaden.terminal.Launcher
```

## Mit echtem ESP32

1. Einmalig die Werkzeuge zum Flashen einrichten (Arch/CachyOS):
   ```bash
   sudo pacman -S --needed arduino-cli
   arduino-cli config init
   arduino-cli config add board_manager.additional_urls \
     https://espressif.github.io/arduino-esp32/package_esp32_index.json
   arduino-cli core update-index
   arduino-cli core install esp32:esp32
   arduino-cli lib install MFRC522
   ```
   Der ESP32-Core belegt rund 1 GB unter `~/.arduino15`.
2. Firmware bauen und aufspielen:
   ```bash
   arduino-cli compile --fqbn esp32:esp32:esp32 firmware/esp32-rfid-reader
   arduino-cli upload -p /dev/ttyUSB0 --fqbn esp32:esp32:esp32 firmware/esp32-rfid-reader
   ```
3. Einmalig Zugriff auf den seriellen Port freischalten:
   ```bash
   sudo usermod -aG uucp $USER   # unter Arch/CachyOS; anderswo: dialout
   ```
   Danach ab- und wieder anmelden.
4. Port herausfinden und eintragen:
   ```bash
   ls /dev/ttyUSB* /dev/ttyACM*
   ```
   ```properties
   serial.port=/dev/ttyUSB0
   ```

Die Firmware gibt je Karte genau eine JSON-Zeile in UTF-8 aus:

```json
{"rfidUid":"04A3B2C1","terminalNumber":23102003}
```

Die Terminalnummer `23102003` steckt fest in der Firmware (`TERMINAL_NUMBER`
im Sketch).
Zeilen, die sich nicht als solches JSON lesen lassen, verwirft die Anwendung
so geht die Startmeldung `# RFID-Leser bereit` nicht als Scan durch.

Ein ACK über das Kabel gibt es nicht: läuft die Anwendung beim Scan nicht, ist
dieser Scan weg. So ist es in #29 festgelegt.

## Selbsttest beim Start

Direkt nach dem Einschalten meldet die Firmware zwei Kommentarzeilen:

```
# RC522 VersionReg 0x92
# RFID-Leser bereit
```

`VersionReg` sagt, ob der Leser überhaupt antwortet:

| Wert | Bedeutung |
|---|---|
| `0x91` | MFRC522 Version 1.0, alles in Ordnung |
| `0x92` | MFRC522 Version 2.0, alles in Ordnung |
| `0x00` oder `0xFF` | Leser antwortet nicht, Verkabelung prüfen |

Bei `0x00` oder `0xFF` steht statt „bereit" die Zeile
`# RFID-Leser antwortet nicht, Verkabelung pruefen`. Ohne diese Meldung sieht
ein falsch verdrahteter Leser genauso aus wie eine Karte, die nicht erkannt
wird — das kostet beim Aufbau viel Sucherei.

## Kartenverhalten

Am Versuchsaufbau gemessen, mit einer MIFARE-Classic-Karte:

| | |
|---|---|
| UID-Format | 8 Zeichen, reines Hex in Grossbuchstaben (4 Byte) |
| Felder je Zeile | genau `rfidUid` und `terminalNumber`, sonst nichts |
| Terminalnummer | Zahl, fest in der Firmware hinterlegt |

Karten mit 7 Byte langer UID, etwa NTAG, liefern entsprechend 14 Zeichen. Die
Anwendung rechnet deshalb nirgends mit einer festen Laenge.

Die Firmware meldet eine aufliegende Karte einmal. Sie erkennt das Entfernen
nach drei Abfragen ohne Kartenantwort. Danach löst dieselbe Karte beim
erneuten Auflegen einen neuen Scan aus.

## Stolperfallen beim Aufbau

**Nach dem Flashen startet das Board nicht von allein.** `arduino-cli upload`
meldet zwar `Hard resetting via RTS pin`, danach hängt der ESP32 aber oft in
einem Zustand, in dem er nichts sendet. Die Sendeleitung liegt dann dauerhaft
auf Low, was am Rechner als endloser Strom aus `0x00` und `0x80` ankommt. Abhilfe:
`EN`-Taste am Board drücken oder das USB-Kabel kurz abziehen.

**Beim Oeffnen des Ports startet die Anwendung das Board neu.** Das Oeffnen
allein wuerde den ESP32 im Reset haengen lassen, er sendet dann gar nichts. Die
Anwendung schickt deshalb selbst einen Resetpuls ueber DTR und RTS. Wer mit
eigenen Werkzeugen am Port mitliest, muss dasselbe tun, sonst wirkt der ESP32
tot, obwohl er in Ordnung ist.

**Die Startmeldung kommt nur ein einziges Mal.** Wer den seriellen Port erst
nach dem Boot öffnet, sieht sie nicht mehr, weil danach nur noch Scans
gesendet werden. Der Port muss also schon offen sein, wenn der ESP32 startet.

**Getestet mit:** ESP32-D0WD-V3 (Revision 3.1, 40 MHz Quarz, 4 MB Flash) am
CP210x-USB-Adapter, RC522 Version 2.0, arduino-cli 1.4.1, Core `esp32:esp32`
3.3.11, Bibliothek `MFRC522` 1.4.12.

## Was passiert bei einem Scan

```
ESP32 ──USB──> ScanSource ──> Puffer (Platte) ──> DeliveryWorker ──HTTPS──> Server
                                   ↑                      │
                                   └── bleibt liegen ─────┘
                                       solange unerreichbar
```

Ein Scan wird **zuerst gepuffert und dann erst gesendet**. Stürzt die Anwendung
direkt nach dem Auflegen der Karte ab, ist der Scan trotzdem sicher.

Eine Buchung, eine bestätigte Ablehnung oder eine bereits verarbeitete Scan-ID
schließt die Zustellung ab. Bei Netzwerkfehlern oder anderen Antworten bleibt
der Scan im Puffer. JavaFX versucht die Zustellung alle fünf Sekunden erneut.

## Zustände der Anzeige

| | |
|---|---|
| ⌾ Karte auflegen | bereit, wartet auf eine Karte |
| ⧗ Wird verarbeitet | Scan ist vorgemerkt und geht gerade raus |
| ⇄ Lokal vorgemerkt | Zustellung wird erneut versucht |
| ✓ Buchung erfolgreich | Backend bestätigt die Buchung mit HTTP 204 |
| ⓘ Scan bereits verarbeitet | Backend kennt die Scan-ID bereits |
| ✕ Nicht gebucht | endgültig abgelehnt, mit öffentlichem Fehlercode |

„Wird verarbeitet" steht mindestens 1 Sekunde, sonst blitzt es nur auf: eine
Absage des Servers kommt oft schon nach gut hundert Millisekunden zurück. Jede
Rückmeldung danach steht 2 Sekunden und die Anzeige springt zurück auf bereit,
damit der nächste Schüler am eigenen Scan sieht, dass etwas passiert, statt auf
die Rückmeldung seines Vorgängers zu schauen. Wie viele Scans wirklich offen
sind, steht dauerhaft unten rechts.

Beim Fehler steht der öffentliche Code dabei, etwa `SCAN_REJECTED`. Der Grund
selbst wird nie angezeigt unbekannte UID und falsche Klasse sehen für den
Schüler gleich aus.

Unten rechts steht, wie viele Scans noch auf Zustellung warten.

Es werden nie ein Name, eine Klasse oder ein Ablehnungsgrund angezeigt.

## Lokales mTLS

Im Repository-Root einmal `docker compose up tls-init` ausführen. Das erzeugt
unter `.local/tls/` die lokale Server-CA, das Client-PKCS#12 für
`terminal-23102003` und den Server-Truststore. Diese Dateien bleiben lokal und
dürfen nie in Git, Logs oder Screenshots erscheinen.

Die Beispielkonfiguration enthält die passenden relativen Pfade und das lokale
Testpasswort. Für den Start muss der Host in `server.url` exakt dem `TLS_HOST`
entsprechen, weil die JVM den Namen beziehungsweise die IP im Serverzertifikat
prüft. Ohne Client-PKCS#12 oder Truststore startet keine Zustellung.

nginx leitet `POST /api/scans` nach erfolgreicher mTLS-Prüfung ans Backend
weiter. Das Backend prüft die Terminal-ID und ihre Raumzuordnung.

## Tests

```bash
mvn test
```
