# Anwesenheitserfassung

Lokale Entwicklungsumgebung für die RFID-basierte Anwesenheitserfassung.

## Voraussetzungen

- Docker Engine mit Docker Compose
- Java 21 und Maven 3.8+
- Node.js 22.12+ und npm

## Lokal starten

```bash
cp .env.example .env
docker compose up --build
```

Die Anwendung ist anschließend unter `https://127.0.0.1:8443/terminal/1` erreichbar. HTTP wird nicht veröffentlicht. Beim ersten Start erzeugt `tls-init` eine lokale CA und ein Serverzertifikat unter `.local/tls/`. Browser zeigen zunächst eine Warnung, bis `ca.crt` als lokale Zertifizierungsstelle importiert wurde.

```bash
docker compose down
```

`docker compose down -v` nicht verwenden, wenn der ESP32 weiterhin derselben CA vertrauen soll: Dadurch wird die lokale MySQL-Datenbank gelöscht. Das TLS-Verzeichnis bleibt zwar bestehen, muss aber ebenfalls nicht gelöscht werden.

## ESP32-Test mit HTTPS

1. Die WLAN-IP des testenden Laptops in `TLS_HOST` in `.env` eintragen.
2. Bei einer geänderten IP das Zertifikat neu erzeugen und nginx neu laden: `docker compose run --rm tls-init && docker compose restart nginx`.
3. `./.local/tls/ca.crt` als CA-Zertifikat in die ESP32-Test-Firmware übernehmen.
4. Die HTTPS-URL des ESP32 auf `https://<TLS_HOST>:8443/api/` setzen.

`WiFiClientSecure` muss dieses CA-Zertifikat mit `setCACert(...)` erhalten. Es prüft damit, ob das Serverzertifikat von der bekannten CA stammt und für die konfigurierte IP ausgestellt wurde. `setInsecure()` ist verboten.

Beim Wechsel auf einen anderen Laptop: dessen WLAN-IP in dessen `.env` setzen, dessen lokale CA in die ESP32-Test-Firmware übernehmen und die Ziel-URL aktualisieren. Der ESP32 sendet immer nur an den gerade konfigurierten Laptop.

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
