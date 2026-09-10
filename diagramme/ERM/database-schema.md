# Database Schema

Dieses Dokument beschreibt das geplante MySQL-8-Schema des
Tagesanwesenheitsmodells. Tabellen- und Spaltennamen sind Englisch,
fachliche Statuswerte Deutsch. Es konkretisiert
`tagesanwesenheit-modell.md`; bei Widersprüchen gilt dieses Schema nicht als
eigenständige abweichende Regelquelle.

## Grundregeln

- Eine `attendance` gilt für einen Schüler und Kalendertag. Die Unterrichtsintervalle stammen aus `timetable_slot` und
  der aktiven `block_assignment`. So wird nur ein Tageszustand geführt.
- Ein Schüler beginnt seinen Schultag implizit als `ABWESEND`. Statuswechsel
  stehen unveränderlich in `attendance_audit`.
- Planungsdatum und Anzeige verwenden `Europe/Berlin`. Ereigniszeitpunkte
  werden als UTC in `DATETIME` gespeichert, damit Zeitumstellungen eindeutig
  bleiben.
- Zeitintervalle sind am Anfang einschließlich, am Ende ausschließlich:
  `[start_time, end_time)`. Die Datumsgrenzen einer Blockzuordnung sind beide
  einschließlich.
- Pausen und Feiertage erzeugen keine Sonderlogik. Nur Überschneidungen mit
  geplanten Slots zählen als Unterrichtszeit.
- `student.unexcused_minutes_account` und
  `student.excused_minutes_account` sind nichtnegative aktuelle Kontostände.
  Jede Änderung erhält in derselben Transaktion ein Audit mit signierten
  Deltas.
- Eine Zeugnisstunde entspricht `45` Minuten. Das Schema speichert keine
  zusätzliche Zeugnis- oder Stundensumme.
- Veränderbare Tabellen besitzen `created_at` und `changed_at`. Die
  unveränderlichen Tabellen `raw_scan` und `attendance_audit` besitzen nur
  `created_at`. Diese technischen Felder fehlen im Chen-ERM zugunsten der
  Lesbarkeit.

## Tables

### `staff`

Personal mit Anmeldung. `role` unterscheidet Lehrkräfte und Administratoren.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `1` |
| `first_name` | `VARCHAR(100)` | not null | `Anna` |
| `last_name` | `VARCHAR(100)` | not null | `Muster` |
| `username` | `VARCHAR(120)` | not null, unique | `anna.muster` |
| `password_hash` | `VARCHAR(60)` | not null, BCrypt hash | `<bcrypt-hash>` |
| `role` | `ENUM('LEHRKRAFT', 'ADMINISTRATOR')` | not null | `LEHRKRAFT` |
| `created_at` | `DATETIME` | not null, UTC | `2026-09-01 06:00:00` |
| `changed_at` | `DATETIME` | not null, UTC | `2026-09-01 06:00:00` |

Der Benutzername folgt `vorname.nachname`; Kollisionen erhalten einen
numerischen Suffix. Diese Regel verhindert manuelle uneinheitliche Namen.

### `school_class`

Eine konkrete Klasse in genau einem Schuljahr. Eine neue Jahrgangsklasse
erhält einen neuen Datensatz; dadurch bleiben alte Planungsbezüge korrekt.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `13` |
| `class_code` | `VARCHAR(30)` | not null | `10BE13` |
| `school_year` | `VARCHAR(9)` | not null, format `YYYY/YYYY` | `2026/2027` |
| `class_teacher_id` | `BIGINT` | not null, foreign key to `staff.id`; staff role must be `LEHRKRAFT` | `1` |
| `created_at` | `DATETIME` | not null, UTC | `2026-09-01 06:00:00` |
| `changed_at` | `DATETIME` | not null, UTC | `2026-09-01 06:00:00` |

`UNIQUE(class_code, school_year)` erlaubt denselben Klassencode in späteren
Schuljahren, aber keine Dublette innerhalb eines Schuljahres. Beim Wechsel
wird `student.school_class_id` auf die neue Klasse gesetzt. Eine alte Klasse
darf erst gelöscht werden, wenn weder Schüler, `teacher_class` noch eine
aufzubewahrende `block_assignment` auf sie verweisen; sonst ginge Historie
verloren.

Nur Administratoren oder der eingetragene Klassenlehrer dürfen Schüler der
Klasse anlegen oder löschen. `teacher_class` allein reicht nicht, weil eine
Unterrichtszuordnung keine Verwaltungsberechtigung ist.

### `student`

Schüler bleiben über Schuljahre erhalten. Die aktuelle Klasse wird direkt
referenziert; alte Planungsbezüge bleiben an den damaligen Klassen.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `101` |
| `first_name` | `VARCHAR(100)` | not null | `Erika` |
| `last_name` | `VARCHAR(100)` | not null | `Beispiel` |
| `birth_date` | `DATE` | not null | `2008-05-14` |
| `school_class_id` | `BIGINT` | not null, foreign key to `school_class.id` | `13` |
| `rfid_uid` | `VARCHAR(64)` | nullable, unique, plaintext | `TEST-UID-001` |
| `unexcused_minutes_account` | `INT` | not null, default `0`, check `>= 0` | `0` |
| `excused_minutes_account` | `INT` | not null, default `0`, check `>= 0` | `0` |
| `created_at` | `DATETIME` | not null, UTC | `2026-09-01 06:05:00` |
| `changed_at` | `DATETIME` | not null, UTC | `2026-09-07 08:45:00` |

Eine neue RFID-UID überschreibt die alte. Es gibt keine `card`-Tabelle und
keine UID-Historie, weil beides fachlich nicht benötigt wird.

### `teacher_class`

M:N-Zuordnung zwischen Lehrkräften und konkreten Klassen für Unterricht und
Planung.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `staff_id` | `BIGINT` | primary key part, foreign key to `staff.id`; role must be `LEHRKRAFT` | `1` |
| `school_class_id` | `BIGINT` | primary key part, foreign key to `school_class.id` | `13` |
| `created_at` | `DATETIME` | not null, UTC | `2026-09-01 06:00:00` |
| `changed_at` | `DATETIME` | not null, UTC | `2026-09-01 06:00:00` |

Der Primärschlüssel `(staff_id, school_class_id)` verhindert doppelte
Zuordnungen.

### `room`

Stammdaten der Unterrichtsräume.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `2` |
| `room_number` | `VARCHAR(30)` | not null, unique | `A123` |
| `created_at` | `DATETIME` | not null, UTC | `2026-09-01 06:00:00` |
| `changed_at` | `DATETIME` | not null, UTC | `2026-09-01 06:00:00` |

### `terminal`

Ein Terminal gehört dauerhaft genau einem Raum; ein Raum besitzt höchstens
ein Terminal. Ein Terminal hat keine feste Klassenbeziehung, weil die Klasse
aus Raum, Scanzeit und Planung folgt.

| Column        | MySQL type | Rules | Example |
|---------------| --- | --- | --- |
| `terminal_id` | `INT` | primary key | `3` |
| `room_id`     | `BIGINT` | not null, unique, foreign key to `room.id` | `2` |
| `created_at`  | `DATETIME` | not null, UTC | `2026-09-01 06:10:00` |
| `changed_at`  | `DATETIME` | not null, UTC | `2026-09-01 06:10:00` |

### `timetable`

Wiederverwendbare Wochenvorlage. Unterschiedliche Klassen können denselben
Stundenplan nacheinander über eigene Blockzuordnungen nutzen.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `1` |
| `name` | `VARCHAR(150)` | not null | `Fachinformatik AE 2026/27` |
| `created_at` | `DATETIME` | not null, UTC | `2026-09-01 06:00:00` |
| `changed_at` | `DATETIME` | not null, UTC | `2026-09-01 06:00:00` |

Ab Beginn der ersten `block_assignment` sind der Stundenplan und seine Slots
unveränderlich. Fehler müssen vor Betriebsbeginn korrigiert werden; spätere
Änderungen würden bereits berechnete Tage rückwirkend verändern. Eine
zukünftige Änderung verwendet einen neuen Stundenplan und eine neue,
nichtüberlappende Blockzuordnung.

### `timetable_slot`

Ein wiederkehrendes Unterrichtsintervall. Eine Doppelstunde benötigt nur
`start_time` und `end_time`, beispielsweise `07:30–09:00`; zusätzliche
Start-/Endfelder wären redundant.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `12` |
| `timetable_id` | `BIGINT` | not null, foreign key to `timetable.id` | `1` |
| `room_id` | `BIGINT` | not null, foreign key to `room.id` | `2` |
| `weekday` | `ENUM('MONTAG', 'DIENSTAG', 'MITTWOCH', 'DONNERSTAG', 'FREITAG')` | not null | `MONTAG` |
| `start_time` | `TIME` | not null | `07:30:00` |
| `end_time` | `TIME` | not null, check `end_time > start_time` | `09:00:00` |
| `created_at` | `DATETIME` | not null, UTC | `2026-09-01 06:00:00` |
| `changed_at` | `DATETIME` | not null, UTC | `2026-09-01 06:00:00` |

Slots desselben Stundenplans dürfen sich am selben Wochentag nicht
überschneiden. Das Backend verhindert außerdem Raumüberschneidungen zwischen
Slots verschiedener Stundenpläne, wenn deren Blockzuordnungen gleichzeitig
aktiv sind. Sonst könnte ein Raumscan nicht eindeutig einer Klasse zugeordnet
werden.

### `block_plan`

Rahmen eines Schuljahres oder Planungszeitraums. Mehrere Klassen besitzen
darin eigene zeitlich begrenzte Blockzuordnungen.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `1` |
| `name` | `VARCHAR(150)` | not null | `Blockplan Fachinformatik 2026/27` |
| `starts_on` | `DATE` | not null | `2026-01-01` |
| `ends_on` | `DATE` | not null, check `ends_on >= starts_on` | `2026-12-23` |
| `created_at` | `DATETIME` | not null, UTC | `2025-12-01 08:00:00` |
| `changed_at` | `DATETIME` | not null, UTC | `2025-12-01 08:00:00` |

`ends_on` ist nur der Anker für die Retention. Der Kontenreset erfolgt
manuell, weil sein fachlich richtiger Zeitpunkt von Zeugnisausgabe und
Sonderfällen abhängt.

### `block_assignment`

Verbindet Blockplan, konkrete Klasse und Stundenplan für einen Teilzeitraum.
Beispiel: Der Plan gilt `01.01–23.12`, die Klasse aber nur `07.10–10.11`.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `4` |
| `block_plan_id` | `BIGINT` | not null, foreign key to `block_plan.id` | `1` |
| `school_class_id` | `BIGINT` | not null, foreign key to `school_class.id` | `13` |
| `timetable_id` | `BIGINT` | not null, foreign key to `timetable.id` | `1` |
| `starts_on` | `DATE` | not null | `2026-10-07` |
| `ends_on` | `DATE` | not null, check `ends_on >= starts_on` | `2026-11-10` |
| `created_at` | `DATETIME` | not null, UTC | `2026-09-01 06:00:00` |
| `changed_at` | `DATETIME` | not null, UTC | `2026-09-01 06:00:00` |

Die Zuordnung muss vollständig innerhalb ihres Blockplans liegen. Zeiträume
desselben Stundenplans und derselben Klasse dürfen nicht überlappen. Ein
Schüler darf dadurch an einem Datum nur einer aktiven Blockzuordnung
angehören. Die nächste Zuordnung eines Schülers darf erst nach dem
Kontenreset seines vorherigen Blockplans beginnen; andernfalls könnte der
Reset Minuten des neuen Plans löschen. Diese zeitabhängigen Regeln prüft das
Backend transaktional, da einzelne SQL-Constraints keine Intervallüberschneidung
über mehrere Zeilen ausdrücken.

### `attendance`

Aktueller Tagesstatus eines Schülers. Der Datensatz wird beim ersten Vorgang
oder spätestens beim Tagesabschluss angelegt; vor dem ersten Statusereignis
gilt implizit `ABWESEND`.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `900` |
| `student_id` | `BIGINT` | not null, foreign key to `student.id` | `101` |
| `attendance_date` | `DATE` | not null, local date in `Europe/Berlin` | `2026-10-12` |
| `block_assignment_id` | `BIGINT` | not null, foreign key to `block_assignment.id`; active on `attendance_date` | `4` |
| `status` | `ENUM('ANWESEND', 'ABWESEND', 'BETRIEB', 'BETRIEBLICH_ENTSCHULDIGT', 'ENTSCHULDIGT', 'MIT_ATTEST_ENTSCHULDIGT')` | not null | `ANWESEND` |
| `created_at` | `DATETIME` | not null, UTC | `2026-10-12 05:45:00` |
| `changed_at` | `DATETIME` | not null, UTC | `2026-10-12 08:15:12` |

`UNIQUE(student_id, attendance_date)` erzwingt genau einen Tagesdatensatz.
`block_assignment_id` speichert die an diesem Tag aktive Zuordnung und bildet
den Retention-Anker. `first_scanned_at` entfällt; der erste Scan ist ein
Audit-Statuswechsel von `ABWESEND` auf `ANWESEND`.

Status und Minutenwirkung:

| Status | Minutenwirkung |
| --- | --- |
| `ANWESEND`, `BETRIEB` | keine Fehlminuten |
| `ABWESEND` | unentschuldigte Minuten |
| `ENTSCHULDIGT`, `MIT_ATTEST_ENTSCHULDIGT`, `BETRIEBLICH_ENTSCHULDIGT` | entschuldigte Minuten |

### `attendance_audit`

Unveränderliches Ereignis- und Buchungsprotokoll. Es dokumentiert
Statuswechsel, Kontodeltas und deren Quelle.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `77` |
| `student_id` | `BIGINT` | not null, foreign key to `student.id` | `101` |
| `attendance_id` | `BIGINT` | nullable, foreign key to `attendance.id`; set for normal attendance audits | `900` |
| `block_plan_id` | `BIGINT` | nullable, foreign key to `block_plan.id`; set only for account reset audit | `NULL` |
| `source` | `ENUM('SYSTEM', 'STAFF', 'TERMINAL')` | not null | `STAFF` |
| `changed_by_staff_id` | `BIGINT` | nullable, foreign key to `staff.id`; required only for `STAFF` | `1` |
| `terminal_id` | `INT` | nullable, foreign key to `terminal.terminal_id`; required only for `TERMINAL` | `NULL` |
| `old_status` | same enum as `attendance.status` | nullable; set only with `new_status` | `ANWESEND` |
| `new_status` | same enum as `attendance.status` | nullable; set only with `old_status`; must differ | `ENTSCHULDIGT` |
| `occurred_at` | `DATETIME` | not null, fachlicher UTC-Zeitpunkt | `2026-10-12 08:15:00` |
| `unexcused_minutes_delta` | `INT` | not null, default `0`, signed | `0` |
| `excused_minutes_delta` | `INT` | not null, default `0`, signed | `45` |
| `created_at` | `DATETIME` | not null, technischer UTC-Zeitpunkt | `2026-10-12 08:16:00` |

Normale Audits setzen `attendance_id` und lassen `block_plan_id` leer.
Reset-Audits tun das Gegenteil. Diese exklusive Zuordnung verhindert einen
willkürlichen Tagesbezug bei `attendance_id = NULL`. Bei gesetzter Attendance
muss deren Schüler mit `student_id` übereinstimmen.

Für `source = STAFF` ist nur `changed_by_staff_id` gesetzt, für
`source = TERMINAL` nur `terminal_id`; bei `source = SYSTEM` sind beide
leer. `source` bleibt zusätzlich erhalten, weil eine Staff- oder
Terminal-ID allein die Ereignisart nicht eindeutig ausdrückt.

Bei einem Statuswechsel sind `old_status` und `new_status` gesetzt. Ohne
Statuswechsel sind beide `NULL`. Ein normales Audit muss einen Statuswechsel
oder mindestens ein Delta ungleich `0` enthalten. Ein Reset-Audit darf beide
Deltas `0` enthalten: Ohne eigene Reset-Tabelle ist dieser Marker nötig, um
auch den manuellen Reset eines bereits leeren Kontos nachzuweisen.

`new_status` gilt ab `occurred_at` bis zum nächsten Ereignis, bei gleichem
Zeitpunkt sortiert nach Audit-`id`. `old_status` dokumentiert den bei
Erstellung bekannten Zustand, wird aber nicht zur Minutenberechnung benutzt.
So bleibt ein rückdatiertes Audit unveränderlich und die Berechnung folgt
allein der Ereignisreihenfolge.

`UNIQUE(student_id, block_plan_id)` erlaubt genau einen Reset-Audit je
Schüler und Blockplan. MySQL lässt weiterhin mehrere normale Audits zu, weil
deren `block_plan_id` `NULL` ist.

### `raw_scan`

Minimaler unveränderter Eingang eines parsebaren Scanauftrags. Er bleibt von
Attendance und Audit getrennt, weil seine technische Aufbewahrungsfrist
kürzer ist.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `scan_id` | `CHAR(36)` | primary key, UUIDv4 from JavaFX | `00000000-0000-4000-8000-000000000001` |
| `rfid_uid` | `VARCHAR(64)` | not null, plaintext | `TEST-UID-001` |
| `scanned_at` | `DATETIME` | not null, unchanged JavaFX UTC instant | `2026-10-12 05:45:12` |
| `created_at` | `DATETIME` | not null, one backend receipt instant in UTC | `2026-10-12 05:45:13` |

Der Rohscan enthält bewusst keine Terminalreferenz. Terminal-ID, mTLS-Identität
und persistierte Zuordnung werden ausschließlich während der Fachvalidierung
verglichen. Ein Ergebniscode oder Ablehnungsgrund gehört ebenfalls nicht in den
Rohscan, weil er erst aus der Fachlogik entsteht.

Das Backend prüft vor der Fachlogik den Primärschlüssel. Existiert die Scan-ID
bereits, endet der Retry neutral mit `SCAN_ALREADY_RECEIVED`; Attendance,
Audit und Konten bleiben unverändert. Der erste Rohscan und alle fachlichen
Änderungen werden in einer gemeinsamen Transaktion erst am Ende committed.
Ein separater früher Commit wäre falsch, weil ein Absturz dann einen Rohscan
ohne abgeschlossene Fachverarbeitung hinterließe und der Retry blockiert wäre.

## Verarbeitung

### Scan

1. JavaFX erzeugt UUIDv4 und UTC-Scanzeit und sendet sie mit UID und
   Terminal-ID per HTTPS/mTLS.
2. Das Backend lehnt vorhandene Scan-IDs vor der Fachlogik neutral ab und
   legt sonst den `raw_scan` innerhalb der gemeinsamen Transaktion an.
3. Persistiertes Terminal und verifizierte Clientidentität müssen zur
   gemeldeten Nummer passen. Der Terminalraum bestimmt den Scanraum.
4. Die Scanzeit muss in einem Slot liegen. Der Slot, seine aktive
   Blockzuordnung und die aktuelle Schülerklasse müssen zusammenpassen.
5. Nur `ABWESEND` oder `BETRIEBLICH_ENTSCHULDIGT` darf der Scan auf
   `ANWESEND` setzen. Jeder andere Status, einschließlich `ANWESEND`, wird
   abgelehnt; Schüler dürfen ihren Status nicht selbst korrigieren.
6. Attendance, Audit und Kontodelta werden durch den gemeinsamen Abgleich
   gespeichert. Das Terminal erhält nur eine neutrale Antwort ohne UID,
   Namen, Klasse oder Status.

Scans in Pausen, außerhalb eines Slots, in einem falschen Raum oder ohne
eindeutige aktive Zuordnung sind ungültig. `scanned_at` darf nicht nach dem
Backend-Eingangszeitpunkt liegen.

### Statuswechsel und Minutenabgleich

Lehrkräfte dürfen einen Status jederzeit übermitteln. `occurred_at` muss im
betroffenen lokalen Kalendertag liegen und darf nicht in der Zukunft liegen.
Ein Ereignis in einer Pause ist erlaubt, erzeugt für die Pause aber keine
Minuten.

Jeder Scan, Lehrerwechsel und Tagesabschluss verwendet dieselbe
transaktionale Abgleichsfunktion:

1. Attendance und Student sperren.
2. Statusereignisse nach `occurred_at`, dann Audit-`id`, laden. Vor dem ersten
   Ereignis gilt `ABWESEND`.
3. Abrechnungsgrenze als Minimum aus Backend-Zeitpunkt und Ende des letzten
   Tagesslots bestimmen.
4. Statusintervalle mit den Slots des Tages schneiden. Künftige Slotanteile
   bleiben ungebucht.
5. Ungerundete Anteile je Konto über alle Slots addieren und erst die
   jeweilige Tagessumme auf volle Minuten abrunden. Dadurch bleiben
   Teilminuten zwischen Slots erhalten.
6. Bereits auditierte Deltas des Tages als Istwert summieren und nur
   `Soll - Ist` buchen.
7. `attendance.status` auf den letzten wirksamen Tagesstatus setzen und bei
   Statuswechsel oder Delta ungleich `0` ein Audit anlegen.

Beispiel: Slots `07:30–09:00` und `09:15–10:45`, erster Scan `09:15`.
Es entstehen `90` unentschuldigte Minuten; die Pause zählt nicht. Wird der
Schüler im letzten Slot ab `10:00` entschuldigt, zählt nur dessen betroffener
Slotanteil zum entschuldigten Konto. Ein kompletter Wechsel des letzten Slots
auf abwesend oder entschuldigt bucht dessen volle Dauer.

Ein wiederholter Job oder Request berechnet dasselbe Soll. Da nur die
Differenz zu bestehenden Audit-Deltas gebucht wird, entsteht keine
Doppelbuchung. Rückdatierte Änderungen rechnen den bereits vergangenen
Tagesteil, bei vergangenen Tagen den ganzen Tag neu; bestehende Audits werden
nicht verändert.

### Tagesabschluss

Der Tagesabschluss läuft je `block_assignment` und Datum nach deren letztem
Slot. Für jeden zugehörigen Schüler mit Unterrichtszeit wird bei Bedarf eine
Attendance angelegt. Ohne Statusereignis gilt der ganze Tag als `ABWESEND` und
alle geplanten Minuten werden unentschuldigt gebucht.

Ein Tag ist offen, wenn eine erwartete Attendance fehlt oder Soll und
auditierte Deltas abweichen. Der regelmäßige und beim Start ausgeführte
Nachholjob verarbeitet nur offene, bereits beendete Tage. Es gibt keinen
separaten Abschlussmarker: Der Soll-Ist-Abgleich ist selbst idempotent und
verhindert einen zweiten Kontoeffekt.

### Kontenreset und Retention

Ein Administrator startet den Reset ausdrücklich für eine Klasse und einen
Blockplan. Es gibt keinen automatischen Reset, keinen berechneten
Resetzeitpunkt und keinen Reset-Nachholjob.

Der Reset ist erst erlaubt, wenn alle Blockzuordnungen dieser Klasse im
gewählten Plan beendet sind. Das Backend ermittelt die Schüler über ihre
Attendances und verarbeitet zuerst alle offenen Tage des Plans. Danach sperrt
es die betroffenen Schüler und setzt ihre beiden Konten in einer Transaktion
auf `0`. Je Schüler entsteht ein `attendance_audit` mit `source = STAFF`, dem
auslösenden `changed_by_staff_id`, gesetzter `block_plan_id`, leerer
`attendance_id` und negativen bisherigen Kontoständen. Bei bereits leeren
Konten sind beide Deltas `0`; der Audit bleibt als notwendiger Resetmarker.
Nach dem Reset sind Attendances dieses Plans nicht mehr korrigierbar, sonst
könnten alte Deltas wieder in das geleerte Konto gelangen.

Der manuelle Reset ist Voraussetzung für die nächste Blockzuordnung. Das
Backend erkennt ihn über genau einen Reset-Audit je Schüler und Blockplan.

Rohscans werden `14` Tage nach `raw_scan.created_at` gelöscht. Am Kalendertag
`DATE_ADD(block_plan.ends_on, INTERVAL 6 MONTH)` um `00:00 Europe/Berlin`
löscht der automatische Retention-Job dessen Attendance-Audits, Attendances
und Blockzuordnungen. Er setzt keine Zeitkonten zurück und läuft unabhängig
vom manuellen Reset, damit die Löschfrist nicht von einer Bedienhandlung
abhängt. Ein fehlender Reset wird als Betriebsfehler protokolliert, aber nicht
automatisch nachgeholt. Blockplan und Stundenpläne werden erst gelöscht, wenn
keine aufzubewahrende Zuordnung mehr auf sie verweist.
Unreferenzierte alte Klassen werden danach gelöscht. Schüler, Staff,
Terminals und Räume bleiben erhalten. Kinder werden kontrolliert vor ihren
Eltern gelöscht; ungeplante Hard-Deletes bleiben durch Foreign Keys gesperrt.

## Required Indexes and Validation

| Area | Rule |
| --- | --- |
| Class identity | `UNIQUE(school_class.class_code, school_class.school_year)` |
| UID | `UNIQUE(student.rfid_uid)` |
| Terminal | primary key `terminal.terminal_id`; `UNIQUE(terminal.room_id)` |
| Teacher assignment | primary key `(teacher_class.staff_id, teacher_class.school_class_id)` |
| Scan idempotency | primary key `raw_scan.scan_id` |
| Daily attendance | `UNIQUE(attendance.student_id, attendance.attendance_date)` |
| Attendance lookup | `INDEX(attendance.block_assignment_id, attendance.attendance_date)` |
| Audit reconciliation | `INDEX(attendance_audit.attendance_id)`, `INDEX(attendance_audit.occurred_at)` |
| Reset audit | `UNIQUE(attendance_audit.student_id, attendance_audit.block_plan_id)`; MySQL permits multiple normal audit rows because their `block_plan_id` is `NULL` |
| Assignment validity | Backend rejects dates outside the block plan and overlapping assignments for class, timetable or student. |
| Slot validity | Database checks `end_time > start_time`; backend rejects overlaps in one timetable. |
| Room resolution | Backend rejects same-room slot overlaps across simultaneously active assignments. |
| Audit source | Backend enforces source-specific Staff/Terminal references and their mutual exclusion. |
| Audit content | Backend enforces status-pair consistency; only a Reset-Audit may have no status change and two zero deltas. |
| Minute accounts | Database checks both accounts `>= 0`; application locks and updates accounts with audit in one transaction. |
| Reset boundary | Backend rejects corrections after the student's manual plan reset. |

Intervall- und tabellenübergreifende Regeln liegen bewusst im Backend, weil
MySQL-Checks keine konkurrierenden Zeilen zuverlässig vergleichen. Die
betroffenen Planungszeilen werden beim Prüfen gesperrt, damit parallele
Requests keine Überschneidung einschleusen.

## Creation Order

1. `staff`, `room` und `terminal` anlegen.
2. `school_class` mit Schuljahr und Klassenlehrer anlegen; bei Bedarf
   `teacher_class` ergänzen.
3. `student` anlegen und seiner aktuellen Klasse zuordnen.
4. `timetable` und seine nichtüberlappenden `timetable_slot`-Einträge anlegen.
5. `block_plan` anlegen.
6. `block_assignment` innerhalb des Plans anlegen und alle Zeit- und
   Raumkonflikte prüfen.
7. `attendance`, `attendance_audit` und `raw_scan` entstehen durch Scan,
   Lehreraktion oder Tagesabschluss; Benutzer legen sie nicht direkt an.
8. Ein Administrator erzeugt über den klassenweisen Reset genau einen
   Reset-Audit je Schüler und Blockplan.

## Relationship Summary

- `room 1 — 0..1 terminal`
- `room 1 — N timetable_slot`
- `timetable 1 — N timetable_slot`
- `block_plan 1 — N block_assignment`
- `school_class 1 — N block_assignment`
- `timetable 1 — N block_assignment`
- `block_assignment 1 — N attendance`
- `school_class 1 — N student`
- `student 1 — N attendance`
- `attendance 0..1 — N attendance_audit`; Reset-Audits besitzen keine
  Attendance.
- `block_plan 0..1 — N attendance_audit`; nur Reset-Audits nutzen diese
  Beziehung.
- `student 1 — N attendance_audit`
- `staff 1 — N attendance_audit` für Quelle `STAFF`
- `terminal 1 — N attendance_audit` für Quelle `TERMINAL`
- `staff N — M school_class` über `teacher_class`; zusätzlich besitzt jede
  Klasse genau einen `class_teacher_id`.
