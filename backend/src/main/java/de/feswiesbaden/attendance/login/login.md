# Login und Sitzungen

Dieses Package meldet vorhandene Benutzer aus `staff` an. Spring Security prüft
Passwörter und verwaltet den Zugriff über eine HTTP-Sitzung. Die Vue-Anmeldemaske
gehört zu [Issue #61](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/61).
Benutzeranlage und Klassenrechte gehören nicht zu diesem Package.

## API

Browser, nginx und Spring verwenden dieselben `/api/`-Pfade. nginx erhält
den Präfix bei der Weiterleitung. Alle Browser-Anfragen laufen über HTTPS;
Vite liefert ausschließlich Seiten und HMR hinter nginx.

| Aufruf | Rumpf | Antwort |
| --- | --- | --- |
| `POST /api/login` | JSON mit `username` und `password` | `200` mit Benutzerobjekt |
| `GET /api/me` | keiner | `200` mit Benutzerobjekt oder `401` ohne gültige Sitzung |
| `POST /api/logout` | `{}` | `204`; Sitzung und Cookies werden entfernt |

Login benötigt `Content-Type: application/json`. Parameter wie `charset=utf-8`
sind erlaubt. Beide Felder müssen Strings sein; der Benutzername darf nicht
leer oder ausschließlich Leerraum sein und höchstens 120 Zeichen enthalten.
Das Passwort darf nicht leer sein und höchstens 72 UTF-8-Bytes enthalten
(BCrypt-Grenze). Passwörter werden weder gekürzt noch getrimmt.

Beispielantwort mit ausschließlich erfundenen Daten:

```json
{"name":"Test Lehrkraft","role":"lehrkraft"}
```

`name` enthält Vorname und Nachname aus `staff`. Die Datenbankrolle
`ADMINISTRATOR` wird als `admin`, `LEHRKRAFT` als `lehrkraft` ausgegeben.
Die Ausgabe enthält weder Benutzername, Passwort-Hash noch die Staff-Entity.

| Status | Bedeutung |
| --- | --- |
| `400` | Ungültiges JSON, fehlende/falsche Felder oder überschrittene Eingabegrenze |
| `401` | Anmeldung fehlgeschlagen oder Sitzung fehlt, ist abgelaufen oder verdrängt |
| `403` | CSRF-Prüfung fehlgeschlagen oder Zugriff verboten |
| `415` | Login-Rumpf hat nicht den Medientyp `application/json` |

Ein unbekannter Benutzer und ein falsches Passwort liefern dieselbe Antwort:
`401` mit `{"code":"LOGIN_FAILED"}`. Validierungsfehler geben den Anfragerumpf
nicht zurück. Die API liefert keine Login-Seiten oder Weiterleitungen.
CSRF wird vor der Anmeldung geprüft: Ohne gültigen Token kann ein Login bereits
`403` liefern, bevor Zugangsdaten oder JSON geprüft werden.

## CSRF im Browser

1. Beim Start `GET /api/me` aufrufen. Auch bei `401` setzt Spring einen
   `XSRF-TOKEN`-Cookie, wenn noch keiner vorhanden ist.
   Wurde die Sitzung durch einen weiteren Login verdrängt, liefert dieser
   Aufruf einen frischen Token für die unmittelbare Wiederanmeldung.
2. Vor jedem schreibenden Aufruf den aktuellen Cookie lesen und seinen Wert
   im Header `X-XSRF-TOKEN` mitsenden. Der Cookie allein genügt nicht.
3. Login als JSON senden; das Sitzungscookie mit `credentials: 'include'`
   übertragen. Nach erfolgreichem Login ist der CSRF-Token erneuert.
4. Für weitere Anfragen den Cookie erneut lesen, statt den früheren Token zu
   behalten. Ein alter Header mit dem neuen Cookie wird abgelehnt.
5. Logout als `POST` mit aktuellem CSRF-Header senden. `GET` meldet nicht ab.
   Logout entfernt den CSRF-Cookie. Vor der nächsten Anmeldung `/api/me` aufrufen.

`XSRF-TOKEN` ist JavaScript-lesbar (`HttpOnly=false`) sowie `Secure`,
`SameSite=Strict` und `Path=/`. Das Sitzungscookie bleibt dagegen `HttpOnly`.
Der SPA-Request-Handler unterstützt den unveränderten Cookie-Wert im Header
und behält Springs XOR-Schutz für CSRF-Request-Attribute bei.

## Passwörter und Sitzungen

- `StaffRepository` liest vorhandene Benutzer; `DaoAuthenticationProvider`
  vergleicht das Passwort mit `staff.password_hash`.
- Der bereitgestellte `PasswordEncoder` erzeugt BCrypt-Hashes mit Kostenfaktor
  12. Gespeichert werden 60 Zeichen ohne `{bcrypt}`-Präfix. Es gibt keine
  Standardkonten, Passwortmigration oder Benutzeranlage.
- Das Cookie heißt `SESSION`: `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/`.
  Session-IDs werden ausschließlich über Cookies übertragen.
- Ein erfolgreicher Login wechselt eine vorhandene Session-ID. Ohne bestehende
  Sitzung wird eine neue angelegt. Spring speichert den Security Context darin.
- Nach 30 Minuten ohne Sitzungszugriff verfällt die Sitzung. Erneute Zugriffe
  verlängern den Zeitraum; regelmäßiges Polling kann die Sitzung aktiv halten.
- Pro Benutzer ist eine Sitzung aktiv. Ein neuer erfolgreicher Login verdrängt
  die alte; deren nächster Zugriff liefert `401` und invalidiert sie.
- Der gespeicherte Benutzername dient als Session-Schlüssel. Schreibvarianten
  bei der Anmeldung erzeugen keine zusätzlichen Benutzeridentitäten.
- Prüfung, ID-Wechsel und Registrierung sind gemeinsam synchronisiert, damit
  gleichzeitig eintreffende Logins keine zweite aktive Sitzung hinterlassen.
  Die BCrypt-Prüfung liegt außerhalb der Sperre.
- Logout invalidiert Sitzung und Security Context und löscht `SESSION` mit
  `Path=/`, den Sicherheitsattributen und `Max-Age=0`.

Sitzungen liegen im Arbeitsspeicher einer Backend-Instanz. Ein Backend-Neustart
meldet alle Benutzer ab. Mehrere Backend-Instanzen werden hier nicht unterstützt.

## Grenzen und Anbindung

`GET /actuator/health` bleibt öffentlich. Die übrigen fachlichen Endpunkte sind
bis zur Umsetzung ihrer Berechtigungen gesperrt. Die Rollenanzeige im Frontend
ersetzt keine Backend-Autorisierung.

Browser-API-Anfragen gehen über HTTPS an nginx auf Port `8443`. nginx leitet
`/api/` direkt an das lokale Spring-Backend auf Port `8080` weiter. Dieser
interne Abschnitt verwendet in diesem Entwicklungsschritt weiterhin HTTP;
Cookies bleiben `Secure`. Seiten und HMR kommen über nginx von Vite auf
HTTPS-Port `5173`, mit CA- und Namensprüfung des Upstream-Zertifikats.
Die einzige `compose.yaml` startet MySQL, `tls-init` und nginx. Backend und
Frontend laufen lokal. Vite enthält keinen API-Proxy.
nginx stellt den getrennten JavaFX-mTLS-Zugang auf Port `8444` bereit und
verwendet keine Staff-Anmeldung.

Passwörter und Hashes gehören nie in Logs, URLs, Browser-Storage oder Screenshots.
Zum Testen ausschließlich isolierte Testbenutzer verwenden. Die lokale
Compose-Umgebung startet keine Backend- oder Frontend-Images; dafür den
Backend- und Frontend-Code aus dem Checkout starten.

## Prüfen

Aus dem Repository-Hauptverzeichnis, mit Java 21, Maven und laufendem Docker:

```sh
mvn -pl backend spotless:check
mvn -pl backend -Dtest=Issue22IntegrationTest test
mvn -pl backend verify
```

`Issue22IntegrationTest` verwendet MySQL-Testcontainers und einen echten
HTTP-Server. Geprüft werden beide Rollen, BCrypt, neutrale Fehler, Cookies,
Session-ID-Wechsel, zwei Clients, gleichzeitige Logins, Inaktivität, CSRF und
Logout. Der Ablauf wird intern über HTTP getestet; Cookie-Sicherheitsattribute
werden ausdrücklich geprüft. Eine testseitig verkürzte Session-Laufzeit prüft
den Ablauf, während die Produktionskonfiguration auf 1800 Sekunden geprüft wird.
