package de.feswiesbaden.attendance.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetentionService {
  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

  private final JdbcTemplate jdbc;

  private final Clock clock;

  public RetentionService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional
  public int deleteRawScans() {
    LocalDateTime cutoff =
        LocalDateTime.ofInstant(clock.instant().minus(Duration.ofDays(14)), ZoneOffset.UTC);
    return jdbc.update("DELETE FROM raw_scan WHERE created_at <= ?", cutoff);
  }

  @Transactional
  public int deleteHistory() {
    LocalDate today = LocalDate.now(clock.withZone(BERLIN));
    // Referenzierte Fristen sperren, damit parallel angelegte Audits die Löschung nicht umgehen
    jdbc.queryForList(
        "SELECT id FROM deletion_date WHERE delete_after <= ? FOR UPDATE", Long.class, today);
    List<Long> attendanceIds =
        jdbc.queryForList(
            """
            SELECT DISTINCT a.attendance_id FROM attendance_audit a
            JOIN deletion_date d ON d.id = a.deletion_date_id
            WHERE d.delete_after <= ? AND a.attendance_id IS NOT NULL
            """,
            Long.class,
            today);
    int audits =
        jdbc.update(
            """
            DELETE FROM attendance_audit WHERE deletion_date_id IN
              (SELECT id FROM deletion_date WHERE delete_after <= ?)
            """,
            today);
    for (Long id : attendanceIds) {
      jdbc.update("DELETE FROM attendance WHERE id = ?", id);
    }
    return audits;
  }
}
