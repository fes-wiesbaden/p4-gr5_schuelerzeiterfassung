package de.feswiesbaden.attendance.repository;

import de.feswiesbaden.attendance.entities.SchoolClass;
import de.feswiesbaden.attendance.entities.Student;
import jakarta.persistence.EntityManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Liest und ändert Schülerdaten, Klassenzuordnungen und Kartenkennungen */
@Repository
public class StudentRepository {
  private static final String STUDENT_COLUMNS =
      "SELECT id, first_name, last_name, birth_date, school_class_id,"
          + " unexcused_minutes_account, excused_minutes_account FROM student";

  private final JdbcTemplate jdbc;

  private final EntityManager entityManager;

  public StudentRepository(JdbcTemplate jdbc, EntityManager entityManager) {
    this.jdbc = jdbc;
    this.entityManager = entityManager;
  }

  /** Liefert die Schüler einer Klasse ohne Kartenkennungen nach ID sortiert */
  public List<StudentData> findByClassId(Long classId) {
    return jdbc.query(
        STUDENT_COLUMNS + " WHERE school_class_id = ? ORDER BY id", this::data, classId);
  }

  /** Liefert die ID eines Schülers anhand der ID des Chips */
  public Optional<Long> findByUUID(String uuid) {
    return jdbc.query(
        "SELECT id FROM student WHERE rfid_uid = ?",
        rs -> rs.next() ? Optional.of(rs.getLong("id")) : Optional.empty(),
        uuid);
  }

  /** Lädt die Daten eines vorhandenen Schülers ohne Kartenkennung */
  public StudentData findById(Long studentId) {
    return jdbc.query(STUDENT_COLUMNS + " WHERE id = ?", this::data, studentId).getFirst();
  }

  /** Liefert die Klassen-ID des Schülers. Bei lock=true wird sein Datensatz zusätzlich gesperrt */
  public Optional<Long> findClassId(Long studentId, boolean lock) {
    return jdbc
        .queryForList(
            "SELECT school_class_id FROM student WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
            Long.class,
            studentId)
        .stream()
        .findFirst();
  }

  /** Speichert einen neuen Schüler mit Verweis auf die angegebene Klasse */
  public void insert(Student student, Long classId) {
    student.setSchoolClass(entityManager.getReference(SchoolClass.class, classId));
    entityManager.persist(student);
  }

  /** Ändert Namen und Geburtsdatum des Schülers */
  public void update(Long studentId, String firstName, String lastName, LocalDate birthDate) {
    jdbc.update(
        "UPDATE student SET first_name = ?, last_name = ?, birth_date = ? WHERE id = ?",
        firstName,
        lastName,
        birthDate,
        studentId);
  }

  /** Löscht den Schüler, sofern keine Datenbankverweise die Löschung verhindern */
  public void delete(Long studentId) {
    jdbc.update("DELETE FROM student WHERE id = ?", studentId);
  }

  /** Setzt oder entfernt die Kartenkennung und liefert die Zahl betroffener Zeilen */
  public int updateUid(Long studentId, String uid) {
    return jdbc.update("UPDATE student SET rfid_uid = ? WHERE id = ?", uid, studentId);
  }

  /** Liest Stammdaten, Klassen-ID und beide Fehlminutenkonten aus dem SQL-Abfrageergebnis */
  private StudentData data(ResultSet row, int index) throws SQLException {
    return new StudentData(
        row.getLong("id"),
        row.getString("first_name"),
        row.getString("last_name"),
        row.getDate("birth_date").toLocalDate(),
        row.getLong("school_class_id"),
        row.getInt("unexcused_minutes_account"),
        row.getInt("excused_minutes_account"));
  }

  /** Enthält Schülerstammdaten, Klasse und Minutenkonten ohne Kartenkennung */
  public record StudentData(
      Long id,
      String firstName,
      String lastName,
      LocalDate birthDate,
      Long classId,
      int unexcusedMinutes,
      int excusedMinutes) {}
}
