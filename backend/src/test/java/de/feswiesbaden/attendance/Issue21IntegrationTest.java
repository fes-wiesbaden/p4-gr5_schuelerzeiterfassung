package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.feswiesbaden.attendance.service.BlockPlanService;
import de.feswiesbaden.attendance.service.DeletionDateService;
import de.feswiesbaden.attendance.service.RetentionService;
import de.feswiesbaden.attendance.service.RfidUidService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "attendance.retention.scheduling.enabled=false")
@Testcontainers
@Sql("/fixtures/attendance.sql")
@ExtendWith(OutputCaptureExtension.class)
class Issue21IntegrationTest {
  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @Autowired JdbcTemplate jdbc;
  @Autowired RfidUidService uids;
  @Autowired BlockPlanService plans;
  @Autowired DeletionDateService deletionDates;
  @Autowired RetentionService retention;
  @MockitoBean Clock clock;

  @BeforeEach
  void fixTime() {
    when(clock.instant()).thenReturn(Instant.parse("2027-06-29T22:00:00Z"));
    when(clock.withZone(any()))
        .thenAnswer(invocation -> Clock.fixed(clock.instant(), invocation.getArgument(0)));
  }

  @AfterEach
  void cleanup() {
    for (String table :
        List.of(
            "attendance_audit",
            "attendance",
            "raw_scan",
            "class_block_assignment",
            "teacher_class",
            "student",
            "school_class",
            "block_assignment",
            "timetable_slot",
            "terminal",
            "room",
            "staff",
            "block_plan",
            "timetable",
            "deletion_date")) {
      jdbc.update("DELETE FROM " + table);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"04A3B2C1", "04A3B2C1D2E3F4", "04A3B2C1D2E3F4A5B6C7"})
  void assignsReplacesAndRemovesUid(String uid) {
    uids.assign(3L, 1L, uid);
    uids.assign(3L, 1L, uid);
    assertThat(uidOf(1L)).isEqualTo(uid);
    uids.assign(3L, 1L, "DEADBEEF");
    assertThat(uidOf(1L)).isEqualTo("DEADBEEF");
    uids.remove(3L, 1L);
    uids.remove(3L, 2L);
    assertThat(uidOf(1L)).isNull();
    assertThat(uidOf(2L)).isNull();
    uids.assign(3L, 2L, uid);
    assertThat(uidOf(2L)).isEqualTo(uid);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "04a3b2c1",
        "04:A3:B2:C1",
        " 04A3B2C1",
        "04A3B2C1\n",
        "ABC",
        "GGGGGGGG",
        "000000000000"
      })
  void rejectsInvalidUidWithoutChangingStudent(String uid) {
    assertThatThrownBy(() -> uids.assign(3L, 1L, uid))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_RFID_UID")
        .hasNoCause();
    assertThat(uidOf(1L)).isEqualTo("TEST-UID-001");
  }

  @ParameterizedTest
  @ValueSource(longs = {1, 2, 999})
  void rejectsNonAdministrators(Long staffId) {
    assertThatThrownBy(() -> uids.assign(staffId, 1L, "04A3B2C1"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> uids.remove(staffId, 1L)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> plans.create(staffId, "Test", LocalDate.now(), LocalDate.now()))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(uidOf(1L)).isEqualTo("TEST-UID-001");
    assertThat(count("block_plan")).isEqualTo(1);
  }

  @Test
  void rejectsMissingActorAndStudent() {
    assertThatThrownBy(() -> uids.assign(null, 1L, "04A3B2C1"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> uids.assign(3L, 999L, "04A3B2C1")).hasMessage("STUDENT_NOT_FOUND");
    assertThatThrownBy(() -> uids.remove(3L, 999L)).hasMessage("STUDENT_NOT_FOUND");
  }

  @Test
  void uidConflictPreservesAssignmentsAndDoesNotLeak(CapturedOutput output) {
    String uid = "04A3B2C1";
    uids.assign(3L, 1L, uid);
    assertThatThrownBy(() -> uids.assign(3L, 2L, uid))
        .hasMessage("RFID_UID_ALREADY_ASSIGNED")
        .hasNoCause();
    assertThat(uidOf(1L)).isEqualTo(uid);
    assertThat(uidOf(2L)).isEqualTo("TEST-UID-002");
    assertThat(output.getAll()).doesNotContain(uid);
  }

  @Test
  void concurrentUidAssignmentHasOnlyOneWinner(CapturedOutput output) throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> assignTogether(barrier, 1L));
      var second = workers.submit(() -> assignTogether(barrier, 2L));
      assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("ASSIGNED", "RFID_UID_ALREADY_ASSIGNED");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM student WHERE rfid_uid = '04A3B2C1'", Integer.class))
        .isEqualTo(1);
    assertThat(output.getAll()).doesNotContain("04A3B2C1");
  }

  @ParameterizedTest
  @CsvSource({"2027-08-31,2028-02-29", "2026-08-31,2027-02-28", "2026-12-31,2027-06-30"})
  void createsPlansWithReusableCalendarDeadline(LocalDate endsOn, LocalDate expected) {
    Long first = plans.create(3L, "Test-A", endsOn.minusMonths(1), endsOn);
    Long second = plans.create(3L, "Test-B", endsOn.minusMonths(1), endsOn);
    assertThat(first).isNotEqualTo(second);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM deletion_date WHERE delete_after = ?",
                Integer.class,
                expected))
        .isEqualTo(1);
    assertThat(deletionDates.getOrCreate(endsOn).getDeleteAfter()).isEqualTo(expected);
  }

  @Test
  void concurrentPlansShareOneDeadline() throws Exception {
    LocalDate endsOn = LocalDate.of(2027, 8, 31);
    CyclicBarrier barrier = new CyclicBarrier(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> createTogether(barrier, "Test-A", endsOn));
      var second = workers.submit(() -> createTogether(barrier, "Test-B", endsOn));
      assertThat(first.get(30, TimeUnit.SECONDS)).isNotEqualTo(second.get(30, TimeUnit.SECONDS));
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM deletion_date WHERE delete_after = '2028-02-29'",
                Integer.class))
        .isEqualTo(1);
    assertThat(count("block_plan")).isEqualTo(3);
  }

  @Test
  void rejectsInvalidPlansBeforeWriting() {
    LocalDate start = LocalDate.of(2027, 1, 1);
    for (String name : List.of("", " ", "X".repeat(151))) {
      assertThatThrownBy(() -> plans.create(3L, name, start, start))
          .hasMessage("INVALID_BLOCK_PLAN");
    }
    assertThatThrownBy(() -> plans.create(3L, null, start, start)).hasMessage("INVALID_BLOCK_PLAN");
    assertThatThrownBy(() -> plans.create(3L, "Test", null, start))
        .hasMessage("INVALID_BLOCK_PLAN");
    assertThatThrownBy(() -> plans.create(3L, "Test", start, null))
        .hasMessage("INVALID_BLOCK_PLAN");
    assertThatThrownBy(() -> plans.create(3L, "Test", start, start.minusDays(1)))
        .hasMessage("INVALID_BLOCK_PLAN");
    assertThat(count("block_plan")).isEqualTo(1);
    assertThat(count("deletion_date")).isEqualTo(1);
  }

  @Test
  void deadlineFailureRollsBackPersistedPlan() {
    jdbc.execute(
        "ALTER TABLE deletion_date ADD CONSTRAINT test_reject_deadline CHECK (delete_after <> '2028-06-30')");
    try {
      assertThatThrownBy(
              () -> plans.create(3L, "Test", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)))
          .isInstanceOf(DataAccessException.class);
      assertThat(count("block_plan")).isEqualTo(1);
      assertThat(count("deletion_date")).isEqualTo(1);
    } finally {
      jdbc.execute("ALTER TABLE deletion_date DROP CHECK test_reject_deadline");
    }
  }

  @Test
  void deletesRawScansAtReceiptAgeBoundaryOnly() {
    jdbc.update("DELETE FROM raw_scan");
    for (int seconds = -1; seconds <= 1; seconds++) {
      jdbc.update(
          "INSERT INTO raw_scan (scan_id, rfid_uid, scanned_at, created_at) VALUES (?, '04A3B2C1', '2020-01-01', ?)",
          "00000000-0000-4000-8000-%012d".formatted(seconds + 2),
          java.time.LocalDateTime.of(2027, 6, 15, 22, 0).plusSeconds(seconds));
    }
    assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'raw_scan' AND index_name = 'idx_raw_scan_created_at'",
                Integer.class))
        .isEqualTo(1);
    assertThat(retention.deleteRawScans()).isEqualTo(2);
    assertThat(count("raw_scan")).isEqualTo(1);
    assertThat(retention.deleteRawScans()).isZero();
    assertThat(count("attendance_audit")).isEqualTo(11);
    assertThat(count("attendance")).isEqualTo(8);
  }

  @Test
  void deletesDueAndOverdueHistoryWithoutResettingAccounts() {
    jdbc.update(
        "INSERT INTO deletion_date (id, delete_after) VALUES (2, '2027-07-01'), (3, '2027-06-29')");
    jdbc.update("UPDATE attendance_audit SET deletion_date_id = 2 WHERE attendance_id = 8");
    jdbc.update(
        "INSERT INTO attendance_audit (student_id, deletion_date_id, event_type, occurred_at) VALUES (1, 3, 'ACCOUNT_RESET', '2026-12-31')");
    List<Map<String, Object>> students = jdbc.queryForList("SELECT * FROM student ORDER BY id");
    when(clock.instant()).thenReturn(Instant.parse("2027-06-29T21:59:59Z"));
    assertThat(retention.deleteHistory()).isEqualTo(1);
    assertThat(count("attendance")).isEqualTo(8);
    when(clock.instant()).thenReturn(Instant.parse("2027-06-29T22:00:00Z"));
    assertThat(retention.deleteHistory()).isEqualTo(10);
    assertThat(jdbc.queryForList("SELECT id FROM attendance", Long.class)).containsExactly(8L);
    assertThat(jdbc.queryForList("SELECT id FROM attendance_audit", Long.class))
        .containsExactly(11L);
    assertThat(jdbc.queryForList("SELECT * FROM student ORDER BY id")).isEqualTo(students);
    assertThat(count("raw_scan")).isEqualTo(7);
    assertThat(count("deletion_date")).isEqualTo(3);
    assertThat(count("block_plan")).isEqualTo(1);
    assertThat(count("school_class")).isEqualTo(3);
    assertThat(retention.deleteHistory()).isZero();
  }

  @Test
  void nonDueAuditReferenceRollsBackEntireHistoryDeletion() {
    jdbc.update("INSERT INTO deletion_date (id, delete_after) VALUES (2, '2027-07-01')");
    jdbc.update("UPDATE attendance_audit SET deletion_date_id = 2 WHERE id = 7");
    assertThatThrownBy(retention::deleteHistory).isInstanceOf(DataAccessException.class);
    assertThat(count("attendance_audit")).isEqualTo(11);
    assertThat(count("attendance")).isEqualTo(8);
    assertThat(count("raw_scan")).isEqualTo(7);
  }

  private String uidOf(Long studentId) {
    return jdbc.queryForObject(
        "SELECT rfid_uid FROM student WHERE id = ?", String.class, studentId);
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  private String assignTogether(CyclicBarrier barrier, Long studentId) throws Exception {
    barrier.await(10, TimeUnit.SECONDS);
    try {
      uids.assign(3L, studentId, "04A3B2C1");
      return "ASSIGNED";
    } catch (IllegalArgumentException conflict) {
      return conflict.getMessage();
    }
  }

  private Long createTogether(CyclicBarrier barrier, String name, LocalDate endsOn)
      throws Exception {
    barrier.await(10, TimeUnit.SECONDS);
    return plans.create(3L, name, endsOn.minusMonths(1), endsOn);
  }
}
