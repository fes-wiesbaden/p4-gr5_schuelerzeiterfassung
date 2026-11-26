package de.feswiesbaden.attendance.enums;

/** Unterscheidet die Arten von Ereignissen im Änderungsprotokoll */
public enum AuditEventType {
  SCAN,
  STATUS_CHANGE,
  DAILY_CLOSE,
  ACCOUNT_RESET,
  MANUAL_ABSENCE_MINUTES,
  TIMED_EXCUSAL
}
