package de.feswiesbaden.attendance.service;

/**
 * Meldet einen abgelehnten Stundenplan-Import mit Fehlercode und der Position der ersten
 * fehlerhaften Unterrichtszeit. Ohne Position betrifft der Fehler den Plan als Ganzes
 */
public class TimetableImportException extends RuntimeException {
  private final Integer slotIndex;

  public TimetableImportException(String code, Integer slotIndex) {
    super(code);
    this.slotIndex = slotIndex;
  }

  public Integer slotIndex() {
    return slotIndex;
  }
}
