# Anwesenheitserfassung

<!-- impeccable:product-schema 1 -->

## Platform

web

## Nutzerinnen und Nutzer

- Lehrkräfte prüfen die Anwesenheit und Fehlminuten ihrer zugeordneten Klassen.
- Die Verwaltung betreut Stammdaten, Räume, Terminals und Planung. Sie legt Lehrkräfte an (Vorname optional) und setzt im Formular „Lehrkraft bearbeiten“ bei Bedarf ein neues Passwort; ein leeres Feld lässt das Passwort unverändert.
- Schülerinnen und Schüler erfassen ihre Anwesenheit mit ihrer RFID-Karte am JavaFX-Terminal.

## Zweck

Die Anwendung erfasst die Anwesenheit von Schulklassen anhand der RFID-Scans und der Unterrichtsplanung. Lehrkräfte sehen den aktuellen Stand und können Fehlzeiten nachvollziehen.

## Überarbeitung

Alle Webseiten folgen einem gemeinsamen Design; die JavaFX-Anzeige übernimmt es schrittweise. Luma dient als visuelle Referenz. „Karte auflegen“ ist die zentrale Aufforderung am Terminal.

Die Live-Anwesenheit zeigt für eine ausgewählte Klasse und einen ausgewählten Tag den Tagesstatus, ganze unentschuldigte Fehltage und teilweise Fehlzeiten. Ein Klick auf einen Namen öffnet das Profil samt Wochenhistorie und Korrekturen.

## Chat-Assistent

Im angemeldeten Webbereich öffnet eine Schaltfläche unten rechts den Assistenten. Klassenlotse beantwortet Fragen zur Bedienung und liest die für die Lehrkraft sichtbaren Klassenstundenpläne und Anwesenheitsdaten. Für die vorhandenen Verwaltungs- und Unterrichtsaktionen zeigt er einen Entwurf mit den konkreten Angaben. Ausgeführt werden Änderungen erst nach ausdrücklicher Bestätigung mit den Rechten der angemeldeten Person.

Für die Demo arbeitet der Assistent in einer eigenen Codex-Sitzung mit einem extern betriebenen Modell und fiktiven Schülerdaten. Die Webanwendung verwendet dafür eine serverseitige Schnittstelle.

Klassenlotse entschuldigt einen Unterrichtszeitraum von heute oder einem vergangenen Tag, einen ganzen Schultag oder mit Attest mehrere Schultage. Ein optionaler Grund erscheint in der Wochenhistorie. Ganztägige Entschuldigungen sind auch als Vormerkung möglich.

Die Verwaltung kann außerdem einen Stundenplan aus einem Foto oder Screenshot auslesen lassen. Klassenlotse fragt danach die Klasse und den Gültigkeitszeitraum ab und öffnet einen Entwurf; angelegt wird erst nach dessen Bestätigung.

Wird ein gültiger RFID-Scan nachgeliefert, zählt seine ursprüngliche Uhrzeit. Der Anwesenheitsdienst berechnet die betroffenen entschuldigten und unentschuldigten Minuten erneut.

Funktionen, Umgang mit Daten und Rechten, Grenzen und mögliche Erweiterungen beschreibt [docs/klassenlotse.md](docs/klassenlotse.md).

## Fehlzeiten fürs Zeugnis

Ein vollständig versäumter Schultag zählt einmal. Sobald ein Teil unentschuldigt bleibt, zählt der ganze Tag als unentschuldigt. Teilweise Fehlzeiten werden je Konto in Minuten summiert und für das Zeugnis auf volle 45-Minuten-Unterrichtsstunden abgerundet.

Karten lassen sich ab 07:30 Uhr einstechen: Außerhalb einer laufenden Stunde zählt der Scan für die nächste Unterrichtszeit des Tages in deren Raum. Scans vor 07:30 Uhr werden abgelehnt.

Ein Kartenscan schließt den Status „Abwesend“ für Korrekturen aus. Ohne Scan, also bei händisch eingetragener Anwesenheit, bleibt „Abwesend“ wählbar; die Korrektur nimmt dann nur die händische Anwesenheit ab dem gewählten Zeitpunkt zurück.

Zusätzliche Fehlminuten lassen sich im Schülerprofil oder über Klassenlotse erfassen. Jede Buchung braucht einen Vermerk mit höchstens 200 Zeichen. Sie ist auf die geplanten Unterrichtsminuten des Tages abzüglich der bereits berechneten Fehlminuten begrenzt und lässt sich wieder entfernen, auch an einem Tag, dessen Unterricht vollständig ausgefallen ist.

## Unterrichtsausfall

Verwaltung und Lehrkräfte markieren im Wochenstundenplan einzelne Stunden, einen ganzen Tag oder eine ganze Woche einer Klasse als Ausfall. Ein Klick auf die Stunde trägt den Ausfall für das angezeigte Datum ein, ein weiterer Klick nimmt ihn zurück. Ausgefallene Stunden sind rot schraffiert und zählen nicht als geplanter Unterricht. Der Ausfall greift über die Zeitüberschneidung und gilt deshalb auch, wenn die Stunde später verschoben wurde. Das gilt auch rückwirkend: Für vergangene Tage berechnet der Anwesenheitsdienst die Fehlzeiten der Klasse neu, beim Zurücknehmen ebenso. Händisch gebuchte Fehlminuten bleiben dabei bestehen; nach dem Eintragen nennt die Anwendung die betroffenen Schülerinnen und Schüler, damit die Lehrkraft die Buchungen prüfen und bei Bedarf entfernen kann.
