package de.feswiesbaden.attendance.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RetentionRepository {
  private final JdbcTemplate jdbc;

  public RetentionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public int deleteRawScansThrough(LocalDateTime cutoff) {
    return jdbc.update("DELETE FROM raw_scan WHERE created_at <= ?", cutoff);
  }

  public void lockDueDeletionDates(LocalDate today) {
    // Referenzierte Fristen sperren, damit parallel angelegte Audits die Löschung nicht umgehen.
    jdbc.queryForList(
        "SELECT id FROM deletion_date WHERE delete_after <= ? FOR UPDATE", Long.class, today);
  }

  public List<Long> findDueAttendanceIds(LocalDate today) {
    return jdbc.queryForList(
        """
        SELECT DISTINCT a.attendance_id FROM attendance_audit a
        JOIN deletion_date d ON d.id = a.deletion_date_id
        WHERE d.delete_after <= ? AND a.attendance_id IS NOT NULL
        """,
        Long.class,
        today);
  }

  public int deleteDueAudits(LocalDate today) {
    return jdbc.update(
        """
        DELETE FROM attendance_audit WHERE deletion_date_id IN
          (SELECT id FROM deletion_date WHERE delete_after <= ?)
        """,
        today);
  }

  public void deleteAttendance(Long attendanceId) {
    jdbc.update("DELETE FROM attendance WHERE id = ?", attendanceId);
  }
}
