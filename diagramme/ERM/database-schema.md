# Database Schema

Dieses Dokument beschreibt das geplante MySQL-8-Schema. Tabellen- und Spaltennamen sind Englisch. Fachliche Werte und Beispieldaten sind Deutsch. Alle Beispiele sind fiktiv; insbesondere ist keine RFID-UID echt.

## Grundregeln

- Eine RFID-UID steht direkt in `student.rfid_uid`. Es gibt keine `card`-Tabelle und keine UID-Historie. Eine neue UID überschreibt die alte UID.
- Eine `teaching_unit` ist ein geplanter Unterrichtstag von Montag bis Freitag für eine Klasse im aktiven Block. Sie entsteht nur, wenn der Stundenplan an diesem Tag mindestens einen Slot enthält, und umfasst alle Doppelstunden sowie Raumwechsel dieses Tages.
- Der aktive Raum eines Scans wird über `terminal -> room -> timetable_slot -> timetable -> block_assignment` bestimmt. Ein Scan in einem späteren Raum aktualisiert dieselbe Tagesanwesenheit.
- Rohscans und fachliche Anwesenheiten bleiben getrennt. Rohscans werden nach 14 Tagen gelöscht. Anwesenheiten und zugehörige Audits werden sechs Monate nach dem Ende ihres `block_plan` automatisch gelöscht.

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
| `created_at` | `DATETIME` | not null | `2026-09-01 08:00:00` |

Der Benutzername wird aus `vorname.nachname` gebildet. Bei einer Kollision erhält er einen numerischen Suffix, zum Beispiel `anna.muster2`.

### `school_class`

Stammdaten einer Schulklasse. Jede Klasse hat genau einen Klassenlehrer. Der Stundenplan wird über die aktive Blockzuordnung bestimmt, nicht direkt an der Klasse gespeichert.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `13` |
| `class_code` | `VARCHAR(30)` | not null, unique | `10BE13` |
| `display_name` | `VARCHAR(120)` | not null | `Fachinformatik AE 10BE13` |
| `class_teacher_id` | `BIGINT` | not null, foreign key to `staff.id`; referenced staff member must have role `LEHRKRAFT` | `1` |
| `created_at` | `DATETIME` | not null | `2026-09-01 08:00:00` |

Der Klassenlehrer darf Schüler seiner Klasse anlegen und löschen. Administratoren dürfen dies für alle Klassen. Die Rollenprüfung erfolgt serverseitig.

### `student`

Schüler gehören direkt zu einer Klasse. Das Geburtsdatum dient neben Vor- und Nachnamen zur Unterscheidung gleichnamiger Schüler. Es ist nicht eindeutig; die technische Identität bleibt `student.id`. Die UID ist eindeutig und wird beim Ersatz unmittelbar überschrieben.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `101` |
| `first_name` | `VARCHAR(100)` | not null | `Erika` |
| `last_name` | `VARCHAR(100)` | not null | `Beispiel` |
| `birth_date` | `DATE` | not null | `2008-05-14` |
| `school_class_id` | `BIGINT` | not null, foreign key to `school_class.id` | `13` |
| `rfid_uid` | `VARCHAR(64)` | nullable, unique, plaintext | `TEST-UID-001` |
| `created_at` | `DATETIME` | not null | `2026-09-01 08:05:00` |

### `teacher_class`

M:N-Zuordnung zwischen Lehrkräften und Klassen für Unterricht und Planung. Sie ersetzt nicht die Klassenlehrer-Zuordnung und verleiht keine Rechte zum Anlegen oder Löschen von Schülern. Administratoren benötigen keine Zuordnung.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `staff_id` | `BIGINT` | primary key part, foreign key to `staff.id`; role must be `LEHRKRAFT` | `1` |
| `school_class_id` | `BIGINT` | primary key part, foreign key to `school_class.id` | `13` |

Der zusammengesetzte Primärschlüssel ist `(staff_id, school_class_id)`.

### `room`

Stammdaten der Unterrichtsräume.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `2` |
| `room_number` | `VARCHAR(30)` | not null, unique | `A123` |
| `description` | `VARCHAR(255)` | nullable | `Informatikraum` |

### `terminal`

Ein RFID-Terminal ist dauerhaft genau einem Raum zugeordnet. Die gemeldete Terminalnummer ist kein Geheimnis, aber eindeutig.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `3` |
| `terminal_number` | `INT` | not null, unique | `3` |
| `room_id` | `BIGINT` | not null, foreign key to `room.id` | `2` |
| `created_at` | `DATETIME` | not null | `2026-09-01 08:10:00` |

### `timetable`

Wiederverwendbarer Stundenplan für eine Fachrichtung oder mehrere Klassen mit gleichem Wochenrhythmus.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `1` |
| `name` | `VARCHAR(150)` | not null | `Stundenplan Fachinformatik AE 2026/27` |
| `valid_from` | `DATE` | not null | `2026-08-17` |
| `valid_to` | `DATE` | not null, must be on or after `valid_from` | `2027-07-16` |

Jede zugehörige `block_assignment` muss vollständig innerhalb von `valid_from` und `valid_to` liegen.

### `timetable_slot`

Eine wiederkehrende Doppelstunde. Jeder Slot enthält den Raum, damit eine Klasse im Tagesverlauf den Raum wechseln kann.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `12` |
| `timetable_id` | `BIGINT` | not null, foreign key to `timetable.id` | `1` |
| `room_id` | `BIGINT` | not null, foreign key to `room.id` | `2` |
| `weekday` | `ENUM('MONTAG', 'DIENSTAG', 'MITTWOCH', 'DONNERSTAG', 'FREITAG')` | not null | `MONTAG` |
| `start_time` | `TIME` | not null | `07:30:00` |
| `end_time` | `TIME` | not null, must be after `start_time` | `09:00:00` |

Beispiel für einen Raumwechsel: `10BE13` nutzt montags `A123` von `07:30` bis `09:00` und `B203` von `09:15` bis `10:45`. Danach darf eine andere Klasse `A123` verwenden.

### `block_plan`

Ein benannter Blockplan für ein Schuljahr und eine Fachrichtung. Mehrere Klassen können darin parallel mit eigenen Stundenplänen aktiv sein.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `1` |
| `name` | `VARCHAR(150)` | not null | `Blockplan Fachinformatik AE 2026/27` |
| `school_year` | `VARCHAR(9)` | not null | `2026/2027` |
| `program_name` | `VARCHAR(150)` | not null | `Fachinformatik Anwendungsentwicklung` |
| `ends_on` | `DATE` | not null | `2027-07-16` |

`ends_on` ist der verbindliche Löschanker: Sechs Monate danach löscht ein serverseitiger Lauf die Anwesenheiten und Audits aller zugehörigen Blockzuordnungen.
### `block_assignment`

Aktiviert eine Klasse in einem Blockplan für einen Zeitraum und ordnet ihr einen Stundenplan zu. Der Raum steht je Doppelstunde in `timetable_slot`.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `4` |
| `block_plan_id` | `BIGINT` | not null, foreign key to `block_plan.id` | `1` |
| `school_class_id` | `BIGINT` | not null, foreign key to `school_class.id` | `13` |
| `timetable_id` | `BIGINT` | not null, foreign key to `timetable.id` | `1` |
| `starts_on` | `DATE` | not null | `2026-09-07` |
| `ends_on` | `DATE` | not null, must be on or after `starts_on` | `2026-10-02` |

Eine Klasse kann in einem Plan mehrere Zeiträume besitzen. Sie darf nach einem Zwischenblock erneut eingeplant werden.

### `teaching_unit`

Die konkrete Anwesenheitseinheit für einen geplanten Unterrichtstag von Montag bis Freitag. Sie wird beim Speichern der Planung aus Blockzuordnung und Stundenplan erzeugt, sofern mindestens ein Slot für diesen Tag existiert. Sie enthält keinen Raum, weil ein Tag mehrere Räume enthalten kann.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `248` |
| `block_assignment_id` | `BIGINT` | not null, foreign key to `block_assignment.id` | `4` |
| `unit_date` | `DATE` | not null | `2026-09-07` |
| `planned_start` | `DATETIME` | not null | `2026-09-07 07:30:00` |
| `created_at` | `DATETIME` | not null | `2026-09-01 09:00:00` |

`UNIQUE(block_assignment_id, unit_date)` verhindert doppelte Tagesunterrichtseinheiten derselben Blockzuordnung.

### `attendance`

Fachlicher Anwesenheitsstatus eines Schülers für eine Tagesunterrichtseinheit.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `900` |
| `student_id` | `BIGINT` | not null, foreign key to `student.id` | `101` |
| `teaching_unit_id` | `BIGINT` | not null, foreign key to `teaching_unit.id` | `248` |
| `status` | `ENUM('ANWESEND', 'ABWESEND', 'ENTSCHULDIGT', 'MIT_ATTEST_ENTSCHULDIGT', 'BETRIEBLICH_ENTSCHULDIGT', 'BETRIEB')` | not null | `ANWESEND` |
| `first_scanned_at` | `DATETIME` | nullable | `2026-09-07 09:15:12` |
| `lateness_minutes` | `INT` | not null, default `0`, must be at least `0` | `105` |

`UNIQUE(student_id, teaching_unit_id)` verhindert doppelte Anwesenheiten. Beim Erzeugen der `teaching_unit` erhalten alle Schüler der zugehörigen Klasse zunächst `ABWESEND`. Schüler werden vor Blockbeginn angelegt und erhalten für künftige Einheiten ihrer Klasse ebenfalls diesen Status. Ein Scan um `09:15` setzt den Status derselben Einheit auf `ANWESEND`; die Verspätung wird ab der ersten geplanten Stunde berechnet.

### `attendance_audit`

Unveränderbares Protokoll jeder manuellen Statusänderung.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `77` |
| `attendance_id` | `BIGINT` | not null, foreign key to `attendance.id` | `900` |
| `changed_by_staff_id` | `BIGINT` | not null, foreign key to `staff.id` | `1` |
| `old_status` | same enum as `attendance.status` | not null | `ABWESEND` |
| `new_status` | same enum as `attendance.status` | not null | `ENTSCHULDIGT` |
| `changed_at` | `DATETIME` | not null | `2026-09-08 10:00:00` |

Die Anwendung erlaubt weder Updates noch einzelne Deletes von Audit-Einträgen. Ein serverseitiger Löschlauf entfernt sie sechs Monate nach `block_plan.ends_on` zusammen mit der zugehörigen Anwesenheit.

### `raw_scan`

Technische Rohdaten des ersten empfangenen Scans je Scan-ID, auch bei unbekanntem Terminal oder unbekannter UID. Doppelte Übertragungen derselben Scan-ID erhöhen den Zähler im vorhandenen Datensatz. `reported_terminal_number` ist bewusst kein Fremdschlüssel: Auch unbekannte Terminalnummern müssen gespeichert werden können.

| Column | MySQL type | Rules | Example |
| --- | --- | --- | --- |
| `id` | `BIGINT` | primary key, auto increment | `830` |
| `scan_id` | `CHAR(36)` | not null, unique | `00000000-0000-0000-0000-000000000001` |
| `rfid_uid` | `VARCHAR(64)` | not null, plaintext | `TEST-UID-001` |
| `reported_terminal_number` | `INT` | not null | `3` |
| `received_at` | `DATETIME` | not null, server timestamp | `2026-09-07 09:15:13` |
| `last_received_at` | `DATETIME` | not null, server timestamp of the latest delivery | `2026-09-07 09:15:13` |
| `duplicate_count` | `INT` | not null, default `0`, must be at least `0` | `0` |
| `processing_result` | `ENUM('VERARBEITET', 'ABGELEHNT')` | not null | `VERARBEITET` |
| `rejection_reason` | `VARCHAR(100)` | nullable | `KEINE_UNTERRICHTSEINHEIT` |

Rohscans werden anhand von `received_at` nach 14 Tagen gelöscht. Sie werden nie an die Terminaloberfläche zurückgegeben.

## Required Indexes and Validation

| Area | Rule |
| --- | --- |
| UID | `UNIQUE(student.rfid_uid)` |
| Terminal | `UNIQUE(terminal.terminal_number)` |
| Scan idempotency | `UNIQUE(raw_scan.scan_id)` |
| Attendance | `UNIQUE(attendance.student_id, attendance.teaching_unit_id)` |
| Teacher assignment | primary key `(teacher_class.staff_id, teacher_class.school_class_id)` |
| Class teacher | `school_class.class_teacher_id` references `staff.id`; the referenced staff member has role `LEHRKRAFT` and is protected by `ON DELETE RESTRICT` |
| Timetable validity | Backend rejects a `block_assignment` outside the date range of its assigned `timetable`. |
| Planning | Backend rejects overlapping slot intervals `[start_time, end_time)` for the same room when the affected `block_assignment` periods overlap. |
| Class planning | Backend rejects overlapping slot intervals `[start_time, end_time)` for the same class when its `block_assignment` periods overlap. |
| Teaching unit | `UNIQUE(teaching_unit.block_assignment_id, teaching_unit.unit_date)` |
| Historical deletion | All historical foreign keys use `ON DELETE RESTRICT`. A server-side retention job selects expired `block_plan` records, then deletes `attendance_audit` before `attendance` in one transaction. Plans, assignments, and teaching units remain. |

## Scan Resolution Example

1. Terminal `3` belongs to room `A123`.
2. At Monday `09:15`, the active `timetable_slot` for `A123` identifies a `timetable`.
3. The timetable identifies the active `block_assignment` and therefore class `10BE14`.
4. The `block_assignment` identifies the `teaching_unit` of `10BE14` for that date.
5. The backend finds the `student` by `rfid_uid` and verifies the class.
6. The existing `attendance` of that student and day is updated. A second scan changes no attendance record.

## Scan Processing Flow

- ESP32 sendet per HTTPS: ungehashte UID, Terminalnummer und eindeutige Scan-ID.
- Backend prüft `raw_scan.scan_id`.
  - Erster Empfang: `raw_scan` mit UID, Terminalnummer und einmalig erzeugter Serverzeit `received_at` speichern.
  - Duplikat: Anwesenheit unverändert lassen; `duplicate_count` erhöhen und `last_received_at` aktualisieren.
- Terminalnummer über `terminal.terminal_number` in einen Raum auflösen.
  - Unbekanntes Terminal: Scan ablehnen, keine Anwesenheit erzeugen.
- Passenden `timetable_slot` für Raum, Wochentag und Serverzeit `received_at` mit `start_time <= scan_time < end_time` suchen.
	- In einer Lücke zwischen Slots, bei Freistunden, vor der ersten und nach der letzten Stunde: Scan ablehnen.
- Über `timetable`, `block_plan` und aktive `block_assignment` die aktuelle Klasse bestimmen.
  - Keine aktive Zuordnung: Scan ablehnen.
- Schüler über `student.rfid_uid` suchen und Klassenzugehörigkeit prüfen.
  - Unbekannte UID oder falsche Klasse: Scan ablehnen.
- Tages-`teaching_unit` der Klasse bestimmen.
	- Beginnt mit der ersten geplanten Stunde.
	- Umfasst spätere Raumwechsel; Scans in Pausen oder Freistunden bleiben ungültig.
- Vorhandenen `attendance`-Datensatz aktualisieren.
  - Erster gültiger Scan: `ABWESEND` auf `ANWESEND` setzen.
  - `first_scanned_at` auf `received_at` setzen.
  - `lateness_minutes` ab `teaching_unit.planned_start` bis `received_at` in vollen Minuten berechnen.
  - Weitere gültige Scans: keine zweite Anwesenheit erzeugen.
- Terminal erhält nur neutrale Erfolg- oder Fehlermeldung; niemals UID, Name, Klasse oder Status.

### Example: Late Scan After a Room Change

- Schüler: `Erika Beispiel`, Klasse: `10BE13`.
- Stundenplan am Montag:
  - `07:30–09:00`: Raum `A123`.
  - `09:15–10:45`: Raum `B203`.
- Scan: `09:20` am Terminal in `B203`.
- Ermittlung: Raum `B203` und Uhrzeit `09:20` ergeben Klasse `10BE13`.
- Tages-`teaching_unit`: Beginn `07:30`.
- Ergebnis:
  - Anwesenheit wird `ANWESEND`.
  - `lateness_minutes` wird `110`.
  - Ein weiterer Scan am selben Tag erzeugt keinen zweiten Anwesenheitsdatensatz.
