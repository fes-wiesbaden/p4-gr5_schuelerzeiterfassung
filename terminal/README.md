# RFID-Terminal (JavaFX-Prototyp)

Desktop-Anwendung, an die ein ESP32 mit RFID-Leser per USB hängt. Sie erfasst
Scans, puffert sie auf der Platte und stellt sie per HTTPS an einen Server zu.

Lokaler Versuchsaufbau, gehört nicht ins GitHub-Projekt.

## Starten

**In VS Code:** `src/main/java/de/feswiesbaden/terminal/Launcher.java` öffnen und
oben auf *Run* klicken — oder einfach `F5`. Die passende Startkonfiguration liegt
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
{"rfidUid":"04A3B2C1","terminalNumber":3}
```

Die Oberfläche hat bewusst **keinen Knopf** dafür — laut Issue #37 darf sie gar
keine Bedienelemente enthalten.

## Mit echtem ESP32

1. `firmware/esp32-rfid-reader/esp32-rfid-reader.ino` auf den ESP32 spielen
   (Bibliothek `MFRC522` von GithubCommunity).
2. Einmalig Zugriff auf den seriellen Port freischalten:
   ```bash
   sudo usermod -aG uucp $USER   # unter Arch/CachyOS; anderswo: dialout
   ```
   Danach ab- und wieder anmelden.
3. Port herausfinden und eintragen:
   ```bash
   ls /dev/ttyUSB* /dev/ttyACM*
   ```
   ```properties
   serial.port=/dev/ttyUSB0
   ```

Die Firmware gibt je Karte genau eine JSON-Zeile in UTF-8 aus:

```json
{"rfidUid":"04A3B2C1","terminalNumber":3}
```

Die Terminalnummer steckt fest in der Firmware (`TERMINAL_NUMBER` im Sketch).
Zeilen, die sich nicht als solches JSON lesen lassen, verwirft die Anwendung —
so geht die Startmeldung `# RFID-Leser bereit` nicht als Scan durch.

Ein ACK über das Kabel gibt es nicht: läuft die Anwendung beim Scan nicht, ist
dieser Scan weg. So ist es in #29 festgelegt.

## Was passiert bei einem Scan

```
ESP32 ──USB──> ScanSource ──> Puffer (Platte) ──> DeliveryWorker ──HTTPS──> Server
                                   ↑                      │
                                   └── bleibt liegen ─────┘
                                       solange unerreichbar
```

Ein Scan wird **zuerst gepuffert und dann erst gesendet**. Stürzt die Anwendung
direkt nach dem Auflegen der Karte ab, ist der Scan trotzdem sicher.

Aus dem Puffer verschwindet er erst, wenn der Server geantwortet hat — egal ob
angenommen oder abgelehnt. Nur wenn der Server gar nicht erreichbar ist, bleibt
er liegen und wird alle 5 Sekunden erneut versucht.

## Zustände der Anzeige

| | |
|---|---|
| ⌾ Karte auflegen | bereit, wartet auf eine Karte |
| ⧗ Wird verarbeitet | Scan ist vorgemerkt und geht gerade raus |
| ⇄ Lokal vorgemerkt | Server nicht erreichbar, wird nachgereicht |
| ✓ Buchung erfolgreich | Server hat mit HTTP 200 geantwortet |
| ✕ Nicht gebucht | endgültig abgelehnt, mit öffentlichem Fehlercode |

Erfolg, Fehler und Vormerkung stehen 3 Sekunden, dann springt die Anzeige zurück
auf bereit. So sieht der nächste Schüler am eigenen Scan, dass etwas passiert,
statt auf die Rückmeldung seines Vorgängers zu schauen. Nur die Verarbeitung
bleibt stehen, weil dort die Antwort noch aussteht. Wie viele Scans wirklich
offen sind, steht dauerhaft unten rechts.

Beim Fehler steht der öffentliche Code dabei, etwa `SCAN_REJECTED`. Der Grund
selbst wird nie angezeigt — unbekannte UID und falsche Klasse sehen für den
Schüler gleich aus.

Unten rechts steht, wie viele Scans noch auf Zustellung warten.

Wie im Web-Terminal steht dort nie ein Name, eine Klasse oder ein Ablehnungsgrund.

## Tests

```bash
mvn test
```
