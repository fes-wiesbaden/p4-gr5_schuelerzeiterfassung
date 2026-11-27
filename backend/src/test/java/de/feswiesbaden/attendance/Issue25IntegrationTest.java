package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.feswiesbaden.attendance.login.StaffPrincipal;
import de.feswiesbaden.attendance.service.RetentionService;
import de.feswiesbaden.attendance.service.StudentService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Rollen- und Klassenrechte sowie Session/CSRF sind bereits in Issue22IntegrationTest und
// Issue23IntegrationTest abgedeckt. Diese Klasse schliesst die verbleibende Luecke aus den
// Akzeptanzkriterien von Issue #25: das manuelle Hard Delete von Stammdaten mit Historie bleibt
// blockiert, waehrend die automatische, fristbasierte Loeschung ueber deletion_date zulaessig
// bleibt und genau diese Blockade danach aufhebt. Die Zuordnung auf HTTP 409 erfolgt erst mit
// dem REST-Controller aus Issue #27; bis dahin ist STUDENT_HAS_HISTORY die massgebliche
// Konfliktkennung auf Service-Ebene.
@SpringBootTest(properties = "attendance.retention.scheduling.enabled=false")
@Testcontainers
@Sql("/fixtures/attendance.sql")
class Issue25IntegrationTest {
  @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @Autowired JdbcTemplate jdbc;

  @Autowired StudentService students;

  @Autowired RetentionService retention;

  @MockitoBean Clock clock;

  @BeforeEach
  void prepare() {
    // Die Schüleranlage prüft das Geburtsdatum gegen den heutigen Berliner Tag
    when(clock.instant()).thenReturn(Instant.parse("2026-09-14T08:45:00Z"));
    when(clock.withZone(any()))
        .thenAnswer(invocation -> Clock.fixed(clock.instant(), invocation.getArgument(0)));
  }

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

  @Test
  void manualHardDeleteStaysBlockedUntilAutomaticRetentionRemovesTheHistory() {
    signIn("test.teacher.a");
    Long id = students.create(1L, "Test", "Historie", LocalDate.of(2008, 1, 1));
    jdbc.update(
        "INSERT INTO attendance_audit (student_id, deletion_date_id, event_type, occurred_at)"
            + " VALUES (?, 1, 'ACCOUNT_RESET', '2026-09-14')",
        id);

    // Manuelles Hard Delete: Stammdaten mit Historie bleiben vollstaendig erhalten.
    var before = students.get(id);
    assertThatThrownBy(() -> students.delete(id)).hasMessage("STUDENT_HAS_HISTORY").hasNoCause();
    assertThat(students.get(id)).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE student_id = ?", Integer.class, id))
        .isEqualTo(1);

    // Die automatische Loeschung ueber deletion_date laeuft unabhaengig von Rollen oder
    // Klassenrechten und bleibt zulaessig, sobald die Frist erreicht ist.
    SecurityContextHolder.clearContext();
    when(clock.instant()).thenReturn(Instant.parse("2027-06-29T22:00:00Z"));
    assertThat(retention.deleteHistory()).isPositive();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_audit WHERE student_id = ?", Integer.class, id))
        .isZero();

    // Ohne verbleibende Historie laesst sich dasselbe Hard Delete nun durchfuehren.
    signIn("test.teacher.a");
    students.delete(id);
    assertThatThrownBy(() -> students.get(id)).hasMessage("STUDENT_NOT_FOUND");
  }

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
