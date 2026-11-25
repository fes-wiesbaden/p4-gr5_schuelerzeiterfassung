package de.feswiesbaden.attendance.enums;

import java.time.DayOfWeek;

/** Definiert die Unterrichtswochentage von Montag bis Freitag */
public enum Weekday {
  MONTAG,
  DIENSTAG,
  MITTWOCH,
  DONNERSTAG,
  FREITAG;

  /** Liefert den Kalenderwochentag */
  public DayOfWeek dayOfWeek() {
    return DayOfWeek.of(ordinal() + 1);
  }
}
