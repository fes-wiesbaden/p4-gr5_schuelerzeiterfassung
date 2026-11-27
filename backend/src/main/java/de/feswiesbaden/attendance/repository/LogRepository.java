package de.feswiesbaden.attendance.repository;

import de.feswiesbaden.attendance.enums.AuditEventType;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Liest Rohscans und Änderungsprotokoll gefiltert, sortiert und seitenweise */
@Repository
public class LogRepository {
  /** Erlaubte Sortierschlüssel, damit kein Anfragewert ins SQL gelangt */
  private static final Map<String, String> RAW_SCAN_SORT =
      Map.of(
          "scannedAt", "r.scanned_at",
          "rfidUid", "r.rfid_uid",
          "student", "student_name",
          "classCode", "c.class_code");

  private static final Map<String, String> AUDIT_SORT =
      Map.of(
          "occurredAt", "a.occurred_at",
          "eventType", "a.event_type",
          "student", "student_name",
          "actor", "actor");

  // "Nachname, Vorname Nachname" findet beide Schreibweisen mit einem einzigen LIKE
  private static final String STUDENT_SEARCH =
      "CONCAT(s.last_name, ', ', s.first_name, ' ', s.last_name)";

  private static final String ACTOR =
      """
      COALESCE(CONCAT(st.last_name, ', ', st.first_name, ' (', st.username, ')'),
               CONCAT('Terminal ', a.terminal_id, ' (Raum ', rm.room_number, ')'), 'System')
      """;

  private static final String RAW_SCAN_FROM =
      """
       FROM raw_scan r
       LEFT JOIN student s ON s.rfid_uid = r.rfid_uid
       LEFT JOIN school_class c ON c.id = s.school_class_id
       WHERE r.scanned_at >= ? AND r.scanned_at < ?
         AND (? IS NULL OR CONCAT_WS(' | ', r.rfid_uid, %s, c.class_code) LIKE ?)
      """
          .formatted(STUDENT_SEARCH);

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
         AND (? IS NULL OR CONCAT_WS(' | ', %s, c.class_code, %s,
                                     CONCAT(st.first_name, ' ', st.last_name)) LIKE ?)
      """
          .formatted(STUDENT_SEARCH, ACTOR);

  private final JdbcTemplate jdbc;

  public LogRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Filter-, Sortier- und Seitenangaben einer Logabfrage */
  public record LogQuery(
      Instant from, Instant to, String search, String sort, boolean ascending, int page, int size) {
    /** Der Suchbegriff kann eine UID sein und bleibt deshalb aus technischen Logs heraus */
    @Override
    public String toString() {
      return "LogQuery[sort=" + sort + ", page=" + page + "]";
    }
  }

  /** Rohscan mit dem Schüler, dem die Karte aktuell zugeordnet ist */
  public record RawScanEntry(
      String scanId, Instant scannedAt, String rfidUid, String student, String classCode) {
    /** Hält UID und Schülerdaten aus der Textdarstellung für technische Logs heraus */
    @Override
    public String toString() {
      return "RawScanEntry[scanId=" + scanId + "]";
    }
  }

  /** Protokolleintrag mit auslösender Lehrkraft, auslösendem Terminal oder System */
  public record AuditEntry(
      Long id,
      Instant occurredAt,
      String eventType,
      String student,
      String classCode,
      String actor,
      String oldStatus,
      String newStatus,
      int unexcusedMinutesDelta,
      int excusedMinutesDelta) {
    /** Hält Schüler- und Mitarbeiterdaten aus der Textdarstellung für technische Logs heraus */
    @Override
    public String toString() {
      return "AuditEntry[id=" + id + "]";
    }
  }

  /** Eine Seite der Ergebnisse und die Gesamtzahl aller Treffer */
  public record Page<T>(List<T> items, long total) {}

  /**
   * Liefert Rohscans im Zeitraum. Suchbegriff und UID werden über den Setter gebunden, damit Spring
   * sie nicht im JDBC-Trace ausgibt
   */
  public Page<RawScanEntry> findRawScans(LogQuery query) {
    var like = like(query.search());
    var params = Arrays.<Object>asList(utc(query.from()), utc(query.to()), like, like);
    var items =
        jdbc.query(
            "SELECT r.scan_id, r.scanned_at, r.rfid_uid, c.class_code,"
                + " CONCAT(s.last_name, ', ', s.first_name) AS student_name"
                + RAW_SCAN_FROM
                + order(RAW_SCAN_SORT, query, "r.scan_id"),
            statement -> bind(statement, params, query),
            (row, index) ->
                new RawScanEntry(
                    row.getString("scan_id"),
                    instant(row.getObject("scanned_at", LocalDateTime.class)),
                    row.getString("rfid_uid"),
                    row.getString("student_name"),
                    row.getString("class_code")));
    return new Page<>(items, count(RAW_SCAN_FROM, params));
  }

  /** Liefert Protokolleinträge im Zeitraum, optional auf einen Ereignistyp beschränkt */
  public Page<AuditEntry> findAudits(LogQuery query, AuditEventType eventType) {
    var type = eventType == null ? null : eventType.name();
    var like = like(query.search());
    var params = Arrays.<Object>asList(utc(query.from()), utc(query.to()), type, type, like, like);
    var items =
        jdbc.query(
            """
            SELECT a.id, a.occurred_at, a.event_type, a.old_status, a.new_status,
                   a.unexcused_minutes_delta, a.excused_minutes_delta, c.class_code,
                   CONCAT(s.last_name, ', ', s.first_name) AS student_name,
            """
                + ACTOR
                + " AS actor"
                + AUDIT_FROM
                + order(AUDIT_SORT, query, "a.id"),
            statement -> bind(statement, params, query),
            (row, index) ->
                new AuditEntry(
                    row.getLong("id"),
                    instant(row.getObject("occurred_at", LocalDateTime.class)),
                    row.getString("event_type"),
                    row.getString("student_name"),
                    row.getString("class_code"),
                    row.getString("actor"),
                    row.getString("old_status"),
                    row.getString("new_status"),
                    row.getInt("unexcused_minutes_delta"),
                    row.getInt("excused_minutes_delta")));
    return new Page<>(items, count(AUDIT_FROM, params));
  }

  private long count(String from, List<Object> params) {
    return jdbc.query(
        "SELECT COUNT(*)" + from,
        statement -> setParams(statement, params),
        rs -> {
          rs.next();
          return rs.getLong(1);
        });
  }

  private static void bind(PreparedStatement statement, List<Object> params, LogQuery query)
      throws SQLException {
    setParams(statement, params);
    statement.setInt(params.size() + 1, query.size());
    statement.setLong(params.size() + 2, (long) query.page() * query.size());
  }

  private static void setParams(PreparedStatement statement, List<Object> params)
      throws SQLException {
    for (int i = 0; i < params.size(); i++) {
      statement.setObject(i + 1, params.get(i));
    }
  }

  /** Die Spalte stammt aus der Whitelist, die Richtung ist fest */
  private static String order(Map<String, String> allowed, LogQuery query, String tieBreaker) {
    var column = allowed.get(query.sort());
    if (column == null) {
      throw new IllegalArgumentException("Unknown sort key");
    }
    var direction = query.ascending() ? " ASC" : " DESC";
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
