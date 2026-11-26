package de.feswiesbaden.attendance.repository;

import de.feswiesbaden.attendance.entities.Timetable;
import de.feswiesbaden.attendance.enums.Weekday;
import jakarta.persistence.EntityManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Speichert Stundenpläne und ihre Unterrichtszeiten (Slots) */
@Repository
public class TimetableRepository {
  private final EntityManager entityManager;

  private final JdbcTemplate jdbc;

  public TimetableRepository(EntityManager entityManager, JdbcTemplate jdbc) {
    this.entityManager = entityManager;
    this.jdbc = jdbc;
  }

  /** Legt einen neuen, zunächst leeren Stundenplan an und liefert dessen ID */
  public Long insertTimetable(String name) {
    Timetable timetable = new Timetable();
    timetable.setName(name);
    entityManager.persist(timetable);
    return timetable.getId();
  }

  /** Prüft, ob der Raum existiert */
  public boolean roomExists(Long roomId) {
    return !jdbc.queryForList("SELECT id FROM room WHERE id = ?", Long.class, roomId).isEmpty();
  }

  /** Prüft, ob Personal mit Lehrkraftrolle für den Unterricht ausgewählt wurde. */
  public boolean teacherExists(Long teacherId) {
    return !jdbc.queryForList(
            "SELECT id FROM staff WHERE id = ? AND role = 'LEHRKRAFT'", Long.class, teacherId)
        .isEmpty();
  }

  /**
   * Prüft, ob der Stundenplan am angegebenen Wochentag bereits einen zeitlich überlappenden Slot
   * enthält
   */
  public boolean hasOverlappingSlot(
      Long timetableId, Weekday weekday, LocalTime startsAt, LocalTime endsAt, LocalDate from) {
    return jdbc.queryForObject(
        """
        SELECT EXISTS(SELECT 1 FROM timetable_slot WHERE timetable_id = ? AND weekday = ?
          AND start_time < end_time AND start_time < ? AND end_time > ?
          AND (valid_until IS NULL OR valid_until > ?))
        """,
        Boolean.class,
        timetableId,
        weekday.name(),
        endsAt,
        startsAt,
        from);
  }

  /** Liest Wochentag und gemeinsamen Zeitraum potenzieller Raumkonflikte */
  public List<RoomOverlap> findRoomOverlaps(
      Long excludeClassId,
      Long timetableId,
      LocalDate periodStart,
      LocalDate periodEnd,
      Long slotId) {
    return jdbc.query(
        """
        SELECT own.weekday,
          GREATEST(b.starts_on, ?, COALESCE(own.valid_from, b.starts_on),
            COALESCE(s.valid_from, b.starts_on)) AS overlap_start,
          LEAST(b.ends_on, ?, COALESCE(DATE_SUB(own.valid_until, INTERVAL 1 DAY), b.ends_on),
            COALESCE(DATE_SUB(s.valid_until, INTERVAL 1 DAY), b.ends_on)) AS overlap_end
        FROM timetable_slot own
        JOIN timetable_slot s ON s.room_id = own.room_id AND s.weekday = own.weekday
          AND s.start_time < own.end_time AND s.end_time > own.start_time
        JOIN school_class c ON c.timetable_id = s.timetable_id
        JOIN class_block_assignment cba ON cba.school_class_id = c.id
        JOIN block_assignment b ON b.id = cba.block_assignment_id AND b.block_plan_id = c.block_plan_id
        WHERE own.timetable_id = ? AND c.id <> ? AND (? IS NULL OR own.id = ?)
          AND own.start_time < own.end_time AND s.start_time < s.end_time
          AND b.starts_on <= ? AND b.ends_on >= ?
        """,
        (row, index) ->
            new RoomOverlap(
                Weekday.valueOf(row.getString("weekday")),
                row.getObject("overlap_start", LocalDate.class),
                row.getObject("overlap_end", LocalDate.class)),
        periodStart,
        periodEnd,
        timetableId,
        excludeClassId,
        slotId,
        slotId,
        periodEnd,
        periodStart);
  }

  /** Sperrt die bearbeitbare Fassung des Slots im angegebenen Stundenplan */
  public Optional<SlotVersion> lockSlot(Long timetableId, Long slotId) {
    return jdbc
        .query(
            """
            SELECT id, series_id, weekday, start_time, valid_from, valid_until, learning_field, teacher_id
            FROM timetable_slot
            WHERE id = ? AND timetable_id = ? AND valid_until IS NULL FOR UPDATE
            """,
            this::slotVersion,
            slotId,
            timetableId)
        .stream()
        .findFirst();
  }

  /** Sperrt abgelöste Fassungen derselben Serie, die heute oder später noch gelten können */
  public List<SlotVersion> lockPredecessors(Long timetableId, Long seriesId, LocalDate today) {
    return jdbc.query(
        """
        SELECT id, series_id, weekday, start_time, valid_from, valid_until, learning_field, teacher_id
            FROM timetable_slot
        WHERE timetable_id = ? AND (id = ? OR series_id = ?) AND valid_until > ?
        ORDER BY id FOR UPDATE
        """,
        this::slotVersion,
        timetableId,
        seriesId,
        seriesId,
        today);
  }

  private SlotVersion slotVersion(ResultSet row, int index) throws SQLException {
    long id = row.getLong("id");
    Long seriesId = row.getObject("series_id", Long.class);
    return new SlotVersion(
        id,
        seriesId == null ? id : seriesId,
        Weekday.valueOf(row.getString("weekday")),
        row.getObject("start_time", LocalTime.class),
        row.getObject("valid_from", LocalDate.class),
        row.getObject("valid_until", LocalDate.class),
        row.getString("learning_field"),
        row.getObject("teacher_id", Long.class));
  }

  /** Entfernt die angegebene Slotfassung */
  public void deleteSlot(long slotId) {
    jdbc.update("DELETE FROM timetable_slot WHERE id = ?", slotId);
  }

  /** Speichert die exklusive Gültigkeitsgrenze der Slotfassung */
  public void setSlotValidUntil(long slotId, LocalDate until) {
    jdbc.update("UPDATE timetable_slot SET valid_until = ? WHERE id = ?", until, slotId);
  }

  /**
   * Fügt den Slot zum Stundenplan hinzu und liefert seine erzeugte ID. Nutzt reines JDBC statt der
   * JPA-Entität, da Hibernate LocalTime-Werte sonst in die JVM-Zeitzone statt zonenlos schreibt
   */
  public Long insertSlot(
      Long timetableId,
      Long roomId,
      Weekday weekday,
      LocalTime startsAt,
      LocalTime endsAt,
      LocalDate validFrom,
      Long seriesId,
      String learningField,
      Long teacherId) {
    var key = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var insert =
              connection.prepareStatement(
                  "INSERT INTO timetable_slot"
                      + " (timetable_id, room_id, weekday, start_time, end_time, valid_from, series_id,"
                      + " learning_field, teacher_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                  Statement.RETURN_GENERATED_KEYS);
          insert.setLong(1, timetableId);
          insert.setLong(2, roomId);
          insert.setString(3, weekday.name());
          insert.setObject(4, startsAt);
          insert.setObject(5, endsAt);
          insert.setObject(6, validFrom);
          insert.setObject(7, seriesId);
          insert.setString(8, learningField);
          insert.setObject(9, teacherId);
          return insert;
        },
        key);
    return key.getKey().longValue();
  }

  /** Enthält die für den Zeitpunkt eines Slotwechsels benötigten Daten */
  public record SlotVersion(
      long id,
      long seriesId,
      Weekday weekday,
      LocalTime startsAt,
      LocalDate validFrom,
      LocalDate validUntil,
      String learningField,
      Long teacherId) {}

  /** Enthält die Terminmerkmale einer möglichen Raumüberschneidung */
  public record RoomOverlap(Weekday weekday, LocalDate startsOn, LocalDate endsOn) {}
}
