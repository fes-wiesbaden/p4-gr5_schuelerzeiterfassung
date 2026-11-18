package de.feswiesbaden.attendance.config;

import de.feswiesbaden.attendance.service.RetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Startet die geplante Löschung abgelaufener Scans und Anwesenheitsdaten */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "attendance.retention.scheduling.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RetentionJobs {
  private static final Logger LOG = LoggerFactory.getLogger(RetentionJobs.class);

  private final RetentionService retention;

  public RetentionJobs(RetentionService retention) {
    this.retention = retention;
  }

  /** Löscht stündlich alte Rohscans und protokolliert das Ergebnis */
  @Scheduled(cron = "0 0 * * * *", zone = "UTC")
  public void deleteRawScans() {
    try {
      LOG.info("RAW_SCAN_RETENTION deleted={}", retention.deleteRawScans());
    } catch (RuntimeException failure) {
      LOG.error("RAW_SCAN_RETENTION_FAILED");
    }
  }

  /** Löscht um Berliner Mitternacht fällige Anwesenheitsdaten und Änderungsprotokolle */
  @Scheduled(cron = "0 0 0 * * *", zone = "Europe/Berlin")
  public void deleteHistory() {
    try {
      LOG.info("HISTORY_RETENTION deletedAudits={}", retention.deleteHistory());
    } catch (RuntimeException failure) {
      LOG.error("HISTORY_RETENTION_FAILED");
    }
  }
}
