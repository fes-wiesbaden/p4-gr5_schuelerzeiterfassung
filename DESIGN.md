# Gestaltung der Anwesenheitserfassung

Anmeldung, Weboberfläche und RFID-Terminal verwenden helle Flächen, dunkle Schrift und Violett für Navigation und Aktionen. Luma ist die visuelle Referenz für Typografie, Abstände und ruhige Flächen. [Luma](https://luma.com/)

## Weboberfläche

Die Anmeldung kombiniert ein kompaktes Formular mit einer grafischen RFID-Karte. Auf schmalen Bildschirmen steht das Motiv oberhalb des Formulars. Im angemeldeten Bereich rückt die Navigation auf kleinen Displays in ein aufklappbares Menü. Die Live-Anwesenheit zeigt zuerst Klasse und Datum, dann die Zusammenfassung und die dazugehörige Liste.

Die runde Schaltfläche unten rechts öffnet Klassenlotse. Das Feenlicht schwebt innerhalb einer festen Klickfläche und zeichnet kurze goldene Funkenbahnen. Über die Schaltfläche im Chat lässt sich die Bewegung pausieren. Bei reduzierter Bewegung bleibt der Stern sichtbar und ruhig. Das Chatfenster hält den Tastaturfokus bis zum Schließen und unterscheidet Fragen, Antworten und bestätigbare Aufträge. Navigationsantworten verlinken auf die zugehörigen Seiten. Ein Entwurf für eine Entschuldigung bleibt editierbar und zeigt betroffene Schultage und Minutenänderungen direkt vor der Bestätigung.

Die Planung zeigt das Schuljahr und die gewählte Kalenderwoche. Über die Blockauswahl lässt sich direkt zur ersten Woche eines zugeordneten Unterrichtsblocks springen. Das Wochenraster zeigt die Unterrichtszeiten von Montag bis Freitag. Ein hinterlegtes Zeitraster zeigt auch freie Stunden und Pausen. Längere Unterrichtszeiten können mehrere Zeilen belegen. Der Raum steht in jeder Unterrichtszelle. Auf kleinen Bildschirmen führt eine Tagesauswahl durch die Woche. Das Schülerprofil zeigt die Zeugniswerte vor der Wochenhistorie. Eingaben und Anzeigen verwenden das Datumsformat TT.MM.JJJJ und die 24-Stunden-Uhr.

DM Sans wird als variable WOFF2-Datei zusammen mit ihrer SIL Open Font License ausgeliefert. Symbole stammen aus PrimeIcons. [Schriftquelle](https://github.com/google/fonts/tree/main/ofl/dmsans)

## Rückmeldungen und Rückfragen

Folgenreiche Aktionen wie Löschen, das Zurücksetzen von Fehlzeitenkonten oder händische Fehlminuten fragen über einen eigenen Dialog nach. Der sichere Knopf „Abbrechen“ erhält den Fokus. Im Chat übernimmt die Vorschau des Auftrags mit eigener Bestätigung diese Rückfrage. Erfolgreiche Änderungen bestätigt eine grüne Meldung am oberen Seitenrand, die nach wenigen Sekunden verschwindet. Fehler stehen an derselben Stelle und nennen die Ursache in Alltagssprache.

Unbekannte Adressen zeigen „Seite nicht gefunden“, Verwaltungsseiten ohne Berechtigung „Kein Zugriff“. Nach einer abgelaufenen Sitzung erklärt die Anmeldung, warum sie erscheint.

## Kalender im Schülerprofil

Die Wochenauswahl der Anwesenheitshistorie markiert Unterrichtstage blau und Tage mit Fehlminuten orange. Tage außerhalb der zugeordneten Unterrichtsblöcke sind ausgegraut. Eine Legende steht unter dem Kalender. Korrekturen öffnen sich über „Korrigieren“ direkt unter dem jeweiligen Tag.

## Farben und Formen

Die gemeinsamen Werte stehen in `frontend/src/styles.css`. Der Hintergrund ist `#f7f5fa`, Flächen sind weiß, die Hauptschrift ist `#261d34` und der Akzent ist `#6344ad`. Karten haben einen Radius von 14 Pixeln. Zustände verwenden Text und ein Symbol sowie eine passende Farbfläche.

## RFID-Terminal

Die Anzeige setzt das Statussymbol und die zur Situation passende Aufforderung in die Mitte. Die Kopfzeile identifiziert die Anwendung. Terminalnummer und offene Zustellungen stehen am unteren Rand. Der Status „Karte auflegen“ verwendet den violetten Akzent der Weboberfläche.
