package de.feswiesbaden.terminal.model;

import java.time.Instant;
import java.util.UUID;

// Die Scan-ID bleibt bei Wiederholungen gleich, damit der Server denselben Scan
// nicht zweimal verarbeitet (#26).
public record Scan(String scanId, String rfidUid, int terminalNumber, Instant capturedAt) {

  public static Scan of(String rfidUid, int terminalNumber) {
    return new Scan(UUID.randomUUID().toString(), rfidUid, terminalNumber, Instant.now());
  }
}
