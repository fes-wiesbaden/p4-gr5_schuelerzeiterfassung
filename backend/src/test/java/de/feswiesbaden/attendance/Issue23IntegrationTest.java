package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.feswiesbaden.attendance.login.StaffPrincipal;
import de.feswiesbaden.attendance.service.BlockPlanService;
import de.feswiesbaden.attendance.service.RfidUidService;
import de.feswiesbaden.attendance.service.SchoolClassService;
import de.feswiesbaden.attendance.service.StudentService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Prüft Rollen, Klassenrechte und Schülerverwaltung mit MySQL */
@SpringBootTest(properties = "attendance.retention.scheduling.enabled=false")
@Testcontainers
@Sql("/fixtures/attendance.sql")
@ExtendWith(OutputCaptureExtension.class)
class Issue23IntegrationTest {
  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @Autowired JdbcTemplate jdbc;

  @Autowired SchoolClassService classes;

  @Autowired StudentService students;

  @Autowired RfidUidService uids;

  @Autowired BlockPlanService plans;

  @Autowired ObjectMapper mapper;

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
   * Prüft die Klassensicht anhand von Klassenleitung, Unterrichtszuordnung und Administratorrolle
   */
  @Test
  void listsOnlyClassesAuthorizedByClassTeacherOrTeachingAssignment() {
    jdbc.update("DELETE FROM teacher_class WHERE staff_id = 1 AND school_class_id = 1");
    jdbc.update("INSERT INTO teacher_class (staff_id, school_class_id) VALUES (2, 3)");
    signIn("test.teacher.a");
    assertThat(classes.list()).extracting(SchoolClassService.ClassView::id).containsExactly(1L, 3L);
    signIn("test.teacher.b");
    assertThat(classes.list()).extracting(SchoolClassService.ClassView::id).containsExactly(2L, 3L);
    signIn("test.admin");
    assertThat(classes.list())
        .extracting(SchoolClassService.ClassView::id)
        .containsExactly(1L, 2L, 3L);
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

  /** Prüft die Schülerverwaltung durch die Klassenleitung ohne zusätzliche Unterrichtszuordnung */
  @Test
  void classTeacherManagesStudentNamesAndBirthDateWithoutTeachingAssignment() {
    jdbc.update("DELETE FROM teacher_class WHERE staff_id = 1");
    signIn("test.teacher.a");
    Long id = students.create(1L, " Neu ", " Schueler ", LocalDate.of(2008, 2, 3));
    assertThat(students.get(id))
        .isEqualTo(
            new StudentService.StudentView(
                id, "Neu", "Schueler", LocalDate.of(2008, 2, 3), 1L, 0, 0, 0, 0, 0, 0));
    assertThat(students.list(1L)).extracting(StudentService.StudentView::id).contains(id);
    assertThat(jdbc.queryForObject("SELECT rfid_uid FROM student WHERE id = ?", String.class, id))
        .isNull();
    students.update(id, "Geaendert", "Name", LocalDate.of(2008, 3, 4));
    assertThat(students.get(id).firstName()).isEqualTo("Geaendert");
    assertThat(students.get(id).lastName()).isEqualTo("Name");
    assertThat(students.get(id).birthDate()).isEqualTo(LocalDate.of(2008, 3, 4));
    students.delete(id);
    assertThat(students.list(1L)).extracting(StudentService.StudentView::id).doesNotContain(id);
  }

  /** Prüft Klassenanlage mit vorhandenen Plänen und serverseitig bestimmter Klassenleitung */
  @Test
  void createsClassesWithExistingPlansAndServerControlledClassTeacher() {
    signIn("test.teacher.b");
    Long own = classes.create(" Freier Code ", null, 1L, 1L);
    assertThat(classes.list())
        .contains(new SchoolClassService.ClassView(own, "Freier Code", 2L, 1L, 1L));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM teacher_class WHERE school_class_id = ?", Integer.class, own))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_block_assignment WHERE school_class_id = ?",
                Integer.class,
                own))
        .isZero();
    Long student = students.create(own, "Test", "Neu", LocalDate.of(2008, 1, 1));
    assertThat(students.get(student).classId()).isEqualTo(own);
    signIn("test.admin");
    Long other = classes.create("Admin-Klasse", 1L, 1L, 2L);
    assertThat(classes.list())
        .contains(new SchoolClassService.ClassView(other, "Admin-Klasse", 1L, 1L, 2L));
  }

  /** Prüft, dass die gespeicherte Rolle auch bei veralteten Sitzungsrechten maßgeblich bleibt */
  @Test
  void usesCurrentPersistedRoleInsteadOfStaleAdministratorAuthorities() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                new StaffPrincipal(2L, "test.teacher.b", "unused", List.of()),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR"))));
    assertThatThrownBy(() -> uids.remove(1L)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> plans.create("Verboten", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)))
        .isInstanceOf(AccessDeniedException.class);
    jdbc.update("UPDATE staff SET role = 'ADMINISTRATOR' WHERE id = 2");
    uids.remove(1L);
    assertThat(classes.list()).hasSize(3);
    jdbc.update("UPDATE staff SET role = 'LEHRKRAFT' WHERE id = 2");
    assertThat(classes.list()).extracting(SchoolClassService.ClassView::id).containsExactly(2L);
    assertThatThrownBy(() -> uids.remove(1L)).isInstanceOf(AccessDeniedException.class);
  }

  /** Prüft Leserechte zugeordneter Lehrkräfte ohne Rechte zur Schülerverwaltung */
  @Test
  void teachingTeacherReadsButCannotManageStudents() {
    jdbc.update("INSERT INTO teacher_class (staff_id, school_class_id) VALUES (2, 1)");
    signIn("test.teacher.b");
    var before = students.get(1L);
    assertThat(students.list(1L)).hasSize(6);
    assertThatThrownBy(() -> students.create(1L, "Test", "Verboten", LocalDate.of(2008, 1, 1)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> students.update(1L, "Test", "Verboten", LocalDate.of(2008, 1, 1)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> students.delete(1L)).isInstanceOf(AccessDeniedException.class);
    assertThat(students.get(1L)).isEqualTo(before);
    assertThat(students.list(1L)).hasSize(6);
  }

  /** Prüft die Ablehnung fremder Klassen und Schüler ohne Datenänderung */
  @Test
  void rejectsAccessToForeignClassAndStudents() {
    signIn("test.teacher.b");
    assertThatThrownBy(() -> students.list(1L)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> students.get(1L)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> students.create(1L, "Test", "Verboten", LocalDate.of(2008, 1, 1)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> students.update(1L, "Test", "Verboten", LocalDate.of(2008, 1, 1)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> students.delete(1L)).isInstanceOf(AccessDeniedException.class);
    signIn("test.admin");
    assertThat(students.get(1L).lastName()).isEqualTo("Ohne-Scan");
    assertThat(students.list(1L)).hasSize(6);
  }

  /**
   * Prüft, dass alle Verwaltungszugriffe einen authentifizierten gespeicherten Mitarbeiter
   * verlangen
   */
  @ParameterizedTest
  @ValueSource(strings = {"missing", "anonymous", "unauthenticated", "unknown"})
  void requiresAnAuthenticatedPersistedStaffMember(String principal) {
    switch (principal) {
      case "missing" -> SecurityContextHolder.clearContext();
      case "anonymous" ->
          SecurityContextHolder.getContext()
              .setAuthentication(
                  new AnonymousAuthenticationToken(
                      "test",
                      "test.admin",
                      List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRATOR"))));
      case "unauthenticated" ->
          SecurityContextHolder.getContext()
              .setAuthentication(
                  UsernamePasswordAuthenticationToken.unauthenticated("test.admin", null));
      case "unknown" ->
          SecurityContextHolder.getContext()
              .setAuthentication(
                  UsernamePasswordAuthenticationToken.authenticated(
                      new StaffPrincipal(-1L, "test.unknown", "unused", List.of()),
                      null,
                      List.of()));
      default -> throw new AssertionError(principal);
    }
    assertThatThrownBy(classes::list).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> classes.create("Verboten", 1L, 1L, 1L))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> students.list(1L)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> students.get(1L)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> students.create(1L, "Test", "Verboten", LocalDate.of(2008, 1, 1)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> students.update(1L, "Test", "Verboten", LocalDate.of(2008, 1, 1)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> students.delete(1L)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> uids.assign(1L, "04A3B2C1")).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> uids.remove(1L)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> plans.create("Verboten", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)))
        .isInstanceOf(AccessDeniedException.class);
  }

  /**
   * Prüft klassenübergreifende Schülerverwaltung bei unveränderter Karte, Klasse und Minutenkonten
   */
  @Test
  void administratorManagesStudentsAcrossClassesWithoutChangingUidClassOrAccounts() {
    signIn("test.admin");
    var before = students.get(7L);
    students.update(7L, "Neu", "Name", LocalDate.of(2008, 3, 4));
    assertThat(students.get(7L))
        .isEqualTo(
            new StudentService.StudentView(
                7L,
                "Neu",
                "Name",
                LocalDate.of(2008, 3, 4),
                2L,
                before.unexcusedMinutes(),
                before.excusedMinutes(),
                before.unexcusedDays(),
                before.excusedDays(),
                before.unexcusedHours(),
                before.excusedHours()));
    assertThat(jdbc.queryForObject("SELECT rfid_uid FROM student WHERE id = 7", String.class))
        .isEqualTo("TEST-UID-007");
    Long id = students.create(3L, "Test", "Neu", LocalDate.of(2008, 1, 1));
    assertThat(students.get(id).classId()).isEqualTo(3L);
    students.delete(id);
    assertThat(students.list(3L)).hasSize(1);
  }

  /**
   * Prüft die Ablehnung einer Schülerlöschung mit Anwesenheits- oder Audithistorie ohne
   * Datenverlust
   */
  @ParameterizedTest
  @ValueSource(strings = {"attendance", "audit"})
  void rejectsDeletionWithAttendanceOrAuditHistoryWithoutDataLoss(String history) {
    signIn("test.teacher.a");
    Long id = students.create(1L, "Test", "Historie", LocalDate.of(2008, 1, 1));
    if (history.equals("attendance")) {
      jdbc.update(
          "INSERT INTO attendance (student_id, attendance_date, status) VALUES (?, '2026-09-14', 'ANWESEND')",
          id);
    } else {
      jdbc.update(
          "INSERT INTO attendance_audit (student_id, deletion_date_id, event_type, occurred_at)"
              + " VALUES (?, 1, 'ACCOUNT_RESET', '2026-09-14')",
          id);
    }
    var before = students.get(id);
    var attendances = jdbc.queryForList("SELECT * FROM attendance ORDER BY id");
    var audits = jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id");
    assertThatThrownBy(() -> students.delete(id)).hasMessage("STUDENT_HAS_HISTORY").hasNoCause();
    assertThat(students.get(id)).isEqualTo(before);
    assertThat(jdbc.queryForList("SELECT * FROM attendance ORDER BY id")).isEqualTo(attendances);
    assertThat(jdbc.queryForList("SELECT * FROM attendance_audit ORDER BY id")).isEqualTo(audits);
  }

  /** Prüft unzulässige Klassenleitungen durch fremde, fehlende oder ungeeignete Mitarbeiter */
  @Test
  void cannotChooseForeignClassTeacherOrUseAdministratorAsClassTeacher() {
    signIn("test.teacher.a");
    assertThatThrownBy(() -> classes.create("Verboten", 2L, 1L, 1L))
        .isInstanceOf(AccessDeniedException.class);
    signIn("test.admin");
    assertThatThrownBy(() -> classes.create("Verboten", 3L, 1L, 1L)).hasMessage("INVALID_CLASS");
    assertThatThrownBy(() -> classes.create("Verboten", 999L, 1L, 1L)).hasMessage("INVALID_CLASS");
    assertThatThrownBy(() -> classes.create("Verboten", null, 1L, 1L)).hasMessage("INVALID_CLASS");
    assertThat(classes.list()).hasSize(3);
  }

  /** Prüft Klassenkennung und vorhandene Pläne vor dem Speichern einer neuen Klasse */
  @Test
  void validatesClassCodeAndExistingPlansWithoutWriting() {
    signIn("test.admin");
    for (String code : java.util.Arrays.asList(null, "", " ", "X".repeat(31))) {
      assertThatThrownBy(() -> classes.create(code, 1L, 1L, 1L)).hasMessage("INVALID_CLASS");
    }
    assertThatThrownBy(() -> classes.create("Neu", 1L, null, 1L)).hasMessage("INVALID_CLASS");
    assertThatThrownBy(() -> classes.create("Neu", 1L, 999L, 1L)).hasMessage("INVALID_CLASS");
    assertThatThrownBy(() -> classes.create("Neu", 1L, 1L, null)).hasMessage("INVALID_CLASS");
    assertThatThrownBy(() -> classes.create("Neu", 1L, 1L, 999L)).hasMessage("INVALID_CLASS");
    assertThatThrownBy(() -> classes.create(" 26TEST-A ", 1L, 1L, 1L))
        .hasMessage("CLASS_CODE_ALREADY_EXISTS")
        .hasNoCause();
    assertThat(classes.list()).hasSize(3);
  }

  /** Prüft ungültige Schülerdaten und fehlende Schüler ohne Änderung gespeicherter Daten */
  @Test
  void validatesStudentNamesAndBirthDateWithoutChangingStoredData() {
    signIn("test.teacher.a");
    var before = students.get(1L);
    for (String name : java.util.Arrays.asList(null, "", " ", "X".repeat(101))) {
      assertThatThrownBy(() -> students.create(1L, name, "Test", LocalDate.of(2008, 1, 1)))
          .hasMessage("INVALID_STUDENT");
      assertThatThrownBy(() -> students.update(1L, "Test", name, LocalDate.of(2008, 1, 1)))
          .hasMessage("INVALID_STUDENT");
    }
    assertThatThrownBy(() -> students.create(1L, "Test", "Test", null))
        .hasMessage("INVALID_STUDENT");
    assertThatThrownBy(() -> students.update(1L, "Test", "Test", null))
        .hasMessage("INVALID_STUDENT");
    assertThatThrownBy(() -> students.create(999L, "Test", "Test", LocalDate.of(2008, 1, 1)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> students.get(999L)).hasMessage("STUDENT_NOT_FOUND");
    assertThatThrownBy(() -> students.update(999L, "Test", "Test", LocalDate.of(2008, 1, 1)))
        .hasMessage("STUDENT_NOT_FOUND");
    assertThatThrownBy(() -> students.delete(999L)).hasMessage("STUDENT_NOT_FOUND");
    assertThat(students.get(1L)).isEqualTo(before);
    assertThat(students.list(1L)).hasSize(6);
  }

  /** Prüft Schüleransichten und Fehlermeldungen auf den Schutz der Kartenkennungen */
  @Test
  void studentViewsExposeNoUidAndFailuresDoNotLogIt(CapturedOutput output) throws Exception {
    signIn("test.teacher.a");
    String view = mapper.writeValueAsString(students.get(1L));
    assertThat(mapper.readTree(view).properties())
        .extracting(Entry::getKey)
        .containsExactlyInAnyOrder(
            "id",
            "firstName",
            "lastName",
            "birthDate",
            "classId",
            "unexcusedMinutes",
            "excusedMinutes",
            "unexcusedDays",
            "excusedDays",
            "unexcusedHours",
            "excusedHours");
    assertThat(mapper.writeValueAsString(students.list(1L))).doesNotContain("TEST-UID", "rfid");
    assertThat(mapper.writeValueAsString(classes.list())).doesNotContain("TEST-UID", "rfid");
    assertThatThrownBy(() -> students.delete(1L)).hasMessage("STUDENT_HAS_HISTORY").hasNoCause();
    assertThat(output.getAll()).doesNotContain("TEST-UID");
  }
}
