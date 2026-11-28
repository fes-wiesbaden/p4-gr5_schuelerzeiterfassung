package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.feswiesbaden.attendance.dtos.RawScanDTO;
import de.feswiesbaden.attendance.enums.ScanResult;
import de.feswiesbaden.attendance.login.StaffPrincipal;
import de.feswiesbaden.attendance.service.AttendanceAccountingService;
import de.feswiesbaden.attendance.service.CodexRunner;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import org.springframework.mock.web.MockMultipartFile;
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

  @Autowired AttendanceAccountingService accounting;

  @MockitoBean Clock clock;

  @MockitoBean CodexRunner codex;

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

  /** Speichert Fach und Lehrkraft eines Slots und übernimmt sie in neue Fassungen */
  @Test
  void storesSubjectAndTeacherAndCarriesThemIntoNewSlotVersions() throws Exception {
    var slot =
        """
        {"weekday":"DIENSTAG","startsAt":"08:00","endsAt":"09:00","roomId":1,
         "subject":" Mathe ","teacherId":1}
        """;
    var tuesday = "$.timetables[0].slots[?(@.weekday == 'DIENSTAG')]";
    write(
            "/api/planning/timetables/1/slots",
            admin,
            slot.replace("\"teacherId\":1", "\"teacherId\":3"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TIMETABLE_SLOT"));
    write("/api/planning/timetables/1/slots", admin, slot.replace(" Mathe ", "x".repeat(81)))
        .andExpect(status().isBadRequest());
    Long slotId = createdId(write("/api/planning/timetables/1/slots", admin, slot));

    mvc.perform(get("/api/planning").session(admin))
        .andExpect(
            jsonPath("$.teachers[*].name", contains("Lehrkraft-A, Test", "Lehrkraft-B, Test")))
        .andExpect(jsonPath("$.teachers[0].id").value(1))
        .andExpect(jsonPath(tuesday + ".subject", contains("Mathe")))
        .andExpect(jsonPath(tuesday + ".teacherId", contains(1)))
        .andExpect(jsonPath(tuesday + ".teacherName", contains("Test Lehrkraft-A")))
        .andExpect(jsonPath("$.timetables[0].slots[0].subject").isEmpty())
        .andExpect(jsonPath("$.timetables[0].slots[0].teacherName").isEmpty());
    mvc.perform(get("/api/planning/classes/1/week?date=2026-09-15").session(teacher))
        .andExpect(jsonPath("$[0].lessons[0].subject").isEmpty())
        .andExpect(jsonPath("$[0].lessons[0].teacherName").isEmpty())
        .andExpect(jsonPath("$[1].lessons[0].subject").value("Mathe"))
        .andExpect(jsonPath("$[1].lessons[0].teacherName").value("Test Lehrkraft-A"));

    write(
            put("/api/planning/timetables/1/slots/" + slotId),
            admin,
            """
            {"weekday":"DIENSTAG","startsAt":"08:15","endsAt":"09:00","roomId":1}
            """)
        .andExpect(status().isOk());
    mvc.perform(get("/api/planning").session(admin))
        .andExpect(jsonPath(tuesday + ".startsAt", contains("08:15:00")))
        .andExpect(jsonPath(tuesday + ".subject", contains("Mathe")))
        .andExpect(jsonPath(tuesday + ".teacherId", contains(1)));
    Long versionId =
        jdbc.queryForObject(
            "SELECT id FROM timetable_slot WHERE weekday = 'DIENSTAG' AND valid_until IS NULL",
            Long.class);
    write(
            put("/api/planning/timetables/1/slots/" + versionId),
            admin,
            """
            {"weekday":"DIENSTAG","startsAt":"08:15","endsAt":"09:00","roomId":1,
             "subject":null,"teacherId":2}
            """)
        .andExpect(status().isOk());
    mvc.perform(get("/api/planning").session(admin))
        .andExpect(jsonPath(tuesday + ".subject", contains((Object) null)))
        .andExpect(jsonPath(tuesday + ".teacherName", contains("Test Lehrkraft-B")));
    jdbc.update("DELETE FROM teacher_class WHERE staff_id = 2");
    jdbc.update("UPDATE school_class SET class_teacher_id = 1 WHERE class_teacher_id = 2");
    jdbc.update("DELETE FROM staff WHERE id = 2");
    mvc.perform(get("/api/planning").session(admin))
        .andExpect(jsonPath(tuesday + ".teacherId", contains((Object) null)));
  }

  /** Importiert einen Stundenplan atomar und nennt die Position der fehlerhaften Unterrichtszeit */
  @Test
  void importsATimetableAtomicallyAndReportsTheInvalidSlot() throws Exception {
    var path = "/api/planning/timetables/import";
    var valid =
        """
        {"name":" Import ","slots":[
          {"weekday":"MONTAG","startsAt":"07:30","endsAt":"09:00","roomId":1,
           "subject":"LF","teacherId":2},
          {"weekday":"MONTAG","startsAt":"09:15","endsAt":"10:45","roomId":2}]}
        """;
    int timetablesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM timetable", Integer.class);
    int slotsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM timetable_slot", Integer.class);

    mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(valid))
        .andExpect(status().isForbidden());
    write(path, admin, valid.replace("09:15", "08:00"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("OVERLAPPING_TIMETABLE_SLOT"))
        .andExpect(jsonPath("$.slotIndex").value(1));
    write(path, admin, valid.replace("\"roomId\":2", "\"roomId\":99"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TIMETABLE_SLOT"))
        .andExpect(jsonPath("$.slotIndex").value(1));
    write(path, admin, valid.replace("\"teacherId\":2", "\"teacherId\":3"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.slotIndex").value(0));
    write(path, admin, "{\"name\":\"Leer\",\"slots\":[]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TIMETABLE_SLOT"))
        .andExpect(jsonPath("$.slotIndex").isEmpty());
    write(path, admin, valid.replace(" Import ", " "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TIMETABLE"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timetable", Integer.class))
        .isEqualTo(timetablesBefore);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timetable_slot", Integer.class))
        .isEqualTo(slotsBefore);

    write(path, teacher, valid)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ADMINISTRATOR_REQUIRED"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timetable", Integer.class))
        .isEqualTo(timetablesBefore);
    Long id = createdIdOk(write(path, admin, valid));
    assertThat(jdbc.queryForObject("SELECT name FROM timetable WHERE id = ?", String.class, id))
        .isEqualTo("Import");
    assertThat(
            jdbc.queryForList(
                "SELECT subject, teacher_id, room_id FROM timetable_slot WHERE timetable_id = ?"
                    + " ORDER BY start_time",
                id))
        .extracting(
            row -> row.get("subject"), row -> row.get("teacher_id"), row -> row.get("room_id"))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("LF", 2L, 1L),
            org.assertj.core.groups.Tuple.tuple(null, null, 2L));
    mvc.perform(get("/api/planning").session(teacher)).andExpect(status().isForbidden());
  }

  /** Legt beim Import eine neue Klasse mit dem neuen Stundenplan an oder gar nichts */
  @Test
  void importsATimetableTogetherWithANewClass() throws Exception {
    var path = "/api/planning/timetables/import";
    var body =
        """
        {"name":"Neu","slots":[
          {"weekday":"MONTAG","startsAt":"11:00","endsAt":"12:30","roomId":1}],
         "schoolClass":{"classCode":"26NEU","classTeacherId":2,"blockPlanId":1}}
        """;
    int timetablesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM timetable", Integer.class);
    write(path, admin, body.replace("\"roomId\":1", "\"roomId\":99"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.slotIndex").value(0));
    write(path, admin, body.replace("26NEU", "26TEST-A"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CLASS_CODE_ALREADY_EXISTS"))
        .andExpect(jsonPath("$.slotIndex").isEmpty());
    write(path, admin, body.replace("\"classTeacherId\":2", "\"classTeacherId\":3"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_CLASS"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timetable", Integer.class))
        .isEqualTo(timetablesBefore);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM school_class WHERE class_code = '26NEU'", Integer.class))
        .isZero();

    var response =
        write(path, admin, body)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.id")).longValue();
    long classId = ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.classId")).longValue();
    assertThat(
            jdbc.queryForMap(
                "SELECT class_code, class_teacher_id, timetable_id FROM school_class WHERE id = ?",
                classId))
        .containsEntry("class_code", "26NEU")
        .containsEntry("class_teacher_id", 2L)
        .containsEntry("timetable_id", id);
  }

  /** Wechselt den Stundenplan einer Klasse ab einem künftigen Tag, ohne frühere Tage zu ändern */
  @Test
  void importsATimetableForAnExistingClassFromAFutureDate() throws Exception {
    var path = "/api/planning/timetables/import";
    var cancellation = "/api/planning/classes/1/lesson-cancellations";
    var week = "/api/planning/classes/1/week?date=2026-09-14";
    var body =
        """
        {"name":"Ab Mittwoch","slots":[
          {"weekday":"MONTAG","startsAt":"11:00","endsAt":"12:30","roomId":1,"subject":"Neu"},
          {"weekday":"MITTWOCH","startsAt":"08:00","endsAt":"09:30","roomId":1}],
         "schoolClass":{"id":1,"validFrom":"2026-09-16"}}
        """;
    setTime("2026-09-15T07:00:00Z");
    accounting.closeDueDays();
    assertThat(account(1)).isEqualTo(180);

    write(path, admin, body.replace("2026-09-16", "2026-09-15"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TIMETABLE_VALID_FROM"));
    write(path, admin, body.replace("\"id\":1", "\"id\":99")).andExpect(status().isNotFound());
    assertThat(
            jdbc.queryForObject("SELECT timetable_id FROM school_class WHERE id = 1", Long.class))
        .isEqualTo(1L);
    var response =
        write(path, admin, body)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.classId").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.id")).longValue();
    assertThat(
            jdbc.queryForObject("SELECT timetable_id FROM school_class WHERE id = 1", Long.class))
        .isEqualTo(id);
    assertThat(
            jdbc.queryForObject("SELECT timetable_id FROM school_class WHERE id = 3", Long.class))
        .isEqualTo(1L);

    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$[0].lessons[*].startsAt", contains("07:30:00", "09:15:00")))
        .andExpect(jsonPath("$[2].lessons[*].startsAt", contains("08:00:00")));
    mvc.perform(get("/api/planning/classes/3/week?date=2026-09-21").session(teacher))
        .andExpect(jsonPath("$[0].lessons[*].startsAt", contains("07:30:00", "09:15:00")));

    // Ein bereits abgerechneter Tag rechnet auch bei erneuter Abrechnung mit dem alten Plan
    accounting.closeDueDays();
    assertThat(account(1)).isEqualTo(180);
    write(cancellation, admin, "{\"date\":\"2026-09-14\",\"startsAt\":\"09:15\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    assertThat(account(1)).isEqualTo(90);
    write(delete(cancellation + "?date=2026-09-14"), admin, "").andExpect(status().isOk());
    assertThat(account(1)).isEqualTo(180);

    setTime("2026-09-16T08:00:00Z");
    accounting.closeDueDays();
    assertThat(account(1)).isEqualTo(270);
  }

  /** Ein befristeter Plan gilt nur in seinem Zeitraum; danach setzt der bisherige wieder ein */
  @Test
  void importsATemporaryTimetableAndResumesThePreviousOneAfterwards() throws Exception {
    var path = "/api/planning/timetables/import";
    var body =
        """
        {"name":"Befristet","slots":[
          {"weekday":"MONTAG","startsAt":"11:00","endsAt":"12:30","roomId":1}],
         "schoolClass":{"id":3,"validFrom":"2026-09-21","validUntil":"2026-09-25"}}
        """;
    jdbc.update(
        "INSERT INTO block_assignment (id, block_plan_id, starts_on, ends_on)"
            + " VALUES (9, 1, '2026-09-28', '2026-10-02')");
    jdbc.update(
        "INSERT INTO class_block_assignment (school_class_id, block_assignment_id) VALUES (3, 9)");
    write(path, admin, body.replace("2026-09-25", "2026-09-20"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TIMETABLE_VALID_FROM"));
    write(path, admin, body).andExpect(status().isOk()).andExpect(jsonPath("$.classId").value(3));

    mvc.perform(get("/api/planning/classes/3/week?date=2026-09-21").session(teacher))
        .andExpect(jsonPath("$[0].lessons[*].startsAt", contains("11:00:00")));
    mvc.perform(get("/api/planning/classes/3/week?date=2026-09-28").session(teacher))
        .andExpect(jsonPath("$[0].lessons[*].startsAt", contains("07:30:00", "09:15:00")));
    mvc.perform(get("/api/planning/classes/1/week?date=2026-09-14").session(teacher))
        .andExpect(jsonPath("$[0].lessons[*].startsAt", contains("07:30:00", "09:15:00")));

    // Eine zweite befristete Zuordnung ersetzt die erste im überlappenden Zeitraum
    write(path, admin, body.replace("11:00", "13:00").replace("12:30", "14:00"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/planning/classes/3/week?date=2026-09-21").session(teacher))
        .andExpect(jsonPath("$[0].lessons[*].startsAt", contains("13:00:00")));
    mvc.perform(get("/api/planning/classes/3/week?date=2026-09-28").session(teacher))
        .andExpect(jsonPath("$[0].lessons[*].startsAt", contains("07:30:00", "09:15:00")));

    // Der Schüler ist seit 08:00 Uhr anwesend; im befristeten Plan fehlt er daher nicht
    setTime("2026-09-22T12:30:00Z");
    accounting.closeDueDays();
    assertThat(account(8)).isZero();
    setTime("2026-09-28T09:00:00Z");
    accounting.closeDueDays();
    assertThat(account(8)).isEqualTo(180);
  }

  private int account(long student) {
    return jdbc.queryForObject(
        "SELECT unexcused_minutes_account FROM student WHERE id = ?", Integer.class, student);
  }

  private Long createdIdOk(ResultActions result) throws Exception {
    var body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
  }

  /** Prüft das Bild, säubert die gelesenen Einträge und ordnet Lehrkräfte und Räume zu */
  @Test
  void readsATimetableDraftFromAnImageWithoutSavingAnything() throws Exception {
    var path = "/api/assistant/timetable-drafts";
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    jdbc.update(
        "INSERT INTO staff (id, first_name, last_name, username, password_hash, role) VALUES"
            + " (10, '', 'Elborg', 'elborg', 'x', 'LEHRKRAFT'),"
            + " (11, '', 'Elborg Foerster', 'elborg.f', 'x', 'LEHRKRAFT'),"
            + " (12, 'Mia', 'Sommerfeld', 'sommerfeld', 'x', 'LEHRKRAFT')");
    jdbc.update("INSERT INTO room (id, room_number) VALUES (10, 'A 213')");
    int timetablesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM timetable", Integer.class);

    upload(path, teacher, png)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ADMINISTRATOR_REQUIRED"));
    upload(path, admin, png).andExpect(status().isServiceUnavailable());
    when(codex.enabled()).thenReturn(true);
    upload(path, admin, new byte[0])
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TIMETABLE_IMAGE"));
    upload(path, admin, "GIF89a-kein-erlaubtes-Format".getBytes(StandardCharsets.UTF_8))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TIMETABLE_IMAGE"));
    byte[] large = new byte[8 * 1024 * 1024 + 1];
    System.arraycopy(png, 0, large, 0, png.length);
    upload(path, admin, large)
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("TIMETABLE_IMAGE_TOO_LARGE"));
    verify(codex, never()).run(any());

    when(codex.run(any()))
        .thenReturn(
            """
            {"recognized":true,"name":"10/11/12BE13 · Stundenplan (aus Bild, 48. KW 2026)",
             "className":"26 test-a","weekStartsOn":"2026-11-23",
             "slots":[
              {"weekday":"MONTAG","startsAt":"7:30","endsAt":"09:00","subject":"LF",
               "teacherName":"Sommer","roomNumber":"A213"},
              {"weekday":"DIENSTAG","startsAt":"09:15","endsAt":"12:30","subject":"SPO",
               "teacherName":"Elborg","roomNumber":"Sport 1"},
              {"weekday":"MITTWOCH","startsAt":"07:30","endsAt":"09:00","subject":null,
               "teacherName":"Elborg Fc","roomNumber":null},
              {"weekday":"DONNERSTAG","startsAt":"07:30","endsAt":"09:00","subject":"X",
               "teacherName":"Elb","roomNumber":"TEST-R01"},
              {"weekday":"SAMSTAG","startsAt":"07:30","endsAt":"09:00","subject":"X",
               "teacherName":null,"roomNumber":null},
              {"weekday":"FREITAG","startsAt":"10:00","endsAt":"09:00","subject":"X",
               "teacherName":null,"roomNumber":null}],
             "notes":["Do 14:45: Fach nur teilweise lesbar (CISCO-A…)"]}
            """);
    upload(path, admin, png)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("10/11/12BE13 · Stundenplan (aus Bild, 48. KW 2026)"))
        .andExpect(jsonPath("$.slots.length()").value(4))
        .andExpect(jsonPath("$.classCode").value("26 test-a"))
        .andExpect(jsonPath("$.classId").value(1))
        .andExpect(jsonPath("$.weekStartsOn").value("2026-11-23"))
        .andExpect(jsonPath("$.classes[0].blocks[0].startsOn").value("2026-09-14"))
        .andExpect(jsonPath("$.classes[0].blocks[0].endsOn").value("2026-09-18"))
        .andExpect(jsonPath("$.classes[*].classCode", contains("26TEST-A", "26TEST-B", "26TEST-C")))
        .andExpect(jsonPath("$.blockPlans[0].id").value(1))
        .andExpect(jsonPath("$.blockPlans[0].startsOn").value("2026-09-01"))
        .andExpect(jsonPath("$.slots[0].startsAt").value("07:30"))
        .andExpect(jsonPath("$.slots[0].teacherId").value(12))
        .andExpect(jsonPath("$.slots[0].roomId").value(10))
        .andExpect(jsonPath("$.slots[1].teacherId").value(10))
        .andExpect(jsonPath("$.slots[1].roomId").isEmpty())
        .andExpect(jsonPath("$.slots[1].endsAt").value("12:30"))
        .andExpect(jsonPath("$.slots[2].teacherId").value(11))
        .andExpect(jsonPath("$.slots[3].teacherId").isEmpty())
        .andExpect(jsonPath("$.slots[3].roomId").value(1))
        .andExpect(jsonPath("$.notes", hasItem("Do 14:45: Fach nur teilweise lesbar (CISCO-A…)")))
        .andExpect(jsonPath("$.notes", hasItem(containsString("„Elb“ ist nicht eindeutig"))))
        .andExpect(jsonPath("$.notes", hasItem(containsString("Raum „Sport 1“"))))
        .andExpect(jsonPath("$.notes", hasItem(containsString("2 Einträge"))))
        .andExpect(jsonPath("$.rooms[*].roomNumber", hasItem("A 213")))
        .andExpect(jsonPath("$.teachers[*].name", hasItem("Sommerfeld, Mia")))
        .andExpect(jsonPath("$.teachers[*].name", hasItem("Elborg")));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timetable", Integer.class))
        .isEqualTo(timetablesBefore);

    when(codex.run(any()))
        .thenReturn(
            "{\"recognized\":false,\"name\":\"\",\"className\":null,\"slots\":[],\"notes\":[]}");
    upload(path, admin, png)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("TIMETABLE_NOT_RECOGNIZED"));
    when(codex.run(any())).thenThrow(new IllegalStateException("ASSISTANT_BUSY"));
    upload(path, admin, png).andExpect(status().isTooManyRequests());
  }

  private ResultActions upload(String path, MockHttpSession session, byte[] image)
      throws Exception {
    String token = "test-csrf-token";
    return mvc.perform(
        multipart(path)
            .file(new MockMultipartFile("image", "plan.png", "image/png", image))
            .session(session)
            .cookie(new Cookie("XSRF-TOKEN", token))
            .header("X-XSRF-TOKEN", token));
  }

  @Test
  void changesAndRemovesSlotsFromTheNextWeekWithoutRewritingAttendance() throws Exception {
    Long blockId =
        createdId(
            write(
                "/api/planning/block-plans/1/blocks",
                admin,
                """
        {"startsOn":"2026-10-05","endsOn":"2026-10-16"}
        """));
    write("/api/planning/classes/1/blocks", admin, "{\"blockId\":" + blockId + "}")
        .andExpect(status().isNoContent());

    setTime("2026-10-05T05:45:00Z");
    write(
            put("/api/planning/timetables/1/slots/1"),
            admin,
            """
        {"weekday":"DIENSTAG","startsAt":"08:00","endsAt":"09:00","roomId":2}
        """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.effectiveOn").value("2026-10-13"));

    setTime("2026-10-05T08:00:00Z");
    write(delete("/api/planning/timetables/1/slots/2"), admin, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.effectiveOn").value("2026-10-12"));
    mvc.perform(get("/api/planning").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timetables[0].slots[*].weekday", contains("DIENSTAG")))
        .andExpect(jsonPath("$.timetables[0].slots[0].validFrom").value("2026-10-13"));

    setTime("2026-10-13T09:00:00Z");
    assertThat(scan(9001, "2026-10-05T05:45:00Z")).isEqualTo(ScanResult.SUCCESS);
    assertThat(scan(9001, "2026-10-12T05:45:00Z")).isEqualTo(ScanResult.REJECTED);
    assertThat(scan(9001, "2026-10-13T06:15:00Z")).isEqualTo(ScanResult.REJECTED);
    assertThat(scan(9002, "2026-10-13T06:15:00Z")).isEqualTo(ScanResult.SUCCESS);
    accounting.closeDueDays();
    assertThat(
            jdbc.queryForList(
                """
        SELECT unexcused_minutes_delta FROM attendance_audit h
        JOIN attendance a ON a.id = h.attendance_id
        WHERE h.student_id = 1 AND event_type = 'DAILY_CLOSE' ORDER BY a.attendance_date
        """,
                Integer.class))
        .containsExactly(180, 180, 60);
  }

  @Test
  void permitsAnUnstartedSlotChangeThisWeekAndRejectsUnauthorizedOrConflictingChanges()
      throws Exception {
    String slot = "/api/planning/timetables/1/slots/1";
    String change =
        """
        {"weekday":"MONTAG","startsAt":"08:00","endsAt":"09:00","roomId":1}
        """;
    setTime("2026-09-14T05:00:00Z");
    write(put(slot), teacher, change).andExpect(status().isForbidden());
    write(delete(slot), teacher, "").andExpect(status().isForbidden());
    write(delete("/api/planning/timetables/2/slots/1"), admin, "").andExpect(status().isNotFound());
    write(put(slot), admin, change.replace("09:00", "09:30"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OVERLAPPING_TIMETABLE_SLOT"));
    write(put(slot), admin, change.replace("\"roomId\":1", "\"roomId\":2"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ROOM_ALREADY_ASSIGNED"));
    write(put(slot), admin, change)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.effectiveOn").value("2026-09-14"));
  }

  @Test
  void cancelsTheUpcomingPredecessorWhenDeletingARescheduledSlot() throws Exception {
    jdbc.update("UPDATE block_assignment SET starts_on = '2026-09-07' WHERE id = 1");
    jdbc.update("UPDATE timetable_slot SET weekday = 'FREITAG' WHERE id = 1");
    setTime("2026-09-17T08:00:00Z");
    String slots = "/api/planning/timetables/1/slots/";
    long slotId = 1;
    for (String end : List.of("09:00", "08:45")) {
      write(
              put(slots + slotId),
              admin,
              """
              {"weekday":"MONTAG","startsAt":"08:00","endsAt":"%s","roomId":1}
              """
                  .formatted(end))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.effectiveOn").value("2026-09-21"));
      slotId =
          jdbc.queryForObject(
              "SELECT id FROM timetable_slot WHERE timetable_id = 1"
                  + " AND valid_from = '2026-09-21' AND valid_until IS NULL",
              Long.class);
    }
    write(delete(slots + slotId), admin, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.effectiveOn").value("2026-09-18"));
    mvc.perform(get("/api/planning").session(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timetables[0].slots[*].id", contains(2)));

    setTime("2026-09-18T06:15:00Z");
    assertThat(scan(9001, "2026-09-11T06:00:00Z")).isEqualTo(ScanResult.SUCCESS);
    assertThat(scan(9001, "2026-09-18T06:00:00Z")).isEqualTo(ScanResult.REJECTED);
  }

  /** Sendet einen Testscan für einen Schüler ohne Buchung in den betroffenen Wochen */
  private ScanResult scan(int terminal, String at) {
    return accounting.recordScan(
        "CN=terminal-" + terminal,
        new RawScanDTO(UUID.randomUUID(), "TEST-UID-006", terminal, Instant.parse(at)),
        clock.instant());
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
    return write(post(url), session, body);
  }

  /** Führt eine schreibende Anfrage mit Sitzung und CSRF-Token aus */
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

  /** Setzt den aktuellen Zeitpunkt der Testuhr */
  private void setTime(String instant) {
    when(clock.instant()).thenReturn(Instant.parse(instant));
  }
}
