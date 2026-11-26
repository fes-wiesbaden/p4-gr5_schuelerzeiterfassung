package de.feswiesbaden.attendance;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

  @Test
  void readsOnlyAccessibleWeeksAndSelectsTheSlotVersionForThatDate() throws Exception {
    jdbc.update(
        "INSERT INTO timetable_period (timetable_id, period_number, starts_at, ends_at)"
            + " VALUES (1, 1, '07:30:00', '08:15:00'), (1, 2, '08:15:00', '09:00:00')");
    jdbc.update("UPDATE timetable_slot SET valid_until = '2026-09-16' WHERE id = 1");
    jdbc.update(
        """
        INSERT INTO timetable_slot
          (timetable_id, room_id, weekday, start_time, end_time, valid_from)
        VALUES (1, 2, 'MONTAG', '08:15:00', '09:00:00', '2026-09-16')
        """);
    var teacher = staffSession("test.teacher.a");
    mvc.perform(get("/api/planning/classes/1/week?date=2026-09-16"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/planning/classes/2/week?date=2026-09-16").session(teacher))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/planning/classes/1/week?date=2026-09-16").session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].date").value("2026-09-14"))
        .andExpect(jsonPath("$[0].periods[1].number").value(2))
        .andExpect(jsonPath("$[0].periods[1].startsAt").value("08:15:00"))
        .andExpect(jsonPath("$[0].lessons[0].startsAt").value("07:30:00"))
        .andExpect(jsonPath("$[0].lessons[0].roomNumber").value("TEST-R01"))
        .andExpect(jsonPath("$[1].lessons").isEmpty());
    mvc.perform(get("/api/planning/classes/3/week?date=2026-09-21").session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].lessons[0].startsAt").value("08:15:00"))
        .andExpect(jsonPath("$[0].lessons[0].roomNumber").value("TEST-R02"));
  }

  @Test
  void theSameSchoolYearTemplateAppliesToEveryAssignedBlockOnly() throws Exception {
    jdbc.update("UPDATE block_plan SET ends_on = '2027-07-31' WHERE id = 1");
    jdbc.update(
        "INSERT INTO block_assignment (id, block_plan_id, starts_on, ends_on)"
            + " VALUES (3, 1, '2027-06-14', '2027-06-25')");
    jdbc.update("INSERT INTO class_block_assignment VALUES (1, 3)");
    jdbc.update("UPDATE timetable_slot SET valid_until = '2027-08-01' WHERE id = 1");
    jdbc.update(
        "INSERT INTO timetable_slot (timetable_id, room_id, weekday, start_time, end_time, valid_from)"
            + " VALUES (1, 1, 'MONTAG', '07:00:00', '09:00:00', '2027-08-01')");
    var teacher = staffSession("test.teacher.a");
    mvc.perform(get("/api/planning/classes/1/schedule")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/planning/classes/2/schedule").session(teacher))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/planning/classes/1/schedule").session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schoolYear").value(2026))
        .andExpect(jsonPath("$.regularWeek.length()").value(5))
        .andExpect(jsonPath("$.regularWeek[0].lessons[0].startsAt").value("07:30:00"))
        .andExpect(jsonPath("$.blocks.length()").value(2))
        .andExpect(jsonPath("$.blocks[0].startsOn").value("2026-09-14"))
        .andExpect(jsonPath("$.blocks[1].startsOn").value("2027-06-14"));
    for (var day : List.of("2026-09-14", "2027-06-14")) {
      mvc.perform(get("/api/planning/classes/1/week").param("date", day).session(teacher))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].lessons[0].startsAt").value("07:30:00"));
    }
    mvc.perform(get("/api/planning/classes/1/week?date=2026-09-21").session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].lessons").isEmpty());
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
