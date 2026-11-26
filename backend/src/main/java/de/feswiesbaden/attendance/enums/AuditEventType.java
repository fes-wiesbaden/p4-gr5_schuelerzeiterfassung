package de.feswiesbaden.attendance.enums;

/** Unterscheidet Scan, Statuskorrektur, Tagesabschluss und Kontenreset im Protokoll */
public enum AuditEventType {
  SCAN,
  STATUS_CHANGE,
  DAILY_CLOSE,
  ACCOUNT_RESET,
  MANUAL_ABSENCE_MINUTES
}
