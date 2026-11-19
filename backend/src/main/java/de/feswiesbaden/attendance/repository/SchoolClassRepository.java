package de.feswiesbaden.attendance.repository;

import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Liest Klassenzuordnungen und Planungsdaten und speichert Klassen und Planwechsel. Die Services
 * müssen die übergebenen Mitarbeiter-IDs und Administratorrechte zuvor ermitteln
 */
@Repository
public class SchoolClassRepository {
  private static final String CLASS_ACCESS =
      """
      (? OR c.class_teacher_id = ? OR EXISTS
        (SELECT 1 FROM teacher_class tc WHERE tc.school_class_id = c.id AND tc.staff_id = ?))
      """;

  private final JdbcTemplate jdbc;

  public SchoolClassRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Liefert eigene und zugeordnete Klassen des Mitarbeiters. Bei administrator=true werden alle
   * Klassen zurückgegeben
   */
  public List<ClassData> findAccessible(Long staffId, boolean administrator) {
    return jdbc.query(
        "SELECT c.id, c.class_code, c.class_teacher_id, c.block_plan_id, c.timetable_id"
            + " FROM school_class c WHERE "
            + CLASS_ACCESS
            + " ORDER BY c.id",
        (row, index) ->
            new ClassData(
                row.getLong("id"),
                row.getString("class_code"),
                row.getLong("class_teacher_id"),
                row.getLong("block_plan_id"),
                row.getLong("timetable_id")),
        administrator,
        staffId,
        staffId);
  }

  /**
   * Prüft, ob die Klasse existiert und über Administratorstatus, Klassenleitung oder Lehrzuordnung
   * zugänglich ist
   */
  public boolean isAccessible(Long classId, Long staffId, boolean administrator) {
    return !jdbc.queryForList(
            "SELECT c.id FROM school_class c WHERE c.id = ? AND " + CLASS_ACCESS,
            Long.class,
            classId,
            administrator,
            staffId,
            staffId)
        .isEmpty();
  }

  /**
   * Sperrt den Klassendatensatz und liefert die ID der Klassenlehrkraft, falls die Klasse existiert
   */
  public Optional<Long> lockClassTeacher(Long classId) {
    return jdbc
        .queryForList(
            "SELECT class_teacher_id FROM school_class WHERE id = ? FOR UPDATE",
            Long.class,
            classId)
        .stream()
        .findFirst();
  }

  /** Prüft, ob der Mitarbeiter als Lehrkraft gespeichert ist */
  public boolean hasTeacher(Long staffId) {
    return !jdbc.queryForList(
            "SELECT id FROM staff WHERE id = ? AND role = 'LEHRKRAFT'", Long.class, staffId)
        .isEmpty();
  }

  /** Prüft, ob der Blockplan existiert */
  public boolean hasBlockPlan(Long planId) {
    return !jdbc.queryForList("SELECT id FROM block_plan WHERE id = ?", Long.class, planId)
        .isEmpty();
  }

  /** Prüft, ob der Stundenplan existiert */
  public boolean hasTimetable(Long timetableId) {
    return !jdbc.queryForList("SELECT id FROM timetable WHERE id = ?", Long.class, timetableId)
        .isEmpty();
  }

  /** Speichert eine Klasse mit Lehrkraft und Plänen und liefert ihre erzeugte ID */
  public Long insert(String classCode, Long classTeacherId, Long blockPlanId, Long timetableId) {
    var key = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var insert =
              connection.prepareStatement(
                  "INSERT INTO school_class (class_code, class_teacher_id, block_plan_id, timetable_id)"
                      + " VALUES (?, ?, ?, ?)",
                  Statement.RETURN_GENERATED_KEYS);
          insert.setString(1, classCode);
          insert.setLong(2, classTeacherId);
          insert.setLong(3, blockPlanId);
          insert.setLong(4, timetableId);
          return insert;
        },
        key);
    return key.getKey().longValue();
  }

  /** Sperrt Klasse und aktuellen Blockplan und liefert deren Planungsdaten */
  public Optional<ClassPlan> lockPlan(Long classId) {
    return findPlan(classId, true);
  }

  /** Lädt den aktuellen Blockplan der Klasse und sperrt beide Datensätze auf Wunsch */
  public Optional<ClassPlan> findPlan(Long classId, boolean lock) {
    return jdbc
        .query(
            "SELECT c.id, c.block_plan_id, c.timetable_id, p.starts_on, p.ends_on FROM school_class c"
                + " JOIN block_plan p ON p.id = c.block_plan_id WHERE c.id = ?"
                + (lock ? " FOR UPDATE" : ""),
            (row, index) ->
                new ClassPlan(
                    row.getLong("id"),
                    row.getLong("block_plan_id"),
                    row.getLong("timetable_id"),
                    row.getDate("starts_on").toLocalDate(),
                    row.getDate("ends_on").toLocalDate()),
            classId)
        .stream()
        .findFirst();
  }

  /** Lädt Beginn und Ende des Blockplans mit Lesesperre */
  public Optional<PlanDates> lockPlanDates(Long planId) {
    return jdbc
        .query(
            "SELECT starts_on, ends_on FROM block_plan WHERE id = ? FOR SHARE",
            (row, index) ->
                new PlanDates(row.getDate(1).toLocalDate(), row.getDate(2).toLocalDate()),
            planId)
        .stream()
        .findFirst();
  }

  /** Prüft, ob der Blockplan Blöcke enthält, deren Enddatum am angegebenen Tag oder danach liegt */
  public boolean hasUnfinishedBlocks(Long planId, LocalDate today) {
    return !jdbc.queryForList(
            "SELECT id FROM block_assignment WHERE block_plan_id = ? AND ends_on >= ? FOR UPDATE",
            Long.class,
            planId,
            today)
        .isEmpty();
  }

  /** Entfernt die bisherigen Blockzuordnungen und ersetzt den aktuellen Blockplan */
  public void replacePlan(Long classId, Long planId) {
    jdbc.update("DELETE FROM class_block_assignment WHERE school_class_id = ?", classId);
    jdbc.update("UPDATE school_class SET block_plan_id = ? WHERE id = ?", planId, classId);
  }

  /** Enthält Klassen-, Blockplan- und Stundenplan-ID sowie Beginn und Ende des Blockplans */
  public record ClassPlan(
      Long classId, Long planId, Long timetableId, LocalDate startsOn, LocalDate endsOn) {}

  /** Enthält Beginn und Ende eines Blockplans */
  public record PlanDates(LocalDate startsOn, LocalDate endsOn) {}

  /** Enthält Klassenkennung, Klassenlehrkraft und aktuelle Planverweise */
  public record ClassData(
      Long id, String classCode, Long classTeacherId, Long blockPlanId, Long timetableId) {}
}
