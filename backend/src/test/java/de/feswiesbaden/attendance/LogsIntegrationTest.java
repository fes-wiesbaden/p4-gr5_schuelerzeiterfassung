package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.feswiesbaden.attendance.login.StaffPrincipal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Prüft Rechte, Filter, Sortierung und Seiten der Logs-Ansicht */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Sql("/fixtures/attendance.sql")
class LogsIntegrationTest {
  private static final String RANGE = "?from=2026-09-14T00:00:00Z&to=2026-09-15T00:00:00Z";

  private static final String RAW_SCANS = "/api/logs/raw-scans" + RANGE;

  private static final String AUDITS = "/api/logs/audits" + RANGE;

  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @Autowired JdbcTemplate jdbc;

  @Autowired MockMvc mvc;

  /**
   * Ersetzt Rohscans und Protokoll der Fixture: drei Rohscans am 14.09., davon einer mit
   * unbekannter Karte, und einer am Folgetag
   */
  @BeforeEach
  void scans() {
    jdbc.update("DELETE FROM attendance_audit");
    jdbc.update("DELETE FROM raw_scan");
    jdbc.update(
        """
        INSERT INTO raw_scan (scan_id, rfid_uid, scanned_at) VALUES
          ('00000000-0000-0000-0000-000000000001', 'TEST-UID-002', '2026-09-14 06:10:00'),
          ('00000000-0000-0000-0000-000000000002', 'UNBEKANNT-99', '2026-09-14 06:20:00'),
          ('00000000-0000-0000-0000-000000000003', 'TEST-UID-003', '2026-09-14 06:30:00'),
          ('00000000-0000-0000-0000-000000000004', 'TEST-UID-002', '2026-09-15 06:10:00')
        """);
    jdbc.update(
        """
        INSERT INTO attendance_audit (student_id, deletion_date_id, event_type,
          changed_by_staff_id, terminal_id, new_status, occurred_at) VALUES
          (2, 1, 'SCAN', NULL, 9001, 'ANWESEND', '2026-09-14 06:10:00'),
          (3, 1, 'STATUS_CHANGE', 1, NULL, 'ENTSCHULDIGT', '2026-09-14 07:00:00')
        """);
  }

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

  /** Lehrkräfte sehen alle Rohscans des Zeitraums inklusive unbekannter Karten */
  @Test
  void teacherSeesAllRawScansNewestFirst() throws Exception {
    mvc.perform(get(RAW_SCANS).session(staffSession("test.teacher.b")))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.items[0].rfidUid").value("TEST-UID-003"))
        .andExpect(jsonPath("$.items[0].scannedAt").value("2026-09-14T06:30:00Z"))
        .andExpect(jsonPath("$.items[1].student").doesNotExist())
        .andExpect(jsonPath("$.items[2].student").value("Verspaetet, Test"))
        .andExpect(jsonPath("$.items[2].classCode").value("26TEST-A"));
  }

  /** Suche nach Schülername, aufsteigende Sortierung und Seiten mit Gesamtzahl */
  @Test
  void filtersSortsAndPagesRawScans() throws Exception {
    var admin = staffSession("test.admin");
    mvc.perform(get(RAW_SCANS).param("search", "Test Verspaetet").session(admin))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].rfidUid").value("TEST-UID-002"));
    mvc.perform(get(RAW_SCANS + "&sort=rfidUid&direction=asc&size=2&page=1").session(admin))
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].rfidUid").value("UNBEKANNT-99"));
  }

  /** Weder Antwort noch gebundener Suchbegriff geben die UID in technischen Logs aus */
  @Test
  @ExtendWith({OutputCaptureExtension.class, RfidLoggingExtension.class})
  void keepsUidOutOfTechnicalLogs(CapturedOutput output) throws Exception {
    mvc.perform(get(RAW_SCANS + "&search=TEST-UID-002").session(staffSession("test.admin")))
        .andExpect(jsonPath("$.items[0].rfidUid").value("TEST-UID-002"));
    assertThat(output.getAll()).doesNotContain("TEST-UID-002", "Verspaetet");
  }

  /** Das Änderungsprotokoll bleibt Lehrkräften verschlossen */
  @Test
  void teacherCannotReadAudits() throws Exception {
    mvc.perform(get(AUDITS).session(staffSession("test.teacher.a")))
        .andExpect(status().isForbidden())
        .andExpect(content().json("{\"code\":\"ADMINISTRATOR_REQUIRED\"}"));
  }

  /** Administratoren sehen, welche Lehrkraft oder welches Terminal einen Eintrag ausgelöst hat */
  @Test
  void adminSeesAuditActors() throws Exception {
    var admin = staffSession("test.admin");
    mvc.perform(get(AUDITS + "&sort=occurredAt&direction=asc").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.items[0].actor").value("Terminal 9001 (Raum TEST-R01)"))
        .andExpect(jsonPath("$.items[1].actor").value("Lehrkraft-A, Test (test.teacher.a)"));
    for (var search : List.of("test.teacher.a", "Test Lehrkraft-A", "Raum TEST-R01")) {
      mvc.perform(get(AUDITS).param("search", search).session(admin))
          .andExpect(jsonPath("$.total").value(1));
    }
    mvc.perform(get(AUDITS + "&eventType=SCAN").session(admin))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].eventType").value("SCAN"));
  }

  /**
   * Ereignisse sortieren nach Anzeigename: Kontenreset vor Scan, anders als die Enum-Reihenfolge
   */
  @Test
  void sortsEventsByDisplayName() throws Exception {
    jdbc.update(
        "INSERT INTO attendance_audit (student_id, deletion_date_id, event_type, changed_by_staff_id,"
            + " occurred_at) VALUES (2, 1, 'ACCOUNT_RESET', 3, '2026-09-14 08:00:00')");
    mvc.perform(get(AUDITS + "&sort=eventType&direction=asc").session(staffSession("test.admin")))
        .andExpect(jsonPath("$.items[0].eventType").value("ACCOUNT_RESET"))
        .andExpect(jsonPath("$.items[1].eventType").value("SCAN"));
  }

  /** Unbekannte Sortierschlüssel, Ereignistypen und Seitengrößen werden abgelehnt */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/logs/raw-scans" + RANGE + "&sort=scan_id;DROP",
        "/api/logs/raw-scans" + RANGE + "&direction=sideways",
        "/api/logs/raw-scans" + RANGE + "&size=1000",
        "/api/logs/audits" + RANGE + "&eventType=UNKNOWN"
      })
  void rejectsInvalidQueries(String url) throws Exception {
    mvc.perform(get(url).session(staffSession("test.admin")))
        .andExpect(status().isBadRequest())
        .andExpect(content().json("{\"code\":\"INVALID_REQUEST\"}"));
  }

  /** Ohne Anmeldung liefern beide Endpunkte nichts */
  @ParameterizedTest
  @ValueSource(strings = {RAW_SCANS, AUDITS})
  void requiresAuthentication(String url) throws Exception {
    mvc.perform(get(url)).andExpect(status().isUnauthorized());
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
