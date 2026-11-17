package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.feswiesbaden.attendance.enums.AttendanceStatus;
import de.feswiesbaden.attendance.repository.RetentionRepository;
import de.feswiesbaden.attendance.service.AttendanceAccountingService;
import de.feswiesbaden.attendance.service.RetentionService;
import de.feswiesbaden.attendance.service.SchoolClassService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@Sql("/fixtures/attendance.sql")
class AttendanceAccountingIntegrationTest {
  private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);

  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @Autowired JdbcTemplate jdbc;

  @Autowired AttendanceAccountingService accounting;

  @Autowired SchoolClassService classes;

  @Autowired RetentionService retention;

  @Autowired RetentionRepository retentionRepository;

  @Autowired PlatformTransactionManager transactions;

  @MockitoBean Clock clock;

  @BeforeEach
  void prepare() {
    jdbc.update("DELETE FROM attendance_audit");
    jdbc.update("DELETE FROM attendance");
    jdbc.update("UPDATE student SET unexcused_minutes_account = 0, excused_minutes_account = 0");
    signIn("test.teacher.a");
    setTime("2026-09-14T08:45:00Z");
    when(clock.withZone(any()))
        .thenAnswer(invocation -> Clock.fixed(clock.instant(), invocation.getArgument(0)));
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
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
  @CsvSource({
    "ANWESEND,0,0",
    "BETRIEB,0,0",
    "ABWESEND,180,0",
    "ENTSCHULDIGT,0,180",
    "MIT_ATTEST_ENTSCHULDIGT,0,180",
    "BETRIEBLICH_ENTSCHULDIGT,0,180"
  })
  void booksEveryStatusWithItsDocumentedMinuteEffect(
      AttendanceStatus status, int unexcused, int excused) {
    assertThat(accounting.correctStatus(1L, MONDAY, status, Instant.parse("2026-09-14T05:30:00Z")))
        .isTrue();
    assertAccount(1, unexcused, excused);
    assertThat(
            jdbc.queryForMap(
                "SELECT event_type, changed_by_staff_id, terminal_id, deletion_date_id"
                    + " FROM attendance_audit WHERE student_id = 1"))
        .containsEntry("event_type", "STATUS_CHANGE")
        .containsEntry("changed_by_staff_id", 1L)
        .containsEntry("terminal_id", null)
        .containsEntry("deletion_date_id", 1L);
  }

  @Test
  void closesOnlyExpectedDaysAndRepeatsWithoutAnyNewBooking() {
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isZero();
    assertThat(accounting.closeDueDays()).isEqualTo(7);
    for (int student = 1; student <= 7; student++) {
      assertAccount(student, 180, 0);
    }
    assertAccount(8, 0, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE event_type = 'DAILY_CLOSE'"
                    + " AND changed_by_staff_id IS NULL AND terminal_id IS NULL AND old_status IS NULL"
                    + " AND new_status IS NULL AND deletion_date_id = 1",
                Integer.class))
        .isEqualTo(7);
    var audits = jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id");
    assertThat(accounting.closeDueDays()).isZero();
    assertThat(jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id")).isEqualTo(audits);
  }

  @Test
  void neverBooksFutureSlotPartsOrPausesDuringTheCurrentDay() {
    var at = Instant.parse("2026-09-14T06:00:00Z");
    setTime(at.toString());
    assertThat(accounting.correctStatus(1L, MONDAY, AttendanceStatus.ENTSCHULDIGT, at)).isTrue();
    assertAccount(1, 30, 0);
    assertThat(accounting.closeDueDays()).isZero();
    setTime("2026-09-14T07:05:00Z");
    assertThat(accounting.correctStatus(1L, MONDAY, AttendanceStatus.ENTSCHULDIGT, at)).isTrue();
    assertAccount(1, 30, 60);
    setTime("2026-09-14T07:10:00Z");
    assertThat(accounting.correctStatus(1L, MONDAY, AttendanceStatus.ENTSCHULDIGT, at)).isFalse();
    assertAccount(1, 30, 60);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_audit", Integer.class))
        .isEqualTo(2);
  }

  @ParameterizedTest
  @CsvSource({"ABWESEND,1", "BETRIEBLICH_ENTSCHULDIGT,0"})
  void addsPartialMinutesAcrossSlotsAndRoundsEachAccountSeparately(
      AttendanceStatus second, int unexcused) {
    assertThat(scan(1, "2026-09-14T05:30:30Z")).isTrue();
    accounting.correctStatus(1L, MONDAY, second, Instant.parse("2026-09-14T07:00:00Z"));
    assertThat(scan(1, "2026-09-14T07:15:30Z")).isTrue();
    assertAccount(1, unexcused, 0);
    assertThat(accounting.closeDueDays()).isEqualTo(6);
    assertAccount(1, unexcused, 0);
  }

  @Test
  void excusesOnlyTheAffectedIntervalAndKeepsLaterScansAndAudits() {
    scan(1, "2026-09-14T07:15:00Z");
    assertAccount(1, 90, 0);
    var original = jdbc.queryForMap("SELECT * FROM attendance_audit WHERE student_id = 1");
    assertThat(
            accounting.correctStatus(
                1L, MONDAY, AttendanceStatus.ENTSCHULDIGT, Instant.parse("2026-09-14T06:00:00Z")))
        .isTrue();
    assertAccount(1, 30, 60);
    assertThat(jdbc.queryForMap("SELECT * FROM attendance_audit WHERE id = ?", original.get("id")))
        .isEqualTo(original);
    assertThat(
            jdbc.queryForMap(
                "SELECT unexcused_minutes_delta, excused_minutes_delta"
                    + " FROM attendance_audit WHERE student_id = 1 ORDER BY id DESC LIMIT 1"))
        .containsEntry("unexcused_minutes_delta", -60)
        .containsEntry("excused_minutes_delta", 60);
    assertThat(
            jdbc.queryForObject("SELECT status FROM attendance WHERE student_id = 1", String.class))
        .isEqualTo("ANWESEND");
  }

  @Test
  void insertsEarlierScansByTheirOriginalTimeWithoutOverwritingLaterEvents() {
    assertThat(scan(1, "2026-09-14T07:15:00Z")).isTrue();
    assertAccount(1, 90, 0);
    assertThat(scan(1, "2026-09-14T06:00:00Z")).isTrue();
    assertAccount(1, 30, 0);
    accounting.correctStatus(
        1L, MONDAY, AttendanceStatus.ENTSCHULDIGT, Instant.parse("2026-09-14T06:30:00Z"));
    assertThat(scan(1, "2026-09-14T05:30:00Z")).isTrue();
    assertAccount(1, 0, 30);
    assertThat(
            jdbc.queryForObject("SELECT status FROM attendance WHERE student_id = 1", String.class))
        .isEqualTo("ANWESEND");
    assertThat(
            jdbc.queryForObject(
                "SELECT MIN(occurred_at) FROM attendance_audit WHERE event_type = 'SCAN'",
                java.sql.Timestamp.class))
        .isEqualTo(java.sql.Timestamp.valueOf("2026-09-14 05:30:00"));
  }

  @ParameterizedTest
  @EnumSource(
      value = AttendanceStatus.class,
      names = {"ANWESEND", "BETRIEB", "ENTSCHULDIGT", "MIT_ATTEST_ENTSCHULDIGT"})
  void rejectsScansAtOtherStatusesWithoutAnyAttendanceOrAccountChange(AttendanceStatus status) {
    accounting.correctStatus(1L, MONDAY, status, Instant.parse("2026-09-14T05:30:00Z"));
    var accounts = jdbc.queryForList("SELECT * FROM student ORDER BY id");
    var days = jdbc.queryForList("SELECT * FROM attendance ORDER BY id");
    var audits = jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id");
    assertThat(scan(1, "2026-09-14T06:00:00Z")).isFalse();
    assertThat(jdbc.queryForList("SELECT * FROM student ORDER BY id")).isEqualTo(accounts);
    assertThat(jdbc.queryForList("SELECT * FROM attendance ORDER BY id")).isEqualTo(days);
    assertThat(jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id")).isEqualTo(audits);
  }

  @Test
  void rejectsInvalidScanTimesRoomsAndBlocksWithoutCreatingAnAttendance() {
    assertThat(scan(1, "2026-09-14T05:29:59Z")).isFalse();
    assertThat(scan(1, "2026-09-14T07:00:00Z")).isFalse();
    assertThat(scan(1, "2026-09-14T07:15:00.001Z")).isFalse();
    assertThat(scan(1, "2026-09-15T05:30:00Z")).isFalse();
    assertThat(scan(8, "2026-09-14T06:00:00Z")).isFalse();
    assertThat(accounting.recordScan(1L, 9002, Instant.parse("2026-09-14T06:00:00Z"))).isFalse();
    assertThat(accounting.recordScan(1L, 9999, Instant.parse("2026-09-14T06:00:00Z"))).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_audit", Integer.class))
        .isZero();
  }

  @Test
  void enforcesStaffAccessAndTheAffectedBerlinDay() {
    signIn("test.teacher.b");
    assertThatThrownBy(
            () ->
                accounting.correctStatus(
                    1L,
                    MONDAY,
                    AttendanceStatus.ENTSCHULDIGT,
                    Instant.parse("2026-09-14T06:00:00Z")))
        .isInstanceOf(AccessDeniedException.class);
    signIn("test.teacher.a");
    assertThatThrownBy(
            () ->
                accounting.correctStatus(
                    1L,
                    MONDAY,
                    AttendanceStatus.ENTSCHULDIGT,
                    Instant.parse("2026-09-13T21:59:59Z")))
        .hasMessage("INVALID_STATUS_TIME");
    assertThatThrownBy(
            () ->
                accounting.correctStatus(
                    1L,
                    MONDAY,
                    AttendanceStatus.ENTSCHULDIGT,
                    Instant.parse("2026-09-14T09:00:00Z")))
        .hasMessage("INVALID_STATUS_TIME");
    assertThat(
            accounting.correctStatus(
                1L, MONDAY, AttendanceStatus.ENTSCHULDIGT, Instant.parse("2026-09-13T22:00:00Z")))
        .isTrue();
    assertAccount(1, 0, 180);
  }

  @Test
  void resetCompletesMissingDaysAtomicallyAndLocksItsWholeBerlinDay() {
    signIn("test.admin");
    assertThat(classes.resetAccounts(1L, 1L)).isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE event_type = 'DAILY_CLOSE'",
                Integer.class))
        .isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE event_type = 'ACCOUNT_RESET'",
                Integer.class))
        .isEqualTo(6);
    assertAccount(1, 0, 0);
    assertThat(scan(1, "2026-09-14T06:00:00Z")).isFalse();
    assertThatThrownBy(
            () ->
                accounting.correctStatus(
                    1L,
                    MONDAY,
                    AttendanceStatus.ENTSCHULDIGT,
                    Instant.parse("2026-09-14T06:00:00Z")))
        .hasMessage("ATTENDANCE_DAY_LOCKED_OR_UNPLANNED");
    assertThat(accounting.closeDueDays()).isEqualTo(1);
    assertAccount(1, 0, 0);
    assertThat(classes.resetAccounts(1L, 1L)).isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE event_type = 'DAILY_CLOSE'",
                Integer.class))
        .isEqualTo(7);
  }

  @Test
  void neverRecreatesExpiredHistoryOrResetsAccountsDuringRetention() {
    accounting.closeDueDays();
    setTime("2027-06-29T22:00:00Z");
    assertThat(retention.deleteHistory()).isEqualTo(7);
    assertThat(accounting.closeDueDays()).isZero();
    assertThat(scan(1, "2026-09-14T06:00:00Z")).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isZero();
    assertAccount(1, 180, 0);
  }

  @Test
  void failedScanAuditRollsBackTheNewAttendanceAndAccount() {
    jdbc.execute(
        "ALTER TABLE attendance_audit ADD CONSTRAINT test_reject_booking CHECK (student_id <> 1)");
    try {
      assertThatThrownBy(() -> scan(1, "2026-09-14T06:00:00Z"))
          .isInstanceOf(DataAccessException.class);
      assertAccount(1, 0, 0);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isZero();
    } finally {
      jdbc.execute("ALTER TABLE attendance_audit DROP CHECK test_reject_booking");
    }
  }

  @Test
  void failedClassCloseRollsBackThatClassAndStillProcessesTheNextClass() {
    jdbc.execute(
        "ALTER TABLE attendance_audit ADD CONSTRAINT test_reject_booking CHECK (student_id <> 2)");
    try {
      assertThat(accounting.closeDueDays()).isEqualTo(1);
      assertAccount(1, 0, 0);
      assertAccount(7, 180, 0);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class))
          .isEqualTo(1);
    } finally {
      jdbc.execute("ALTER TABLE attendance_audit DROP CHECK test_reject_booking");
    }
    assertThat(accounting.closeDueDays()).isEqualTo(6);
    assertAccount(1, 180, 0);
  }

  @Test
  void concurrentScansAcceptExactlyOneAndConcurrentCloseNeverDoubleBooks() throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first =
          workers.submit(
              () -> {
                barrier.await();
                return scan(1, "2026-09-14T06:00:00Z");
              });
      var second =
          workers.submit(
              () -> {
                barrier.await();
                return scan(1, "2026-09-14T06:00:00Z");
              });
      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      var closeFirst = workers.submit(accounting::closeDueDays);
      var closeSecond = workers.submit(accounting::closeDueDays);
      assertThat(closeFirst.get(20, TimeUnit.SECONDS) + closeSecond.get(20, TimeUnit.SECONDS))
          .isEqualTo(6);
    }
    assertAccount(1, 30, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE student_id = 1", Integer.class))
        .isEqualTo(1);
  }

  private boolean scan(long student, String at) {
    return accounting.recordScan(student, 9001, Instant.parse(at));
  }

  @Test
  void concurrentLateScanAndCloseReachTheSameAuditedAccount() throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var scan =
          workers.submit(
              () -> {
                barrier.await();
                return scan(1, "2026-09-14T06:00:00Z");
              });
      var close =
          workers.submit(
              () -> {
                barrier.await();
                return accounting.closeDueDays();
              });
      assertThat(scan.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(close.get(20, TimeUnit.SECONDS)).isBetween(6, 7);
    }
    assertAccount(1, 30, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(unexcused_minutes_delta) FROM attendance_audit"
                    + " WHERE student_id = 1",
                Integer.class))
        .isEqualTo(30);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance WHERE student_id = 1", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void scanWaitingForRetentionCannotRecreateHistoryWhenTheDeadlineExpires() throws Exception {
    accounting.closeDueDays();
    setTime("2027-06-29T21:59:59Z");
    var locked = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    var attempted = new CompletableFuture<Void>();
    try (var workers = Executors.newFixedThreadPool(2)) {
      var deletion =
          workers.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          transaction -> {
                            retentionRepository.lockDueDeletionDates(LocalDate.of(2027, 6, 30));
                            locked.complete(null);
                            release.join();
                            return retention.deleteHistory();
                          }));
      try {
        locked.get(10, TimeUnit.SECONDS);
        var scan =
            workers.submit(
                () -> {
                  attempted.complete(null);
                  return scan(1, "2026-09-14T06:00:00Z");
                });
        attempted.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> scan.get(300, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        setTime("2027-06-29T22:00:00Z");
        release.complete(null);
        assertThat(deletion.get(10, TimeUnit.SECONDS)).isEqualTo(7);
        assertThat(scan.get(10, TimeUnit.SECONDS)).isFalse();
      } finally {
        release.complete(null);
      }
    }
    assertAccount(1, 180, 0);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_audit", Integer.class))
        .isZero();
  }

  private void assertAccount(long student, int unexcused, int excused) {
    assertThat(
            jdbc.queryForMap(
                "SELECT unexcused_minutes_account, excused_minutes_account FROM student WHERE id = ?",
                student))
        .containsEntry("unexcused_minutes_account", unexcused)
        .containsEntry("excused_minutes_account", excused);
  }

  private void setTime(String now) {
    when(clock.instant()).thenReturn(Instant.parse(now));
  }

  private void signIn(String username) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(username, null, List.of()));
  }
}
