# Anwesenheitserfassung

Lokale Entwicklungsumgebung für die RFID-basierte Anwesenheitserfassung.

## Voraussetzungen

- Docker Engine mit Docker Compose
- Java 21 und Maven 3.8+
- Node.js 22.12+ und npm

## IntelliJ IDEA

Das Repository-Root als Projekt öffnen. Die Root-`pom.xml` importiert das Maven-Modul `backend` automatisch. IntelliJ fragt gegebenenfalls nach dem Maven-Import; diesen bestätigen und Java 21 als Project SDK wählen.

## Lokal starten

```bash
cp .env.example .env
docker compose up --build
```

Unter Linux müssen `HOST_UID` und `HOST_GID` in `.env` der Ausgabe von `id -u` und `id -g` entsprechen. Unter Docker Desktop für Windows bleiben die Standardwerte `1000`.

Die Anwendung ist anschließend unter `https://127.0.0.1:8443/terminal/1` erreichbar. Der ESP32-Scan-Endpunkt ist getrennt unter Port `8444` und verlangt mTLS. HTTP wird nicht veröffentlicht. Beim ersten Start erzeugt `tls-init` die lokalen Zertifikate unter `.local/tls/`. Browser zeigen zunächst eine Warnung, bis `ca.crt` als lokale Zertifizierungsstelle importiert wurde.

```bash
docker compose down
```

`docker compose down -v` nicht verwenden, wenn der ESP32 weiterhin derselben CA vertrauen soll: Dadurch wird die lokale MySQL-Datenbank gelöscht. Das TLS-Verzeichnis bleibt zwar bestehen, muss aber ebenfalls nicht gelöscht werden.

## Lokal debuggen

Der Hybrid-Modus lässt Backend und Frontend lokal laufen. Docker stellt nur MySQL und den mTLS-Proxy für den ESP32 bereit.

```bash
docker compose -f compose.dev.yaml up --build -d
cd backend && mvn spring-boot:run
cd frontend && npm run dev
```

Das Backend ist in IntelliJ über die Klasse `AttendanceApplication` debugbar. Der lokale Vite-Server nutzt das erzeugte Serverzertifikat: `https://<TLS_HOST>:5173/terminal/1`. Für den ESP32 bleibt `https://<TLS_HOST>:8444/api/` das mTLS-Ziel. `compose.dev.yaml` veröffentlicht MySQL ausschließlich für den lokalen Backend-Debugger.

```bash
docker compose -f compose.dev.yaml down
```

## ESP32-Test mit mTLS

Beim ersten Einrichten erzeugt `tls-init` einmalig die feste ESP32-Identität: `esp32-client.crt` und `esp32-client.key`. Diese beiden Dateien gehören ausschließlich in die ESP32-Firmware. Der private Schlüssel darf nicht in Git, Logs oder Screenshots erscheinen.

1. Die WLAN-IP des testenden Laptops in `TLS_HOST` in `.env` eintragen.
2. `./.local/tls/ca.crt` als Server-CA sowie `esp32-client.crt` und `esp32-client.key` in die ESP32-Test-Firmware übernehmen.
3. Die HTTPS-URL des ESP32 auf `https://<TLS_HOST>:8444/api/` setzen.

`WiFiClientSecure` erhält die Server-CA mit `setCACert(...)`, das feste Client-Zertifikat mit `setCertificate(...)` und den privaten Schlüssel mit `setPrivateKey(...)`. Dadurch prüft der ESP32 den Server und nginx prüft den ESP32. `setInsecure()` ist verboten.

Beim Wechsel auf einen anderen Laptop: Vor dem Start dessen `.local/tls/esp32-client-ca.crt` mit der öffentlichen Client-CA vom ersten Einrichten ersetzen. Dann dessen WLAN-IP in `.env` setzen, `docker compose up --build` starten und dessen neue `ca.crt` sowie die Ziel-URL in der ESP32-Firmware aktualisieren. Das feste ESP32-Client-Zertifikat und sein privater Schlüssel bleiben unverändert.

## Prüfungen

```bash
cd backend && mvn spotless:check test
cd frontend && npm ci && npm run lint && npm run format:check && npm test && npm run build
docker compose config
docker compose --profile test build tls-test
docker compose --profile test run --rm tls-test
docker compose up --build -d
sh scripts/verify-local-tls.sh
docker compose down
```
