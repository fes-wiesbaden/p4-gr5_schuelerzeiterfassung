package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
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

  @Test
  void multipleResetsCanShareStudentAndDeletionDate() {
    jdbc.update(
        "INSERT INTO attendance_audit"
            + " (student_id, deletion_date_id, event_type, changed_by_staff_id, occurred_at)"
            + " VALUES (5, 1, 'ACCOUNT_RESET', 3, '2026-12-30 12:00:00'),"
            + " (5, 1, 'ACCOUNT_RESET', 3, '2026-12-31 12:00:00')");

    assertThat(
            jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM attendance_audit
                WHERE student_id = 5 AND deletion_date_id = 1 AND event_type = 'ACCOUNT_RESET'
                  AND attendance_id IS NULL AND changed_by_staff_id = 3
                """,
                Integer.class))
        .isEqualTo(2);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "terminal | UPDATE terminal SET terminal_id = 9001 WHERE terminal_id = 9002",
        "terminal | UPDATE terminal SET room_id = 1 WHERE terminal_id = 9002",
        "room | UPDATE room SET room_number = 'TEST-R01' WHERE id = 2",
        "school_class | UPDATE school_class SET class_code = '26TEST-A' WHERE id = 2",
        "deletion_date | INSERT INTO deletion_date (delete_after) VALUES ('2027-06-30')",
        "attendance | INSERT INTO attendance (student_id, attendance_date, status)"
            + " VALUES (1, '2026-09-14', 'ANWESEND')",
        "class_block_assignment | INSERT INTO class_block_assignment"
            + " (school_class_id, block_assignment_id) VALUES (1, 1)"
      })
  void duplicateKeysPreserveExistingRows(String table, String sql) {
    List<Map<String, Object>> before = rowsOf(table);

    assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DuplicateKeyException.class);

    assertThat(rowsOf(table)).isEqualTo(before);
  }

  @ParameterizedTest
  @CsvSource({
    "school_class, class_teacher_id",
    "school_class, block_plan_id",
    "school_class, timetable_id",
    "student, school_class_id",
    "teacher_class, staff_id",
    "teacher_class, school_class_id",
    "terminal, room_id",
    "timetable_slot, timetable_id",
    "timetable_slot, room_id",
    "block_assignment, block_plan_id",
    "class_block_assignment, school_class_id",
    "class_block_assignment, block_assignment_id",
    "attendance, student_id",
    "attendance_audit, student_id",
    "attendance_audit, attendance_id",
    "attendance_audit, deletion_date_id",
    "attendance_audit, changed_by_staff_id",
    "attendance_audit, terminal_id"
  })
  void missingReferencesPreserveExistingRows(String table, String column) {
    List<Map<String, Object>> before = rowsOf(table);

    assertThatThrownBy(
            () -> jdbc.update("UPDATE " + table + " SET " + column + " = 999999 LIMIT 1"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);

    assertThat(rowsOf(table)).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "staff",
        "timetable",
        "block_plan",
        "school_class",
        "student",
        "room",
        "terminal",
        "block_assignment",
        "attendance",
        "deletion_date"
      })
  void deletingReferencedRowsIsRejected(String table) {
    List<Map<String, Object>> before = rowsOf(table);
    List<Map<String, Object>> audits = rowsOf("attendance_audit");

    assertThatThrownBy(() -> jdbc.update("DELETE FROM " + table))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasRootCauseInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);

    assertThat(rowsOf(table)).isEqualTo(before);
    assertThat(rowsOf("attendance_audit")).isEqualTo(audits);
  }

  @ParameterizedTest
  @MethodSource("intervals")
  void orderedAndEqualIntervalsCanBeInsertedAndUpdated(
      String table, String startColumn, String endColumn, String insert, String start, String end) {
    int id = 100;
    for (String acceptedEnd : List.of(start, end)) {
      assertThat(jdbc.update(insert, id, start, acceptedEnd)).isEqualTo(1);
      assertThat(intervalOf(table, startColumn, endColumn, id)).containsExactly(start, acceptedEnd);

      assertThat(
              jdbc.update(
                  "UPDATE "
                      + table
                      + " SET "
                      + startColumn
                      + " = ?, "
                      + endColumn
                      + " = ? WHERE id = 1",
                  start,
                  acceptedEnd))
          .isEqualTo(1);
      assertThat(intervalOf(table, startColumn, endColumn, 1)).containsExactly(start, acceptedEnd);
      id++;
    }
  }

  @ParameterizedTest
  @MethodSource("intervals")
  void reversedIntervalInsertIsRejectedWithoutChangingRows(
      String table, String startColumn, String endColumn, String insert, String start, String end) {
    List<Map<String, Object>> before = rowsOf(table);

    assertThatThrownBy(() -> jdbc.update(insert, 100, end, start))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("chk_" + table + "_interval");

    assertThat(rowsOf(table)).isEqualTo(before);
  }

  @ParameterizedTest
  @MethodSource("intervals")
  void reversedIntervalUpdatePreservesOriginalRow(
      String table, String startColumn, String endColumn, String insert, String start, String end) {
    List<Map<String, Object>> before = rowsOf(table);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE "
                        + table
                        + " SET "
                        + startColumn
                        + " = ?, "
                        + endColumn
                        + " = ? WHERE id = 1",
                    end,
                    start))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("chk_" + table + "_interval");

    assertThat(rowsOf(table)).isEqualTo(before);
  }

  private static Stream<Arguments> intervals() {
    return Stream.of(
        Arguments.of(
            "block_assignment",
            "starts_on",
            "ends_on",
            "INSERT INTO block_assignment (id, block_plan_id, starts_on, ends_on) VALUES (?, 1, ?, ?)",
            "2026-09-14",
            "2026-09-18"),
        Arguments.of(
            "block_plan",
            "starts_on",
            "ends_on",
            "INSERT INTO block_plan (id, name, starts_on, ends_on) VALUES (?, 'Test-Intervall', ?, ?)",
            "2026-09-01",
            "2026-12-31"),
        Arguments.of(
            "timetable_slot",
            "start_time",
            "end_time",
            "INSERT INTO timetable_slot (id, timetable_id, room_id, weekday, start_time, end_time)"
                + " VALUES (?, 1, 1, 'FREITAG', ?, ?)",
            "07:30:00",
            "09:00:00"));
  }

  private List<String> intervalOf(String table, String startColumn, String endColumn, int id) {
    return jdbc.queryForObject(
        "SELECT " + startColumn + ", " + endColumn + " FROM " + table + " WHERE id = ?",
        (rs, rowNum) -> List.of(rs.getString(1), rs.getString(2)),
        id);
  }

  private List<Map<String, Object>> rowsOf(String table) {
    return jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1, 2");
  }

  private List<String> columnsOf(String table) {
    return jdbc.queryForList(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?",
        String.class,
        table);
  }
}
