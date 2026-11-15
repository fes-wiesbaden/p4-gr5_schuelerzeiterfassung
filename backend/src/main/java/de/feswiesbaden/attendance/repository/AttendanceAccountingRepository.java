package de.feswiesbaden.attendance.repository;

import de.feswiesbaden.attendance.enums.AttendanceStatus;
import de.feswiesbaden.attendance.enums.AuditEventType;
import de.feswiesbaden.attendance.enums.Weekday;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AttendanceAccountingRepository {
  private final JdbcTemplate jdbc;

  public AttendanceAccountingRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Account> lockAccounts(Long classId) {
    return jdbc.query(
        "SELECT id, unexcused_minutes_account, excused_minutes_account, created_at"
            + " FROM student WHERE school_class_id = ? ORDER BY id FOR UPDATE",
        (row, index) ->
            new Account(
                row.getLong("id"),
                row.getInt("unexcused_minutes_account"),
                row.getInt("excused_minutes_account"),
                row.getTimestamp("created_at").toLocalDateTime()),
        classId);
  }

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

  public List<Audit> findAudits(Long studentId) {
    return jdbc.query(
        "SELECT id, attendance_id, deletion_date_id, event_type, occurred_at, new_status,"
            + " unexcused_minutes_delta, excused_minutes_delta"
            + " FROM attendance_audit WHERE student_id = ? ORDER BY occurred_at, id FOR UPDATE",
        (row, index) ->
            new Audit(
                row.getLong("id"),
                row.getObject("attendance_id", Long.class),
                row.getLong("deletion_date_id"),
                AuditEventType.valueOf(row.getString("event_type")),
                row.getTimestamp("occurred_at").toLocalDateTime(),
                row.getString("new_status") == null
                    ? null
                    : AttendanceStatus.valueOf(row.getString("new_status")),
                row.getInt("unexcused_minutes_delta"),
                row.getInt("excused_minutes_delta")),
        studentId);
  }

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

  public record Account(Long id, int unexcused, int excused, LocalDateTime createdAt) {}

  public record Period(Long planId, LocalDate startsOn, LocalDate endsOn) {}

  public record Slot(DayOfWeek weekday, LocalTime startsAt, LocalTime endsAt) {}

  public record DayAttendance(Long id, LocalDate date, AttendanceStatus status) {}

  public record Audit(
      Long id,
      Long attendanceId,
      Long deletionDateId,
      AuditEventType type,
      LocalDateTime occurredAt,
      AttendanceStatus status,
      int unexcusedDelta,
      int excusedDelta) {}
}
