# Testdaten für Issue #19

`attendance.sql` enthält ausschließlich erfundene Personen und `TEST-UID-*`.
Die Lehrkräfte haben einen BCrypt-Testhash; Anmeldungen werden hier nicht getestet.
Die Datei liegt auf dem Test-Classpath und wird nach Flyway V1 per `@Sql` geladen.
Die Testklasse verwendet einen eigenen MySQL-8-Testcontainer. Jede Testmethode
lädt die Fixture und setzt ihre Änderungen durch Transaktionsrollback zurück.

```bash
mvn -pl backend spotless:check test -Dtest=AttendanceFixtureTest
```

Klassen A und B haben im Block vom 14.–18.09.2026 Unterricht in getrennten
Räumen. Klasse C verwendet den Stundenplan von A im Block vom 21.–25.09.2026.
Der gemeinsame Blockplan endet am 31.12.2026; die Audit-Löschfrist ist der 30.06.2027.

Beide Stundenpläne enthalten montags `07:30–09:00` und `09:15–10:45`.
Ein Slot dauert immer 90 Minuten, also zwei Schulstunden à 45 Minuten.
Ein Tag umfasst 180 Unterrichtsminuten; die Pause zählt nicht.
Slotzeiten sind Berliner Ortszeit, gespeicherte Ereigniszeitpunkte UTC.

| Schüler-ID | Fall | Ereignisse in Berliner Ortszeit | Unentschuldigt | Entschuldigt | Endstatus |
| --- | --- | --- | ---: | ---: | --- |
| 1 | Kein Scan | Tagesabschluss 10:45 | 180 | 0 | ABWESEND |
| 2 | Verspäteter Scan | Scan 09:15 | 90 | 0 | ANWESEND |
| 3 | Statuswechsel im Slot | Scan 07:30, Lehrkraft setzt ab 08:00 abwesend, Tagesabschluss 10:45 | 150 | 0 | ABWESEND |
| 4 | Teilentschuldigung | Scan 09:15 bucht 90; Lehrkraft entschuldigt um 09:30 rückwirkend ab 08:00 bis zum Scan: −60 unentschuldigt, +60 entschuldigt | 30 | 60 | ANWESEND |
| 5 | Bereits anwesend | Scan 07:30, weiterer Scan mit neuer ID 07:45; zwei Rohscans, ein Scan-Audit | 0 | 0 | ANWESEND |
| 6 | Retry | Scan 07:30, Wiederholung mit derselben Scan-ID und demselben Inhalt; ein Rohscan, ein Scan-Audit | 0 | 0 | ANWESEND |
| 7 | Zweiter Raum | Kein Scan, Tagesabschluss 10:45 | 180 | 0 | ABWESEND |
| 8 | Zweiter Block | Scan am 21.09.2026 um 08:00 | 30 | 0 | ANWESEND |

Die Fälle 1–7 liegen am 14.09.2026. Der letzte zeitlich wirksame Status von
Schüler 4 bleibt der Scan um 09:15, auch wenn das rückdatierte Audit später angelegt wurde.

Die Tests prüfen gespeicherte Zustände und verhindern eine doppelte Scan-ID auf
Datenbankebene. Die spätere Scanverarbeitung muss zusätzlich die Ablehnung bei
`ANWESEND` und die neutrale Retry-Antwort ohne weitere Buchung nachweisen.

## Issue #20: Etappe 1

`MySqlIntegrationTest` prüft Unique-Constraints durch abgelehnte Schreibversuche
und zulässige Gegenfälle, die Klassenauflösung einer Attendance per SQL und JPA,
Audit-Fremdschlüssel, mehrere Reset-Audits einschließlich Null-Deltas sowie den
Schutz referenzierter Historie vor vorzeitiger Löschung. Jeder Test lädt die
Fixture innerhalb einer zurückgerollten Transaktion.

`MySqlTransactionTest` lädt die Fixture committed in einen eigenen MySQL-Container
und bereinigt sie nach jedem Test in Fremdschlüsselreihenfolge. Geprüft werden
Commit und Rollback von Konto und Audit sowie die Löschreihenfolge
Audit → Attendance → Löschfrist mit einem nicht fälligen Kontrollfall.
Kontostände, Rohscans und Stammdaten bleiben bei der Historienlöschung erhalten.
Die Ergebnisse werden nach Abschluss der jeweiligen Transaktion gelesen.

Die SQL-Abläufe beweisen DB-Constraints und Transaktionen, keine Servicevalidierung.
Issue #20 bleibt offen, bis folgende Kriterien über die tatsächlichen Services
getestet werden können:

| Abhängigkeit | Ausstehende Nachweise |
| --- | --- |
| #21 / #40 | Blockplan-Erstellung berechnet `ends_on.plusMonths(6)`, einschließlich Monatsende und Schaltjahr; vorhandene Frist wird wiederverwendet; Plan und Frist werden atomar angelegt. |
| #21 | Retention-Service löscht fällige Historie transaktional zum vorgesehenen Berliner Zeitpunkt. |
| #40 | Slot-, Block- und Raumüberschneidungen werden beim Anlegen und Ändern abgelehnt. |
| #40 | Falscher Klassen-Blockplan und gleichzeitig genutzter Stundenplan werden abgelehnt. |
| #40 | Zulässige Gegenfälle, Zeitgrenzen und konkurrierende Planungsänderungen werden geprüft. |

Es werden keine SQL-Trigger, neuen Schema-Constraints oder Test-Services ergänzt,
um fehlende Backend-Logik zu ersetzen. Die Fixture-Prüfungen aus #19 bleiben
Nachweise für gespeicherte Beispieldaten, nicht für deren fachliche Verarbeitung.
