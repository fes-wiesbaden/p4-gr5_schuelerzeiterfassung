package de.feswiesbaden.attendance.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Scanauftrag im JSON-Format des JavaFX-Terminals */
public record RawScanDTO(
    @NotNull UUID scanId,
    @NotBlank @Size(max = 64) String rfidUid,
    @NotNull Integer terminalNumber,
    @NotNull Instant capturedAt) {

  private static final Instant EARLIEST_STORABLE = Instant.parse("1000-01-01T00:00:00Z");

  private static final Instant FIRST_UNSTORABLE = Instant.parse("+10000-01-01T00:00:00Z");

  /** Begrenzt den UTC-Zeitpunkt auf den Wertebereich der Rohscan-Tabelle */
  public RawScanDTO {
    if (capturedAt != null
        && (capturedAt.isBefore(EARLIEST_STORABLE) || !capturedAt.isBefore(FIRST_UNSTORABLE))) {
      throw new IllegalArgumentException("SCAN_REJECTED");
    }
  }

  /** Hält die UID aus technischen Logs heraus und erhält die Scan-ID zur Zuordnung */
  @Override
  public String toString() {
    return "RawScanDTO[scanId=" + scanId + "]";
  }
}
