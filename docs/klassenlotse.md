# Klassenlotse

Klassenlotse ist der Chat-Assistent der Weboberfläche. Er hilft Lehrkräften und
der Verwaltung, sich in der Anwesenheitserfassung zurechtzufinden, Daten
nachzuschlagen und Änderungen vorzubereiten. Dieses Dokument beschreibt, was er
kann, wie er mit Daten und Rechten umgeht und wo seine Grenzen liegen.

Die Schaltfläche unten rechts im angemeldeten Webbereich öffnet den Chat.

## Was Klassenlotse kann

### Fragen zur Bedienung

Klassenlotse erklärt, wo sich etwas in der Anwendung findet und wie ein Ablauf
funktioniert, zum Beispiel „Wie korrigiere ich einen Status?“. Führt die Antwort
auf eine Seite, steht ein Link dorthin unter der Antwort. Verlinkt werden nur
Seiten, die in der eigenen Navigation vorhanden sind.

### Lesende Abfragen

Fragen nach konkreten Werten beantwortet Klassenlotse mit einer Abfrage. Fehlt
eine Angabe wie Klasse oder Datum, erscheint ein kurzes Formular dafür. Unter dem
Ergebnis steht ein Link zur passenden Seite.

| Abfrage                       | Ergebnis im Chat                                                                                             |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------ |
| Schülerprofil anzeigen        | Entschuldigte und unentschuldigte Fehltage und Fehlstunden                                                   |
| Tagesanwesenheit anzeigen     | Alle Namen, getrennt nach unentschuldigt abwesend, entschuldigt und im Betrieb, dazu die Zahl der Anwesenden |
| Unterrichtszeiten anzeigen    | Unterrichtszeiten und Räume einer Klasse an einem Tag                                                        |
| Wochenstundenplan anzeigen    | Unterrichtszeiten einer Klasse von Montag bis Freitag                                                        |
| Anwesenheitshistorie anzeigen | Fehlzeiten einer Schülerin oder eines Schülers in einer Woche                                                |

### Aufträge mit Bestätigung

Für Änderungen füllt Klassenlotse ein Formular mit den genannten Angaben vor.
Jeder Auftrag durchläuft dieselben Schritte:

1. Formular prüfen und ergänzen.
2. Vorschau ansehen. Sie nennt die konkreten Angaben und bei riskanten
   Aufträgen die Folgen.
3. Ausdrücklich bestätigen. Erst dann wird gebucht.

„Abbrechen“ verwirft den Auftrag in jedem Schritt, ohne dass etwas gespeichert
wird. Die Aufträge lassen sich auch ohne Chat-Eingabe direkt über „Auftrag
auswählen“ öffnen.

**Fehlzeiten und Anwesenheit**

- Fehlzeit entschuldigen: bestimmte Uhrzeiten von heute oder einem vergangenen
  Tag, ein ganzer Schultag oder mit Attest mehrere Schultage. Die Vorschau zeigt
  je Tag, was sich ändert. Künftige ganze Tage werden vorgemerkt.
- Fehlminuten hinzufügen, immer mit einem Vermerk (höchstens 200 Zeichen), warum
  die Minuten eingetragen werden. Höchstens so viele Minuten, wie an dem Tag
  nach den bereits berechneten Fehlminuten noch geplant sind
- Offene Fehlzeiten entschuldigen
- Ganzen Schultag im Profil entschuldigen
- Tagesstatus korrigieren
- Status für einen Zeitraum ändern

**Schülerinnen, Schüler und Klassen**

- Schüler anlegen, Schülerstammdaten ändern, Schüler löschen
- Klasse anlegen
- Blockplan der Klasse wechseln
- Nur Verwaltung: Klasse bearbeiten, Klasse löschen, Fehlzeitenkonten
  zurücksetzen

**Planung, Räume, Personal und Karten (nur Verwaltung)**

- Stundenplan anlegen; Unterrichtszeit hinzufügen, ändern, löschen
- Blockplan anlegen, Unterrichtsblock hinzufügen, Klasse einem Unterrichtsblock
  zuordnen
- Raum anlegen, umbenennen, löschen
- Lehrkraft anlegen, bearbeiten, löschen (Vorname optional; ein neues Passwort
  setzt die Verwaltung im Formular „Lehrkraft bearbeiten“, nicht im Chat)
- RFID-Karte zuordnen, RFID-Kartenzuordnung entfernen

Die maßgebliche Liste steht in `frontend/src/api/assistantCommands.ts`.

## Daten und Rechte

**Das Modell macht nur Vorschläge.** Es liefert einen Antworttext, ein
Seitenziel oder einen Entwurf mit Feldwerten. Es hat keinen Zugriff auf die
Datenbank und kann selbst nichts lesen oder buchen.

**Abfragen und Buchungen laufen mit den Rechten der angemeldeten Person.** Der
Browser ruft dafür dieselben Schnittstellen auf wie die übrigen Seiten. Das
Backend prüft bei jedem Aufruf die Berechtigung, etwa die Zuständigkeit für eine
Klasse. Aufträge der Verwaltung werden Lehrkräften nicht angeboten. Eine
bestätigte Entschuldigung steht im Änderungsprotokoll unter der Person, die sie
bestätigt hat (siehe
[ADR 0001](adr/0001-bestaetigte-entschuldigung-im-chat.md)).

**Was an das Modell geht:** die aktuelle Chat-Nachricht mit höchstens 750
Zeichen, bis zu vier vorherige Nachrichten des Gesprächs, die aktuelle Seite,
die Rolle (Lehrkraft oder Verwaltung) und das heutige Datum. Ergebnisse von
Abfragen, Aufträgen und Buchungen bleiben im Browser und werden nicht in die
nächste Anfrage aufgenommen. Namen, die jemand selbst in den Chat schreibt, sind Teil
der Nachricht und gehen damit an das Modell.

**Passwörter und Kartenkennungen** gehören in das Formular des jeweiligen
Auftrags. Der Chat hält eine Nachricht zurück, wenn sie nach „Passwort“,
„Kennwort“, „Kartenkennung“, „Kartennummer“ oder „RFID-UID“ im selben Satz
„:“, „=“, „auf“, „lautet“, „ist“ oder „wird“ und einen Wert enthält, oder wenn
sie eine Kartenkennung aus 8, 14 oder 20 Hexzeichen enthält. Das ist eine
Absicherung gegen Versehen, keine vollständige Erkennung: Anders formulierte
Zugangsdaten gehen an das Modell. Deshalb gilt die Regel trotzdem.

**Demobetrieb:** Der Chat ist standardmäßig ausgeschaltet. Für die Demo startet
das Backend je Frage eine eigene Codex-Sitzung ohne Werkzeuge mit einem extern
betriebenen Modell (siehe [README, Abschnitt „Codex-Demo“](../README.md#codex-demo)).
In diesem Betrieb sind ausschließlich fiktive Schülerdaten zu verwenden. Die
Formulare für Aufträge und Entschuldigungen funktionieren auch ohne das Modell.

## Grenzen

- Klassenlotse bearbeitet pro Vorschlag genau einen Auftrag. Sammelaufträge wie
  „entschuldige die ganze Klasse“ gibt es nicht.
- Antworten des Modells können falsch oder unvollständig sein. Verbindlich sind
  die Vorschau und die Seiten der Anwendung.
- Das Modell kennt keine Daten der Schule. Es übernimmt nur Angaben aus der
  Nachricht; die Auswahl von Klasse und Schüler/in erfolgt im Formular anhand
  der tatsächlich zugänglichen Einträge.
- Das Gespräch wird nicht gespeichert. Nach dem Neuladen der Seite ist der
  Verlauf leer.
- Es wird jeweils nur eine Frage gleichzeitig beantwortet. Eine Antwort kann bis
  zu 40 Sekunden dauern. „Abbrechen“ gibt die Eingabe sofort wieder frei; die
  bereits gestartete Anfrage läuft auf dem Server noch zu Ende, sodass eine
  direkt folgende Frage kurz abgewiesen werden kann.
- Die Vorschau einer mehrtägigen Entschuldigung nennt für Tage ohne Änderung
  nicht, ob dort kein Unterricht geplant war oder keine Fehlminuten offen sind.
- Für den Betrieb mit echten Schülerdaten ist die Codex-Demo nicht vorgesehen.

## Stundenplan aus einem Bild

Die Verwaltung lädt über „Stundenplan aus Bild“ ein Foto oder einen Screenshot eines Wochenstundenplans hoch (PNG, JPEG oder WebP, höchstens 8 MB). Lehrkräften steht die Funktion nicht zur Verfügung. Klassenlotse liest daraus Klasse, Wochentag, Beginn, Ende, Fach, Lehrkraft und Raum; das dauert bis zu anderthalb Minuten. Beim Lesen wird nichts gespeichert.

Danach fragt Klassenlotse im Chat nach:

1. ob die erkannte Klasse stimmt – das Kürzel lässt sich korrigieren oder aus den vorhandenen Klassen wählen,
2. bei einer noch unbekannten Klasse, ob sie direkt mit angelegt werden soll – sonst entsteht nur der Stundenplan ohne Klasse,
3. für welchen Zeitraum der Plan gilt – ab einem Datum bis auf Weiteres oder befristet, etwa für einen Unterrichtsblock.

Mit diesen Antworten öffnet sich der Entwurf unter „Stundenplan-Entwurf“. Er bleibt im Browser-Tab erhalten, bis er angelegt oder verworfen wird, und wird beim Abmelden gelöscht. Ein weiteres Bild ersetzt einen offenen Entwurf erst nach Rückfrage. Er zeigt die Zuordnung zur Klasse, eine Vorschau im Wochenraster und jede Unterrichtszeit als bearbeitbare Zeile. Räume und Lehrkräfte ordnet das Backend den vorhandenen Stammdaten zu; was nicht eindeutig passt, bleibt offen und ist markiert. Unsichere oder abgeschnittene Angaben nennt ein Hinweis über dem Entwurf.

Erst „Stundenplan anlegen“ mit anschließender Bestätigung speichert, und zwar vollständig oder gar nicht. Für eine bestehende Klasse gilt der neue Plan nur im gewählten Zeitraum; davor, danach und für alle vergangenen Tage bleibt ihr bisheriger Plan maßgeblich, bereits erfasste Fehlzeiten ändern sich nicht. Eine neue Klasse braucht zusätzlich Klassenleitung und Blockplan.

Das Bild geht für das Auslesen an das extern betriebene Modell der Demo und wird auf dem Server danach gelöscht. Auf Stundenplänen stehen in der Regel Namen von Lehrkräften – für den Betrieb mit echten Daten gilt deshalb dasselbe wie für den übrigen Chat: nur mit geklärter Rechtsgrundlage oder mit einem lokal betriebenen Modell.

### Bekannte Einschränkung

Ein befristet importierter Stundenplan erscheint in der Planungsübersicht der Verwaltung nicht als bearbeitbare Fassung; dort steht die Fortsetzung des bisherigen Plans. Ändern lässt sich der importierte Plan nur über einen erneuten Import. Der bisherige Plan bleibt als nicht mehr verwendeter Eintrag in der Liste stehen.

## Ausblick

Die folgenden Punkte sind Ideen für eine mögliche Weiterentwicklung. Keiner
davon ist umgesetzt, geplant oder zugesagt.

- **Wochen- und Monatsauswertungen:** Fehlzeiten einer Klasse über einen
  längeren Zeitraum zusammenfassen, etwa vor einer Zeugniskonferenz.
- **Hinweise auf auffällige Fehlzeiten:** zum Beispiel wiederholtes Fehlen am
  selben Wochentag. Die Regeln dafür müssten nachvollziehbar und einstellbar
  sein; die Bewertung bliebe bei der Lehrkraft.
- **Entwürfe für Elternmitteilungen:** ein Textvorschlag zu offenen Fehlzeiten,
  den die Lehrkraft bearbeitet und selbst versendet.
- **Sprachsteuerung:** Fragen und Aufträge diktieren, etwa am Lehrerpult. Die
  Bestätigung vor jeder Buchung bliebe bestehen.
- **Datensparsamer Betrieb mit lokal betriebenem Modell:** Voraussetzung dafür,
  den Chat außerhalb der Demo mit echten Daten zu nutzen. Dazu gehört eine
  Abstimmung mit dem [Datenschutzkonzept](../datenschutzkonzept.md).
- **Vorschau mit allen Schultagen:** Das Backend könnte auch geplante Tage ohne
  offene Fehlminuten liefern, damit die Vorschau sie eindeutig benennt.
