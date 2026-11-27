package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.enums.AuditEventType;
import de.feswiesbaden.attendance.repository.LogRepository;
import de.feswiesbaden.attendance.repository.LogRepository.AuditEntry;
import de.feswiesbaden.attendance.repository.LogRepository.Page;
import de.feswiesbaden.attendance.repository.LogRepository.RawScanEntry;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stellt Rohscans allen Mitarbeitern und das Änderungsprotokoll nur Administratoren bereit */
@Service
public class LogService {
  private static final int MAX_PAGE_SIZE = 100;

  private final LogRepository logs;

  private final StaffAuthorization authorization;

  public LogService(LogRepository logs, StaffAuthorization authorization) {
    this.logs = logs;
    this.authorization = authorization;
  }

  /** Filter-, Sortier- und Seitenangaben einer Logabfrage */
  public record LogQuery(
      Instant from, Instant to, String search, String sort, String direction, int page, int size) {}

  /** Liefert Rohscans für Lehrkräfte und Administratoren */
  @Transactional(readOnly = true)
  public Page<RawScanEntry> rawScans(LogQuery query) {
    authorization.currentStaff();
    validate(query);
    return logs.findRawScans(
        query.from(),
        query.to(),
        query.search(),
        column(LogRepository.RAW_SCAN_SORT, query.sort()),
        ascending(query.direction()),
        query.size(),
        query.page());
  }

  /** Liefert das Änderungsprotokoll ausschließlich für Administratoren */
  @Transactional(readOnly = true)
  public Page<AuditEntry> audits(LogQuery query, String eventType) {
    authorization.requireAdministrator();
    validate(query);
    if (eventType != null) {
      try {
        AuditEventType.valueOf(eventType);
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException("INVALID_LOG_QUERY");
      }
    }
    return logs.findAudits(
        query.from(),
        query.to(),
        eventType,
        query.search(),
        column(LogRepository.AUDIT_SORT, query.sort()),
        ascending(query.direction()),
        query.size(),
        query.page());
  }

  private static void validate(LogQuery query) {
    if (query.from() == null
        || query.to() == null
        || !query.from().isBefore(query.to())
        || query.page() < 0
        || query.size() < 1
        || query.size() > MAX_PAGE_SIZE
        || (query.search() != null && query.search().length() > 100)) {
      throw new IllegalArgumentException("INVALID_LOG_QUERY");
    }
  }

  private static String column(Map<String, String> allowed, String sort) {
    var column = allowed.get(sort);
    if (column == null) {
      throw new IllegalArgumentException("INVALID_LOG_QUERY");
    }
    return column;
  }

  private static boolean ascending(String direction) {
    return switch (direction) {
      case "asc" -> true;
      case "desc" -> false;
      default -> throw new IllegalArgumentException("INVALID_LOG_QUERY");
    };
  }
}
