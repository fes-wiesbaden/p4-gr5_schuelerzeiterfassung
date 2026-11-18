package de.feswiesbaden.attendance.repository;

import de.feswiesbaden.attendance.enums.AttendanceStatus;
import de.feswiesbaden.attendance.enums.AuditEventType;
import de.feswiesbaden.attendance.enums.Weekday;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Liest und speichert Tagesanwesenheiten, Fehlminutenkonten und Änderungsprotokolle.
 *
 * <p>Die aufrufenden Services bilden die Transaktionsgrenze. Datenbanksperren bleiben bis zum Ende
 * dieser Transaktion bestehen. Konten werden durch Sperren des Schülerdatensatzes geschützt
 */
@Repository
public class AttendanceAccountingRepository {
  private final JdbcTemplate jdbc;

  public AttendanceAccountingRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Lädt die Fehlminutenkonten einer Klasse und sperrt die Schülerdatensätze in aufsteigender
   * ID-Reihenfolge
   */
  public List<Account> lockAccounts(Long classId) {
    return jdbc.query(
        "SELECT id, unexcused_minutes_account, excused_minutes_account, created_at"
            + " FROM student WHERE school_class_id = ? ORDER BY id FOR UPDATE",
        this::account,
        classId);
  }

  /** Lädt die Fehlminutenkonten und sperrt den Schüler, sofern er zur angegebenen Klasse gehört */
  public Optional<Account> lockAccount(Long studentId, Long classId) {
    return jdbc
        .query(
            "SELECT id, unexcused_minutes_account, excused_minutes_account, created_at"
                + " FROM student WHERE id = ? AND school_class_id = ? FOR UPDATE",
            this::account,
            studentId,
            classId)
        .stream()
        .findFirst();
  }

  /**
   * Liefert Klassen-IDs, deren Blockplan begonnen hat und dessen Löschdatum noch nicht erreicht ist
   */
  public List<Long> findClassesToClose(LocalDate today) {
    return jdbc.queryForList(
        "SELECT c.id FROM school_class c JOIN block_plan p ON p.id = c.block_plan_id"
            + " WHERE p.starts_on <= ? AND DATE_ADD(p.ends_on, INTERVAL 6 MONTH) > ? ORDER BY c.id",
        Long.class,
        today,
        today);
  }

  /**
   * Prüft einen Slot des angegebenen Stundenplans im Terminalraum zur Berliner Ortszeit. Der Beginn
   * zählt zum Slot, das Ende nicht. Die Blockzuordnung wird hier nicht geprüft
   */
  public boolean matchesTerminalSlot(
      Long timetableId, Long terminalId, LocalDate date, LocalTime time) {
    return !jdbc.queryForList(
            "SELECT s.id FROM timetable_slot s JOIN terminal t ON t.room_id = s.room_id"
                + " WHERE s.timetable_id = ? AND t.terminal_id = ? AND s.weekday = ?"
                + " AND s.start_time <= ? AND s.end_time > ? FOR SHARE",
            Long.class,
            timetableId,
            terminalId,
            Weekday.values()[date.getDayOfWeek().getValue() - 1].name(),
            time,
            time)
        .isEmpty();
  }

  /** Lädt die zugeordneten Blockzeiträume der Klasse mit Lesesperre */
  public List<Period> findPeriods(Long classId) {
    return jdbc.query(
        "SELECT b.block_plan_id, b.starts_on, b.ends_on FROM block_assignment b"
            + " JOIN class_block_assignment c ON c.block_assignment_id = b.id WHERE c.school_class_id = ? FOR SHARE",
        (row, index) ->
            new Period(
                row.getLong("block_plan_id"),
                row.getDate("starts_on").toLocalDate(),
                row.getDate("ends_on").toLocalDate()),
        classId);
  }

  /**
   * Lädt alle Slots mit positiver Dauer aus dem Stundenplan, nach Beginn sortiert und mit
   * Lesesperre
   */
  public List<Slot> findSlots(Long timetableId) {
    return jdbc.query(
        "SELECT weekday, start_time, end_time FROM timetable_slot"
            + " WHERE timetable_id = ? AND start_time < end_time ORDER BY start_time, id FOR SHARE",
        (row, index) ->
            new Slot(
                DayOfWeek.of(Weekday.valueOf(row.getString("weekday")).ordinal() + 1),
                row.getTime("start_time").toLocalTime(),
                row.getTime("end_time").toLocalTime()),
        timetableId);
  }

  /** Lädt und sperrt alle Tagesanwesenheiten eines Schülers, nach Datum sortiert */
  public List<DayAttendance> findAttendances(Long studentId) {
    return jdbc.query(
        "SELECT id, attendance_date, status FROM attendance WHERE student_id = ? ORDER BY attendance_date FOR UPDATE",
        (row, index) ->
            new DayAttendance(
                row.getLong("id"),
                row.getDate("attendance_date").toLocalDate(),
                AttendanceStatus.valueOf(row.getString("status"))),
        studentId);
  }

  /** Lädt und sperrt alle Änderungsprotokolle eines Schülers, nach Ereigniszeit und ID sortiert */
  public List<Audit> findAudits(Long studentId) {
    return jdbc.query(
        "SELECT id, attendance_id, deletion_date_id, event_type, occurred_at, old_status, new_status,"
            + " unexcused_minutes_delta, excused_minutes_delta"
            + " FROM attendance_audit WHERE student_id = ? ORDER BY occurred_at, id FOR UPDATE",
        (row, index) ->
            new Audit(
                row.getLong("id"),
                row.getObject("attendance_id", Long.class),
                row.getLong("deletion_date_id"),
                AuditEventType.valueOf(row.getString("event_type")),
                row.getObject("occurred_at", LocalDateTime.class),
                row.getString("new_status") == null
                    ? null
                    : AttendanceStatus.valueOf(row.getString("new_status")),
                row.getInt("unexcused_minutes_delta"),
                row.getInt("excused_minutes_delta"),
                row.getString("old_status") == null
                    ? null
                    : AttendanceStatus.valueOf(row.getString("old_status"))),
        studentId);
  }

  /** Legt eine Tagesanwesenheit an und liefert ihre erzeugte ID */
  public Long insertAttendance(
      Long studentId, LocalDate date, AttendanceStatus status, LocalDateTime now) {
    var key = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var insert =
              connection.prepareStatement(
                  "INSERT INTO attendance (student_id, attendance_date, status, created_at, changed_at)"
                      + " VALUES (?, ?, ?, ?, ?)",
                  Statement.RETURN_GENERATED_KEYS);
          insert.setLong(1, studentId);
          insert.setObject(2, date);
          insert.setString(3, status.name());
          insert.setObject(4, now);
          insert.setObject(5, now);
          return insert;
        },
        key);
    return key.getKey().longValue();
  }

  /**
   * Fügt ein Änderungsprotokoll ein und speichert die übergebenen Fehlminutenkonten und den
   * Tagesstatus
   */
  public void book(
      Account account,
      Long attendanceId,
      AttendanceStatus status,
      Audit audit,
      Long staffId,
      Long terminalId,
      LocalDateTime now) {
    jdbc.update(
        "INSERT INTO attendance_audit (student_id, attendance_id, deletion_date_id, event_type,"
            + " changed_by_staff_id, terminal_id, old_status, new_status, occurred_at,"
            + " unexcused_minutes_delta, excused_minutes_delta, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        account.id(),
        attendanceId,
        audit.deletionDateId(),
        audit.type().name(),
        staffId,
        terminalId,
        audit.oldStatus() == null ? null : audit.oldStatus().name(),
        audit.status() == null ? null : audit.status().name(),
        audit.occurredAt(),
        audit.unexcusedDelta(),
        audit.excusedDelta(),
        now);
    jdbc.update(
        "UPDATE student SET unexcused_minutes_account = ?, excused_minutes_account = ? WHERE id = ?",
        account.unexcused(),
        account.excused(),
        account.id());
    jdbc.update(
        "UPDATE attendance SET status = ?, changed_at = ? WHERE id = ?",
        status.name(),
        now,
        attendanceId);
  }

  /** Liest Schüler-ID, Fehlminutenkonten und Erstellungszeitpunkt aus dem SQL-Abfrageergebnis */
  private Account account(ResultSet row, int index) throws SQLException {
    return new Account(
        row.getLong("id"),
        row.getInt("unexcused_minutes_account"),
        row.getInt("excused_minutes_account"),
        row.getObject("created_at", LocalDateTime.class));
  }

  /**
   * Setzt beide Fehlminutenkonten auf null und protokolliert die abgezogenen Minuten. Auch bei
   * bereits leeren Konten entsteht ein ACCOUNT_RESET-Protokoll ohne Tagesanwesenheit
   */
  public void reset(Account account, Long actorId, Long deletionDateId, LocalDateTime now) {
    jdbc.update(
        "INSERT INTO attendance_audit (student_id, deletion_date_id, event_type, changed_by_staff_id,"
            + " occurred_at, unexcused_minutes_delta, excused_minutes_delta)"
            + " VALUES (?, ?, 'ACCOUNT_RESET', ?, ?, ?, ?)",
        account.id(),
        deletionDateId,
        actorId,
        now,
        -account.unexcused(),
        -account.excused());
    jdbc.update(
        "UPDATE student SET unexcused_minutes_account = 0, excused_minutes_account = 0 WHERE id = ?",
        account.id());
  }

  /**
   * Enthält Schüler-ID, beide Fehlminutenkonten und den Erstellungszeitpunkt des Schülerdatensatzes
   */
  public record Account(Long id, int unexcused, int excused, LocalDateTime createdAt) {}

  /** Enthält einen zugeordneten Blockzeitraum mit seiner Blockplan-ID */
  public record Period(Long planId, LocalDate startsOn, LocalDate endsOn) {}

  /** Enthält Wochentag, Beginn und Ende einer geplanten Unterrichtszeit */
  public record Slot(DayOfWeek weekday, LocalTime startsAt, LocalTime endsAt) {}

  /** Enthält ID, Datum und aktuellen Status einer Tagesanwesenheit */
  public record DayAttendance(Long id, LocalDate date, AttendanceStatus status) {}

  /**
   * Enthält ein Änderungsprotokoll mit UTC-Ereigniszeit, optionalem Statuswechsel und
   * Minutenänderungen. Positive Minutenwerte erhöhen das jeweilige Fehlminutenkonto, negative
   * verringern es
   */
  public record Audit(
      Long id,
      Long attendanceId,
      Long deletionDateId,
      AuditEventType type,
      LocalDateTime occurredAt,
      AttendanceStatus status,
      int unexcusedDelta,
      int excusedDelta,
      AttendanceStatus oldStatus) {}
}
