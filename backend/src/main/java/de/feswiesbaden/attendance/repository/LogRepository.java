package de.feswiesbaden.attendance.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Liest Rohscans und Änderungsprotokoll gefiltert, sortiert und seitenweise */
@Repository
public class LogRepository {
  /** Erlaubte Sortierschlüssel der Rohscans, damit kein Anfragewert ins SQL gelangt */
  public static final Map<String, String> RAW_SCAN_SORT =
      Map.of(
          "scannedAt", "r.scanned_at",
          "rfidUid", "r.rfid_uid",
          "student", "student_name",
          "classCode", "c.class_code");

  /** Erlaubte Sortierschlüssel des Änderungsprotokolls */
  public static final Map<String, String> AUDIT_SORT =
      Map.of(
          "occurredAt", "a.occurred_at",
          "eventType", "a.event_type",
          "student", "student_name",
          "actor", "actor_name");

  private static final String RAW_SCAN_FROM =
      """
       FROM raw_scan r
       LEFT JOIN student s ON s.rfid_uid = r.rfid_uid
       LEFT JOIN school_class c ON c.id = s.school_class_id
       WHERE r.scanned_at >= ? AND r.scanned_at < ?
         AND (? IS NULL OR r.rfid_uid LIKE ? OR CONCAT(s.last_name, ', ', s.first_name) LIKE ?
              OR c.class_code LIKE ?)
      """;

  private static final String AUDIT_FROM =
      """
       FROM attendance_audit a
       JOIN student s ON s.id = a.student_id
       JOIN school_class c ON c.id = s.school_class_id
       LEFT JOIN staff st ON st.id = a.changed_by_staff_id
       LEFT JOIN terminal t ON t.terminal_id = a.terminal_id
       LEFT JOIN room rm ON rm.id = t.room_id
       WHERE a.occurred_at >= ? AND a.occurred_at < ?
         AND (? IS NULL OR a.event_type = ?)
         AND (? IS NULL OR CONCAT(s.last_name, ', ', s.first_name) LIKE ?
              OR CONCAT(st.last_name, ', ', st.first_name) LIKE ? OR st.username LIKE ?
              OR c.class_code LIKE ?)
      """;

  private final JdbcTemplate jdbc;

  public LogRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Rohscan mit dem Schüler, dem die Karte aktuell zugeordnet ist */
  public record RawScanEntry(
      String scanId, Instant scannedAt, String rfidUid, String student, String classCode) {}

  /** Protokolleintrag mit auslösendem Mitarbeiter oder Terminal */
  public record AuditEntry(
      Long id,
      Instant occurredAt,
      String eventType,
      String student,
      String classCode,
      String staffName,
      String staffUsername,
      Integer terminalId,
      String roomNumber,
      String oldStatus,
      String newStatus,
      int unexcusedMinutesDelta,
      int excusedMinutesDelta) {}

  /** Eine Seite der Ergebnisse und die Gesamtzahl aller Treffer */
  public record Page<T>(List<T> items, long total) {}

  /**
   * Liefert Rohscans im Zeitraum. Suchbegriff und UID werden über den Setter gebunden, damit Spring
   * sie nicht im JDBC-Trace ausgibt
   */
  public Page<RawScanEntry> findRawScans(
      Instant from, Instant to, String search, String sortColumn, boolean asc, int size, int page) {
    var like = like(search);
    var params = new ArrayList<Object>(Arrays.asList(utc(from), utc(to), like, like, like, like));
    Long total = count(RAW_SCAN_FROM, params);
    var items =
        jdbc.query(
            "SELECT r.scan_id, r.scanned_at, r.rfid_uid, c.class_code,"
                + " CONCAT(s.last_name, ', ', s.first_name) AS student_name"
                + RAW_SCAN_FROM
                + order(sortColumn, asc, "r.scan_id"),
            statement -> bind(statement, params, size, page),
            (row, index) ->
                new RawScanEntry(
                    row.getString("scan_id"),
                    instant(row.getObject("scanned_at", LocalDateTime.class)),
                    row.getString("rfid_uid"),
                    row.getString("student_name"),
                    row.getString("class_code")));
    return new Page<>(items, total);
  }

  /** Liefert Protokolleinträge im Zeitraum, optional auf einen Ereignistyp beschränkt */
  public Page<AuditEntry> findAudits(
      Instant from,
      Instant to,
      String eventType,
      String search,
      String sortColumn,
      boolean asc,
      int size,
      int page) {
    var like = like(search);
    var params =
        new ArrayList<Object>(
            Arrays.asList(utc(from), utc(to), eventType, eventType, like, like, like, like, like));
    Long total = count(AUDIT_FROM, params);
    var items =
        jdbc.query(
            """
            SELECT a.id, a.occurred_at, a.event_type, a.old_status, a.new_status,
                   a.unexcused_minutes_delta, a.excused_minutes_delta, a.terminal_id,
                   c.class_code, rm.room_number, st.username,
                   CONCAT(s.last_name, ', ', s.first_name) AS student_name,
                   CONCAT(st.last_name, ', ', st.first_name) AS actor_name
            """
                + AUDIT_FROM
                + order(sortColumn, asc, "a.id"),
            statement -> bind(statement, params, size, page),
            (row, index) ->
                new AuditEntry(
                    row.getLong("id"),
                    instant(row.getObject("occurred_at", LocalDateTime.class)),
                    row.getString("event_type"),
                    row.getString("student_name"),
                    row.getString("class_code"),
                    row.getString("actor_name"),
                    row.getString("username"),
                    row.getObject("terminal_id", Integer.class),
                    row.getString("room_number"),
                    row.getString("old_status"),
                    row.getString("new_status"),
                    row.getInt("unexcused_minutes_delta"),
                    row.getInt("excused_minutes_delta")));
    return new Page<>(items, total);
  }

  private Long count(String from, List<Object> params) {
    return jdbc.query(
        "SELECT COUNT(*)" + from,
        statement -> setParams(statement, params),
        rs -> rs.next() ? rs.getLong(1) : 0L);
  }

  private static void bind(PreparedStatement statement, List<Object> params, int size, int page)
      throws SQLException {
    setParams(statement, params);
    statement.setInt(params.size() + 1, size);
    statement.setLong(params.size() + 2, (long) page * size);
  }

  private static void setParams(PreparedStatement statement, List<Object> params)
      throws SQLException {
    for (int i = 0; i < params.size(); i++) {
      statement.setObject(i + 1, params.get(i));
    }
  }

  /** Die Spalte stammt aus einer der Whitelists, die Richtung ist fest */
  private static String order(String column, boolean asc, String tieBreaker) {
    var direction = asc ? " ASC" : " DESC";
    return " ORDER BY " + column + direction + ", " + tieBreaker + direction + " LIMIT ? OFFSET ?";
  }

  private static String like(String search) {
    if (search == null || search.isBlank()) {
      return null;
    }
    var escaped = search.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  private static LocalDateTime utc(Instant at) {
    return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
  }

  private static Instant instant(LocalDateTime at) {
    return at.toInstant(ZoneOffset.UTC);
  }
}
