package de.feswiesbaden.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.feswiesbaden.attendance.enums.Weekday;
import de.feswiesbaden.attendance.service.BlockPlanService;
import de.feswiesbaden.attendance.service.SchoolClassService;
import de.feswiesbaden.attendance.service.TimetableService;
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
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prüft die Ablehnung von Zeit- und Zuordnungskonflikten beim Anlegen oder Ändern der
 * Stunden-/Blockplanung (Issue #40). Diese Tests waren laut #20 für dessen Abschluss ausdrücklich
 * dorthin verschoben: überlappende Slots, überlappende Blöcke, Raumkonflikte, Blockzuordnungen zu
 * einem fremden Plan und unzulässige Stundenplan-Wiederverwendung
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

  @BeforeEach
  void prepare() {
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

  /** Slots desselben Stundenplans dürfen sich nicht überlappen */
  @Test
  void rejectsOverlappingTimetableSlots() {
    Long timetableId = timetables.create("Testplan");
    timetables.addSlot(timetableId, Weekday.MONTAG, LocalTime.of(7, 30), LocalTime.of(9, 0), 90L);
    assertThatThrownBy(
            () ->
                timetables.addSlot(
                    timetableId, Weekday.MONTAG, LocalTime.of(8, 0), LocalTime.of(8, 45), 90L))
        .hasMessage("OVERLAPPING_TIMETABLE_SLOT");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM timetable_slot WHERE timetable_id = ?",
                Integer.class,
                timetableId))
        .isEqualTo(1);
  }

  /** Blöcke eines Blockplans dürfen sich nicht überlappen */
  @Test
  void rejectsOverlappingBlockAssignmentsInTheSamePlan() {
    Long planId =
        plans.create("Testblockplan", LocalDate.of(2030, 1, 1), LocalDate.of(2030, 6, 30));
    plans.addBlockAssignment(planId, LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 5));
    assertThatThrownBy(
            () ->
                plans.addBlockAssignment(
                    planId, LocalDate.of(2030, 1, 3), LocalDate.of(2030, 1, 10)))
        .hasMessage("OVERLAPPING_BLOCK_ASSIGNMENT");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM block_assignment WHERE block_plan_id = ?",
                Integer.class,
                planId))
        .isEqualTo(1);
  }

  /** Eine Blockzuordnung muss zum aktuellen Blockplan der Klasse gehören */
  @Test
  void rejectsBlockAssignmentBelongingToAnotherPlan() {
    Long foreignPlanId =
        plans.create("Fremdplan", LocalDate.of(2030, 1, 1), LocalDate.of(2030, 6, 30));
    Long foreignBlockId =
        plans.addBlockAssignment(foreignPlanId, LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 5));
    // Klasse 1 aus der Fixture nutzt Blockplan 1, nicht den soeben angelegten Fremdplan
    assertThatThrownBy(() -> classes.assignBlock(1L, foreignBlockId))
        .hasMessage("BLOCK_ASSIGNMENT_BELONGS_TO_ANOTHER_PLAN");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_block_assignment WHERE block_assignment_id = ?",
                Integer.class,
                foreignBlockId))
        .isZero();
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

  /** Setzt den angegebenen Testmitarbeiter als authentifizierten Benutzer */
  private void signIn(String username) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(username, null, List.of()));
  }
}
