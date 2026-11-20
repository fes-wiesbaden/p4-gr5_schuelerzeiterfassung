package de.feswiesbaden.attendance.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * Bildet den vom JavaFX-Terminal gesendeten Scan ab. Die JSON-Feldnamen terminalNumber und
 * capturedAt entsprechen dem Terminal-seitigen Scan-Record; intern heissen die Felder terminalId
 * und scannedAt, wie an den uebrigen Stellen im Backend
 */
public record RawScanDTO(
    UUID scanId,
    String rfidUid,
    @JsonProperty("terminalNumber") Long terminalId,
    @JsonProperty("capturedAt") Instant scannedAt) {}
