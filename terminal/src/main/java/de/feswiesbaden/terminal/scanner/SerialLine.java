package de.feswiesbaden.terminal.scanner;

import de.feswiesbaden.terminal.model.Json;
import java.util.Optional;

/** Erkennt Scanmeldungen des ESP32 und verwirft andere Zeilen */
public final class SerialLine {

  private SerialLine() {}

  /** Enthält die übertragene Kartenkennung und Terminalnummer, noch ohne Scan-ID oder Zeitpunkt */
  public record Reading(String rfidUid, int terminalNumber) {}

  /**
   * Liest Kartenkennung und Terminalnummer aus JSON und verwirft unlesbare Zeilen oder leere
   * Kennungen. Das UID-Format und die Gültigkeit der Terminalnummer werden hier nicht geprüft
   */
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
      // Absichtlich keine Ausgabe: sonst stünde die Zeile samt UID im Log
      return Optional.empty();
    }
  }
}
