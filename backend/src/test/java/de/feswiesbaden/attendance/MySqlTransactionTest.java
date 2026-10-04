package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prüft mit MySQL Commit und Rollback von Minutenkonten und Audits sowie die transaktionale
 * Historienlöschung in Fremdschlüsselreihenfolge. Nicht fällige Daten, Rohscans und Kontostände
 * bleiben erhalten.
 */
@SpringBootTest
@Testcontainers
class MySqlTransactionTest {
  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  private static final List<String> DELETION_ORDER =
      List.of(
          "attendance_audit",
          "raw_scan",
          "attendance",
          "student",
          "teacher_class",
          "class_block_assignment",
          "school_class",
          "block_assignment",
          "timetable_slot",
          "terminal",
          "room",
          "staff",
          "block_plan",
          "timetable",
          "deletion_date");

  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactionManager;

  private TransactionTemplate transaction;

  @BeforeEach
  void loadCommittedFixture() {
    transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        status -> {
          new ResourceDatabasePopulator(new ClassPathResource("fixtures/attendance.sql"))
              .execute(dataSource);
          jdbc.update("INSERT INTO deletion_date (id, delete_after) VALUES (2, '2027-07-01')");
          jdbc.update(
              """
              INSERT INTO block_plan (id, name, starts_on, ends_on)
              VALUES (2, 'Test-Kontrollplan', '2026-01-01', '2027-01-01')
              """);
          jdbc.update(
              """
              INSERT INTO school_class
                (id, class_code, class_teacher_id, block_plan_id, timetable_id)
              VALUES (4, '26TEST-D', 2, 2, 2)
              """);
          jdbc.update(
              """
              INSERT INTO block_assignment (id, block_plan_id, starts_on, ends_on)
              VALUES (3, 2, '2026-10-05', '2026-10-09')
              """);
          jdbc.update("INSERT INTO class_block_assignment VALUES (4, 3)");
          jdbc.update(
              """
              INSERT INTO student
                (id, first_name, last_name, birth_date, school_class_id, unexcused_minutes_account)
              VALUES (9, 'Test', 'Kontrollfall', '2008-01-01', 4, 180)
              """);
          jdbc.update(
              """
              INSERT INTO attendance (id, student_id, attendance_date, status)
              VALUES (99, 9, '2026-10-05', 'ABWESEND')
              """);
          jdbc.update(
              """
              INSERT INTO attendance_audit
                (id, student_id, attendance_id, deletion_date_id, event_type, occurred_at,
                 unexcused_minutes_delta)
              VALUES (99, 9, 99, 2, 'DAILY_CLOSE', '2026-10-05 08:45:00', 180)
              """);
          jdbc.update(
              """
          INSERT INTO attendance_audit
            (student_id, deletion_date_id, event_type, changed_by_staff_id, occurred_at)
          VALUES (5, 1, 'ACCOUNT_RESET', 3, '2026-10-01 12:00:00')
          """);
        });
  }

  @AfterEach
  void removeCommittedFixture() {
    transaction.executeWithoutResult(
        status -> {
          for (String table : DELETION_ORDER) {
            jdbc.update("DELETE FROM " + table);
          }
        });
  }

  @Test
  void accountAndAuditCommitTogether() {
    int auditsBefore = count("SELECT COUNT(*) FROM attendance_audit");
    transaction.executeWithoutResult(status -> resetAccount());

    assertThat(count("SELECT unexcused_minutes_account FROM student WHERE id = 4")).isZero();
    assertThat(count("SELECT excused_minutes_account FROM student WHERE id = 4")).isZero();
    assertThat(count("SELECT COUNT(*) FROM attendance_audit")).isEqualTo(auditsBefore + 1);
    assertThat(
            count(
                """
        SELECT COUNT(*) FROM attendance_audit
        WHERE student_id = 4 AND deletion_date_id = 1 AND attendance_id IS NULL
          AND event_type = 'ACCOUNT_RESET' AND changed_by_staff_id = 3
          AND unexcused_minutes_delta = -30 AND excused_minutes_delta = -60
        """))
        .isEqualTo(1);
  }

  @Test
  void constraintFailureRollsBackAccountAndAudit() {
    Map<String, Object> before = snapshot();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      resetAccount();
                      jdbc.update("INSERT INTO deletion_date (delete_after) VALUES ('2027-06-30')");
                    }))
        .isInstanceOf(DuplicateKeyException.class);

    assertThat(snapshot()).isEqualTo(before);
    assertThat(
            count(
                "SELECT COUNT(*) FROM attendance_audit WHERE student_id = 4 AND event_type = 'ACCOUNT_RESET'"))
        .isZero();
  }

  @Test
  void orderedDeletionCommitsAndPreservesUnrelatedData() {
    Map<String, Object> before = snapshot();
    transaction.executeWithoutResult(status -> deleteDueHistory(false));

    assertThat(jdbc.queryForList("SELECT id FROM deletion_date", Long.class)).containsExactly(2L);
    assertThat(jdbc.queryForList("SELECT id FROM attendance", Long.class)).containsExactly(99L);
    assertThat(jdbc.queryForList("SELECT id FROM attendance_audit", Long.class))
        .containsExactly(99L);
    assertThat(count("SELECT COUNT(*) FROM attendance_audit WHERE event_type = 'ACCOUNT_RESET'"))
        .isZero();
    Map<String, Object> after = snapshot();
    before.keySet().removeAll(List.of("attendance", "attendance_audit", "deletion_date"));
    assertThat(after).containsAllEntriesOf(before);
  }

  @Test
  void failureBetweenDeletionStepsRestoresEntireHistory() {
    Map<String, Object> before = snapshot();
    assertThatThrownBy(() -> transaction.executeWithoutResult(status -> deleteDueHistory(true)))
        .isInstanceOf(DuplicateKeyException.class);

    assertThat(snapshot()).isEqualTo(before);
    assertThat(count("SELECT COUNT(*) FROM attendance_audit WHERE deletion_date_id = 1"))
        .isEqualTo(12);
    assertThat(count("SELECT COUNT(*) FROM attendance WHERE id <> 99")).isEqualTo(8);
  }

  private void resetAccount() {
    jdbc.update(
        """
        UPDATE student SET unexcused_minutes_account = 0, excused_minutes_account = 0 WHERE id = 4
        """);
    jdbc.update(
        """
        INSERT INTO attendance_audit
          (student_id, deletion_date_id, event_type, changed_by_staff_id, occurred_at,
           unexcused_minutes_delta, excused_minutes_delta)
        VALUES (4, 1, 'ACCOUNT_RESET', 3, '2026-10-02 12:00:00', -30, -60)
        """);
  }

  // SQL exercises the FK order and atomicity; the retention service belongs to issue #21.
  private void deleteDueHistory(boolean failAfterAttendanceDeletion) {
    List<Long> attendanceIds =
        jdbc.queryForList(
            """
        SELECT DISTINCT a.attendance_id FROM attendance_audit a
        JOIN deletion_date d ON d.id = a.deletion_date_id
        WHERE d.delete_after <= '2027-06-30' AND a.attendance_id IS NOT NULL
        """,
            Long.class);
    jdbc.update(
        """
        DELETE FROM attendance_audit WHERE deletion_date_id IN
          (SELECT id FROM deletion_date WHERE delete_after <= '2027-06-30')
        """);
    for (Long id : attendanceIds) {
      jdbc.update("DELETE FROM attendance WHERE id = ?", id);
    }
    if (failAfterAttendanceDeletion) {
      jdbc.update("INSERT INTO deletion_date (delete_after) VALUES ('2027-07-01')");
    }
    jdbc.update("DELETE FROM deletion_date WHERE delete_after <= '2027-06-30'");
  }

  private Map<String, Object> snapshot() {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String table : DELETION_ORDER) {
      result.put(table, count("SELECT COUNT(*) FROM " + table));
    }
    result.put(
        "accounts",
        jdbc.queryForList(
            """
        SELECT id, unexcused_minutes_account, excused_minutes_account FROM student ORDER BY id
        """));
    return result;
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }
}
