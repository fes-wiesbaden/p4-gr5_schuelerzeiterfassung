# Git- und Pull-Request-Konventionen

## Branches

Wenn ein Issue existiert, muss der Branch dessen Nummer enthalten.

```text
feat/<issue>-<kurze-beschreibung>
fix/<issue>-<kurze-beschreibung>
```

Beispiele:

```text
feat/16-git-konventionen
fix/42-rfid-scan-validierung
```

Branchwechsel erfolgen nur mit Zustimmung. `git worktree` wird nur auf ausdrückliche Anfrage verwendet.

## Commits

Commit-Nachrichten folgen Conventional Commits und sind auf Deutsch formuliert.

```text
<typ>: <kurze deutsche beschreibung>
```

Zulässige Typen:

```text
feat | fix | refactor | build | ci | chore | docs | style | perf | test
```

Beispiele:

```text
docs: Git-Konventionen ergänzen
fix: doppelte RFID-Scans verhindern
test: Anwesenheitsvalidierung abdecken
```

Commits werden nicht ohne ausdrückliche Anfrage geändert (`git commit --amend`).

## Pull Requests

Vor dem Pull Request:

- den aufgabenspezifischen Diff auf unbeabsichtigte Änderungen prüfen;
- eigenen und generierten Code auf Korrektheit, Sicherheit, Datenschutz, Duplikate und unnötige Komplexität prüfen;
- alle relevanten Prüfungen und Tests erfolgreich ausführen;
- die Akzeptanzkriterien des Issues gegen die Implementierung prüfen;
- keine Geheimnisse, personenbezogenen Daten, lokalen Datenbanken, Debug-Dateien oder temporären Artefakte einschließen;
- bei UI-Änderungen bereinigte Vorher-/Nachher-Bilder beifügen; keine sensiblen Daten, internen Kennungen oder RFID-UIDs hochladen.

Vor dem Merge wird die PR geprüft und bei Qualitätsmängeln überarbeitet oder neu strukturiert. Eine vorhandene PR wird verbessert und anschließend gemergt, statt sie zu schließen und dieselbe Änderung als direkten Commit zu duplizieren.

Commits und Pushes erfolgen nur mit ausdrücklicher Freigabe.

## Qualitätsprüfungen

Das Projekt verwendet System-Maven; ein Maven Wrapper ist nicht eingecheckt. Befehle werden aus dem Repository-Hauptverzeichnis ausgeführt:

```sh
# Backend: Formatierung, Tests und Build
mvn -pl backend spotless:check verify
mvn -pl backend org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check "-Dcheckstyle.config.location=config/checkstyle/checkstyle.xml"

# Terminal: Formatierung, Tests und Build
mvn -pl terminal spotless:check verify
mvn -pl terminal org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check "-Dcheckstyle.config.location=$PWD/backend/config/checkstyle/checkstyle.xml"

# Frontend-Abhängigkeiten und Prüfungen
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build
```

Die Backend-Integrationstests verwenden MySQL 8 über Testcontainers und benötigen Docker; H2 ist kein Ersatz. Checkstyle erfasst Produktions- und Testquellen beider Java-Module. Formatierung bei Bedarf mit `mvn -pl 'backend,terminal' spotless:apply` korrigieren und danach erneut prüfen.

JavaFX-Tests benötigen eine Anzeige. Unter Linux ohne Desktop `xvfb-run -a mvn -pl terminal spotless:check verify` verwenden.

Zusätzlich nach Änderungsumfang prüfen:

- UI: Playwright und lokale, von personenbezogenen Daten bereinigte Screenshots verwenden.
- RFID: USB-Zustellung, Client- und Serverzertifikatsprüfung, gültige HTTPS-Zustellung, Bindung der Terminalidentität und Idempotenz doppelter Scans nachweisen.
- Terminal: erfolgreiche Zustellung, persistierte und wiederholte Scans, neutrale synchrone Erfolgs- und Fehlerrückmeldungen sowie fehlende RFID-UIDs in UI, Logs, Browser-Speichern und Screenshots nachweisen.

## GitHub Actions

Der Workflow [Quality checks](.github/workflows/quality.yml) startet bei Pushes auf alle Branches. Er prüft:

- Backend: Spotless, Checkstyle, Maven-Tests und Build;
- Terminal: Spotless, Checkstyle, JavaFX-Tests unter Xvfb und Build;
- Frontend: ESLint, Prettier, TypeScript-Typecheck, Vitest und Buildprüfung.

Security-Scans sind derzeit nicht Bestandteil der Pipeline.

Frontend und Backend werden ausschließlich lokal gestartet. Die Qualitätspipeline prüft weiterhin die Builds.

Die lokale Compose-Umgebung startet MySQL und den mTLS-Proxy:

```sh
docker compose up --build -d
```

Backend und Frontend laufen lokal. Einrichtung und Start stehen in der [README](README.md#lokal-debuggen).
