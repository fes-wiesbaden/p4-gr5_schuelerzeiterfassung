# Datenschutz

[[[Die Anwendung verarbeitet Anwesenheitsdaten von Schülerinnen und Schülern. Diese Daten behandeln wir besonders sorgfältig. Datenschutz ist kein nachträgliches Zusatzthema, sondern Teil von Datenmodell, Benutzeroberfläche, Berechtigungen und Betrieb.]]]

## Datenminimierung und Zweckbindung

[[[Wir verarbeiten nur Daten, die für die Anwesenheitserfassung erforderlich sind: Stammdaten (Voller Name und Geburtsdatum zur besseren Identifizierung), Klassenzuordnung, RFID-UID, Unterrichtsplanung sowie Anwesenheits- und Verspätungsdaten.]]]<br>
[[[Das öffentliche Terminal zeigt ausschließlich eine neutrale Erfolgs- oder Fehlermeldung. Es zeigt keine Namen, Klassen oder RFID-UIDs.]]]

## Datenmodell und Trennung der Daten

[[[Personenbezogene Stammdaten werden zentral gespeichert. Anwesenheiten referenzieren Schülerinnen und Schüler über interne IDs.]]]
[[[Das ist aber keine Anonymisierung oder vollständige Pseudonymisierung: Mit Zugriff auf die Stammdaten kann eine interne ID einer Person zugeordnet werden. Die Daten bleiben deshalb personenbezogen und werden entsprechend geschützt.]]]<br>

## Schutz der RFID-UID

[[[Die RFID-UID wird direkt am Schülerdatensatz gespeichert und nur serverseitig für die Zuordnung eines Scans verarbeitet. Sie darf nicht in Browser-Speicher, Terminaloberfläche, SSE-Rückmeldungen, API-Antworten für das Terminal, Konsolenausgaben oder Screenshots gelangen.]]]

[[[Die Terminalnummer dient nur zur Zuordnung des Terminals zu einem Raum. Die Zuordnung einer Klasse erfolgt immer anhand der aktuellen Unterrichtsplanung auf dem Server.]]]

## Zugriff und sichere Übertragung

- Die Kommunikation zwischen Weboberfläche und Backend erfolgt über HTTPS, die zwischen ESP32 und Backend über mTLS.
- Passwörter werden nicht im Klartext gespeichert, sondern mit BCrypt gehasht.
- Sitzungen werden mittels SpringSecurity verwaltet und abgesichert: HttpOnly, Secure, SameSite=Strict, Sitzungsrotation bei Anmeldung, Ablauf nach Inaktivität, serverseitige Abmeldung und CSRF-Schutz.
- [[[Berechtigungen werden im Backend geprüft. Administratoren verwalten alle Daten; Lehrkräfte erhalten nur Zugriff auf zugeordnete Klassen. Das gilt auch für Live-Anzeige, Historie, Auswertungen und CSV-Exporte.]]]

## Aufbewahrung und Löschung

- [[[Rohscans werden automatisch 14 Tage nach Eingang gelöscht.]]]
- [[[Anwesenheiten und Änderungsprotokolle werden sechs Monate nach Schuljahresende automatisch gelöscht.]]]
- [[[Der automatische Löschlauf entfernt außerdem nicht mehr benötigte Stunden- und Blockpläne einschließlich ihrer Unterrichtseinheiten.]]]

## Nachvollziehbarkeit und Datenqualität

[[[Jede manuelle Statusänderung erzeugt einen unveränderbaren und nur durch das System löschbaren Protokolleintrag. RFID-Scans werden über eine eindeutige Scan-ID idempotent verarbeitet. Wiederholte Übertragungen ändern daher keine bestehende Anwesenheit. Diese Maßnahmen schützen vor doppelten, unberechtigten oder nicht nachvollziehbaren Änderungen.]]]

## Entwicklung und Betrieb

[[[Während der Entwicklung verwenden wir ausschließlich fiktive Testdaten. Echte Schülerdaten, RFID-UIDs, Zugangsdaten und sensible Screenshots gehören nicht in Quellcode, Git, Tickets oder Dokumentation. Jede neue Schnittstelle und Anzeige wird darauf geprüft, nur die für ihre Aufgabe notwendigen Daten bereitzustellen.]]]<br><br>


## Teil für Dokumentation
[[[Da das System personenbezogene Daten verarbeitet (Namen, Geburtsdaten, Anwesenheitsdaten), hat der Schutz seiner Daten hohe Priorität. Dafür greifen wir auf verschiedene Maßnahmen zurück: Organisatorische und technische.]]]<br>
[[[Zu den organisatorischen gehören die Datenminimierung (wir nutzen nur Daten, die für die Funktion und die eindeutige Zuordnung relevant sind), die Zugriffsbeschränkungen (Lehrer haben nur Zugriff auf die von ihnen unterrichteten Klassen) sowie die Löschfristen (Rohscans werden 14 Tage, Anwesenheiten und Änderungsprotokolle sechs Monate nach Schuljahresende aufgehoben).]]]
Die technischen beinhalten unter anderem eine Zertifikatsprüfung und verschlüsselte Kommunikation (https für Backend <-> Frontend, mTLS für Terminal <-> Backend), eine passwortgeschützte Datenbank und gehashte Passwörter sowie effektives Sitzungsmanagement durch das Backend (Inaktivitäts-Logout, Sitzungsrotation, httpOnly-Flag am Cookie, CSRF-Schutz).
