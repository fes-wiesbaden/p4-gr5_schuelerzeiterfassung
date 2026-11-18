package de.feswiesbaden.attendance.config;

import de.feswiesbaden.attendance.service.AttendanceAccountingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Startet den Tagesabschluss beim Anwendungsstart und täglich um 00:15 Uhr Berliner Zeit */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "attendance.accounting.scheduling.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AttendanceJobs {
  private static final Logger LOG = LoggerFactory.getLogger(AttendanceJobs.class);

  private final AttendanceAccountingService accounting;

  public AttendanceJobs(AttendanceAccountingService accounting) {
    this.accounting = accounting;
  }

  /** Schließt fällige Tage ab und protokolliert Fehler ohne personenbezogene Details */
  @EventListener(ApplicationReadyEvent.class)
  @Scheduled(cron = "0 15 0 * * *", zone = "Europe/Berlin")
  public void closeDays() {
    try {
      LOG.info("DAILY_CLOSE changedDays={}", accounting.closeDueDays());
    } catch (RuntimeException failure) {
      LOG.error("DAILY_CLOSE_FAILED");
    }
  }
}
