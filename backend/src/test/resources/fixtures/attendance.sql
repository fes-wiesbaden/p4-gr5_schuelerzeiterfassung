-- Issue #19
SET @fixture_created_at = '2026-09-01 06:00:00';

INSERT INTO staff (id, first_name, last_name, username, password_hash, role, created_at, changed_at) VALUES
    (1, 'Test', 'Lehrkraft-A', 'test.teacher.a', '$2b$12$.rLwXM6vtugSfHeayUVZX.1JUG5mYV0G/t5Gwc.Z9VJJ.Hjphpx/K', 'LEHRKRAFT', @fixture_created_at, @fixture_created_at),
    (2, 'Test', 'Lehrkraft-B', 'test.teacher.b', '$2b$12$.rLwXM6vtugSfHeayUVZX.1JUG5mYV0G/t5Gwc.Z9VJJ.Hjphpx/K', 'LEHRKRAFT', @fixture_created_at, @fixture_created_at),
    (3, 'Test', 'Administrator', 'test.admin', '$2b$12$.rLwXM6vtugSfHeayUVZX.1JUG5mYV0G/t5Gwc.Z9VJJ.Hjphpx/K', 'ADMINISTRATOR', @fixture_created_at, @fixture_created_at);

INSERT INTO timetable (id, name, created_at, changed_at) VALUES
    (1, 'Test-Stundenplan-A', @fixture_created_at, @fixture_created_at),
    (2, 'Test-Stundenplan-B', @fixture_created_at, @fixture_created_at);

INSERT INTO block_plan (id, name, starts_on, ends_on, created_at, changed_at) VALUES
    (1, 'Test-Blockplan-2026', '2026-09-01', '2026-12-31', @fixture_created_at, @fixture_created_at);

INSERT INTO deletion_date (id, delete_after) VALUES (1, '2027-06-30');

INSERT INTO school_class (id, class_code, class_teacher_id, block_plan_id, timetable_id, created_at, changed_at) VALUES
    (1, '26TEST-A', 1, 1, 1, @fixture_created_at, @fixture_created_at),
    (2, '26TEST-B', 2, 1, 2, @fixture_created_at, @fixture_created_at),
    (3, '26TEST-C', 1, 1, 1, @fixture_created_at, @fixture_created_at);

INSERT INTO teacher_class (staff_id, school_class_id) VALUES (1, 1), (2, 2), (1, 3);

INSERT INTO room (id, room_number, created_at, changed_at) VALUES
    (1, 'TEST-R01', @fixture_created_at, @fixture_created_at),
    (2, 'TEST-R02', @fixture_created_at, @fixture_created_at);

INSERT INTO terminal (terminal_id, room_id, created_at, changed_at) VALUES
    (9001, 1, @fixture_created_at, @fixture_created_at),
    (9002, 2, @fixture_created_at, @fixture_created_at);

-- Jeder Slot dauert 90 Minuten = zwei Schulstunden; die 15-minütige Pause zählt nicht.
INSERT INTO timetable_slot (id, timetable_id, room_id, weekday, start_time, end_time, created_at, changed_at) VALUES
    (1, 1, 1, 'MONTAG', '07:30:00', '09:00:00', @fixture_created_at, @fixture_created_at),
    (2, 1, 1, 'MONTAG', '09:15:00', '10:45:00', @fixture_created_at, @fixture_created_at),
    (3, 2, 2, 'MONTAG', '07:30:00', '09:00:00', @fixture_created_at, @fixture_created_at),
    (4, 2, 2, 'MONTAG', '09:15:00', '10:45:00', @fixture_created_at, @fixture_created_at);

INSERT INTO block_assignment (id, block_plan_id, starts_on, ends_on, created_at, changed_at) VALUES
    (1, 1, '2026-09-14', '2026-09-18', @fixture_created_at, @fixture_created_at),
    (2, 1, '2026-09-21', '2026-09-25', @fixture_created_at, @fixture_created_at);

-- A und B nutzen in Block 1 getrennte Räume; C nutzt den Stundenplan von A erst in Block 2.
INSERT INTO class_block_assignment (school_class_id, block_assignment_id) VALUES (1, 1), (2, 1), (3, 2);

INSERT INTO student (id, first_name, last_name, birth_date, school_class_id, rfid_uid,
                     unexcused_minutes_account, excused_minutes_account, created_at, changed_at) VALUES
    (1, 'Test', 'Ohne-Scan', '2008-01-01', 1, 'TEST-UID-001', 180, 0, @fixture_created_at, '2026-09-14 08:45:00'),
    (2, 'Test', 'Verspaetet', '2008-01-01', 1, 'TEST-UID-002', 90, 0, @fixture_created_at, '2026-09-14 07:15:00'),
    (3, 'Test', 'Statuswechsel', '2008-01-01', 1, 'TEST-UID-003', 150, 0, @fixture_created_at, '2026-09-14 08:45:00'),
    (4, 'Test', 'Teilentschuldigung', '2008-01-01', 1, 'TEST-UID-004', 30, 60, @fixture_created_at, '2026-09-14 07:30:00'),
    (5, 'Test', 'Bereits-Anwesend', '2008-01-01', 1, 'TEST-UID-005', 0, 0, @fixture_created_at, '2026-09-14 05:30:00'),
    (6, 'Test', 'Retry', '2008-01-01', 1, 'TEST-UID-006', 0, 0, @fixture_created_at, '2026-09-14 05:30:00'),
    (7, 'Test', 'Raum-B', '2008-01-01', 2, 'TEST-UID-007', 180, 0, @fixture_created_at, '2026-09-14 08:45:00'),
    (8, 'Test', 'Block-C', '2008-01-01', 3, 'TEST-UID-008', 30, 0, @fixture_created_at, '2026-09-21 06:00:00');

INSERT INTO attendance (id, student_id, attendance_date, status, created_at, changed_at) VALUES
    (1, 1, '2026-09-14', 'ABWESEND', '2026-09-14 08:45:00', '2026-09-14 08:45:00'),
    (2, 2, '2026-09-14', 'ANWESEND', '2026-09-14 07:15:00', '2026-09-14 07:15:00'),
    (3, 3, '2026-09-14', 'ABWESEND', '2026-09-14 05:30:00', '2026-09-14 08:45:00'),
    (4, 4, '2026-09-14', 'ANWESEND', '2026-09-14 07:15:00', '2026-09-14 07:30:00'),
    (5, 5, '2026-09-14', 'ANWESEND', '2026-09-14 05:30:00', '2026-09-14 05:30:00'),
    (6, 6, '2026-09-14', 'ANWESEND', '2026-09-14 05:30:00', '2026-09-14 05:30:00'),
    (7, 7, '2026-09-14', 'ABWESEND', '2026-09-14 08:45:00', '2026-09-14 08:45:00'),
    (8, 8, '2026-09-21', 'ANWESEND', '2026-09-21 06:00:00', '2026-09-21 06:00:00');

INSERT INTO attendance_audit (id, student_id, attendance_id, deletion_date_id, event_type,
                              changed_by_staff_id, terminal_id, old_status, new_status, occurred_at,
                              unexcused_minutes_delta, excused_minutes_delta, created_at) VALUES
    (1, 1, 1, 1, 'DAILY_CLOSE', NULL, NULL, NULL, NULL, '2026-09-14 08:45:00', 180, 0, '2026-09-14 08:45:00'),
    (2, 2, 2, 1, 'SCAN', NULL, 9001, 'ABWESEND', 'ANWESEND', '2026-09-14 07:15:00', 90, 0, '2026-09-14 07:15:01'),
    (3, 3, 3, 1, 'SCAN', NULL, 9001, 'ABWESEND', 'ANWESEND', '2026-09-14 05:30:00', 0, 0, '2026-09-14 05:30:01'),
    (4, 3, 3, 1, 'STATUS_CHANGE', 1, NULL, 'ANWESEND', 'ABWESEND', '2026-09-14 06:00:00', 0, 0, '2026-09-14 06:00:00'),
    (5, 3, 3, 1, 'DAILY_CLOSE', NULL, NULL, NULL, NULL, '2026-09-14 08:45:00', 150, 0, '2026-09-14 08:45:00'),
    (6, 4, 4, 1, 'SCAN', NULL, 9001, 'ABWESEND', 'ANWESEND', '2026-09-14 07:15:00', 90, 0, '2026-09-14 07:15:01'),
    -- Um 09:30 Berliner Zeit rückwirkend 08:00–09:00 entschuldigen; der Scan um 09:15 bleibt das letzte wirksame Statusereignis.
    (7, 4, 4, 1, 'STATUS_CHANGE', 1, NULL, 'ANWESEND', 'ENTSCHULDIGT', '2026-09-14 06:00:00', -60, 60, '2026-09-14 07:30:00'),
    (8, 5, 5, 1, 'SCAN', NULL, 9001, 'ABWESEND', 'ANWESEND', '2026-09-14 05:30:00', 0, 0, '2026-09-14 05:30:01'),
    (9, 6, 6, 1, 'SCAN', NULL, 9001, 'ABWESEND', 'ANWESEND', '2026-09-14 05:30:00', 0, 0, '2026-09-14 05:30:01'),
    (10, 7, 7, 1, 'DAILY_CLOSE', NULL, NULL, NULL, NULL, '2026-09-14 08:45:00', 180, 0, '2026-09-14 08:45:00'),
    (11, 8, 8, 1, 'SCAN', NULL, 9001, 'ABWESEND', 'ANWESEND', '2026-09-21 06:00:00', 30, 0, '2026-09-21 06:00:01');

INSERT INTO raw_scan (scan_id, rfid_uid, scanned_at, created_at) VALUES
    ('00000000-0000-4000-8000-000000000002', 'TEST-UID-002', '2026-09-14 07:15:00', '2026-09-14 07:15:01'),
    ('00000000-0000-4000-8000-000000000003', 'TEST-UID-003', '2026-09-14 05:30:00', '2026-09-14 05:30:01'),
    ('00000000-0000-4000-8000-000000000004', 'TEST-UID-004', '2026-09-14 07:15:00', '2026-09-14 07:15:01'),
    ('00000000-0000-4000-8000-000000000005', 'TEST-UID-005', '2026-09-14 05:30:00', '2026-09-14 05:30:01'),
    -- Neue Scan-ID bei bereits anwesendem Schüler: nur Rohscan, kein weiteres Audit und keine Kontobuchung.
    ('00000000-0000-4000-8000-000000000015', 'TEST-UID-005', '2026-09-14 05:45:00', '2026-09-14 05:45:01'),
    -- Wiederholung mit derselben Scan-ID und demselben Inhalt; es entsteht kein zweiter Datensatz.
    ('00000000-0000-4000-8000-000000000006', 'TEST-UID-006', '2026-09-14 05:30:00', '2026-09-14 05:30:01'),
    ('00000000-0000-4000-8000-000000000008', 'TEST-UID-008', '2026-09-21 06:00:00', '2026-09-21 06:00:01');
