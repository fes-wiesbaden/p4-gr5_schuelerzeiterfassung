package de.feswiesbaden.attendance.repository;

import de.feswiesbaden.attendance.entities.Timetable;
import de.feswiesbaden.attendance.enums.Weekday;
import jakarta.persistence.EntityManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
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

  /**
   * Prüft, ob der Stundenplan am angegebenen Wochentag bereits einen zeitlich überlappenden Slot
   * enthält
   */
  public boolean hasOverlappingSlot(
      Long timetableId, Weekday weekday, LocalTime startsAt, LocalTime endsAt) {
    return !jdbc.queryForList(
            "SELECT id FROM timetable_slot WHERE timetable_id = ? AND weekday = ?"
                + " AND start_time < ? AND end_time > ?",
            Long.class,
            timetableId,
            weekday.name(),
            endsAt,
            startsAt)
        .isEmpty();
  }

  /**
   * Prüft, ob eine andere Klasse den Raum im angegebenen Zeitfenster belegt, während einer ihrer
   * zugeordneten Blöcke den angegebenen Zeitraum überschneidet
   */
  public boolean isRoomOccupied(
      Long excludeClassId,
      Long roomId,
      Weekday weekday,
      LocalTime startsAt,
      LocalTime endsAt,
      LocalDate periodStart,
      LocalDate periodEnd) {
    return !jdbc.queryForList(
            """
            SELECT c.id FROM school_class c
            JOIN timetable_slot s ON s.timetable_id = c.timetable_id
            JOIN class_block_assignment cba ON cba.school_class_id = c.id
            JOIN block_assignment b ON b.id = cba.block_assignment_id
            WHERE c.id <> ? AND s.room_id = ? AND s.weekday = ?
              AND s.start_time < ? AND s.end_time > ?
              AND b.starts_on <= ? AND b.ends_on >= ?
            """,
            Long.class,
            excludeClassId,
            roomId,
            weekday.name(),
            endsAt,
            startsAt,
            periodEnd,
            periodStart)
        .isEmpty();
  }

  /**
   * Fügt den Slot zum Stundenplan hinzu und liefert seine erzeugte ID. Nutzt reines JDBC statt der
   * JPA-Entität, da Hibernate LocalTime-Werte sonst in die JVM-Zeitzone statt zonenlos schreibt
   */
  public Long insertSlot(
      Long timetableId, Long roomId, Weekday weekday, LocalTime startsAt, LocalTime endsAt) {
    var key = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var insert =
              connection.prepareStatement(
                  "INSERT INTO timetable_slot (timetable_id, room_id, weekday, start_time, end_time)"
                      + " VALUES (?, ?, ?, ?, ?)",
                  Statement.RETURN_GENERATED_KEYS);
          insert.setLong(1, timetableId);
          insert.setLong(2, roomId);
          insert.setString(3, weekday.name());
          insert.setObject(4, startsAt);
          insert.setObject(5, endsAt);
          return insert;
        },
        key);
    return key.getKey().longValue();
  }
}
