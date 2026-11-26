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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
    return findAccount(studentId, classId, true);
  }

  public Optional<Account> findAccount(Long studentId, Long classId, boolean lock) {
    return jdbc
        .query(
            "SELECT id, unexcused_minutes_account, excused_minutes_account, created_at"
                + " FROM student WHERE id = ? AND school_class_id = ?"
                + (lock ? " FOR UPDATE" : ""),
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

  /** Lädt die zugeordneten Blockzeiträume der Klasse mit Lesesperre */
  public List<Period> findPeriods(Long classId) {
    return findPeriods(classId, true);
  }

  /** Lädt die Blockzeiträume der Klasse und verwendet bei Bedarf eine Lesesperre */
  public List<Period> findPeriods(Long classId, boolean lock) {
    return jdbc.query(
        "SELECT b.block_plan_id, b.starts_on, b.ends_on FROM block_assignment b"
            + " JOIN class_block_assignment c ON c.block_assignment_id = b.id WHERE c.school_class_id = ?"
            + (lock ? " FOR SHARE" : ""),
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
    return findSlots(timetableId, true);
  }

  /** Lädt die Unterrichtszeiten nach Beginn sortiert und verwendet bei Bedarf eine Lesesperre */
  public List<Slot> findSlots(Long timetableId, boolean lock) {
    return jdbc.query(
        "SELECT weekday, start_time, end_time, room_id, valid_from, valid_until"
            + " FROM timetable_slot"
            + " WHERE timetable_id = ? AND start_time < end_time ORDER BY start_time, id"
            + (lock ? " FOR SHARE" : ""),
        (row, index) ->
            new Slot(
                Weekday.valueOf(row.getString("weekday")).dayOfWeek(),
                row.getTime("start_time").toLocalTime(),
                row.getTime("end_time").toLocalTime(),
                row.getLong("room_id"),
                row.getObject("valid_from", LocalDate.class),
                row.getObject("valid_until", LocalDate.class)),
        timetableId);
  }

  /**
   * Prüft, ob am angegebenen Tag eine andere Klasse denselben Raum in einem überlappenden Zeitraum
   * belegt. Nur Klassen zählen, deren aktueller Blockplan und ein zugeordneter Block dieses Plans
   * den Tag einschließen. Slots ohne Dauer belegen den Raum nicht
   */
  public boolean roomDoubleBooked(
      long excludeClassId, long roomId, LocalTime startsAt, LocalTime endsAt, LocalDate date) {
    return jdbc.queryForObject(
        """
        SELECT EXISTS(SELECT 1 FROM school_class c
        JOIN timetable_slot s ON s.timetable_id = c.timetable_id
        JOIN class_block_assignment cba ON cba.school_class_id = c.id
        JOIN block_assignment b ON b.id = cba.block_assignment_id
        JOIN block_plan p ON p.id = c.block_plan_id
        WHERE c.id <> ? AND s.room_id = ? AND s.weekday = ?
          AND s.start_time < s.end_time AND s.start_time < ? AND s.end_time > ?
          AND (s.valid_from IS NULL OR s.valid_from <= ?)
          AND (s.valid_until IS NULL OR s.valid_until > ?)
          AND b.block_plan_id = c.block_plan_id AND b.starts_on <= ? AND b.ends_on >= ?
          AND p.starts_on <= ? AND p.ends_on >= ?)
        """,
        Boolean.class,
        excludeClassId,
        roomId,
        Weekday.values()[date.getDayOfWeek().ordinal()].name(),
        endsAt,
        startsAt,
        date,
        date,
        date,
        date,
        date,
        date);
  }

  /** Lädt die Tagesanwesenheiten eines Schülers, bei Bedarf mit Sperre */
  public List<DayAttendance> findAttendances(Long studentId, boolean lock) {
    return jdbc.query(
        "SELECT id, attendance_date, status, full_absence, full_unexcused_minutes, full_excused_minutes"
            + " FROM attendance WHERE student_id = ? ORDER BY attendance_date"
            + (lock ? " FOR UPDATE" : ""),
        (row, index) ->
            new DayAttendance(
                row.getLong("id"),
                row.getDate("attendance_date").toLocalDate(),
                AttendanceStatus.valueOf(row.getString("status")),
                new FullDay(
                    nullableStatus(row.getString("full_absence")),
                    row.getInt("full_unexcused_minutes"),
                    row.getInt("full_excused_minutes"))),
        studentId);
  }

  /** Lädt die Änderungsprotokolle eines Schülers, bei Bedarf mit Sperre */
  public List<Audit> findAudits(Long studentId, boolean lock) {
    return jdbc.query(
        "SELECT id, attendance_id, deletion_date_id, event_type, occurred_at,"
            + " excusal_ends_at, excusal_status, excusal_reason, old_status, new_status,"
            + " unexcused_minutes_delta, excused_minutes_delta"
            + " FROM attendance_audit WHERE student_id = ? ORDER BY occurred_at, id"
            + (lock ? " FOR UPDATE" : ""),
        (row, index) -> audit(row),
        studentId);
  }

  /** Liest die noch aufbewahrten Audits eines Klassentags nach Schüler gruppiert */
  public Map<Long, List<Audit>> findDayAudits(Long classId, LocalDate date, LocalDate today) {
    return jdbc.query(
        """
        SELECT h.id, h.student_id, h.attendance_id, h.deletion_date_id, h.event_type, h.occurred_at,
               h.excusal_ends_at, h.excusal_status, h.excusal_reason, h.old_status, h.new_status,
               h.unexcused_minutes_delta, h.excused_minutes_delta
        FROM attendance_audit h
        JOIN attendance a ON a.id = h.attendance_id AND a.student_id = h.student_id
        JOIN student s ON s.id = a.student_id
        JOIN deletion_date d ON d.id = h.deletion_date_id
        WHERE s.school_class_id = ? AND a.attendance_date = ? AND d.delete_after > ?
        """,
        rows -> {
          Map<Long, List<Audit>> audits = new HashMap<>();
          while (rows.next()) {
            audits
                .computeIfAbsent(rows.getLong("student_id"), id -> new ArrayList<>())
                .add(audit(rows));
          }
          return audits;
        },
        classId,
        date,
        today);
  }

  /** Liest die aufbewahrten Tagesaudits eines Schülers aus dem gewählten Datumsbereich */
  public Map<LocalDate, List<Audit>> findStudentDayAudits(
      Long studentId, LocalDate from, LocalDate through, LocalDate today) {
    return jdbc.query(
        """
        SELECT h.id, h.attendance_id, h.deletion_date_id, h.event_type, h.occurred_at,
               h.excusal_ends_at, h.excusal_status, h.excusal_reason, h.old_status, h.new_status,
               h.unexcused_minutes_delta, h.excused_minutes_delta,
               a.attendance_date
        FROM attendance_audit h
        JOIN attendance a ON a.id = h.attendance_id AND a.student_id = h.student_id
        JOIN deletion_date d ON d.id = h.deletion_date_id
        WHERE h.student_id = ? AND a.attendance_date BETWEEN ? AND ? AND d.delete_after > ?
        """,
        rows -> {
          Map<LocalDate, List<Audit>> audits = new HashMap<>();
          while (rows.next()) {
            audits
                .computeIfAbsent(
                    rows.getObject("attendance_date", LocalDate.class), date -> new ArrayList<>())
                .add(audit(rows));
          }
          return audits;
        },
        studentId,
        from,
        through,
        today);
  }

  /** Liest bereits abgerechnete ganze Fehltage aus aufbewahrten Anwesenheiten. */
  public Map<LocalDate, AttendanceStatus> findFullAbsences(
      Long studentId, LocalDate from, LocalDate through, LocalDate today) {
    return jdbc.query(
        """
        SELECT a.attendance_date, a.full_absence
        FROM attendance a
        WHERE a.student_id = ? AND a.attendance_date BETWEEN ? AND ? AND a.full_absence IS NOT NULL
          AND EXISTS (SELECT 1 FROM attendance_audit h
                      JOIN deletion_date d ON d.id = h.deletion_date_id
                      WHERE h.attendance_id = a.id AND d.delete_after > ?)
        """,
        rows -> {
          Map<LocalDate, AttendanceStatus> days = new HashMap<>();
          while (rows.next()) {
            days.put(
                rows.getObject("attendance_date", LocalDate.class),
                AttendanceStatus.valueOf(rows.getString("full_absence")));
          }
          return days;
        },
        studentId,
        from,
        through,
        today);
  }

  /** Liefert den Zeitpunkt des letzten Kontenresets für die Anzeige der Korrektursperre */
  public Optional<LocalDateTime> latestResetAt(Long studentId) {
    return jdbc.query(
        """
        SELECT occurred_at FROM attendance_audit
        WHERE student_id = ? AND event_type = 'ACCOUNT_RESET'
        ORDER BY id DESC LIMIT 1
        """,
        rows ->
            rows.next() ? Optional.of(rows.getObject(1, LocalDateTime.class)) : Optional.empty(),
        studentId);
  }

  /** Liest ein Audit mit Statuswechsel und beiden Minutendeltas */
  private Audit audit(ResultSet row) throws SQLException {
    String oldStatus = row.getString("old_status");
    String newStatus = row.getString("new_status");
    return new Audit(
        row.getLong("id"),
        row.getObject("attendance_id", Long.class),
        row.getLong("deletion_date_id"),
        AuditEventType.valueOf(row.getString("event_type")),
        row.getObject("occurred_at", LocalDateTime.class),
        row.getObject("excusal_ends_at", LocalDateTime.class),
        nullableStatus(row.getString("excusal_status")),
        row.getString("excusal_reason"),
        newStatus == null ? null : AttendanceStatus.valueOf(newStatus),
        row.getInt("unexcused_minutes_delta"),
        row.getInt("excused_minutes_delta"),
        oldStatus == null ? null : AttendanceStatus.valueOf(oldStatus));
  }

  private static AttendanceStatus nullableStatus(String status) {
    return status == null ? null : AttendanceStatus.valueOf(status);
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

  /** Liest die aufbewahrten Vormerkungen eines Schülers für den angegebenen Zeitraum */
  public List<PlannedStatus> findPlannedStatuses(
      Long studentId, LocalDate from, LocalDate through, LocalDate today, boolean lock) {
    return jdbc.query(
        """
        SELECT p.student_id, p.attendance_date, p.starts_at, p.ends_at, p.status
        FROM planned_attendance_status p JOIN deletion_date d ON d.id = p.deletion_date_id
        WHERE p.student_id = ? AND p.attendance_date BETWEEN ? AND ? AND d.delete_after > ?
        ORDER BY p.starts_at
        """
            + (lock ? " FOR UPDATE" : ""),
        this::plannedStatus,
        studentId,
        from,
        through,
        today);
  }

  /** Liest die Vormerkungen aller Schüler eines Klassentags */
  public List<PlannedStatus> findClassPlannedStatuses(
      Long classId, LocalDate date, LocalDate today) {
    return jdbc.query(
        """
        SELECT p.student_id, p.attendance_date, p.starts_at, p.ends_at, p.status
        FROM planned_attendance_status p JOIN deletion_date d ON d.id = p.deletion_date_id
        JOIN student s ON s.id = p.student_id
        WHERE s.school_class_id = ? AND p.attendance_date = ? AND d.delete_after > ?
        ORDER BY p.starts_at
        """,
        this::plannedStatus,
        classId,
        date,
        today);
  }

  /** Protokolliert die Vormerkung mit ihrem Erstellungszeitpunkt, Akteur und Löschdatum */
  public void insertPlannedStatus(
      PlannedStatus period, Long staffId, Long deletionDateId, LocalDateTime now) {
    jdbc.update(
        """
        INSERT INTO planned_attendance_status
            (student_id, attendance_date, starts_at, ends_at, status, changed_by_staff_id,
             deletion_date_id, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        period.studentId(),
        period.date(),
        period.start(),
        period.end(),
        period.status().name(),
        staffId,
        deletionDateId,
        now);
  }

  private PlannedStatus plannedStatus(ResultSet row, int index) throws SQLException {
    return new PlannedStatus(
        row.getLong("student_id"),
        row.getObject("attendance_date", LocalDate.class),
        row.getObject("starts_at", LocalDateTime.class),
        row.getObject("ends_at", LocalDateTime.class),
        AttendanceStatus.valueOf(row.getString("status")));
  }

  /** Enthält den ausgewählten Berliner Tag und den Zeitraum als UTC-Zeit */
  public record PlannedStatus(
      Long studentId,
      LocalDate date,
      LocalDateTime start,
      LocalDateTime end,
      AttendanceStatus status) {}

  /** Prüft einen bereits verarbeiteten Buchungsauftrag der Tagesanwesenheit */
  public Optional<Integer> findManualBooking(Long attendanceId, UUID requestId) {
    return jdbc.query(
        "SELECT unexcused_minutes_delta FROM attendance_audit"
            + " WHERE attendance_id = ? AND request_id = ? AND event_type = 'MANUAL_ABSENCE_MINUTES' FOR UPDATE",
        rows -> rows.next() ? Optional.of(rows.getInt(1)) : Optional.empty(),
        attendanceId,
        requestId.toString());
  }

  /** Speichert die berechneten Fehlminutenkonten und den Tagesstatus */
  public void updateTotals(
      Account account, Long attendanceId, AttendanceStatus status, LocalDateTime now) {
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

  /** Übernimmt die Änderungen der Tageszählung getrennt vom unveränderten Minutenkonto. */
  public void updateFullDay(Long studentId, Long attendanceId, FullDay previous, FullDay current) {
    jdbc.update(
        """
        UPDATE student
        SET unexcused_days_account = unexcused_days_account + ?,
            excused_days_account = excused_days_account + ?,
            full_unexcused_minutes_account = full_unexcused_minutes_account + ?,
            full_excused_minutes_account = full_excused_minutes_account + ?
        WHERE id = ?
        """,
        current.unexcusedDays() - previous.unexcusedDays(),
        current.excusedDays() - previous.excusedDays(),
        current.unexcusedMinutes() - previous.unexcusedMinutes(),
        current.excusedMinutes() - previous.excusedMinutes(),
        studentId);
    jdbc.update(
        """
        UPDATE attendance
        SET full_absence = ?, full_unexcused_minutes = ?, full_excused_minutes = ?, changed_at = changed_at
        WHERE id = ?
        """,
        current.status() == null ? null : current.status().name(),
        current.unexcusedMinutes(),
        current.excusedMinutes(),
        attendanceId);
  }

  /** Fügt ein unveränderliches Änderungsprotokoll ein */
  public void insertAudit(
      Long studentId,
      Long attendanceId,
      Audit audit,
      Long staffId,
      Integer terminalId,
      LocalDateTime now,
      UUID requestId) {
    jdbc.update(
        "INSERT INTO attendance_audit (student_id, attendance_id, deletion_date_id, event_type,"
            + " changed_by_staff_id, terminal_id, old_status, new_status, occurred_at,"
            + " excusal_ends_at, excusal_status, excusal_reason,"
            + " unexcused_minutes_delta, excused_minutes_delta, created_at, request_id)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        studentId,
        attendanceId,
        audit.deletionDateId(),
        audit.type().name(),
        staffId,
        terminalId,
        audit.oldStatus() == null ? null : audit.oldStatus().name(),
        audit.status() == null ? null : audit.status().name(),
        audit.occurredAt(),
        audit.excusalEndsAt(),
        audit.excusalStatus() == null ? null : audit.excusalStatus().name(),
        audit.excusalReason(),
        audit.unexcusedDelta(),
        audit.excusedDelta(),
        now,
        requestId == null ? null : requestId.toString());
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
        "UPDATE student SET unexcused_minutes_account = 0, excused_minutes_account = 0,"
            + " unexcused_days_account = 0, excused_days_account = 0,"
            + " full_unexcused_minutes_account = 0, full_excused_minutes_account = 0 WHERE id = ?",
        account.id());
  }

  /**
   * Enthält Schüler-ID, beide Fehlminutenkonten und den Erstellungszeitpunkt des Schülerdatensatzes
   */
  public record Account(Long id, int unexcused, int excused, LocalDateTime createdAt) {}

  /** Enthält einen zugeordneten Blockzeitraum mit seiner Blockplan-ID */
  public record Period(Long planId, LocalDate startsOn, LocalDate endsOn) {}

  /** Enthält Wochentag, Beginn, Ende und Raum einer geplanten Unterrichtszeit */
  public record Slot(
      DayOfWeek weekday,
      LocalTime startsAt,
      LocalTime endsAt,
      long roomId,
      LocalDate validFrom,
      LocalDate validUntil) {
    /** Prüft Wochentag und Gültigkeit dieser Fassung für einen Kalendertag */
    public boolean occursOn(LocalDate date) {
      return weekday == date.getDayOfWeek()
          && (validFrom == null || !date.isBefore(validFrom))
          && (validUntil == null || date.isBefore(validUntil));
    }
  }

  /** Enthält ID, Datum und aktuellen Status einer Tagesanwesenheit */
  public record DayAttendance(Long id, LocalDate date, AttendanceStatus status, FullDay fullDay) {}

  /** Ein ganzer Fehltag und die Minuten, die bereits in seiner Tageszählung enthalten sind. */
  public record FullDay(AttendanceStatus status, int unexcusedMinutes, int excusedMinutes) {
    public static final FullDay NONE = new FullDay(null, 0, 0);

    public FullDay includingManualMinutes(long minutes) {
      return status == null || minutes == 0
          ? this
          : new FullDay(status, Math.toIntExact(unexcusedMinutes + minutes), excusedMinutes);
    }

    public int unexcusedDays() {
      return status == AttendanceStatus.ABWESEND ? 1 : 0;
    }

    public int excusedDays() {
      return status == AttendanceStatus.ENTSCHULDIGT ? 1 : 0;
    }
  }

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
      LocalDateTime excusalEndsAt,
      AttendanceStatus excusalStatus,
      String excusalReason,
      AttendanceStatus status,
      int unexcusedDelta,
      int excusedDelta,
      AttendanceStatus oldStatus) {
    public Audit(
        Long id,
        Long attendanceId,
        Long deletionDateId,
        AuditEventType type,
        LocalDateTime occurredAt,
        AttendanceStatus status,
        int unexcusedDelta,
        int excusedDelta,
        AttendanceStatus oldStatus) {
      this(
          id,
          attendanceId,
          deletionDateId,
          type,
          occurredAt,
          null,
          null,
          null,
          status,
          unexcusedDelta,
          excusedDelta,
          oldStatus);
    }
  }
}
