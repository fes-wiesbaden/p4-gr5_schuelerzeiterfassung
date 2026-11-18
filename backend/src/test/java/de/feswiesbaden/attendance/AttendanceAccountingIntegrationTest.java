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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.junit.jupiter.params.provider.ValueSource;
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

/** Prüft Tagesstatus, Minutenabrechnung und unveränderliche Audits mit MySQL */
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

  /** Leert die Tagesabrechnung und setzt Mitarbeiter und Testuhr auf feste Ausgangswerte */
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

  /** Entfernt den Sicherheitskontext und die Testdaten in der Reihenfolge ihrer Abhängigkeiten */
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

  /** Prüft Minutenkonten und Auditfelder für jeden Tagesstatus */
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
    var at = Instant.parse("2026-09-14T05:30:00Z");
    assertThat(accounting.correctStatus(1L, MONDAY, status, at)).isTrue();
    assertAccount(1, unexcused, excused);
    var attendanceId =
        jdbc.queryForObject("SELECT id FROM attendance WHERE student_id = 1", Long.class);
    assertThat(jdbc.queryForMap("SELECT * FROM attendance_audit WHERE student_id = 1"))
        .containsEntry("student_id", 1L)
        .containsEntry("attendance_id", attendanceId)
        .containsEntry("event_type", "STATUS_CHANGE")
        .containsEntry("changed_by_staff_id", 1L)
        .containsEntry("terminal_id", null)
        .containsEntry("deletion_date_id", 1L)
        .containsEntry("old_status", status == AttendanceStatus.ABWESEND ? null : "ABWESEND")
        .containsEntry("new_status", status == AttendanceStatus.ABWESEND ? null : status.name())
        .containsEntry("occurred_at", LocalDateTime.ofInstant(at, ZoneOffset.UTC))
        .containsEntry("created_at", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
        .containsEntry("unexcused_minutes_delta", unexcused)
        .containsEntry("excused_minutes_delta", excused);
  }

  /**
   * Prüft Statuswechsel mit Null-Deltas, unveränderte ältere Audits und wirkungslose Wiederholungen
   */
  @ParameterizedTest
  @CsvSource({
    "ANWESEND,ABWESEND,180,0",
    "ABWESEND,ENTSCHULDIGT,-180,180",
    "ENTSCHULDIGT,MIT_ATTEST_ENTSCHULDIGT,0,0",
    "MIT_ATTEST_ENTSCHULDIGT,BETRIEBLICH_ENTSCHULDIGT,0,0",
    "BETRIEBLICH_ENTSCHULDIGT,BETRIEB,0,-180",
    "BETRIEB,ANWESEND,0,0"
  })
  void auditsStatusTransitionsIncludingZeroDeltasWithoutRewritingHistory(
      AttendanceStatus before, AttendanceStatus after, int unexcusedDelta, int excusedDelta) {
    var at = Instant.parse("2026-09-14T05:30:00Z");
    assertThat(accounting.correctStatus(1L, MONDAY, before, at)).isTrue();
    var original = jdbc.queryForMap("SELECT * FROM attendance_audit WHERE student_id = 1");

    assertThat(accounting.correctStatus(1L, MONDAY, after, at)).isTrue();
    assertThat(jdbc.queryForMap("SELECT * FROM attendance_audit WHERE id = ?", original.get("id")))
        .isEqualTo(original);
    assertThat(
            jdbc.queryForMap(
                "SELECT * FROM attendance_audit WHERE student_id = 1 ORDER BY id DESC LIMIT 1"))
        .containsEntry("attendance_id", original.get("attendance_id"))
        .containsEntry("event_type", "STATUS_CHANGE")
        .containsEntry("changed_by_staff_id", 1L)
        .containsEntry("terminal_id", null)
        .containsEntry("deletion_date_id", 1L)
        .containsEntry("old_status", before.name())
        .containsEntry("new_status", after.name())
        .containsEntry("occurred_at", LocalDateTime.ofInstant(at, ZoneOffset.UTC))
        .containsEntry("unexcused_minutes_delta", unexcusedDelta)
        .containsEntry("excused_minutes_delta", excusedDelta);
    assertThat(
            jdbc.queryForObject("SELECT status FROM attendance WHERE student_id = 1", String.class))
        .isEqualTo(after.name());

    var audits = jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id");
    assertThat(audits).hasSize(2);
    assertThat(accounting.correctStatus(1L, MONDAY, after, at)).isFalse();
    assertThat(jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id")).isEqualTo(audits);
  }

  /** Prüft den Abschluss geplanter Tage und die Wiederholung ohne zusätzliche Buchungen */
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

  /** Prüft, dass nur vergangene Slotanteile und keine Pausen gebucht werden */
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
    assertThat(
            jdbc.queryForMap(
                "SELECT * FROM attendance_audit WHERE student_id = 1 ORDER BY id DESC LIMIT 1"))
        .containsEntry("event_type", "STATUS_CHANGE")
        .containsEntry("changed_by_staff_id", 1L)
        .containsEntry("old_status", null)
        .containsEntry("new_status", null)
        .containsEntry("unexcused_minutes_delta", 0)
        .containsEntry("excused_minutes_delta", 60);
    setTime("2026-09-14T07:10:00Z");
    assertThat(accounting.correctStatus(1L, MONDAY, AttendanceStatus.ENTSCHULDIGT, at)).isFalse();
    assertAccount(1, 30, 60);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_audit", Integer.class))
        .isEqualTo(2);
  }

  /** Prüft die Addition von Slotanteilen und die getrennte Abrundung beider Minutenkonten */
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

  /** Prüft rückwirkendes Entschuldigen bei Erhalt späterer Scans und bestehender Audits */
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
                "SELECT old_status, new_status, unexcused_minutes_delta, excused_minutes_delta"
                    + " FROM attendance_audit WHERE student_id = 1 ORDER BY id DESC LIMIT 1"))
        .containsEntry("old_status", "ABWESEND")
        .containsEntry("new_status", "ENTSCHULDIGT")
        .containsEntry("unexcused_minutes_delta", -60)
        .containsEntry("excused_minutes_delta", 60);
    assertThat(
            jdbc.queryForObject("SELECT status FROM attendance WHERE student_id = 1", String.class))
        .isEqualTo("ANWESEND");
  }

  /**
   * Prüft verspätet zugestellte Scans anhand ihrer ursprünglichen Zeit und späterer
   * Statusereignisse
   */
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

  /** Prüft die unveränderten Tages- und Kontendaten bei Scans mit unzulässigem Ausgangsstatus */
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

  /**
   * Prüft die Ablehnung ungültiger Scanzeiten, Terminalräume und Blockzuordnungen ohne neue
   * Anwesenheit
   */
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

  /** Prüft Klassenrechte, Berliner Tagesgrenzen und das Verbot zukünftiger Korrekturzeiten */
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
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_audit", Integer.class))
        .isZero();
    assertAccount(1, 0, 0);
    assertThat(
            accounting.correctStatus(
                1L, MONDAY, AttendanceStatus.ENTSCHULDIGT, Instant.parse("2026-09-13T22:00:00Z")))
        .isTrue();
    assertAccount(1, 0, 180);
  }

  /**
   * Prüft vergangene Tageskorrekturen und den protokollierten Akteur für alle berechtigten Rollen
   */
  @ParameterizedTest
  @CsvSource({"test.teacher.a,1", "test.teacher.b,2", "test.admin,3"})
  void permitsPastDayCorrectionsForClassTeacherAssignedTeacherAndAdministrator(
      String username, long staffId) {
    jdbc.update("DELETE FROM teacher_class WHERE staff_id = 1 AND school_class_id = 1");
    jdbc.update("INSERT INTO teacher_class (staff_id, school_class_id) VALUES (2, 1)");
    signIn(username);
    setTime("2026-09-15T08:45:00Z");
    var at = Instant.parse("2026-09-13T22:00:00Z");

    assertThat(accounting.correctStatus(1L, MONDAY, AttendanceStatus.ENTSCHULDIGT, at)).isTrue();
    assertAccount(1, 0, 180);
    assertThat(
            jdbc.queryForMap("SELECT attendance_date, status FROM attendance WHERE student_id = 1"))
        .containsEntry("attendance_date", java.sql.Date.valueOf(MONDAY))
        .containsEntry("status", "ENTSCHULDIGT");
    assertThat(jdbc.queryForMap("SELECT * FROM attendance_audit WHERE student_id = 1"))
        .containsEntry("changed_by_staff_id", staffId)
        .containsEntry("occurred_at", LocalDateTime.ofInstant(at, ZoneOffset.UTC))
        .containsEntry("created_at", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
  }

  /** Prüft nachgeholte Tagesabschlüsse beim Reset und die Sperre des gesamten Berliner Resettags */
  @ParameterizedTest
  @ValueSource(strings = {"2026-09-14T09:00:00Z", "2026-09-15T09:00:00Z"})
  void resetCompletesMissingDaysAtomicallyAndLocksItsWholeBerlinDay(String correctionTime) {
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
    var audits = jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id");
    var attendances = jdbc.queryForList("SELECT * FROM attendance ORDER BY id");
    setTime(correctionTime);
    signIn("test.teacher.a");
    assertThat(scan(1, "2026-09-14T06:00:00Z")).isFalse();
    assertThatThrownBy(
            () ->
                accounting.correctStatus(
                    1L,
                    MONDAY,
                    AttendanceStatus.ENTSCHULDIGT,
                    Instant.parse("2026-09-14T06:00:00Z")))
        .hasMessage("ATTENDANCE_DAY_LOCKED_OR_UNPLANNED");
    assertThat(jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id")).isEqualTo(audits);
    assertThat(jdbc.queryForList("SELECT * FROM attendance ORDER BY id")).isEqualTo(attendances);
    assertAccount(1, 0, 0);
    assertThat(accounting.closeDueDays()).isEqualTo(1);
    assertAccount(1, 0, 0);
    signIn("test.admin");
    assertThat(classes.resetAccounts(1L, 1L)).isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE event_type = 'DAILY_CLOSE'",
                Integer.class))
        .isEqualTo(7);
  }

  /**
   * Prüft, dass die Löschung abgelaufener Historie Konten erhält und spätere Aufrufe keine Historie
   * neu anlegen
   */
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

  /** Prüft das Zurückrollen von Anwesenheit und Konto bei einem Fehler im Audit */
  @ParameterizedTest
  @ValueSource(strings = {"SCAN", "STATUS_CHANGE"})
  void failedAuditRollsBackTheNewAttendanceAndAccount(String eventType) {
    jdbc.execute(
        "ALTER TABLE attendance_audit ADD CONSTRAINT test_reject_booking CHECK (student_id <> 1)");
    try {
      assertThatThrownBy(
              () -> {
                if (eventType.equals("SCAN")) {
                  scan(1, "2026-09-14T06:00:00Z");
                } else {
                  accounting.correctStatus(
                      1L,
                      MONDAY,
                      AttendanceStatus.ENTSCHULDIGT,
                      Instant.parse("2026-09-14T06:00:00Z"));
                }
              })
          .isInstanceOf(DataAccessException.class);
      assertAccount(1, 0, 0);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isZero();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_audit", Integer.class))
          .isZero();
    } finally {
      jdbc.execute("ALTER TABLE attendance_audit DROP CHECK test_reject_booking");
    }
  }

  /**
   * Prüft das Zurückrollen einer fehlerhaften Klasse und den unabhängigen Abschluss der nächsten
   */
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

  /** Prüft genau einen akzeptierten parallelen Scan und Tagesabschlüsse ohne Doppelbuchung */
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

  /** Ruft die Scanabrechnung für Terminal 9001 auf und liefert, ob der Testscan angenommen wurde */
  private boolean scan(long student, String at) {
    return accounting.recordScan(student, 9001, Instant.parse(at));
  }

  /**
   * Prüft konsistente Konten und Audits bei gleichzeitigem nachträglichem Scan und Tagesabschluss
   */
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

  /** Prüft, dass ein wartender Scan nach Fristablauf keine gelöschte Historie neu anlegt */
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

  /** Prüft beide gespeicherten Minutenkonten des Testschülers */
  private void assertAccount(long student, int unexcused, int excused) {
    assertThat(
            jdbc.queryForMap(
                "SELECT unexcused_minutes_account, excused_minutes_account FROM student WHERE id = ?",
                student))
        .containsEntry("unexcused_minutes_account", unexcused)
        .containsEntry("excused_minutes_account", excused);
  }

  /** Setzt den aktuellen Zeitpunkt der Testuhr */
  private void setTime(String now) {
    when(clock.instant()).thenReturn(Instant.parse(now));
  }

  /** Setzt den angegebenen Testmitarbeiter als authentifizierten Benutzer */
  private void signIn(String username) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(username, null, List.of()));
  }
}
