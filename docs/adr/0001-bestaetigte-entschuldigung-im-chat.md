# Das Backend verbucht bestätigte Entschuldigungen aus dem Chat

Der folgende Stand beschreibt die erste Fassung. Klassenlotse unterstützt inzwischen auch ganze Schultage und bei Attest mehrere Schultage. Die Vorschau und ausdrückliche Bestätigung gelten weiterhin.


Der Chat-Assistent erstellt einen Entschuldigungsvorschlag. Die angemeldete Person prüft Schüler und Zeitraum und bestätigt die Buchung. Das Backend prüft die Klassenberechtigung und verbucht die Entschuldigung mit dem bestehenden Änderungsprotokoll, damit die Änderung der bestätigenden Person zugeordnet werden kann.

Das Änderungsprotokoll erhält einen eigenen Ereignistyp mit Anfang und Ende. Dadurch zählt ein gültiger nachgereichter Scan weiter zum Zeitpunkt des Auflegens, und der gemeinsame Minutenabgleich berechnet das bestätigte Zeitfenster bei Bedarf neu.
