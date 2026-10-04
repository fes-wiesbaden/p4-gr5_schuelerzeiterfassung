package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.feswiesbaden.attendance.entities.Attendance;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
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
class MySqlIntegrationTest {
  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManager entityManager;

  @Test
  void migrationReflectsCurrentDomainModel() {
    assertThat(columnsOf("attendance"))
        .contains("student_id", "attendance_date")
        .doesNotContain("school_class_id", "block_assignment_id", "teaching_unit_id");
    assertThat(columnsOf("class_block_assignment"))
        .containsExactlyInAnyOrder("school_class_id", "block_assignment_id");
    assertThat(columnsOf("teacher_class")).containsExactlyInAnyOrder("staff_id", "school_class_id");
    assertThat(columnsOf("deletion_date")).containsExactlyInAnyOrder("id", "delete_after");
    assertThat(columnsOf("attendance_audit"))
        .contains("event_type", "deletion_date_id", "attendance_id")
        .doesNotContain("source", "block_plan_id");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      value = {
        "terminal | terminal_id, room_id | 9001, 3 | 9003, 3",
        "terminal | terminal_id, room_id | 9003, 1 | 9003, 3",
        "raw_scan | scan_id, rfid_uid, scanned_at |"
            + " '00000000-0000-4000-8000-000000000006', 'TEST-UID-006', '2026-09-14 05:30:00' |"
            + " '00000000-0000-4000-8000-000000000099', 'TEST-UID-006', '2026-09-14 05:30:00'",
        "school_class | class_code, class_teacher_id, block_plan_id, timetable_id |"
            + " '26TEST-A', 1, 1, 1 | '26TEST-D', 1, 1, 1",
        "deletion_date | delete_after | '2027-06-30' | '2027-07-01'",
        "attendance | student_id, attendance_date, status |"
            + " 1, '2026-09-14', 'ABWESEND' | 1, '2026-09-15', 'ABWESEND'",
        "class_block_assignment | school_class_id, block_assignment_id | 1, 1 | 2, 2"
      })
  void uniqueConstraintsRejectDuplicatesAndAllowDistinctKeys(
      String table, String columns, String duplicate, String distinct) {
    jdbc.update("INSERT INTO room (id, room_number) VALUES (3, 'TEST-R03')");
    String insert = "INSERT INTO " + table + " (" + columns + ") VALUES (%s)";
    int before = count("SELECT COUNT(*) FROM " + table);
    assertThatThrownBy(() -> jdbc.update(insert.formatted(duplicate)))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(count("SELECT COUNT(*) FROM " + table)).isEqualTo(before);
    assertThat(jdbc.update(insert.formatted(distinct))).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM " + table)).isEqualTo(before + 1);
  }

  @Test
  void attendanceResolvesClassThroughStudentAndAllowsAnotherStudentOnSameDay() {
    assertThat(entityManager.find(Attendance.class, 1L).getStudent().getSchoolClass().getId())
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                """
        SELECT c.class_code FROM attendance a
        JOIN student s ON s.id = a.student_id
        JOIN school_class c ON c.id = s.school_class_id
        WHERE a.id = 1
        """,
                String.class))
        .isEqualTo("26TEST-A");
    assertThat(
            jdbc.update(
                """
        INSERT INTO attendance (student_id, attendance_date, status)
        VALUES (2, '2026-09-15', 'ABWESEND'), (3, '2026-09-15', 'ABWESEND')
        """))
        .isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM attendance WHERE attendance_date = '2026-09-15'"))
        .isEqualTo(2);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
      999, 1, 1, 1, NULL
      1, 999, 1, 1, NULL
      1, 1, 999, 1, NULL
      1, 1, NULL, 1, NULL
      1, 1, 1, 999, NULL
      1, 1, 1, NULL, 999
      """)
  void auditRequiresDeletionDateAndExistingReferences(String references) {
    int before = count("SELECT COUNT(*) FROM attendance_audit");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
        INSERT INTO attendance_audit
          (student_id, attendance_id, deletion_date_id, changed_by_staff_id, terminal_id,
           event_type, old_status, new_status, occurred_at)
        VALUES (%s, 'STATUS_CHANGE', 'ANWESEND', 'ABWESEND', '2026-09-14 08:00:00')
        """
                        .formatted(references)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(count("SELECT COUNT(*) FROM attendance_audit")).isEqualTo(before);
  }

  @Test
  void multipleResetsCanShareStudentAndDeletionDate() {
    jdbc.update(
        """
        INSERT INTO attendance_audit
          (student_id, deletion_date_id, event_type, changed_by_staff_id, occurred_at,
           unexcused_minutes_delta, excused_minutes_delta)
        VALUES (1, 1, 'ACCOUNT_RESET', 3, '2026-10-01 12:00:00', -180, 0),
               (1, 1, 'ACCOUNT_RESET', 3, '2026-10-02 12:00:00', 0, 0)
        """);
    assertThat(
            count(
                """
        SELECT COUNT(*) FROM attendance_audit
        WHERE student_id = 1 AND deletion_date_id = 1 AND event_type = 'ACCOUNT_RESET'
          AND attendance_id IS NULL AND changed_by_staff_id = 3 AND terminal_id IS NULL
        """))
        .isEqualTo(2);
    assertThat(
            count(
                """
        SELECT COUNT(*) FROM attendance_audit WHERE event_type = 'ACCOUNT_RESET'
          AND unexcused_minutes_delta = 0 AND excused_minutes_delta = 0
        """))
        .isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({"attendance, 1", "student, 1", "deletion_date, 1"})
  void referencedHistoryCannotBeDeletedPrematurely(String table, int id) {
    int before = count("SELECT COUNT(*) FROM " + table);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM " + table + " WHERE id = ?", id))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(count("SELECT COUNT(*) FROM " + table)).isEqualTo(before);
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  private List<String> columnsOf(String table) {
    return jdbc.queryForList(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?",
        String.class,
        table);
  }
}
