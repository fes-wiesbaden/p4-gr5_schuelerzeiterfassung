package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.feswiesbaden.attendance.dtos.RawScanDTO;
import de.feswiesbaden.attendance.enums.ScanResult;
import de.feswiesbaden.attendance.login.StaffPrincipal;
import de.feswiesbaden.attendance.service.AttendanceAccountingService;
import de.feswiesbaden.attendance.service.BlockPlanService;
import de.feswiesbaden.attendance.service.RetentionService;
import de.feswiesbaden.attendance.service.SchoolClassService;
import de.feswiesbaden.attendance.service.StudentService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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

/** Prüft klassenweise Kontenresets und Blockplanwechsel mit MySQL */
@SpringBootTest(properties = "attendance.retention.scheduling.enabled=false")
@Testcontainers
@Sql("/fixtures/attendance.sql")
class ClassAccountsIntegrationTest {
  @Container @ServiceConnection
  static MySQLContainer<?> mysql =
      new MySQLContainer<>("mysql:8.0").withUrlParam("connectionTimeZone", "UTC");

  @Autowired JdbcTemplate jdbc;

  @Autowired AttendanceAccountingService attendance;

  @Autowired SchoolClassService classes;

  @Autowired BlockPlanService plans;

  @Autowired StudentService students;

  @Autowired RetentionService retention;

  @Autowired PlatformTransactionManager transactions;

  @MockitoBean Clock clock;

  /** Meldet den Testadministrator an und setzt die Testuhr auf einen festen Zeitpunkt */
  @BeforeEach
  void prepare() {
    signIn("test.admin");
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

  /** Prüft wiederholte Klassenresets mit einem Audit je Schüler einschließlich leerer Konten */
  @Test
  void resetsTheClassRepeatedlyAndAuditsEveryStudentIncludingEmptyAccounts() {
    var attendances = jdbc.queryForList("SELECT * FROM attendance ORDER BY id");
    var deadlines = jdbc.queryForList("SELECT * FROM deletion_date ORDER BY id");
    assertThat(classes.resetAccounts(1L, 1L)).isEqualTo(6);
    assertThat(
            jdbc.query(
                "SELECT student_id, unexcused_minutes_delta, excused_minutes_delta"
                    + " FROM attendance_audit WHERE event_type = 'ACCOUNT_RESET' ORDER BY id",
                (row, index) -> List.of(row.getInt(1), row.getInt(2), row.getInt(3))))
        .containsExactly(
            List.of(1, -180, 0),
            List.of(2, -90, 0),
            List.of(3, -150, 0),
            List.of(4, -30, -60),
            List.of(5, 0, 0),
            List.of(6, 0, 0));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE event_type = 'ACCOUNT_RESET'"
                    + " AND changed_by_staff_id = 3 AND deletion_date_id = 1 AND attendance_id IS NULL"
                    + " AND terminal_id IS NULL AND old_status IS NULL AND new_status IS NULL"
                    + " AND occurred_at = '2026-09-14 08:45:00'",
                Integer.class))
        .isEqualTo(6);
    var originalAudits = jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id");
    setTime("2026-09-14T09:00:00Z");
    assertThat(classes.resetAccounts(1L, 1L)).isEqualTo(6);
    var audits = jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id");
    assertThat(audits.subList(0, originalAudits.size())).isEqualTo(originalAudits);
    assertThat(audits.subList(originalAudits.size(), audits.size()))
        .hasSize(6)
        .allSatisfy(
            audit ->
                assertThat(audit)
                    .containsEntry("event_type", "ACCOUNT_RESET")
                    .containsEntry("changed_by_staff_id", 3L)
                    .containsEntry("deletion_date_id", 1L)
                    .containsEntry("attendance_id", null)
                    .containsEntry("terminal_id", null)
                    .containsEntry("old_status", null)
                    .containsEntry("new_status", null)
                    .containsEntry("occurred_at", java.time.LocalDateTime.of(2026, 9, 14, 9, 0))
                    .containsEntry("unexcused_minutes_delta", 0)
                    .containsEntry("excused_minutes_delta", 0));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE event_type = 'ACCOUNT_RESET'",
                Integer.class))
        .isEqualTo(12);
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(unexcused_minutes_account + excused_minutes_account)"
                    + " FROM student WHERE school_class_id = 1",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT unexcused_minutes_account FROM student WHERE id = 7", Integer.class))
        .isEqualTo(180);
    assertThat(jdbc.queryForList("SELECT * FROM attendance ORDER BY id")).isEqualTo(attendances);
    assertThat(jdbc.queryForList("SELECT * FROM deletion_date ORDER BY id")).isEqualTo(deadlines);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_scan", Integer.class)).isEqualTo(7);
  }

  /** Setzt den angegebenen Testmitarbeiter als authentifizierten Benutzer */
  private void signIn(String username) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                new StaffPrincipal(
                    jdbc
                        .queryForList("SELECT id FROM staff WHERE username=?", Long.class, username)
                        .stream()
                        .findFirst()
                        .orElse(-1L),
                    username,
                    "unused",
                    List.of()),
                null,
                List.of()));
  }

  /** Prüft den auditierten Abschlussreset durch Admin oder Klassenleitung beim Planwechsel */
  @ParameterizedTest
  @CsvSource({"test.admin,3", "test.teacher.a,1"})
  void changesPlanWithFinalResetAndKeepsClassStudentsAndTeachers(String username, long staffId) {
    Long next = plans.create("Folgeplan", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31));
    var students = jdbc.queryForList("SELECT id, school_class_id FROM student ORDER BY id");
    var teachers =
        jdbc.queryForList("SELECT * FROM teacher_class ORDER BY staff_id, school_class_id");
    var audits = jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id");
    setTime("2027-02-01T12:00:00Z");
    signIn(username);
    classes.changeBlockPlan(1L, 1L, next);
    assertThat(classes.list())
        .contains(new SchoolClassService.ClassView(1L, "26TEST-A", 1L, next, 1L));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_block_assignment WHERE school_class_id = 1",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_block_assignment WHERE school_class_id IN (2, 3)",
                Integer.class))
        .isEqualTo(2);
    assertThat(jdbc.queryForList("SELECT id, school_class_id FROM student ORDER BY id"))
        .isEqualTo(students);
    assertThat(
            jdbc.queryForList(
                "SELECT id FROM student WHERE school_class_id = 1"
                    + " AND (unexcused_minutes_account <> 0 OR excused_minutes_account <> 0)"))
        .isEmpty();
    assertThat(jdbc.queryForList("SELECT * FROM teacher_class ORDER BY staff_id, school_class_id"))
        .isEqualTo(teachers);
    assertThat(jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id")).containsAll(audits);
    assertThat(
            jdbc.queryForList(
                "SELECT changed_by_staff_id FROM attendance_audit"
                    + " WHERE event_type = 'ACCOUNT_RESET' AND deletion_date_id = 1",
                Long.class))
        .containsExactly(staffId, staffId, staffId, staffId, staffId, staffId);
  }

  /** Prüft die Ablehnung gebuchter Minuten an ungeplanten Tagen trotz passender Kontensumme */
  @Test
  void rejectsBookedMinutesOnAnUnplannedDayInsteadOfTrustingTheAccountBalance() {
    setTime("2026-09-15T16:00:00Z");
    jdbc.update(
        "INSERT INTO attendance (id, student_id, attendance_date, status) VALUES (20, 1, '2026-09-15', 'ABWESEND')");
    jdbc.update(
        "INSERT INTO attendance_audit (student_id, attendance_id, deletion_date_id, event_type,"
            + " occurred_at, unexcused_minutes_delta) VALUES (1, 20, 1, 'DAILY_CLOSE', '2026-09-15 12:00:00', 60)");
    jdbc.update("UPDATE student SET unexcused_minutes_account = 240 WHERE id = 1");
    assertThatThrownBy(() -> classes.resetAccounts(1L, 1L)).hasMessage("CLASS_NOT_FULLY_ACCOUNTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT unexcused_minutes_account FROM student WHERE id = 1", Integer.class))
        .isEqualTo(240);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE event_type = 'ACCOUNT_RESET'",
                Integer.class))
        .isZero();
  }

  /** Prüft sekundengenaue Resetzeiten für die unmittelbare Wiederholung */
  @Test
  void storesResetTimeAtDatabasePrecisionForImmediateRepeat() {
    setTime("2026-09-14T08:45:00.900Z");
    assertThat(classes.resetAccounts(1L, 1L)).isEqualTo(6);
    assertThat(classes.resetAccounts(1L, 1L)).isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "SELECT MAX(occurred_at) FROM attendance_audit WHERE event_type = 'ACCOUNT_RESET'",
                LocalDateTime.class))
        .isEqualTo(LocalDateTime.parse("2026-09-14T08:45:00"));
  }

  /** Prüft, dass Slots ohne Dauer weder den Reset blockieren noch Anwesenheiten erfordern */
  @Test
  void zeroLengthSlotsNeitherBlockResetNorRequireAttendance() {
    jdbc.update("INSERT INTO timetable (id, name) VALUES (3, 'Nullzeit')");
    jdbc.update(
        "INSERT INTO timetable_slot (timetable_id, room_id, weekday, start_time, end_time)"
            + " VALUES (3, 1, 'MONTAG', '12:00:00', '12:00:00')");
    Long classId = classes.create("Nullzeit", 1L, 1L, 3L);
    jdbc.update(
        "INSERT INTO class_block_assignment (school_class_id, block_assignment_id) VALUES (?, 1)",
        classId);
    Long studentId = students.create(classId, "Test", "Nullzeit", LocalDate.of(2008, 1, 1));
    jdbc.update("UPDATE student SET created_at = '2026-09-14 04:00:00' WHERE id = ?", studentId);
    setTime("2026-09-14T05:00:00Z");
    assertThat(classes.resetAccounts(classId, 1L)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance WHERE student_id = ?", Integer.class, studentId))
        .isZero();
  }

  /** Prüft, dass eine Unterrichtszuordnung allein keinen Reset oder Planwechsel erlaubt */
  @ParameterizedTest
  @ValueSource(strings = {"test.teacher.b", "test.unknown", "missing"})
  void rejectsUnauthorizedResetAndPlanChange(String username) {
    Long next = nextPlan();
    jdbc.update("INSERT INTO teacher_class (staff_id, school_class_id) VALUES (2, 1)");
    setTime("2026-12-31T23:00:00Z");
    if (username.equals("missing")) {
      SecurityContextHolder.clearContext();
    } else {
      signIn(username);
    }
    assertThatThrownBy(() -> classes.resetAccounts(1L, 1L))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> classes.changeBlockPlan(1L, 1L, next))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(resetCount()).isZero();
  }

  /** Prüft, dass auch die Klassenleitung einzelne Kontenresets dem Admin überlassen muss */
  @Test
  void rejectsStandaloneResetByClassTeacher() {
    signIn("test.teacher.a");
    assertThatThrownBy(() -> classes.resetAccounts(1L, 1L))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(resetCount()).isZero();
  }

  /** Prüft fehlende Klassen und inzwischen geänderte Pläne ohne Änderung der Konten */
  @Test
  void rejectsMissingClassesAndStaleExpectedPlansWithoutChangingAccounts() {
    assertThatThrownBy(() -> classes.resetAccounts(999L, 1L)).hasMessage("CLASS_NOT_FOUND");
    assertThatThrownBy(() -> classes.resetAccounts(1L, 999L)).hasMessage("CLASS_PLAN_CHANGED");
    assertThatThrownBy(() -> classes.changeBlockPlan(1L, 999L, 2L))
        .hasMessage("CLASS_PLAN_CHANGED");
    assertThat(
            jdbc.queryForObject(
                "SELECT unexcused_minutes_account FROM student WHERE id = 1", Integer.class))
        .isEqualTo(180);
    assertThat(resetCount()).isZero();
  }

  /** Prüft die Resetsperre vor dem Ende aller heutigen Slots */
  @ParameterizedTest
  @ValueSource(strings = {"2026-09-14T05:29:59Z", "2026-09-14T07:01:00Z", "2026-09-14T08:44:59Z"})
  void rejectsResetBeforeAllOfTodaysSlotsHaveEnded(String now) {
    setTime(now);
    assertThatThrownBy(() -> classes.resetAccounts(1L, 1L)).hasMessage("CLASS_HAS_REMAINING_SLOTS");
    assertThat(resetCount()).isZero();
  }

  /** Prüft die Ablehnung unvollständiger oder widersprüchlicher Tagesabrechnungen */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing_status",
        "wrong_status",
        "wrong_account",
        "audit_without_attendance",
        "foreign_attendance"
      })
  void rejectsIncompleteOrInconsistentDailyAccounting(String defect) {
    switch (defect) {
      case "missing_status" ->
          jdbc.update("UPDATE attendance_audit SET new_status = NULL WHERE id = 8");
      case "wrong_status" -> jdbc.update("UPDATE attendance SET status = 'BETRIEB' WHERE id = 5");
      case "wrong_account" ->
          jdbc.update("UPDATE student SET unexcused_minutes_account = 179 WHERE id = 1");
      case "audit_without_attendance", "foreign_attendance" -> {
        jdbc.update(
            "INSERT INTO attendance_audit (student_id, attendance_id, deletion_date_id, event_type, occurred_at,"
                + " unexcused_minutes_delta) VALUES (1, ?, 1, 'DAILY_CLOSE', '2026-09-14 08:45:00', 60)",
            defect.equals("foreign_attendance") ? 7L : null);
        jdbc.update("UPDATE student SET unexcused_minutes_account = 240 WHERE id = 1");
      }
      default -> throw new AssertionError(defect);
    }
    var accounts = jdbc.queryForList("SELECT * FROM student ORDER BY id");
    assertThatThrownBy(() -> classes.resetAccounts(1L, 1L)).hasMessage("CLASS_NOT_FULLY_ACCOUNTED");
    assertThat(jdbc.queryForList("SELECT * FROM student ORDER BY id")).isEqualTo(accounts);
    assertThat(resetCount()).isZero();
  }

  /** Prüft das Nachholen fehlender Tagesbuchungen innerhalb des Kontenresets */
  @ParameterizedTest
  @ValueSource(strings = {"missing_attendance", "partial_total"})
  void completesMissingAccountingBeforeResetInsteadOfWaitingForTheNightJob(String missing) {
    if (missing.equals("missing_attendance")) {
      jdbc.update("DELETE FROM attendance_audit WHERE attendance_id = 1");
      jdbc.update("DELETE FROM attendance WHERE id = 1");
      jdbc.update("UPDATE student SET unexcused_minutes_account = 0 WHERE id = 1");
    } else {
      jdbc.update("UPDATE attendance_audit SET unexcused_minutes_delta = 179 WHERE id = 1");
      jdbc.update("UPDATE student SET unexcused_minutes_account = 179 WHERE id = 1");
    }
    assertThat(classes.resetAccounts(1L, 1L)).isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(unexcused_minutes_delta) FROM attendance_audit"
                    + " WHERE student_id = 1 AND event_type = 'DAILY_CLOSE'",
                Integer.class))
        .isEqualTo(180);
    assertThat(
            jdbc.queryForObject(
                "SELECT unexcused_minutes_delta FROM attendance_audit"
                    + " WHERE student_id = 1 AND event_type = 'ACCOUNT_RESET'",
                Integer.class))
        .isEqualTo(-180);
  }

  /** Prüft für jeden Status die beim Kontenreset protokollierten Minutenabzüge */
  @ParameterizedTest
  @CsvSource({
    "ANWESEND,0,0",
    "BETRIEB,0,0",
    "ABWESEND,180,0",
    "ENTSCHULDIGT,0,180",
    "MIT_ATTEST_ENTSCHULDIGT,0,180",
    "BETRIEBLICH_ENTSCHULDIGT,0,180"
  })
  void verifiesTheDocumentedMinuteContributionOfEveryStatus(
      String status, int unexcused, int excused) {
    jdbc.update("UPDATE attendance SET status = ? WHERE id = 5", status);
    jdbc.update(
        "UPDATE attendance_audit SET event_type = ?, new_status = ?, old_status = ?, terminal_id = NULL,"
            + " changed_by_staff_id = ?, unexcused_minutes_delta = ?, excused_minutes_delta = ? WHERE id = 8",
        status.equals("ABWESEND") ? "DAILY_CLOSE" : "STATUS_CHANGE",
        status.equals("ABWESEND") ? null : status,
        status.equals("ABWESEND") ? null : "ABWESEND",
        status.equals("ABWESEND") ? null : 3L,
        unexcused,
        excused);
    jdbc.update(
        "UPDATE student SET unexcused_minutes_account = ?, excused_minutes_account = ? WHERE id = 5",
        unexcused,
        excused);
    assertThat(classes.resetAccounts(1L, 1L)).isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "SELECT unexcused_minutes_delta FROM attendance_audit"
                    + " WHERE student_id = 5 AND event_type = 'ACCOUNT_RESET'",
                Integer.class))
        .isEqualTo(-unexcused);
    assertThat(
            jdbc.queryForObject(
                "SELECT excused_minutes_delta FROM attendance_audit"
                    + " WHERE student_id = 5 AND event_type = 'ACCOUNT_RESET'",
                Integer.class))
        .isEqualTo(-excused);
  }

  /** Prüft die Reihenfolge zeitgleicher Statusereignisse anhand ihrer Audit-ID */
  @Test
  void resolvesSameTimestampStatusEventsByTheirAuditId() {
    jdbc.update(
        "INSERT INTO attendance_audit (student_id, attendance_id, deletion_date_id, event_type,"
            + " changed_by_staff_id, old_status, new_status, occurred_at, unexcused_minutes_delta)"
            + " VALUES (3, 3, 1, 'STATUS_CHANGE', 3, 'ABWESEND', 'ANWESEND', '2026-09-14 06:00:00', -150)");
    jdbc.update("UPDATE student SET unexcused_minutes_account = 0 WHERE id = 3");
    jdbc.update("UPDATE attendance SET status = 'ANWESEND' WHERE id = 3");
    assertThat(classes.resetAccounts(1L, 1L)).isEqualTo(6);
  }

  /** Prüft die Ablehnung nachträglicher Buchungen am bereits zurückgesetzten Tag */
  @Test
  void rejectsLaterBookingsOnTheResetDayAndDoesNotRebookEarlierDays() {
    assertThat(classes.resetAccounts(1L, 1L)).isEqualTo(6);
    setTime("2026-09-14T09:00:00Z");
    jdbc.update(
        "INSERT INTO attendance_audit (student_id, attendance_id, deletion_date_id, event_type,"
            + " changed_by_staff_id, old_status, new_status, occurred_at, unexcused_minutes_delta)"
            + " VALUES (5, 5, 1, 'STATUS_CHANGE', 3, 'ANWESEND', 'ABWESEND', '2026-09-14 05:30:00', 180)");
    jdbc.update("UPDATE student SET unexcused_minutes_account = 180 WHERE id = 5");
    jdbc.update("UPDATE attendance SET status = 'ABWESEND' WHERE id = 5");
    assertThatThrownBy(() -> classes.resetAccounts(1L, 1L)).hasMessage("RESET_DAY_LOCKED");
    assertThat(resetCount()).isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "SELECT unexcused_minutes_account FROM student WHERE id = 5", Integer.class))
        .isEqualTo(180);
  }

  /** Prüft Tagesabschluss und anschließenden Reset für einen späteren geplanten Tag */
  @Test
  void aLaterResetFirstBooksTheNewPlannedDayAndThenResetsItsMinutes() {
    classes.resetAccounts(1L, 1L);
    jdbc.update(
        "INSERT INTO block_assignment (id, block_plan_id, starts_on, ends_on)"
            + " VALUES (3, 1, '2026-09-28', '2026-10-02')");
    jdbc.update(
        "INSERT INTO class_block_assignment (school_class_id, block_assignment_id) VALUES (1, 3)");
    setTime("2026-09-28T08:45:00Z");
    assertThat(classes.resetAccounts(1L, 1L)).isEqualTo(6);
    assertThat(resetCount()).isEqualTo(12);
    assertThat(
            jdbc.queryForObject(
                "SELECT unexcused_minutes_delta FROM attendance_audit"
                    + " WHERE student_id = 1 AND event_type = 'ACCOUNT_RESET' ORDER BY id DESC LIMIT 1",
                Integer.class))
        .isEqualTo(-180);
  }

  /**
   * Prüft, dass die Abrechnung mit dem ganzen Berliner Erstellungstag des Schülerdatensatzes
   * beginnt
   */
  @ParameterizedTest
  @CsvSource({"2026-09-14T08:45:00,true", "2026-09-14T22:30:00,false"})
  void expectedDaysStartWithTheWholeBerlinEnrollmentDay(
      java.time.LocalDateTime enrolled, boolean monday) {
    Long student = students.create(1L, "Test", "Aufnahme", LocalDate.of(2008, 1, 1));
    jdbc.update("UPDATE student SET created_at = ? WHERE id = ?", enrolled, student);
    setTime("2026-09-15T16:00:00Z");
    assertThat(classes.resetAccounts(1L, 1L)).isEqualTo(7);
    assertThat(
            jdbc.queryForObject(
                "SELECT unexcused_minutes_delta FROM attendance_audit"
                    + " WHERE student_id = ? AND event_type = 'ACCOUNT_RESET'",
                Integer.class,
                student))
        .isEqualTo(monday ? -180 : 0);
  }

  /** Prüft das Zurückrollen aller Konten und Audits bei einem Fehler am zweiten Schüler */
  @Test
  void auditFailureOnTheSecondStudentRollsBackEveryAccountAndAudit() {
    var accounts = jdbc.queryForList("SELECT * FROM student ORDER BY id");
    var audits = jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id");
    jdbc.execute(
        "ALTER TABLE attendance_audit ADD CONSTRAINT test_reject_reset"
            + " CHECK (event_type <> 'ACCOUNT_RESET' OR student_id <> 2)");
    try {
      assertThatThrownBy(() -> classes.resetAccounts(1L, 1L))
          .isInstanceOf(DataAccessException.class);
      assertThat(jdbc.queryForList("SELECT * FROM student ORDER BY id")).isEqualTo(accounts);
      assertThat(jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id")).isEqualTo(audits);
    } finally {
      jdbc.execute("ALTER TABLE attendance_audit DROP CHECK test_reject_reset");
    }
  }

  /** Prüft Planende und einen abschließenden Reset nach dem letzten Berliner Plantag */
  @Test
  void planChangeNeedsPlanEndAndAFinalResetAfterThatBerlinDay() {
    Long next = nextPlan();
    setTime("2026-12-31T22:59:59Z");
    classes.resetAccounts(1L, 1L);
    assertThatThrownBy(() -> classes.changeBlockPlan(1L, 1L, next))
        .hasMessage("CURRENT_PLAN_NOT_FINISHED");
    setTime("2026-12-31T23:00:00Z");
    classes.changeBlockPlan(1L, 1L, next);
    assertThat(resetCount()).isEqualTo(12);
    assertThatThrownBy(() -> classes.resetAccounts(1L, 1L)).hasMessage("CLASS_PLAN_CHANGED");
  }

  /** Prüft ungültige Pläne und den Rollback des Resets bei einem fehlgeschlagenen Planwechsel */
  @Test
  void planChangeRejectsUnfinishedOldBlocksAndOverlappingOrMissingNewPlans() {
    Long next = nextPlan();
    setTime("2026-12-31T23:00:00Z");
    jdbc.update(
        "INSERT INTO block_assignment (id, block_plan_id, starts_on, ends_on)"
            + " VALUES (3, 1, '2027-01-01', '2027-01-03')");
    assertThatThrownBy(() -> classes.changeBlockPlan(1L, 1L, next))
        .hasMessage("CURRENT_PLAN_NOT_FINISHED");
    jdbc.update("DELETE FROM block_assignment WHERE id = 3");
    assertThatThrownBy(() -> classes.changeBlockPlan(1L, 1L, 999L))
        .hasMessage("BLOCK_PLAN_NOT_FOUND");
    Long overlap =
        plans.create("Ueberlappend", LocalDate.of(2026, 12, 31), LocalDate.of(2027, 12, 31));
    assertThatThrownBy(() -> classes.changeBlockPlan(1L, 1L, overlap))
        .hasMessage("NEXT_PLAN_OVERLAPS_CURRENT_PLAN");
    jdbc.execute(
        "ALTER TABLE school_class ADD CONSTRAINT test_reject_plan CHECK (block_plan_id = 1)");
    try {
      assertThatThrownBy(() -> classes.changeBlockPlan(1L, 1L, next))
          .isInstanceOf(DataAccessException.class);
    } finally {
      jdbc.execute("ALTER TABLE school_class DROP CHECK test_reject_plan");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT unexcused_minutes_account FROM student WHERE id = 1", Integer.class))
        .isEqualTo(180);
    assertThat(resetCount()).isZero();
  }

  /** Prüft den erneuten Abschlussreset nach einer späteren Schüleraufnahme */
  @Test
  void aNewStudentAfterFinalResetRequiresAnotherClassReset() {
    Long next = nextPlan();
    setTime("2026-12-31T23:00:00Z");
    classes.resetAccounts(1L, 1L);
    Long added = students.create(1L, "Test", "Nachaufnahme", LocalDate.of(2008, 1, 1));
    jdbc.update("UPDATE student SET created_at = '2027-01-01 00:01:00' WHERE id = ?", added);
    setTime("2027-01-01T00:02:00Z");
    classes.changeBlockPlan(1L, 1L, next);
    assertThat(
            jdbc.queryForList(
                "SELECT student_id FROM attendance_audit WHERE student_id = ? AND event_type = 'ACCOUNT_RESET'",
                Long.class,
                added))
        .containsExactly(added);
  }

  /** Prüft die Ablehnung eines beendeten Folgeplans auch bei gemeinsamem Löschdatum */
  @Test
  void rejectsFinishedTargetWithSharedRetentionDate() {
    Long first = plans.create("August-A", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 30));
    Long finished = plans.create("August-B", LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 31));
    Long classId = classes.create("26Kollision", 1L, first, 1L);
    Long student = students.create(classId, "Test", "Kollision", LocalDate.of(2008, 1, 1));
    jdbc.update("UPDATE student SET created_at = '2026-08-01 00:00:00' WHERE id = ?", student);
    setTime("2026-09-01T10:00:00Z");
    classes.resetAccounts(classId, first);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM deletion_date WHERE delete_after = '2027-02-28'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT d.delete_after FROM attendance_audit a"
                    + " JOIN deletion_date d ON d.id = a.deletion_date_id"
                    + " WHERE a.student_id = ? AND a.event_type = 'ACCOUNT_RESET'",
                LocalDate.class,
                student))
        .isEqualTo(LocalDate.of(2027, 2, 28));

    assertThatThrownBy(() -> classes.changeBlockPlan(classId, first, finished))
        .hasMessage("NEXT_PLAN_FINISHED");
    assertThat(classes.list())
        .filteredOn(schoolClass -> schoolClass.id().equals(classId))
        .extracting(SchoolClassService.ClassView::blockPlanId)
        .containsExactly(first);
  }

  /** Prüft den Wechsel auf einen noch laufenden Folgeplan mit bereits vergangenem Beginn */
  @ParameterizedTest
  @ValueSource(strings = {"2027-01-02", "2027-12-31"})
  void acceptsRunningTargetWithPastStartDate(LocalDate endsOn) {
    Long next = plans.create("Laufend", LocalDate.of(2027, 1, 1), endsOn);
    setTime("2027-01-02T12:00:00Z");
    classes.resetAccounts(1L, 1L);

    classes.changeBlockPlan(1L, 1L, next);

    assertThat(classes.list())
        .filteredOn(schoolClass -> schoolClass.id().equals(1L))
        .extracting(SchoolClassService.ClassView::blockPlanId)
        .containsExactly(next);
  }

  /** Prüft Reset und Planwechsel nach Ablauf der Historie bei unveränderter Löschfrist */
  @Test
  void expiredHistoryIsUnnecessaryForResetAndPlanChangeWithoutExtendingRetention() {
    Long next = nextPlan();
    setTime("2027-06-29T22:00:00Z");
    assertThat(retention.deleteHistory()).isEqualTo(11);
    signIn("test.teacher.a");
    classes.changeBlockPlan(1L, 1L, next);
    assertThat(
            jdbc.queryForObject(
                "SELECT delete_after FROM deletion_date WHERE id = 1", LocalDate.class))
        .isEqualTo(LocalDate.of(2027, 6, 30));
    assertThat(resetCount()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT unexcused_minutes_account FROM student WHERE id = 1", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance WHERE student_id IN (1,2,3,4,5,6)", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_scan", Integer.class)).isEqualTo(7);
  }

  /** Prüft Scan und Tagesabschluss, wenn der Wechsel am ersten Unterrichtstag erfolgt */
  @Test
  void booksFirstDayAfterThePlanChange() {
    Long old = plans.create("August-A", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 27));
    var firstDay = LocalDate.of(2026, 8, 31);
    Long next = plans.create("August-B", firstDay, firstDay);
    Long classId = classes.create("26Wechsel", 1L, old, 1L);
    Long scanned = students.create(classId, "Test", "Scan", LocalDate.of(2008, 1, 1));
    Long absent = students.create(classId, "Test", "Ohne Scan", LocalDate.of(2008, 1, 1));
    jdbc.update(
        "UPDATE student SET created_at = '2026-08-01 00:00:00' WHERE school_class_id = ?", classId);
    setTime("2026-08-31T05:00:00Z");
    classes.changeBlockPlan(classId, old, next);
    jdbc.update(
        "INSERT INTO block_assignment (id, block_plan_id, starts_on, ends_on) VALUES (3, ?, ?, ?)",
        next,
        firstDay,
        firstDay);
    jdbc.update("INSERT INTO class_block_assignment VALUES (?, 3)", classId);
    jdbc.update("UPDATE student SET rfid_uid = 'TEST-UID-WECHSEL' WHERE id = ?", scanned);
    setTime("2026-08-31T05:40:00Z");
    assertThat(
            attendance.recordScan(
                "CN=terminal-9001",
                new RawScanDTO(
                    UUID.randomUUID(),
                    "TEST-UID-WECHSEL",
                    9001,
                    Instant.parse("2026-08-31T05:40:00Z")),
                clock.instant()))
        .isEqualTo(ScanResult.SUCCESS);
    setTime("2026-08-31T08:45:00Z");
    attendance.closeDueDays();
    assertThat(
            jdbc.queryForObject(
                "SELECT unexcused_minutes_account FROM student WHERE id = ?",
                Integer.class,
                absent))
        .isEqualTo(180);
  }

  /** Prüft die Resetsperre bei fehlender Historie vor Ablauf der Aufbewahrungsfrist */
  @Test
  void missingHistoryBeforeRetentionExpiryStillPreventsReset() {
    setTime("2027-06-29T21:59:59Z");
    jdbc.update("DELETE FROM attendance_audit");
    jdbc.update("DELETE FROM attendance");
    assertThatThrownBy(() -> classes.resetAccounts(1L, 1L)).hasMessage("CLASS_NOT_FULLY_ACCOUNTED");
    assertThat(resetCount()).isZero();
  }

  /** Prüft den wirkungslosen Reset und den abgelehnten Planwechsel einer leeren Klasse */
  @Test
  void anEmptyClassCanResetButCannotChangePlan() {
    setTime("2026-12-31T23:00:00Z");
    Long empty = classes.create("Leer", 1L, 1L, 1L);
    assertThat(classes.resetAccounts(empty, 1L)).isZero();
    assertThatThrownBy(() -> classes.changeBlockPlan(empty, 1L, nextPlan()))
        .hasMessage("CLASS_HAS_NO_STUDENTS");
    assertThat(resetCount()).isZero();
  }

  /** Zählt die gespeicherten Kontenreset-Audits */
  private int resetCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM attendance_audit WHERE event_type = 'ACCOUNT_RESET'", Integer.class);
  }

  /** Prüft vollständige Schüleraudits für jeden von zwei parallelen Klassenresets */
  @Test
  void concurrentResetsEachAuditTheWholeClass() throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> resetTogether(barrier));
      var second = workers.submit(() -> resetTogether(barrier));
      assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(6);
      assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(6);
    }
    assertThat(
            jdbc.queryForList(
                "SELECT COUNT(*) FROM attendance_audit WHERE event_type = 'ACCOUNT_RESET'"
                    + " GROUP BY student_id ORDER BY student_id",
                Integer.class))
        .containsExactly(2, 2, 2, 2, 2, 2);
  }

  /**
   * Prüft das Warten auf eine parallele Aufnahme und den Reset einschließlich des neuen Schülers
   */
  @Test
  void resetWaitsForConcurrentEnrollmentAndIncludesTheNewStudent() throws Exception {
    setTime("2026-12-31T23:00:00Z");
    classes.resetAccounts(1L, 1L);
    setTime("2027-01-01T00:02:00Z");
    var created = new CompletableFuture<Long>();
    var release = new CompletableFuture<Void>();
    var attempted = new CompletableFuture<Void>();
    try (var workers = Executors.newFixedThreadPool(2)) {
      var enrollment =
          workers.submit(
              () -> {
                signIn("test.teacher.a");
                try {
                  return new TransactionTemplate(transactions)
                      .execute(
                          transaction -> {
                            Long id =
                                students.create(1L, "Test", "Parallel", LocalDate.of(2008, 1, 1));
                            jdbc.update(
                                "UPDATE student SET created_at = '2027-01-01 00:01:00' WHERE id = ?",
                                id);
                            created.complete(id);
                            release.join();
                            return id;
                          });
                } finally {
                  SecurityContextHolder.clearContext();
                }
              });
      try {
        Long studentId = created.get(10, TimeUnit.SECONDS);
        var reset =
            workers.submit(
                () -> {
                  signIn("test.admin");
                  try {
                    attempted.complete(null);
                    return classes.resetAccounts(1L, 1L);
                  } finally {
                    SecurityContextHolder.clearContext();
                  }
                });
        attempted.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> reset.get(300, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        release.complete(null);
        assertThat(enrollment.get(10, TimeUnit.SECONDS)).isEqualTo(studentId);
        assertThat(reset.get(10, TimeUnit.SECONDS)).isEqualTo(7);
        assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM attendance_audit"
                        + " WHERE event_type = 'ACCOUNT_RESET' AND student_id = ?",
                    Integer.class,
                    studentId))
            .isEqualTo(1);
      } finally {
        release.complete(null);
      }
    }
  }

  /** Startet einen Klassenreset nach der gemeinsamen Freigabe beider Testthreads */
  private int resetTogether(CyclicBarrier barrier) throws Exception {
    signIn("test.admin");
    try {
      barrier.await(10, TimeUnit.SECONDS);
      return classes.resetAccounts(1L, 1L);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  /** Legt einen festen Folgeplan für den Planwechseltest an */
  private Long nextPlan() {
    return plans.create("Folgeplan", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31));
  }

  /** Setzt den aktuellen Zeitpunkt der Testuhr */
  private void setTime(String instant) {
    when(clock.instant()).thenReturn(Instant.parse(instant));
  }
}
