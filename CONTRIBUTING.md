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
- alle relevanten Prüfungen und Tests erfolgreich ausführen;
- die Akzeptanzkriterien des Issues gegen die Implementierung prüfen;
- keine Geheimnisse, personenbezogenen Daten, lokalen Datenbanken, Debug-Dateien oder temporären Artefakte einschließen;
- bei UI-Änderungen bereinigte Vorher-/Nachher-Bilder beifügen; unsichere Bilder nicht hochladen.

Vor dem Merge wird die PR geprüft und bei Qualitätsmängeln überarbeitet oder neu strukturiert. Eine vorhandene PR wird verbessert und anschließend gemergt, statt sie zu schließen und dieselbe Änderung als direkten Commit zu duplizieren.

Für Issue- und PR-Arbeit wird die GitHub CLI verwendet. Pushes erfolgen nur mit ausdrücklicher Freigabe.

## Qualitätsprüfungen

Das Projekt verwendet System-Maven; ein Maven Wrapper ist nicht eingecheckt. Befehle werden aus dem Repository-Hauptverzeichnis ausgeführt:

```sh
# Backend-Formatierung und Verifikation
mvn spotless:check
mvn spotless:apply
mvn -pl backend test
mvn -pl backend verify

# Frontend-Abhängigkeiten und Prüfungen
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend test
npm --prefix frontend run build
```

Die Backend-Integrationstests verwenden MySQL über Testcontainers und benötigen Docker. Nach `spotless:apply` erneut `mvn spotless:check` ausführen. GitHub Actions soll dieselben Befehle verwenden.

## GitHub Actions

Der Workflow `Quality checks` startet bei Pushes und Pull Requests. Er prüft:

- Backend: Spotless, Checkstyle, Maven-Tests und Build;
- Frontend: ESLint, Prettier, TypeScript-Typecheck, Vitest und Produktionsbuild.

Security-Scans sind derzeit nicht Bestandteil der Pipeline.

Der Workflow `Publish Docker images` startet bei Pull Requests und Pushes auf `main`. Bei Pull Requests werden Backend- und Frontend-Images nur gebaut. Nach einem erfolgreichen Push auf `main` werden beide Images in die GitHub Container Registry veröffentlicht:

- `ghcr.io/fes-wiesbaden/p4-gr5_schuelerzeiterfassung-backend`;
- `ghcr.io/fes-wiesbaden/p4-gr5_schuelerzeiterfassung-frontend`.

Jedes Image erhält den Commit-SHA und `latest`. Fehlgeschlagene Builds veröffentlichen keine Images. Die Infrastruktur-Images für MySQL und nginx werden nicht in GHCR veröffentlicht; TLS-Init wird lokal gebaut.

Für den Containerstart werden die veröffentlichten Images verwendet:

```sh
docker compose pull
docker compose up -d
```

Bei privaten GHCR-Paketen vorher mit `docker login ghcr.io` anmelden. Lokale Entwicklung mit laufendem Backend und Frontend erfolgt weiterhin über `compose.dev.yaml`.
