package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.feswiesbaden.attendance.login.StaffPrincipal;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prüft die Rechte der Lehrkraft in der aktuellen Anwesenheitsansicht und beim Abruf vergangener
 * Tage über HTTP (Issue #45). Das Polling-Verhalten aus #43 prüft LiveAttendanceView.test.ts
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Sql("/fixtures/attendance.sql")
class Issue45IntegrationTest {
  private static final String CLASS_DAY = "/api/classes/1/attendance?date=2026-09-14";

  private static final String CORRECTION =
      "/api/students/1/attendance/2026-09-14/status-corrections";

  private static final String EXCUSE =
      """
      {"status":"ENTSCHULDIGT","occurredAt":"2026-09-14T05:45:00Z"}
      """;

  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @Autowired JdbcTemplate jdbc;

  @Autowired MockMvc mvc;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping handlers;

  @MockitoBean Clock clock;

  /**
   * Leert die Tagesabrechnung und setzt die Testuhr nach dem Unterrichtsende am Montag, 14.09.2026
   */
  @BeforeEach
  void prepare() {
    jdbc.update("DELETE FROM attendance_audit");
    jdbc.update("DELETE FROM attendance");
    jdbc.update("UPDATE student SET unexcused_minutes_account = 0, excused_minutes_account = 0");
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
   * Lehrkraft B sieht nur ihre Klasse 2. Am 21.09. läuft nur Klasse 3 von Lehrkraft A, daher wählt
   * die Ansicht für B keine fremde Klasse vor
   */
  @Test
  void teacherSeesOnlyAssignedClasses() throws Exception {
    var teacher = staffSession("test.teacher.b");
    mvc.perform(get("/api/classes").session(teacher)).andExpect(jsonPath("$[*].id", contains(2)));
    mvc.perform(get("/api/classes/2/attendance?date=2026-09-14").session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].studentId", contains(7)));

    setTime("2026-09-21T06:00:00Z");
    mvc.perform(get("/api/attendance/current-class").session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.classId").value((Object) null));
  }

  /**
   * Direkte Abrufe des heutigen und eines vergangenen Tages sowie Statuskorrekturen in fremden
   * Klassen werden wie unbekannte Klassen abgelehnt und ändern weder Anwesenheit, Protokoll noch
   * Minutenkonten
   */
  @Test
  void rejectsDirectAccessToForeignClassesWithoutSideEffects() throws Exception {
    var teacher = staffSession("test.teacher.b");
    for (String url :
        List.of(
            CLASS_DAY,
            "/api/classes/1/attendance?date=2026-09-11",
            "/api/classes/999/attendance?date=2026-09-11")) {
      mvc.perform(get(url).session(teacher))
          .andExpect(status().isNotFound())
          .andExpect(content().json("{\"code\":\"NOT_FOUND\"}"));
    }
    mvc.perform(
            get("/api/classes/1/attendance?date=2026-09-11")
                .session(staffSession("test.teacher.a")))
        .andExpect(status().isOk());

    write(CORRECTION, teacher, EXCUSE)
        .andExpect(status().isNotFound())
        .andExpect(content().json("{\"code\":\"NOT_FOUND\"}"));
    assertNoAttendanceBooked();
  }

  /**
   * Die Korrektur der Lehrkraft wird mit Mitarbeiter, Zeitpunkten, Status, Löschfrist und
   * Minutendeltas protokolliert und ist beim nächsten REST-Abruf mit neu berechneten Minuten
   * sichtbar. Eine abgelehnte Korrektur schreibt kein Protokoll
   */
  @Test
  void auditsTeacherCorrectionCompletelyAndShowsItOnTheNextRestRead() throws Exception {
    var teacher = staffSession("test.teacher.a");
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$[0].status").value("ABWESEND"))
        .andExpect(jsonPath("$[0].unexcusedMinutes").value(180));

    write(
            CORRECTION,
            teacher,
            "{\"status\":\"ENTSCHULDIGT\",\"occurredAt\":\"2026-09-14T09:00:00Z\"}")
        .andExpect(status().isBadRequest())
        .andExpect(content().json("{\"code\":\"INVALID_STATUS_TIME\"}"));
    assertNoAttendanceBooked();

    write(CORRECTION, teacher, EXCUSE)
        .andExpect(status().isOk())
        .andExpect(content().json("{\"changed\":true}"));
    var attendanceId =
        jdbc.queryForObject("SELECT id FROM attendance WHERE student_id = 1", Long.class);
    assertThat(jdbc.queryForList("SELECT * FROM attendance_audit"))
        .singleElement()
        .satisfies(
            audit ->
                assertThat(audit)
                    .containsEntry("student_id", 1L)
                    .containsEntry("attendance_id", attendanceId)
                    .containsEntry("event_type", "STATUS_CHANGE")
                    .containsEntry("changed_by_staff_id", 1L)
                    .containsEntry("terminal_id", null)
                    .containsEntry("deletion_date_id", 1L)
                    .containsEntry("old_status", "ABWESEND")
                    .containsEntry("new_status", "ENTSCHULDIGT")
                    .containsEntry("occurred_at", LocalDateTime.of(2026, 9, 14, 5, 45))
                    .containsEntry(
                        "created_at", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .containsEntry("unexcused_minutes_delta", 15)
                    .containsEntry("excused_minutes_delta", 165));

    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(jsonPath("$[0].status").value("ENTSCHULDIGT"))
        .andExpect(jsonPath("$[0].unexcusedMinutes").value(15));
  }

  /** Tagesstatus, Vorauswahl und Korrektur sind nur mit Anmeldung erreichbar */
  @Test
  void requiresAuthenticationForLiveAttendance() throws Exception {
    mvc.perform(get(CLASS_DAY)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/attendance/current-class")).andExpect(status().isUnauthorized());
    String token = "test-csrf-token";
    mvc.perform(
            post(CORRECTION)
                .cookie(new Cookie("XSRF-TOKEN", token))
                .header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(EXCUSE))
        .andExpect(status().isUnauthorized());
    assertNoAttendanceBooked();
  }

  /** Kein Endpunkt streamt Server-Sent Events; WebFlux liegt nicht auf dem Klassenpfad */
  @Test
  void offersNoServerSentEvents() {
    assertThat(ClassUtils.isPresent("org.springframework.web.reactive.DispatcherHandler", null))
        .isFalse();
    assertThat(handlers.getHandlerMethods())
        .isNotEmpty()
        .allSatisfy(
            (mapping, handler) -> {
              assertThat(
                      ResponseBodyEmitter.class.isAssignableFrom(
                          handler.getReturnType().getParameterType()))
                  .as(handler.toString())
                  .isFalse();
              assertThat(mapping.getProducesCondition().getProducibleMediaTypes())
                  .doesNotContain(MediaType.TEXT_EVENT_STREAM);
            });
  }

  /** Stellt sicher, dass weder Anwesenheit noch Protokoll noch Minutenkonten geändert wurden */
  private void assertNoAttendanceBooked() {
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_audit", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(unexcused_minutes_account + excused_minutes_account) FROM student",
                Integer.class))
        .isZero();
  }

  /** Setzt die Testuhr auf den angegebenen UTC-Zeitpunkt */
  private void setTime(String now) {
    when(clock.instant()).thenReturn(Instant.parse(now));
  }

  /** Erstellt eine Sitzung des angegebenen Testmitarbeiters */
  private MockHttpSession staffSession(String username) {
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new StaffPrincipal(
                jdbc.queryForObject("SELECT id FROM staff WHERE username=?", Long.class, username),
                username,
                "unused",
                List.of()),
            null,
            List.of()));
    var session = new MockHttpSession();
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    return session;
  }

  /** Führt einen Schreibzugriff mit Sitzung und passendem CSRF-Token aus */
  private ResultActions write(String url, MockHttpSession session, String body) throws Exception {
    String token = "test-csrf-token";
    return mvc.perform(
        post(url)
            .session(session)
            .cookie(new Cookie("XSRF-TOKEN", token))
            .header("X-XSRF-TOKEN", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }
}
