package de.feswiesbaden.terminal.scanner;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

// Erwartet genau eine JSON-Zeile vom ESP32 (#29):
//     {"rfidUid":"04A3B2C1","terminalNumber":3}
// Alles andere wird verworfen, sonst ginge die Startmeldung als Scan durch.
public final class SerialLine {
  private static final ObjectMapper JSON = new ObjectMapper();

  private SerialLine() {}

  public record Reading(String rfidUid, int terminalNumber) {}

  public static Optional<Reading> parse(String zeile) {
    if (zeile == null || zeile.isBlank()) {
      return Optional.empty();
    }

    try {
      Reading gelesen = JSON.readValue(zeile.trim(), Reading.class);
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
