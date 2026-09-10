# Systemarchitektur

![Systemarchitektur](./systemarchitektur.drawio.svg)

## Komponenten und Datenfluss

`RFID-Leser → ESP32 → USB-Serial → JavaFX → HTTPS/mTLS → nginx → Spring Boot → MySQL → synchrone neutrale Antwort → JavaFX`

- Der ESP32 liest die RFID-UID und sendet UID und feste Terminal-ID als UTF-8-JSON-Zeile über USB-Serial an JavaFX. Er verwendet kein WLAN, NTP, HTTPS, `WiFiClientSecure`, Server- oder Clientzertifikat.
- JavaFX erzeugt nach dem Kabelscan UUIDv4 und UTC-Scanzeit, speichert nicht endgültig beantwortete Scans lokal und wiederholt sie mit derselben Scan-ID. Es entfernt sie erst nach einer endgültigen Antwort; Netzwerk- und technische Serverfehler bleiben wiederholbar.
- `nginx` terminiert HTTPS, prüft für den Scan-Endpunkt das installationsbezogene JavaFX-Clientzertifikat gegen die Terminal-Client-CA und setzt die geprüfte Identität intern. Die JVM prüft Serverzertifikat sowie IP oder Hostnamen gegen die installierte Server-CA; Trust-all und deaktivierte Hostnamenprüfung sind verboten.
- Das Spring-Boot-Backend ist die alleinige Instanz für Autorisierung, Raumauflösung, Planung, Scanvalidierung, Anwesenheit, Verspätung und Aufbewahrung. Die JavaFX-Scanzeit ist für die Fachlogik maßgeblich und darf nicht nach der Backend-Eingangszeit liegen. Die Backend-Eingangszeit dient nur Rohscan-Nachvollziehbarkeit und technischem Logging.
- Das Backend gleicht Zertifikatsidentität, gemeldete Terminal-ID und persistierte Terminalzuordnung ab. Jedes Terminal gehört genau einem Raum und jeder Raum besitzt höchstens ein Terminal. Es bestimmt aus JavaFX-Scanzeit, `timetable_slot` und aktiver `block_assignment` die Klasse.
- Ein parsebarer Auftrag wird zusammen mit fachlicher Attendance und Audit in einer Transaktion gespeichert. `raw_scan` enthält kein ScanResult und keinen fachlichen Ablehnungsgrund; ein technischer Abbruch bleibt wiederholbar, jeder spätere Retry einer gespeicherten Scan-ID erhält `SCAN_ALREADY_RECEIVED`.
- Die JavaFX-Terminalansicht erhält nach vollständiger Verarbeitung nur eine neutrale synchrone Rückmeldung. UID, Name, Geburtsdatum, Klasse und Anwesenheitsdetails dürfen dort nie erscheinen.

Die Vue-SPA enthält ausschließlich geschützte Lehrkraft- und Administratorbereiche; es gibt keine öffentliche Terminalroute und kein Terminal-SSE. Die Lehreransicht lädt über einen serverseitig autorisierten REST-Endpunkt. Manueller Refresh oder Polling ist noch offen.

## Fachlogik und Speicherung

- Eine Attendance gilt je Schüler und Berliner Kalendertag; ihr anfänglicher Status ist implizit `ABWESEND`. `timetable_slot` und aktive `block_assignment` liefern die zu wertenden Zeitintervalle; Pausen liegen außerhalb der Slots.
- Ein gültiger Scan setzt nur `ABWESEND` oder `BETRIEBLICH_ENTSCHULDIGT` auf `ANWESEND`. Ein weiterer Scan bei `ANWESEND` oder einem anderen Status wird als `SCAN_REJECTED` abgelehnt und erzeugt keine Kontobuchung.
- Studenten führen getrennte, nichtnegative Konten für unentschuldigte und entschuldigte Minuten. Verspätungen und vollständiges Fehlen belasten zunächst das unentschuldigte Konto; 45 Minuten ergeben je Konto eine Zeugnisstunde.
- Personal kann den Status innerhalb eines Tages manuell ändern; der Statusverlauf bestimmt die Slot-Minuten. Eine Entschuldigung verschiebt nur die betroffenen Minuten.
- Alle terminal-, personal- und systemverursachten Änderungen erzeugen unveränderbare Audits mit signierten Minutendeltas und genau einer Quelle: Staff oder Terminal, bei System keine der beiden Referenzen. Eine zentrale transaktionale Soll-Ist-Abgleichsfunktion sperrt Attendance und Student und bucht nur Sollbeitrag minus auditierte Deltas. Ein manueller klassenweiser Reset hat keine Attendance, ist aber direkt mit dem Blockplan verknüpft und wird auch bei Null-Deltas auditiert.
- MySQL speichert Rohscans getrennt von Anwesenheiten und Audits. Rohscans werden 14 Tage nach `raw_scan.created_at` gelöscht. Sechs Monate nach `block_plan.ends_on` werden zugehörige `block_assignment`-, `attendance`- und `attendance_audit`-Daten gelöscht. `timetable` und `block_plan` werden erst gelöscht, wenn keine aufzubewahrende Blockzuordnung mehr auf sie verweist.
- `block_assignment` ist ein Wochen- oder Teilblock innerhalb eines `block_plan`. `terminal.terminal_id` ist Primärschlüssel. Jede Tabelle besitzt `created_at` und `changed_at`; diese technischen Felder zeigt das Chen-ERM nicht.
- `student.birth_date` ist Stammdaten- und ERM-Scope. Es erfordert keine neue Architekturkomponente und wird nicht an das Terminal übertragen.

Sitzung und CSRF schützen Web-Anfragen serverseitig durch Rollen- und Klassenrechte. Passwörter werden mit BCrypt gespeichert; der Session-Cookie ist Secure, HttpOnly und SameSite=Strict. Administratoren verwalten alle Klassen, Schüler und UIDs. Schüler einer Klasse dürfen nur Administrator oder Klassenlehrer anlegen bzw. löschen; `teacher_class` allein reicht nicht.

## Architektur-relevante Issues

- [#18 – Datenmodell](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/18), [#20 – Datenbank-Integrationstests](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/20), [#21 – RFID-UIDs & Datenaufbewahrung](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/21) und [#24 – Terminal-/Raum-Identifikation](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/24): Datenmodell, Aufbewahrung und Terminal-Raum-Zuordnung.
- [#26 – Scan-Endpunkt](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/26), [#29 – RFID-Hardware](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/29), [#30 – Tagesanwesenheit & Scanvalidierung](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/30) und [#31 – Anwesenheit & Status](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/31): Scanweg und zentrale Fachlogik.
- [#32 – Statuskorrekturen & Audit](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/32), [#40 – Stunden-/Blockplanung](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/40), [#44 – Statuskorrektur & Historie](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/44) und [#46 – Auswertungen](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/46): Minutenkonten, Audit-Abgleich, Blockplanung und Zeugnisstunden.
- [#22 – Login & Session-Sicherheit](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/22), [#23 – Rollen & Klassenrechte](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/23), [#34 – Vue-SPA](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/34), [#37 – JavaFX-Terminalansicht](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/37) und [#43 – Aktuelle Anwesenheitsansicht](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/43): Rechte, Oberflächentrennung und REST ohne SSE.
