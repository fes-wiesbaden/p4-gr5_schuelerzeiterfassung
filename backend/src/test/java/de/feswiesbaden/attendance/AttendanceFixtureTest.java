package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@Transactional
@Sql("/fixtures/attendance.sql")
class AttendanceFixtureTest {
  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @Autowired JdbcTemplate jdbc;

  @Test
  void schedulesHaveNinetyMinuteSlotsAndSeparatedBlocks() {
    assertThat(count("SELECT COUNT(*) FROM school_class")).isEqualTo(3);
    assertThat(count("SELECT COUNT(*) FROM room")).isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM timetable")).isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM block_assignment")).isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM timetable_slot")).isEqualTo(4);
    assertThat(
            count(
                "SELECT COUNT(*) FROM timetable_slot WHERE TIME_TO_SEC(end_time) - TIME_TO_SEC(start_time) <> 90 * 60"))
        .isZero();
    assertThat(
            count(
                """
                SELECT COUNT(*) FROM timetable_slot a JOIN timetable_slot b
                  ON a.id < b.id AND a.timetable_id = b.timetable_id AND a.weekday = b.weekday
                WHERE a.start_time < b.end_time AND b.start_time < a.end_time
                """))
        .isZero();
    assertThat(
            count(
                """
                SELECT COUNT(*) FROM block_assignment a JOIN block_assignment b
                  ON a.id < b.id AND a.block_plan_id = b.block_plan_id
                WHERE a.starts_on <= b.ends_on AND b.starts_on <= a.ends_on
                """))
        .isZero();
    assertThat(
            count(
                """
                SELECT COUNT(*) FROM class_block_assignment cb
                JOIN school_class c ON c.id = cb.school_class_id
                JOIN block_assignment b ON b.id = cb.block_assignment_id
                JOIN block_plan p ON p.id = c.block_plan_id
                WHERE b.block_plan_id <> p.id OR b.starts_on < p.starts_on OR b.ends_on > p.ends_on
                """))
        .isZero();
    assertThat(
            count(
                """
                SELECT COUNT(*) FROM school_class a JOIN school_class b
                  ON a.id < b.id AND a.timetable_id = b.timetable_id
                JOIN class_block_assignment ca ON ca.school_class_id = a.id
                JOIN class_block_assignment cb ON cb.school_class_id = b.id
                JOIN block_assignment ba ON ba.id = ca.block_assignment_id
                JOIN block_assignment bb ON bb.id = cb.block_assignment_id
                WHERE ba.starts_on <= bb.ends_on AND bb.starts_on <= ba.ends_on
                """))
        .isZero();
  }

  @ParameterizedTest
  @CsvSource({
    "1, ABWESEND, 180, 0, 1",
    "2, ANWESEND, 90, 0, 1",
    "3, ABWESEND, 150, 0, 3",
    "4, ANWESEND, 30, 60, 2",
    "5, ANWESEND, 0, 0, 1",
    "6, ANWESEND, 0, 0, 1",
    "7, ABWESEND, 180, 0, 1",
    "8, ANWESEND, 30, 0, 1"
  })
  void dailySnapshotsMatchDocumentedCases(
      int studentId, String status, int unexcused, int excused, int auditCount) {
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM attendance WHERE student_id = ?", String.class, studentId))
        .isEqualTo(status);
    assertThat(count("SELECT COUNT(*) FROM attendance WHERE student_id = ?", studentId))
        .isEqualTo(1);
    assertThat(count("SELECT unexcused_minutes_account FROM student WHERE id = ?", studentId))
        .isEqualTo(unexcused);
    assertThat(count("SELECT excused_minutes_account FROM student WHERE id = ?", studentId))
        .isEqualTo(excused);
    assertThat(count("SELECT COUNT(*) FROM attendance_audit WHERE student_id = ?", studentId))
        .isEqualTo(auditCount);
  }

  @Test
  void auditsMatchAccountsActorsAndRetention() {
    assertThat(
            count(
                """
                SELECT COUNT(*) FROM student s WHERE
                  s.unexcused_minutes_account < 0 OR s.excused_minutes_account < 0 OR
                  s.unexcused_minutes_account <> (SELECT SUM(unexcused_minutes_delta)
                    FROM attendance_audit WHERE student_id = s.id) OR
                  s.excused_minutes_account <> (SELECT SUM(excused_minutes_delta)
                    FROM attendance_audit WHERE student_id = s.id)
                """))
        .isZero();
    assertThat(
            count(
                """
                SELECT COUNT(*) FROM attendance_audit aa
                JOIN attendance a ON a.id = aa.attendance_id
                JOIN student s ON s.id = a.student_id
                JOIN school_class c ON c.id = s.school_class_id
                JOIN block_plan p ON p.id = c.block_plan_id
                JOIN deletion_date d ON d.id = aa.deletion_date_id
                WHERE aa.student_id <> a.student_id
                  OR d.delete_after <> DATE_ADD(p.ends_on, INTERVAL 6 MONTH)
                  OR (aa.event_type = 'SCAN' AND (aa.terminal_id IS NULL OR aa.changed_by_staff_id IS NOT NULL))
                  OR (aa.event_type = 'STATUS_CHANGE' AND (aa.changed_by_staff_id IS NULL OR aa.changed_by_staff_id <> c.class_teacher_id OR aa.terminal_id IS NOT NULL))
                  OR (aa.event_type = 'DAILY_CLOSE' AND (aa.terminal_id IS NOT NULL OR aa.changed_by_staff_id IS NOT NULL))
                """))
        .isZero();
    assertThat(
            count(
                """
                SELECT COUNT(*) FROM attendance a WHERE a.status <> COALESCE(
                  (SELECT aa.new_status FROM attendance_audit aa
                   WHERE aa.attendance_id = a.id AND aa.new_status IS NOT NULL
                   ORDER BY aa.occurred_at DESC, aa.id DESC LIMIT 1), 'ABWESEND')
                """))
        .isZero();
    assertThat(
            count(
                """
                SELECT COUNT(*) FROM attendance_audit WHERE student_id = 4
                  AND event_type = 'STATUS_CHANGE' AND occurred_at = '2026-09-14 06:00:00'
                  AND created_at = '2026-09-14 07:30:00'
                  AND unexcused_minutes_delta = -60 AND excused_minutes_delta = 60
                """))
        .isEqualTo(1);
  }

  @Test
  void scansHaveSyntheticValuesAndMatchBerlinScheduling() {
    assertThat(count("SELECT COUNT(*) FROM raw_scan")).isEqualTo(7);
    assertThat(
            count(
                "SELECT COUNT(*) FROM student WHERE first_name <> 'Test' OR rfid_uid NOT LIKE 'TEST-UID-%'"))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM staff WHERE first_name <> 'Test' OR username NOT LIKE 'test.%'"))
        .isZero();
    assertThat(
            count(
                """
                SELECT COUNT(*) FROM raw_scan WHERE rfid_uid NOT LIKE 'TEST-UID-%'
                  OR scan_id NOT REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                  OR scanned_at > created_at
                """))
        .isZero();
    // Beide festgelegten Montage liegen in der Berliner Sommerzeit (UTC+02:00).
    assertThat(
            count(
                """
                SELECT COUNT(*) FROM attendance_audit aa
                JOIN attendance a ON a.id = aa.attendance_id
                JOIN student s ON s.id = a.student_id
                JOIN school_class c ON c.id = s.school_class_id
                JOIN terminal t ON t.terminal_id = aa.terminal_id
                WHERE aa.event_type = 'SCAN' AND (
                  DATE(DATE_ADD(aa.occurred_at, INTERVAL 2 HOUR)) <> a.attendance_date
                  OR NOT EXISTS (SELECT 1 FROM class_block_assignment cb
                    JOIN block_assignment b ON b.id = cb.block_assignment_id
                    WHERE cb.school_class_id = c.id AND b.block_plan_id = c.block_plan_id
                      AND a.attendance_date BETWEEN b.starts_on AND b.ends_on)
                  OR NOT EXISTS (SELECT 1 FROM timetable_slot ts
                    WHERE ts.timetable_id = c.timetable_id AND ts.room_id = t.room_id
                      AND ts.weekday = 'MONTAG'
                      AND TIME(DATE_ADD(aa.occurred_at, INTERVAL 2 HOUR)) >= ts.start_time
                      AND TIME(DATE_ADD(aa.occurred_at, INTERVAL 2 HOUR)) < ts.end_time)
                  OR NOT EXISTS (SELECT 1 FROM raw_scan rs
                    WHERE rs.rfid_uid = s.rfid_uid AND rs.scanned_at = aa.occurred_at))
                """))
        .isZero();
    assertThat(
            count(
                """
                SELECT COUNT(*) FROM raw_scan rs JOIN student s ON s.rfid_uid = rs.rfid_uid
                WHERE s.id IN (1, 7)
                """))
        .isZero();
    assertThat(
            count(
                """
                SELECT COUNT(*) FROM raw_scan rs JOIN student s ON s.rfid_uid = rs.rfid_uid
                WHERE s.id = 5
                """))
        .isEqualTo(2);
  }

  @Test
  void duplicateScanIdIsRejectedByDatabaseWithoutChangingSnapshot() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO raw_scan (scan_id, rfid_uid, scanned_at, created_at)
                    SELECT scan_id, rfid_uid, scanned_at, created_at FROM raw_scan
                    WHERE scan_id = '00000000-0000-4000-8000-000000000006'
                    """))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(
            count(
                "SELECT COUNT(*) FROM raw_scan WHERE scan_id = '00000000-0000-4000-8000-000000000006'"))
        .isEqualTo(1);
    dailySnapshotsMatchDocumentedCases(6, "ANWESEND", 0, 0, 1);
  }

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }
}
