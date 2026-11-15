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

  public List<StudentData> findByClassId(Long classId) {
    return jdbc.query(
        STUDENT_COLUMNS + " WHERE school_class_id = ? ORDER BY id", this::data, classId);
  }

  public StudentData findById(Long studentId) {
    return jdbc.query(STUDENT_COLUMNS + " WHERE id = ?", this::data, studentId).getFirst();
  }

  public Optional<Long> findClassId(Long studentId, boolean lock) {
    return jdbc
        .queryForList(
            "SELECT school_class_id FROM student WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
            Long.class,
            studentId)
        .stream()
        .findFirst();
  }

  public void insert(Student student, Long classId) {
    student.setSchoolClass(entityManager.getReference(SchoolClass.class, classId));
    entityManager.persist(student);
  }

  public void update(Long studentId, String firstName, String lastName, LocalDate birthDate) {
    jdbc.update(
        "UPDATE student SET first_name = ?, last_name = ?, birth_date = ? WHERE id = ?",
        firstName,
        lastName,
        birthDate,
        studentId);
  }

  public void delete(Long studentId) {
    jdbc.update("DELETE FROM student WHERE id = ?", studentId);
  }

  public int updateUid(Long studentId, String uid) {
    return jdbc.update("UPDATE student SET rfid_uid = ? WHERE id = ?", uid, studentId);
  }

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

  public record StudentData(
      Long id,
      String firstName,
      String lastName,
      LocalDate birthDate,
      Long classId,
      int unexcusedMinutes,
      int excusedMinutes) {}
}
