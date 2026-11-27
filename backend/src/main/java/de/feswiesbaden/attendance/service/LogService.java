package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.enums.AuditEventType;
import de.feswiesbaden.attendance.repository.LogRepository;
import de.feswiesbaden.attendance.repository.LogRepository.AuditEntry;
import de.feswiesbaden.attendance.repository.LogRepository.LogQuery;
import de.feswiesbaden.attendance.repository.LogRepository.Page;
import de.feswiesbaden.attendance.repository.LogRepository.RawScanEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stellt Rohscans allen Mitarbeitern und das Änderungsprotokoll nur Administratoren bereit */
@Service
public class LogService {
  private final LogRepository logs;

  private final StaffAuthorization authorization;

  public LogService(LogRepository logs, StaffAuthorization authorization) {
    this.logs = logs;
    this.authorization = authorization;
  }

  /** Liefert Rohscans aller Klassen für Lehrkräfte und Administratoren */
  @Transactional(readOnly = true)
  public Page<RawScanEntry> rawScans(LogQuery query) {
    authorization.currentStaff();
    return logs.findRawScans(query);
  }

  /** Liefert das Änderungsprotokoll ausschließlich für Administratoren */
  @Transactional(readOnly = true)
  public Page<AuditEntry> audits(LogQuery query, AuditEventType eventType) {
    authorization.requireAdministrator();
    return logs.findAudits(query, eventType);
  }
}
