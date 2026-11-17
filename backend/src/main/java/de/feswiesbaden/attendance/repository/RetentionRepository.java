package de.feswiesbaden.attendance.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Löscht alte Rohscans sowie fällige Änderungsprotokolle und deren Tagesanwesenheiten */
@Repository
public class RetentionRepository {
  private final JdbcTemplate jdbc;

  public RetentionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Löscht Rohscans bis einschließlich der angegebenen Empfangszeit und liefert deren Anzahl */
  public int deleteRawScansThrough(LocalDateTime cutoff) {
    return jdbc.update("DELETE FROM raw_scan WHERE created_at <= ?", cutoff);
  }

  /** Sperrt alle bis zum angegebenen Tag fälligen Löschfristen */
  public void lockDueDeletionDates(LocalDate today) {
    // Neue Buchungen sperren dieselben Fristen. Die Löschung darf sich nicht mit ihnen
    // überschneiden
    jdbc.queryForList(
        "SELECT id FROM deletion_date WHERE delete_after <= ? FOR UPDATE", Long.class, today);
  }

  /** Liefert die unterschiedlichen Anwesenheits-IDs aus fälligen Protokollen */
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

  /** Löscht fällige Protokolle und liefert deren Anzahl */
  public int deleteDueAudits(LocalDate today) {
    return jdbc.update(
        """
        DELETE FROM attendance_audit WHERE deletion_date_id IN
          (SELECT id FROM deletion_date WHERE delete_after <= ?)
        """,
        today);
  }

  /** Löscht die Anwesenheit nach dem Entfernen ihrer Protokollverweise */
  public void deleteAttendance(Long attendanceId) {
    jdbc.update("DELETE FROM attendance WHERE id = ?", attendanceId);
  }
}
