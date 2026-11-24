package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.feswiesbaden.attendance.dtos.RawScanDTO;
import de.feswiesbaden.attendance.enums.AttendanceStatus;
import de.feswiesbaden.attendance.enums.ScanResult;
import de.feswiesbaden.attendance.repository.RetentionRepository;
import de.feswiesbaden.attendance.service.AttendanceAccountingService;
import de.feswiesbaden.attendance.service.RetentionService;
import de.feswiesbaden.attendance.service.SchoolClassService;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Prüft Tagesstatus, Minutenabrechnung und unveränderliche Audits mit MySQL */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Sql("/fixtures/attendance.sql")
class AttendanceAccountingIntegrationTest {
  private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);

  private static final String CLASS_DAY = "/api/classes/1/attendance?date=2026-09-14";

  private static final String CORRECTION =
      "/api/students/1/attendance/2026-09-14/status-corrections";

  private static final String RESET = "/api/classes/1/account-resets";

  private static final String EXCUSE =
      """
      {"status":"ENTSCHULDIGT","occurredAt":"2026-09-14T05:45:00Z"}
      """;

  private static final String STUDENT =
      """
      {"firstName":"Neu","lastName":"Schüler","birthDate":"2008-02-03"}
      """;

  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @Autowired JdbcTemplate jdbc;

  @Autowired MockMvc mvc;

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

  /**
   * Prüft laufende Tagesfehlminuten, eine verspätete Ankunft und eine nachträgliche Entschuldigung
   */
  @Test
  void readsUpdatedDailyMinutesWithoutBookingThem() throws Exception {
    var teacher = staffSession("test.teacher.a");
    setTime("2026-09-14T05:50:00Z");
    assertThat(scan(2, "2026-09-14T05:50:00Z")).isTrue();
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("ABWESEND"))
        .andExpect(jsonPath("$[0].unexcusedMinutes").value(20))
        .andExpect(jsonPath("$[1].status").value("ANWESEND"))
        .andExpect(jsonPath("$[1].unexcusedMinutes").value(20))
        .andExpect(jsonPath("$[0].birthDate").doesNotExist())
        .andExpect(jsonPath("$[0].rfidUid").doesNotExist());

    setTime("2026-09-14T06:00:00Z");
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(jsonPath("$[0].unexcusedMinutes").value(30))
        .andExpect(jsonPath("$[1].unexcusedMinutes").value(20));
    assertAccount(1, 0, 0);
    write(post(CORRECTION), teacher, EXCUSE)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(jsonPath("$[0].status").value("ENTSCHULDIGT"))
        .andExpect(jsonPath("$[0].unexcusedMinutes").value(15));
    mvc.perform(get("/api/students/1").session(teacher))
        .andExpect(jsonPath("$.unexcusedMinutes").value(15))
        .andExpect(jsonPath("$.excusedMinutes").value(15));
    mvc.perform(get("/api/classes/1/attendance?date=2026-09-12").session(teacher))
        .andExpect(content().json("[]"));
    mvc.perform(get("/api/classes/1/attendance?date=2026-09-15").session(teacher))
        .andExpect(status().isBadRequest());
  }

  /**
   * Prüft Schüler, Anwesenheit, Ereignisart, Terminal-ID, fehlende Staff-ID, Löschfrist sowie alten
   * und neuen Status des Audits eines echten Scans. Weitere Löschfristen stellen sicher, dass die
   * zum Blockplan passende Frist gewählt wird
   */
  @Test
  void scanAuditHasTerminalIdNoStaffIdAndThePlanDeletionDate() {
    jdbc.update("UPDATE deletion_date SET delete_after = '2026-12-31' WHERE id = 1");
    jdbc.update(
        "INSERT INTO deletion_date (id, delete_after) VALUES (2, '2027-06-30'), (3, '2027-12-31')");
    assertThat(scan(2, "2026-09-14T05:50:00Z")).isTrue();
    var attendanceId =
        jdbc.queryForObject("SELECT id FROM attendance WHERE student_id = 2", Long.class);
    assertThat(jdbc.queryForMap("SELECT * FROM attendance_audit WHERE student_id = 2"))
        .containsEntry("student_id", 2L)
        .containsEntry("attendance_id", attendanceId)
        .containsEntry("event_type", "SCAN")
        .containsEntry("terminal_id", 9001)
        .containsEntry("changed_by_staff_id", null)
        .containsEntry("deletion_date_id", 2L)
        .containsEntry("old_status", "ABWESEND")
        .containsEntry("new_status", "ANWESEND");
  }

  /** Prüft Klassengrenzen und die Verwaltungsrechte der Klassenleitung über HTTP */
  @Test
  void enforcesClassAccessAndClassManagementThroughHttp() throws Exception {
    var teacher = staffSession("test.teacher.b");
    mvc.perform(get("/api/classes")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/classes").session(teacher)).andExpect(jsonPath("$[*].id", contains(2)));
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(status().isNotFound())
        .andExpect(content().json("{\"code\":\"NOT_FOUND\"}"));
    mvc.perform(get("/api/classes/999/attendance?date=2026-09-14").session(teacher))
        .andExpect(status().isNotFound())
        .andExpect(content().json("{\"code\":\"NOT_FOUND\"}"));
    write(post(CORRECTION), teacher, EXCUSE).andExpect(status().isNotFound());
    write(put("/api/students/1"), teacher, STUDENT).andExpect(status().isNotFound());

    jdbc.update("INSERT INTO teacher_class (staff_id, school_class_id) VALUES (2, 1)");
    mvc.perform(get("/api/classes/1/students").session(teacher)).andExpect(status().isOk());
    mvc.perform(get(CLASS_DAY).session(teacher)).andExpect(status().isOk());
    write(post(CORRECTION), teacher, EXCUSE).andExpect(status().isOk());
    write(post("/api/classes/1/students"), teacher, STUDENT).andExpect(status().isForbidden());
    write(delete("/api/students/1"), teacher, "").andExpect(status().isForbidden());

    mvc.perform(post("/api/classes").session(teacher)).andExpect(status().isForbidden());
    write(
            post("/api/classes"),
            teacher,
            "{\"classCode\":\"26TEST-NEU\",\"blockPlanId\":1,\"timetableId\":2}")
        .andExpect(status().isCreated());
    assertThat(
            jdbc.queryForObject(
                "SELECT class_teacher_id FROM school_class WHERE class_code = '26TEST-NEU'",
                Long.class))
        .isEqualTo(2L);
  }

  /** Prüft Schülerverwaltung und HTTP 409 bei bestehender Anwesenheitshistorie */
  @Test
  void managesStudentsButPreservesTheirHistory() throws Exception {
    var teacher = staffSession("test.teacher.a");
    write(post("/api/classes/1/students"), teacher, STUDENT).andExpect(status().isCreated());
    long id = jdbc.queryForObject("SELECT id FROM student WHERE first_name = 'Neu'", Long.class);
    write(put("/api/students/" + id), teacher, STUDENT.replace("Neu", "Geändert"))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/students/" + id).session(teacher))
        .andExpect(jsonPath("$.firstName").value("Geändert"));
    write(delete("/api/students/" + id), teacher, "").andExpect(status().isNoContent());

    assertThat(scan(1, "2026-09-14T05:50:00Z")).isTrue();
    var admin = staffSession("test.admin");
    mvc.perform(get("/api/classes").session(admin))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[*].id", contains(1, 2, 3)));
    write(delete("/api/students/1"), admin, "")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STUDENT_HAS_HISTORY"));
    mvc.perform(get("/api/students/1").session(admin)).andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE student_id = 1", Integer.class))
        .isEqualTo(1);
    assertAccount(1, 20, 0);
  }

  /** Prüft Administratorrechte, Planbindung, wiederholte Resets und erhaltene Tageswerte */
  @Test
  void resetsAccountsThroughHttpAndKeepsDailyMinutes() throws Exception {
    var admin = staffSession("test.admin");
    var teacher = staffSession("test.teacher.a");
    String plan = "{\"expectedBlockPlanId\":1}";
    write(post(RESET), teacher, plan).andExpect(status().isForbidden());
    write(post(RESET), admin, "{}").andExpect(status().isBadRequest());
    write(post(RESET), admin, "{\"expectedBlockPlanId\":999}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CLASS_PLAN_CHANGED"));
    for (int reset = 0; reset < 2; reset++) {
      write(post(RESET), admin, plan)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.resetCount").value(6));
    }
    jdbc.update("DELETE FROM student WHERE id = 8");
    setTime("2026-09-21T06:00:00Z");
    write(post("/api/classes/3/account-resets"), admin, plan)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resetCount").value(0));
    assertThat(
            jdbc.queryForObject(
                """
        SELECT COUNT(*) FROM attendance_audit WHERE event_type = 'ACCOUNT_RESET'
        AND changed_by_staff_id = 3 AND deletion_date_id = 1 AND attendance_id IS NULL
        """,
                Integer.class))
        .isEqualTo(12);
    assertThat(
            jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM attendance_audit
                WHERE event_type = 'ACCOUNT_RESET' AND unexcused_minutes_delta = 0
                """,
                Integer.class))
        .isEqualTo(6);
    assertAccount(1, 0, 0);
    write(post(CORRECTION), teacher, EXCUSE).andExpect(status().isConflict());
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(jsonPath("$[0].unexcusedMinutes").value(180));

    jdbc.update(
        "INSERT INTO block_plan (id, name, starts_on, ends_on) VALUES (999, 'Folgeplan', '2027-01-01', '2027-06-30')");
    jdbc.update("DELETE FROM class_block_assignment WHERE school_class_id = 1");
    jdbc.update("UPDATE school_class SET block_plan_id = 999 WHERE id = 1");
    setTime("2027-01-01T00:00:00Z");
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(jsonPath("$[0].unexcusedMinutes").value(180));
    setTime("2027-06-29T22:00:00Z");
    mvc.perform(get(CLASS_DAY).session(teacher)).andExpect(content().json("[]"));
  }

  /** Legt eine Testsitzung mit dem gespeicherten Sicherheitskontext des Mitarbeiters an */
  private MockHttpSession staffSession(String username) {
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(username, null, List.of()));
    var session = new MockHttpSession();
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    return session;
  }

  /** Führt einen Schreibzugriff mit Sitzung und passendem CSRF-Token aus */
  private ResultActions write(
      MockHttpServletRequestBuilder request, MockHttpSession session, String body)
      throws Exception {
    String token = "test-csrf-token";
    return mvc.perform(
        request
            .session(session)
            .cookie(new Cookie("XSRF-TOKEN", token))
            .header("X-XSRF-TOKEN", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
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

  /** Prüft die Ablehnung ungültiger Scanzeiten und Blockzuordnungen ohne neue Anwesenheit */
  @Test
  void rejectsInvalidScanTimesAndBlocksWithoutCreatingAnAttendance() {
    assertThat(scan(1, "2026-09-14T05:29:59Z")).isFalse();
    assertThat(scan(1, "2026-09-14T07:00:00Z")).isFalse();
    assertThat(scan(1, "2026-09-14T07:15:00.001Z")).isFalse();
    assertThat(scan(1, "2026-09-15T05:30:00Z")).isFalse();
    assertThat(scan(8, "2026-09-14T06:00:00Z")).isFalse();
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

  /** Prüft das Zurückrollen von Anwesenheit und Konto bei einer fehlerhaften Statuskorrektur */
  @Test
  void failedStatusAuditRollsBackTheNewAttendanceAndAccount() {
    jdbc.execute(
        "ALTER TABLE attendance_audit ADD CONSTRAINT test_reject_booking CHECK (student_id <> 1)");
    try {
      assertThatThrownBy(
              () ->
                  accounting.correctStatus(
                      1L,
                      MONDAY,
                      AttendanceStatus.ENTSCHULDIGT,
                      Instant.parse("2026-09-14T06:00:00Z")))
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
    String rfidUid =
        jdbc.queryForObject("SELECT rfid_uid FROM student WHERE id = ?", String.class, student);
    return accounting
        .recordScan(
            "CN=terminal-9001",
            new RawScanDTO(UUID.randomUUID(), rfidUid, 9001, Instant.parse(at)),
            clock.instant())
        .equals(ScanResult.SUCCESS);
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
