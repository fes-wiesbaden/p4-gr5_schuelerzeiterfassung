package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.feswiesbaden.attendance.login.StaffPrincipal;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Prüft die Planungs-API mit Rechten, Fehlercodes und gespeicherten Planungsdaten (Issue #40) */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Sql("/fixtures/attendance.sql")
class PlanningApiIntegrationTest {
  @Container @ServiceConnection
  static MySQLContainer<?> mysql =
      new MySQLContainer<>("mysql:8.0").withUrlParam("connectionTimeZone", "UTC");

  @Autowired JdbcTemplate jdbc;

  @Autowired MockMvc mvc;

  @MockitoBean Clock clock;

  private MockHttpSession admin;

  private MockHttpSession teacher;

  /** Legt Sitzungen für Administrator und Lehrkraft an und setzt die Testuhr */
  @BeforeEach
  void prepare() {
    admin = staffSession("test.admin");
    teacher = staffSession("test.teacher.a");
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

  /** Nur Administratoren erhalten die Übersicht mit Slots, Blöcken, Zuordnungen und Löschdatum */
  @Test
  void returnsTheOverviewOnlyToAdministrators() throws Exception {
    mvc.perform(get("/api/planning")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/planning").session(teacher))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ADMINISTRATOR_REQUIRED"));
    mvc.perform(get("/api/planning").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timetables[0].name").value("Test-Stundenplan-A"))
        .andExpect(jsonPath("$.timetables[0].slots[0].weekday").value("MONTAG"))
        .andExpect(jsonPath("$.timetables[0].slots[0].startsAt").value("07:30:00"))
        .andExpect(jsonPath("$.timetables[0].slots[0].roomNumber").value("TEST-R01"))
        .andExpect(jsonPath("$.blockPlans[0].deleteAfter").value("2027-06-30"))
        .andExpect(jsonPath("$.blockPlans[0].blocks[0].classIds", contains(1, 2)))
        .andExpect(jsonPath("$.blockPlans[0].blocks[1].classIds", contains(3)))
        .andExpect(jsonPath("$.rooms[*].roomNumber", contains("TEST-R01", "TEST-R02")))
        .andExpect(
            jsonPath("$.classes[*].classCode", contains("26TEST-A", "26TEST-B", "26TEST-C")));
  }

  /** Lehrkräfte dürfen keine Pläne anlegen */
  @Test
  void rejectsPlanCreationByTeachers() throws Exception {
    write("/api/planning/timetables", teacher, "{\"name\":\"Fremd\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ADMINISTRATOR_REQUIRED"));
    write(
            "/api/planning/block-plans",
            teacher,
            "{\"name\":\"Fremd\",\"startsOn\":\"2027-01-01\",\"endsOn\":\"2027-06-30\"}")
        .andExpect(status().isForbidden());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timetable", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM block_plan", Integer.class)).isEqualTo(1);
  }

  /** Legt Stundenplan und Slots an und lehnt Überschneidungen und ungültige Eingaben ab */
  @Test
  void createsTimetablesAndRejectsOverlappingSlots() throws Exception {
    Long timetableId = createdId(write("/api/planning/timetables", admin, "{\"name\":\" Neu \"}"));
    String slots = "/api/planning/timetables/" + timetableId + "/slots";
    write(
            slots,
            admin,
            "{\"weekday\":\"DIENSTAG\",\"startsAt\":\"07:30\",\"endsAt\":\"09:00\",\"roomId\":1}")
        .andExpect(status().isCreated());
    write(
            slots,
            admin,
            "{\"weekday\":\"DIENSTAG\",\"startsAt\":\"08:45\",\"endsAt\":\"10:00\",\"roomId\":2}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OVERLAPPING_TIMETABLE_SLOT"));
    write(
            slots,
            admin,
            "{\"weekday\":\"DIENSTAG\",\"startsAt\":\"10:00\",\"endsAt\":\"09:00\",\"roomId\":1}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TIMETABLE_SLOT"));
    write(
            slots,
            admin,
            "{\"weekday\":\"SAMSTAG\",\"startsAt\":\"07:30\",\"endsAt\":\"09:00\",\"roomId\":1}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    assertThat(
            jdbc.queryForObject(
                "SELECT name FROM timetable WHERE id = ?", String.class, timetableId))
        .isEqualTo("Neu");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM timetable_slot WHERE timetable_id = ?",
                Integer.class,
                timetableId))
        .isEqualTo(1);
  }

  /**
   * Legt Blockplan mit Löschdatum und Blöcke an und lehnt überlappende und planfremde Zeiträume ab
   */
  @Test
  void createsBlockPlansWithDeletionDateAndRejectsOverlappingBlocks() throws Exception {
    Long planId =
        createdId(
            write(
                "/api/planning/block-plans",
                admin,
                "{\"name\":\"Folgeplan\",\"startsOn\":\"2027-01-01\",\"endsOn\":\"2027-07-15\"}"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM deletion_date WHERE delete_after = '2028-01-15'",
                Integer.class))
        .isEqualTo(1);
    String blocks = "/api/planning/block-plans/" + planId + "/blocks";
    write(blocks, admin, "{\"startsOn\":\"2027-01-11\",\"endsOn\":\"2027-01-22\"}")
        .andExpect(status().isCreated());
    write(blocks, admin, "{\"startsOn\":\"2027-01-22\",\"endsOn\":\"2027-01-29\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OVERLAPPING_BLOCK_ASSIGNMENT"));
    write(blocks, admin, "{\"startsOn\":\"2027-07-12\",\"endsOn\":\"2027-07-19\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_BLOCK_ASSIGNMENT"));
    write(blocks, admin, "{\"startsOn\":\"2026-12-28\",\"endsOn\":\"2027-01-08\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_BLOCK_ASSIGNMENT"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM block_assignment WHERE block_plan_id = ?",
                Integer.class,
                planId))
        .isEqualTo(1);
    write(
            "/api/planning/block-plans",
            admin,
            "{\"name\":\"Falsch\",\"startsOn\":\"2027-02-01\",\"endsOn\":\"2027-01-01\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_BLOCK_PLAN"));
  }

  /** Lehnt planfremde und doppelte Blockzuordnungen ab */
  @Test
  void rejectsConflictingClassBlockAssignments() throws Exception {
    Long foreignPlan =
        createdId(
            write(
                "/api/planning/block-plans",
                admin,
                "{\"name\":\"Fremd\",\"startsOn\":\"2027-01-01\",\"endsOn\":\"2027-06-30\"}"));
    Long foreignBlock =
        createdId(
            write(
                "/api/planning/block-plans/" + foreignPlan + "/blocks",
                admin,
                "{\"startsOn\":\"2027-01-11\",\"endsOn\":\"2027-01-15\"}"));
    write("/api/planning/classes/1/blocks", admin, "{\"blockId\":" + foreignBlock + "}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("BLOCK_ASSIGNMENT_BELONGS_TO_ANOTHER_PLAN"));
    write("/api/planning/classes/1/blocks", admin, "{\"blockId\":1}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("BLOCK_ALREADY_ASSIGNED"));
    write("/api/planning/classes/1/blocks", teacher, "{\"blockId\":2}")
        .andExpect(status().isForbidden());
    write("/api/planning/classes/999/blocks", admin, "{\"blockId\":2}")
        .andExpect(status().isNotFound());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM class_block_assignment", Integer.class))
        .isEqualTo(3);
  }

  /** Ordnet einer Klasse einen freien Block ihres aktuellen Plans zu */
  @Test
  void assignsAFreeBlockOfTheCurrentPlan() throws Exception {
    write("/api/planning/classes/2/blocks", admin, "{\"blockId\":2}")
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForList(
                "SELECT block_assignment_id FROM class_block_assignment WHERE school_class_id = 2"
                    + " ORDER BY block_assignment_id",
                Long.class))
        .containsExactly(1L, 2L);
  }

  /** Lehnt den Planwechsel vor Planende ab und führt ihn danach mit Abschlussreset aus */
  @Test
  void changesTheBlockPlanOnlyAfterTheCurrentPlanHasEnded() throws Exception {
    Long next =
        createdId(
            write(
                "/api/planning/block-plans",
                admin,
                "{\"name\":\"Folgeplan\",\"startsOn\":\"2027-01-01\",\"endsOn\":\"2027-12-31\"}"));
    String change = "/api/planning/classes/1/block-plan-change";
    String body = "{\"expectedBlockPlanId\":1,\"nextBlockPlanId\":" + next + "}";
    write(change, teacher, body)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CURRENT_PLAN_NOT_FINISHED"));
    setTime("2027-02-01T12:00:00Z");
    write(change, staffSession("test.teacher.b"), body).andExpect(status().isNotFound());
    write(change, teacher, "{\"expectedBlockPlanId\":999,\"nextBlockPlanId\":" + next + "}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CLASS_PLAN_CHANGED"));
    write(change, teacher, body).andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject("SELECT block_plan_id FROM school_class WHERE id = 1", Long.class))
        .isEqualTo(next);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE event_type = 'ACCOUNT_RESET'",
                Integer.class))
        .isEqualTo(6);
  }

  /** Liest die ID aus einer erfolgreichen Anlageantwort */
  private Long createdId(ResultActions result) throws Exception {
    String body =
        result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return Long.valueOf(body.replaceAll("\\D", ""));
  }

  /** Legt eine Testsitzung mit dem gespeicherten Sicherheitskontext des Mitarbeiters an */
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

  /** Führt einen POST mit Sitzung und passendem CSRF-Token aus */
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

  /** Setzt den aktuellen Zeitpunkt der Testuhr */
  private void setTime(String instant) {
    when(clock.instant()).thenReturn(Instant.parse(instant));
  }
}
