package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
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

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
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

  private static final String EXCUSAL_PREVIEW = "/api/assistant/excusals/preview";

  private static final String EXCUSAL =
      """
      {"classId":1,"studentId":1,"date":"2026-09-14","startsAt":"08:15","endsAt":"09:00"}
      """;

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

  @Autowired SessionRegistry sessionRegistry;

  @Autowired PasswordEncoder passwordEncoder;

  @MockitoBean Clock clock;

  /** Prüft Klassenrechte und Entschuldigung ohne Verlust des späteren Check-ins */
  @Test
  void readsAndExcusesAnAbsenceInTheStudentWeek() throws Exception {
    var teacher = staffSession("test.teacher.a");
    var otherTeacher = staffSession("test.teacher.b");
    var week = "/api/students/1/attendance/week?date=2026-09-15";
    var excuse = "/api/students/1/attendance/2026-09-14/open-absence-excuses";
    assertThat(scan(1, "2026-09-14T07:35:00Z")).isTrue();
    setTime("2026-09-15T07:00:00Z");

    mvc.perform(get(week).session(otherTeacher)).andExpect(status().isNotFound());
    write(post(excuse), otherTeacher, "{}").andExpect(status().isNotFound());
    mvc.perform(get(week).session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.startsOn").value("2026-09-14"))
        .andExpect(jsonPath("$.endsOn").value("2026-09-20"))
        .andExpect(jsonPath("$.days[0].date").value("2026-09-14"))
        .andExpect(jsonPath("$.days[0].lateMinutes").value(110))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").value(110))
        .andExpect(jsonPath("$.days[0].excusedMinutes").value(0))
        .andExpect(jsonPath("$.days[0].absenceLevel").value("PARTIAL"))
        .andExpect(jsonPath("$.days[0].canExcuseOpenAbsences").value(true));
    write(post(excuse), teacher, "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    write(post(excuse), teacher, "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(false));

    mvc.perform(get(week).session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days[0].lateMinutes").value(110))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").value(0))
        .andExpect(jsonPath("$.days[0].excusedMinutes").value(110))
        .andExpect(jsonPath("$.days[0].absenceLevel").isEmpty())
        .andExpect(jsonPath("$.days[0].canExcuseOpenAbsences").value(false));
    assertAccount(1, 0, 110);
    assertThat(
            jdbc.queryForMap(
                "SELECT event_type, changed_by_staff_id, deletion_date_id FROM attendance_audit"
                    + " WHERE student_id = 1 AND event_type = 'STATUS_CHANGE'"))
        .containsEntry("changed_by_staff_id", 1L)
        .containsEntry("deletion_date_id", 1L);
  }

  /** Prüft Wochenhistorie ohne Ankunft und die Korrektursperre nach einem Kontenreset */
  @Test
  void keepsAnAllDayAbsenceVisibleAfterResetAndPlanChange() throws Exception {
    var teacher = staffSession("test.teacher.a");
    var admin = staffSession("test.admin");
    var week = "/api/students/1/attendance/week?date=2026-09-14";
    var excuse = "/api/students/1/attendance/2026-09-14/open-absence-excuses";
    setTime("2026-09-14T09:00:00Z");
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").value(180))
        .andExpect(jsonPath("$.days[0].canCorrectStatus").value(true))
        .andExpect(jsonPath("$.days[0].canPlanStatus").value(false));
    setTime("2026-09-15T07:00:00Z");
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].didNotArrive").value(true))
        .andExpect(jsonPath("$.days[0].lateMinutes").isEmpty())
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").value(180))
        .andExpect(jsonPath("$.days[0].absenceLevel").value("FULL"));
    write(post("/api/students/2/attendance/2026-09-14/open-absence-excuses"), teacher, "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    assertAccount(2, 0, 180);
    assertThat(
            jdbc.queryForObject("SELECT status FROM attendance WHERE student_id = 2", String.class))
        .isEqualTo("ENTSCHULDIGT");
    var third = "/api/students/3/attendance/2026-09-14/";
    write(
            post(third + "status-periods"),
            teacher,
            """
        {"status":"BETRIEBLICH_ENTSCHULDIGT","startsAt":"09:30","endsAt":"10:00"}
        """)
        .andExpect(status().isOk());
    write(post(third + "open-absence-excuses"), teacher, "{}").andExpect(status().isOk());
    assertAccount(3, 0, 180);
    assertThat(
            jdbc.queryForList(
                """
        SELECT new_status FROM attendance_audit WHERE student_id = 3
        AND new_status IS NOT NULL ORDER BY occurred_at, id
        """,
                String.class))
        .containsExactly("ENTSCHULDIGT", "BETRIEBLICH_ENTSCHULDIGT", "ABWESEND", "ENTSCHULDIGT");
    write(post(RESET), admin, "{\"expectedBlockPlanId\":1}").andExpect(status().isOk());
    write(post(excuse), teacher, "{}").andExpect(status().isConflict());
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].canExcuseOpenAbsences").value(false));
    jdbc.update(
        "INSERT INTO block_plan (id, name, starts_on, ends_on)"
            + " VALUES (999, 'Folgeplan', '2027-01-01', '2027-06-30')");
    jdbc.update("DELETE FROM class_block_assignment WHERE school_class_id = 1");
    jdbc.update("UPDATE school_class SET block_plan_id = 999 WHERE id = 1");
    setTime("2027-01-01T00:00:00Z");
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].date").value("2026-09-14"))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").value(180))
        .andExpect(jsonPath("$.days[0].absenceLevel").isEmpty())
        .andExpect(jsonPath("$.days[0].lateMinutes").isEmpty());
    setTime("2027-06-29T22:00:00Z");
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").isEmpty());
  }

  /** Prüft Tagesbuchung, Klassenrechte, erneute Zustellung und anschließende Slotkorrektur */
  @Test
  void manuallyAddsUnexcusedMinutesWithoutChangingTheTimeline() throws Exception {
    var assignedTeacher = staffSession("test.teacher.b");
    var admin = staffSession("test.admin");
    var week = "/api/students/1/attendance/week?date=2026-09-15";
    var base = "/api/students/1/attendance/2026-09-14/";
    var request =
        """
        {"minutes":15,"requestId":"9e2c9c26-87da-46dc-96f2-18897ef16c30","note":"Vermerk"}
        """;
    var period =
        """
        {"status":"BETRIEBLICH_ENTSCHULDIGT","startsAt":"09:30","endsAt":"11:00"}
        """;
    assertThat(scan(1, "2026-09-14T05:30:00Z")).isTrue();
    setTime("2026-09-15T07:00:00Z");
    mvc.perform(get("/api/students").session(assignedTeacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == 1)]").isEmpty());
    write(post(base + "manual-absence-minutes"), assignedTeacher, request)
        .andExpect(status().isNotFound());
    write(post(base + "status-periods"), assignedTeacher, period).andExpect(status().isNotFound());

    jdbc.update("INSERT INTO teacher_class (staff_id, school_class_id) VALUES (2, 1)");
    mvc.perform(get("/api/students").session(assignedTeacher))
        .andExpect(jsonPath("$[?(@.id == 1)]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.classId == 2)]").isNotEmpty());

    write(post(base + "manual-absence-minutes"), assignedTeacher, request.replace("15,", "15.5,"))
        .andExpect(status().isBadRequest());
    write(post(base + "manual-absence-minutes"), assignedTeacher, request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    write(post(base + "manual-absence-minutes"), assignedTeacher, request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(false));
    write(post(base + "manual-absence-minutes"), assignedTeacher, request.replace("15,", "16,"))
        .andExpect(status().isConflict());
    assertAccount(1, 15, 0);
    mvc.perform(get(week).session(assignedTeacher))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").value(15))
        .andExpect(jsonPath("$.days[0].didNotArrive").value(false))
        .andExpect(jsonPath("$.days[0].manualAbsenceMinutes").value(15))
        .andExpect(jsonPath("$.days[0].manualAbsenceLimit").value(165))
        .andExpect(jsonPath("$.days[0].editable").value(true));
    assertThat(
            jdbc.queryForMap(
                """
        SELECT changed_by_staff_id, deletion_date_id, old_status, new_status,
               unexcused_minutes_delta FROM attendance_audit
        WHERE student_id = 1 AND event_type = 'MANUAL_ABSENCE_MINUTES'
        """))
        .containsEntry("changed_by_staff_id", 2L)
        .containsEntry("deletion_date_id", 1L)
        .containsEntry("old_status", null)
        .containsEntry("new_status", null)
        .containsEntry("unexcused_minutes_delta", 15);

    write(post(base + "status-periods"), assignedTeacher, period).andExpect(status().isOk());
    mvc.perform(get(week).session(assignedTeacher))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").value(15))
        .andExpect(jsonPath("$.days[0].excusedMinutes").value(75))
        .andExpect(jsonPath("$.days[0].manualAbsenceLimit").value(90))
        .andExpect(jsonPath("$.days[0].absenceLevel").value("PARTIAL"));
    assertAccount(1, 15, 75);
    assertThat(
            jdbc.queryForObject("SELECT status FROM attendance WHERE student_id = 1", String.class))
        .isEqualTo("ANWESEND");

    accounting.closeDueDays();
    assertAccount(1, 15, 75);
    write(post(base + "open-absence-excuses"), assignedTeacher, "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(false));
    assertAccount(1, 15, 75);
    mvc.perform(get(week).session(assignedTeacher))
        .andExpect(jsonPath("$.days[0].manualAbsenceMinutes").value(15))
        .andExpect(jsonPath("$.days[0].canExcuseOpenAbsences").value(false));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE student_id = 1"
                    + " AND new_status = 'BETRIEBLICH_ENTSCHULDIGT'",
                Long.class))
        .isEqualTo(1L);
    write(post(RESET), admin, "{\"expectedBlockPlanId\":1}").andExpect(status().isOk());
    write(post(base + "manual-absence-minutes"), assignedTeacher, request)
        .andExpect(status().isConflict());
    write(post(base + "status-periods"), assignedTeacher, period).andExpect(status().isConflict());
  }

  /**
   * Überschreibt einen Scan nur innerhalb des Zeitraums und stellt seinen Status danach wieder her
   */
  @Test
  void appliesAStatusPeriodAcrossAnExistingScanAndKeepsTheRestOfTheDay() throws Exception {
    var admin = staffSession("test.admin");
    assertThat(scan(1, "2026-09-14T07:20:00Z")).isTrue();
    setTime("2026-09-15T07:00:00Z");
    var endpoint = "/api/students/1/attendance/2026-09-14/status-periods";
    var request =
        """
        {"status":"BETRIEBLICH_ENTSCHULDIGT","startsAt":"08:45","endsAt":"09:45"}
        """;
    write(post(endpoint), admin, request.replace("09:45", "08:15"))
        .andExpect(status().isBadRequest());
    write(post(endpoint), admin, request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    write(post(endpoint), admin, request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(false));
    assertAccount(1, 75, 45);
    assertThat(
            jdbc.queryForList(
                """
        SELECT new_status FROM attendance_audit
        WHERE student_id = 1 AND event_type = 'STATUS_CHANGE'
        ORDER BY occurred_at, id
        """,
                String.class))
        .containsExactly("BETRIEBLICH_ENTSCHULDIGT", "BETRIEBLICH_ENTSCHULDIGT", "ANWESEND");
    assertThat(
            jdbc.queryForObject("SELECT status FROM attendance WHERE student_id = 1", String.class))
        .isEqualTo("ANWESEND");
  }

  /** Prüft die zwei Kalenderwochen, Klassenrechte und die Buchung erst nach der Vormerkung */
  @Test
  void plansAnExcusedPeriodForAnUpcomingWeekWithoutBookingFutureMinutes() throws Exception {
    var teacher = staffSession("test.teacher.a");
    var other = staffSession("test.teacher.b");
    var path = "/api/students/8/attendance/2026-09-21/status-periods";
    var week = "/api/students/8/attendance/week?date=";
    var period =
        """
        {"status":"ENTSCHULDIGT","startsAt":"07:30","endsAt":"08:00"}
        """;
    setTime("2026-09-13T22:30:00Z");
    mvc.perform(get(week + "2026-10-04").session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.endsOn").value("2026-10-04"));
    mvc.perform(get(week + "2026-10-05").session(teacher)).andExpect(status().isBadRequest());
    mvc.perform(get(week + "2026-09-21").session(other)).andExpect(status().isNotFound());
    write(post(path), other, period).andExpect(status().isNotFound());
    write(post(path), teacher, period.replace("ENTSCHULDIGT", "ANWESEND"))
        .andExpect(status().isBadRequest());
    write(post(path.replace("09-21", "10-05")), teacher, period).andExpect(status().isBadRequest());

    write(post(path), teacher, period)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    write(post(path), teacher, period)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(false));
    write(post(path), teacher, period.replace("07:30", "07:45")).andExpect(status().isConflict());
    mvc.perform(get(week + "2026-09-21").session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days[0].canPlanStatus").value(true))
        .andExpect(jsonPath("$.days[0].plannedStatuses[0].status").value("ENTSCHULDIGT"))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").isEmpty())
        .andExpect(jsonPath("$.days[0].editable").value(false));
    assertAccount(8, 0, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE student_id = 8", Long.class))
        .isZero();
    assertThat(
            jdbc.queryForMap(
                "SELECT changed_by_staff_id, deletion_date_id FROM planned_attendance_status"))
        .containsEntry("changed_by_staff_id", 1L)
        .containsEntry("deletion_date_id", 1L);

    setTime("2026-09-21T05:50:00Z");
    assertThat(scan(8, "2026-09-21T05:50:00Z")).isFalse();
    mvc.perform(get("/api/classes/3/attendance?date=2026-09-21").session(teacher))
        .andExpect(jsonPath("$[0].status").value("ENTSCHULDIGT"))
        .andExpect(jsonPath("$[0].unexcusedMinutes").value(0));
    setTime("2026-09-22T07:00:00Z");
    accounting.closeDueDays();
    accounting.closeDueDays();
    assertAccount(8, 150, 30);
    mvc.perform(get(week + "2026-09-21").session(teacher))
        .andExpect(jsonPath("$.days[0].excusedMinutes").value(30))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").value(150));
    setTime("2027-06-29T22:30:00Z");
    retention.deleteHistory();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM planned_attendance_status", Long.class))
        .isZero();
  }

  /** Ein Check-in im betrieblich entschuldigten Zeitraum bleibt nach dessen Ende wirksam */
  @Test
  void acceptsACheckInDuringABusinessExcuseAndKeepsTheArrival() throws Exception {
    setTime("2026-09-14T08:00:00Z");
    var teacher = staffSession("test.teacher.a");
    write(
            post("/api/students/8/attendance/2026-09-21/status-periods"),
            teacher,
            """
        {"status":"BETRIEBLICH_ENTSCHULDIGT","startsAt":"07:30","endsAt":"09:00"}
        """)
        .andExpect(status().isOk());
    setTime("2026-09-21T05:50:00Z");
    assertThat(scan(8, "2026-09-21T05:50:00Z")).isTrue();
    setTime("2026-09-22T07:00:00Z");
    accounting.closeDueDays();
    assertAccount(8, 0, 20);
    mvc.perform(get("/api/students/8/attendance/week?date=2026-09-21").session(teacher))
        .andExpect(jsonPath("$.days[0].didNotArrive").value(false))
        .andExpect(jsonPath("$.days[0].lateMinutes").value(20))
        .andExpect(jsonPath("$.days[0].excusedMinutes").value(20));
  }

  /** Unterscheidet vergangene und geplante Zeiträume des aktuellen Berliner Tags */
  @Test
  void correctsAnElapsedPeriodTodayAndAllowsALaterExcuse() throws Exception {
    var teacher = staffSession("test.teacher.a");
    var endpoint = "/api/students/1/attendance/2026-09-14/status-periods";
    var week = "/api/students/1/attendance/week?date=2026-09-14";
    setTime("2026-09-14T06:15:00Z");
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].canCorrectStatus").value(true))
        .andExpect(jsonPath("$.days[0].canPlanStatus").value(true))
        .andExpect(jsonPath("$.days[0].editable").value(false));
    write(
            post(endpoint),
            teacher,
            """
        {"status":"ENTSCHULDIGT","startsAt":"08:00","endsAt":"08:30"}
        """)
        .andExpect(status().isBadRequest());
    write(
            post(endpoint),
            teacher,
            """
        {"status":"ENTSCHULDIGT","startsAt":"07:30","endsAt":"08:00"}
        """)
        .andExpect(status().isOk());
    assertAccount(1, 15, 30);
    write(
            post(endpoint),
            teacher,
            """
        {"status":"BETRIEBLICH_ENTSCHULDIGT","startsAt":"09:30","endsAt":"10:00"}
        """)
        .andExpect(status().isOk());
    assertAccount(1, 15, 30);

    setTime("2026-09-14T09:00:00Z");
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").value(120))
        .andExpect(jsonPath("$.days[0].excusedMinutes").value(60))
        .andExpect(jsonPath("$.days[0].canCorrectStatus").value(true))
        .andExpect(jsonPath("$.days[0].canPlanStatus").value(false));
    write(
            post(endpoint),
            teacher,
            """
        {"status":"ENTSCHULDIGT","startsAt":"10:00","endsAt":"10:15"}
        """)
        .andExpect(status().isOk());
    assertAccount(1, 105, 75);
    write(post(RESET), staffSession("test.admin"), "{\"expectedBlockPlanId\":1}")
        .andExpect(status().isOk());
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].canCorrectStatus").value(false));
  }

  /** Die Ganztagskorrektur zählt beide Unterrichtsslots und erhält manuelle Buchungen */
  @Test
  void excusesAWholeCompletedSchoolDayWithoutCountingTheBreak() throws Exception {
    var teacher = staffSession("test.teacher.a");
    var other = staffSession("test.teacher.b");
    var path = "/api/students/1/attendance/2026-09-14/school-day-excuses";
    var week = "/api/students/1/attendance/week?date=2026-09-14";
    assertThat(scan(1, "2026-09-14T05:30:00Z")).isTrue();
    setTime("2026-09-15T07:00:00Z");
    write(post(path), other, "{}").andExpect(status().isNotFound());
    write(post(path.replace("09-14", "09-19")), teacher, "{}").andExpect(status().isConflict());
    var manual =
        """
        {"minutes":15,"requestId":"9e2c9c26-87da-46dc-96f2-18897ef16c30","note":"Vermerk"}
        """;
    write(post(path.replace("school-day-excuses", "manual-absence-minutes")), teacher, manual)
        .andExpect(status().isOk());
    write(post(path), teacher, "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    write(post(path), teacher, "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(false));
    assertAccount(1, 15, 180);
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].didNotArrive").value(false))
        .andExpect(jsonPath("$.days[0].excusedMinutes").value(180))
        .andExpect(jsonPath("$.days[0].manualAbsenceMinutes").value(15));
    write(post(RESET), staffSession("test.admin"), "{\"expectedBlockPlanId\":1}")
        .andExpect(status().isOk());
    write(post(path), teacher, "{}").andExpect(status().isConflict());
  }

  /** Rechnet bei laufendem Unterricht erst die verstrichenen Minuten ab */
  @Test
  void excusesAnOngoingSchoolDayWithoutBookingRemainingMinutesEarly() throws Exception {
    setTime("2026-09-14T06:15:00Z");
    var teacher = staffSession("test.teacher.a");
    var path = "/api/students/1/attendance/2026-09-14/school-day-excuses";
    assertThat(scan(1, "2026-09-14T05:30:00Z")).isTrue();
    write(post(path), teacher, "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    assertAccount(1, 0, 45);
    setTime("2026-09-15T07:00:00Z");
    accounting.closeDueDays();
    assertAccount(1, 0, 180);
  }

  /** Ermittelt die Zeiten einer Ganztagsvormerkung aus dem Stundenplan */
  @Test
  void plansAWholeUpcomingSchoolDayUsingTheFirstAndLastSlot() throws Exception {
    setTime("2026-09-14T08:00:00Z");
    var teacher = staffSession("test.teacher.a");
    var path = "/api/students/8/attendance/2026-09-21/school-day-excuses";
    var week = "/api/students/8/attendance/week?date=2026-09-21";
    write(post(path), teacher, "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].plannedStatuses[0].startsAt").value("07:30:00"))
        .andExpect(jsonPath("$.days[0].plannedStatuses[0].endsAt").value("10:45:00"))
        .andExpect(jsonPath("$.days[0].plannedStatuses[0].status").value("ENTSCHULDIGT"));
    assertAccount(8, 0, 0);
    setTime("2026-09-22T07:00:00Z");
    accounting.closeDueDays();
    assertAccount(8, 0, 180);
  }

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
            "planned_attendance_status",
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
        .andExpect(jsonPath("$[?(@.studentId == 1)].status").value("ABWESEND"))
        .andExpect(jsonPath("$[?(@.studentId == 1)].unexcusedMinutes").value(20))
        .andExpect(jsonPath("$[?(@.studentId == 2)].status").value("ANWESEND"))
        .andExpect(jsonPath("$[?(@.studentId == 2)].unexcusedMinutes").value(20))
        .andExpect(jsonPath("$[?(@.studentId == 1)].birthDate").value("2008-01-01"))
        .andExpect(jsonPath("$[?(@.studentId == 1)].rfidUid").doesNotExist());

    setTime("2026-09-14T06:00:00Z");
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(jsonPath("$[?(@.studentId == 1)].unexcusedMinutes").value(30))
        .andExpect(jsonPath("$[?(@.studentId == 2)].unexcusedMinutes").value(20));
    assertAccount(1, 0, 0);
    write(post(CORRECTION), teacher, EXCUSE)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(jsonPath("$[?(@.studentId == 1)].status").value("ENTSCHULDIGT"))
        .andExpect(jsonPath("$[?(@.studentId == 1)].unexcusedMinutes").value(15));
    mvc.perform(get("/api/students/1").session(teacher))
        .andExpect(jsonPath("$.unexcusedMinutes").value(15))
        .andExpect(jsonPath("$.excusedMinutes").value(15));
    mvc.perform(get("/api/classes/1/attendance?date=2026-09-12").session(teacher))
        .andExpect(content().json("[]"));
    mvc.perform(get("/api/classes/1/attendance?date=2026-09-15").session(teacher))
        .andExpect(status().isBadRequest());
  }

  /** Prüft Bestätigung, Wiederholung, nachgelieferte Scans und überlappende Zeitfenster */
  @Test
  void boundedExcusalKeepsLateAttendanceAndCountsOverlapOnce() throws Exception {
    var teacher = staffSession("test.teacher.a");
    String confirmation = previewExcusal(teacher, EXCUSAL, 45);
    assertAccount(1, 0, 0);
    write(post(confirmation), teacher, "{}").andExpect(status().isOk());
    write(post(confirmation), teacher, "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.excusedAdded").value(45));
    assertAccount(1, 135, 45);
    assertThat(jdbc.queryForMap("SELECT * FROM attendance_audit WHERE student_id = 1"))
        .containsEntry("event_type", "TIMED_EXCUSAL")
        .containsEntry("changed_by_staff_id", 1L)
        .containsEntry("new_status", null);
    assertThat(scan(1, "2026-09-14T06:40:00Z")).isTrue();
    assertAccount(1, 45, 25);
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.studentId == 1)].status").value("ANWESEND"))
        .andExpect(jsonPath("$[?(@.studentId == 1)].unexcusedMinutes").value(45));

    String overlap =
        previewExcusal(teacher, EXCUSAL.replace("08:15", "08:00").replace("09:00", "08:30"), 15);
    write(post(overlap), teacher, "{}").andExpect(status().isOk());
    assertAccount(1, 30, 40);
  }

  /** Prüft Sitzungsbindung, Klassenrechte und eine Änderung zwischen Vorschau und Buchung */
  @Test
  void boundedExcusalRequiresAccessibleClassAndCurrentPreview() throws Exception {
    var teacher = staffSession("test.teacher.a");
    var other = staffSession("test.teacher.b");
    write(post(EXCUSAL_PREVIEW), other, EXCUSAL).andExpect(status().isNotFound());
    String confirmation = previewExcusal(teacher, EXCUSAL, 45);
    write(post(confirmation), other, "{}").andExpect(status().isConflict());
    mvc.perform(post(confirmation).session(teacher)).andExpect(status().isForbidden());
    assertThat(scan(1, "2026-09-14T06:40:00Z")).isTrue();
    write(post(confirmation), teacher, "{}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EXCUSAL_PREVIEW_CHANGED"));
    assertAccount(1, 70, 0);
    String expires = previewExcusal(teacher, EXCUSAL, 25);
    setTime("2026-09-14T08:51:00Z");
    write(post(expires), teacher, "{}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EXCUSAL_PREVIEW_EXPIRED"));
    assertAccount(1, 70, 0);
  }

  @Test
  void distinguishesWholeDaysFromPartialHoursAndProtectsRecordedPresence() throws Exception {
    addTuesdayLesson();
    var teacher = staffSession("test.teacher.a");
    var profile = "/api/students/1";
    var monday = profile + "/attendance/2026-09-14/";
    var tuesday = profile + "/attendance/2026-09-15/";
    var absent =
        """
        {"status":"ABWESEND","occurredAt":"2026-09-14T06:20:00Z"}
        """;
    assertThat(scan(1, "2026-09-14T05:30:00Z")).isTrue();
    write(post(CORRECTION), teacher, absent)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ATTENDANCE_ALREADY_PRESENT"));
    write(
            post(monday + "status-periods"),
            teacher,
            """
        {"status":"ABWESEND","startsAt":"08:00","endsAt":"08:30"}
        """)
        .andExpect(status().isConflict());
    write(
            post(monday + "manual-absence-minutes"),
            teacher,
            """
        {"minutes":35,"requestId":"84f153a6-8088-431d-9bd6-6a4274e7b319","note":"Vermerk"}
        """)
        .andExpect(status().isOk());
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(jsonPath("$[?(@.studentId == 1)].status").value("ANWESEND"))
        .andExpect(jsonPath("$[?(@.studentId == 1)].wasPresent").value(true))
        .andExpect(jsonPath("$[?(@.studentId == 1)].unexcusedMinutes").value(35));
    mvc.perform(get(profile).session(teacher))
        .andExpect(jsonPath("$.unexcusedDays").value(0))
        .andExpect(jsonPath("$.unexcusedHours").value(0));
    setTime("2026-09-15T08:00:00Z");
    accounting.closeDueDays();
    mvc.perform(get(profile).session(teacher))
        .andExpect(jsonPath("$.unexcusedDays").value(1))
        .andExpect(jsonPath("$.unexcusedHours").value(0));
    assertThat(scan(1, "2026-09-15T05:30:00Z")).isTrue();
    write(
            post(tuesday + "manual-absence-minutes"),
            teacher,
            """
        {"minutes":20,"requestId":"03a4cb24-5e5e-4260-95b9-995a0f7b2be6","note":"Vermerk"}
        """)
        .andExpect(status().isOk());
    mvc.perform(get(profile).session(teacher))
        .andExpect(jsonPath("$.unexcusedMinutes").value(55))
        .andExpect(jsonPath("$.unexcusedDays").value(0))
        .andExpect(jsonPath("$.unexcusedHours").value(1));
    write(post(RESET), staffSession("test.admin"), "{\"expectedBlockPlanId\":1}")
        .andExpect(status().isOk());
    mvc.perform(get(profile).session(teacher))
        .andExpect(jsonPath("$.unexcusedDays").value(0))
        .andExpect(jsonPath("$.unexcusedHours").value(0));
  }

  @Test
  void previewDoesNotWaitForClassWriteLock() throws Exception {
    addTuesdayLesson();
    setTime("2026-09-16T08:00:00Z");
    var teacher = staffSession("test.teacher.a");
    var request =
        """
        {"classId":1,"studentId":1,"date":"2026-09-14","endDate":"2026-09-16",
         "status":"MIT_ATTEST_ENTSCHULDIGT"}
        """;
    var locked = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    try (var workers = Executors.newFixedThreadPool(2)) {
      var holder =
          workers.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          tx -> {
                            jdbc.queryForObject(
                                "SELECT id FROM school_class WHERE id = 1 FOR UPDATE", Long.class);
                            jdbc.queryForObject(
                                "SELECT id FROM deletion_date WHERE id = 1 FOR UPDATE", Long.class);
                            locked.complete(null);
                            release.join();
                            return null;
                          }));
      try {
        locked.get(10, TimeUnit.SECONDS);
        var preview = workers.submit(() -> previewExcusal(teacher, request, 225));
        preview.get(10, TimeUnit.SECONDS);
        release.complete(null);
        holder.get(10, TimeUnit.SECONDS);
      } finally {
        release.complete(null);
      }
    }
  }

  @Test
  void confirmsMultipleCompletedSchoolDaysAtomicallyAndKeepsTheirReason() throws Exception {
    addTuesdayLesson();
    setTime("2026-09-16T08:00:00Z");
    var teacher = staffSession("test.teacher.a");
    var request =
        """
        {"classId":1,"studentId":1,"date":"2026-09-14","endDate":"2026-09-16",
         "status":"MIT_ATTEST_ENTSCHULDIGT","reason":"Attest liegt vor"}
        """;
    write(
            post(EXCUSAL_PREVIEW),
            teacher,
            request.replace("MIT_ATTEST_ENTSCHULDIGT", "ENTSCHULDIGT"))
        .andExpect(status().isBadRequest());
    var confirmation = previewExcusal(teacher, request, 225);
    assertAccount(1, 0, 0);
    write(post(confirmation), teacher, "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days.length()").value(2))
        .andExpect(jsonPath("$.days[0].before").value("ABWESEND"))
        .andExpect(jsonPath("$.days[1].after").value("ENTSCHULDIGT"));
    assertAccount(1, 0, 225);
    mvc.perform(get("/api/students/1").session(teacher))
        .andExpect(jsonPath("$.excusedDays").value(2))
        .andExpect(jsonPath("$.excusedHours").value(0));
    mvc.perform(get("/api/students/1/attendance/week?date=2026-09-14").session(teacher))
        .andExpect(jsonPath("$.days[0].excusals[0].reason").value("Attest liegt vor"))
        .andExpect(jsonPath("$.days[0].fullAbsence").value("ENTSCHULDIGT"));
    accounting.closeDueDays();
    assertAccount(1, 0, 225);
  }

  @Test
  void schedulesAWholeSchoolDayAndRecalculatesAfterALateScan() throws Exception {
    addTuesdayLesson();
    var teacher = staffSession("test.teacher.a");
    var request =
        """
        {"classId":1,"studentId":1,"date":"2026-09-15","reason":"Krankmeldung"}
        """;
    var confirmation = previewExcusal(teacher, request, 45);
    write(post(confirmation), teacher, "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days[0].pending").value(true));
    assertAccount(1, 0, 0);
    setTime("2026-09-15T08:00:00Z");
    accounting.closeDueDays();
    assertAccount(1, 180, 45);
    mvc.perform(get("/api/students/1").session(teacher))
        .andExpect(jsonPath("$.excusedDays").value(1));
    assertThat(scan(1, "2026-09-15T05:40:00Z")).isTrue();
    assertAccount(1, 180, 10);
    mvc.perform(get("/api/students/1").session(teacher))
        .andExpect(jsonPath("$.excusedDays").value(0))
        .andExpect(jsonPath("$.excusedHours").value(0));
  }

  private void addTuesdayLesson() {
    jdbc.update(
        """
        INSERT INTO timetable_slot (timetable_id, room_id, weekday, start_time, end_time)
        VALUES (1, 1, 'DIENSTAG', '07:30:00', '08:15:00')
        """);
  }

  /** Erstellt eine Vorschau mit dem erwarteten Effekt und gibt die Bestätigungsroute zurück */
  private String previewExcusal(MockHttpSession session, String body, int minutes)
      throws Exception {
    var response =
        write(post(EXCUSAL_PREVIEW), session, body)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.impact.unexcusedRemoved").value(minutes))
            .andExpect(jsonPath("$.impact.excusedAdded").value(minutes))
            .andReturn()
            .getResponse();
    String id = JsonPath.read(response.getContentAsString(), "$.id");
    return "/api/assistant/excusals/" + id + "/confirm";
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
        .andExpect(jsonPath(entry + ".roomNumber", contains("A104")))
        .andExpect(jsonPath("$[?(@.roomNumber=='TEST-R01')].terminalId", contains(9001)));
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
    String hash =
        jdbc.queryForObject("SELECT password_hash FROM staff WHERE id = ?", String.class, id);
    write(put(item), admin, names).andExpect(status().isNoContent());
    write(put(item), admin, names.replace("}", ",\"username\":\"anders\"}"))
        .andExpect(status().isBadRequest());
    write(put(item), admin, names.replace("}", ",\"password\":\"\"}"))
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject("SELECT password_hash FROM staff WHERE id = ?", String.class, id))
        .isEqualTo(hash);
    write(put(item), admin, names.replace("}", ",\"password\":\"" + "x".repeat(73) + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TEACHER_PASSWORD"));

    // Ein neues Passwort gilt sofort und beendet bestehende Sitzungen der Lehrkraft
    var principal = new StaffPrincipal(id, "test.crud", "unused", List.of());
    sessionRegistry.registerNewSession("crud-session", principal);
    write(put(item), admin, names.replace("}", ",\"password\":\"Neues-Passwort\"}"))
        .andExpect(status().isNoContent());
    assertThat(
            passwordEncoder.matches(
                "Neues-Passwort",
                jdbc.queryForObject(
                    "SELECT password_hash FROM staff WHERE id = ?", String.class, id)))
        .isTrue();
    assertThat(sessionRegistry.getSessionInformation("crud-session").isExpired()).isTrue();
    sessionRegistry.removeSessionInformation("crud-session");
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

  /** Eine Lehrkraft ohne Vornamen erscheint in Auswahllisten nur mit dem Nachnamen */
  @Test
  void teachersMayHaveNoFirstName() throws Exception {
    var admin = staffSession("test.admin");
    write(
            post("/api/teachers"),
            admin,
            """
            {"lastName":"Ohnevorname","username":"test.ohne","password":"Fixture-Passwort"}
            """)
        .andExpect(status().isCreated());
    long id = jdbc.queryForObject("SELECT id FROM staff WHERE username = 'test.ohne'", Long.class);
    write(put("/api/teachers/" + id), admin, "{\"firstName\":null,\"lastName\":\"Neu\"}")
        .andExpect(status().isNoContent());
    write(put("/api/teachers/" + id), admin, "{\"firstName\":\"\",\"lastName\":\" \"}")
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/planning").session(admin))
        .andExpect(jsonPath("$.teachers[?(@.id == " + id + ")].name", contains("Neu")));
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

  /** Prüft, dass Lehrkräfte nur Kartenzuordnungen ihrer zugänglichen Klassen lesen */
  @Test
  @ExtendWith(RfidLoggingExtension.class)
  void teacherReadsRfidAssignmentsOnlyForAccessibleClasses(CapturedOutput output) throws Exception {
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
        .andExpect(jsonPath("$.length()").value(6))
        .andExpect(jsonPath("$[*].classCode", everyItem(is("26TEST-A"))))
        .andExpect(jsonPath("$[0].studentName").value("Bereits-Anwesend, Test"))
        .andExpect(jsonPath("$[*].rfidUid", not(hasItem("TEST-UID-007"))));
    mvc.perform(get(endpoint).session(staffSession("test.teacher.b")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(
            jsonPath("$")
                .value(
                    hasItem(
                        Map.of(
                            "studentName", "Raum-B, Test",
                            "classCode", "26TEST-B",
                            "rfidUid", "TEST-UID-007"))));
    jdbc.update("INSERT INTO teacher_class (staff_id, school_class_id) VALUES (1, 2)");
    mvc.perform(get(endpoint).session(teacher)).andExpect(jsonPath("$.length()").value(7));
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
        .andExpect(jsonPath("$[?(@.studentId == 1)].unexcusedMinutes").value(180));

    jdbc.update(
        "INSERT INTO block_plan (id, name, starts_on, ends_on) VALUES (999, 'Folgeplan', '2027-01-01', '2027-06-30')");
    jdbc.update("DELETE FROM class_block_assignment WHERE school_class_id = 1");
    jdbc.update("UPDATE school_class SET block_plan_id = 999 WHERE id = 1");
    setTime("2027-01-01T00:00:00Z");
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(jsonPath("$[?(@.studentId == 1)].unexcusedMinutes").value(180));
    setTime("2027-06-29T22:00:00Z");
    mvc.perform(get(CLASS_DAY).session(teacher)).andExpect(content().json("[]"));
  }

  /** Sortiert Schüler nach Nachname und Vorname sowie Klassen nach Klassenkürzel */
  @Test
  void sortsStudentsByNameAndClassesByCode() throws Exception {
    var teacher = staffSession("test.teacher.a");
    mvc.perform(get("/api/classes/1/students").session(teacher))
        .andExpect(
            jsonPath(
                "$[*].lastName",
                contains(
                    "Bereits-Anwesend",
                    "Ohne-Scan",
                    "Retry",
                    "Statuswechsel",
                    "Teilentschuldigung",
                    "Verspaetet")));
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(jsonPath("$[*].studentId", contains(5, 1, 6, 3, 4, 2)))
        .andExpect(jsonPath("$[0].birthDate").value("2008-01-01"));
    jdbc.update("UPDATE school_class SET class_code = '26TEST-Z' WHERE id = 1");
    mvc.perform(get("/api/classes").session(teacher))
        .andExpect(jsonPath("$[*].id", contains(3, 1)));
  }

  /**
   * Begrenzt händische und berechnete Fehlminuten zusammen auf den Unterrichtstag und nimmt die
   * händischen auditiert zurück
   */
  @Test
  void limitsManualAbsenceMinutesToTheSchoolDayAndRemovesThemAgain() throws Exception {
    var teacher = staffSession("test.teacher.a");
    var path = "/api/students/1/attendance/2026-09-14/manual-absence-minutes";
    var week = "/api/students/1/attendance/week?date=2026-09-14";
    assertThat(scan(1, "2026-09-14T06:30:00Z")).isTrue();
    setTime("2026-09-15T07:00:00Z");
    accounting.closeDueDays();
    assertAccount(1, 60, 0);
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].manualAbsenceLimit").value(120));

    write(post(path), teacher, manualMinutes(121))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MANUAL_ABSENCE_EXCEEDS_SCHOOL_DAY"));
    for (String note : List.of("", ",\"note\":\"  \"", ",\"note\":\"" + "x".repeat(201) + "\"")) {
      write(post(path), teacher, manualMinutes(10).replace(",\"note\":\"Vermerk\"", note))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_MANUAL_ABSENCE_NOTE"));
    }
    write(post(path), teacher, manualMinutes(100)).andExpect(status().isOk());
    write(post(path), teacher, manualMinutes(21))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MANUAL_ABSENCE_EXCEEDS_SCHOOL_DAY"));
    write(post(path), teacher, manualMinutes(20)).andExpect(status().isOk());
    assertAccount(1, 180, 0);
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].plannedMinutes").value(180))
        .andExpect(jsonPath("$.days[0].manualAbsenceMinutes").value(120))
        .andExpect(jsonPath("$.days[0].manualAbsenceLimit").value(0))
        .andExpect(jsonPath("$.days[0].manualAbsenceEntries.length()").value(2))
        .andExpect(jsonPath("$.days[0].manualAbsenceEntries[0].minutes").value(100))
        .andExpect(jsonPath("$.days[0].manualAbsenceEntries[0].note").value("Vermerk"))
        .andExpect(jsonPath("$.days[5].manualAbsenceEntries").isEmpty())
        .andExpect(jsonPath("$.days[5].plannedMinutes").value(0));
    mvc.perform(get("/api/students/1").session(teacher))
        .andExpect(jsonPath("$.unexcusedPartialMinutes").value(180))
        .andExpect(jsonPath("$.unexcusedDays").value(0));

    write(delete(path), staffSession("test.teacher.b"), "").andExpect(status().isNotFound());
    write(delete(path), teacher, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    write(delete(path), teacher, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(false));
    assertAccount(1, 60, 0);
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].manualAbsenceMinutes").value(0))
        .andExpect(jsonPath("$.days[0].manualAbsenceEntries").isEmpty())
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").value(60));
    assertThat(
            jdbc.queryForList(
                """
                SELECT unexcused_minutes_delta FROM attendance_audit
                WHERE student_id = 1 AND event_type = 'MANUAL_ABSENCE_MINUTES' ORDER BY id
                """,
                Integer.class))
        .containsExactly(100, 20, -120);
    write(post(path), teacher, manualMinutes(120)).andExpect(status().isOk());
    assertAccount(1, 180, 0);
  }

  /** Ein ganztägig entschuldigter Tag zeigt in der Live-Anwesenheit „Entschuldigt“ */
  @Test
  void showsAnExcusedSchoolDayAsExcusedInTheClassDay() throws Exception {
    var teacher = staffSession("test.teacher.a");
    var student = "$[?(@.studentId == 1)]";
    setTime("2026-09-14T09:00:00Z");
    write(post("/api/students/1/attendance/2026-09-14/school-day-excuses"), teacher, "{}")
        .andExpect(jsonPath("$.changed").value(true));
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(jsonPath(student + ".status").value("ENTSCHULDIGT"))
        .andExpect(jsonPath(student + ".fullAbsence").value("ENTSCHULDIGT"))
        .andExpect(jsonPath(student + ".excusedMinutes").value(180))
        .andExpect(jsonPath("$[?(@.studentId == 3)].status").value("ABWESEND"));
  }

  /** Eine Buchung am selben Tag und der spätere Tagesabschluss zählen den Fehltag einmal */
  @Test
  void closingAfterASameDayBookingCountsTheFullDayOnce() throws Exception {
    var teacher = staffSession("test.teacher.a");
    setTime("2026-09-14T09:00:00Z");
    write(
            post(CORRECTION),
            teacher,
            """
            {"status":"ABWESEND","occurredAt":"2026-09-14T05:30:00Z"}
            """)
        .andExpect(jsonPath("$.changed").value(true));
    assertAccount(1, 180, 0);
    assertThat(fullDayAccount()).containsExactly(1, 180);
    setTime("2026-09-15T07:00:00Z");
    accounting.closeDueDays();
    accounting.closeDueDays();
    assertAccount(1, 180, 0);
    assertThat(fullDayAccount()).containsExactly(1, 180);
  }

  /**
   * Nach der letzten Stunde sind Entschuldigung und händische Minuten schon am selben Tag möglich
   */
  @Test
  void editsTheCurrentDayOnceItsLessonsHaveEnded() throws Exception {
    var teacher = staffSession("test.teacher.a");
    var day = "/api/students/2/attendance/2026-09-14/";
    var week = "/api/students/2/attendance/week?date=2026-09-14";
    setTime("2026-09-14T07:25:00Z");
    assertThat(scan(2, "2026-09-14T07:20:00Z")).isTrue();
    setTime("2026-09-14T08:30:00Z");
    write(post(day + "open-absence-excuses"), teacher, "{}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_ATTENDANCE_DATE"));
    mvc.perform(get(week).session(teacher)).andExpect(jsonPath("$.days[0].editable").value(false));

    setTime("2026-09-14T09:00:00Z");
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].editable").value(true))
        .andExpect(jsonPath("$.days[0].canExcuseOpenAbsences").value(true));
    write(post(day + "open-absence-excuses"), teacher, "{}")
        .andExpect(jsonPath("$.changed").value(true));
    write(post(day + "manual-absence-minutes"), teacher, manualMinutes(10))
        .andExpect(status().isOk());
    assertAccount(2, 10, 95);
    setTime("2026-09-15T07:00:00Z");
    accounting.closeDueDays();
    assertAccount(2, 10, 95);
  }

  /** Eine spätere Abwesenheit lässt eine frühere händische Anwesenheit bestehen */
  @Test
  void laterAbsenceKeepsAnEarlierManualPresence() throws Exception {
    var teacher = staffSession("test.teacher.a");
    setTime("2026-09-15T07:00:00Z");
    write(
            post(CORRECTION),
            teacher,
            """
            {"status":"ANWESEND","occurredAt":"2026-09-14T06:00:00Z"}
            """)
        .andExpect(status().isOk());
    assertAccount(1, 30, 0);
    write(
            post(CORRECTION),
            teacher,
            """
            {"status":"ABWESEND","occurredAt":"2026-09-14T08:00:00Z"}
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    assertAccount(1, 75, 0);
    mvc.perform(get("/api/students/1/attendance/week?date=2026-09-14").session(teacher))
        .andExpect(jsonPath("$.days[0].wasPresent").value(true))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").value(75))
        .andExpect(jsonPath("$.days[0].fullAbsence").isEmpty());
  }

  private static String manualMinutes(int minutes) {
    return "{\"minutes\":"
        + minutes
        + ",\"requestId\":\""
        + UUID.randomUUID()
        + "\",\"note\":\"Vermerk\"}";
  }

  private List<Integer> fullDayAccount() {
    var row =
        jdbc.queryForMap(
            "SELECT unexcused_days_account, full_unexcused_minutes_account FROM student WHERE id = 1");
    return List.of(
        (Integer) row.get("unexcused_days_account"),
        (Integer) row.get("full_unexcused_minutes_account"));
  }

  /** Nimmt eine versehentlich händisch gesetzte Anwesenheit zurück, einen Scan dagegen nicht */
  @Test
  void retractsAManualPresenceButKeepsAScannedOne() throws Exception {
    var teacher = staffSession("test.teacher.a");
    var week = "/api/students/1/attendance/week?date=2026-09-14";
    var student = "$[?(@.studentId == 1)]";
    setTime("2026-09-14T05:00:00Z");
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").isEmpty())
        .andExpect(jsonPath("$.days[0].interim").value(false))
        .andExpect(jsonPath("$.days[0].plannedMinutes").value(180));

    setTime("2026-09-14T06:15:00Z");
    write(
            post(CORRECTION),
            teacher,
            """
            {"status":"ANWESEND","occurredAt":"2026-09-14T06:10:00Z"}
            """)
        .andExpect(status().isOk());
    assertAccount(1, 40, 0);
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(jsonPath(student + ".status").value("ANWESEND"))
        .andExpect(jsonPath(student + ".wasPresent").value(true))
        .andExpect(jsonPath(student + ".canMarkAbsent").value(true));
    write(
            post(CORRECTION),
            teacher,
            """
            {"status":"ABWESEND","occurredAt":"2026-09-14T06:10:00Z"}
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    assertAccount(1, 45, 0);
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(jsonPath(student + ".status").value("ABWESEND"))
        .andExpect(jsonPath(student + ".wasPresent").value(false))
        .andExpect(jsonPath(student + ".unexcusedMinutes").value(45))
        .andExpect(jsonPath(student + ".excusedMinutes").value(0))
        .andExpect(jsonPath(student + ".canMarkAbsent").value(true));
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].interim").value(true))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").value(45))
        .andExpect(jsonPath("$.days[0].absenceLevel").value("PARTIAL"))
        .andExpect(jsonPath("$.days[0].fullAbsence").isEmpty())
        .andExpect(jsonPath("$.days[0].wasPresent").value(false))
        .andExpect(jsonPath("$.days[0].didNotArrive").value(true))
        .andExpect(jsonPath("$.days[0].canMarkAbsent").value(true))
        .andExpect(jsonPath("$.days[0].canPlanStatus").value(true))
        .andExpect(jsonPath("$.days[0].editable").value(false));

    assertThat(scan(2, "2026-09-14T05:50:00Z")).isTrue();
    write(
            post("/api/students/2/attendance/2026-09-14/status-corrections"),
            teacher,
            """
            {"status":"ABWESEND","occurredAt":"2026-09-14T06:12:00Z"}
            """)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ATTENDANCE_ALREADY_PRESENT"));
    mvc.perform(get(CLASS_DAY).session(teacher))
        .andExpect(jsonPath("$[?(@.studentId == 2)].canMarkAbsent").value(false));
    mvc.perform(get("/api/students/2/attendance/week?date=2026-09-14").session(teacher))
        .andExpect(jsonPath("$.days[0].canMarkAbsent").value(false))
        .andExpect(jsonPath("$.days[0].interim").value(true))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").value(20));

    setTime("2026-09-15T07:00:00Z");
    accounting.closeDueDays();
    assertAccount(1, 180, 0);
    assertThat(fullDayAccount()).containsExactly(1, 180);
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].interim").value(false))
        .andExpect(jsonPath("$.days[0].absenceLevel").value("FULL"))
        .andExpect(jsonPath("$.days[0].fullAbsence").value("ABWESEND"));
  }

  /** Lehnt ein Geburtsdatum nach dem heutigen Berliner Tag beim Anlegen und Ändern ab */
  @Test
  void rejectsBirthDatesInTheFuture() throws Exception {
    var admin = staffSession("test.admin");
    var future = STUDENT.replace("2008-02-03", "2026-09-15");
    write(post("/api/classes/1/students"), admin, future)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BIRTH_DATE_IN_FUTURE"));
    write(put("/api/students/1"), admin, future)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BIRTH_DATE_IN_FUTURE"));
    write(post("/api/classes/1/students"), admin, STUDENT.replace("2008-02-03", "2026-09-14"))
        .andExpect(status().isCreated());
  }

  /** Liefert Unterrichts- und Fehltage des Schülerprofils für einen begrenzten Zeitraum */
  @Test
  void readsLessonAndAbsenceDaysForTheStudentCalendar() throws Exception {
    var teacher = staffSession("test.teacher.a");
    var calendar = "/api/students/%d/attendance/calendar?from=%s&to=%s";
    assertThat(scan(2, "2026-09-14T05:30:00Z")).isTrue();

    mvc.perform(
            get(calendar.formatted(1, "2026-09-12", "2026-09-22"))
                .session(staffSession("test.teacher.b")))
        .andExpect(status().isNotFound());
    mvc.perform(get(calendar.formatted(1, "2026-09-12", "2026-09-22")).session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(11))
        .andExpect(jsonPath("$[0].date").value("2026-09-12"))
        .andExpect(jsonPath("$[0].lessonDay").value(false))
        .andExpect(jsonPath("$[0].absent").value(false))
        .andExpect(jsonPath("$[2].date").value("2026-09-14"))
        .andExpect(jsonPath("$[2].lessonDay").value(true))
        .andExpect(jsonPath("$[2].absent").value(true))
        .andExpect(jsonPath("$[9].lessonDay").value(false));
    mvc.perform(get(calendar.formatted(2, "2026-09-14", "2026-09-14")).session(teacher))
        .andExpect(jsonPath("$[0].lessonDay").value(true))
        .andExpect(jsonPath("$[0].absent").value(false));
    mvc.perform(get(calendar.formatted(8, "2026-09-21", "2026-09-21")).session(teacher))
        .andExpect(jsonPath("$[0].lessonDay").value(true))
        .andExpect(jsonPath("$[0].absent").value(false));
    mvc.perform(get(calendar.formatted(1, "2026-09-01", "2026-12-02")).session(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(93));
    mvc.perform(get(calendar.formatted(1, "2026-09-01", "2026-12-03")).session(teacher))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_ATTENDANCE_DATE"));
    mvc.perform(get(calendar.formatted(1, "2026-09-15", "2026-09-14")).session(teacher))
        .andExpect(status().isBadRequest());
  }

  /** Ein künftiger Ausfall nimmt die Stunde aus der Abrechnung und lehnt Scans neutral ab */
  @Test
  void upcomingLessonCancellationBooksNoAbsenceMinutes() throws Exception {
    var teacher = staffSession("test.teacher.a");
    var path = "/api/planning/classes/3/lesson-cancellations";
    var body =
        """
        {"date":"2026-09-21","startsAt":"07:30"}
        """;
    write(post(path), teacher, body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    write(post(path), teacher, body).andExpect(jsonPath("$.changed").value(false));
    mvc.perform(get("/api/planning/classes/3/week?date=2026-09-21").session(teacher))
        .andExpect(jsonPath("$[0].lessons.length()").value(2))
        .andExpect(jsonPath("$[0].lessons[0].cancelled").value(true))
        .andExpect(jsonPath("$[0].lessons[1].cancelled").value(false));

    setTime("2026-09-21T06:00:00Z");
    mvc.perform(get("/api/students/8/attendance/week?date=2026-09-21").session(teacher))
        .andExpect(jsonPath("$.days[0].plannedMinutes").value(90));
    setTime("2026-09-22T07:00:00Z");
    accounting.closeDueDays();
    assertAccount(8, 90, 0);
  }

  /** Ein Scan während einer ausgefallenen Stunde gilt im Raum der nächsten Stunde für diese */
  @Test
  void scanDuringACancelledLessonCountsForTheNextLesson() throws Exception {
    var teacher = staffSession("test.teacher.a");
    write(
            post("/api/planning/classes/3/lesson-cancellations"),
            teacher,
            "{\"date\":\"2026-09-21\",\"startsAt\":\"07:30\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.manualAbsences").isEmpty());
    setTime("2026-09-21T06:10:00Z");
    assertThat(scanAt(8, 9002, "2026-09-21T06:00:00Z")).isFalse();
    assertThat(scan(8, "2026-09-21T06:00:00Z")).isTrue();
    setTime("2026-09-22T07:00:00Z");
    accounting.closeDueDays();
    assertAccount(8, 0, 0);
  }

  /**
   * Rechnet einen abgeschlossenen Tag beim Ausfall und bei dessen Rücknahme neu ab. Händische
   * Fehlminuten bleiben stehen und lassen sich auch nach einem Ausfall des ganzen Tages entfernen
   */
  @Test
  void retroactiveLessonCancellationRecalculatesTheClassDayInBothDirections() throws Exception {
    var teacher = staffSession("test.teacher.a");
    var path = "/api/planning/classes/1/lesson-cancellations";
    var manual = "/api/students/2/attendance/2026-09-14/manual-absence-minutes";
    var week = "/api/students/1/attendance/week?date=2026-09-14";
    assertThat(scan(2, "2026-09-14T07:20:00Z")).isTrue();
    setTime("2026-09-15T07:00:00Z");
    accounting.closeDueDays();
    write(post(manual), teacher, manualMinutes(15)).andExpect(status().isOk());
    assertAccount(1, 180, 0);
    assertAccount(2, 110, 0);
    assertThat(fullDayAccount()).containsExactly(1, 180);

    write(post(path), staffSession("test.teacher.b"), "{\"date\":\"2026-09-14\"}")
        .andExpect(status().isNotFound());
    for (String invalid :
        List.of(
            "{\"date\":\"2026-09-14\",\"startsAt\":\"08:00\"}",
            "{\"date\":\"2026-09-13\"}",
            "{\"date\":\"2026-09-21\",\"startsAt\":\"07:30\"}")) {
      write(post(path), teacher, invalid)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_LESSON_CANCELLATION"));
    }
    assertAccount(1, 180, 0);

    write(post(path), teacher, "{\"date\":\"2026-09-14\",\"startsAt\":\"09:15:00\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true))
        .andExpect(jsonPath("$.manualAbsences.length()").value(1))
        .andExpect(jsonPath("$.manualAbsences[0].studentId").value(2))
        .andExpect(jsonPath("$.manualAbsences[0].lastName").value("Verspaetet"))
        .andExpect(jsonPath("$.manualAbsences[0].date").value("2026-09-14"))
        .andExpect(jsonPath("$.manualAbsences[0].minutes").value(15));
    assertAccount(1, 90, 0);
    assertAccount(2, 105, 0);
    assertThat(fullDayAccount()).containsExactly(1, 90);
    assertThat(
            jdbc.queryForMap(
                """
                SELECT changed_by_staff_id, unexcused_minutes_delta FROM attendance_audit
                WHERE student_id = 1 AND event_type = 'LESSON_CANCELLATION'
                """))
        .containsEntry("changed_by_staff_id", 1L)
        .containsEntry("unexcused_minutes_delta", -90);
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].plannedMinutes").value(90))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").value(90));

    write(delete(path + "?date=2026-09-14&startsAt=09:15"), teacher, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    write(delete(path + "?date=2026-09-14&startsAt=09:15"), teacher, "")
        .andExpect(jsonPath("$.changed").value(false));
    assertAccount(1, 180, 0);
    assertAccount(2, 110, 0);
    assertThat(fullDayAccount()).containsExactly(1, 180);

    write(post(path), teacher, "{\"date\":\"2026-09-14\"}")
        .andExpect(jsonPath("$.changed").value(true));
    assertAccount(1, 0, 0);
    assertAccount(2, 15, 0);
    assertThat(fullDayAccount()).containsExactly(0, 0);
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$.days[0].plannedMinutes").value(0))
        .andExpect(jsonPath("$.days[0].fullAbsence").isEmpty());
    mvc.perform(
            get("/api/students/2/attendance/calendar?from=2026-09-14&to=2026-09-14")
                .session(teacher))
        .andExpect(jsonPath("$[0].lessonDay").value(false))
        .andExpect(jsonPath("$[0].absent").value(true));
    accounting.closeDueDays();
    assertAccount(2, 15, 0);
    write(delete(manual), teacher, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    assertAccount(2, 0, 0);

    write(delete(path + "?date=2026-09-14"), teacher, "")
        .andExpect(jsonPath("$.changed").value(true));
    assertAccount(1, 180, 0);
    assertAccount(2, 95, 0);
    assertThat(fullDayAccount()).containsExactly(1, 180);

    write(post(RESET), staffSession("test.admin"), "{\"expectedBlockPlanId\":1}")
        .andExpect(status().isOk());
    write(post(path), teacher, "{\"date\":\"2026-09-14\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ATTENDANCE_DAY_LOCKED_OR_UNPLANNED"));
  }

  /** Holt bei der Rücknahme eines ausgefallenen Schultags die fehlende Buchung sofort nach */
  @Test
  void restoringAFullyCancelledPastDayBooksMissingAttendanceExactlyOnce() throws Exception {
    var teacher = staffSession("test.teacher.a");
    var path = "/api/planning/classes/3/lesson-cancellations";
    write(post(path), teacher, "{\"date\":\"2026-09-21\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));

    setTime("2026-09-22T07:00:00Z");
    accounting.closeDueDays();
    assertAccount(8, 0, 0);
    var attendanceCount = "SELECT COUNT(*) FROM attendance WHERE student_id = 8";
    assertThat(jdbc.queryForObject(attendanceCount, Integer.class)).isZero();

    write(delete(path + "?date=2026-09-21"), teacher, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true));
    assertAccount(8, 180, 0);
    mvc.perform(get("/api/students/8").session(teacher))
        .andExpect(jsonPath("$.unexcusedDays").value(1))
        .andExpect(jsonPath("$.unexcusedHours").value(0));
    assertThat(
            jdbc.queryForMap(
                """
                SELECT changed_by_staff_id, unexcused_minutes_delta FROM attendance_audit
                WHERE student_id = 8 AND event_type = 'LESSON_CANCELLATION'
                """))
        .containsEntry("changed_by_staff_id", 1L)
        .containsEntry("unexcused_minutes_delta", 180);

    write(delete(path + "?date=2026-09-21"), teacher, "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(false));
    accounting.closeDueDays();
    assertAccount(8, 180, 0);
    assertThat(jdbc.queryForObject(attendanceCount, Integer.class)).isEqualTo(1);
  }

  /** Ein Ausfall gilt weiter, wenn die Stunde danach verschoben wird */
  @Test
  void cancellationFollowsALessonMovedWithinItsTimeSpan() throws Exception {
    var teacher = staffSession("test.teacher.a");
    var path = "/api/planning/classes/3/lesson-cancellations";
    var week = "/api/planning/classes/3/week?date=2026-09-21";
    write(post(path), teacher, "{\"date\":\"2026-09-21\",\"startsAt\":\"07:30\"}")
        .andExpect(jsonPath("$.changed").value(true));
    jdbc.update("UPDATE timetable_slot SET start_time = '07:45:00' WHERE id = 1");
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$[0].lessons[0].startsAt").value("07:45:00"))
        .andExpect(jsonPath("$[0].lessons[0].cancelled").value(true))
        .andExpect(jsonPath("$[0].lessons[1].cancelled").value(false));
    write(post(path), teacher, "{\"date\":\"2026-09-21\",\"startsAt\":\"07:45\"}")
        .andExpect(jsonPath("$.changed").value(false));
    setTime("2026-09-22T07:00:00Z");
    accounting.closeDueDays();
    assertAccount(8, 90, 0);

    write(delete(path + "?date=2026-09-21&startsAt=07:45"), teacher, "")
        .andExpect(jsonPath("$.changed").value(true));
    mvc.perform(get(week).session(teacher))
        .andExpect(jsonPath("$[0].lessons[0].cancelled").value(false));
    accounting.closeDueDays();
    assertAccount(8, 165, 0);
  }

  /** Ein Scan ab 07:30 Uhr vor der ersten Stunde gilt für diese Stunde und ihren Raum */
  @Test
  void earlyScanCountsForTheNextLessonInItsRoom() throws Exception {
    jdbc.update("UPDATE timetable_slot SET start_time = '07:45:00' WHERE id = 1");
    assertThat(scanAt(2, 9001, "2026-09-14T05:10:00Z")).isFalse();
    assertThat(scanAt(3, 9002, "2026-09-14T05:35:00Z")).isFalse();
    assertThat(scan(1, "2026-09-14T05:35:00Z")).isTrue();
    assertAccount(1, 0, 0);
    mvc.perform(
            get("/api/students/1/attendance/week?date=2026-09-14")
                .session(staffSession("test.teacher.a")))
        .andExpect(jsonPath("$.days[0].unexcusedMinutes").value(0))
        .andExpect(jsonPath("$.days[0].lateMinutes").value(0))
        .andExpect(jsonPath("$.days[0].wasPresent").value(true))
        .andExpect(jsonPath("$.days[0].canMarkAbsent").value(false));
    setTime("2026-09-15T07:00:00Z");
    accounting.closeDueDays();
    assertAccount(1, 0, 0);
    assertAccount(2, 165, 0);
    assertThat(fullDayAccount()).containsExactly(0, 0);
  }

  /** Ein Scan in der Pause gilt für die nächste Stunde; frühere Fehlminuten bleiben bestehen */
  @Test
  void scanInTheBreakCountsForTheNextLessonAndKeepsEarlierAbsences() throws Exception {
    var teacher = staffSession("test.teacher.a");
    assertThat(scan(2, "2026-09-14T07:05:00Z")).isTrue();
    setTime("2026-09-14T09:00:00Z");
    assertThat(scan(3, "2026-09-14T08:50:00Z")).isFalse();
    setTime("2026-09-15T07:00:00Z");
    accounting.closeDueDays();
    assertAccount(2, 90, 0);
    assertAccount(3, 180, 0);
    mvc.perform(get("/api/students/2").session(teacher))
        .andExpect(jsonPath("$.unexcusedPartialMinutes").value(90))
        .andExpect(jsonPath("$.unexcusedDays").value(0));
    mvc.perform(get("/api/students/2/attendance/week?date=2026-09-14").session(teacher))
        .andExpect(jsonPath("$.days[0].lateMinutes").value(90));
  }

  private boolean scanAt(long student, int terminal, String at) {
    String rfidUid =
        jdbc.queryForObject("SELECT rfid_uid FROM student WHERE id = ?", String.class, student);
    return accounting
        .recordScan(
            "CN=terminal-" + terminal,
            new RawScanDTO(UUID.randomUUID(), rfidUid, terminal, Instant.parse(at)),
            clock.instant())
        .equals(ScanResult.SUCCESS);
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
    "ANWESEND,ENTSCHULDIGT,0,180",
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
    assertThat(
            accounting.correctStatus(
                1L, MONDAY, AttendanceStatus.BETRIEB, Instant.parse("2026-09-14T05:30:30Z")))
        .isTrue();
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
    // Das Ende der letzten Stunde ist ausgeschlossen; eine Pause zählt dagegen für die Folgestunde
    assertThat(scan(1, "2026-09-14T08:45:00Z")).isFalse();
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
        .andExpect(jsonPath("$[?(@.studentId == 1)].status").value("ABWESEND"));
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
