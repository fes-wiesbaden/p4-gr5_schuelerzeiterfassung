# Anwesenheitserfassung

RFID-basierte Anwesenheitserfassung für Schulklassen mit wechselnden
Raumbelegungen. Das Repository enthält Spring-Backend, Vue-Weboberfläche,
JavaFX-Terminal und ESP32-Firmware. Diese Anleitung richtet sich an
Projektentwickler.

[![Qualitätsprüfungen](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/actions/workflows/quality.yml/badge.svg?branch=main)](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/actions/workflows/quality.yml)

## Entwicklungsumgebung

| Komponente | Ausführung | Aufgabe/Zugang |
| --- | --- | --- |
| `tls-init` | Docker, einmalig | Lokale Zertifikate und Truststore erzeugen |
| MySQL 8 | Docker | Datenbank auf `127.0.0.1:3306` |
| nginx | Docker | JavaFX-mTLS auf Port `8444`; Proxy zum lokalen Backend |
| Spring Boot | Lokal, Java 21 | Backend auf Port `8080` |
| Vue/Vite | Lokal, Node.js | Browser über `https://<TLS_HOST>:5173/`; `/api/`-Proxy zum Backend |
| JavaFX | Lokal, Java 21 | RFID-Terminal; [Einrichtung](terminal/README.md) |

Der Browser verwendet Vite. nginx ist für den Terminalzugang bestimmt und
leitet dessen Testanfragen an `host.docker.internal:8080` weiter. Port `8444`
ist keine Browser-Oberfläche.

## Starten

Voraussetzungen: Docker Engine mit Docker Compose, Java 21, Maven 3.8+,
Node.js 22.12+ und npm. Befehle aus dem Repository-Hauptverzeichnis ausführen.

### Konfiguration

Bei der ersten Einrichtung, sofern `.env` noch nicht existiert:

```sh
cp .env.example .env
```

Vor dem Start `.env` prüfen:

| Einstellung | Bedeutung |
| --- | --- |
| `TLS_HOST` | Host/IP in den Zertifikaten; Standard `127.0.0.1` |
| `HOST_UID`, `HOST_GID` | Unter Linux Werte aus `id -u` und `id -g`; unter Docker Desktop für Windows Standard `1000` |
| `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` | Zugangsdaten für die lokale Datenbank |

### Infrastruktur

```sh
docker compose up --build -d
docker compose ps
```

Vor dem Backendstart muss MySQL `healthy` sein. `tls-init` beendet sich nach
der Zertifikatserzeugung; MySQL und nginx laufen weiter.

### Backend und Frontend

In getrennten Terminals starten:

```sh
mvn -pl backend spring-boot:run
```

```sh
npm --prefix frontend ci
npm --prefix frontend run dev
```

Vite leitet `/api/` an `http://127.0.0.1:8080` weiter. Nach dem
[CA-Import](#tls-und-terminal) die Weboberfläche auf `https://<TLS_HOST>:5173/`
öffnen, standardmäßig `https://127.0.0.1:5173/`.

## Lokal debuggen

Für IntelliJ IDEA die Root-`pom.xml` importieren; sie enthält `backend` und
`terminal`. Java 21 als Project SDK verwenden und `AttendanceApplication`
starten oder debuggen.

## Komponenten und Dokumentation

| Verzeichnis | Inhalt | Weiterführend |
| --- | --- | --- |
| `backend/` | Datenmodell, Dienste und Login | [Login und Sitzungen](backend/src/main/java/de/feswiesbaden/attendance/login/login.md) |
| `frontend/` | Vue 3 / TypeScript für Lehrkraft- und Verwaltungsansichten | [Lokaler Start](#backend-und-frontend) |
| `terminal/` | JavaFX, USB-Scans und persistente Zustellwarteschlange | [Terminal-Anleitung](terminal/README.md) |
| `terminal/firmware/` | ESP32-RFID-Leser mit USB-Serial | [ESP32-Einrichtung](terminal/README.md#mit-echtem-esp32) |
| `infra/` | TLS-Erzeugung und nginx | [TLS und Terminal](#tls-und-terminal) |

## TLS und Terminal

`tls-init` erzeugt beim ersten Start die lokale Server-CA `.local/tls/ca.crt`.
Diese im Browser als vertrauenswürdige Stammzertifizierungsstelle für Websites
[importieren](https://chromium.googlesource.com/chromium/src/+/HEAD/docs/linux/cert_management.md "Offizielle Chromium-Anleitung für Linux") und den Browser neu starten. Vite verwendet das erzeugte
Serverzertifikat für HTTPS auf Port `5173`.

Immer exakt `TLS_HOST` öffnen: `localhost` und `127.0.0.1` sind unterschiedliche
Zertifikatsnamen. Das TLS-Verzeichnis bleibt bei einem Neustart erhalten.

Für JavaFX entstehen Clientzertifikat, privater Schlüssel, Client-PKCS#12 und
Java-Truststore unter `.local/tls/`. In `terminal/` die Beispiel-Properties
kopieren, `server.url` auf exakt diesen Host setzen und dort `mvn javafx:run`
starten. Zertifikate lassen sich auch separat erzeugen:

```sh
docker compose up tls-init
```

JavaFX prüft Serverzertifikat und Hostnamen; nginx prüft das Clientzertifikat
gegen die Terminal-Client-CA. Private Schlüssel und Speicher nie in Git,
Logs oder Screenshots aufnehmen. Trust-all und deaktivierte Hostnamenprüfung
sind verboten. Die [Terminal-README](terminal/README.md) beschreibt USB,
Firmware und Konfiguration.

## Prüfungen

Die Backend-Integrationstests verwenden eine eigene MySQL-Datenbank über
Testcontainers und benötigen laufendes Docker.

```sh
mvn -pl backend spotless:check verify
mvn -pl terminal spotless:check test
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

Die Zertifikatserzeugung benötigt kein laufendes Backend oder Frontend:

```sh
docker compose build tls-init
docker compose run --rm --no-deps --entrypoint /usr/local/bin/test-generate-certificates tls-init
```

Für die TLS-Verbindungsprüfung müssen Infrastruktur, lokales Backend und
Frontend laufen:

```sh
sh scripts/verify-local-tls.sh
```

Die CI führt außerdem Checkstyle aus. Git- und PR-Regeln stehen in
[CONTRIBUTING.md](CONTRIBUTING.md); die [GitHub-Issues](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues)
enthalten die Projektanforderungen.

## Beenden

Backend und Frontend mit `Strg+C` beenden, beziehungsweise die Backend-
Anwendung in IntelliJ stoppen. Danach die Infrastruktur beenden:

```sh
docker compose down
```

`docker compose down -v` löscht die lokale MySQL-Datenbank. Die Zertifikate
unter `.local/tls/` bleiben bestehen.
