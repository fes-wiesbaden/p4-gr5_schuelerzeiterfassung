package de.feswiesbaden.terminal.scanner;

import de.feswiesbaden.terminal.model.Json;
import java.util.Optional;

// Erwartet genau eine JSON-Zeile vom ESP32 (#29):
//     {"rfidUid":"04A3B2C1","terminalNumber":3}
// Alles andere wird verworfen, sonst ginge die Startmeldung als Scan durch.
public final class SerialLine {

  private SerialLine() {}

  public record Reading(String rfidUid, int terminalNumber) {}

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
