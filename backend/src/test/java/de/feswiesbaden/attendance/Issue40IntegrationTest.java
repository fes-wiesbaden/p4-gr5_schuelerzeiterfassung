package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.feswiesbaden.attendance.enums.Weekday;
import de.feswiesbaden.attendance.login.StaffPrincipal;
import de.feswiesbaden.attendance.service.BlockPlanService;
import de.feswiesbaden.attendance.service.SchoolClassService;
import de.feswiesbaden.attendance.service.TimetableService;
import de.feswiesbaden.attendance.service.TimetableService.LessonDetails;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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

/**
 * Prüft Raumkonflikte und unzulässige Stundenplan-Wiederverwendung der Stunden-/Blockplanung (Issue
 * #40, offene Tests aus #20). Überlappende Slots und Blöcke sowie planfremde Zuordnungen prüft
 * PlanningApiIntegrationTest über die API
 */
@SpringBootTest
@Testcontainers
@Sql("/fixtures/attendance.sql")
class Issue40IntegrationTest {
  @Container @ServiceConnection
  static MySQLContainer<?> mysql =
      new MySQLContainer<>("mysql:8.0").withUrlParam("connectionTimeZone", "UTC");

  @Autowired JdbcTemplate jdbc;

  @Autowired TimetableService timetables;

  @Autowired BlockPlanService plans;

  @Autowired SchoolClassService classes;

  @MockitoBean Clock clock;

  @BeforeEach
  void prepare() {
    when(clock.instant()).thenReturn(Instant.parse("2026-09-13T18:00:00Z"));
    signIn("test.admin");
    jdbc.update("INSERT INTO room (id, room_number) VALUES (90, 'KONFLIKT-R90')");
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

  @Test
  void preservesLessonDetailsForLegacyUpdatesAndAllowsExplicitClearing() {
    jdbc.update("UPDATE timetable_slot SET learning_field = 'LF 1', teacher_id = 1 WHERE id = 1");
    when(clock.instant()).thenReturn(Instant.parse("2026-09-14T08:50:00Z"));
    var effectiveOn =
        timetables.updateSlot(
            1L, 1L, Weekday.MONTAG, LocalTime.of(7, 30), LocalTime.of(9, 0), 1L, null);
    assertThat(effectiveOn).isEqualTo(LocalDate.of(2026, 9, 21));
    var future =
        jdbc.queryForMap(
            "SELECT id, learning_field, teacher_id FROM timetable_slot"
                + " WHERE timetable_id = 1 AND series_id = 1 AND valid_until IS NULL");
    assertThat(future).containsEntry("learning_field", "LF 1").containsEntry("teacher_id", 1L);

    timetables.updateSlot(
        1L,
        (Long) future.get("id"),
        Weekday.MONTAG,
        LocalTime.of(7, 30),
        LocalTime.of(9, 0),
        1L,
        new LessonDetails(null, null));
    assertThat(
            jdbc.queryForMap(
                "SELECT learning_field, teacher_id FROM timetable_slot"
                    + " WHERE timetable_id = 1 AND series_id = 1 AND valid_until IS NULL"))
        .containsEntry("learning_field", null)
        .containsEntry("teacher_id", null);
    assertThat(
            jdbc.queryForList(
                "SELECT learning_field FROM timetable_slot WHERE id = 1", String.class))
        .containsExactly("LF 1");
  }

  /**
   * Derselbe Raum darf nicht gleichzeitig von zwei aktiven Klassen belegt sein. Die neue Klasse
   * nutzt bewusst einen eigenen Stundenplan, damit ausschließlich der Raumkonflikt greift
   */
  @Test
  void rejectsRoomConflictWhenAssigningABlock() {
    Long timetableId = timetables.create("Konfliktplan");
    // Derselbe Raum (1 = TEST-R01) und derselbe Slot wie bei Klasse 1
    timetables.addSlot(timetableId, Weekday.MONTAG, LocalTime.of(7, 30), LocalTime.of(9, 0), 1L);
    Long classId = classes.create("40-KONFLIKT", 1L, 1L, timetableId);
    // Block 1 der Fixture (2026-09-14 bis 2026-09-18) ist bereits Klasse 1 zugeordnet, die Raum 1
    // im selben Slot nutzt
    assertThatThrownBy(() -> classes.assignBlock(classId, 1L)).hasMessage("ROOM_ALREADY_ASSIGNED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM class_block_assignment", Integer.class))
        .isEqualTo(3);
  }

  /**
   * Ein neuer Slot darf keinen Raum belegen, den eine gleichzeitig aktive Klasse zur selben Zeit
   * nutzt, sobald eine Klasse mit diesem Stundenplan einem überschneidenden Block zugeordnet ist
   */
  @Test
  void rejectsRoomConflictWhenAddingASlotToAnAssignedTimetable() {
    Long timetableId = timetables.create("Später ergänzter Plan");
    Long classId = classes.create("40-SLOT", 1L, 1L, timetableId);
    classes.assignBlock(classId, 1L);
    // Klasse 1 belegt Raum 1 montags von 07:30 bis 09:00 im selben Block 1
    assertThatThrownBy(
            () ->
                timetables.addSlot(
                    timetableId, Weekday.MONTAG, LocalTime.of(8, 0), LocalTime.of(8, 45), 1L))
        .hasMessage("ROOM_ALREADY_ASSIGNED");
    timetables.addSlot(timetableId, Weekday.MONTAG, LocalTime.of(8, 0), LocalTime.of(8, 45), 90L);
    assertThat(
            jdbc.queryForList(
                "SELECT room_id FROM timetable_slot WHERE timetable_id = ?",
                Long.class,
                timetableId))
        .containsExactly(90L);
  }

  /**
   * Derselbe Stundenplan darf nur von Klassen genutzt werden, deren zugeordnete Blöcke sich nicht
   * überschneiden. Der geteilte Stundenplan hat Slots, damit die Wiederverwendung auch dann statt
   * eines Raumkonflikts gemeldet wird
   */
  @Test
  void rejectsDisallowedTimetableReuseWithOverlappingBlocks() {
    Long sharedTimetableId = timetables.create("Geteilter Stundenplan");
    timetables.addSlot(
        sharedTimetableId, Weekday.MONTAG, LocalTime.of(7, 30), LocalTime.of(9, 0), 90L);
    Long otherPlanId =
        plans.create("Anderer Plan", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31));
    Long firstClass = classes.create("40-A", 1L, 1L, sharedTimetableId);
    Long firstBlock =
        plans.addBlockAssignment(1L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5));
    classes.assignBlock(firstClass, firstBlock);

    Long secondClass = classes.create("40-B", 1L, otherPlanId, sharedTimetableId);
    Long secondBlock =
        plans.addBlockAssignment(otherPlanId, LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 8));
    assertThatThrownBy(() -> classes.assignBlock(secondClass, secondBlock))
        .hasMessage("TIMETABLE_REUSE_NOT_ALLOWED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_block_assignment WHERE block_assignment_id = ?",
                Integer.class,
                secondBlock))
        .isZero();
    Long laterBlock =
        plans.addBlockAssignment(
            otherPlanId, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 16));
    classes.assignBlock(secondClass, laterBlock);
  }

  @Test
  void acceptsOverlappingBlocksWithoutTheConflictingWeekday() {
    Long planId = plans.create("Teilwoche", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    Long blockId =
        plans.addBlockAssignment(planId, LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 18));
    jdbc.update("UPDATE timetable_slot SET room_id = 1 WHERE id = 3");
    Long classId = classes.create("40-TEILWOCHE", 1L, planId, 2L);
    jdbc.update("DELETE FROM class_block_assignment WHERE school_class_id = 2");

    classes.assignBlock(classId, blockId);

    assertThat(
            jdbc.queryForObject(
                "SELECT block_assignment_id FROM class_block_assignment WHERE school_class_id = ?",
                Long.class,
                classId))
        .isEqualTo(blockId);
  }

  @Test
  void ignoresZeroDurationWhenAddingAnOverlappingSlot() {
    jdbc.update("UPDATE timetable_slot SET start_time = '08:15', end_time = '08:15' WHERE id = 1");

    Long slotId =
        timetables.addSlot(1L, Weekday.MONTAG, LocalTime.of(8, 0), LocalTime.of(8, 45), 1L);

    assertThat(
            jdbc.queryForObject(
                "SELECT timetable_id FROM timetable_slot WHERE id = ?", Long.class, slotId))
        .isEqualTo(1L);
  }

  @Test
  void ignoresZeroDurationOnEitherSideOfARoomConflict() {
    jdbc.update(
        "UPDATE timetable_slot SET room_id = 1, start_time = '08:15', end_time = '08:15' WHERE id = 3");
    jdbc.update("DELETE FROM class_block_assignment WHERE school_class_id = 2");
    // Derselbe Raum und dieselbe Uhrzeit wie bei Klasse 1, aber ohne Raumbelegung
    classes.assignBlock(2L, 1L);

    jdbc.update("DELETE FROM class_block_assignment WHERE school_class_id = 1");
    classes.assignBlock(1L, 1L);

    assertThat(
            jdbc.queryForList(
                "SELECT school_class_id FROM class_block_assignment WHERE block_assignment_id = 1",
                Long.class))
        .containsExactlyInAnyOrder(1L, 2L);
  }

  @Test
  void ignoresOldAssignmentsWhenReusingATimetable() {
    Long nextPlan =
        plans.create("Nachfolgeplan", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30));
    jdbc.update("UPDATE school_class SET block_plan_id = ? WHERE id = 1", nextPlan);
    Long classId = classes.create("40-NACHFOLGE", 1L, 1L, 1L);

    classes.assignBlock(classId, 1L);

    assertThat(
            jdbc.queryForList(
                "SELECT school_class_id FROM class_block_assignment WHERE block_assignment_id = 1",
                Long.class))
        .contains(1L, classId);
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
