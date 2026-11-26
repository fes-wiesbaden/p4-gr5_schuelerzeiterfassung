# Gestaltung der Anwesenheitserfassung

Anmeldung, Weboberfläche und RFID-Terminal verwenden helle Flächen, dunkle Schrift und Violett für Navigation und Aktionen. Luma ist die visuelle Referenz für Typografie, Abstände und ruhige Flächen. [Luma](https://luma.com/)

## Weboberfläche

Die Anmeldung kombiniert ein kompaktes Formular mit einer grafischen RFID-Karte. Auf schmalen Bildschirmen steht das Motiv oberhalb des Formulars. Im angemeldeten Bereich rückt die Navigation auf kleinen Displays in ein aufklappbares Menü. Die Live-Anwesenheit zeigt zuerst Klasse und Datum, dann die Zusammenfassung und die dazugehörige Liste.

Die runde Schaltfläche unten rechts öffnet Klassenlotse. Das Feenlicht schwebt innerhalb einer festen Klickfläche und zeichnet kurze goldene Funkenbahnen. Über die Schaltfläche im Chat lässt sich die Bewegung pausieren. Bei reduzierter Bewegung bleibt der Stern sichtbar und ruhig. Das Chatfenster hält den Tastaturfokus bis zum Schließen und unterscheidet Fragen, Antworten und bestätigbare Aufträge. Navigationsantworten verlinken auf die zugehörigen Seiten. Ein Entwurf für eine Entschuldigung bleibt editierbar und zeigt betroffene Schultage und Minutenänderungen direkt vor der Bestätigung.

Die Planung zeigt das Schuljahr und bietet den Regelstundenplan sowie die konkrete Kalenderwoche an. Der Regelstundenplan bildet das wiederkehrende Wochenraster ab. Eine Auswahl führt direkt zu den zugeordneten Unterrichtsblöcken. Das Wochenraster ordnet Lernfelder und Lehrkräfte von Montag bis Freitag an. Ein hinterlegtes Zeitraster zeigt auch freie Stunden und Pausen. Unterricht kann mehrere Zeilen belegen und erhält je Lehrkraft eine dezente Farbe. Lernfeld und Lehrkraft bleiben immer lesbar. Der Stammraum steht einmal über dem Raster, abweichende Räume erscheinen beim Unterricht. Auf kleinen Bildschirmen führt eine Tagesauswahl durch die Woche. Das Schülerprofil zeigt die Zeugniswerte vor der Wochenhistorie. Eingaben und Anzeigen verwenden das Datumsformat TT.MM.JJJJ und die 24-Stunden-Uhr.

DM Sans wird als variable WOFF2-Datei zusammen mit ihrer SIL Open Font License ausgeliefert. Symbole stammen aus PrimeIcons. [Schriftquelle](https://github.com/google/fonts/tree/main/ofl/dmsans)

## Farben und Formen

Die gemeinsamen Werte stehen in `frontend/src/styles.css`. Der Hintergrund ist `#f7f5fa`, Flächen sind weiß, die Hauptschrift ist `#261d34` und der Akzent ist `#6344ad`. Karten haben einen Radius von 14 Pixeln. Zustände verwenden Text und ein Symbol sowie eine passende Farbfläche.

## RFID-Terminal

Die Anzeige setzt das Statussymbol und die zur Situation passende Aufforderung in die Mitte. Die Kopfzeile identifiziert die Anwendung. Terminalnummer und offene Zustellungen stehen am unteren Rand. Der Status „Karte auflegen“ verwendet den violetten Akzent der Weboberfläche.
