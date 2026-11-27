package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.feswiesbaden.attendance.config.AttendanceJobs;
import de.feswiesbaden.attendance.dtos.RawScanDTO;
import de.feswiesbaden.attendance.enums.AttendanceStatus;
import de.feswiesbaden.attendance.enums.ScanResult;
import de.feswiesbaden.attendance.login.StaffPrincipal;
import de.feswiesbaden.attendance.repository.RetentionRepository;
import de.feswiesbaden.attendance.service.AttendanceAccountingService;
import de.feswiesbaden.attendance.service.RetentionService;
import de.feswiesbaden.attendance.service.SchoolClassService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
@SpringBootTest(
    properties =
        "attendance.terminal.proxy-token=" + AttendanceAccountingIntegrationTest.PROXY_TOKEN)
@AutoConfigureMockMvc
@Testcontainers
@Sql("/fixtures/attendance.sql")
@ExtendWith(OutputCaptureExtension.class)
class AttendanceAccountingIntegrationTest {
  static final String PROXY_TOKEN =
      "0000000000000000000000000000000000000000000000000000000000000000";

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

  /** Prüft die drei Exportumfänge, den Berliner Blockbeginn und die Klassengrenze. */
  @Test
  void exportsBlockAttendanceAndEnforcesClassAccess() throws Exception {
    String export = "/api/attendance/export";
    var teacher = staffSession("test.teacher.a");
    var admin = staffSession("test.admin");
    setTime("2026-09-14T05:50:00Z");
    assertThat(scan(3, "2026-09-14T05:50:00Z")).isTrue();
    setTime("2026-09-14T06:30:00Z");
    write(post(CORRECTION), teacher, EXCUSE).andExpect(status().isOk());
    write(post(CORRECTION.replace("/1/", "/2/")), teacher, EXCUSE.replace("05:45", "05:30"))
        .andExpect(status().isOk());
    write(post(export), teacher, "{\"classes\":{\"1\":[1,2]}}")
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"Verspaetet\";0;60;0;1;0;0;")));
    jdbc.update(
        "UPDATE student SET first_name = ?, last_name = ? WHERE id = 1",
        "=1+1",
        "Müller; \"Test\"");
    jdbc.update(
        "INSERT INTO timetable_slot (id, timetable_id, room_id, weekday, start_time, end_time)"
            + " VALUES (5, 1, 1, 'DIENSTAG', '10:30:00', '11:15:00')");
    setTime("2026-09-15T08:45:00Z");
    accounting.closeDueDays();

    var csv =
        write(post(export), teacher, "{\"classes\":{\"1\":[1]}}")
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/csv;charset=UTF-8"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(
                header().string("Content-Disposition", "attachment; filename=anwesenheit.csv"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(csv.lines().toList())
        .hasSize(2)
        .first()
        .asString()
        .startsWith("\uFEFFKlasse;")
        .endsWith("Unentschuldigte Fehltage;Entschuldigte Fehltage;Tagesdetails (Berliner Datum)");
    assertThat(csv.lines().toList().getLast())
        .isEqualTo(
            "\"26TEST-A\";\"'=1+1\";\"Müller; \"\"Test\"\"\";15;165;0;0;1;0;"
                + "\"2026-09-14 | ENTSCHULDIGT | 15 min unentschuldigt | 165 min entschuldigt"
                + " / 2026-09-15 | ABWESEND | 15 min unentschuldigt | 0 min entschuldigt\"");

    write(post(export), teacher, "{\"classes\":{\"1\":[]}}")
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"Verspaetet\";0;180;0;0;0;1;")))
        .andExpect(content().string(containsString("\"Statuswechsel\";20;0;0;0;0;0;")));
    String group = "{\"classes\":{\"1\":[1],\"2\":[7]}}";
    write(post(export), teacher, group).andExpect(status().isNotFound());
    write(post(export), teacher, "{\"classes\":{\"1\":[1,7]}}").andExpect(status().isBadRequest());
    write(post(export), admin, group)
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Raum-B")));
    write(post(export), teacher, "{\"classes\":{\"3\":[]}}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("NO_ACTIVE_EXPORT_BLOCK"));

    setTime("2026-09-15T09:30:00Z");
    write(post(RESET), admin, "{\"expectedBlockPlanId\":1}").andExpect(status().isOk());
    write(post(export), teacher, "{\"classes\":{\"1\":[2]}}")
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"Verspaetet\";0;0;0;0;0;0;")))
        .andExpect(content().string(containsString("2026-09-14 | ENTSCHULDIGT")));
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

  @Test
  void adminManagesRoomsThroughHttp() throws Exception {
    var admin = staffSession("test.admin");
    var teacher = staffSession("test.teacher.b");
    String room = "{\"roomNumber\":\"A104\"}";
    write(post("/api/rooms"), teacher, room).andExpect(status().isForbidden());
    write(post("/api/rooms"), admin, room).andExpect(status().isCreated());
    write(post("/api/rooms"), admin, room).andExpect(status().isConflict());

    long id = jdbc.queryForObject("SELECT id FROM room WHERE room_number = 'A104'", Long.class);
    String item = "/api/rooms/" + id;
    String entry = "$[?(@.id == " + id + ")]";
    mvc.perform(get("/api/rooms").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath(entry + ".roomNumber", contains("A104")));
    write(put(item), admin, "{\"roomNumber\":\"A105\"}").andExpect(status().isNoContent());
    mvc.perform(get("/api/rooms").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath(entry + ".roomNumber", contains("A105")));
    write(delete(item), admin, "").andExpect(status().isNoContent());
    mvc.perform(get("/api/rooms").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath(entry).isEmpty());
  }

  @Test
  void adminManagesEmptyClassAndKeepsPlansAndBlocksWhenDeletingIt() throws Exception {
    var admin = staffSession("test.admin");
    write(
            post("/api/classes"),
            admin,
            """
            {"classCode":"26TEST-LEER","classTeacherId":1,"blockPlanId":1,"timetableId":1}
            """)
        .andExpect(status().isCreated());
    long id =
        jdbc.queryForObject(
            "SELECT id FROM school_class WHERE class_code = '26TEST-LEER'", Long.class);
    String item = "/api/classes/" + id;
    String entry = "$[?(@.id == " + id + ")]";
    mvc.perform(get("/api/classes").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath(entry + ".classTeacherId", contains(1)));
    write(put(item), admin, "{\"classCode\":\"26TEST-NEU\",\"classTeacherId\":2}")
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/classes").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath(entry + ".classCode", contains("26TEST-NEU")))
        .andExpect(jsonPath(entry + ".classTeacherId", contains(2)));

    jdbc.update("INSERT INTO teacher_class VALUES (1, ?)", id);
    jdbc.update(
        "INSERT INTO class_block_assignment SELECT ?, id FROM block_assignment LIMIT 1", id);
    int blocks = jdbc.queryForObject("SELECT COUNT(*) FROM block_assignment", Integer.class);
    int plans = jdbc.queryForObject("SELECT COUNT(*) FROM block_plan", Integer.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_block_assignment WHERE school_class_id = ?",
                Integer.class,
                id))
        .isOne();
    write(delete(item), admin, "").andExpect(status().isNoContent());
    mvc.perform(get("/api/classes").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath(entry).isEmpty());
    for (String table : List.of("teacher_class", "class_block_assignment")) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM " + table + " WHERE school_class_id = ?",
                  Integer.class,
                  id))
          .isZero();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM school_class WHERE id = ?", Integer.class, id))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM block_assignment", Integer.class))
        .isEqualTo(blocks);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM block_plan", Integer.class))
        .isEqualTo(plans);
  }

  @Test
  void occupiedClassKeepsAllAssignments() throws Exception {
    var admin = staffSession("test.admin");
    var assignments =
        jdbc.queryForList("SELECT * FROM class_block_assignment WHERE school_class_id=1");
    write(delete("/api/classes/1"), admin, "")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CLASS_HAS_STUDENTS"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM teacher_class WHERE school_class_id=1", Integer.class))
        .isOne();
    assertThat(jdbc.queryForList("SELECT * FROM class_block_assignment WHERE school_class_id=1"))
        .containsExactlyInAnyOrderElementsOf(assignments);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM student WHERE school_class_id=1", Integer.class))
        .isPositive();
  }

  @Test
  void adminManagesTeachersButCannotChangeAccountFields() throws Exception {
    var admin = staffSession("test.admin");
    mvc.perform(get("/api/teachers").session(staffSession("test.teacher.b")))
        .andExpect(status().isForbidden());
    write(
            post("/api/teachers"),
            admin,
            """
            {"firstName":"Test","lastName":"Lehrkraft","username":"test.crud","password":"Fixture-Passwort"}
            """)
        .andExpect(status().isCreated());
    long id = jdbc.queryForObject("SELECT id FROM staff WHERE username = 'test.crud'", Long.class);
    String item = "/api/teachers/" + id;
    String entry = "$[?(@.id == " + id + ")]";
    mvc.perform(get("/api/teachers").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath(entry + ".username", contains("test.crud")));
    String names = "{\"firstName\":\"Neuer\",\"lastName\":\"Name\"}";
    write(put(item), admin, names).andExpect(status().isNoContent());
    for (String field : List.of("username", "password")) {
      write(put(item), admin, names.replace("}", ",\"" + field + "\":\"anders\"}"))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(get("/api/teachers").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath(entry + ".firstName", contains("Neuer")))
        .andExpect(jsonPath(entry + ".lastName", contains("Name")))
        .andExpect(jsonPath(entry + ".username", contains("test.crud")));
    write(delete(item), admin, "").andExpect(status().isNoContent());
    mvc.perform(get("/api/teachers").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath(entry).isEmpty());
  }

  @Test
  void lateSessionCannotUseReassignedUsername() throws Exception {
    jdbc.update(
        "INSERT INTO staff(id,first_name,last_name,username,password_hash,role) "
            + "SELECT 99,'Test','Alt','reused',password_hash,role FROM staff WHERE id=1");
    var lateSession = staffSession("reused");
    write(delete("/api/teachers/99"), staffSession("test.admin"), "")
        .andExpect(status().isNoContent());
    jdbc.update(
        "INSERT INTO staff(id,first_name,last_name,username,password_hash,role) "
            + "SELECT 100,'Test','Neu','reused',password_hash,role FROM staff WHERE id=1");
    mvc.perform(get("/api/me").session(lateSession)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/me").session(staffSession("reused"))).andExpect(status().isOk());
  }

  @Test
  void adminManagesStudentsThroughHttp() throws Exception {
    var admin = staffSession("test.admin");
    String collection = "/api/classes/1/students";
    write(post(collection), admin, STUDENT).andExpect(status().isCreated());
    long id = jdbc.queryForObject("SELECT id FROM student WHERE first_name = 'Neu'", Long.class);
    String item = "/api/students/" + id;
    String entry = "$[?(@.id == " + id + ")]";
    mvc.perform(get(collection).session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath(entry + ".unexcusedMinutes", contains(0)))
        .andExpect(jsonPath(entry + ".excusedMinutes", contains(0)));
    write(put(item), admin, STUDENT.replace("Neu", "Geändert")).andExpect(status().isNoContent());
    mvc.perform(get(item).session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.firstName").value("Geändert"));
    write(delete(item), admin, "").andExpect(status().isNoContent());
    mvc.perform(get(collection).session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath(entry).isEmpty());
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

  /** Prüft Kartenzuordnung über HTTP mit CSRF, Rollenprüfung und neutralen Antworten */
  @Test
  @ExtendWith(RfidLoggingExtension.class)
  void managesRfidCardsThroughAdminHttp(CapturedOutput output) throws Exception {
    var admin = staffSession("test.admin");
    var teacher = staffSession("test.teacher.a");
    String card = "/api/students/1/rfid-uid";
    String newUid = "{\"uid\":\"04A3B2C1\"}";

    mvc.perform(put(card).session(admin).contentType(MediaType.APPLICATION_JSON).content(newUid))
        .andExpect(status().isForbidden());
    write(put(card), teacher, newUid).andExpect(status().isForbidden());
    write(delete(card), teacher, "").andExpect(status().isForbidden());
    write(put(card), admin, "{\"uid\":\"invalid\"}")
        .andExpect(status().isBadRequest())
        .andExpect(content().json("{\"code\":\"INVALID_RFID_UID\"}"));
    write(put(card), admin, newUid)
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    assertThat(jdbc.queryForObject("SELECT rfid_uid FROM student WHERE id = 1", String.class))
        .isEqualTo("04A3B2C1");
    write(put("/api/students/2/rfid-uid"), admin, newUid)
        .andExpect(status().isConflict())
        .andExpect(content().json("{\"code\":\"RFID_UID_ALREADY_ASSIGNED\"}"));
    mvc.perform(get("/api/students/1").session(admin))
        .andExpect(jsonPath("$.rfidUid").doesNotExist());
    write(delete(card), admin, "")
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    assertThat(jdbc.queryForObject("SELECT rfid_uid FROM student WHERE id = 1", String.class))
        .isNull();
    assertThat(output.getAll())
        .contains("to [RfidUidRequest]", "Setting SQL statement parameter value")
        .doesNotContain("04A3B2C1");
  }

  /** Prüft den klassenübergreifenden UID-Lesezugriff und die Begrenzung auf den Leseendpunkt */
  @Test
  @ExtendWith(RfidLoggingExtension.class)
  void teacherReadsRfidAssignmentsAcrossClassBoundaries(CapturedOutput output) throws Exception {
    String endpoint = "/api/students/rfid-uids";
    var teacher = staffSession("test.teacher.a");
    jdbc.update("UPDATE student SET rfid_uid = NULL WHERE id = 8");

    mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
    mvc.perform(get(endpoint).session(staffSession("test.admin")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/students/7").session(teacher)).andExpect(status().isNotFound());
    mvc.perform(get("/api/students/1").session(teacher))
        .andExpect(jsonPath("$.rfidUid").doesNotExist());
    mvc.perform(get(endpoint).session(teacher))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.length()").value(7))
        .andExpect(
            jsonPath("$")
                .value(
                    hasItem(
                        Map.of(
                            "studentName", "Raum-B, Test",
                            "classCode", "26TEST-B",
                            "rfidUid", "TEST-UID-007"))));
    assertThat(output.getAll())
        .contains("Writing [[RfidAssignment")
        .doesNotContain("TEST-UID-007", "TEST-UID-005");
  }

  /** Prüft Planbindung, Resets ausgewählter Klassen und erhaltene Tageswerte */
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
    var deadlines = jdbc.queryForList("SELECT * FROM deletion_date ORDER BY id");
    write(post(RESET), admin, plan)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resetCount").value(6));
    assertAccount(7, 0, 0);
    write(post("/api/classes/2/account-resets"), admin, plan)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resetCount").value(1));
    assertAccount(7, 0, 0);
    write(post(RESET), admin, plan)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resetCount").value(6));
    assertThat(jdbc.queryForList("SELECT * FROM deletion_date ORDER BY id")).isEqualTo(deadlines);
    var resets =
        jdbc.queryForList(
            "SELECT * FROM attendance_audit WHERE event_type = 'ACCOUNT_RESET' ORDER BY id");
    assertThat(resets)
        .allSatisfy(
            audit ->
                assertThat(audit)
                    .containsEntry("changed_by_staff_id", 3L)
                    .containsEntry("deletion_date_id", 1L)
                    .containsEntry("attendance_id", null))
        .extracting(audit -> audit.get("student_id"))
        .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 1L, 2L, 3L, 4L, 5L, 6L);
    assertThat(resets.get(6))
        .containsEntry("unexcused_minutes_delta", -180)
        .containsEntry("excused_minutes_delta", 0);
    assertThat(
            jdbc.queryForList(
                """
                SELECT student_id FROM attendance_audit
                WHERE event_type = 'ACCOUNT_RESET' AND unexcused_minutes_delta = 0
                  AND excused_minutes_delta = 0
                ORDER BY student_id
                """,
                Long.class))
        .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    jdbc.update("DELETE FROM student WHERE id = 8");
    setTime("2026-09-21T06:00:00Z");
    write(post("/api/classes/3/account-resets"), admin, plan)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resetCount").value(0));
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

  /** Prüft Tagesabschluss ohne Scan, nachträgliche Korrektur und erneuten Abschluss */
  @Test
  void closesOnlyExpectedDaysAndDoesNotDoubleBookAfterCorrection() throws Exception {
    var attendanceJobs = new AttendanceJobs(accounting);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isZero();
    setTime("2026-09-14T22:15:00Z");
    attendanceJobs.closeDays();
    assertThat(
            jdbc.queryForList(
                "SELECT student_id FROM attendance"
                    + " WHERE attendance_date = ? AND status = 'ABWESEND' ORDER BY student_id",
                Long.class,
                MONDAY))
        .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isEqualTo(7);
    for (int student = 1; student <= 7; student++) {
      assertAccount(student, 180, 0);
    }
    assertAccount(8, 0, 0);
    assertThat(
            jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM attendance_audit audit
                JOIN attendance day ON day.id = audit.attendance_id AND day.student_id = audit.student_id
                WHERE audit.event_type = 'DAILY_CLOSE' AND audit.changed_by_staff_id IS NULL
                  AND audit.terminal_id IS NULL AND audit.old_status IS NULL AND audit.new_status IS NULL
                  AND audit.deletion_date_id = 1 AND audit.unexcused_minutes_delta = 180
                  AND audit.excused_minutes_delta = 0
                """,
                Integer.class))
        .isEqualTo(7);
    setTime("2026-09-14T22:30:00Z");
    write(post(CORRECTION), staffSession("test.teacher.a"), EXCUSE)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    assertAccount(1, 15, 165);
    assertThat(
            jdbc.queryForMap(
                "SELECT * FROM attendance_audit WHERE student_id = 1 AND event_type = 'STATUS_CHANGE'"))
        .containsEntry("unexcused_minutes_delta", -165)
        .containsEntry("excused_minutes_delta", 165);
    var audits = jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id");
    attendanceJobs.closeDays();
    assertThat(jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id")).isEqualTo(audits);
    assertAccount(1, 15, 165);
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

  /** Prüft Scans paralleler Klassen und den Klassenwechsel im selben Raum beim Folgeblock */
  @Test
  void scansUseTheAssignedClassAndTimetableAtCaptureTime() throws Exception {
    setTime("2026-09-21T06:00:00Z");
    postScan("TEST-UID-001", 9001, "2026-09-14T05:30:00Z").andExpect(status().isNoContent());
    postScan("TEST-UID-007", 9001, "2026-09-14T05:30:00Z").andExpect(status().isBadRequest());
    postScan("TEST-UID-007", 9002, "2026-09-14T05:30:00Z").andExpect(status().isNoContent());
    postScan("TEST-UID-001", 9001, "2026-09-21T05:30:00Z").andExpect(status().isBadRequest());
    postScan("TEST-UID-008", 9001, "2026-09-21T05:30:00Z").andExpect(status().isNoContent());

    assertThat(
            jdbc.query(
                """
                SELECT day.student_id, day.attendance_date, audit.terminal_id FROM attendance day
                JOIN attendance_audit audit ON audit.attendance_id = day.id
                WHERE audit.event_type = 'SCAN' AND day.status = 'ANWESEND'
                ORDER BY day.student_id
                """,
                (row, index) ->
                    List.of(row.getInt(1), row.getObject(2, LocalDate.class), row.getInt(3))))
        .containsExactly(
            List.of(1, MONDAY, 9001),
            List.of(7, MONDAY, 9002),
            List.of(8, MONDAY.plusWeeks(1), 9001));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isEqualTo(3);
  }

  /**
   * Prüft die Ablehnung eines Scans, dessen Raum zur selben Zeit von einer anderen, gleichzeitig
   * aktiven Klasse belegt ist. Plan und Block der anderen Klasse beginnen und enden am Scantag,
   * damit alle Datumsgrenzen inklusive geprüft werden
   */
  @Test
  void rejectsScansWhenAnotherActiveClassDoubleBooksTheRoom() {
    insertPlan99("2026-09-14", "2026-09-14", "2026-09-14", "2026-09-14");
    insertOtherClassInRoom1(99, 99, "08:00:00", "08:45:00");
    assertThat(scan(1, "2026-09-14T06:15:00Z")).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance", Integer.class)).isZero();
  }

  /**
   * Überschneiden sich frühere Slots des Tages, wird auch ein Scan im späteren, selbst nicht
   * überlappenden Slot neutral abgelehnt. Der Rohscan bleibt gespeichert und der Tagesabruf für die
   * Lehrkraft bleibt möglich
   */
  @Test
  void rejectsScansWhenEarlierSlotsOfTheDayOverlap() throws Exception {
    jdbc.update(
        "INSERT INTO timetable_slot (id, timetable_id, room_id, weekday, start_time, end_time)"
            + " VALUES (99, 1, 2, 'MONTAG', '08:00:00', '08:45:00')");
    var rawScans = jdbc.queryForObject("SELECT COUNT(*) FROM raw_scan", Integer.class);
    assertThat(scan(1, "2026-09-14T07:15:00Z")).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_scan", Integer.class))
        .isEqualTo(rawScans + 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE student_id = 1", Integer.class))
        .isZero();
    mvc.perform(get(CLASS_DAY).session(staffSession("test.teacher.a")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("ABWESEND"));
  }

  /** Ein Slot ohne Dauer einer anderen Klasse belegt den Raum nicht */
  @Test
  void acceptsScansWhenAnotherClassHasOnlyAnEmptySlotInTheRoom() {
    insertOtherClassInRoom1(1, 1, "08:15:00", "08:15:00");
    assertThat(scan(1, "2026-09-14T06:15:00Z")).isTrue();
  }

  /** Der Block einer anderen Klasse aus einem fremden Plan macht diese Klasse nicht aktiv */
  @Test
  void acceptsScansWhenAnotherClassIsOnlyAssignedToABlockOfAForeignPlan() {
    insertPlan99("2026-09-01", "2026-12-31", "2026-09-14", "2026-09-18");
    insertOtherClassInRoom1(1, 99, "08:00:00", "08:45:00");
    assertThat(scan(1, "2026-09-14T06:15:00Z")).isTrue();
  }

  /**
   * Eine andere Klasse blockiert den Raum nur, wenn ihr aktueller Blockplan und ihr Block den
   * Scantag einschließen. Jeweils eine der beiden Spannen endet vor oder beginnt nach dem 14.09.
   */
  @ParameterizedTest
  @CsvSource({
    "2026-09-01, 2026-09-13, 2026-09-14, 2026-09-18",
    "2026-09-15, 2026-12-31, 2026-09-14, 2026-09-18",
    "2026-09-01, 2026-12-31, 2026-09-07, 2026-09-13"
  })
  void acceptsScansWhenTheOtherClassPlanOrBlockExcludesTheScanDay(
      String planStartsOn, String planEndsOn, String blockStartsOn, String blockEndsOn) {
    insertPlan99(planStartsOn, planEndsOn, blockStartsOn, blockEndsOn);
    insertOtherClassInRoom1(99, 99, "08:00:00", "08:45:00");
    assertThat(scan(1, "2026-09-14T06:15:00Z")).isTrue();
  }

  /** Direkt aneinander anschließende Slots des Tages gelten nicht als Überlappung */
  @Test
  void acceptsScansWhenSlotsOfTheDayOnlyTouch() {
    jdbc.update(
        "INSERT INTO timetable_slot (id, timetable_id, room_id, weekday, start_time, end_time)"
            + " VALUES (99, 1, 2, 'MONTAG', '09:00:00', '09:15:00')");
    assertThat(scan(1, "2026-09-14T06:15:00Z")).isTrue();
  }

  /**
   * Eine aktive Klasse belegt den Raum nur in ihren eigenen Zeitfenstern. Direkt vor und nach dem
   * gescannten Slot anschließende Slots am Scantag und ein gleichzeitiger Slot an einem anderen
   * Wochentag blockieren nicht
   */
  @Test
  void acceptsScansWhenAnotherClassUsesTheRoomOnlyAtOtherTimes() {
    insertPlan99("2026-09-14", "2026-09-14", "2026-09-14", "2026-09-14");
    insertOtherClassInRoom1(99, 99, "09:00:00", "09:15:00");
    jdbc.update(
        "INSERT INTO timetable_slot (id, timetable_id, room_id, weekday, start_time, end_time)"
            + " VALUES (98, 99, 1, 'DIENSTAG', '08:00:00', '08:45:00'),"
            + " (97, 99, 1, 'MONTAG', '07:00:00', '07:30:00')");
    assertThat(scan(1, "2026-09-14T06:15:00Z")).isTrue();
  }

  /** Legt Blockplan 99 mit dem Block 99 an */
  private void insertPlan99(
      String planStartsOn, String planEndsOn, String blockStartsOn, String blockEndsOn) {
    jdbc.update(
        "INSERT INTO block_plan (id, name, starts_on, ends_on) VALUES (99, 'Plan 99', ?, ?)",
        planStartsOn,
        planEndsOn);
    jdbc.update(
        "INSERT INTO block_assignment (id, block_plan_id, starts_on, ends_on)"
            + " VALUES (99, 99, ?, ?)",
        blockStartsOn,
        blockEndsOn);
  }

  /** Legt Klasse 99 mit einem Montagsslot in Raum 1 an und weist ihr einen Block zu */
  private void insertOtherClassInRoom1(long planId, long blockId, String startsAt, String endsAt) {
    jdbc.update("INSERT INTO timetable (id, name) VALUES (99, 'Stundenplan 99')");
    jdbc.update(
        "INSERT INTO timetable_slot (id, timetable_id, room_id, weekday, start_time, end_time)"
            + " VALUES (99, 99, 1, 'MONTAG', ?, ?)",
        startsAt,
        endsAt);
    jdbc.update(
        "INSERT INTO school_class (id, class_code, class_teacher_id, block_plan_id, timetable_id)"
            + " VALUES (99, 'KLASSE99', 1, ?, 99)",
        planId);
    jdbc.update(
        "INSERT INTO class_block_assignment (school_class_id, block_assignment_id) VALUES (99, ?)",
        blockId);
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

  /** Sendet einen Testscan an den Spring-Endpunkt mit der vom Proxy bestätigten Identität */
  private ResultActions postScan(String rfidUid, int terminal, String at) throws Exception {
    return mvc.perform(
        post("/api/scans")
            .header("X-Terminal-Proxy-Token", PROXY_TOKEN)
            .header("X-Terminal-Client-Verify", "SUCCESS")
            .header("X-Terminal-Client-Subject", "CN=terminal-" + terminal)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"scanId":"%s","rfidUid":"%s","terminalNumber":%d,"capturedAt":"%s"}
                """
                    .formatted(UUID.randomUUID(), rfidUid, terminal, at)));
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
            UsernamePasswordAuthenticationToken.authenticated(
                new StaffPrincipal(
                    jdbc.queryForObject(
                        "SELECT id FROM staff WHERE username=?", Long.class, username),
                    username,
                    "unused",
                    List.of()),
                null,
                List.of()));
  }
}
