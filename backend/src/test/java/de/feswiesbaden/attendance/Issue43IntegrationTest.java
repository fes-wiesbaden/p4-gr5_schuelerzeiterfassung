package de.feswiesbaden.attendance;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.feswiesbaden.attendance.login.StaffPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prüft die Vorauswahl der Klasse für die aktuelle Anwesenheitsansicht nach Stundenplan (Issue
 * #43). Tagesstatus, Korrekturen und deren Protokoll prüft AttendanceAccountingIntegrationTest
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Sql("/fixtures/attendance.sql")
class Issue43IntegrationTest {
  private static final String CURRENT_CLASS = "/api/attendance/current-class";

  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @Autowired JdbcTemplate jdbc;

  @Autowired MockMvc mvc;

  @MockitoBean Clock clock;

  /** Entfernt die Testdaten in der Reihenfolge ihrer Abhängigkeiten */
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
   * Laufender Slot, Pause vor dem nächsten Slot, Unterrichtsende, Block der anderen Klasse und der
   * Berliner Tag kurz nach Mitternacht. Beim Administrator laufen Klasse 1 und 2 gleichzeitig.
   * Slots: montags 07:30-09:00 und 09:15-10:45 Berliner Zeit
   */
  @ParameterizedTest
  @CsvSource({
    "test.teacher.a, 2026-09-14T06:00:00Z, 1",
    "test.teacher.a, 2026-09-14T07:05:00Z, 1",
    "test.teacher.a, 2026-09-14T04:00:00Z, 1",
    "test.teacher.a, 2026-09-14T09:00:00Z, ",
    "test.teacher.a, 2026-09-21T06:00:00Z, 3",
    "test.teacher.b, 2026-09-14T06:00:00Z, 2",
    "test.teacher.a, 2026-09-13T22:30:00Z, 1",
    "test.admin, 2026-09-14T06:00:00Z, 1"
  })
  void preselectsTheAccessibleClassByTimetable(String username, String now, Long classId)
      throws Exception {
    when(clock.instant()).thenReturn(Instant.parse(now));
    mvc.perform(get(CURRENT_CLASS).session(staffSession(username)))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"classId\":" + classId + ",\"serverTime\":\"" + now + "\"}"));
  }

  /** Ohne Anmeldung liefert der Endpunkt keine Klasse */
  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get(CURRENT_CLASS)).andExpect(status().isUnauthorized());
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
}
