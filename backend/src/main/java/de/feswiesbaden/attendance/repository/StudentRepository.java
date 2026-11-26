package de.feswiesbaden.attendance.repository;

import de.feswiesbaden.attendance.entities.SchoolClass;
import de.feswiesbaden.attendance.entities.Student;
import jakarta.persistence.EntityManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Liest und ändert Schülerdaten. Bindet Kartenkennungen direkt, damit Spring sie nicht im
 * JDBC-Trace ausgibt
 */
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

  /** Liefert sämtliche Schüler der für den Mitarbeiter sichtbaren Klassen */
  public List<StudentData> findAccessible(Long staffId, boolean administrator) {
    return jdbc.query(
        STUDENT_COLUMNS
            + " WHERE school_class_id IN (SELECT c.id FROM school_class c WHERE "
            + SchoolClassRepository.CLASS_ACCESS
            + ") ORDER BY last_name, first_name, id",
        this::data,
        administrator,
        staffId,
        staffId);
  }

  /** Liefert die Schüler-ID zur RFID-Kennung */
  public Optional<Long> findIdByRfidUid(String rfidUid) {
    return jdbc.query(
        "SELECT id FROM student WHERE rfid_uid = ?",
        statement -> statement.setString(1, rfidUid),
        rs -> rs.next() ? Optional.of(rs.getLong("id")) : Optional.empty());
  }

  /** Liefert die Zuordnungen vorhandener Karten mit Schülername und Klassenkürzel */
  public List<RfidAssignment> findRfidAssignments() {
    return jdbc.query(
        """
        SELECT CONCAT(s.last_name, ', ', s.first_name) AS student_name, c.class_code, s.rfid_uid
        FROM student s
        JOIN school_class c ON c.id = s.school_class_id
        WHERE s.rfid_uid IS NOT NULL
        ORDER BY c.class_code, s.last_name, s.first_name, s.id
        """,
        (row, index) ->
            new RfidAssignment(
                row.getString("student_name"),
                row.getString("class_code"),
                row.getString("rfid_uid")));
  }

  /** Liest Namen und Anlagezeitpunkt der Schüler für die Anwesenheitsliste */
  public List<AttendanceStudent> findAttendanceStudents(Long classId) {
    return jdbc.query(
        "SELECT id, first_name, last_name, created_at FROM student WHERE school_class_id = ? ORDER BY id",
        (row, index) ->
            new AttendanceStudent(
                row.getLong("id"),
                row.getString("first_name"),
                row.getString("last_name"),
                row.getObject("created_at", LocalDateTime.class)),
        classId);
  }

  /** Liefert den Anlagezeitpunkt für die Abgrenzung der Anwesenheitshistorie */
  public Optional<LocalDateTime> findCreatedAt(Long studentId, Long classId) {
    return jdbc.query(
        "SELECT created_at FROM student WHERE id = ? AND school_class_id = ?",
        rows ->
            rows.next() ? Optional.of(rows.getObject(1, LocalDateTime.class)) : Optional.empty(),
        studentId,
        classId);
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
    return jdbc.update(
        "UPDATE student SET rfid_uid = ? WHERE id = ?",
        statement -> {
          statement.setString(1, uid);
          statement.setObject(2, studentId);
        });
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

  /** Enthält die Schülerdaten für die Zuordnung zur Tagesanwesenheit */
  public record AttendanceStudent(
      Long id, String firstName, String lastName, LocalDateTime createdAt) {}

  /** Enthält die sichtbaren Felder der Kartenzuordnung für Lehrkräfte */
  public record RfidAssignment(String studentName, String classCode, String rfidUid) {
    /** Hält Schülerdaten und UID aus der Textdarstellung für technische Logs heraus */
    @Override
    public String toString() {
      return "RfidAssignment";
    }
  }
}
