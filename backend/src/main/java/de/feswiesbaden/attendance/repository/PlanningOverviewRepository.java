package de.feswiesbaden.attendance.repository;

import de.feswiesbaden.attendance.enums.Weekday;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Liest Stundenpläne, Blockpläne, Räume und Klassen für die Planungsübersicht */
@Repository
public class PlanningOverviewRepository {
  private final JdbcTemplate jdbc;

  public PlanningOverviewRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Liefert alle Stundenpläne nach Namen sortiert */
  public List<TimetableRow> findTimetables() {
    return jdbc.query(
        "SELECT id, name FROM timetable ORDER BY name, id",
        (row, index) -> new TimetableRow(row.getLong("id"), row.getString("name")));
  }

  /** Liefert die Stundenraster aller Stundenpläne, nach Stundenplan gruppiert */
  public Map<Long, List<PeriodRow>> findAllPeriods() {
    return jdbc.query(
        "SELECT timetable_id, period_number, starts_at, ends_at FROM timetable_period"
            + " ORDER BY timetable_id, starts_at",
        rows -> {
          Map<Long, List<PeriodRow>> periods = new HashMap<>();
          while (rows.next()) {
            periods
                .computeIfAbsent(rows.getLong("timetable_id"), id -> new ArrayList<>())
                .add(period(rows));
          }
          return periods;
        });
  }

  /** Liefert das optionale Stundenraster eines Stundenplans. */
  public List<PeriodRow> findPeriods(Long timetableId) {
    return jdbc.query(
        """
        SELECT period_number, starts_at, ends_at
        FROM timetable_period WHERE timetable_id = ? ORDER BY starts_at
        """,
        (row, index) -> period(row),
        timetableId);
  }

  private static PeriodRow period(ResultSet row) throws SQLException {
    return new PeriodRow(
        row.getInt("period_number"),
        row.getObject("starts_at", LocalTime.class),
        row.getObject("ends_at", LocalTime.class));
  }

  /** Liefert alle Slots mit Raumnummer, sortiert nach Stundenplan, Wochentag und Beginn */
  public List<SlotRow> findSlots() {
    return jdbc.query(
        """
        SELECT s.id, s.timetable_id, s.weekday, s.start_time, s.end_time, s.room_id, r.room_number,
               s.valid_from, s.subject, s.teacher_id,
               TRIM(CONCAT(t.first_name, ' ', t.last_name)) AS teacher_name
        FROM timetable_slot s JOIN room r ON r.id = s.room_id
        LEFT JOIN staff t ON t.id = s.teacher_id
        WHERE s.valid_until IS NULL
        ORDER BY s.timetable_id, s.weekday, s.start_time
        """,
        (row, index) ->
            new SlotRow(
                row.getLong("id"),
                row.getLong("timetable_id"),
                Weekday.valueOf(row.getString("weekday")),
                row.getObject("start_time", LocalTime.class),
                row.getObject("end_time", LocalTime.class),
                row.getLong("room_id"),
                row.getString("room_number"),
                row.getObject("valid_from", LocalDate.class),
                row.getString("subject"),
                row.getObject("teacher_id", Long.class),
                row.getString("teacher_name")));
  }

  /** Liefert Vor- und Nachnamen aller Lehrkräfte nach Nachname und Vorname sortiert */
  public List<TeacherNameRow> findTeacherNames() {
    return jdbc.query(
        "SELECT id, first_name, last_name FROM staff WHERE role = 'LEHRKRAFT'"
            + " ORDER BY last_name, first_name, id",
        (row, index) ->
            new TeacherNameRow(
                row.getLong("id"), row.getString("first_name"), row.getString("last_name")));
  }

  /** Liefert die Namen der Lehrkräfte, die in Slots des Stundenplans eingetragen sind */
  public List<TeacherRow> findSlotTeachers(Long timetableId) {
    return jdbc.query(
        "SELECT DISTINCT t.id, TRIM(CONCAT(t.first_name, ' ', t.last_name)) AS name"
            + " FROM timetable_slot s JOIN staff t ON t.id = s.teacher_id WHERE s.timetable_id = ?",
        (row, index) -> new TeacherRow(row.getLong("id"), row.getString("name")),
        timetableId);
  }

  /** Liefert alle Blockpläne, die neuesten zuerst */
  public List<BlockPlanRow> findBlockPlans() {
    return jdbc.query(
        "SELECT id, name, starts_on, ends_on FROM block_plan ORDER BY starts_on DESC, id DESC",
        (row, index) ->
            new BlockPlanRow(
                row.getLong("id"),
                row.getString("name"),
                row.getObject("starts_on", LocalDate.class),
                row.getObject("ends_on", LocalDate.class)));
  }

  /** Liefert alle Blockzeiträume chronologisch */
  public List<BlockRow> findBlocks() {
    return jdbc.query(
        "SELECT id, block_plan_id, starts_on, ends_on FROM block_assignment"
            + " ORDER BY block_plan_id, starts_on",
        (row, index) ->
            new BlockRow(
                row.getLong("id"),
                row.getLong("block_plan_id"),
                row.getObject("starts_on", LocalDate.class),
                row.getObject("ends_on", LocalDate.class)));
  }

  /** Liefert alle Zuordnungen von Klassen zu Blockzeiträumen */
  public List<ClassBlockRow> findClassBlocks() {
    return jdbc.query(
        "SELECT school_class_id, block_assignment_id FROM class_block_assignment",
        (row, index) ->
            new ClassBlockRow(row.getLong("school_class_id"), row.getLong("block_assignment_id")));
  }

  /** Liefert je Klasse die zugeordneten Blöcke ihres aktuellen Blockplans chronologisch */
  public List<ClassPeriodRow> findClassPeriods() {
    return jdbc.query(
        """
        SELECT c.id, b.starts_on, b.ends_on FROM school_class c
        JOIN class_block_assignment cba ON cba.school_class_id = c.id
        JOIN block_assignment b ON b.id = cba.block_assignment_id
        WHERE b.block_plan_id = c.block_plan_id
        ORDER BY c.id, b.starts_on
        """,
        (row, index) ->
            new ClassPeriodRow(
                row.getLong("id"),
                row.getObject("starts_on", LocalDate.class),
                row.getObject("ends_on", LocalDate.class)));
  }

  /** Enthält einen der Klasse zugeordneten Unterrichtsblock */
  public record ClassPeriodRow(Long classId, LocalDate startsOn, LocalDate endsOn) {}

  /** Liefert alle Räume nach Raumnummer sortiert */
  public List<RoomRow> findRooms() {
    return jdbc.query(
        "SELECT id, room_number FROM room ORDER BY room_number",
        (row, index) -> new RoomRow(row.getLong("id"), row.getString("room_number")));
  }

  /** Enthält ID und Namen eines Stundenplans */
  public record TimetableRow(Long id, String name) {}

  /** Eine nummerierte Unterrichtsstunde im Zeitraster des Stundenplans. */
  public record PeriodRow(int number, LocalTime startsAt, LocalTime endsAt) {}

  /** Enthält ID und Raumnummer eines Raums */
  public record RoomRow(Long id, String roomNumber) {}

  /** Enthält einen Slot mit Wochentag, Uhrzeiten, Raum sowie optional Fach und Lehrkraft */
  public record SlotRow(
      Long id,
      Long timetableId,
      Weekday weekday,
      LocalTime startsAt,
      LocalTime endsAt,
      Long roomId,
      String roomNumber,
      LocalDate validFrom,
      String subject,
      Long teacherId,
      String teacherName) {}

  /** Enthält ID und Anzeigenamen einer Lehrkraft */
  public record TeacherRow(Long id, String name) {}

  /** Enthält ID, Vor- und Nachnamen einer Lehrkraft */
  public record TeacherNameRow(Long id, String firstName, String lastName) {
    /** Liefert die Auswahloption „Nachname, Vorname“, ohne Vorname nur den Nachnamen */
    public TeacherRow option() {
      return new TeacherRow(
          id, firstName.isBlank() ? lastName.strip() : lastName.strip() + ", " + firstName.strip());
    }
  }

  /** Enthält Name und Zeitraum eines Blockplans */
  public record BlockPlanRow(Long id, String name, LocalDate startsOn, LocalDate endsOn) {}

  /** Enthält einen Blockzeitraum und seinen Blockplan */
  public record BlockRow(Long id, Long blockPlanId, LocalDate startsOn, LocalDate endsOn) {}

  /** Enthält eine Zuordnung von Klasse zu Blockzeitraum */
  public record ClassBlockRow(Long classId, Long blockId) {}
}
