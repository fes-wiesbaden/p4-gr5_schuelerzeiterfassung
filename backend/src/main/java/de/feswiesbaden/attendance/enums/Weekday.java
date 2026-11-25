package de.feswiesbaden.attendance.enums;

import java.time.DayOfWeek;

/** Definiert die Unterrichtswochentage von Montag bis Freitag */
public enum Weekday {
  MONTAG,
  DIENSTAG,
  MITTWOCH,
  DONNERSTAG,
  FREITAG;

  /** Liefert den Unterrichtswochentag zu einem Kalenderwochentag von Montag bis Freitag */
  public static Weekday of(DayOfWeek day) {
    return values()[day.getValue() - 1];
  }
}
