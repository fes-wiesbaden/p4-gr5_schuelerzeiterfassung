package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class MySqlIntegrationTest {
  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @Autowired JdbcTemplate jdbc;

  @Test
  void contextLoads() {}

  @Test
  void migrationReflectsCurrentDomainModel() {
    assertThat(columnsOf("attendance"))
        .contains("student_id", "attendance_date")
        .doesNotContain("school_class_id", "block_assignment_id", "teaching_unit_id");
    assertThat(columnsOf("class_block_assignment"))
        .containsExactlyInAnyOrder("school_class_id", "block_assignment_id");
    assertThat(columnsOf("deletion_date")).containsExactlyInAnyOrder("id", "delete_after");
    assertThat(columnsOf("attendance_audit"))
        .contains("event_type", "deletion_date_id", "attendance_id")
        .doesNotContain("source", "block_plan_id");
  }

  @Test
  void multipleResetsCanShareStudentAndDeletionDate() {
    jdbc.update(
        "INSERT INTO staff (id, first_name, last_name, username, password_hash, role)"
            + " VALUES (1, 'Test', 'Teacher', 'test.teacher', 'test-hash', 'LEHRKRAFT'),"
            + " (2, 'Test', 'Admin', 'test.admin', 'test-hash', 'ADMINISTRATOR')");
    jdbc.update("INSERT INTO timetable (id, name) VALUES (1, 'Testplan')");
    jdbc.update(
        "INSERT INTO block_plan (id, name, starts_on, ends_on)"
            + " VALUES (1, 'Testblockplan', '2026-01-01', '2026-12-31')");
    jdbc.update(
        "INSERT INTO school_class (id, class_code, class_teacher_id, block_plan_id, timetable_id)"
            + " VALUES (1, '26TEST', 1, 1, 1)");
    jdbc.update(
        "INSERT INTO student (id, first_name, last_name, birth_date, school_class_id)"
            + " VALUES (1, 'Test', 'Student', '2008-01-01', 1)");
    jdbc.update("INSERT INTO deletion_date (id, delete_after) VALUES (1, '2027-06-30')");
    jdbc.update(
        "INSERT INTO attendance_audit"
            + " (student_id, deletion_date_id, event_type, changed_by_staff_id, occurred_at)"
            + " VALUES (1, 1, 'ACCOUNT_RESET', 2, '2026-06-01 12:00:00'),"
            + " (1, 1, 'ACCOUNT_RESET', 2, '2026-12-31 12:00:00')");

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE student_id = 1 AND deletion_date_id = 1",
                Integer.class))
        .isEqualTo(2);
  }

  private java.util.List<String> columnsOf(String table) {
    return jdbc.queryForList(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?",
        String.class,
        table);
  }
}
