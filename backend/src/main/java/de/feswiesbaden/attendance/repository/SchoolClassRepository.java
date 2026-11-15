package de.feswiesbaden.attendance.repository;

import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

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

  public Optional<Long> lockClassTeacher(Long classId) {
    return jdbc
        .queryForList(
            "SELECT class_teacher_id FROM school_class WHERE id = ? FOR UPDATE",
            Long.class,
            classId)
        .stream()
        .findFirst();
  }

  public boolean hasTeacher(Long staffId) {
    return !jdbc.queryForList(
            "SELECT id FROM staff WHERE id = ? AND role = 'LEHRKRAFT'", Long.class, staffId)
        .isEmpty();
  }

  public boolean hasBlockPlan(Long planId) {
    return !jdbc.queryForList("SELECT id FROM block_plan WHERE id = ?", Long.class, planId)
        .isEmpty();
  }

  public boolean hasTimetable(Long timetableId) {
    return !jdbc.queryForList("SELECT id FROM timetable WHERE id = ?", Long.class, timetableId)
        .isEmpty();
  }

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

  public record ClassData(
      Long id, String classCode, Long classTeacherId, Long blockPlanId, Long timetableId) {}
}
