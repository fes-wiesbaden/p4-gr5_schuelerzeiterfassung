package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.feswiesbaden.attendance.login.StaffPrincipal;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prüft die Rechte der Lehrkraft bei vergangenen Tagen über HTTP (Issue #45). Bereits anderswo
 * geprüft und hier nicht wiederholt: Klassenliste in Issue23IntegrationTest und
 * AttendanceAccountingIntegrationTest; Ablehnung des heutigen Tages fremder Klassen sowie Korrektur
 * am selben Tag mit anschließendem REST-Abruf in AttendanceAccountingIntegrationTest; Tagesabruf
 * ohne Anmeldung in Issue22IntegrationTest; Vorauswahl nur zugänglicher Klassen in
 * Issue43IntegrationTest; Polling-Verhalten aus #43 in LiveAttendanceView.test.ts und
 * EventSource-Freiheit des Frontends in noServerSentEvents.test.ts
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

  @MockitoBean Clock clock;

  /** Leert die Tagesabrechnung und setzt die Testuhr auf Dienstag, 15.09.2026, nach Montag */
  @BeforeEach
  void prepare() {
    jdbc.update("DELETE FROM attendance_audit");
    jdbc.update("DELETE FROM attendance");
    jdbc.update("UPDATE student SET unexcused_minutes_account = 0, excused_minutes_account = 0");
    when(clock.instant()).thenReturn(Instant.parse("2026-09-15T06:00:00Z"));
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(clock.withZone(any()))
        .thenAnswer(invocation -> Clock.fixed(clock.instant(), invocation.getArgument(0)));
  }

  /** Entfernt die Testdaten in der Reihenfolge ihrer Abhängigkeiten */
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

  /**
   * Der Abruf eines vergangenen Tages und die rückwirkende Statuskorrektur am Folgetag in einer
   * fremden Klasse werden abgelehnt und ändern weder Anwesenheit, Protokoll noch Minutenkonten
   */
  @Test
  void rejectsDirectAccessToForeignClassesWithoutSideEffects() throws Exception {
    var teacher = staffSession("test.teacher.b");
    var before = history();
    String pastDay = "/api/classes/1/attendance?date=2026-09-11";
    mvc.perform(get(pastDay).session(teacher))
        .andExpect(status().isNotFound())
        .andExpect(content().json("{\"code\":\"NOT_FOUND\"}"));
    mvc.perform(get(pastDay).session(staffSession("test.teacher.a"))).andExpect(status().isOk());

    write(post(CORRECTION), teacher, EXCUSE)
        .andExpect(status().isNotFound())
        .andExpect(content().json("{\"code\":\"NOT_FOUND\"}"));
    assertThat(history()).isEqualTo(before);
  }

  /**
   * Die rückwirkende Korrektur der Lehrkraft am Folgetag wird mit der Mitarbeiter-ID der Sitzung,
   * Zeitpunkten, Status, Löschfrist und Minutendeltas protokolliert und ist beim nächsten
   * REST-Abruf mit neu berechneten Minuten sichtbar. Weitere Löschfristen stellen sicher, dass die
   * zum Blockplan passende Frist gewählt wird
   */
  @Test
  void auditsRetroactiveTeacherCorrectionCompletelyAndShowsItOnTheNextRestRead() throws Exception {
    jdbc.update("UPDATE deletion_date SET delete_after = '2026-12-31' WHERE id = 1");
    jdbc.update(
        "INSERT INTO deletion_date (id, delete_after) VALUES (2, '2027-06-30'), (3, '2027-12-31')");
    var teacher = staffSession("test.teacher.a");
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].studentId").value(1))
        .andExpect(jsonPath("$[0].status").value("ABWESEND"))
        .andExpect(jsonPath("$[0].unexcusedMinutes").value(180));

    write(post(CORRECTION), teacher, EXCUSE)
        .andExpect(status().isOk())
        .andExpect(content().json("{\"changed\":true}"));
    // queryForMap verlangt genau einen Protokolleintrag
    assertThat(jdbc.queryForMap("SELECT * FROM attendance_audit"))
        .containsEntry("student_id", 1L)
        .containsEntry(
            "attendance_id",
            jdbc.queryForObject("SELECT id FROM attendance WHERE student_id = 1", Long.class))
        .containsEntry("event_type", "STATUS_CHANGE")
        .containsEntry("changed_by_staff_id", 1L)
        .containsEntry("terminal_id", null)
        .containsEntry("deletion_date_id", 2L)
        .containsEntry("old_status", "ABWESEND")
        .containsEntry("new_status", "ENTSCHULDIGT")
        .containsEntry("occurred_at", LocalDateTime.of(2026, 9, 14, 5, 45))
        .containsEntry("created_at", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
        .containsEntry("unexcused_minutes_delta", 15)
        .containsEntry("excused_minutes_delta", 165);

    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].studentId").value(1))
        .andExpect(jsonPath("$[0].status").value("ENTSCHULDIGT"))
        .andExpect(jsonPath("$[0].unexcusedMinutes").value(15));
  }

  /**
   * Vor dem Kontenreset ist die rückwirkende Korrektur möglich, danach ist der Tag gesperrt. Die
   * weitere Korrektur der Lehrkraft wird abgelehnt und ändert weder Anwesenheit, Protokoll noch
   * Minutenkonten
   */
  @Test
  void rejectsCorrectionOfADayLockedByTheAccountReset() throws Exception {
    var teacher = staffSession("test.teacher.a");
    write(post(CORRECTION), teacher, EXCUSE).andExpect(status().isOk());
    write(
            post("/api/classes/1/account-resets"),
            staffSession("test.admin"),
            "{\"expectedBlockPlanId\":1}")
        .andExpect(status().isOk());
    var before = history();

    write(
            post(CORRECTION),
            teacher,
            "{\"status\":\"ANWESEND\",\"occurredAt\":\"2026-09-14T05:45:00Z\"}")
        .andExpect(status().isConflict())
        .andExpect(content().json("{\"code\":\"ATTENDANCE_DAY_LOCKED_OR_UNPLANNED\"}"));
    assertThat(history()).isEqualTo(before);
  }

  /** Die Statuskorrektur ist nur mit Anmeldung erreichbar */
  @Test
  void requiresAuthenticationForCorrections() throws Exception {
    write(post(CORRECTION), new MockHttpSession(), EXCUSE).andExpect(status().isUnauthorized());
  }

  /**
   * Produktionscode und Abhängigkeiten enthalten keine Streaming- oder SSE-Infrastruktur, auch
   * nicht als ResponseEntity-Generic, manuell gesetzten Content-Type oder WebFlux. Das
   * Modulverzeichnis wird aus target/test-classes abgeleitet, damit der Test unabhängig vom
   * Arbeitsverzeichnis läuft
   */
  @Test
  void usesNoServerSentEvents() throws Exception {
    var module =
        Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
            .resolve("../..")
            .normalize();
    try (var files = Files.walk(module.resolve("src/main"))) {
      var sources =
          Stream.concat(files.filter(Files::isRegularFile), Stream.of(module.resolve("pom.xml")))
              .toList();
      assertThat(sources).hasSizeGreaterThan(1);
      for (var file : sources) {
        assertThat(Files.readString(file))
            .as(file.toString())
            .doesNotContain(
                "SseEmitter",
                "ServerSentEvent",
                "ResponseBodyEmitter",
                "StreamingResponseBody",
                "Flux",
                "webflux",
                "TEXT_EVENT_STREAM",
                "text/event-stream");
      }
    }
  }

  /**
   * Liefert Anwesenheiten, Protokoll und Schüler mit Minutenkonten für Vorher-nachher-Vergleiche
   */
  private List<List<Map<String, Object>>> history() {
    return List.of(
        jdbc.queryForList("SELECT * FROM attendance ORDER BY id"),
        jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id"),
        jdbc.queryForList("SELECT * FROM student ORDER BY id"));
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
}
