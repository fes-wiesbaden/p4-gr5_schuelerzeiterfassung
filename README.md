# Anwesenheitserfassung

Lokale Entwicklungsumgebung für die RFID-basierte Anwesenheitserfassung.

## Voraussetzungen

- Docker Engine mit Docker Compose
- Java 21 und Maven 3.8+
- Node.js 22.12+ und npm

## Lokal starten

```bash
cp .env.example .env
docker compose pull
docker compose up -d
```

Unter Linux müssen `HOST_UID` und `HOST_GID` in `.env` der Ausgabe von `id -u` und `id -g` entsprechen. Unter Docker Desktop für Windows bleiben die Standardwerte `1000`.

Die Anwendung ist anschließend unter `https://127.0.0.1:8443/` erreichbar. Der
für JavaFX reservierte mTLS-Zugang liegt getrennt auf Port `8444`. HTTP wird
nicht veröffentlicht.

```bash
docker compose down
```

`docker compose down -v` löscht die lokale MySQL-Datenbank. Das lokale TLS-Verzeichnis bleibt bestehen und muss für einen normalen Neustart nicht gelöscht werden.

## Lokal debuggen

### IntelliJ IDEA

Das Repository-Root als Projekt öffnen. Die Root-`pom.xml` importiert das Maven-Modul `backend` automatisch. IntelliJ fragt gegebenenfalls nach dem Maven-Import; diesen bestätigen und Java 21 als Project SDK wählen.

Der Hybrid-Modus lässt Backend und Vue-Frontend lokal laufen. Docker stellt MySQL und den für JavaFX vorgesehenen mTLS-Proxy bereit.

```bash
docker compose -f compose.dev.yaml up --build -d
cd backend && mvn spring-boot:run
cd frontend && npm run dev
```

Das Backend ist in IntelliJ über die Klasse `AttendanceApplication` debugbar. Der lokale Vite-Server nutzt das erzeugte Serverzertifikat unter `https://<TLS_HOST>:5173/`. Port `8444` ist für die spätere JavaFX-Kommunikation reserviert. `compose.dev.yaml` veröffentlicht MySQL ausschließlich für den lokalen Backend-Debugger.

```bash
docker compose -f compose.dev.yaml down
```

## Lokales Browser-Zertifikat

Beim ersten Start erzeugt `tls-init` die lokale Server-CA unter `.local/tls/ca.crt`. Sie gilt sowohl für den vollständigen Container-Start auf Port `8443` als auch für den lokalen Vite-Debugger auf Port `5173`.

Der Browser vertraut dieser privaten CA nicht automatisch. `ca.crt` deshalb einmal als vertrauenswürdige Stammzertifizierungsstelle für Websites importieren und den Browser neu starten. Anschließend immer exakt den in `TLS_HOST` eingetragenen Host öffnen, zum Beispiel `https://127.0.0.1:8443/` oder `https://127.0.0.1:5173/`. `localhost` ist bei TLS ein anderer Name als `127.0.0.1`.

Port `8444` ist ausschließlich der mTLS-Endpunkt des JavaFX-Terminals und keine
Browser-Oberfläche.

## JavaFX-Terminal mit mTLS

Beim ersten Einrichten erzeugt `tls-init` die lokale Terminalidentität
`terminal-23102003`: Client-Zertifikat, privater Schlüssel, Client-PKCS#12 und
einen Java-Truststore mit der Server-CA. Private Schlüssel und Speicher dürfen
nicht in Git, Logs oder Screenshots erscheinen.

1. `TLS_HOST` in `.env` auf den Host oder die IP des nginx-Proxys setzen.
2. `docker compose up tls-init` ausführen.
3. In `terminal/` die Beispiel-Properties kopieren und den Host in `server.url`
   auf exakt `TLS_HOST` setzen.
4. Das Terminal mit `mvn javafx:run` starten.

JavaFX prüft den Server mit dem lokalen Truststore. nginx prüft das
Clientzertifikat gegen die Terminal-Client-CA. Trust-all und deaktivierte
Hostname-Prüfung sind verboten. Der echte Scan-Endpunkt folgt in Issue #26;
der aktuelle nginx-Testpfad dient nur dem mTLS-Nachweis.

## Prüfungen

```bash
docker compose up tls-init
mvn -pl backend spotless:check test
mvn -pl terminal spotless:check test
cd frontend && npm ci && npm run lint && npm run format:check && npm test && npm run build
docker compose config
docker compose --profile test build tls-test
docker compose --profile test run --rm tls-test
docker compose up --build -d
sh scripts/verify-local-tls.sh
docker compose down
```
