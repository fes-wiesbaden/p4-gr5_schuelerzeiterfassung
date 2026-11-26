# Anwesenheitserfassung

<!-- impeccable:product-schema 1 -->

## Platform

web

## Nutzerinnen und Nutzer

- Lehrkräfte prüfen die Anwesenheit und Fehlminuten ihrer zugeordneten Klassen.
- Die Verwaltung betreut Stammdaten, Räume, Terminals und Planung.
- Schülerinnen und Schüler erfassen ihre Anwesenheit mit ihrer RFID-Karte am JavaFX-Terminal.

## Zweck

Die Anwendung erfasst die Anwesenheit von Schulklassen anhand der RFID-Scans und der Unterrichtsplanung. Lehrkräfte sehen den aktuellen Stand und können Fehlzeiten nachvollziehen.

## Überarbeitung

Anmeldung und Live-Anwesenheit sind die ersten beiden Ansichten der Überarbeitung. Ihr gemeinsames Design wird anschließend auf die weiteren Webseiten und die JavaFX-Anzeige übertragen. Luma dient als visuelle Referenz. „Karte auflegen“ ist die zentrale Aufforderung am Terminal.

Die Live-Anwesenheit zeigt für eine ausgewählte Klasse und einen ausgewählten Tag den Tagesstatus, ganze unentschuldigte Fehltage und teilweise Fehlzeiten. Ein Klick auf einen Namen öffnet das Profil samt Wochenhistorie und Korrekturen.

## Chat-Assistent

Im angemeldeten Webbereich öffnet eine Schaltfläche unten rechts den Assistenten. Klassenlotse beantwortet Fragen zur Bedienung und liest die für die Lehrkraft sichtbaren Klassenstundenpläne und Anwesenheitsdaten. Für die vorhandenen Verwaltungs- und Unterrichtsaktionen zeigt er einen Entwurf mit den konkreten Angaben. Ausgeführt werden Änderungen erst nach ausdrücklicher Bestätigung mit den Rechten der angemeldeten Person.

Für die Demo arbeitet der Assistent in einer eigenen Codex-Sitzung mit einem extern betriebenen Modell und fiktiven Schülerdaten. Die Webanwendung verwendet dafür eine serverseitige Schnittstelle.

Klassenlotse entschuldigt einen vergangenen Unterrichtszeitraum, einen ganzen Schultag oder mit Attest mehrere Schultage. Ein optionaler Grund erscheint in der Wochenhistorie. Ganztägige Entschuldigungen sind auch als Vormerkung möglich.

Wird ein gültiger RFID-Scan nachgeliefert, zählt seine ursprüngliche Uhrzeit. Der Anwesenheitsdienst berechnet die betroffenen entschuldigten und unentschuldigten Minuten erneut.


## Fehlzeiten fürs Zeugnis

Ein vollständig versäumter Schultag zählt einmal. Sobald ein Teil unentschuldigt bleibt, zählt der ganze Tag als unentschuldigt. Teilweise Fehlzeiten werden je Konto in Minuten summiert und für das Zeugnis auf volle 45-Minuten-Unterrichtsstunden abgerundet. Bereits erfasste Anwesenheit schließt den Status „Abwesend“ für Korrekturen aus. Zusätzliche Fehlminuten lassen sich im Schülerprofil erfassen.
