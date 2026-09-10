# Datenschutz

Die Anwendung verarbeitet Anwesenheitsdaten von Schülerinnen und Schülern. Diese Daten behandeln wir besonders sorgfältig. Datenschutz ist kein nachträgliches Zusatzthema, sondern Teil von Datenmodell, Benutzeroberfläche, Berechtigungen und Betrieb.

## Datenminimierung und Zweckbindung

Wir verarbeiten nur Daten, die für die Anwesenheitserfassung erforderlich sind: Stammdaten (Voller Name und Geburtsdatum zur besseren Identifizierung), Klassenzuordnung, RFID-UID, Unterrichtsplanung, Anwesenheiten sowie getrennte Konten für nicht entschuldigte und entschuldigte Minuten.<br>
Das JavaFX-Terminal zeigt ausschließlich eine neutrale Erfolgs- oder Fehlermeldung. Es zeigt keine Namen, Klassen oder RFID-UIDs.

## Datenmodell und Trennung der Daten

Personenbezogene Stammdaten werden zentral gespeichert. Anwesenheiten referenzieren Schülerinnen und Schüler über interne IDs.
Das ist aber keine Anonymisierung oder vollständige Pseudonymisierung: Mit Zugriff auf die Stammdaten kann eine interne ID einer Person zugeordnet werden. Die Daten bleiben deshalb personenbezogen und werden entsprechend geschützt.<br>

## Schutz der RFID-UID

Die RFID-UID wird direkt am Schülerdatensatz gespeichert und serverseitig für die Zuordnung eines Scans verarbeitet. JavaFX darf sie nur vorübergehend in einer lokal zugriffsbeschränkten Warteschlange halten, bis eine endgültige Backend-Antwort vorliegt. Sie darf nicht in Browser-Speicher, Terminaloberfläche, API-Antworten für das Terminal, Konsolenausgaben, technische Logs oder Screenshots gelangen.

Die Terminal-ID identifiziert das Terminal und wird mit dessen mTLS-Identität abgeglichen. Jedes Terminal gehört genau einem Raum und jeder Raum besitzt höchstens ein Terminal. Die Zuordnung einer Klasse erfolgt immer anhand der aktuellen Unterrichtsplanung auf dem Server.

## Zugriff und sichere Übertragung

- Die Kommunikation zwischen Weboberfläche und Backend erfolgt über HTTPS. JavaFX kommuniziert über mTLS mit nginx; der ESP32 ist ausschließlich per USB-Serial mit JavaFX verbunden.
- Die JVM prüft Serverzertifikat und IP beziehungsweise Hostnamen gegen die installierte Server-CA. nginx prüft das installationsbezogene JavaFX-Clientzertifikat gegen die Terminal-Client-CA.
- Spring Boot gleicht die von nginx geprüfte Zertifikatsidentität mit der gemeldeten Terminal-ID und der gespeicherten Terminalzuordnung ab.
- Passwörter werden nicht im Klartext gespeichert, sondern mit BCrypt gehasht.
- Sitzungen werden mittels SpringSecurity verwaltet und abgesichert: HttpOnly, Secure, SameSite=Strict, Sitzungsrotation bei Anmeldung, Ablauf nach Inaktivität, serverseitige Abmeldung und CSRF-Schutz.
- Berechtigungen werden im Backend geprüft. Administratoren verwalten alle Daten; Lehrkräfte erhalten nur Zugriff auf zugeordnete Klassen. Das gilt auch für Live-Anzeige, Historie, Auswertungen und CSV-Exporte.

## Aufbewahrung und Löschung

- Rohscans werden automatisch 14 Tage nach Eingang gelöscht.
- Anwesenheiten und Änderungsprotokolle werden um 00:00 Uhr Europe/Berlin sechs Kalendermonate nach `block_plan.ends_on` automatisch gelöscht.
- Der automatische Löschlauf entfernt außerdem nicht mehr benötigte Stunden- und Blockpläne, sobald keine aufzubewahrende Blockzuordnung mehr darauf verweist.

## Nachvollziehbarkeit und Datenqualität

Jede automatische, terminalbasierte oder manuelle Anwesenheits- und Minutenänderung erzeugt einen unveränderbaren und nur durch das System löschbaren Protokolleintrag mit ihrer Quelle und den entstandenen Minutendeltas. JavaFX versieht RFID-Scans mit einer eindeutigen Scan-ID; das Backend verarbeitet Wiederholungen idempotent. Wiederholte Übertragungen ändern daher keine bestehende Anwesenheit. Diese Maßnahmen schützen vor doppelten, unberechtigten oder nicht nachvollziehbaren Änderungen.

## Entwicklung und Betrieb

Während der Entwicklung verwenden wir ausschließlich fiktive Testdaten. Echte Schülerdaten, RFID-UIDs, Zugangsdaten und sensible Screenshots gehören nicht in Quellcode, Git, Tickets oder Dokumentation. Jede neue Schnittstelle und Anzeige wird darauf geprüft, nur die für ihre Aufgabe notwendigen Daten bereitzustellen.<br><br>


## Teil für Dokumentation
Da das System personenbezogene Daten verarbeitet (Namen, Geburtsdaten, Anwesenheitsdaten), hat der Schutz seiner Daten hohe Priorität. Dafür greifen wir auf verschiedene Maßnahmen zurück: Organisatorische und technische.<br>
Zu den organisatorischen gehören die Datenminimierung (wir nutzen nur Daten, die für die Funktion und die eindeutige Zuordnung relevant sind), die Zugriffsbeschränkungen (Lehrer haben nur Zugriff auf die von ihnen unterrichteten Klassen) sowie die Löschfristen (Rohscans werden 14 Tage nach Eingang, Anwesenheiten und Änderungsprotokolle um 00:00 Uhr Europe/Berlin sechs Kalendermonate nach `block_plan.ends_on` aufgehoben).
Die technischen beinhalten unter anderem eine beidseitige Zertifikatsprüfung und verschlüsselte Kommunikation (HTTPS für Backend ↔ Frontend, mTLS für JavaFX-Terminal ↔ nginx), eine passwortgeschützte Datenbank und gehashte Passwörter sowie effektives Sitzungsmanagement durch das Backend (Inaktivitäts-Logout, Sitzungsrotation, HttpOnly-Flag am Cookie, CSRF-Schutz).
