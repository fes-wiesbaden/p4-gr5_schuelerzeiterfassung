package de.feswiesbaden.attendance.service;

import de.feswiesbaden.attendance.repository.RetentionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Löscht Rohscans und Anwesenheitsdaten nach Ablauf ihrer Aufbewahrungsfrist */
@Service
public class RetentionService {
  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

  private final RetentionRepository retention;

  private final Clock clock;

  public RetentionService(RetentionRepository retention, Clock clock) {
    this.retention = retention;
    this.clock = clock;
  }

  /** Löscht Rohscans, deren Empfang mindestens 14 Tage zurückliegt, und liefert deren Anzahl */
  @Transactional
  public int deleteRawScans() {
    LocalDateTime cutoff =
        LocalDateTime.ofInstant(clock.instant().minus(Duration.ofDays(14)), ZoneOffset.UTC);
    return retention.deleteRawScansThrough(cutoff);
  }

  /**
   * Löscht fällige Änderungsprotokolle und die darüber ermittelten Tagesanwesenheiten. Konten,
   * Stammdaten und Löschdatumszeilen bleiben bestehen. Der Rückgabewert zählt gelöschte Protokolle,
   * einschließlich Kontenresets ohne Tagesanwesenheit
   */
  @Transactional
  public int deleteHistory() {
    LocalDate today = LocalDate.now(clock.withZone(BERLIN));
    retention.lockDueDeletionDates(today);
    var attendanceIds = retention.findDueAttendanceIds(today);
    int audits = retention.deleteDueAudits(today);
    retention.deleteDuePlannedStatuses(today);
    for (Long id : attendanceIds) {
      retention.deleteAttendance(id);
    }
    return audits;
  }

  /** Entfernt beim Nachholen eines Resets fällige Resetprotokolle in derselben Transaktion */
  void deleteDueAccountResets(Long classId, Long deletionDateId, LocalDate today) {
    retention.deleteDueAccountResets(classId, deletionDateId, today);
  }
}
