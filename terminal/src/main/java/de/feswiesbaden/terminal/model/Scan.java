package de.feswiesbaden.terminal.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Enthält die einmal erzeugte Scan-ID, Kartenkennung, Terminalnummer und UTC-Scanzeit. Bei jedem
 * Zustellversuch werden dieselbe ID und derselbe Zeitpunkt gesendet, damit das Backend
 * Wiederholungen erkennen kann
 */
public record Scan(String scanId, String rfidUid, int terminalNumber, Instant capturedAt) {

  /** Erzeugt eine UUIDv4 und erfasst die aktuelle UTC-Scanzeit auf ganze Sekunden gekürzt */
  public static Scan of(String rfidUid, int terminalNumber) {
    return new Scan(
        UUID.randomUUID().toString(),
        rfidUid,
        terminalNumber,
        Instant.now().truncatedTo(ChronoUnit.SECONDS));
  }
}
