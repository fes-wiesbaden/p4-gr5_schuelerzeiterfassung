package de.feswiesbaden.terminal.scanner;

import de.feswiesbaden.terminal.model.Json;
import java.util.Optional;

/** Erkennt Scanmeldungen des ESP32 und verwirft andere Zeilen */
public final class SerialLine {

  private SerialLine() {}

  public record Reading(String rfidUid, int terminalNumber) {}

  /** Liest UID und Terminalnummer aus einer JSON-Zeile und verwirft unlesbare oder leere Scans */
  public static Optional<Reading> parse(String zeile) {
    if (zeile == null || zeile.isBlank()) {
      return Optional.empty();
    }

    try {
      Reading gelesen = Json.mapper().readValue(zeile.trim(), Reading.class);
      if (gelesen.rfidUid() == null || gelesen.rfidUid().isBlank()) {
        return Optional.empty();
      }
      return Optional.of(gelesen);
    } catch (Exception keinScan) {
      // Absichtlich keine Ausgabe: sonst stünde die Zeile samt UID im Log.
      return Optional.empty();
    }
  }
}
